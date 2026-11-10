package com.dbsync.metadata.dao;

import com.dbsync.common.model.CompareVerdictSnapshot;
import com.dbsync.common.util.JsonUtil;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 比对作业 DAO。
 *
 * <p>Gate G1（比对通过率）与 G1'（结果新鲜度）从这里取数：
 * <ul>
 *   <li>通过率：{@code pass_rate}；</li>
 *   <li>新鲜度：{@code finished_at}，超期（默认 6h）即视为"没有有效比对结论"，Gate 不允许切流。</li>
 * </ul>
 */
@Repository
public class CompareJobDao extends AbstractMetaDao {

    /**
     * 比对作业行（ADDED：04 文档列了 CompareJobDao 但 common 未列作业模型；
     * 用 DAO 内嵌套类承载，避免为一个纯台账行在 common 加模型）。
     */
    public static final class CompareJobRow {

        private final long id;
        private final long taskId;
        private final Long tableMappingId;
        private final String triggerType;
        private final String state;
        private final int totalBlocks;
        private final int doneBlocks;
        private final int diffBlocks;
        private final long totalRows;
        private final long diffRows;
        private final Double passRate;
        private final String verdict;
        private final Instant startedAt;
        private final Instant finishedAt;

        /**
         * 构造。
         *
         * @param id             作业 ID
         * @param taskId         任务 ID
         * @param tableMappingId 表映射 ID（可 null 表示全任务）
         * @param triggerType    触发方式（MANUAL/SCHEDULED/PRE_CUTOVER）
         * @param state          状态
         * @param totalBlocks    总块数
         * @param doneBlocks     已完成块数
         * @param diffBlocks     差异块数
         * @param totalRows      总行数
         * @param diffRows       差异行数
         * @param passRate       通过率
         * @param verdict        结论
         * @param startedAt      开始时间
         * @param finishedAt     结束时间
         */
        public CompareJobRow(long id, long taskId, Long tableMappingId, String triggerType,
                             String state, int totalBlocks, int doneBlocks, int diffBlocks,
                             long totalRows, long diffRows, Double passRate, String verdict,
                             Instant startedAt, Instant finishedAt) {
            this.id = id;
            this.taskId = taskId;
            this.tableMappingId = tableMappingId;
            this.triggerType = triggerType;
            this.state = state;
            this.totalBlocks = totalBlocks;
            this.doneBlocks = doneBlocks;
            this.diffBlocks = diffBlocks;
            this.totalRows = totalRows;
            this.diffRows = diffRows;
            this.passRate = passRate;
            this.verdict = verdict;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
        }

        /** @return 作业 ID */
        public long getId() {
            return id;
        }

        /** @return 任务 ID */
        public long getTaskId() {
            return taskId;
        }

        /** @return 表映射 ID */
        public Long getTableMappingId() {
            return tableMappingId;
        }

        /** @return 触发方式 */
        public String getTriggerType() {
            return triggerType;
        }

        /** @return 状态 */
        public String getState() {
            return state;
        }

        /** @return 总块数 */
        public int getTotalBlocks() {
            return totalBlocks;
        }

        /** @return 已完成块数 */
        public int getDoneBlocks() {
            return doneBlocks;
        }

        /** @return 差异块数 */
        public int getDiffBlocks() {
            return diffBlocks;
        }

        /** @return 总行数 */
        public long getTotalRows() {
            return totalRows;
        }

        /** @return 差异行数 */
        public long getDiffRows() {
            return diffRows;
        }

        /** @return 通过率（未完成时 null） */
        public Double getPassRate() {
            return passRate;
        }

        /** @return 结论 */
        public String getVerdict() {
            return verdict;
        }

        /** @return 开始时间 */
        public Instant getStartedAt() {
            return startedAt;
        }

        /** @return 结束时间 */
        public Instant getFinishedAt() {
            return finishedAt;
        }

        @Override
        public String toString() {
            return "CompareJob#" + id + "[" + state + "," + doneBlocks + "/" + totalBlocks + "]";
        }
    }

