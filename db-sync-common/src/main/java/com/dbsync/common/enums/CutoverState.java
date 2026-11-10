package com.dbsync.common.enums;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 切流状态机（架构 §4.5.3 第 9 行表格的**唯一代码化数据源**）。
 *
 * <p>三条硬编码校验规则：
 * <ol>
 *   <li><b>不可跳步前进</b>：{@code DRAFT → GRAY_WRITE} 非法；</li>
 *   <li><b>回滚可跳步</b>：{@code ROLLED_BACK} 可从任意非终态直接到达；</li>
 *   <li><b>终态不可逆</b>：{@code DONE} / {@code ROLLED_BACK} 拒绝一切跃迁。</li>
 * </ol>
 *
 * <p><b>扩展点（Q-B 决策）</b>：本期「只读窗口」按 ≤5 分钟设计，切换前由人工确认，
 * 因此本枚举**不设 DRAIN（排空/短暂只读）状态**。若后续需要真正的在线切流，
 * 应在 {@code GRAY_WRITE} 与 {@code FULL_TARGET} 之间插入 {@code DRAIN}：
 * 只需在 {@link #forwardTargets()} 的 {@code GRAY_WRITE} 分支把 {@code FULL_TARGET} 拆成
 * {@code DRAIN → FULL_TARGET}，并把 {@code DRAIN} 加入 {@link #isRollbackCapable()} 集合，
 * 其余代码（{@code CutoverStateMachine} / {@code CutoverOrchestrator}）无需修改。
 */
public enum CutoverState {

    /** 草稿。 */
    DRAFT,
    /** 预检（Gate 判定）。 */
    PRE_CHECK,
    /** 预检不通过。 */
    PRE_CHECK_FAILED,
    /** 读灰度（源+目标按比例读）。 */
    GRAY_READ,
    /** 写灰度（双写）。 */
    GRAY_WRITE,
    /** 全量目标（读目标、写目标）。 */
    FULL_TARGET,
    /** 观察期。 */
    OBSERVE,
    /** 完成（终态）。 */
    DONE,
    /** 已回滚（终态）。 */
    ROLLED_BACK;

    /**
     * 前进一步允许的目标（不含回退与回滚）。
     *
     * @return 不可变集合
     */
    public Set<CutoverState> forwardTargets() {
        switch (this) {
            case DRAFT:
                return Collections.unmodifiableSet(EnumSet.of(PRE_CHECK));
            case PRE_CHECK:
                return Collections.unmodifiableSet(EnumSet.of(GRAY_READ, PRE_CHECK_FAILED));
            case PRE_CHECK_FAILED:
                return Collections.unmodifiableSet(EnumSet.of(PRE_CHECK));
            case GRAY_READ:
                return Collections.unmodifiableSet(EnumSet.of(GRAY_WRITE));
            case GRAY_WRITE:
                // 扩展点：如引入 DRAIN，这里改为 EnumSet.of(DRAIN)
                return Collections.unmodifiableSet(EnumSet.of(FULL_TARGET));
            case FULL_TARGET:
                return Collections.unmodifiableSet(EnumSet.of(OBSERVE, DONE));
            case OBSERVE:
                return Collections.unmodifiableSet(EnumSet.of(DONE));
            case DONE:
            case ROLLED_BACK:
            default:
                return Collections.emptySet();
        }
    }

    /**
     * 回退一步允许的目标（不含回滚）。
     *
     * @return 不可变集合
     */
    public Set<CutoverState> backwardTargets() {
        switch (this) {
            case PRE_CHECK:
            case PRE_CHECK_FAILED:
                return Collections.unmodifiableSet(EnumSet.of(DRAFT));
            case GRAY_READ:
                return Collections.unmodifiableSet(EnumSet.of(PRE_CHECK));
            case GRAY_WRITE:
                return Collections.unmodifiableSet(EnumSet.of(GRAY_READ));
            case FULL_TARGET:
                return Collections.unmodifiableSet(EnumSet.of(GRAY_WRITE));
            case OBSERVE:
                return Collections.unmodifiableSet(EnumSet.of(FULL_TARGET));
            default:
                return Collections.emptySet();
        }
    }

    /**
     * @return true 表示该状态下允许"一键回滚到 ROLLED_BACK"
     */
    public boolean isRollbackCapable() {
        return this == GRAY_READ || this == GRAY_WRITE || this == FULL_TARGET || this == OBSERVE;
    }

    /**
     * @return true 表示终态（不可跃出）
     */
    public boolean isTerminal() {
        return this == DONE || this == ROLLED_BACK;
    }

    /**
     * 全部合法目标（前进 ∪ 回退 ∪ 回滚）。
     *
     * @return 有序不可变列表
     */
    public List<CutoverState> allowedNext() {
        Set<CutoverState> all = new LinkedHashSet<>();
        all.addAll(forwardTargets());
        all.addAll(backwardTargets());
        if (isRollbackCapable()) {
            all.add(ROLLED_BACK);
        }
        return List.copyOf(new ArrayList<>(all));
    }

    /**
     * 全部合法的"上一状态"（即哪些状态可以跃迁到本状态）。
     *
     * @return 有序不可变列表
     */
    public List<CutoverState> allowedPrev() {
        List<CutoverState> prev = new ArrayList<>();
        for (CutoverState s : values()) {
            if (s != this && s.allowedNext().contains(this)) {
                prev.add(s);
            }
        }
        return List.copyOf(prev);
    }

    /**
     * 是否可以从 {@code from} 直接回滚到本状态（回滚可跳步）。
     *
     * @param from 起始状态
     * @return true 表示可达
     */
    public boolean isRollbackReachableFrom(CutoverState from) {
        if (from == null || this != ROLLED_BACK) {
            return false;
        }
        return !from.isTerminal();
    }

    /**
     * 判定跃迁是否合法（供状态机与单测共用）。
     *
     * @param from 起始
     * @param to   目标
     * @return true 合法
     */
    public static boolean isLegal(CutoverState from, CutoverState to) {
        return from != null && to != null && from.allowedNext().contains(to);
    }

    /**
     * 状态序号（用于 Prometheus {@code dbsync.cutover.state} 指标映射）。
     *
     * @return 0..8
     */
    public int code() {
        return ordinal();
    }
}
