package com.dbsync.cdc;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.dialect.Dialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 冲突解决器（三策略 × 三操作，语义见类头表）。
 *
 * <table border="1">
 *   <caption>策略语义（与 PRD CDC-06/07 一一对应）</caption>
 *   <tr><th>策略</th><th>INSERT</th><th>UPDATE</th><th>DELETE</th></tr>
 *   <tr><td>SOURCE_WINS</td><td>upsert 覆盖</td><td>upsert 覆盖</td><td>删目标行</td></tr>
 *   <tr><td>TARGET_WINS</td><td>已存在则跳过</td><td>跳过</td><td>跳过</td></tr>
 *   <tr><td>TIMESTAMP_NEWER</td><td>upsert</td><td>{@code commitTs > 目标 update_time} 才应用</td>
 *       <td>同 UPDATE 判断</td></tr>
 * </table>
 *
 * <p><b>{@code TIMESTAMP_NEWER} 的实测陷阱</b>：目标表可能根本没有 {@code update_time} 列
 * （本平台不强制业务表加列）。此时无法比较，本类的选择是<b>照常应用并打 WARN</b> ——
 * 因为"丢弃一条真实变更"比"覆盖一条可能更新的数据"更难被发现与修复。
 * 这个降级行为写在日志里，运维可据此要求业务方补列。
 *
 * <p><b>为什么方言从构造函数注入</b>：{@code resolve} 的签名里没有 {@code Dialect}，
 * 但拼 {@code SELECT ... WHERE pk=?} 必须用方言引号（硬规则 3/8）。
 */