    private final RowMapper<CompareJobRow> mapper = new RowMapper<CompareJobRow>() {
        @Override
        public CompareJobRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            BigDecimal rate = rs.getBigDecimal("pass_rate");
            return new CompareJobRow(rs.getLong("id"), rs.getLong("task_id"),
                    nullableLong(rs, "table_mapping_id"), rs.getString("trigger_type"),
                    rs.getString("state"), rs.getInt("total_blocks"), rs.getInt("done_blocks"),
                    rs.getInt("diff_blocks"), rs.getLong("total_rows"), rs.getLong("diff_rows"),
                    rate == null ? null : Double.valueOf(rate.doubleValue()),
                    rs.getString("verdict"), instantOf(rs, "started_at"),
                    instantOf(rs, "finished_at"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public CompareJobDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 创建作业。
     *
     * @param jobId          作业 ID
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID（null 表示全任务）
     * @param triggerType    触发方式
     * @param blockRows      分块行数
     * @param parallelism    并行度
     * @param normalizeJson  归一化规则快照
     * @param operator       操作人
     * @return 影响行数
     */
    public int insert(long jobId, long taskId, Long tableMappingId, String triggerType,
                      int blockRows, int parallelism, String normalizeJson, String operator) {
        return jdbc.update("INSERT INTO sync_compare_job (id,task_id,table_mapping_id,trigger_type,"
                        + "state,block_rows,parallelism,algorithms,normalize_json,operator,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                jobId, taskId, tableMappingId, triggerType, "PENDING", blockRows, parallelism,
                "COUNT,SUM,HASH", normalizeJson, operator, toTimestamp(now()));
    }

    /**
     * 置为运行中并记录总块数。
     *
     * @param jobId       作业 ID
     * @param totalBlocks 总块数
     * @return 影响行数
     */
    public int markRunning(long jobId, int totalBlocks) {
        return jdbc.update("UPDATE sync_compare_job SET state=?,total_blocks=?,started_at=? "
                + "WHERE id=?", "RUNNING", totalBlocks, toTimestamp(now()), jobId);
    }

    /**
     * 推进块进度（每完成一块调用一次）。
     *
     * @param jobId      作业 ID
     * @param doneBlocks 已完成块数
     * @param diffBlocks 差异块数
     * @return 影响行数
     */
    public int updateProgress(long jobId, int doneBlocks, int diffBlocks) {
        return jdbc.update("UPDATE sync_compare_job SET done_blocks=?,diff_blocks=? WHERE id=?",
                doneBlocks, diffBlocks, jobId);
    }

    /**
     * 完成作业（写入最终统计与结论）。
     *
     * @param jobId      作业 ID
     * @param totalRows  总行数
     * @param diffRows   差异行数
     * @param passRate   通过率
     * @param verdict    结论（PASS/WARN/FAIL）
     * @param reportPath 报告路径（可 null）
     * @return 影响行数
     */
    public int finish(long jobId, long totalRows, long diffRows, double passRate, String verdict,
                      String reportPath) {
        Instant now = now();
        return jdbc.update("UPDATE sync_compare_job SET state=?,total_rows=?,diff_rows=?,"
                        + "pass_rate=?,verdict=?,finished_at=?,report_path=? WHERE id=?",
                "FINISHED", totalRows, diffRows, BigDecimal.valueOf(passRate), verdict,
                toTimestamp(now), reportPath, jobId);
    }

    /**
     * 标记失败。
     *
     * @param jobId 作业 ID
     * @return 影响行数
     */
    public int markFailed(long jobId) {
        return jdbc.update("UPDATE sync_compare_job SET state=?,finished_at=? WHERE id=?",
                "FAILED", toTimestamp(now()), jobId);
    }

    /**
     * 标记取消（// ADDED：T35 验收 5 要求"cancel 后作业状态 CANCELED"，
     * 文档的 DAO 清单只有 markRunning/finish/markFailed，缺 CANCELED 这一终态）。
     *
     * <p>已完成的块与差异保留，便于事后排查"取消时已经算到哪"。
     *
     * @param jobId 作业 ID
     * @return 影响行数
     */
    public int markCanceled(long jobId) {
        return jdbc.update("UPDATE sync_compare_job SET state=?,finished_at=? WHERE id=?",
                "CANCELED", toTimestamp(now()), jobId);
    }

    /**
     * 按 ID 查询。
     *
     * @param jobId 作业 ID
     * @return 作业行
     */
    public Optional<CompareJobRow> findById(long jobId) {
        List<CompareJobRow> list = jdbc.query("SELECT * FROM sync_compare_job WHERE id=?",
                mapper, jobId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 按任务列出作业（倒序）。
     *
     * @param taskId 任务 ID
     * @param limit  上限
     * @return 作业列表
     */
    public List<CompareJobRow> findByTask(long taskId, int limit) {
        int effective = limit <= 0 ? 20 : limit;
        return jdbc.query("SELECT * FROM sync_compare_job WHERE task_id=? "
                + "ORDER BY created_at DESC LIMIT " + effective, mapper, taskId);
    }

    /**
     * 最近一次<b>成功完成</b>的作业结论快照（Gate G1 的唯一取数口）。
     *
     * @param taskId 任务 ID
     * @return 结论快照；从未完成过返回 empty
     */
    public Optional<CompareVerdictSnapshot> latestFinishedSnapshot(long taskId) {
        List<CompareJobRow> list = jdbc.query("SELECT * FROM sync_compare_job WHERE task_id=? "
                + "AND state=? AND finished_at IS NOT NULL ORDER BY finished_at DESC LIMIT 1",
                mapper, taskId, "FINISHED");
        if (list.isEmpty()) {
            return Optional.empty();
        }
        CompareJobRow row = list.get(0);
        String normalizeJson = jdbc.queryForObject("SELECT normalize_json FROM sync_compare_job "
                + "WHERE id=?", String.class, row.getId());
        Map<String, Object> snapshot = JsonUtil.parseObject(normalizeJson);
        return Optional.of(new CompareVerdictSnapshot(row.getId(), row.getTaskId(),
                row.getPassRate() == null ? 0d : row.getPassRate().doubleValue(),
                row.getTotalRows(), row.getDiffRows(),
                row.getVerdict() == null ? "FAIL" : row.getVerdict(),
                row.getFinishedAt() == null ? Instant.EPOCH : row.getFinishedAt(), snapshot));
    }

    /**
     * 统计未完成作业数（防止重复提交同一任务的比对）。
     *
     * @param taskId 任务 ID
     * @return 数量
     */
    public long countUnfinished(long taskId) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_compare_job WHERE task_id=? "
                + "AND state IN (?,?)", Long.class, taskId, "PENDING", "RUNNING");
        return n == null ? 0L : n;
    }

    /**
     * 删除任务下全部作业。
     *
     * @param taskId 任务 ID
     * @return 影响行数
     */
    public int deleteByTask(long taskId) {
        return jdbc.update("DELETE FROM sync_compare_job WHERE task_id=?", taskId);
    }
}
