package com.dbsync.cutover;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.model.RouteSnapshot;
import com.dbsync.metadata.dao.CutoverPlanDao;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 切流恢复服务（T38）：进程启动时把"两段之间崩掉"的计划对齐。
 *
 * <p><b>为什么必须有这个类</b>（架构 §4.5.1）：状态机存在元数据库，路由生效在外部。
 * 若实例在"写 intent"与"写 confirmed"之间宕机，重启后看到 {@code intent != confirmed}
 * 就<b>必须</b>判断"到底是没下发，还是下发了没记账"，否则后续 Gate 会用错误前提判定。
 *
 * <p>三路分支见 {@link ReconcileResult.Branch}。
 *
 * <p><b>不变量 I7</b>：{@code intent != confirmed} 时禁止推进下一步 —— 因此本服务运行完
 * 仍为 {@code DRIFT} 的计划会被标 {@code INCONSISTENT}，编排器读到该状态直接拒绝 advance。
 */
public class CutoverRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(CutoverRecoveryService.class);

    /** 重新下发后的 ack 等待时长（可配，默认 3s：启动阶段不能卡太久）。 */
    private static final Duration DEFAULT_ACK_TIMEOUT = Duration.ofSeconds(3L);

    private final CutoverPlanDao planDao;
    private final RouteRegistry routeRegistry;
    private final Duration ackTimeout;

    /**
     * 构造。
     *
     * @param planDao       计划 DAO
     * @param routeRegistry 路由注册表
     */
    public CutoverRecoveryService(CutoverPlanDao planDao, RouteRegistry routeRegistry) {
        this(planDao, routeRegistry, DEFAULT_ACK_TIMEOUT);
    }

    /**
     * 构造（自定义 ack 超时，便于单测）。
     *
     * @param planDao       计划 DAO
     * @param routeRegistry 路由注册表
     * @param ackTimeout    ack 超时
     */
    public CutoverRecoveryService(CutoverPlanDao planDao, RouteRegistry routeRegistry,
                                  Duration ackTimeout) {
        this.planDao = planDao;
        this.routeRegistry = routeRegistry;
        this.ackTimeout = ackTimeout == null ? DEFAULT_ACK_TIMEOUT : ackTimeout;
    }

    /**
     * 对齐全部"intent != confirmed"的计划。
     *
     * @return 每个计划的对齐结果（无不一致计划返回空列表）
     */
    public List<ReconcileResult> reconcileAll() {
        List<CutoverPlan> plans = planDao.findNeedingReconcile();
        List<ReconcileResult> results = new ArrayList<ReconcileResult>(plans.size());
        for (CutoverPlan plan : plans) {
            results.add(reconcile(plan));
        }
        if (!results.isEmpty()) {
            log.warn("启动对齐完成：共 {} 个计划存在 intent/confirmed 不一致", results.size());
        }
        return results;
    }

    /**
     * 对齐单个计划。
     *
     * @param plan 计划
     * @return 对齐结果
     */
    public ReconcileResult reconcile(CutoverPlan plan) {
        long planId = plan.getId();
        CutoverState intent = plan.getIntentState();
        CutoverState confirmed = plan.getConfirmedState();
        RouteSnapshot snapshot = routeRegistry.current(planId);
        RouteRegistry.Bucket external = RouteRegistry.bucketOf(snapshot);
        RouteRegistry.Bucket intentBucket = RouteRegistry.bucketOf(intent);
        RouteRegistry.Bucket confirmedBucket = RouteRegistry.bucketOf(confirmed);

        // ① 外部已经在 intent：只补 confirmed（不重复下发，避免又制造一次窗口）
        if (external == intentBucket) {
            long version = routeRegistry.currentVersion(planId);
            int updated = planDao.confirm(planId, intent, version, planDao.versionOf(planId));
            log.info("对齐分支①：外部已在 intent，补 confirmed plan={} state={} version={} rows={}",
                    planId, intent, version, updated);
            if (updated == 0) {
                // 版本冲突：可能另一个实例刚改过，交给下一轮扫描
                planDao.markInconsistent(planId);
                return new ReconcileResult(planId, ReconcileResult.Branch.DRIFT, intent, confirmed,
                        external, confirmed, version, false,
                        "补 confirmed 时版本冲突，已标记 INCONSISTENT 待下一轮");
            }
            return new ReconcileResult(planId, ReconcileResult.Branch.EXTERNAL_AT_INTENT, intent,
                    confirmed, external, intent, version, true, "外部已在 intent，已补 confirmed");
        }

        // ② 外部停在 confirmed：重新下发并等 ack
        if (external == confirmedBucket) {
            RouteSnapshot target = new RouteRuleEngine().snapshotFor(plan, intent, 0L,
                    java.time.Instant.now());
            long version = routeRegistry.publish(planId, target);
            boolean acked = routeRegistry.awaitAck(planId, version, ackTimeout);
            if (!acked) {
                planDao.markInconsistent(planId);
                log.warn("对齐分支②：重新下发后仍未 ack plan={} version={}，标记 INCONSISTENT",
                        planId, version);
                return new ReconcileResult(planId, ReconcileResult.Branch.EXTERNAL_AT_CONFIRMED,
                        intent, confirmed, external, confirmed, version, false,
                        "重新下发后 " + ackTimeout.toMillis() + "ms 内未收到 ack");
            }
            int updated = planDao.confirm(planId, intent, version, planDao.versionOf(planId));
            if (updated == 0) {
                planDao.markInconsistent(planId);
                return new ReconcileResult(planId, ReconcileResult.Branch.EXTERNAL_AT_CONFIRMED,
                        intent, confirmed, external, confirmed, version, false,
                        "重新下发成功但补 confirmed 冲突");
            }
            log.info("对齐分支②：重新下发并确认 plan={} state={} version={}", planId, intent,
                    version);
            return new ReconcileResult(planId, ReconcileResult.Branch.EXTERNAL_AT_CONFIRMED, intent,
                    confirmed, external, intent, version, true, "已重新下发并收到 ack");
        }

        // ③ 外部漂移：绝不推进，标 INCONSISTENT 等人工
        planDao.markInconsistent(planId);
        String message = "外部路由既非 intent(" + intentBucket + ") 也非 confirmed("
                + confirmedBucket + ")，实际=" + external + "；已标记 INCONSISTENT，禁止自动推进";
        log.error("对齐分支③：外部漂移 plan={} {}", planId, message);
        return new ReconcileResult(planId, ReconcileResult.Branch.DRIFT, intent, confirmed,
                external, confirmed, routeRegistry.currentVersion(planId), true, message);
    }
}
