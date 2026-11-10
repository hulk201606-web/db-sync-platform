package com.dbsync.metadata.dao;

import com.dbsync.common.enums.DiffType;
import com.dbsync.common.model.DiffRecord;
import com.dbsync.common.util.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 差异明细 DAO（二级下钻结果）。
 *
 * <p>{@code src_row_json}/{@code tgt_row_json} 保留原始行快照，是"修复可回滚"的前提：
 * 修复前必须能把目标行还原（架构 §8.4 修复动作需备份）。
 */
@Repository
public class CompareDiffDao extends AbstractMetaDao {

    /** 单次 {@code IN (...)} 参数上限。 */
    private static final int IN_CHUNK = 200;

    private final RowMapper<DiffRecord> mapper = new RowMapper<DiffRecord>() {
        @Override
        public DiffRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DiffRecord(rs.getLong("id"), rs.getLong("job_id"), rs.getLong("block_id"),
                    rs.getLong("table_mapping_id"), rs.getString("pk_json"),
                    rs.getLong("pk_hash"), DiffType.valueOf(rs.getString("diff_type")),
                    parseColumnDiffs(rs.getString("diff_columns")),
                    rs.getString("src_row_json"), rs.getString("tgt_row_json"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public CompareDiffDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 批量写入差异明细。
     *
     * @param diffs 差异列表
     * @return 影响行数
     */
    public int insertBatch(List<DiffRecord> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return 0;
        }
        java.time.Instant now = now();
        List<Object[]> args = new ArrayList<Object[]>(diffs.size());
        for (DiffRecord d : diffs) {
            args.add(new Object[] {d.getId(), d.getJobId(), d.getBlockId(),
                    d.getTableMappingId(), d.getPkJson(), d.getPkHash(), d.getDiffType().name(),
                    JsonUtil.toJson(d.getColumnDiffs()), d.getSrcRowJson(), d.getTgtRowJson(),
                    toTimestamp(now)});
        }
        int[] rows = jdbc.batchUpdate("INSERT INTO sync_compare_diff (id,job_id,block_id,"
                + "table_mapping_id,pk_json,pk_hash,diff_type,diff_columns,src_row_json,"
                + "tgt_row_json,repair_state,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,'NONE',?)", args);
        int sum = 0;
        for (int r : rows) {
            sum += r;
        }
        return sum;
    }

    /**
     * 按作业列出差异（分页用 limit）。
     *
     * @param jobId 作业 ID
     * @param limit 上限
     * @return 差异列表
     */
    public List<DiffRecord> findByJob(long jobId, int limit) {
        int effective = limit <= 0 ? 500 : limit;
        return jdbc.query("SELECT * FROM sync_compare_diff WHERE job_id=? "
                + "ORDER BY table_mapping_id, pk_hash LIMIT " + effective, mapper, jobId);
    }

    /**
     * 按差异类型筛选。
     *
     * @param jobId    作业 ID
     * @param diffType 类型
     * @param limit    上限
     * @return 差异列表
     */
    public List<DiffRecord> findByJobAndType(long jobId, DiffType diffType, int limit) {
        int effective = limit <= 0 ? 500 : limit;
        return jdbc.query("SELECT * FROM sync_compare_diff WHERE job_id=? AND diff_type=? "
                        + "ORDER BY table_mapping_id, pk_hash LIMIT " + effective,
                mapper, jobId, diffType.name());
    }

    /**
     * 分页查询差异（T37：{@code /diffs?page=&size=&type=}）。
     *
     * <p>// ADDED: 原有 {@link #findByJob} / {@link #findByJobAndType} 只支持 limit，
     * 无法做真正的翻页（前端要 page/size）。排序与 {@code idx_diff_job} 对齐。
     *
     * @param jobId    作业 ID
     * @param diffType 类型（可 null 表示全部）
     * @param offset   偏移
     * @param limit    每页条数
     * @return 差异列表
     */
    public List<DiffRecord> findPage(long jobId, DiffType diffType, int offset, int limit) {
        int effective = limit <= 0 ? 100 : Math.min(limit, 1000);
        int skip = Math.max(0, offset);
        if (diffType == null) {
            return jdbc.query("SELECT * FROM sync_compare_diff WHERE job_id=? "
                            + "ORDER BY table_mapping_id, pk_hash LIMIT " + effective + " OFFSET "
                            + skip, mapper, jobId);
        }
        return jdbc.query("SELECT * FROM sync_compare_diff WHERE job_id=? AND diff_type=? "
                        + "ORDER BY table_mapping_id, pk_hash LIMIT " + effective + " OFFSET " + skip,
                mapper, jobId, diffType.name());
    }

    /**
     * 按主键哈希查差异（单行追溯）。
     *
     * @param jobId  作业 ID
     * @param pkHash 主键哈希
     * @return 差异列表
     */
    public List<DiffRecord> findByPkHash(long jobId, long pkHash) {
        return jdbc.query("SELECT * FROM sync_compare_diff WHERE job_id=? AND pk_hash=?",
                mapper, jobId, pkHash);
    }

    /**
     * 按 ID 批量查询差异（// ADDED：T36 的 {@code DiffRepairer} 只有差异 ID 列表，
     * 文档 DAO 清单缺这个入口；分批 IN 避免 SQL 过长）。
     *
     * @param ids 差异 ID 列表
     * @return 差异记录（顺序与入参一致；不存在的 ID 会被跳过）
     */
    public List<DiffRecord> findByIds(List<Long> ids) {
        Map<Long, DiffRecord> found = new LinkedHashMap<Long, DiffRecord>();
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<DiffRecord>();
        }
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(from + IN_CHUNK, ids.size()));
            StringBuilder sql = new StringBuilder("SELECT * FROM sync_compare_diff WHERE id IN (");
            List<Object> args = new ArrayList<Object>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append('?');
                args.add(chunk.get(i));
            }
            sql.append(')');
            for (DiffRecord record : jdbc.query(sql.toString(), mapper, args.toArray())) {
                found.put(Long.valueOf(record.getId()), record);
            }
        }
        List<DiffRecord> ordered = new ArrayList<DiffRecord>(found.size());
        for (Long id : ids) {
            DiffRecord record = found.get(id);
            if (record != null) {
                ordered.add(record);
            }
        }
        return ordered;
    }

    /**
     * 批量更新修复状态。
     *
     * @param ids         差异 ID 列表
     * @param repairState 状态（NONE/FIXED/FAILED/IGNORED）
     * @param repairBy    操作人
     * @param backupTable 备份表名（可 null）
     * @return 影响行数
     */
    public int updateRepairState(List<Long> ids, String repairState, String repairBy,
                                 String backupTable) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        java.time.Instant now = now();
        int sum = 0;
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(from + IN_CHUNK, ids.size()));
            StringBuilder sql = new StringBuilder("UPDATE sync_compare_diff SET repair_state=?,"
                    + "repair_by=?,repair_at=?,backup_table=? WHERE id IN (");
            List<Object> args = new ArrayList<Object>(chunk.size() + 4);
            args.add(repairState);
            args.add(repairBy);
            args.add(toTimestamp(now));
            args.add(backupTable);
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append('?');
                args.add(chunk.get(i));
            }
            sql.append(')');
            sum += jdbc.update(sql.toString(), args.toArray());
        }
        return sum;
    }

    /**
     * 统计作业差异行数。
     *
     * @param jobId 作业 ID
     * @return 数量
     */
    public long countByJob(long jobId) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_compare_diff WHERE job_id=?",
                Long.class, jobId);
        return n == null ? 0L : n;
    }

    /**
     * 按差异类型统计（报告饼图数据）。
     *
     * @param jobId 作业 ID
     * @return 类型 → 数量（三种类型都出现）
     */
    public Map<DiffType, Long> countByType(long jobId) {
        Map<DiffType, Long> result = new LinkedHashMap<DiffType, Long>();
        for (DiffType type : DiffType.values()) {
            result.put(type, Long.valueOf(0L));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT diff_type, COUNT(1) AS cnt "
                + "FROM sync_compare_diff WHERE job_id=? GROUP BY diff_type", jobId);
        for (Map<String, Object> row : rows) {
            Object type = row.get("diff_type");
            Object cnt = row.get("cnt");
            if (type == null || cnt == null) {
                continue;
            }
            try {
                result.put(DiffType.valueOf(String.valueOf(type)),
                        Long.valueOf(((Number) cnt).longValue()));
            } catch (IllegalArgumentException ignored) {
                // 未知类型（历史数据）忽略，不阻塞统计
            }
        }
        return result;
    }

    /**
     * 删除作业下全部差异。
     *
     * @param jobId 作业 ID
     * @return 影响行数
     */
    public int deleteByJob(long jobId) {
        return jdbc.update("DELETE FROM sync_compare_diff WHERE job_id=?", jobId);
    }

    /**
     * 解析列差异 JSON：{@code {"col":{"src":"..","tgt":".."}}}。
     *
     * @param json JSON
     * @return 列差异；空返回空 Map
     */
    private static Map<String, Map<String, String>> parseColumnDiffs(String json) {
        Map<String, Map<String, String>> result =
                new LinkedHashMap<String, Map<String, String>>();
        if (json == null || json.isEmpty()) {
            return result;
        }
        Map<String, Object> root = JsonUtil.parseObject(json);
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            Map<String, String> pair = new LinkedHashMap<String, String>();
            if (entry.getValue() instanceof Map) {
                for (Map.Entry<?, ?> inner : ((Map<?, ?>) entry.getValue()).entrySet()) {
                    pair.put(String.valueOf(inner.getKey()),
                            inner.getValue() == null ? null : String.valueOf(inner.getValue()));
                }
            }
            result.put(entry.getKey(), pair);
        }
        return result;
    }
}
