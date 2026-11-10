package com.dbsync.common.exception;

import com.dbsync.common.enums.CutoverState;
import java.util.List;

/**
 * 非法状态跃迁（E6001）。消息中必须带 {@code allowed=[...]} 便于前端提示。
 */
public class IllegalStateTransitionException extends DbSyncException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造。
     *
     * @param from    当前状态
     * @param to      请求的目标状态
     * @param allowed 当前状态允许的目标列表
     */
    public IllegalStateTransitionException(CutoverState from, CutoverState to, List<CutoverState> allowed) {
        super(ErrorCode.ILLEGAL_TRANSITION, null,
                from + " → " + to + "。允许目标：allowed=" + allowed);
        with("current", String.valueOf(from));
        with("requested", String.valueOf(to));
        with("allowed", allowed);
    }
}