public class ConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(ConflictResolver.class);

    /** 目标端"更新时间"列名的候选（按顺序取第一个存在的）。 */
    private static final List<String> TIMESTAMP_COLUMNS = List.of("update_time", "updated_at",
            "modify_time", "gmt_modified");

    private final Dialect dialect;

    /**
     * 构造。
     *
     * @param dialect 目标方言
     */
    public ConflictResolver(Dialect dialect) {
        this.dialect = dialect;
    }

    /**
     * 判定是否应用。
     *
     * @param e          变更事件
     * @param targetConn 目标库连接
     * @param t          目标表元数据
     * @param policy     冲突策略（null 视为 SOURCE_WINS）
     * @return 判定结果
     */
    public ConflictDecision resolve(ChangeEvent e, Connection targetConn, TableMeta t,
                                    ConflictPolicy policy) {
        ConflictPolicy effective = policy == null ? ConflictPolicy.SOURCE_WINS : policy;
        switch (effective) {
            case TARGET_WINS:
                return resolveTargetWins(e, targetConn, t);
            case TIMESTAMP_NEWER:
                return resolveTimestampNewer(e, targetConn, t);
            case SOURCE_WINS:
            default:
                return ConflictDecision.apply();
        }
    }

    /**
     * 目标端优先：只有"目标还不存在这一行"的 INSERT 才应用。
     *
     * @param e          变更事件
     * @param targetConn 目标连接
     * @param t          目标表
     * @return 判定
     */
    private ConflictDecision resolveTargetWins(ChangeEvent e, Connection targetConn, TableMeta t) {
        if (e.getOp() != ChangeOp.INSERT) {
            return ConflictDecision.skip(ConflictDecision.TARGET_WINS_IGNORED,
                    "TARGET_WINS：UPDATE/DELETE 一律不覆盖目标端");
        }
        if (exists(e, targetConn, t)) {
            return ConflictDecision.skip(ConflictDecision.TARGET_WINS_IGNORED,
                    "TARGET_WINS：目标端已存在该主键，跳过 INSERT");
        }
        return ConflictDecision.apply();
    }

    /**
     * 时间戳更新者优先。
     *
     * @param e          变更事件
     * @param targetConn 目标连接
     * @param t          目标表
     * @return 判定
     */
    private ConflictDecision resolveTimestampNewer(ChangeEvent e, Connection targetConn,
                                                   TableMeta t) {
        if (e.getOp() == ChangeOp.INSERT) {
            return ConflictDecision.apply();
        }
        ColumnMeta tsColumn = timestampColumn(t);
        if (tsColumn == null) {
            log.warn("TIMESTAMP_NEWER 策略下目标表 {} 无更新时间列，已降级为直接应用（变更 id={}）",
                    t.getTableName(), e.getId());
            return ConflictDecision.apply();
        }
        Instant targetTs = readTargetTimestamp(e, targetConn, t, tsColumn);
        if (targetTs == null) {
            // 目标行不存在：DELETE 无事可做，UPDATE 按 upsert 语义插入
            return ConflictDecision.apply();
        }
        Instant sourceTs = e.getCommitTs();
        if (sourceTs == null) {
            return ConflictDecision.apply();
        }
        if (sourceTs.isAfter(targetTs)) {
            return ConflictDecision.apply();
        }
        return ConflictDecision.skip(ConflictDecision.TIMESTAMP_OLDER_IGNORED,
                "TIMESTAMP_NEWER：源提交时间 " + sourceTs + " 不晚于目标 " + targetTs);
    }

    /**
     * 目标行是否存在（按主键）。
     *
     * @param e      变更事件
     * @param conn   连接
     * @param t      目标表
     * @return true 表示存在
     */
    boolean exists(ChangeEvent e, Connection conn, TableMeta t) {
        String sql = "SELECT 1 FROM " + qualified(t) + " WHERE " + pkPredicate(t);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindPk(ps, e, t);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, ex,
                    "查询目标行是否存在失败（" + t.getTableName() + "）：" + ex.getMessage());
        }
    }

    /**
     * 读目标行的更新时间。
     *
     * @param e        变更事件
     * @param conn     连接
     * @param t        目标表
     * @param tsColumn 时间列
     * @return 时间；目标行不存在返回 null
     */
    Instant readTargetTimestamp(ChangeEvent e, Connection conn, TableMeta t, ColumnMeta tsColumn) {
        String sql = "SELECT " + dialect.quote(tsColumn.getName()) + " FROM " + qualified(t)
                + " WHERE " + pkPredicate(t);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindPk(ps, e, t);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp ts = rs.getTimestamp(1);
                return ts == null ? null : ts.toInstant();
            }
        } catch (SQLException ex) {
            throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, ex,
                    "读取目标更新时间失败（" + t.getTableName() + "）：" + ex.getMessage());
        }
    }

    /**
     * 找目标表的时间列。
     *
     * @param t 目标表
     * @return 列元数据；没有返回 null
     */
    ColumnMeta timestampColumn(TableMeta t) {
        for (String candidate : TIMESTAMP_COLUMNS) {
            for (ColumnMeta column : t.getColumns()) {
                if (column.getName().toLowerCase(Locale.ROOT).equals(candidate)) {
                    return column;
                }
            }
        }
        return null;
    }

    /**
     * 主键等值谓词（列名全部走方言引号）。
     *
     * @param t 目标表
     * @return 形如 {@code `id`=?} 的谓词
     */
    private String pkPredicate(TableMeta t) {
        StringBuilder sb = new StringBuilder();
        for (String pk : t.primaryKeyNames()) {
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            sb.append(dialect.quote(pk)).append("=?");
        }
        return sb.toString();
    }

    /**
     * 绑定主键值。
     *
     * @param ps 语句
     * @param e  事件
     * @param t  目标表
     * @throws SQLException 绑定失败
     */
    private void bindPk(PreparedStatement ps, ChangeEvent e, TableMeta t) throws SQLException {
        List<Object> tuple = e.getPkTuple();
        List<String> pks = t.primaryKeyNames();
        if (tuple == null || tuple.size() != pks.size()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "主键元组与表 " + t.getTableName() + " 的主键列数不一致：tuple="
                            + (tuple == null ? 0 : tuple.size()) + ", cols=" + pks.size());
        }
        for (int i = 0; i < pks.size(); i++) {
            ps.setObject(i + 1, tuple.get(i));
        }
    }

    /**
     * 拼引号全名。
     *
     * @param t 表元数据
     * @return 全名
     */
    private String qualified(TableMeta t) {
        String schema = t.getSchema();
        if (schema == null || schema.isBlank()) {
            return dialect.quote(t.getTableName());
        }
        return dialect.quote(schema) + "." + dialect.quote(t.getTableName());
    }
}
