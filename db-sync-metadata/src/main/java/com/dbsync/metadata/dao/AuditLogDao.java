package com.dbsync.metadata.dao;

import com.dbsync.common.enums.AuditResult;
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
 * 审计日志 DAO。
 *
 * <p>审计是<b>只追加</b>的（append-only）：本类不提供 UPDATE/DELETE 单行的方法，
 * 只有按时间窗清理（保留策略由运维配置）。任何"改审计"的需求都必须被拒绝。
 */
@Repository
public class AuditLogDao extends AbstractMetaDao {

    /**
     * 审计行（ADDED：04 文档列了 AuditLogDao；core 的 {@code AuditEntry} 属于 core 模块端口类型，
     * metadata 不能依赖 core，故用 DAO 内嵌套类承载查询结果）。
     */
    public static final class AuditLogRow {

        private final long id;
        private final String actor;
        private final String actorRole;
        private final String action;
        private final String targetType;
        private final Long targetId;
        private final String targetName;
        private final String paramsDigest;
        private final String result;
        private final String errorCode;
        private final String ip;
        private final String traceId;
        private final Instant createdAt;

        /**
         * 构造。
         *
         * @param id           审计 ID
         * @param actor        操作人
         * @param actorRole    操作人角色
         * @param action       动作
         * @param targetType   目标类型
         * @param targetId     目标 ID
         * @param targetName   目标名
         * @param paramsDigest 参数摘要（脱敏后）
         * @param result       结果（SUCCESS/DENIED）
         * @param errorCode    错误码
         * @param ip           来源 IP
         * @param traceId      链路 ID
         * @param createdAt    发生时间
         */
        public AuditLogRow(long id, String actor, String actorRole, String action,
                           String targetType, Long targetId, String targetName,
                           String paramsDigest, String result, String errorCode, String ip,
                           String traceId, Instant createdAt) {
            this.id = id;
            this.actor = actor;
            this.actorRole = actorRole;
            this.action = action;
            this.targetType = targetType;
            this.targetId = targetId;
            this.targetName = targetName;
            this.paramsDigest = paramsDigest;
            this.result = result;
            this.errorCode = errorCode;
            this.ip = ip;
            this.traceId = traceId;
            this.createdAt = createdAt;
        }

        /** @return 审计 ID */
        public long getId() {
            return id;
        }

        /** @return 操作人 */
        public String getActor() {
            return actor;
        }

        /** @return 操作人角色 */
        public String getActorRole() {
            return actorRole;
        }

        /** @return 动作 */
        public String getAction() {
            return action;
        }

        /** @return 目标类型 */
        public String getTargetType() {
            return targetType;
        }

        /** @return 目标 ID */
        public Long getTargetId() {
            return targetId;
        }

        /** @return 目标名 */
        public String getTargetName() {
            return targetName;
        }

        /** @return 参数摘要 */
        public String getParamsDigest() {
            return paramsDigest;
        }

        /** @return 结果 */
        public String getResult() {
            return result;
        }

        /** @return 错误码 */
        public String getErrorCode() {
            return errorCode;
        }

        /** @return 来源 IP */
        public String getIp() {
            return ip;
        }

        /** @return 链路 ID */
        public String getTraceId() {
            return traceId;
        }

        /** @return 发生时间 */
        public Instant getCreatedAt() {
            return createdAt;
        }
    }

