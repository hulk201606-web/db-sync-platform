package com.dbsync.compare;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.EnvType;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.JdbcEndpoint;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.DialectRegistry;
import com.dbsync.metadata.dao.CompareBlockDao;
import com.dbsync.metadata.dao.CompareDiffDao;
import com.dbsync.metadata.dao.CompareJobDao;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import com.zaxxer.hikari.HikariConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * compare 模块测试基类：一套 H2 元数据库 + 一套源库 + 一套目标库 + 真实 DAO / 真实方言。
 *
 * <p>不起 Spring 容器：compare 的类都是普通对象，手工装配更快也更可控。
 * 每个测试类用独立内存库名，避免 {@code DB_CLOSE_DELAY=-1} 下互相污染。
 */
abstract class CompareTestSupport {

    /** 主键列。 */
    static final String PK = "id";

    /** 源数据源 ID。 */
    static final long SRC_DS_ID = 1L;
    /** 目标数据源 ID。 */
    static final long TGT_DS_ID = 2L;

    private static final AtomicLong SEQ = new AtomicLong(10_000L);

    protected JdbcDataSource metaDs;
    protected JdbcTemplate jdbc;
    protected DataSourceDao dataSourceDao;
    protected SyncTaskDao taskDao;
    protected TableMappingDao mappingDao;
    protected CompareJobDao jobDao;
    protected CompareBlockDao blockDao;
    protected CompareDiffDao diffDao;
    protected DataSourceRegistry registry;
    protected SnowflakeId idGenerator;

    protected JdbcDataSource sourceDs;
    protected JdbcDataSource targetDs;

    protected Dialect dialect = DialectRegistry.get(DbType.MYSQL);

    /**
     * 建三套库并装配 DAO / 注册表。
     */
    @BeforeEach
    void setUpCompare() {
        metaDs = h2(metaName());
        Flyway flyway = Flyway.configure().dataSource(metaDs)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(metaDs);

        dataSourceDao = new DataSourceDao(jdbc);
        taskDao = new SyncTaskDao(jdbc);
        mappingDao = new TableMappingDao(jdbc);
        jobDao = new CompareJobDao(jdbc);
        blockDao = new CompareBlockDao(jdbc);
        diffDao = new CompareDiffDao(jdbc);

        sourceDs = h2(metaName() + "_src");
        targetDs = h2(metaName() + "_tgt");
        dataSourceDao.insert(dataSourceDef(SRC_DS_ID, "src", "src"), "test");
        dataSourceDao.insert(dataSourceDef(TGT_DS_ID, "tgt", "tgt"), "test");

        registry = new H2Registry(dataSourceDao, sourceDs, targetDs);
        idGenerator = new SnowflakeId(SnowflakeId.defaultWorkerId());
    }

    /**
     * H2 数据源注册表：绕过 Hikari 与驱动加载（单测不连真实 MySQL）。
     */
    private static final class H2Registry extends DataSourceRegistry {

        private final JdbcDataSource sourceDs;
        private final JdbcDataSource targetDs;

        private H2Registry(DataSourceDao dao, JdbcDataSource sourceDs, JdbcDataSource targetDs) {
            super(dao, null);
            this.sourceDs = sourceDs;
            this.targetDs = targetDs;
        }

        @Override
        protected DataSource newPool(DataSourceDef def, HikariConfig config) {
            return def.getId() == SRC_DS_ID ? sourceDs : targetDs;
        }

        @Override
        protected void ensureDriverAvailable(DataSourceDef def) {
            // 单测：H2 驱动已在 classpath
        }
    }

    /**
     * 构造数据源定义。
     *
     * @param id    ID
     * @param name  名称
     * @param db    库名
     * @return 定义
     */
    private static DataSourceDef dataSourceDef(long id, String name, String db) {
        return DataSourceDef.builder(id, name)
                .dbType(DbType.MYSQL).env(EnvType.UAT)
                .endpoint(new JdbcEndpoint("127.0.0.1", 3306, db, "PUBLIC", Map.of()))
                .username("sa").passwordCipher("").keyVersion((short) 1)
                .poolMaxSize(4).build();
    }

