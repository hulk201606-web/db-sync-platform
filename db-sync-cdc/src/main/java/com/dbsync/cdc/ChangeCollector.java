package com.dbsync.cdc;

import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>一轮</b>采集：把一个任务下所有启用映射的源库变更搬进 {@code sync_change_log}。
 *
 * <p><b>这个类解决的正是本轮的上线阻断问题</b>：{@code TriggerLogPoller.pollOnce} /
 * {@code TimestampPoller.pollOnce} 此前全仓库<b>没有任何生产调用方</b>——
 * 触发器把变更写进了源库日志表，但没有人把它搬进 {@code sync_change_log}，
 * 于是消费侧再正确也无数据可消费（端到端表现为"目标表永远是空的"）。
 *
 * <p><b>职责边界</b>（有意做窄）：
 * <ul>
 *   <li>只做"一轮"，不含任何定时/线程逻辑 → 可以在单测里同步调用、断言精确到条数；</li>
 *   <li>不含退避/重入保护 → 那是 {@link CdcCaptureScheduler} 的事；</li>
 *   <li>模式 → 采集器的映射只认 {@link ChangePoller#mode()}，不做 {@code if/else}，
 *       加第四种模式时只需多注册一个采集器。</li>
 * </ul>
 *
 * <p><b>跳过规则</b>（三条都是"必须"，不是优化）：
 * <ol>
 *   <li>任务不是 {@code RUNNING} → 不采集。PAUSED/CANCELLED 的任务还往队列里灌变更，
 *       等于"暂停了还在动数据"；</li>
 *   <li>模式不需要采集（{@code SDK_WRITE} / null）→ 不采集。SDK 模式由业务侧双写，
 *       平台再轮询会把同一份变更记两遍；</li>
 *   <li>没有启用的表映射 → 不采集（无事可做）。</li>
 * </ol>
 */
public class ChangeCollector {

    private static final Logger log = LoggerFactory.getLogger(ChangeCollector.class);

    private final SyncTaskDao syncTaskDao;
    private final TableMappingDao tableMappingDao;
    private final Map<IncrementalMode, ChangePoller> pollers =
            new EnumMap<>(IncrementalMode.class);

    /**
     * 构造。
     *
     * @param syncTaskDao     任务 DAO（读状态/模式）
     * @param tableMappingDao 表映射 DAO（读启用的映射）
     * @param pollers         采集器列表（按 {@link ChangePoller#mode()} 注册）
     */
    public ChangeCollector(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao,
                           List<ChangePoller> pollers) {
        this.syncTaskDao = syncTaskDao;
        this.tableMappingDao = tableMappingDao;
        if (pollers != null) {
            for (ChangePoller poller : pollers) {
                if (poller != null && poller.mode() != null) {
                    ChangePoller previous = this.pollers.put(poller.mode(), poller);
                    if (previous != null) {
                        // 同一模式注册两个实现会让"到底谁在采集"变成隐患，直接失败
                        throw new IllegalArgumentException(
                                "同一增量模式注册了多个采集器：" + poller.mode());
                    }
                }
            }
        }
    }

    /**
     * 执行一轮采集。
     *
     * @param taskId 任务 ID
     * @return 本轮入队总条数
     * @throws DbSyncException 任务不存在（{@code E3004}）/ 本模式没有采集器（{@code E9001}）/
     *                         有映射采集失败（{@code E4001}，用于触发退避）
     */
    public int collectOnce(long taskId) {
        Optional<SyncTask> loaded;
        try {
            loaded = syncTaskDao.findById(taskId);
        } catch (RuntimeException e) {
            log.warn("采集跳过：读取任务失败 task={} err={}", taskId, e.toString());
            return 0;
        }
        if (loaded.isEmpty()) {
            log.debug("采集跳过：任务不存在（可能已被硬删除）task={}", taskId);
            return 0;
        }
        SyncTask task = loaded.get();
        if (task.getState() != TaskState.RUNNING) {
            log.debug("采集跳过：任务状态不是 RUNNING task={} state={}", taskId, task.getState());
            return 0;
        }
        IncrementalMode mode = task.getIncrementalMode();
        if (!CaptureBackoff.needsCapture(mode)) {
            log.debug("采集跳过：该增量模式不需要采集 task={} mode={}", taskId, mode);
            return 0;
        }
        ChangePoller poller = pollers.get(mode);
        if (poller == null) {
            throw new DbSyncException(ErrorCode.EXTERNAL_NOT_IMPLEMENTED,
                    "增量模式 " + mode + " 没有注册采集器，源库变更无法进入队列")
                    .with("taskId", Long.valueOf(taskId)).with("mode", String.valueOf(mode));
        }
        List<TableMapping> mappings = tableMappingDao.findEnabledByTask(taskId);
        if (mappings.isEmpty()) {
            log.debug("采集跳过：没有启用的表映射 task={}", taskId);
            return 0;
        }
        int total = 0;
        int failed = 0;
        String firstError = null;
        for (TableMapping mapping : mappings) {
            try {
                total += poller.pollOnce(taskId, mapping.getId());
            } catch (RuntimeException e) {
                // 单表失败不立刻中断：其余表照常采集，最后再用一个汇总异常触发退避，
                // 这样"一张表坏了"不会让"另外十张表"也停止同步。
                failed++;
                if (firstError == null) {
                    firstError = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                log.warn("采集失败 task={} mapping={} table={} err={}", taskId, mapping.getId(),
                        mapping.getSourceTable(), e.toString());
            }
        }
        if (failed > 0) {
            List<String> details = new ArrayList<>(2);
            details.add(failed + "/" + mappings.size() + " 张表采集失败");
            details.add(firstError);
            throw new DbSyncException(ErrorCode.TRIGGER_DEPLOY_FAILED,
                    "增量采集部分失败：" + String.join("；", details))
                    .with("taskId", Long.valueOf(taskId))
                    .with("failedMappings", Integer.valueOf(failed));
        }
        if (total > 0) {
            log.info("增量采集完成 task={} mode={} 入队={} 表数={}", taskId, mode,
                    Integer.valueOf(total), Integer.valueOf(mappings.size()));
        }
        return total;
    }

    /**
     * 该任务当前是否需要采集（供调度器判断"要不要建任务"）。
     *
     * @param task 任务
     * @return true 表示需要
     */
    public static boolean needsCapture(SyncTask task) {
        return task != null && task.getState() == TaskState.RUNNING
                && CaptureBackoff.needsCapture(task.getIncrementalMode());
    }
}
