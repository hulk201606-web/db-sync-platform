package com.dbsync.core.datasource;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.util.AesGcmCipher;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.DialectRegistry;
import com.dbsync.dialect.JdbcDriverLoader;
import com.dbsync.metadata.dao.DataSourceDao;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据源注册表：按 dsId 管理业务连接池（与元数据库池完全隔离）。
 *
 * <p><b>四条硬约束</b>（T17）：
 * <ol>
 *   <li>建池时 {@code autoCommit=false}——Oracle/PG 的 fetchSize 只有在非自动提交下才是"游标读"，
 *       否则驱动会把整表读进内存（架构 §5.5 / R2）。</li>
 *   <li>建池前 {@link JdbcDriverLoader#ensureLoaded}，驱动缺失抛 {@code E7001}。</li>
 *   <li>池上限必须 ≤ 0.6 × 数据库 {@code max_connections}（用 JDBC 标准
 *       {@link DatabaseMetaData#getMaxConnections()} 取，不写库特有 SQL）；取不到则 WARN 跳过。</li>
 *   <li>口令用 {@link AesGcmCipher} 解密，只存在于 {@code setPassword} 那一行，不缓存到字段。</li>
 * </ol>
 */
public class DataSourceRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRegistry.class);

    /** 池上限占数据库连接上限的安全比例（架构 §13.1）。 */
    private static final double POOL_MAX_RATIO = 0.6d;

    private final Map<Long, DataSourceHandle> handles = new ConcurrentHashMap<Long, DataSourceHandle>();

    private final DataSourceDao dataSourceDao;
    private final AesGcmCipher cipher;
    private final ConnectionTester tester;

    /**
     * 构造。
     *
     * @param dataSourceDao 数据源 DAO（取配置）
     * @param cipher        口令解密器（可为 null：明文场景/测试）
     */
    public DataSourceRegistry(DataSourceDao dataSourceDao, AesGcmCipher cipher) {
        this.dataSourceDao = dataSourceDao;
        this.cipher = cipher;
        this.tester = new ConnectionTester(cipher);
    }

    /**
     * 按 dsId 取或建池（线程安全，重复调用返回同一 handle）。
     *
     * @param dsId 数据源 ID
     * @return 句柄
     */
    public DataSourceHandle handle(long dsId) {
        DataSourceHandle existing = handles.get(Long.valueOf(dsId));
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        synchronized (this) {
            DataSourceHandle again = handles.get(Long.valueOf(dsId));
            if (again != null && !again.isClosed()) {
                return again;
            }
            DataSourceDef def = dataSourceDao.findById(dsId).orElseThrow(
                    () -> new DbSyncException(ErrorCode.DATASOURCE_NOT_FOUND, String.valueOf(dsId)));
            return register(def);
        }
    }

    /**
     * 注册（建池并缓存）。
     *
     * @param def 数据源定义
     * @return 句柄
     */
    public DataSourceHandle register(DataSourceDef def) {
        DataSourceHandle previous = handles.remove(Long.valueOf(def.getId()));
        if (previous != null) {
            closeQuietly(previous);
        }
        ensureDriverAvailable(def);
        Dialect dialect = DialectRegistry.get(def.getDbType());
        String url = dialect.buildJdbcUrl(def.getEndpoint());

        HikariConfig config = new HikariConfig();
        config.setPoolName("dbsync-ds-" + def.getId());
        config.setJdbcUrl(url);
        config.setUsername(def.getUsername());
        if (cipher != null && def.getPasswordCipher() != null
                && !def.getPasswordCipher().isEmpty()) {
            // 口令只在这一次调用里出现，不落到任何字段
            config.setPassword(cipher.decrypt(def.getPasswordCipher(), def.getKeyVersion()));
        } else {
            config.setPassword(def.getPasswordCipher());
        }
        config.setMaximumPoolSize(Math.max(1, def.getPoolMaxSize()));
        config.setMinimumIdle(Math.min(Math.max(0, def.getPoolMinIdle()), config.getMaximumPoolSize()));
        config.setConnectionTimeout(10_000L);
        config.setIdleTimeout(60_000L);
        config.setMaxLifetime(30L * 60L * 1000L);
        // 硬约束 1：Oracle / PG 的 fetchSize 生效前提
        config.setAutoCommit(false);
        config.setReadOnly(false);
        // 语句缓存对逐批 upsert 有明显收益（MySQL/PG 驱动均支持）
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "256");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        DataSource pool = newPool(def, config);
        verifyPoolSize(def, pool, config.getMaximumPoolSize());
        DataSourceHandle handle = new DataSourceHandle(def, pool, tester);
        handles.put(Long.valueOf(def.getId()), handle);
        log.info("数据源连接池已建立 ds={} dbType={} poolSize={}", def.getName(),
                def.getDbType(), config.getMaximumPoolSize());
        return handle;
    }

    /**
     * 建池（<b>可覆写点</b>：单测用一个 H2 DataSource 替身，避免为跑 DAO 测试引入真实 MySQL）。
     *
     * @param def    数据源定义
     * @param config 已完成配置的 Hikari 配置
     * @return 连接池
     */
    protected DataSource newPool(DataSourceDef def, HikariConfig config) {
        return new HikariDataSource(config);
    }

    /**
     * 确保驱动可用（<b>可覆写点</b>：单测里没有 MySQL/Oracle 驱动 jar）。
     *
     * @param def 数据源定义
     */
    protected void ensureDriverAvailable(DataSourceDef def) {
        JdbcDriverLoader.ensureLoaded(def.getDbType().driverClassName());
    }

    /**
     * 配比校验：池上限 ≤ 0.6 × max_connections。
     *
     * <p>取不到 {@code max_connections} 时只 WARN 不阻断——否则第一次接 Oracle 就会卡住
     * （Oracle 的 {@code getMaxConnections()} 常返回 0）。
     *
     * @param def     数据源定义
     * @param pool    连接池
     * @param maxSize 池上限
     */
    private void verifyPoolSize(DataSourceDef def, DataSource pool, int maxSize) {
        Connection connection = null;
        try {
            connection = pool.getConnection();
            DatabaseMetaData metaData = connection.getMetaData();
            int serverMax = metaData.getMaxConnections();
            if (serverMax <= 0) {
                log.warn("无法获取数据库最大连接数（dbType={}），跳过池配比校验；请人工确认 "
                        + "poolMaxSize={} 不挤占业务连接", def.getDbType(), maxSize);
                return;
            }
            verifyPoolRatio(def, maxSize, serverMax);
        } catch (SQLException e) {
            log.warn("池配比校验查询失败（不影响建池）ds={}：{}", def.getName(), e.getMessage());
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 归还失败由池自行处理
                }
            }
        }
    }

    /**
     * 配比判定（纯函数，便于单测直接验证阈值逻辑）。
     *
     * @param def       数据源定义
     * @param maxSize   配置的池上限
     * @param serverMax 数据库 max_connections
     * @throws DbSyncException {@code E2010} 超过 0.6 × serverMax
     */
    static void verifyPoolRatio(DataSourceDef def, int maxSize, int serverMax) {
        if (serverMax <= 0) {
            return;
        }
        int allowed = (int) Math.floor(serverMax * POOL_MAX_RATIO);
        if (maxSize > allowed) {
            throw new DbSyncException(ErrorCode.POOL_LIMIT_EXCEEDED,
                    def.getName() + " 的 poolMaxSize=" + maxSize + " 超过数据库允许的 "
                            + allowed + "（= 0.6 × max_connections " + serverMax + "）")
                    .with("dsId", def.getId()).with("maxConnections", serverMax);
        }
    }

    /**
     * 配置变更后调用：关闭旧池，下次 {@link #handle(long)} 重建。
     *
     * @param dsId 数据源 ID
     */
    public void evict(long dsId) {
        DataSourceHandle removed = handles.remove(Long.valueOf(dsId));
        if (removed != null) {
            closeQuietly(removed);
            log.info("数据源连接池已关闭（等待重建）dsId={}", dsId);
        }
    }

    /**
     * 探测（走短连接，不建池）。
     *
     * @param def 数据源定义
     * @return 探测结果
     */
    public ConnectionTestResult test(DataSourceDef def) {
        return tester.test(def);
    }

    /**
     * 当前缓存的句柄 ID（运维大盘用）。
     *
     * @return ID 列表
     */
    public List<Long> cachedIds() {
        List<Long> ids = new ArrayList<Long>(handles.keySet());
        java.util.Collections.sort(ids);
        return ids;
    }

    /**
     * 关闭全部连接池。
     */
    @Override
    public void close() {
        for (Map.Entry<Long, DataSourceHandle> entry : handles.entrySet()) {
            closeQuietly(entry.getValue());
        }
        handles.clear();
    }

    /**
     * 静默关闭单个句柄。
     *
     * @param handle 句柄
     */
    private static void closeQuietly(DataSourceHandle handle) {
        try {
            handle.close();
        } catch (RuntimeException e) {
            log.warn("关闭连接池失败（忽略）{}：{}", handle, e.getMessage());
        }
    }
}
