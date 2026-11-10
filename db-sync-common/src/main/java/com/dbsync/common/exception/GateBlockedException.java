package com.dbsync.common.exception;

import java.util.List;
import java.util.Map;

/**
 * Gate 卡点阻断（E6002）。携带 4 个指标的实测值与阈值快照，前端只展示不判断。
 */
public class GateBlockedException extends DbSyncException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造。
     *
     * @param reasons 阻断原因列表
     * @param items   指标明细（每项含 code/actual/threshold/pass/hint）
     */
    public GateBlockedException(List<String> reasons, List<Map<String, Object>> items) {
        super(ErrorCode.GATE_BLOCKED, null, String.join("；", reasons));
        with("reasons", List.copyOf(reasons));
        with("items", List.copyOf(items));
    }
}
