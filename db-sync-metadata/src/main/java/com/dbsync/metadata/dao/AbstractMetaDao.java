// ADDED: 18 个 DAO 共用的时间/JSON/分页小工具。04 文档列了 18 个 DAO 但未列公共基类；
// 抽基类是为了避免把 `Timestamp.from(instant)` / UTC 转换逻辑复制 18 遍（复制是 bug 的温床）。
package com.dbsync.metadata.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import com.dbsync.common.util.SnowflakeId;

/**
 * 元数据库 DAO 基类（只放跨 DAO 复用的最小工具，不做任何 ORM 映射）。
 *
 * <p>事务约定：单语句方法不需要事务；多语句原子方法由子类用
 * {@code TransactionTemplate} 包裹（06 文档 §1.5 "DAO 方法自带事务"）。
 */
public abstract class AbstractMetaDao {

    /** 元数据库专用 JdbcTemplate（与业务数据源完全隔离）。 */
    protected final JdbcTemplate jdbc;

    /**
     * 主键生成器（架构 §7.1：元数据库主键统一雪花 ID，不用 AUTO_INCREMENT）。
     *
     * <p>进程内共享一个实例，保证单调递增；workerId 由主机名派生，
     * 生产环境建议通过 {@code db-sync.meta.worker-id} 显式覆盖（当前阶段用派生值即可）。
     */
    protected static final SnowflakeId ID_GEN = new SnowflakeId(SnowflakeId.defaultWorkerId());

    /**
     * 生成元数据表主键。
     *
     * @return 雪花 ID
     */
    protected static long nextId() {
        return ID_GEN.nextId();
    }

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    protected AbstractMetaDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Instant → Timestamp（UTC，毫秒精度）。
     *
     * @param instant 时间
     * @return Timestamp；null 原样返回
     */
    protected static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /**
     * Timestamp → Instant。
     *
     * @param ts 时间戳
     * @return Instant；null 原样返回
     */
    protected static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 按列名读 Instant（列可能为 null）。
     *
     * @param rs      结果集
     * @param column  列名
     * @return Instant
     * @throws SQLException 读取失败
     */
    protected static Instant instantOf(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 按列名读可空 long。
     *
     * @param rs     结果集
     * @param column 列名
     * @return Long；SQL NULL 返回 null
     * @throws SQLException 读取失败
     */
    protected static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /**
     * 按列名读可空 int。
     *
     * @param rs     结果集
     * @param column 列名
     * @return Integer；SQL NULL 返回 null
     * @throws SQLException 读取失败
     */
    protected static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    /**
     * 当前 UTC 时间。
     *
     * @return Instant.now()
     */
    protected static Instant now() {
        return Instant.now();
    }
}
