package com.dbsync.metadata.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 触发器部署台账 DAO。
 *
 * <p>记录"每张源表装了哪些触发器、DDL 是什么、装没装成功"，用于：
 * <ul>
 *   <li>重装/卸载触发器（切流完成后必须清理源库触发器，PRD §6.4）；</li>
 *   <li>启动自检（台账有、库里没有 → 漂移告警）；</li>
 *   <li>多实例部署时避免重复建触发器（依赖 {@code uk_trg(task_id, table_mapping_id)}）。</li>
 * </ul>
 */
@Repository
public class TriggerDefDao extends AbstractMetaDao {

    /**
     * 触发器台账行（ADDED：04 文档列了触发器台账表与 DAO，但未在 common 模块列值对象；
     * 这里用 DAO 内部嵌套类承载，避免为一个纯台账行在 common 加第 29 个模型）。
     */
    public static final class TriggerDefRecord {

        private final long id;
        private final long taskId;
        private final long tableMappingId;
        private final long sourceDsId;
        private final String logTableName;
        private final String triggerNames;
        private final String ddlJson;
        private final String state;
        private final Instant deployedAt;
        private final String lastError;

        /**
         * 构造。
         *
         * @param id             台账 ID
         * @param taskId         任务 ID
         * @param tableMappingId 表映射 ID
         * @param sourceDsId     源数据源 ID
         * @param logTableName   日志表名
         * @param triggerNames   触发器名（逗号分隔）
         * @param ddlJson        DDL（JSON 数组字符串）
         * @param state          状态（PENDING/DEPLOYED/FAILED/DROPPED）
         * @param deployedAt     部署时间
         * @param lastError      最近错误
         */
        public TriggerDefRecord(long id, long taskId, long tableMappingId, long sourceDsId,
                                String logTableName, String triggerNames, String ddlJson,
                                String state, Instant deployedAt, String lastError) {
            this.id = id;
            this.taskId = taskId;
            this.tableMappingId = tableMappingId;
            this.sourceDsId = sourceDsId;
            this.logTableName = logTableName;
            this.triggerNames = triggerNames;
            this.ddlJson = ddlJson;
            this.state = state;
            this.deployedAt = deployedAt;
            this.lastError = lastError;
        }

        /** @return 台账 ID */
        public long getId() {
            return id;
        }

        /** @return 任务 ID */
        public long getTaskId() {
            return taskId;
        }

        /** @return 表映射 ID */
        public long getTableMappingId() {
            return tableMappingId;
        }

        /** @return 源数据源 ID */
        public long getSourceDsId() {
            return sourceDsId;
        }

        /** @return 日志表名 */
        public String getLogTableName() {
            return logTableName;
        }

        /** @return 触发器名（逗号分隔） */
        public String getTriggerNames() {
            return triggerNames;
        }

        /** @return DDL JSON */
        public String getDdlJson() {
            return ddlJson;
        }

        /** @return 状态 */
        public String getState() {
            return state;
        }

        /** @return 部署时间 */
        public Instant getDeployedAt() {
            return deployedAt;
        }

        /** @return 最近错误 */
        public String getLastError() {
            return lastError;
        }

        @Override
        public String toString() {
            return "TriggerDefRecord#" + id + "[" + logTableName + "," + state + "]";
        }
    }

