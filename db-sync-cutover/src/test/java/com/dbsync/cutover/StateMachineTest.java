package com.dbsync.cutover;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.exception.IllegalStateTransitionException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T38 验收：切流状态机。
 *
 * <p>9 条合法跃迁 + 不可跳步 + 回滚可跳步 + 终态不可逆。
 */
class StateMachineTest {

    private final CutoverStateMachine machine = new CutoverStateMachine();

    @Test
    @DisplayName("should_allow_When_nineLegalTransitions：9 条合法跃迁全部通过")
    void should_allow_When_nineLegalTransitions() {
        List<CutoverState[]> legal = List.of(
                new CutoverState[] {CutoverState.DRAFT, CutoverState.PRE_CHECK},
                new CutoverState[] {CutoverState.PRE_CHECK, CutoverState.GRAY_READ},
                new CutoverState[] {CutoverState.PRE_CHECK, CutoverState.PRE_CHECK_FAILED},
                new CutoverState[] {CutoverState.PRE_CHECK_FAILED, CutoverState.PRE_CHECK},
                new CutoverState[] {CutoverState.GRAY_READ, CutoverState.GRAY_WRITE},
                new CutoverState[] {CutoverState.GRAY_WRITE, CutoverState.FULL_TARGET},
                new CutoverState[] {CutoverState.FULL_TARGET, CutoverState.OBSERVE},
                new CutoverState[] {CutoverState.OBSERVE, CutoverState.DONE},
                new CutoverState[] {CutoverState.GRAY_READ, CutoverState.PRE_CHECK});
        for (CutoverState[] pair : legal) {
            assertDoesNotThrow(() -> machine.assertTransition(pair[0], pair[1]),
                    pair[0] + "→" + pair[1]);
        }
    }

    @Test
    @DisplayName("should_throwE6001WithAllowed_When_jumpForward：DRAFT→GRAY_WRITE 非法且消息含 allowed")
    void should_throwE6001WithAllowed_When_jumpForward() {
        IllegalStateTransitionException e = assertThrows(IllegalStateTransitionException.class,
                () -> machine.assertTransition(CutoverState.DRAFT, CutoverState.GRAY_WRITE));
        assertEquals(ErrorCode.ILLEGAL_TRANSITION, e.errorCode());
        assertTrue(e.getMessage().contains("allowed=[PRE_CHECK]"), e.getMessage());
    }

    @Test
    @DisplayName("should_reachRolledBack_When_fromAnyNonTerminal：6+ 个非终态可一键回滚")
    void should_reachRolledBack_When_fromAnyNonTerminal() {
        int reachable = 0;
        for (CutoverState state : CutoverState.values()) {
            if (state.isTerminal()) {
                continue;
            }
            if (CutoverState.ROLLED_BACK.isRollbackReachableFrom(state)) {
                reachable++;
            }
        }
        assertTrue(reachable >= 6, "非终态应至少 6 个可达 ROLLED_BACK，实际 " + reachable);
    }

    @Test
    @DisplayName("should_jumpToRolledBack_When_grayWrite：GRAY_WRITE→ROLLED_BACK 合法（可跳步）")
    void should_jumpToRolledBack_When_grayWrite() {
        assertDoesNotThrow(() -> machine.assertTransition(CutoverState.GRAY_WRITE,
                CutoverState.ROLLED_BACK));
        assertDoesNotThrow(() -> machine.assertRollback(CutoverState.GRAY_WRITE));
    }

    @Test
    @DisplayName("should_rejectRollback_When_draft：DRAFT 不允许回滚")
    void should_rejectRollback_When_draft() {
        assertThrows(IllegalStateTransitionException.class,
                () -> machine.assertRollback(CutoverState.DRAFT));
    }

    @Test
    @DisplayName("should_rejectAnyTransition_When_terminal：DONE/ROLLED_BACK 不可跃出")
    void should_rejectAnyTransition_When_terminal() {
        assertTrue(planFor(CutoverState.DONE).isEmpty());
        assertTrue(planFor(CutoverState.ROLLED_BACK).isEmpty());
        for (CutoverState to : CutoverState.values()) {
            assertThrows(IllegalStateTransitionException.class,
                    () -> machine.assertTransition(CutoverState.DONE, to));
            assertThrows(IllegalStateTransitionException.class,
                    () -> machine.assertTransition(CutoverState.ROLLED_BACK, to));
        }
    }

    /**
     * @param state 状态
     * @return 允许的下一步
     */
    private static List<CutoverState> planFor(CutoverState state) {
        return state.allowedNext();
    }

    @Test
    @DisplayName("should_returnNull_When_multiBranchOrTerminal：nextForward 只对单分支返回")
    void should_returnNull_When_multiBranchOrTerminal() {
        assertEquals(CutoverState.PRE_CHECK, machine.nextForward(CutoverState.DRAFT));
        assertEquals(CutoverState.GRAY_WRITE, machine.nextForward(CutoverState.GRAY_READ));
        assertNull(machine.nextForward(CutoverState.PRE_CHECK), "PRE_CHECK 有两个出口");
        assertNull(machine.nextForward(CutoverState.DONE));
    }

    @Test
    @DisplayName("should_beTrue_When_canTransition：只读判定不抛异常")
    void should_beTrue_When_canTransition() {
        assertTrue(machine.canTransition(CutoverState.GRAY_WRITE, CutoverState.FULL_TARGET));
        assertFalse(machine.canTransition(CutoverState.GRAY_WRITE, CutoverState.OBSERVE));
    }

    @Test
    @DisplayName("should_throw_When_nullStates：空状态视为非法")
    void should_throw_When_nullStates() {
        assertThrows(DbSyncException.class, () -> machine.assertTransition(null, CutoverState.DRAFT));
        assertThrows(DbSyncException.class,
                () -> machine.assertTransition(CutoverState.DRAFT, null));
    }
}
