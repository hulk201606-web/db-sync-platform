package com.dbsync.metadata.dao;

import com.dbsync.common.model.ColumnMapping;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 列映射 DAO（含人工覆盖记录，架构 §6.4 "覆盖必须留痕"）。
 */
@Repository
public class ColumnMappingDao extends AbstractMetaDao {

    private final RowMapper<ColumnMapping> mapper = new RowMapper<ColumnMapping>() {
        @Override
        public ColumnMapping mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ColumnMapping(rs.getString("source_column"), rs.getString("target_column"),
                    rs.getString("source_type"), rs.getString("target_type"),
                    rs.getString("mapped_type"), rs.getString("override_by"),
                    rs.getInt("is_primary_key") != 0, rs.getInt("is_auto_increment") != 0,
                    rs.getInt("ordinal"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public ColumnMappingDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 批量写入列映射。
     *
     * @param tableMappingId 表映射 ID
     * @param mappings       列映射（ordinal 由调用方保证连续）
     * @return 影响行数
     */
    public int insertBatch(long tableMappingId, List<ColumnMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return 0;
        }
        Instant now = now();
        List<Object[]> args = new ArrayList<Object[]>(mappings.size());
        for (ColumnMapping c : mappings) {
            args.add(new Object[] {nextId(), tableMappingId, c.getSourceColumn(),
                    c.getTargetColumn(), c.getSourceType(), c.getTargetType(),
                    c.getMappedType(), c.getOverrideBy(), c.isPrimaryKey() ? 1 : 0,
                    c.isAutoIncrement() ? 1 : 0, c.getOrdinal(), toTimestamp(now)});

        }
        int[] rows = jdbc.batchUpdate("INSERT INTO sync_column_mapping (id,table_mapping_id,"
                + "source_column,target_column,source_type,target_type,mapped_type,override_by,"
                + "nullable,is_primary_key,is_auto_increment,ordinal,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,1,?,?,?,?)", args);
        int sum = 0;
        for (int r : rows) {
            sum += r;
        }
        return sum;
    }

    /**
     * 按表映射列出列映射（按 ordinal 排序，保证写入列序稳定）。
     *
     * @param tableMappingId 表映射 ID
     * @return 列映射列表
     */
    public List<ColumnMapping> findByTableMappingId(long tableMappingId) {
        return jdbc.query("SELECT * FROM sync_column_mapping WHERE table_mapping_id=? "
                + "ORDER BY ordinal", mapper, tableMappingId);
    }

    /**
     * 统计列数。
     *
     * @param tableMappingId 表映射 ID
     * @return 列数
     */
    public int countByTableMappingId(long tableMappingId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_column_mapping "
                + "WHERE table_mapping_id=?", Integer.class, tableMappingId);
        return n == null ? 0 : n;
    }

    /**
     * 删除表映射下全部列映射（重新发现时先删后插）。
     *
     * @param tableMappingId 表映射 ID
     * @return 影响行数
     */
    public int deleteByTableMappingId(long tableMappingId) {
        return jdbc.update("DELETE FROM sync_column_mapping WHERE table_mapping_id=?",
                tableMappingId);
    }
}
