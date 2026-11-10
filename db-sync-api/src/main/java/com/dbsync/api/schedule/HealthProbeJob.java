package com.dbsync.api.schedule;

import com.dbsync.common.model.DataSourceDef;
import com.dbsync.core.datasource.ConnectionTestResult;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.metadata.dao.DataSourceDao;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据源探活任务（T44 / PRD DS-05，简化为"定时探活 + 状态"）。
 *
 * <p>每 60 秒（{@code dbsync.jobs.health-probe-ms}）对全部数据源做一次短连接探测，
 * 把结果写回 {@code ds_datasource.health_status}。
 * 探测走 {@link com.dbsync.core.datasource.ConnectionTester}（独立短连接），
 * <b>不占用业务连接池</b>，避免"探活把池占满导致迁移卡住"。
 *
 * <p><b>逐源限时 + 整轮限时</b>：每个数据源的探测提交到独立执行器、用 {@code Future.get} 限时，
 * 整轮还有总时限，超过后剩余数据源直接标 DOWN 跳过。这样单源挂住（驱动超时未生效、
 * DNS 卡死等）既不会拖住整个调度线程，也不会让本轮无限延长。
 *
 * <p>{@code // TODO(P2)}：不做"慢查询探测"（04 文档 §5 明确 P2）。
 */
public class HealthProbeJob {

    private static final Logger log = LoggerFactory.getLogger(HealthProbeJob.class);

    /** 单源探测上限（略大于驱动侧 5s 登录超时，给 getMetaData 留余量）。 */
    private static final long PER_SOURCE_TIMEOUT_MS = 8_000L;
    /** 整轮探活上限：不允许单轮把调度线程占死。 */
    private static final long OVERALL_TIMEOUT_MS = 60_000L;

    private final DataSourceDao dataSourceDao;
    private final DataSourceRegistry registry;
    /** 探活执行器：daemon 线程；cancel 只释放 Future 等待，驱动侧超时由 ConnectionTester 兜底。 */
    private final ExecutorService probePool = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "dbsync-health-probe");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 构造。
     *
     * @param dataSourceDao 数据源 DAO
     * @param registry      数据源注册表
     */
    public HealthProbeJob(DataSourceDao dataSourceDao, DataSourceRegistry registry) {
        this.dataSourceDao = dataSourceDao;
        this.registry = registry;
    }

    /**
     * 执行一次全量探活。
     *
     * @return 探活为 DOWN 的数据源数
     */
    public int runOnce() {
        List<DataSourceDef> all = dataSourceDao.findAll();
        int down = 0;
        List<String> downNames = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OVERALL_TIMEOUT_MS);
        for (DataSourceDef def : all) {
            long remainingOverallMs =
                    TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingOverallMs <= 0L) {
                down++;
                downNames.add(def.getName());
                dataSourceDao.updateHealth(def.getId(), "DOWN", "整轮探活超时，剩余数据源未探测");
                continue;
            }
            long timeoutMs = Math.min(PER_SOURCE_TIMEOUT_MS, remainingOverallMs);
            // 显式 Callable，避免 "() -> 方法调用" 在 submit(Runnable)/submit(Callable) 间的重载歧义
            java.util.concurrent.Callable<ConnectionTestResult> task = () -> registry.test(def);
            Future<ConnectionTestResult> future = probePool.submit(task);
            try {
                ConnectionTestResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                String status = result.isSuccess() ? "UP" : "DOWN";
                dataSourceDao.updateHealth(def.getId(), status, result.getMessage());
                if (!result.isSuccess()) {
                    down++;
                    downNames.add(def.getName());
                }
            } catch (TimeoutException e) {
                future.cancel(true);
                down++;
                downNames.add(def.getName());
                dataSourceDao.updateHealth(def.getId(), "DOWN",
                        "单源探活超时（" + timeoutMs + "ms）");
                log.warn("数据源探活超时（标记 DOWN）ds={} timeoutMs={}", def.getName(),
                        Long.valueOf(timeoutMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                down++;
                downNames.add(def.getName());
                dataSourceDao.updateHealth(def.getId(), "DOWN", "探活被中断");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                down++;
                downNames.add(def.getName());
                // 携带异常对象打完整堆栈，不再只留一行 e.toString()
                log.warn("数据源探活异常（标记 DOWN）ds={}", def.getName(), cause);
                dataSourceDao.updateHealth(def.getId(), "DOWN", String.valueOf(cause));
            } catch (RuntimeException e) {
                down++;
                downNames.add(def.getName());
                log.warn("数据源探活异常（标记 DOWN）ds={}", def.getName(), e);
                dataSourceDao.updateHealth(def.getId(), "DOWN", e.toString());
            }
        }
        if (down > 0) {
            log.warn("探活发现不可用数据源 {} 个：{}", Integer.valueOf(down), downNames);
        }
        return down;
    }

    /**
     * 探活单个数据源（API 手动触发用）。
     *
     * @param id 数据源 ID
     * @return 探测结果
     */
    public ConnectionTestResult probeOne(long id) {
        DataSourceDef def = dataSourceDao.findById(id).orElseThrow(
                () -> new com.dbsync.common.exception.DbSyncException(
                        com.dbsync.common.exception.ErrorCode.DATASOURCE_NOT_FOUND, id));
        ConnectionTestResult result = registry.test(def);
        dataSourceDao.updateHealth(id, result.isSuccess() ? "UP" : "DOWN", result.getMessage());
        return result;
    }
}
