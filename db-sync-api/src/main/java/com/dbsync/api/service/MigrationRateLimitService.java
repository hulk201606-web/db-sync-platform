package com.dbsync.api.service;

import com.dbsync.api.dto.migration.RateLimitRequest;
import com.dbsync.api.dto.migration.RateLimitView;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.core.throttle.Throttle;
import com.dbsync.core.throttle.ThrottleRegistry;
import com.dbsync.metadata.dao.SyncTaskDao;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行中动态限速服务（MIG-05）。
 *
 * <p><b>两个动作，顺序不能反</b>：
 * <ol>
 *   <li><b>先落库</b> {@code sync_task.rate_limit_rows}——重启、换实例、下次启动都靠它；
 *       反过来的话"内存变了库没变"，进程一重启限速就悄悄丢了；</li>
 *   <li><b>再热更新</b>本实例持有的 {@link Throttle}——让正在跑的 worker 立刻变慢/变快。</li>
 * </ol>
 *
 * <p><b>{@code effective} 的含义</b>：只描述"本实例能否立即生效"。任务在别的实例上跑时，
 * 本实例注册表里没有对应的限速器，只能给出 {@code NEXT_START}——这是诚实的回答，
 * 而不是假装热更新了（跨实例热更新需要额外的广播通道，不在本期范围）。
 *
 * <p><b>为什么不写在 {@link MigrationService} 里</b>：那个类已承载
 * 预检/启动/暂停/恢复/取消/进度/核数 7 件事，再塞"限速 + 分片清单 + 表级进度"
 * 会变成上帝类，且它的构造参数会连带影响 3 个既有测试的装配。
 */
