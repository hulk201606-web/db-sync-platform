package com.dbsync.cutover;

import com.dbsync.common.model.GateResult;
import java.time.Instant;
import java.util.List;

/**
 * dry-run 报告（// ADDED：04 文档 T40 的 {@code dryRun} 返回 {@code DryRunReport}，
 * 但 common 模型清单里没有该类；它是只读演练产物，不属于跨模块契约）。
 *
 * <p><b>接口预留说明（T41）</b>：本期 dry-run <b>只列步骤、不做任何状态写入</b>，
 * 不包含"沙箱环境真实演练"。{@code // TODO(P1)} 见 {@link CutoverOrchestrator#dryRun(long)}。
 *
 * <p>不可变。
 */
public final class DryRunReport {

    private final long planId;
    private final String currentState;
    private final String targetState;
    private final boolean allowed;
    private final GateResult gateResult;
    private final long nextRouteVersion;
    private final List<String> steps;
    private final Instant generatedAt;

    /**
     * 构造。
     *
     * @param planId          计划 ID
     * @param currentState    当前 intent
     * @param targetState     如果执行会到达的状态
     * @param allowed         是否允许执行（状态机 + Gate 综合）
     * @param gateResult      Gate 判定（未评估时为 null）
     * @param nextRouteVersion 预计发布的路由版本（0 表示不发布）
     * @param steps           执行步骤（人类可读）
     * @param generatedAt     生成时间
     */
    public DryRunReport(long planId, String currentState, String targetState, boolean allowed,
                        GateResult gateResult, long nextRouteVersion, List<String> steps,
                        Instant generatedAt) {
        this.planId = planId;
        this.currentState = currentState;
        this.targetState = targetState;
        this.allowed = allowed;
        this.gateResult = gateResult;
        this.nextRouteVersion = nextRouteVersion;
        this.steps = List.copyOf(steps == null ? List.of() : steps);
        this.generatedAt = generatedAt;
    }

    /** @return 计划 ID */
    public long getPlanId() {
        return planId;
    }

    /** @return 当前状态 */
    public String getCurrentState() {
        return currentState;
    }

    /** @return 目标状态 */
    public String getTargetState() {
        return targetState;
    }

    /** @return 是否允许执行 */
    public boolean isAllowed() {
        return allowed;
    }

    /** @return Gate 判定（可 null） */
    public GateResult getGateResult() {
        return gateResult;
    }

    /** @return 预计发布的路由版本 */
    public long getNextRouteVersion() {
        return nextRouteVersion;
    }

    /** @return 执行步骤（不可变） */
    public List<String> getSteps() {
        return steps;
    }

    /** @return 生成时间 */
    public Instant getGeneratedAt() {
        return generatedAt;
    }

    @Override
    public String toString() {
        return "DryRunReport[plan=" + planId + "," + currentState + "→" + targetState
                + ",allowed=" + allowed + ",steps=" + steps.size() + "]";
    }
}