    private final RowMapper<AuditLogRow> mapper = new RowMapper<AuditLogRow>() {
        @Override
        public AuditLogRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AuditLogRow(rs.getLong("id"), rs.getString("actor"),
                    rs.getString("actor_role"), rs.getString("action"),
                    rs.getString("target_type"), nullableLong(rs, "target_id"),
                    rs.getString("target_name"), rs.getString("params_digest"),
                    rs.getString("result"), rs.getString("error_code"), rs.getString("ip"),
                    rs.getString("trace_id"), instantOf(rs, "created_at"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public AuditLogDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 追加一条审计。
     *
     * @param actor        操作人
     * @param actorRole    角色
     * @param action       动作（如 TASK_CREATE / CUTOVER_ADVANCE）
     * @param targetType   目标类型
     * @param targetId     目标 ID（可 null）
     * @param targetName   目标名（可 null）
     * @param paramsDigest 参数摘要（必须已脱敏）
     * @param result       结果
     * @param errorCode    错误码（可 null）
     * @param ip           来源 IP
     * @param userAgent    UA
     * @param traceId      链路 ID
     * @return 影响行数
     */
    public int append(String actor, String actorRole, String action, String targetType,
                      Long targetId, String targetName, String paramsDigest, AuditResult result,
                      String errorCode, String ip, String userAgent, String traceId) {
        return jdbc.update("INSERT INTO sys_audit_log (id,actor,actor_role,action,target_type,"
                        + "target_id,target_name,params_digest,result,error_code,ip,user_agent,"
                        + "trace_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                nextId(), actor, actorRole, action, targetType, targetId,
                truncate(targetName, 256), paramsDigest,
                result == null ? AuditResult.SUCCESS.name() : result.name(),
                errorCode, ip, truncate(userAgent, 256), traceId, toTimestamp(now()));
    }

    /**
     * 按操作人查询（倒序）。
     *
     * @param actor 操作人
     * @param limit 上限
     * @return 审计列表
     */
    public List<AuditLogRow> findByActor(String actor, int limit) {
        int effective = limit <= 0 ? 100 : limit;
        return jdbc.query("SELECT * FROM sys_audit_log WHERE actor=? ORDER BY created_at DESC "
                + "LIMIT " + effective, mapper, actor);
    }

    /**
     * 按目标查询（倒序）。
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @param limit      上限
     * @return 审计列表
     */
    public List<AuditLogRow> findByTarget(String targetType, long targetId, int limit) {
        int effective = limit <= 0 ? 100 : limit;
        return jdbc.query("SELECT * FROM sys_audit_log WHERE target_type=? AND target_id=? "
                + "ORDER BY created_at DESC LIMIT " + effective, mapper, targetType, targetId);
    }

    /**
     * 按时间窗查询（合规导出）。
     *
     * @param from  起（含）
     * @param to    止（不含）
     * @param limit 上限
     * @return 审计列表
     */
    public List<AuditLogRow> findByTimeRange(Instant from, Instant to, int limit) {
        int effective = limit <= 0 ? 1000 : limit;
        return jdbc.query("SELECT * FROM sys_audit_log WHERE created_at >= ? AND created_at < ? "
                + "ORDER BY created_at LIMIT " + effective, mapper, toTimestamp(from),
                toTimestamp(to));
    }

    /**
     * 四维筛选查询（时间 / 人 / 对象 / 动作）—— T46 要求。
     *
     * <p>// ADDED: 04 文档 T46 要求"时间/人/对象/动作四维筛选"，原有 DAO 只有单维查询。
     * 动态条件一律用 {@code ?} 占位符拼接（架构 §12.2：禁止拼值）。
     *
     * @param from       起始时间（含，可 null）
     * @param to         结束时间（不含，可 null）
     * @param actor      操作人（可 null）
     * @param targetType 目标类型（可 null）
     * @param targetId   目标 ID（可 null）
     * @param action     动作（可 null）
     * @param result     结果（可 null）
     * @param limit      上限
     * @return 审计列表（时间倒序）
     */
    public List<AuditLogRow> search(Instant from, Instant to, String actor, String targetType,
                                    Long targetId, String action, String result, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM sys_audit_log WHERE 1=1");
        List<Object> args = new ArrayList<>(8);
        if (from != null) {
            sql.append(" AND created_at >= ?");
            args.add(toTimestamp(from));
        }
        if (to != null) {
            sql.append(" AND created_at < ?");
            args.add(toTimestamp(to));
        }
        if (actor != null) {
            sql.append(" AND actor = ?");
            args.add(actor);
        }
        if (targetType != null) {
            sql.append(" AND target_type = ?");
            args.add(targetType);
        }
        if (targetId != null) {
            sql.append(" AND target_id = ?");
            args.add(targetId);
        }
        if (action != null) {
            sql.append(" AND action = ?");
            args.add(action);
        }
        if (result != null) {
            sql.append(" AND result = ?");
            args.add(result);
        }
        int effective = limit <= 0 ? 500 : Math.min(limit, 5000);
        sql.append(" ORDER BY created_at DESC LIMIT ").append(effective);
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    /**
     * 按动作统计（合规巡检：切流/回滚次数）。
     *
     * @param action 动作
     * @return 数量
     */
    public long countByAction(String action) {        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sys_audit_log WHERE action=?",
                Long.class, action);
        return n == null ? 0L : n;
    }

    /**
     * 统计时间窗内被拒绝的操作数（安全告警）。
     *
     * @param from 起
     * @param to   止
     * @return 数量
     */
    public long countDenied(Instant from, Instant to) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sys_audit_log WHERE result=? "
                + "AND created_at >= ? AND created_at < ?", Long.class,
                AuditResult.DENIED.name(), toTimestamp(from), toTimestamp(to));
        return n == null ? 0L : n;
    }

    /**
     * 清理早于给定时间的审计（保留策略，按批删避免长事务）。
     *
     * @param before     时间上界
     * @param batchLimit 单批最大行数
     * @return 实际删除行数
     */
    public int deleteBefore(Instant before, int batchLimit) {
        int limit = batchLimit <= 0 ? 5000 : batchLimit;
        List<Long> ids = jdbc.queryForList("SELECT id FROM sys_audit_log WHERE created_at < ? "
                + "LIMIT " + limit, Long.class, toTimestamp(before));
        if (ids.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM sys_audit_log WHERE id IN (");
        List<Object> args = new ArrayList<Object>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
            args.add(ids.get(i));
        }
        sql.append(')');
        return jdbc.update(sql.toString(), args.toArray());
    }

    /**
     * 字符串截断。
     *
     * @param value 原值
     * @param max   最大长度
     * @return 截断值
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
