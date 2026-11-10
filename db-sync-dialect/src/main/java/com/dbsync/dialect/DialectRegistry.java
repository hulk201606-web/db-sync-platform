package com.dbsync.dialect;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.dialect.db2.Db2Dialect;
import com.dbsync.dialect.dm.DmDialect;
import com.dbsync.dialect.mysql.MySqlDialect;
import com.dbsync.dialect.oracle.OracleDialect;
import com.dbsync.dialect.postgresql.PostgresDialect;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 方言注册表：按 {@link DbType} 返回**单例**方言实现。
 *
 * <p>方言实现必须无状态，因此可安全共享；不要在业务代码里 {@code new XxxDialect()}，
 * 否则会绕开能力声明的统一口径。
 */
public final class DialectRegistry {

    private static final Map<DbType, Dialect> DIALECTS;

    static {
        Map<DbType, Dialect> map = new EnumMap<>(DbType.class);
        map.put(DbType.MYSQL, new MySqlDialect());
        map.put(DbType.ORACLE, new OracleDialect());
        map.put(DbType.POSTGRESQL, new PostgresDialect());
        map.put(DbType.DM, new DmDialect());
        map.put(DbType.DB2, new Db2Dialect());
        DIALECTS = Collections.unmodifiableMap(map);
    }

    private DialectRegistry() {
    }

    /**
     * 按类型取方言单例。
     *
     * @param dbType 数据库类型
     * @return 方言
     * @throws DbSyncException {@code E7002} 类型未注册
     */
    public static Dialect get(DbType dbType) {
        Dialect d = dbType == null ? null : DIALECTS.get(dbType);
        if (d == null) {
            throw new DbSyncException(ErrorCode.DIALECT_UNSUPPORTED, String.valueOf(dbType));
        }
        return d;
    }

    /**
     * 按 JDBC URL 取方言。
     *
     * @param jdbcUrl JDBC URL
     * @return 方言
     * @throws DbSyncException {@code E7010} URL 无法识别
     */
    public static Dialect getByJdbcUrl(String jdbcUrl) {
        return get(DbType.ofJdbcUrl(jdbcUrl));
    }

    /**
     * @return 不可变的 类型 → 方言 视图
     */
    public static Map<DbType, Dialect> all() {
        return DIALECTS;
    }
}
