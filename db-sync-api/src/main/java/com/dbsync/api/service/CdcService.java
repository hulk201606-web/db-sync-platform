package com.dbsync.api.service;

import com.dbsync.api.cdc.CdcRuntimeManager;
import com.dbsync.api.dto.cdc.CdcLatencyView;
import com.dbsync.api.dto.cdc.CdcModeRequest;
import com.dbsync.api.dto.cdc.CdcModeView;
import com.dbsync.api.dto.cdc.CdcRuntimeView;
import com.dbsync.api.dto.cdc.ChangeTraceView;
import com.dbsync.api.dto.cdc.TriggerInstallView;
import com.dbsync.cdc.TriggerInstaller;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.util.HashUtil;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.metadata.dao.ChangeLogDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 增量（CDC）服务（T32：模式配置 / 延迟 / 触发器 / 追溯）。
 *
 * <p>关键规则：
 * <ul>
 *   <li><b>RUNNING 不可切模式</b>（T32 验收 3）：运行中切模式会让已入队的变更含义错位
 *       （触发器日志与时间戳模式的 seq 语义不同），必须停任务再切；</li>
 *   <li><b>追溯走索引</b>：{@code pk} 先规范化为 pk_hash 候选再按
 *       {@code (task_id, table_mapping_id, pk_hash, seq)} 查询，命中 {@code idx_trace}；</li>
 *   <li><b>指标端口永不抛异常</b>：增量指标异常不能让大盘 500（{@link IncrementalMetricsPort} 约定）；</li>
 *   <li><b>消费会话跟着模式走</b>（CDC-02）：模式切换后对齐本实例的消费会话，
 *       运行态由 {@link #runtime(long)} 只读暴露（不在这里启停，避免读接口产生副作用）。</li>
 * </ul>
 */
public class CdcService {

    private static final Logger log = LoggerFactory.getLogger(CdcService.class);

    /** 追溯默认条数。 */
    private static final int TRACE_LIMIT = 20;
    /** 失败率窗口（与 Gate 的 15 分钟口径一致）。 */
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(15L);

    private final SyncTaskDao syncTaskDao;
    private final TableMappingDao tableMappingDao;
    private final ChangeLogDao changeLogDao;
    private final TriggerInstaller triggerInstaller;
    private final IncrementalMetricsPort metricsPort;
    private final TaskService taskService;
    private final CdcRuntimeManager cdcRuntime;

    /**
     * 构造。
     *
     * @param syncTaskDao      任务 DAO
     * @param tableMappingDao  表映射 DAO
     * @param changeLogDao     变更日志 DAO
     * @param triggerInstaller 触发器部署器
     * @param metricsPort      增量指标端口
     * @param taskService      任务服务
     * @param cdcRuntime       增量消费运行期（模式/状态变化时对齐消费会话）
     */
    public CdcService(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao,
                      ChangeLogDao changeLogDao, TriggerInstaller triggerInstaller,
                      IncrementalMetricsPort metricsPort, TaskService taskService,
                      CdcRuntimeManager cdcRuntime) {
        this.syncTaskDao = syncTaskDao;
        this.tableMappingDao = tableMappingDao;
        this.changeLogDao = changeLogDao;
        this.triggerInstaller = triggerInstaller;
        this.metricsPort = metricsPort;
        this.taskService = taskService;
        this.cdcRuntime = cdcRuntime;
    }

    /**
     * 切换增量模式。
     *
     * @param taskId 任务 ID
     * @param req    请求
     * @return 切换结果
     */
    public CdcModeView switchMode(long taskId, CdcModeRequest req) {
        SyncTask task = taskService.loadOrThrow(taskId);
        IncrementalMode target = parseMode(req == null ? null : req.mode());
        if (task.getState() == TaskState.RUNNING) {
            throw new DbSyncException(ErrorCode.TASK_STATE_INVALID,
                    "RUNNING 状态不可切换增量模式，请先暂停")
                    .with("state", task.getState().name());
        }
        if (task.getIncrementalMode() == target) {
            return new CdcModeView(taskId, target.name(), task.getIncrementalMode().name(), false);
        }
        syncTaskDao.updateIncrementalMode(taskId, target);
        // 模式语义变了，本实例的消费会话必须跟着对齐：
        // 任务在 RUNNING 时上面已拒绝切换，因此这里实际总是"停"；
        // 保留 start 分支是为了"将来允许运行中切模式"时不必再改一处（避免两个真相）。
        alignRuntime(taskId);
        log.info("增量模式已切换 task={} {} -> {} reason={}", taskId, task.getIncrementalMode(),
                target, req == null ? null : req.reason());
        return new CdcModeView(taskId, target.name(), task.getIncrementalMode().name(), true);
    }

    /**
     * 运行态视图（{@code GET /api/tasks/{taskId}/cdc/runtime}）。
     *
     * <p>返回字段（camelCase）：{@code taskId} / {@code running} / {@code consumerConcurrency} /
     * {@code startedAt}（ISO-8601，未运行为 null）/ {@code ownerInstance} / {@code lastError}。
     * {@code running} 表示<b>本实例</b>是否有该任务的消费会话；跨实例的判断请看水位线延迟。
     *
     * @param taskId 任务 ID
     * @return 运行态视图
     */
    public CdcRuntimeView runtime(long taskId) {
        taskService.loadOrThrow(taskId);
        return cdcRuntime.status(taskId);
    }

    /**
     * 让消费会话与"库里真实状态 + 当前模式"对齐。
     *
     * @param taskId 任务 ID
     */
    private void alignRuntime(long taskId) {
        SyncTask task = taskService.loadOrThrow(taskId);
        if (task.getState() == TaskState.RUNNING
                && CdcRuntimeManager.needsConsumer(task.getIncrementalMode())) {
            cdcRuntime.start(taskId);
        } else {
            cdcRuntime.stop(taskId);
        }
    }

    /**
     * 增量健康度。
     *
     * @param taskId 任务 ID
     * @return 指标视图
     */
    public CdcLatencyView latency(long taskId) {
        SyncTask task = taskService.loadOrThrow(taskId);
        long lag = safeLong(() -> metricsPort.latencyP95(taskId));
        long backlog = safeLong(() -> metricsPort.backlog(taskId));
        double failRate = safeDouble(() -> metricsPort.failRate(taskId, FAIL_WINDOW));
        Instant lastApply = null;
        try {
            lastApply = metricsPort.lastApplyAt(taskId);
        } catch (RuntimeException e) {
            log.debug("读取 lastApplyAt 失败 task={} err={}", taskId, e.toString());
        }
        return new CdcLatencyView(taskId, task.getIncrementalMode().name(), lag, backlog, failRate,
                lastApply == null ? null : lastApply.toString());
    }

    /**
     * 部署触发器（幂等）。
     *
     * @param taskId 任务 ID
     * @return 部署结果
     */
    public TriggerInstallView installTriggers(long taskId) {
        taskService.loadOrThrow(taskId);
        List<TableMapping> mappings = tableMappingDao.findEnabledByTask(taskId);
        if (mappings.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "任务没有启用的表映射，无法部署触发器")
                    .with("taskId", Long.valueOf(taskId));
        }
        List<String> tables = new ArrayList<>(mappings.size());
        for (TableMapping mapping : mappings) {
            triggerInstaller.install(taskId, mapping.getId());
            tables.add(mapping.getSourceTable());
        }
        log.info("触发器部署完成 task={} tables={}", taskId, tables.size());
        return new TriggerInstallView(taskId, tables.size(), tables);
    }

    /**
     * 卸载触发器（幂等）。
     *
     * @param taskId 任务 ID
     * @return 卸载结果
     */
    public TriggerInstallView uninstallTriggers(long taskId) {
        taskService.loadOrThrow(taskId);
        List<TableMapping> mappings = tableMappingDao.findByTask(taskId);
        List<String> tables = new ArrayList<>(mappings.size());
        for (TableMapping mapping : mappings) {
            triggerInstaller.uninstall(taskId, mapping.getId());
            tables.add(mapping.getSourceTable());
        }
        log.info("触发器已卸载 task={} tables={}", taskId, tables.size());
        return new TriggerInstallView(taskId, tables.size(), tables);
    }

    /**
     * 按主键追溯最近 20 条变更。
     *
     * @param taskId 任务 ID
     * @param table  表名（源表或目标表名，可空 → 该任务全部表）
     * @param pk     主键文本（单列主键；复合主键请用 {@code a|b} 形式）
     * @return 追溯结果
     */
    public ChangeTraceView trace(long taskId, String table, String pk) {
        taskService.loadOrThrow(taskId);
        if (pk == null || pk.isBlank()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "pk 不能为空");
        }
        List<TableMapping> mappings = tableMappingDao.findByTask(taskId);
        List<TableMapping> targets = new ArrayList<>();
        for (TableMapping mapping : mappings) {
            if (table == null || table.isBlank()
                    || mapping.getSourceTable().equalsIgnoreCase(table.trim())
                    || mapping.getTargetTable().equalsIgnoreCase(table.trim())) {
                targets.add(mapping);
            }
        }
        if (targets.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "表不存在于该任务：" + table);
        }
        List<Long> hashes = pkHashCandidates(pk.trim());
        List<Map<String, Object>> items = new ArrayList<>();
        for (TableMapping mapping : targets) {
            List<ChangeEvent> events = changeLogDao.trace(taskId, mapping.getId(), hashes,
                    TRACE_LIMIT);
            for (ChangeEvent event : events) {
                items.add(toItem(mapping, event));
            }
        }
        return new ChangeTraceView(taskId, table, pk, items.size(), items);
    }

    /**
     * 主键哈希候选。
     *
     * <p>同一逻辑主键在不同来源下的规范化结果可能不同（{@code "1"} / {@code 1L} /
     * {@code 1.00}），{@link HashUtil#pkHash} 对它们的规范形一致，但仍做多候选兜底，
     * 避免因上游写入类型差异导致查不到。
     *
     * @param pkText 主键文本
     * @return 去重后的候选哈希
     */
    static List<Long> pkHashCandidates(String pkText) {
        Set<Long> hashes = new LinkedHashSet<>();
        hashes.add(Long.valueOf(HashUtil.pkHash(List.<Object>of(pkText))));
        try {
            hashes.add(Long.valueOf(HashUtil.pkHash(List.<Object>of(Long.valueOf(pkText)))));
        } catch (NumberFormatException ignored) {
            // 非数字主键：只有字符串候选
        }
        try {
            hashes.add(Long.valueOf(HashUtil.pkHash(
                    List.<Object>of(new BigDecimal(pkText)))));
        } catch (NumberFormatException ignored) {
            // 非数字主键：跳过
        }
        return new ArrayList<>(hashes);
    }

    private static Map<String, Object> toItem(TableMapping mapping, ChangeEvent event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", Long.valueOf(event.getId()));
        item.put("table", mapping.getSourceTable());
        item.put("seq", Long.valueOf(event.getSeq()));
        item.put("op", event.getOp() == null ? null : event.getOp().name());
        item.put("status", event.getStatus() == null ? null : event.getStatus().name());
        item.put("groupNo", Integer.valueOf(event.getGroupNo()));
        item.put("commitTs", event.getCommitTs() == null ? null : event.getCommitTs().toString());
        item.put("changedColumns", event.getChangedColumns());
        item.put("pkHash", Long.valueOf(event.getPkHash()));
        return item;
    }

    private static IncrementalMode parseMode(String value) {
        if (value == null || value.isBlank()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "mode 不能为空");
        }
        try {
            return IncrementalMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "不支持的增量模式：" + value);
        }
    }

    private static long safeLong(java.util.function.LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static double safeDouble(java.util.function.DoubleSupplier supplier) {
        try {
            return supplier.getAsDouble();
        } catch (RuntimeException e) {
            return 0.0d;
        }
    }
}