    /**
     * 建一个 H2 内存库（MySQL 兼容模式，与生产 MySQL 的标识符习惯一致）。
     *
     * @param name 库名
     * @return 数据源
     */
    protected JdbcDataSource h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * 元数据库名。
     *
     * @return 库名
     */
    protected String metaName() {
        return "cmp_" + getClass().getSimpleName().toLowerCase();
    }

    /**
     * 执行 SQL。
     *
     * @param ds   数据源
     * @param sqls 语句
     */
    protected void exec(JdbcDataSource ds, String... sqls) {
        try (Connection connection = ds.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : sqls) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("执行 SQL 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 建源表与目标表（{@code id BIGINT} 主键 + {@code name VARCHAR(64)} + {@code amount DECIMAL(12,2)}）。
     *
     * @param table 表名
     */
    protected void createBothTables(String table) {
        String ddl = "CREATE TABLE " + table + " (id BIGINT NOT NULL PRIMARY KEY, "
                + "name VARCHAR(64), amount DECIMAL(12,2))";
        exec(sourceDs, "DROP TABLE IF EXISTS " + table, ddl);
        exec(targetDs, "DROP TABLE IF EXISTS " + table, ddl);
    }

    /**
     * 两侧同时插入 id ∈ [from,to] 的行（值完全一致）。
     *
     * @param table 表名
     * @param from  起始 id（含）
     * @param to    结束 id（含）
     */
    protected void insertBoth(String table, long from, long to) {
        insertRange(sourceDs, table, from, to);
        insertRange(targetDs, table, from, to);
    }

    /**
     * 单侧插入。
     *
     * @param ds    数据源
     * @param table 表名
     * @param from  起始 id（含）
     * @param to    结束 id（含）
     */
    protected void insertRange(JdbcDataSource ds, String table, long from, long to) {
        try (Connection connection = ds.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + table + " (id,name,amount) VALUES (?,?,?)")) {
                for (long i = from; i <= to; i++) {
                    ps.setLong(1, i);
                    ps.setString(2, "n" + i);
                    ps.setBigDecimal(3, java.math.BigDecimal.valueOf(i).setScale(2));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("插入数据失败：" + e.getMessage(), e);
        }
    }

    /**
     * 单侧更新某行的 name。
     *
     * @param ds    数据源
     * @param table 表名
     * @param id    主键
     * @param value 新值
     */
    protected void updateName(JdbcDataSource ds, String table, long id, String value) {
        exec(ds, "UPDATE " + table + " SET name='" + value + "' WHERE id=" + id);
    }

    /**
     * 统计行数。
     *
     * @param ds    数据源
     * @param table 表名
     * @return 行数
     */
    protected long countRows(JdbcDataSource ds, String table) {
        try (Connection connection = ds.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("统计行数失败：" + e.getMessage(), e);
        }
    }

    /**
     * 判断表是否存在（H2 元数据）。
     *
     * @param ds    数据源
     * @param table 表名
     * @return true 表示存在
     */
    protected boolean tableExists(JdbcDataSource ds, String table) {
        try (Connection connection = ds.getConnection()) {
            try (ResultSet rs = connection.getMetaData().getTables(null, null,
                    table.toLowerCase(java.util.Locale.ROOT), new String[] {"TABLE"})) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取元数据失败：" + e.getMessage(), e);
        }
    }

    /**
     * 读取单列字符串值。
     *
     * @param ds     数据源
     * @param sql    SQL
     * @param params 参数
     * @return 值；无行返回 null
     */
    protected String queryString(JdbcDataSource ds, String sql, Object... params) {
        try (Connection connection = ds.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + e.getMessage(), e);
        }
    }

    /**
     * 建任务（源/目标为上面两个 H2 数据源）。
     *
     * @return 任务
     */
    protected SyncTask newTask() {
        long id = SEQ.getAndIncrement();
        taskDao.insert(new SyncTask(id, "t" + id, SRC_DS_ID, TGT_DS_ID, 4, 4, 1000, 2_000_000,
                0L, 2, IncrementalMode.TRIGGER_LOG, 4, TaskState.READY), "test");
        return taskDao.findById(id).orElseThrow();
    }

    /**
     * 建表映射。
     *
     * <p><b>schema 传空串（= MySQL 语义：无 schema，用当前 database）</b>：H2 的
     * {@code DatabaseMetaData.getColumns} 把 schema 当"大小写不敏感的 pattern"，
     * 但 {@code getPrimaryKeys} 是"精确比较 schema"，{@code DATABASE_TO_LOWER=TRUE} 下
     * 库里的 schema 是 {@code public}，传 {@code PUBLIC} 会读不到主键（→ 退化单块、无法按主键定位）。
     * 传空串会走方言的 {@code defaultSchema()}（MySQL 为 null），与生产一致。
     *
     * @param taskId 任务 ID
     * @param table  表名
     * @param rows   估算行数
     * @return 映射
     */
    protected TableMapping newMapping(long taskId, String table, long rows) {
        long id = SEQ.getAndIncrement();
        mappingDao.insert(new TableMapping(id, taskId, "", table, "", table, true,
                "NUMERIC", rows, null, true, true));
        return mappingDao.findById(id).orElseThrow();
    }

    /**
     * 构造表元数据（与 {@link #createBothTables(String)} 的列一致）。
     *
     * @param table 表名
     * @param est   估算行数
     * @return 表元数据
     */
    protected TableMeta tableMeta(String table, long est) {
        List<ColumnMeta> extra = List.of(
                ColumnMeta.builder("name", 1, "VARCHAR").jdbcType(java.sql.Types.VARCHAR)
                        .size(64).build(),
                ColumnMeta.builder("amount", 2, "DECIMAL").jdbcType(java.sql.Types.DECIMAL)
                        .decimalDigits(2).build());
        return tableMeta(table, est, extra);
    }

    /**
     * 构造表元数据（自定义附加列，主键列固定为 {@code id}）。
     *
     * @param table 表名
     * @param est   估算行数
     * @param extra 附加列
     * @return 表元数据
     */
    protected TableMeta tableMeta(String table, long est, List<ColumnMeta> extra) {
        java.util.List<ColumnMeta> columns = new java.util.ArrayList<ColumnMeta>();
        columns.add(ColumnMeta.builder(PK, 0, "BIGINT").jdbcType(java.sql.Types.BIGINT).size(19)
                .nullable(false).build());
        columns.addAll(extra);
        return new TableMeta("PUBLIC", table, columns,
                new PrimaryKeyMeta("pk_" + table, List.of(PK)), List.of(), est, null);
    }

    /**
     * 建作业（不走引擎，用于修复/报告类测试）。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 映射 ID
     * @return 作业 ID
     */
    protected long newJob(long taskId, Long tableMappingId) {
        long jobId = idGenerator.nextId();
        jobDao.insert(jobId, taskId, tableMappingId, "MANUAL", 100, 1,
                "{}", "test");
        jobDao.markRunning(jobId, 1);
        return jobId;
    }

    /**
     * E5001/E5002 等业务异常的错误码断言辅助。
     *
     * @param e 异常
     * @return 错误码
     */
    protected static com.dbsync.common.exception.ErrorCode errorCodeOf(RuntimeException e) {
        if (e instanceof com.dbsync.common.exception.DbSyncException) {
            return ((com.dbsync.common.exception.DbSyncException) e).errorCode();
        }
        throw new IllegalStateException("不是 DbSyncException：" + e);
    }
}
