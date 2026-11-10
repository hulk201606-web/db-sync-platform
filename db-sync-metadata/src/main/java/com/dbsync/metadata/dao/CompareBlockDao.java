package com.dbsync.metadata.dao;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.model.BlockResult;
import com.dbsync.common.model.PkRange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 比对分块 DAO（一级校验和：count + sum(hash)）。
 *
 * <p>设计意图：块内 15 万行只要 count 与 sum(hash) 都相同就判定"块一致"，
 * 只有不一致的块才下钻逐行比对（{@code drilled=1}）。
 * 这样 1 亿行的表通常只需要 1/1000 的逐行比对开销。
 */
@Repository
public class CompareBlockDao extends AbstractMetaDao {

    private final RowMapper<BlockResult> mapper = new RowMapper<BlockResult>() {
        @Override
        public BlockResult mapRow(ResultSet rs, int rowNum) throws SQLException {
            // 库里 pk_start/pk_end 统一按字符串存；读出的 range 仅用于展示/报表，
            // 下钻比对的真实边界由 BlockPlanner 重新按 RangeKind 计算（不能依赖这里的 kind）
            PkRange range = new PkRange(rs.getString("pk_start"), rs.getString("pk_end"),
                    RangeKind.STRING);
            return new BlockResult(rs.getLong("id"), rs.getInt("block_no"),
                    rs.getLong("table_mapping_id"), range,
                    nullToZero(rs, "src_count"), nullToZero(rs, "tgt_count"),
                    rs.getString("src_sum_hash"), rs.getString("tgt_sum_hash"),
                    rs.getString("src_row_hash"), rs.getString("tgt_row_hash"),
                    rs.getInt("matched") != 0, rs.getInt("diff_rows"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public CompareBlockDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 批量写入块结果。
     *
     * @param jobId  作业 ID
     * @param blocks 块结果
     * @return 影响行数
     */
    public int insertBatch(long jobId, List<BlockResult> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }
        java.time.Instant now = now();
        List<Object[]> args = new ArrayList<Object[]>(blocks.size());
        for (BlockResult b : blocks) {
            args.add(new Object[] {b.getBlockId(), jobId, b.getTableMappingId(), b.getBlockNo(),
                    b.getRange() == null || b.getRange().getStart() == null ? null
                            : String.valueOf(b.getRange().getStart()),
                    b.getRange() == null || b.getRange().getEnd() == null ? null
                            : String.valueOf(b.getRange().getEnd()),
                    b.getSrcCount(), b.getTgtCount(), b.getSrcSumHash(), b.getTgtSumHash(),
                    b.getSrcRowHash(), b.getTgtRowHash(), b.isMatched() ? 1 : 0,
                    b.getDiffRows(), toTimestamp(now)});
        }
        int[] rows = jdbc.batchUpdate("INSERT INTO sync_compare_block (id,job_id,"
                + "table_mapping_id,block_no,pk_start,pk_end,src_count,tgt_count,src_sum_hash,"
                + "tgt_sum_hash,src_row_hash,tgt_row_hash,matched,diff_rows,drilled,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)", args);
        int sum = 0;
        for (int r : rows) {
            sum += r;
        }
        return sum;
    }

    /**
     * 按作业列出全部块。
     *
     * @param jobId 作业 ID
     * @return 块结果
     */
    public List<BlockResult> findByJob(long jobId) {
        return jdbc.query("SELECT * FROM sync_compare_block WHERE job_id=? ORDER BY block_no",
                mapper, jobId);
    }

    /**
     * 列出不一致块（下钻对象）。
     *
     * @param jobId 作业 ID
     * @return 块结果
     */
    public List<BlockResult> findMismatched(long jobId) {
        return jdbc.query("SELECT * FROM sync_compare_block WHERE job_id=? AND matched=0 "
                + "ORDER BY block_no", mapper, jobId);
    }

    /**
     * 标记块已下钻。
     *
     * @param jobId    作业 ID
     * @param blockNo  块号
     * @param diffRows 实际差异行数
     * @return 影响行数
     */
    public int markDrilled(long jobId, int blockNo, int diffRows) {
        return jdbc.update("UPDATE sync_compare_block SET drilled=1,diff_rows=? "
                + "WHERE job_id=? AND block_no=?", diffRows, jobId, blockNo);
    }

    /**
     * 统计一致块数。
     *
     * @param jobId 作业 ID
     * @return 数量
     */
    public long countMatched(long jobId) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_compare_block WHERE job_id=? "
                + "AND matched=1", Long.class, jobId);
        return n == null ? 0L : n;
    }

    /**
     * 统计不一致块数。
     *
     * @param jobId 作业 ID
     * @return 数量
     */
    public long countMismatched(long jobId) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_compare_block WHERE job_id=? "
                + "AND matched=0", Long.class, jobId);
        return n == null ? 0L : n;
    }

    /**
     * 删除作业下全部块。
     *
     * @param jobId 作业 ID
     * @return 影响行数
     */
    public int deleteByJob(long jobId) {
        return jdbc.update("DELETE FROM sync_compare_block WHERE job_id=?", jobId);
    }

    /**
     * 可空计数读取。
     *
     * @param rs     结果集
     * @param column 列名
     * @return 值；NULL 返回 0
     * @throws SQLException 读取失败
     */
    private static long nullToZero(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? 0L : v;
    }
}
