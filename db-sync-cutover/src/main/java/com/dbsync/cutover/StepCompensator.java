package com.dbsync.cutover;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.model.CutoverStep;
import com.dbsync.metadata.dao.CutoverPlanDao;
import com.dbsync.metadata.dao.CutoverStepLogDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 步骤补偿器（T40）：当"外部动作没成功"时把平台侧状态<b>退回去</b>。
 *
 * <p><b>为什么必须有</b>（架构 §4.6.4 J1，也是坑 K5）：两段式是
 * "先写 intent → 下发 → 等 ack → 写 confirmed"。若 ack 超时（SDK 挂了、网络分区、
 * 网关没启用），平台侧已经写了 {@code intent=GRAY_WRITE} 但外部还在 {@code GRAY_READ}。
 * 如果不管，计划会永久卡在 {@code intent != confirmed}，运维只能手工改库 —— 这正是要消灭的
 * "状态不一致"事故。
 *
 * <p>补偿动作（必须三步都做，缺一不可）：
 * <ol>
 *   <li>把 {@code intent} 退回原值 → 恢复 I5（confirmed 只能等于 intent 或其前驱）；</li>
 *   <li>若回退后 {@code intent == confirmed}，把 {@code plan_status} 恢复成 {@code ACTIVE}；</li>
 *   <li>落一条 {@code result='TIMEOUT'} + {@code error_msg='CONFIRM_TIMEOUT'} 的步骤留痕，
 *       让审计能定位"这次失败发生在下发环节"。</li>
 * </ol>
 *
 * <p><b>注意</b>：补偿<b>不</b>重新下发旧快照 —— 因为旧快照本来就还是外部的当前值
 * （正因为 SDK 没 ack，说明它没切过去）。若确实需要重放，由
 * {@link CutoverRecoveryService} 的启动对齐负责。
 */
public class StepCompensator {

    private static final Logger log = LoggerFactory.getLogger(StepCompensator.class);

    /** 步骤留痕的固定错误码（架构 §4.6.4 原文）。 */
    public static final String ERROR_CONFIRM_TIMEOUT = "CONFIRM_TIMEOUT";

    /** 步骤留痕的固定结果值。 */
    public static final String RESULT_TIMEOUT = "TIMEOUT";

    private final CutoverPlanDao planDao;
    private final CutoverStepLogDao stepLogDao;

    /**
     * 构造。
     *
     * @param planDao    计划 DAO
     * @param stepLogDao 步骤留痕 DAO
     */
    public StepCompensator(CutoverPlanDao planDao, CutoverStepLogDao stepLogDao) {
        this.planDao = planDao;
        this.stepLogDao = stepLogDao;
    }

    /**
     * ack 超时补偿。
     *
     * @param plan            计划（已被写成 intent=attempted 之后的状态）
     * @param attempted       本次尝试的目标状态（intent 现值）
     * @param originalIntent  本次尝试之前的状态（要退回到这里）
     * @param publishedVersion 本次下发的路由版本（留痕用）
     * @param operator        操作人
     * @param stepSeq         步骤序号
     * @param startedAtMillis 开始时间（算耗时）
     * @return 已落库的步骤留痕
     */
    public CutoverStep compensateAckTimeout(CutoverPlan plan, CutoverState attempted,
                                            CutoverState originalIntent, long publishedVersion,
                                            String operator, int stepSeq, long startedAtMillis) {
        long planId = plan.getId();
        // ① 回退 intent
        CutoverPlan current = planDao.findById(planId).orElse(plan);
        int updated = planDao.updateIntent(planId, originalIntent, planDao.versionOf(planId));
        if (updated == 0) {
            log.warn("补偿回退 intent 遇版本冲突（另一实例已改动）plan={}，交由对齐扫描处理", planId);
        }
        // ② 若回退后 intent == confirmed，恢复 ACTIVE（否则保留 INCONSISTENT 让恢复服务接手）
        CutoverPlan afterRollback = planDao.findById(planId).orElse(current);
        if (afterRollback.getIntentState() == afterRollback.getConfirmedState()) {
            planDao.updatePlanStatus(planId, CutoverPlanDao.STATUS_ACTIVE);
        }
        // ③ 留痕
        long duration = Math.max(0L, System.currentTimeMillis() - startedAtMillis);
        CutoverStep step = new CutoverStep(planId, stepSeq, attempted, originalIntent,
                "ADVANCE", RESULT_TIMEOUT,
                ERROR_CONFIRM_TIMEOUT + "：路由下发后未在超时时间内收到 ack，已回退 intent 至 "
                        + originalIntent,
                duration, operator, plan.getRouteVersion(), publishedVersion, false, null, null);
        stepLogDao.append(step, null);
        log.error("ack 超时补偿完成 plan={} {} → 回退 {} 版本={} 耗时={}ms", planId, attempted,
                originalIntent, publishedVersion, duration);
        return step;
    }

    /**
     * Gate 阻断留痕（不需要回退 intent，但必须留痕显示"被拦住了"）。
     *
     * @param plan      计划
     * @param from      当前状态
     * @param to        阻断后落到 {@code PRE_CHECK_FAILED}
     * @param operator  操作人
     * @param stepSeq   步骤序号
     * @param startedAtMillis 开始时间
     * @param gateJson  Gate 判定 JSON（可 null）
     * @param reason    阻断原因
     * @return 已落库的步骤留痕
     */
    public CutoverStep recordBlocked(CutoverPlan plan, CutoverState from, CutoverState to,
                                     String operator, int stepSeq, long startedAtMillis,
                                     com.dbsync.common.model.GateResult gateResult, String reason) {
        long duration = Math.max(0L, System.currentTimeMillis() - startedAtMillis);
        CutoverStep step = new CutoverStep(plan.getId(), stepSeq, from, to, "GATE", "BLOCKED",
                reason, duration, operator, plan.getRouteVersion(), plan.getRouteVersion(), false,
                null, gateResult);
        stepLogDao.append(step, null);
        log.warn("Gate 阻断已留痕 plan={} {} → {} 原因={}", plan.getId(), from, to, reason);
        return step;
    }
}
