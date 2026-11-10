package com.dbsync.cdc;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.util.HashUtil;
import java.util.List;

/**
 * 分组器：{@code group = fnv1a64(normalizedPkTuple) % groupCount}。
 *
 * <p><b>为什么按主键哈希分组而不是按表/按范围分</b>：组内必须串行（同一行的
 * update、delete 顺序不能乱），组间必须无关（否则一条毒消息能卡死全库）。
 * 主键哈希同时满足两点，并且扩容/缩容时只需重算哈希，不需要搬迁数据。
 *
 * <p><b>为什么"归一化"必须在这里就做对</b>：{@code 1}(Long) 与 {@code 1.00}(BigDecimal)
 * 是同一个主键，如果哈希不同就会落到不同组 —— 同一行的两次变更被并行应用，
 * 最终值取决于调度顺序，等于放弃了 CDC 的顺序保证。
 * {@link HashUtil#pkHash(List)} 用 {@code stripTrailingZeros().toPlainString()} 做归一，
 * 本类是它唯一的调用点（K4：归一化单点）。
 */
public class GroupAssigner {

    /**
     * 计算分组号。
     *
     * @param pkTuple    主键值元组（顺序与 {@code pkCols} 一致）
     * @param pkCols     主键列元数据（用于校验元组长度）
     * @param groupCount 组数（≤1 时恒为 0）
     * @return 组号，范围 {@code [0, groupCount)}
     */
    public int groupOf(List<Object> pkTuple, List<ColumnMeta> pkCols, int groupCount) {
        if (groupCount <= 1) {
            return 0;
        }
        if (pkCols != null && !pkCols.isEmpty() && pkTuple != null
                && pkTuple.size() != pkCols.size()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "主键元组长度 " + pkTuple.size() + " 与主键列数 " + pkCols.size() + " 不一致");
        }
        long hash = HashUtil.pkHash(pkTuple);
        // floorMod：pkHash 是 long（可为负），取模必须用 floorMod 否则组号会变负
        return (int) Math.floorMod(hash, (long) groupCount);
    }
}
