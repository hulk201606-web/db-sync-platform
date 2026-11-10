package com.dbsync.metadata.dao;

import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.TableMapping;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 表映射 DAO。
 *
 * <p>唯一约束 {@code uk_map(task_id, source_schema, source_table)} 保证同一任务内不会重复映射同一张源表。
 *
 * <p><b>冲突策略（V3）</b>：{@code conflict_policy} 是表级策略列（默认 {@code SOURCE_WINS}），
 * 读写都走 {@link ConflictPolicy#parse(String)}——库里的值若被人工改坏，
 * 读取即抛 {@code E1001}，不做静默兜底（静默兜底的后果是数据被错误一方覆盖且无人知晓）。
 */
@Repository
public class TableMappingDao extends AbstractMetaDao {

    private final RowMapper<TableMapping> mapper = new RowMapper<TableMapping>() {
        @Override
        public TableMapping mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TableMapping(rs.getLong("id"), rs.getLong("task_id"),
                    rs.getString("source_schema"), rs.getString("source_table"),
                    rs.getString("target_schema"), rs.getString("target_table"),
                    rs.getInt("has_primary_key") != 0, rs.getString("range_kind"),
                    rs.getLong("row_count_estimate"), rs.getString("generated_ddl"),
                    rs.getInt("ddl_applied") != 0, rs.getInt("enabled") != 0,
                    ConflictPolicy.parse(rs.getString("conflict_policy")));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public TableMappingDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 新增单条映射。
     *
     * @param m 映射
     * @return 影响行数
     */
    public int insert(TableMapping m) {
        Instant now = now();
        return jdbc.update("INSERT INTO sync_table_mapping (id,task_id,source_schema,source_table,"
                        + "target_schema,target_table,row_count_estimate,has_primary_key,range_kind,"
                        + "generated_ddl,ddl_applied,enabled,conflict_policy,version,created_at,"
                        + "updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
                m.getId(), m.getTaskId(), m.getSourceSchema(), m.getSourceTable(),
                m.getTargetSchema(), m.getTargetTable(), m.getRowCountEstimate(),
                m.isHasPrimaryKey() ? 1 : 0, m.getRangeKind(), m.getGeneratedDdl(),
                m.isDdlApplied() ? 1 : 0, m.isEnabled() ? 1 : 0,
                policyName(m.getConflictPolicy()), toTimestamp(now), toTimestamp(now));
    }

    /**
     * 批量新增映射（自动发现阶段一次写入，减少往返）。
     *
     * @param mappings 映射列表
     * @return 影响行数
     */
    public int insertBatch(List<TableMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return 0;
        }
        Instant now = now();
        List<Object[]> args = new ArrayList<Object[]>(mappings.size());
        for (TableMapping m : mappings) {
            args.add(new Object[] {m.getId(), m.getTaskId(), m.getSourceSchema(),
                    m.getSourceTable(), m.getTargetSchema(), m.getTargetTable(),
                    m.getRowCountEstimate(), m.isHasPrimaryKey() ? 1 : 0, m.getRangeKind(),
                    m.getGeneratedDdl(), m.isDdlApplied() ? 1 : 0, m.isEnabled() ? 1 : 0,
                    policyName(m.getConflictPolicy()), toTimestamp(now), toTimestamp(now)});
        }
        int[] rows = jdbc.batchUpdate("INSERT INTO sync_table_mapping (id,task_id,source_schema,"
                + "source_table,target_schema,target_table,row_count_estimate,has_primary_key,"
                + "range_kind,generated_ddl,ddl_applied,enabled,conflict_policy,version,created_at,"
                + "updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)", args);
        int sum = 0;
        for (int r : rows) {
            sum += r;
        }
        return sum;
    }

    /**
     * 按 ID 查询。
     *
     * @param id 映射 ID
     * @return 映射
     */
    public Optional<TableMapping> findById(long id) {
        List<TableMapping> list = jdbc.query(
                "SELECT * FROM sync_table_mapping WHERE id=?", mapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 按任务列出全部映射（含禁用的，调度器需要全量判断）。
     *
     * @param taskId 任务 ID
     * @return 映射列表
     */
    public List<TableMapping> findByTask(long taskId) {
        return jdbc.query("SELECT * FROM sync_table_mapping WHERE task_id=? ORDER BY id",
                mapper, taskId);
    }

    /**
     * 按任务列出启用的映射（迁移/CDC/比对只处理启用的）。
     *
     * @param taskId 任务 ID
     * @return 映射列表
     */
    public List<TableMapping> findEnabledByTask(long taskId) {
        return jdbc.query("SELECT * FROM sync_table_mapping WHERE task_id=? AND enabled=1 "
                + "ORDER BY id", mapper, taskId);
    }

    /**
     * 回写生成的 DDL。
     *
     * @param id            映射 ID
     * @param ddl           DDL 文本
     * @param riskTagsJson  风险标签（JSON 数组字符串，可为 null）
     * @return 影响行数
     */
    public int updateDdl(long id, String ddl, String riskTagsJson) {
        return jdbc.update("UPDATE sync_table_mapping SET generated_ddl=?,risk_tags=?,"
                + "version=version+1,updated_at=? WHERE id=?",
                ddl, riskTagsJson, toTimestamp(now()), id);
    }

    /**
     * 标记目标表已建。
     *
     * @param id 映射 ID
     * @return 影响行数
     */
    public int markDdlApplied(long id) {
        return jdbc.update("UPDATE sync_table_mapping SET ddl_applied=1,version=version+1,"
                + "updated_at=? WHERE id=?", toTimestamp(now()), id);
    }

    /**
     * 更新行数估算。
     *
     * @param id        映射 ID
     * @param estimate  估算行数
     * @return 影响行数
     */
    public int updateRowCountEstimate(long id, long estimate) {
        return jdbc.update("UPDATE sync_table_mapping SET row_count_estimate=?,"
                + "updated_at=? WHERE id=?", estimate, toTimestamp(now()), id);
    }

    /**
     * 启用/禁用映射。
     *
     * @param id      映射 ID
     * @param enabled 是否启用
     * @return 影响行数
     */
    public int setEnabled(long id, boolean enabled) {
        return jdbc.update("UPDATE sync_table_mapping SET enabled=?,version=version+1,"
                + "updated_at=? WHERE id=?", enabled ? 1 : 0, toTimestamp(now()), id);
    }

    /**
     * 更新表级冲突策略（CDC-06「按表可配」）。
     *
     * <p>不改 {@code enabled}、不动乐观版本以外的字段：策略是一个独立的业务配置，
     * 与"是否参与同步"正交（禁用中的表也应能改策略，恢复时立刻生效）。
     *
     * @param id     映射 ID
     * @param policy 策略（不可 null）
     * @return 影响行数（0 表示映射不存在）
     */
    public int updateConflictPolicy(long id, ConflictPolicy policy) {
        if (policy == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "表级冲突策略不能为空")
                    .with("tableMappingId", Long.valueOf(id));
        }
        return jdbc.update("UPDATE sync_table_mapping SET conflict_policy=?,version=version+1,"
                        + "updated_at=? WHERE id=?",
                policy.name(), toTimestamp(now()), id);
    }

    /**
     * 删除任务下全部映射（物理删除，仅任务草稿态可用）。
     *
     * @param taskId 任务 ID
     * @return 影响行数
     */
    public int deleteByTask(long taskId) {
        return jdbc.update("DELETE FROM sync_table_mapping WHERE task_id=?", taskId);
    }

    /**
     * 策略 → 列值（null 视为默认）。
     *
     * @param policy 策略
     * @return 枚举名
     */
    private static String policyName(ConflictPolicy policy) {
        return (policy == null ? ConflictPolicy.SOURCE_WINS : policy).name();
    }
}
