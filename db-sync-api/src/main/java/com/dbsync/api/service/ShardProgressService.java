package com.dbsync.api.service;

import com.dbsync.api.dto.migration.ShardPageView;
import com.dbsync.api.dto.migration.ShardView;
import com.dbsync.api.dto.migration.TableProgressListView;
import com.dbsync.api.dto.migration.TableProgressView;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ShardRowSums;
import com.dbsync.common.model.ShardSnapshot;
import com.dbsync.common.model.TableMapping;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分片级 / 表级进度查询服务（MIG-06）。
 *
 * <p>任务级进度（{@link MigrationService#progress}）只回答"整体到哪儿了"，
 * 排障时必须能下钻到"哪张表、哪个分片卡住了"，因此补两级：
 * <ul>
 *   <li><b>表级</b>：按 {@code table_mapping_id} 聚合，一次 GROUP BY 出结果；</li>
 *   <li><b>分片级</b>：分页清单，含状态/租约/错误摘要，供热力图与定位死信分片。</li>
 * </ul>
 *
 * <p><b>性能取舍</b>：聚合在数据库里做（{@code GROUP BY}），不在 Java 里
 * "查全部分片再循环"。8 亿行表切 400 分片尚能接受，但分片数会随数据量继续涨，
 * 用"全量查回内存"的写法是在给未来埋线性劣化的坑。
 */
public class ShardProgressService {

    /** 分片清单默认页大小（一次 200 条足够画满一屏热力图）。 */
    public static final int DEFAULT_PAGE_SIZE = 200;

    /**
     * 分片清单页大小上限。
     *
     * <p>不设上限的话，一次 {@code size=100000} 会把整个任务的分片连同
     * {@code last_error}（1000 字符）全拉进内存，既是慢查询也是内存风险。
     */
    public static final int MAX_PAGE_SIZE = 1000;

    private final ShardDao shardDao;
    private final TableMappingDao tableMappingDao;
    private final TaskService taskService;

    /**
     * 构造。
     *
     * @param shardDao        分片 DAO
     * @param tableMappingDao 表映射 DAO（取源/目标表名）
     * @param taskService     任务服务（存在性校验，任务不存在给 E3004 而不是空列表）
     */
    public ShardProgressService(ShardDao shardDao, TableMappingDao tableMappingDao,
                                TaskService taskService) {
        this.shardDao = shardDao;
        this.tableMappingDao = tableMappingDao;
        this.taskService = taskService;
    }

    /**
     * 分片清单（分页 + 可选状态过滤）。
     *
     * <p>参数归一化（不抛错，静默纠正——看板接口不该因为前端传了 0 就 400）：
     * {@code page<=0 → 1}；{@code size<=0 → 200}；{@code size>1000 → 1000}。
     * 非法 {@code state} 则明确报 E1001：状态拼错时静默返回"全部"会让人误判。
     *
     * @param taskId 任务 ID
     * @param state  状态过滤（可空/空串表示全部）
     * @param page   页码（1 起）
     * @param size   页大小
     * @return 分页结果
     */
    public ShardPageView shards(long taskId, String state, int page, int size) {
        taskService.loadOrThrow(taskId);
        ShardState filter = parseState(state);
        int safePage = page <= 0 ? 1 : page;
        int safeSize = normalizeSize(size);
        long total = shardDao.countByTaskAndState(taskId, filter);
        int offset = (safePage - 1) * safeSize;
        List<ShardSnapshot> snapshots = total <= offset
                ? new ArrayList<ShardSnapshot>()
                : shardDao.findPageByTask(taskId, filter, offset, safeSize);
        List<ShardView> items = new ArrayList<>(snapshots.size());
        for (ShardSnapshot snapshot : snapshots) {
            items.add(ShardView.of(snapshot));
        }
        return new ShardPageView(total, safePage, safeSize, items);
    }

    /**
     * 按表进度（MIG-06 表级）。
     *
     * <p>两张聚合表都来自一次 GROUP BY 查询；映射列表本身决定输出顺序与表名，
     * 没有分片的表也会出现（各项 0）——否则前端无法区分"表还没切分片"和"表不存在"。
     *
     * <p><b>分母的兜底优先级</b>：
     * <ol>
     *   <li>分片实测 {@code source_rows}（{@code ShardWorker} 成功落库时写入），</li>
     *   <li>该表<b>所有</b>分片的 {@code source_rows} 都未知（聚合 ≤0）时，退回
     *       {@code sync_table_mapping.row_count_estimate}（建映射时采到的统计估算值），
     *       并把 {@code rowsFromEstimate} 置 true 让前端标注"约"；</li>
     *   <li>两者都没有 → {@code sourceRows ≤ 0}、{@code percent = null}（前端显示"—"）。</li>
     * </ol>
     *
     * <p>为什么不在"部分分片已知"时也混用估算值：分子分母口径不一致会算出
     * 超过 100% 的百分比（估算值通常偏小），比显示"—"更容易误导人。
     *
     * @param taskId 任务 ID
     * @return 各表进度
     */
    public TableProgressListView tablesProgress(long taskId) {
        taskService.loadOrThrow(taskId);
        Map<Long, Map<ShardState, Integer>> counts = shardDao.countByStateGroupByMapping(taskId);
        Map<Long, ShardRowSums> sums = shardDao.sumRowsGroupByMapping(taskId);
        List<TableProgressView> items = new ArrayList<>();
        for (TableMapping mapping : tableMappingDao.findByTask(taskId)) {
            Long key = Long.valueOf(mapping.getId());
            Map<ShardState, Integer> stateCounts = counts.get(key);
            int total = sum(stateCounts);
            int success = count(stateCounts, ShardState.SUCCESS);
            int failed = count(stateCounts, ShardState.FAILED);
            int dead = count(stateCounts, ShardState.DEAD);
            ShardRowSums rowSums = sums.get(key);
            long committedRows = rowSums == null ? 0L : rowSums.getCommittedRows();
            long sourceRows = rowSums == null ? 0L : rowSums.getSourceRows();
            boolean rowsFromEstimate = false;
            if (total > 0 && sourceRows <= 0L) {
                long estimate = mapping.getRowCountEstimate();
                if (estimate > 0L) {
                    sourceRows = estimate;
                    rowsFromEstimate = true;
                }
            }
            items.add(new TableProgressView(mapping.getId(), mapping.getSourceTable(),
                    mapping.getTargetTable(), total, success, failed, dead, committedRows,
                    sourceRows, TableProgressView.percentOf(committedRows, sourceRows),
                    rowsFromEstimate));
        }
        return new TableProgressListView(taskId, items);
    }

    /**
     * 解析状态过滤条件。
     *
     * @param state 状态名（可空/空串）
     * @return 状态；null 表示不过滤
     */
    private static ShardState parseState(String state) {
        if (state == null || state.trim().isEmpty()) {
            return null;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        try {
            return ShardState.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "未知的分片状态：" + state + "（可选 PENDING/CLAIMED/RUNNING/SUCCESS/FAILED"
                            + "/DEAD/PAUSED）").with("field", "state").with("value", state);
        }
    }

    /**
     * 归一化页大小（0/负数走默认，超上限截断）。
     *
     * @param size 入参
     * @return 实际页大小
     */
    private static int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 状态计数求和。
     *
     * @param counts 状态计数（可 null）
     * @return 合计
     */
    private static int sum(Map<ShardState, Integer> counts) {
        if (counts == null) {
            return 0;
        }
        int total = 0;
        for (Integer value : counts.values()) {
            if (value != null) {
                total += value.intValue();
            }
        }
        return total;
    }

    /**
     * 取某状态计数（缺省 0）。
     *
     * @param counts 状态计数（可 null）
     * @param state  状态
     * @return 数量
     */
    private static int count(Map<ShardState, Integer> counts, ShardState state) {
        if (counts == null) {
            return 0;
        }
        Integer value = counts.get(state);
        return value == null ? 0 : value.intValue();
    }
}