    private final RowMapper<TriggerDefRecord> mapper = new RowMapper<TriggerDefRecord>() {
        @Override
        public TriggerDefRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TriggerDefRecord(rs.getLong("id"), rs.getLong("task_id"),
                    rs.getLong("table_mapping_id"), rs.getLong("source_ds_id"),
                    rs.getString("log_table_name"), rs.getString("trigger_names"),
                    rs.getString("ddl_json"), rs.getString("state"),
                    instantOf(rs, "deployed_at"), rs.getString("last_error"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public TriggerDefDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 登记待部署的触发器（幂等：已存在则更新 DDL 并回到 PENDING）。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param sourceDsId     源数据源 ID
     * @param logTableName   日志表名
     * @param triggerNames   触发器名（逗号分隔）
     * @param ddlJson        DDL JSON
     * @return 影响行数
     */
    public int upsertPending(long taskId, long tableMappingId, long sourceDsId, String logTableName,
                             String triggerNames, String ddlJson) {
        int rows = jdbc.update("UPDATE sync_trigger_def SET log_table_name=?,trigger_names=?,"
                        + "ddl_json=?,state=?,last_error=NULL WHERE task_id=? AND table_mapping_id=?",
                logTableName, triggerNames, ddlJson, "PENDING", taskId, tableMappingId);
        if (rows > 0) {
            return rows;
        }
        try {
            return jdbc.update("INSERT INTO sync_trigger_def (id,task_id,table_mapping_id,"
                            + "source_ds_id,log_table_name,trigger_names,ddl_json,state,created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)",
                    nextId(), taskId, tableMappingId, sourceDsId, logTableName, triggerNames,
                    ddlJson, "PENDING", toTimestamp(now()));
        } catch (DuplicateKeyException race) {
            return jdbc.update("UPDATE sync_trigger_def SET log_table_name=?,trigger_names=?,"
                            + "ddl_json=?,state=? WHERE task_id=? AND table_mapping_id=?",
                    logTableName, triggerNames, ddlJson, "PENDING", taskId, tableMappingId);
        }
    }

    /**
     * 标记已部署。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @return 影响行数
     */
    public int markDeployed(long taskId, long tableMappingId) {
        return jdbc.update("UPDATE sync_trigger_def SET state=?,deployed_at=?,last_error=NULL "
                        + "WHERE task_id=? AND table_mapping_id=?",
                "DEPLOYED", toTimestamp(now()), taskId, tableMappingId);
    }

    /**
     * 标记部署失败。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param error          错误信息
     * @return 影响行数
     */
    public int markFailed(long taskId, long tableMappingId, String error) {
        String msg = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
        return jdbc.update("UPDATE sync_trigger_def SET state=?,last_error=? "
                + "WHERE task_id=? AND table_mapping_id=?", "FAILED", msg, taskId, tableMappingId);
    }

    /**
     * 标记已卸载。
     *
     * @param taskId 任务 ID
     * @return 影响行数
     */
    public int markAllDropped(long taskId) {
        return jdbc.update("UPDATE sync_trigger_def SET state=? WHERE task_id=?", "DROPPED", taskId);
    }

    /**
     * 查询单张表的台账。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @return 台账行
     */
    public Optional<TriggerDefRecord> findByTaskAndTable(long taskId, long tableMappingId) {
        List<TriggerDefRecord> list = jdbc.query("SELECT * FROM sync_trigger_def WHERE task_id=? "
                + "AND table_mapping_id=?", mapper, taskId, tableMappingId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 列出任务下台账。
     *
     * @param taskId 任务 ID
     * @return 台账列表
     */
    public List<TriggerDefRecord> findByTask(long taskId) {
        return jdbc.query("SELECT * FROM sync_trigger_def WHERE task_id=? "
                + "ORDER BY table_mapping_id", mapper, taskId);
    }

    /**
     * 按状态列出（启动自检：找出未部署成功的）。
     *
     * @param state 状态
     * @return 台账列表
     */
    public List<TriggerDefRecord> findByState(String state) {
        return jdbc.query("SELECT * FROM sync_trigger_def WHERE state=? ORDER BY task_id, "
                + "table_mapping_id", mapper, state);
    }

    /**
     * 删除任务下台账。
     *
     * @param taskId 任务 ID
     * @return 影响行数
     */
    public int deleteByTask(long taskId) {
        return jdbc.update("DELETE FROM sync_trigger_def WHERE task_id=?", taskId);
    }
}
