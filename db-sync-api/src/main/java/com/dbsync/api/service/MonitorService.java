package com.dbsync.api.service;

import com.dbsync.api.alert.AlertService;
import com.dbsync.api.dto.monitor.OverviewView;
import com.dbsync.api.metrics.SyncMetrics;
import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.model.SyncTask;
import com.dbsync.metadata.dao.CutoverPlanDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.metadata.dao.InstanceDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 大盘服务（T45：{@code /api/monitor/overview}）。
 *
 * <p>只读聚合，全部从元数据库 + 内存指标取数，不查业务库
 * （避免"大盘把源库拖慢"这类事故）。分片热力最多取前 20 个任务，
 * 防止任务极多时响应体爆炸。
 *
 * <p><b>存活窗口不再各写一份</b>：统一用 {@link InstanceDao#DEFAULT_ALIVE_WINDOW}
 * （90s），避免大盘 90s、HeartbeatJob 30s、InstanceDao 兜底 30s 三处不一致。
 */
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    /** 分片热力最多展示的任务数。 */
    private static final int HEAT_TASK_LIMIT = 20;

    private final SyncTaskDao syncTaskDao;
    private final ShardDao shardDao;
    private final DeadLetterDao deadLetterDao;
    private final CutoverPlanDao cutoverPlanDao;
    private final InstanceDao instanceDao;
    private final SyncMetrics syncMetrics;
    private final AlertService alertService;

    /**
     * 构造。
     *
     * @param syncTaskDao    任务 DAO
     * @param shardDao       分片 DAO
     * @param deadLetterDao  死信 DAO
     * @param cutoverPlanDao 切流计划 DAO
     * @param instanceDao    实例 DAO
     * @param syncMetrics    指标
     * @param alertService   告警服务（可 null，则不含告警列表）
     */
    public MonitorService(SyncTaskDao syncTaskDao, ShardDao shardDao, DeadLetterDao deadLetterDao,
                          CutoverPlanDao cutoverPlanDao, InstanceDao instanceDao,
                          SyncMetrics syncMetrics, AlertService alertService) {
        this.syncTaskDao = syncTaskDao;
        this.shardDao = shardDao;
        this.deadLetterDao = deadLetterDao;
        this.cutoverPlanDao = cutoverPlanDao;
        this.instanceDao = instanceDao;
        this.syncMetrics = syncMetrics;
        this.alertService = alertService;
    }

    /**
     * 总览。
     *
     * @return 总览视图
     */
    public OverviewView overview() {
        List<SyncTask> tasks = syncTaskDao.findAll();
        Map<String, Integer> taskCounts = new TreeMap<>();
        for (TaskState state : TaskState.values()) {
            taskCounts.put(state.name(), Integer.valueOf(0));
        }
        for (SyncTask task : tasks) {
            String key = task.getState().name();
            Integer previous = taskCounts.get(key);
            taskCounts.put(key, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
        }

        Map<String, Integer> shardCounts = new TreeMap<>();
        for (ShardState state : ShardState.values()) {
            shardCounts.put(state.name(), Integer.valueOf(0));
        }
        Map<String, Map<String, Integer>> heat = new LinkedHashMap<>();
        Map<String, Double> tps = new LinkedHashMap<>();
        long committedRows = 0L;
        double totalTps = 0.0d;
        int heatLimit = 0;
        for (SyncTask task : tasks) {
            Map<ShardState, Integer> counts = shardDao.countByState(task.getId());
            for (Map.Entry<ShardState, Integer> entry : counts.entrySet()) {
                String key = entry.getKey().name();
                Integer previous = shardCounts.get(key);
                shardCounts.put(key, Integer.valueOf((previous == null ? 0 : previous.intValue())
                        + entry.getValue().intValue()));
            }
            if (heatLimit < HEAT_TASK_LIMIT && task.getState().isActive()) {
                Map<String, Integer> perTask = new TreeMap<>();
                for (Map.Entry<ShardState, Integer> entry : counts.entrySet()) {
                    perTask.put(entry.getKey().name(), entry.getValue());
                }
                heat.put(String.valueOf(task.getId()), perTask);
                heatLimit++;
            }
            long rows = shardDao.sumCommittedRows(task.getId());
            committedRows += rows;
            double taskTps = syncMetrics == null ? 0.0d : syncMetrics.tps(task.getId());
            tps.put(String.valueOf(task.getId()), Double.valueOf(taskTps));
            totalTps += taskTps;
        }

        Map<String, Integer> cutover = new TreeMap<>();
        for (CutoverState state : CutoverState.values()) {
            cutover.put(state.name(), Integer.valueOf(0));
        }
        for (SyncTask task : tasks) {
            for (CutoverPlan plan : cutoverPlanDao.findByTask(task.getId())) {
                String key = plan.getIntentState().name();
                Integer previous = cutover.get(key);
                cutover.put(key, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
            }
        }

        long alive = 0L;
        try {
            alive = instanceDao.countAlive(InstanceDao.DEFAULT_ALIVE_WINDOW);
        } catch (RuntimeException e) {
            log.debug("读取存活实例数失败", e);
        }
        long open = 0L;
        try {
            open = deadLetterDao.countOpenAll();
        } catch (RuntimeException e) {
            log.debug("读取死信数失败", e);
        }
        List<Map<String, Object>> alerts = new ArrayList<>();
        if (alertService != null) {
            for (AlertService.AlertRecord record : alertService.recent()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rule", record.getRule());
                row.put("title", record.getTitle());
                row.put("detail", record.getDetail());
                row.put("firedAt", record.getFiredAt().toString());
                row.put("channels", record.getChannels());
                alerts.add(row);
            }
        }
        return new OverviewView(taskCounts, tasks.size(), shardCounts, heat, committedRows, tps,
                totalTps, open, alive, cutover, alerts);
    }
}
