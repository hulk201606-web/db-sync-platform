package com.dbsync.migration;

import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.ReadOptions;
import com.dbsync.common.options.WriteOptions;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.DialectRegistry;
import com.dbsync.metadata.dao.ChangeLogDao;
import com.dbsync.metadata.dao.CheckpointDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import com.dbsync.metadata.dao.WatermarkDao;
import com.dbsync.core.checkpoint.CheckpointStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * migration 模块测试基类：一套 H2 元数据库 + 一套源库 + 一套目标库。
 *
 * <p>不起 Spring 容器：migration 的类都是普通对象，手工装配更快也更可控。
 * 每个测试类用独立内存库名，避免 {@code DB_CLOSE_DELAY=-1} 下互相污染。
 */
abstract class MigrationTestSupport {

    /** 业务主键列名（源/目标表统一）。 */
    static final String PK_COLUMN = "id";

    /** 雪花 ID 生成器（测试内部用，避免与 DAO 抢 workerId）。 */
    private static final AtomicLong SEQ = new AtomicLong(1_000L);

    protected JdbcDataSource metaDs;
    protected JdbcTemplate jdbc;
    protected TransactionTemplate tx;

    protected ShardDao shardDao;
    protected CheckpointDao checkpointDao;
    protected WatermarkDao watermarkDao;
    protected ChangeLogDao changeLogDao;
    protected SyncTaskDao taskDao;
    protected TableMappingDao mappingDao;
    protected DeadLetterDao deadLetterDao;
    protected CheckpointStore checkpointStore;
    protected DeadLetterService deadLetterService;

    protected JdbcDataSource sourceDs;
    protected JdbcDataSource targetDs;

    protected Dialect dialect = DialectRegistry.get(com.dbsync.common.enums.DbType.MYSQL);

    /**
     * 建三套库并装配 DAO。
     */
    @BeforeEach
    void setUpDatabases() {
        metaDs = h2(metaName());
        Flyway flyway = Flyway.configure()
                .dataSource(metaDs)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(metaDs);
        tx = new TransactionTemplate(new DataSourceTransactionManager(metaDs));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        shardDao = new ShardDao(jdbc, tx, ClaimStrategy.SKIP_LOCKED);
        checkpointDao = new CheckpointDao(jdbc);
        watermarkDao = new WatermarkDao(jdbc);
        changeLogDao = new ChangeLogDao(jdbc);
        taskDao = new SyncTaskDao(jdbc);
        mappingDao = new TableMappingDao(jdbc);
        deadLetterDao = new DeadLetterDao(jdbc);
        checkpointStore = new CheckpointStore(checkpointDao, shardDao, watermarkDao, tx);
        deadLetterService = new DeadLetterService(deadLetterDao, shardDao, null);

        sourceDs = h2(metaName() + "_src");
        targetDs = h2(metaName() + "_tgt");
    }

    /**
     * 建一个 H2 内存库。
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
        return "mig_" + getClass().getSimpleName().toLowerCase();
    }

    /**
     * 执行 DDL/DML。
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
     * 建源表与目标表（主键 id BIGINT，name VARCHAR）。
     *
     * @param table 表名
     */
    protected void createBothTables(String table) {
        String ddl = "CREATE TABLE " + table + " (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(64))";
        exec(sourceDs, ddl);
        exec(targetDs, ddl);
    }

