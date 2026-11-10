package com.dbsync.cutover;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.exception.IllegalStateTransitionException;

/**
 * 切流状态机（T38）：{@link CutoverState} 的"唯一守卫"。
 *
 * <p><b>为什么守卫要单独一个类</b>：状态机的合法表已经写在枚举里（{@code allowedNext()}），
 * 但"谁在什么时机检查"是散落的风险点 —— 编排器、API、补偿逻辑、恢复服务都要检查。
 * 把检查收敛到这里，任何一处漏检都能被单测直接覆盖到（{@code StateMachineTest}）。
 *
 * <p><b>三条规则</b>（架构 §4.5.3）：
 * <ol>
 *   <li>前进不可跳步：{@code DRAFT → GRAY_WRITE} 非法；</li>
 *   <li>回滚可跳步：任意非终态 → {@code ROLLED_BACK}；</li>
 *   <li>终态不可逆：{@code DONE} / {@code ROLLED_BACK} 拒绝一切跃迁。</li>
 * </ol>
 *
 * <p><b>扩展点（Q-B：只读窗口 ≤5 分钟，本期无 DRAIN）</b>：若未来插入 {@code DRAIN}，
 * 只需改 {@link CutoverState#forwardTargets()} 与 {@link CutoverState#isRollbackCapable()}，
 * 本类<b>无需修改</b>。
 */
public class CutoverStateMachine {

    /**
     * 校验跃迁合法。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @throws IllegalStateTransitionException {@code E6001}，消息含 {@code allowed=[...]}
     */
    public void assertTransition(CutoverState from, CutoverState to) {
        if (!CutoverState.isLegal(from, to)) {
            throw new IllegalStateTransitionException(from, to,
                    from == null ? java.util.List.of() : from.allowedNext());
        }
    }

    /**
     * 是否合法（不抛异常版本，供只读校验/dry-run 使用）。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return true 合法
     */
    public boolean canTransition(CutoverState from, CutoverState to) {
        return CutoverState.isLegal(from, to);
    }

    /**
     * 计算"前进一步"的目标状态。
     *
     * <p>{@code PRE_CHECK} 有两个前进目标（{@code GRAY_READ} / {@code PRE_CHECK_FAILED}），
     * 由调用方按 Gate 结论选择，因此这里只对单目标状态返回确定值。
     *
     * @param from 当前状态
     * @return 下一步；多分支或终态返回 {@code null}
     */
    public CutoverState nextForward(CutoverState from) {
        if (from == null) {
            return null;
        }
        java.util.Set<CutoverState> forward = from.forwardTargets();
        if (forward.size() == 1) {
            return forward.iterator().next();
        }
        // FULL_TARGET 有 {OBSERVE, DONE} 两条前进路径，而 advance(planId, operator) 没有
        // target 参数。按「不可跳步前进」原则默认进观察期：DONE 必须由 OBSERVE 再推一步。
        // （2026-11-10 真库 E2E：此前返回 null，导致 FULL_TARGET 处 advance 抛
        //   "FULL_TARGET → FULL_TARGET" 非法跃迁，切流卡在倒数第二步。）
        if (from == CutoverState.FULL_TARGET) {
            return CutoverState.OBSERVE;
        }
        return null;
    }

    /**
     * 校验"一键回滚"是否允许。
     *
     * @param from 当前状态
     * @throws IllegalStateTransitionException {@code E6001}
     */
    public void assertRollback(CutoverState from) {
        if (from == null || !from.isRollbackCapable()) {
            throw new IllegalStateTransitionException(from, CutoverState.ROLLED_BACK,
                    from == null ? java.util.List.of() : from.allowedNext());
        }
    }
}
