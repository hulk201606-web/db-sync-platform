package com.dbsync.compare;

import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.ShardPlanOptions;
import com.dbsync.core.shard.ShardPlan;
import com.dbsync.core.shard.ShardPlanner;

/**
 * 块规划器（T35）：把表按主键切成"每块 N 行"的区间。
 *
 * <p><b>复用存量分片算法</b>（架构 §8.4 明确要求）：比对块与迁移分片是同一个数学问题，
 * 直接用 {@link ShardPlanner}，避免出现两套区间算法导致"迁移按 A 切、比对按 B 切"，
 * 那样每块边界都不一致，校验和永远对不上。
 *
 * <p>本类不抛业务异常，纯计算；区间语义沿用 {@link PkRange}：{@code (start, end]}。
 *
 * <p><b>已知限制</b>：联合主键只按首列切分（与 {@link ShardPlanner} 一致）；
 * 首列非数值时退化为前缀区间，块内行数不再精确等于 {@code blockRows}，
 * 但这不影响正确性（校验和仍然按实际区间计算）。
 */
public class BlockPlanner {

    /** 单表最多切出的块数（防止 1 亿行 × 小块把元数据库塞满）。 */
    public static final int MAX_BLOCKS = 4096;

    private final ShardPlanner shardPlanner;

    /**
     * 构造（默认分片算法）。
     */
    public BlockPlanner() {
        this(new ShardPlanner());
    }

    /**
     * 构造。
     *
     * @param shardPlanner 复用存量分片算法
     */
    public BlockPlanner(ShardPlanner shardPlanner) {
        this.shardPlanner = shardPlanner == null ? new ShardPlanner() : shardPlanner;
    }

    /**
     * 规划块区间。
     *
     * @param table         表元数据
     * @param blockRows     每块目标行数（≤0 取 10 万）
     * @param estimatedRows 估算总行数（≤0 表示未知，退化为单块）
     * @return 区间列表（顺序即块号，块号从 0 开始）
     */
    public java.util.List<PkRange> plan(TableMeta table, int blockRows, long estimatedRows) {
        int rows = blockRows <= 0 ? 100_000 : blockRows;
        ShardPlanOptions options = new ShardPlanOptions(rows, 1, MAX_BLOCKS, 32);
        // 未知真实 min/max 时用 "主键空间当作 [0, 估算行数]" 的存量算法（文档签名版）
        return shardPlanner.plan(table, estimatedRows, options).getRanges();
    }

    /**
     * 规划块区间（已知真实主键 min/max 时切分更均匀）。
     *
     * @param table         表元数据
     * @param min           主键最小值（null 表示未知）
     * @param max           主键最大值（null 表示未知）
     * @param blockRows     每块目标行数
     * @param estimatedRows 估算总行数
     * @return 区间列表
     */
    public java.util.List<PkRange> plan(TableMeta table, Object min, Object max, int blockRows,
                                        long estimatedRows) {
        int rows = blockRows <= 0 ? 100_000 : blockRows;
        ShardPlanOptions options = new ShardPlanOptions(rows, 1, MAX_BLOCKS, 32);
        if (min == null || max == null) {
            return shardPlanner.plan(table, estimatedRows, options).getRanges();
        }
        return shardPlanner.plan(table, min, max, estimatedRows, options).getRanges();
    }

    /**
     * 规划块区间（返回完整计划，含告警）。
     *
     * @param table         表元数据
     * @param blockRows     每块目标行数
     * @param estimatedRows 估算总行数
     * @return 分片计划
     */
    public ShardPlan planDetailed(TableMeta table, int blockRows, long estimatedRows) {
        int rows = blockRows <= 0 ? 100_000 : blockRows;
        return shardPlanner.plan(table, estimatedRows,
                new ShardPlanOptions(rows, 1, MAX_BLOCKS, 32));
    }
}