public class MigrationRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(MigrationRateLimitService.class);

    /**
     * 限速上限（行/秒）。
     *
     * <p>不是性能限制，而是<b>防手误</b>：1000 万行/秒已远超单机能力，
     * 多打几个 0（如 1e15）会让限速计算失去意义，且这种"天文数字"几乎必然是误操作，
     * 早拒绝比让它生效安全。
     */
    public static final long MAX_ROWS_PER_SEC = 100_000_000L;

    private final SyncTaskDao syncTaskDao;
    private final TaskService taskService;
    private final ThrottleRegistry throttleRegistry;

    /**
     * 构造。
     *
     * @param syncTaskDao      任务 DAO（落库 rate_limit_rows）
     * @param taskService      任务服务（存在性校验）
     * @param throttleRegistry 限速器注册表（热更新入口）
     */
    public MigrationRateLimitService(SyncTaskDao syncTaskDao, TaskService taskService,
                                     ThrottleRegistry throttleRegistry) {
        this.syncTaskDao = syncTaskDao;
        this.taskService = taskService;
        this.throttleRegistry = throttleRegistry;
    }

    /**
     * 调整任务限速。
     *
     * <p>校验全部在写库之前完成：非法值必须"抛 E1001 且不写库"，
     * 否则会留下"库里是脏值、内存没变"的分裂状态。
     *
     * @param taskId  任务 ID
     * @param request 请求（整体或分项，二选一，同时给以分项为准）
     * @return 结果（含是否已热生效）
     */
    public RateLimitView changeLimit(long taskId, RateLimitRequest request) {
        // ① 存在性校验（不存在直接 E3004，不落库）
        taskService.loadOrThrow(taskId);
        // ② 解析 + 校验（负数/超大值 → E1001，此步之前没有任何写操作）
        long[] limits = resolve(request);
        long read = limits[0];
        long write = limits[1];

        // ③ 先持久化：重启/换实例后按此值重建限速器
        long persisted = persistedLimit(read, write);
        if (syncTaskDao.updateRateLimit(taskId, persisted) == 0) {
            // 读到了任务但更新不到：被并发软删了
            throw new DbSyncException(ErrorCode.TASK_NOT_FOUND, taskId);
        }

        // ④ 再热更新：只有本实例正持有该任务的限速器时才能立即生效
        Optional<Throttle> live = throttleRegistry.find(taskId);
        if (live.isEmpty()) {
            log.info("限速已落库（任务未在本实例运行，下次启动生效） task={} persisted={} "
                    + "read={} write={}", taskId, persisted, read, write);
            return new RateLimitView(taskId, false, read, write, RateLimitView.NEXT_START);
        }
        Throttle throttle = live.get();
        throttle.updateLimits(read, write);
        log.info("限速已热更新 task={} persisted={} throttle={}", taskId, persisted, throttle);
        return new RateLimitView(taskId, true, throttle.readLimit(), throttle.writeLimit(),
                RateLimitView.IMMEDIATE);
    }

    /**
     * 解析并校验请求，返回 {读限速, 写限速}。
     *
     * <p>规则（写在接口文档里，避免前端猜）：
     * <ol>
     *   <li>给了分项（{@code readRowsPerSec} / {@code writeRowsPerSec}）→ 以分项为准，
     *       即使同时给了 {@code rowsPerSec} 也忽略后者；</li>
     *   <li>分项只给一侧 → 另一侧沿用已给的值（不归 0，否则漏传一个字段就把另一侧放开）；</li>
     *   <li>只给 {@code rowsPerSec} → 两侧同值；</li>
     *   <li>三个都没给 → E1001。</li>
     * </ol>
     *
     * @param request 请求（可为 null）
     * @return {readRowsPerSec, writeRowsPerSec}
     */
    private static long[] resolve(RateLimitRequest request) {
        if (request == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        Long rows = request.rowsPerSec();
        Long readReq = request.readRowsPerSec();
        Long writeReq = request.writeRowsPerSec();
        if (readReq != null || writeReq != null) {
            long base = readReq != null ? readReq.longValue() : writeReq.longValue();
            long read = readReq != null ? readReq.longValue() : base;
            long write = writeReq != null ? writeReq.longValue() : base;
            validate("readRowsPerSec", read);
            validate("writeRowsPerSec", write);
            return new long[] {read, write};
        }
        if (rows != null) {
            long both = rows.longValue();
            validate("rowsPerSec", both);
            return new long[] {both, both};
        }
        throw new DbSyncException(ErrorCode.PARAM_INVALID,
                "必须提供 rowsPerSec 或 readRowsPerSec/writeRowsPerSec 之一")
                .with("fields", "rowsPerSec|readRowsPerSec|writeRowsPerSec");
    }

    /**
     * 计算落库的单一限速值。
     *
     * <p>{@code sync_task.rate_limit_rows} 只有一个数，而本接口支持读写分设，
     * 因此重启后会按"两侧中较小的那个正数"生效——<b>偏保守</b>是刻意选择：
     * 迁移重启后宁可慢一点，也不要在重启瞬间把目标库压垮（目标库还要服务线上读）。
     * 两侧都是 0 时落 0（不限速）。
     *
     * @param read  读限速
     * @param write 写限速
     * @return 落库值
     */
    private static long persistedLimit(long read, long write) {
        long positiveRead = read > 0L ? read : Long.MAX_VALUE;
        long positiveWrite = write > 0L ? write : Long.MAX_VALUE;
        long min = Math.min(positiveRead, positiveWrite);
        return min == Long.MAX_VALUE ? 0L : min;
    }

    /**
     * 校验单个限速值（负数与天文数字都拒绝）。
     *
     * @param field 字段名（落错误上下文，前端可定位）
     * @param value 值
     */
    private static void validate(String field, long value) {
        if (value < 0L) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    field + " 不能为负数：" + value).with("field", field).with("value", value);
        }
        if (value > MAX_ROWS_PER_SEC) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    field + " 超出上限 " + MAX_ROWS_PER_SEC + "：" + value)
                    .with("field", field).with("value", value)
                    .with("max", MAX_ROWS_PER_SEC);
        }
    }
}