    /**
     * 批量插入数据行。
     *
     * @param ds    数据源
     * @param table 表名
     * @param from  起始 id（含）
     * @param to    结束 id（含）
     */
    protected void insertRows(JdbcDataSource ds, String table, long from, long to) {
        try (Connection connection = ds.getConnection()) {
            connection.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + table + " (id,name) VALUES (?,?)")) {
                for (long i = from; i <= to; i++) {
                    ps.setLong(1, i);
                    ps.setString(2, "n" + i);
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
     * 统计行数。
     *
     * @param ds    数据源
     * @param table 表名
     * @return 行数
     */
    protected long countRows(JdbcDataSource ds, String table) {
        try (Connection connection = ds.getConnection();
             Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("统计行数失败：" + e.getMessage(), e);
        }
    }

    /**
     * 建任务。
     *
     * @param state       初始状态
     * @param batchSize   批大小
     * @param shardRows   每分片目标行数
     * @param rateLimit   读限速（0 不限）
     * @param concurrency 并发
     * @return 任务
     */
    protected SyncTask newTask(TaskState state, int batchSize, int shardRows, long rateLimit,
                               int concurrency) {
        long id = SEQ.getAndIncrement();
        SyncTask task = new SyncTask(id, "t" + id, 1L, 2L, 4, concurrency, batchSize, shardRows,
                rateLimit, 2, IncrementalMode.TRIGGER_LOG, 4, state);
        taskDao.insert(task, "test");
        return taskDao.findById(id).orElseThrow();
    }

    /**
     * 建表映射。
     *
     * @param taskId 任务 ID
     * @param table  表名
     * @param rows   估算行数
     * @return 表映射
     */
    protected TableMapping newMapping(long taskId, String table, long rows) {
        long id = SEQ.getAndIncrement();
        mappingDao.insert(new TableMapping(id, taskId, "PUBLIC", table, "PUBLIC", table, true,
                "NUMERIC", rows, null, true, true));
        return mappingDao.findById(id).orElseThrow();
    }

    /**
     * 构造表元数据（id 主键 + name 列，与 {@link #createBothTables(String)} 一致）。
     *
     * @param table     表名
     * @param estimated 估算行数
     * @return 表元数据
     */
    protected TableMeta tableMeta(String table, long estimated) {
        ColumnMeta id = ColumnMeta.builder(PK_COLUMN, 0, "BIGINT")
                .jdbcType(java.sql.Types.BIGINT).size(19).nullable(false).build();
        ColumnMeta name = ColumnMeta.builder("name", 1, "VARCHAR")
                .jdbcType(java.sql.Types.VARCHAR).size(64).nullable(true).build();
        return new TableMeta("PUBLIC", table, List.of(id, name),
                new PrimaryKeyMeta("pk_" + table, List.of(PK_COLUMN)), List.of(), estimated, null);
    }

    /**
     * 构造分片执行上下文（真实 H2 连接 + 真实方言）。
     *
     * @param task    任务
     * @param mapping 表映射
     * @param batch   批大小
     * @return 上下文
     */
    protected ShardContext newContext(SyncTask task, TableMapping mapping, int batch) {
        return newContext(task, mapping, batch, () -> false);
    }

    /**
     * 构造分片执行上下文（可带停止信号）。
     *
     * @param task       任务
     * @param mapping    表映射
     * @param batch      批大小
     * @param stopSignal 停止信号
     * @return 上下文
     */
    protected ShardContext newContext(SyncTask task, TableMapping mapping, int batch,
                                      java.util.function.BooleanSupplier stopSignal) {
        TableMeta meta = tableMeta(mapping.getSourceTable(), mapping.getRowCountEstimate());
        return new ShardContext(task.getId(), mapping, meta, meta, dialect, dialect,
                sourceDs::getConnection, targetDs::getConnection,
                ReadOptions.of(batch), WriteOptions.upsert(), stopSignal);
    }

    /**
     * 构造运行时（测试用，不经过 {@code TaskRuntimeFactory} 的元数据装配）。
     *
     * @param task     任务
     * @param contexts 上下文
     * @return 运行时
     */
    protected TaskRuntime newRuntime(SyncTask task, java.util.Map<Long, ShardContext> contexts) {
        return newRuntime(task, contexts, null);
    }

    /**
     * 构造运行时（可指定限速器）。
     *
     * @param task     任务
     * @param contexts 上下文
     * @param throttle 限速器（null 表示不限速）
     * @return 运行时
     */
    protected TaskRuntime newRuntime(SyncTask task, java.util.Map<Long, ShardContext> contexts,
                                     com.dbsync.core.throttle.Throttle throttle) {
        return new TaskRuntime(task, contexts, new com.dbsync.core.read.JdbcReader(),
                new com.dbsync.core.write.JdbcBatchWriter(), checkpointStore, shardDao,
                deadLetterService,
                throttle == null ? com.dbsync.core.throttle.Throttle.unlimited() : throttle,
                new com.dbsync.common.util.RetryTemplate(2, java.time.Duration.ofMillis(1L), 1.0d,
                        0.0d));
    }
}
