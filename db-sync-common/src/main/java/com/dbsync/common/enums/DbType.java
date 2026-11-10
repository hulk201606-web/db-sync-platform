package com.dbsync.common.enums;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import java.util.Locale;

/**
 * 支持的数据库类型及其驱动/URL 前缀。
 *
 * <p>注意：Oracle / DM / DB2 的驱动受授权限制，不随包发布，必须放在 {@code lib/} 目录，
 * 通过 {@code Class.forName} 反射加载（架构 §5.4）。
 */
public enum DbType {

    /** MySQL 8.0+。 */
    MYSQL("com.mysql.cj.jdbc.Driver", "jdbc:mysql://"),
    /** Oracle 11g/19c。 */
    ORACLE("oracle.jdbc.OracleDriver", "jdbc:oracle:thin:@//"),
    /** PostgreSQL 12+。 */
    POSTGRESQL("org.postgresql.Driver", "jdbc:postgresql://"),
    /** 达梦 DM8。 */
    DM("dm.jdbc.driver.DmDriver", "jdbc:dm://"),
    /** IBM DB2 LUW 11.5。 */
    DB2("com.ibm.db2.jcc.DB2Driver", "jdbc:db2://");

    private final String driverClassName;
    private final String urlPrefix;

    DbType(String driverClassName, String urlPrefix) {
        this.driverClassName = driverClassName;
        this.urlPrefix = urlPrefix;
    }

    /**
     * @return JDBC 驱动类全名
     */
    public String driverClassName() {
        return driverClassName;
    }

    /**
     * @return JDBC URL 前缀
     */
    public String urlPrefix() {
        return urlPrefix;
    }

    /**
     * 按 JDBC URL 前缀识别数据库类型（忽略大小写）。
     *
     * @param jdbcUrl JDBC URL
     * @return 识别出的类型
     * @throws DbSyncException 无法识别时抛 {@code E7010}
     */
    public static DbType ofJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new DbSyncException(ErrorCode.VERSION_UNSUPPORTED, "jdbcUrl 为空");
        }
        String lower = jdbcUrl.toLowerCase(Locale.ROOT).trim();
        for (DbType t : values()) {
            if (lower.startsWith(t.urlPrefix)) {
                return t;
            }
        }
        throw new DbSyncException(ErrorCode.VERSION_UNSUPPORTED, "未知 JDBC URL：" + mask(jdbcUrl))
                .with("jdbcUrlPrefix", lower.length() > 24 ? lower.substring(0, 24) : lower);
    }

    /**
     * 日志脱敏：只保留 {@code jdbc:xxx://} 前缀。
     *
     * @param jdbcUrl 原始 URL
     * @return 脱敏结果
     */
    private static String mask(String jdbcUrl) {
        int idx = jdbcUrl.indexOf("//");
        return idx > 0 ? jdbcUrl.substring(0, idx + 2) + "***" : "***";
    }
}
