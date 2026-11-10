package com.dbsync.core.datasource;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.DriverMissingException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.util.AesGcmCipher;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.DialectRegistry;
import com.dbsync.dialect.JdbcDriverLoader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 连接探测器（"测试连接"按钮的后端）。
 *
 * <p><b>不建池</b>：用 {@link DriverManager} 直连并设置登录超时，
 * 避免"点一次测试连接就在池里留一个坏连接"。探测完立即关闭。
 *
 * <p><b>超时按驱动分别设置</b>：各 JDBC 驱动的超时属性名/单位并不通用
 * （MySQL 用毫秒 {@code connectTimeout}，PG 用秒，Oracle 用 {@code oracle.net.CONNECT_TIMEOUT}，
 * DM8/DB2 主要认 {@link DriverManager#setLoginTimeout(int)}），统一只写
 * {@code loginTimeout/connectTimeout} 对多数驱动等于没设，单源可能挂分钟级。
 * 这里按 {@link DbType} 生成专属属性、统一收敛到 5 秒；连接成功后再用
 * {@link Connection#setNetworkTimeout} 给 DM8/DB2 补一层网络读超时兜底。
 *
 * <p>失败归类规则见 {@link ConnectionTestResult.Category}；判定顺序很重要：
 * 先判驱动（连类都加载不到就谈不上网络），再判网络（SQLState 08），
 * 再判认证（28000 / 1045 / ORA-01017），最后判权限（42000 / 1044 / "to database"）。
 */
public class ConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTester.class);

    /** 统一短超时（秒）：测试连接/探活都不应让运维等太久。 */
    private static final int TIMEOUT_SECONDS = 5;
    /** 统一短超时（毫秒），供毫秒单位的驱动属性使用。 */
    private static final int TIMEOUT_MILLIS = TIMEOUT_SECONDS * 1000;

    private final AesGcmCipher cipher;

    /**
     * 构造。
     *
     * @param cipher 口令解密器（可为 null：明文场景/测试）
     */
    public ConnectionTester(AesGcmCipher cipher) {
        this.cipher = cipher;
    }

    /**
     * 探测数据源连通性。
     *
     * @param def 数据源定义
     * @return 探测结果（永不抛异常，异常被归类后返回）
     */
    public ConnectionTestResult test(DataSourceDef def) {
        long start = System.currentTimeMillis();
        Dialect dialect = DialectRegistry.get(def.getDbType());
        String url = dialect.buildJdbcUrl(def.getEndpoint());
        try {
            JdbcDriverLoader.ensureLoaded(def.getDbType().driverClassName());
        } catch (DriverMissingException e) {
            return ConnectionTestResult.fail(ConnectionTestResult.Category.DRIVER, e.errorCode().code(),
                    e.getMessage(),
                    JdbcDriverLoader.buildMissingMessage(def.getDbType().driverClassName()),
                    elapsed(start));
        } catch (RuntimeException e) {
            return ConnectionTestResult.fail(ConnectionTestResult.Category.DRIVER, "E7001",
                    e.getMessage(), "把驱动 jar 放入 lib/ 目录或加入 classpath 后重启", elapsed(start));
        }

        Properties props = new Properties();
        props.setProperty("user", def.getUsername() == null ? "" : def.getUsername());
        String password = decrypt(def);
        if (password != null) {
            props.setProperty("password", password);
        }
        // 驱动专属超时属性（见类注释），不再只靠两个通用属性名
        applyTimeouts(props, def.getDbType());
        // DriverManager 级登录超时是 DM8/DB2 等驱动唯一稳定生效的开关（单位：秒）
        DriverManager.setLoginTimeout(TIMEOUT_SECONDS);

        Connection connection = null;
        try {
            connection = DriverManager.getConnection(url, props);
            applyNetworkTimeoutFallback(connection, def.getDbType());
            DatabaseMetaData metaData = connection.getMetaData();
            String version = metaData.getDatabaseProductName() + " " + metaData
                    .getDatabaseProductVersion();
            return ConnectionTestResult.ok(elapsed(start), version);
        } catch (SQLException e) {
            ConnectionTestResult result = classify(e, def.getDbType(), elapsed(start));
            log.info("连接测试失败 ds={} dbType={} category={} msg={}", def.getName(),
                    def.getDbType(), result.getCategory(), result.getMessage());
            return result;
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * 按数据库类型生成驱动专属的超时属性（统一 5 秒）。
     *
     * @param props  连接属性
     * @param dbType 数据库类型
     */
    private static void applyTimeouts(Properties props, DbType dbType) {
        DbType type = dbType == null ? DbType.MYSQL : dbType;
        switch (type) {
            case MYSQL:
                // MySQL Connector/J：毫秒
                props.setProperty("connectTimeout", String.valueOf(TIMEOUT_MILLIS));
                props.setProperty("socketTimeout", String.valueOf(TIMEOUT_MILLIS));
                break;
            case POSTGRESQL:
                // pgjdbc：loginTimeout / connectTimeout / socketTimeout 都是秒
                props.setProperty("loginTimeout", String.valueOf(TIMEOUT_SECONDS));
                props.setProperty("connectTimeout", String.valueOf(TIMEOUT_SECONDS));
                props.setProperty("socketTimeout", String.valueOf(TIMEOUT_SECONDS));
                break;
            case ORACLE:
                // Oracle：oracle.net.CONNECT_TIMEOUT / oracle.jdbc.ReadTimeout，毫秒
                props.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(TIMEOUT_MILLIS));
                props.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(TIMEOUT_MILLIS));
                break;
            case DM:
            case DB2:
            default:
                // DM8 / DB2 主要认 DriverManager.setLoginTimeout（秒），这里也放一份到属性里
                props.setProperty("loginTimeout", String.valueOf(TIMEOUT_SECONDS));
                break;
        }
    }

    /**
     * 连接成功后的网络读超时兜底（DM8/DB2 等不认连接前属性的驱动）。
     *
     * @param connection 已建立的连接
     * @param dbType     数据库类型
     */
    private static void applyNetworkTimeoutFallback(Connection connection, DbType dbType) {
        if (dbType != DbType.DM && dbType != DbType.DB2) {
            return;
        }
        try {
            // 同步执行器即可；setNetworkTimeout 单位是毫秒
            connection.setNetworkTimeout(command -> command.run(), TIMEOUT_MILLIS);
        } catch (SQLException | RuntimeException e) {
            log.debug("设置网络读超时失败（忽略，驱动侧登录超时已兜底）dbType={} err={}",
                    dbType, e.toString());
        }
    }

    /**
     * 解密口令。<b>不缓存</b>到任何字段（T17 硬约束 4）。
     *
     * @param def 数据源定义
     * @return 明文口令；无口令返回 null
     */
    private String decrypt(DataSourceDef def) {
        String cipherText = def.getPasswordCipher();
        if (cipherText == null || cipherText.isEmpty()) {
            return null;
        }
        if (cipher == null) {
            // 测试/演示场景：未配置主密钥时，cipher 列直接当作明文
            return cipherText;
        }
        try {
            return cipher.decrypt(cipherText, def.getKeyVersion());
        } catch (DbSyncException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DbSyncException(ErrorCode.DATASOURCE_CONFIG_INVALID,
                    "口令解密失败：" + e.getMessage()).with("ds", def.getName());
        }
    }

    /**
     * 按异常特征归类。
     *
     * @param e        异常
     * @param dbType   数据库类型
     * @param latency  耗时
     * @return 结果
     */
    ConnectionTestResult classify(SQLException e, DbType dbType, long latency) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        String sqlState = e.getSQLState();
        int vendorCode = e.getErrorCode();

        // 1) 网络：SQLState 08xxx，或明确的连接类异常
        if (sqlState != null && sqlState.startsWith("08")) {
            return ConnectionTestResult.fail(ConnectionTestResult.Category.NETWORK,
                    sqlState, message, "检查主机/端口/防火墙，以及实例是否已启动", latency);
        }
        if (lower.contains("connection refused") || lower.contains("connect timed out")
                || lower.contains("unknownhost") || lower.contains("no route to host")
                || lower.contains("communications link failure")) {
            return ConnectionTestResult.fail(ConnectionTestResult.Category.NETWORK,
                    sqlState, message, "检查主机/端口/防火墙，以及实例是否已启动", latency);
        }

        // 2) 驱动：驱动没放或版本不匹配
        if (lower.contains("no suitable driver") || lower.contains("driver not found")) {
            return ConnectionTestResult.fail(ConnectionTestResult.Category.DRIVER,
                    sqlState, message, JdbcDriverLoader.buildMissingMessage(dbType.driverClassName()), latency);
        }

        // 3) 认证：账号口令问题
        if ("28000".equals(sqlState) || vendorCode == 1045 || lower.contains("access denied for user")
                && !lower.contains("to database") || lower.contains("invalid username/password")
                || lower.contains("password") && lower.contains("invalid")) {
            return ConnectionTestResult.fail(ConnectionTestResult.Category.AUTH,
                    sqlState == null ? String.valueOf(vendorCode) : sqlState, message,
                    "核对用户名/口令（注意生产库通常只给只读账号）", latency);
        }

        // 4) 权限：能认证但不能用目标库/schema
        if ("42000".equals(sqlState) || vendorCode == 1044 || lower.contains("to database")
                || lower.contains("insufficient privileges") || lower.contains("permission")
                || lower.contains("schema") && lower.contains("does not exist")) {
            return ConnectionTestResult.fail(ConnectionTestResult.Category.PRIVILEGE,
                    sqlState == null ? String.valueOf(vendorCode) : sqlState, message,
                    "让 DBA 授予目标库 SELECT（迁移还需目标库 INSERT/UPDATE/DELETE）权限", latency);
        }

        return ConnectionTestResult.fail(ConnectionTestResult.Category.UNKNOWN,
                sqlState == null ? String.valueOf(vendorCode) : sqlState, message,
                "未归类错误：请把完整堆栈首行反馈给平台维护者", latency);
    }

    /**
     * 计时。
     *
     * @param start 起始毫秒
     * @return 耗时毫秒
     */
    private static long elapsed(long start) {
        return Math.max(0L, System.currentTimeMillis() - start);
    }

    /**
     * 静默关闭连接。
     *
     * @param connection 连接
     */
    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("关闭探测连接失败（忽略）：{}", e.getMessage());
        }
    }
}
