package com.dbsync.api.cdc;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.cdc.ChangeQueue;
import com.dbsync.cdc.WatermarkStore;
import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.EnvType;
import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.JdbcEndpoint;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.Watermark;
import com.dbsync.common.util.HashUtil;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.pool.NamedThreadPoolManager;
import com.dbsync.metadata.dao.CheckpointDao;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.TriggerDefDao;
import com.dbsync.migration.DeadLetterService;
import com.zaxxer.hikari.HikariConfig;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * 增量运行期测试基类：真元数据库（H2 + Flyway）+ 真目标库（H2）+ 真 DAO + 真工厂。
 *
 * <p>目标库用 {@link H2Registry} 让 {@code DataSourceRegistry} 返回 H2 连接池、
 * 但方言仍是 {@code MYSQL}（与 cdc/compare 模块既有手法一致）：这样
 * {@link CdcContextFactory} 的元数据读取与 {@code UpsertApplier} 的 SQL 走的是<b>生产代码路径</b>，
 * 不是被测试替身绕过的假路径。
 *
 * <p>为什么值得这样搭：本期要证明的是"增量变更真的会落库"（水位线前进 + 日志变 APPLIED +
 * 目标表有新行）。若把工厂/消费者替换成 mock，这条断言就退化成"调用了 start"，
 * 完全无法证明上线阻断项被修好。
 */
public abstract class CdcRuntimeTestSupport extends ApiTestSupport {

    /** 源数据源 ID（固定，便于 H2 注册表按 ID 分池）。 */
    protected static final long SRC_DS_ID = 9_100_001L;
    /** 目标数据源 ID。 */
    protected static final long TGT_DS_ID = 9_100_002L;

    /** 本实例标识（与生产一致：消费者 ID 同时是组租约持有者）。 */
    protected static final String INSTANCE = "test-instance";

    protected JdbcDataSource sourceDs;
    protected JdbcDataSource targetDs;
    protected DataSourceRegistry registry;
    protected NamedThreadPoolManager pools;
    protected DeadLetterService deadLetterService;
    protected CdcContextFactory factory;
    protected CdcRuntimeManager runtime;
    protected CdcRuntimeSupervisor supervisor;
    protected CheckpointDao checkpointDao;
    protected TriggerDefDao triggerDefDao;

    /**
     * 建源/目标 H2、数据源定义、注册表、工厂与运行期。
     */
    @BeforeEach
    void setUpCdcRuntime() {
        sourceDs = h2(metaName() + "_src");
        targetDs = h2(metaName() + "_tgt");
        dataSourceDao.insert(dataSourceDef(SRC_DS_ID, "src"), "test");
        dataSourceDao.insert(dataSourceDef(TGT_DS_ID, "tgt"), "test");
        registry = new H2Registry(dataSourceDao, sourceDs, targetDs);
        deadLetterService = new DeadLetterService(deadLetterDao, shardDao, null);
        pools = new NamedThreadPoolManager();
        checkpointDao = new CheckpointDao(jdbc);
        triggerDefDao = new TriggerDefDao(jdbc);
        factory = new CdcContextFactory(registry, taskDao, mappingDao,
                new ChangeQueue(changeLogDao), new WatermarkStore(watermarkDao, changeLogDao),
                deadLetterService::create, INSTANCE, Duration.ofSeconds(30L),
                Duration.ofMillis(5L));
        // 6 参构造：注入 watermarkDao 让 anyInstanceConsuming 走真实查询（采集侧单独由各用例装配，
        // 这样端到端用例可以精确控制"第几轮采集"，不受后台定时器干扰）
        runtime = new CdcRuntimeManager(pools, factory, taskDao, INSTANCE, watermarkDao, null);
        supervisor = new CdcRuntimeSupervisor(runtime, taskDao);
    }

    /**
     * 停掉所有会话与线程池，避免测试之间互相干扰（H2 库是 {@code DB_CLOSE_DELAY=-1}）。
     */
    @AfterEach
    void tearDownCdcRuntime() {
        runtime.stopAll();
        pools.shutdownAll(Duration.ofMillis(500L));
    }

    // ---------------------------------------------------------------- 造数据

    /**
     * 建任务（直接写成目标状态）。
     *
     * @param state        状态
     * @param mode         增量模式
     * @param groupCount   分组数
     * @param concurrency  消费并发
     * @return 任务 ID
     */
    protected long newTask(TaskState state, IncrementalMode mode, int groupCount, int concurrency) {
        long id = idGenerator.nextId();
        SyncTask task = new SyncTask(id, "t-" + id, SRC_DS_ID, TGT_DS_ID, groupCount, 2, 100,
                10_000, 0L, 2, mode, concurrency, state);
        taskDao.insert(task, "test");
        return id;
    }

    /**
     * 建表映射（目标 schema 留空 → 走方言默认 schema）。
     *
     * @param taskId      任务 ID
     * @param targetTable 目标表名
     * @return 映射 ID
     */
    protected long newMapping(long taskId, String targetTable) {
        return newMapping(taskId, targetTable, ConflictPolicy.SOURCE_WINS);
    }

    /**
     * 建表映射并指定<b>表级</b>冲突策略（CDC-06 按表可配）。
     *
     * @param taskId         任务 ID
     * @param targetTable    目标表名
     * @param conflictPolicy 表级策略
     * @return 映射 ID
     */
    protected long newMapping(long taskId, String targetTable, ConflictPolicy conflictPolicy) {
        long id = idGenerator.nextId();
        mappingDao.insert(new TableMapping(id, taskId, "public", "src_" + targetTable, null,
                targetTable, true, "NUMERIC", 100L, null, true, true, conflictPolicy));
        return id;
    }

    /**
     * 在目标库建表（{@code id BIGINT 主键, name VARCHAR(64)}）。
     *
     * @param table 表名
     */
    protected void createTargetTable(String table) {
        exec(targetDs, "DROP TABLE IF EXISTS " + table,
                "CREATE TABLE " + table + " (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(64))");
    }

    /**
     * 造一条 UPDATE 变更（after 的键用大写，模拟触发器输出）。
     *
     * @param taskId    任务 ID
     * @param mappingId 映射 ID
     * @param pk        主键
     * @param name      值
     * @param seq       组内序号
     * @param group     组号
     * @return 事件
     */
    protected ChangeEvent change(long taskId, long mappingId, long pk, String name, long seq,
                                 int group) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ID", Long.valueOf(pk));
        after.put("NAME", name);
        List<Object> pkTuple = List.of(Long.valueOf(pk));
        return new ChangeEvent(idGenerator.nextId(), taskId, mappingId, group,
                HashUtil.pkHash(pkTuple), seq, Instant.now().minusSeconds(1L), ChangeOp.UPDATE,
                pkTuple, null, after, null, ChangeStatus.NEW, 0, null, null);
    }

    // ---------------------------------------------------------------- 采集侧辅助

    /**
     * 在<b>源库</b>建触发器日志表（结构与 MySQL 方言生成的一致）。
     *
     * <p>为什么直接建表而不真跑 {@code CREATE TRIGGER}：H2 的 MySQL 兼容模式不支持
     * {@code JSON_ARRAY/JSON_OBJECT} 那套触发器语法（cdc 模块既有测试已确认），
     * 但"日志表长什么样 + 轮询器能否正确搬运它"正是本用例要验证的东西。
     *
     * @param sourceTable 源业务表名
     * @return 日志表名
     */
    protected String createTriggerLog(String sourceTable) {
        String logTable = com.dbsync.dialect.DialectRegistry
                .get(DbType.MYSQL).trigger().changeLogTableName(sourceTable);
        exec(sourceDs, "DROP TABLE IF EXISTS " + logTable,
                "CREATE TABLE " + logTable + " (id BIGINT NOT NULL AUTO_INCREMENT, "
                        + "op VARCHAR(8) NOT NULL, pk_json VARCHAR(2048) NOT NULL, "
                        + "before_json LONGTEXT NULL, after_json LONGTEXT NULL, "
                        + "commit_ts TIMESTAMP NOT NULL, PRIMARY KEY (id))",
                "CREATE INDEX " + logTable + "_TS ON " + logTable + " (commit_ts)");
        return logTable;
    }

    /**
     * 往源库日志表写一行（模拟触发器已写入的变更）。
     *
     * @param logTable   日志表名
     * @param op         操作
     * @param pkJson     主键 JSON
     * @param beforeJson 前像（可 null）
     * @param afterJson  后像（可 null）
     */
    protected void appendTriggerLog(String logTable, String op, String pkJson, String beforeJson,
                                    String afterJson) {
        String before = beforeJson == null ? "NULL" : "'" + beforeJson + "'";
        String after = afterJson == null ? "NULL" : "'" + afterJson + "'";
        exec(sourceDs, "INSERT INTO " + logTable
                + " (op,pk_json,before_json,after_json,commit_ts) VALUES ('" + op + "','" + pkJson
                + "'," + before + "," + after + ",CURRENT_TIMESTAMP)");
    }

    /**
     * 建一条触发器台账（让轮询器能查到日志表名）。
     *
     * @param taskId    任务 ID
     * @param mappingId 映射 ID
     * @param logTable  日志表名
     */
    protected void registerTriggerDef(long taskId, long mappingId, String logTable) {
        triggerDefDao.upsertPending(taskId, mappingId, SRC_DS_ID, logTable, "trg_" + mappingId,
                "[]");
        triggerDefDao.markDeployed(taskId, mappingId);
    }

    /**
     * 该任务在 {@code sync_change_log} 里的总行数（判"有没有重复采集"用）。
     *
     * @param taskId 任务 ID
     * @return 行数
     */
    protected long changeLogTotal(long taskId) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_change_log WHERE task_id=?",
                Long.class, taskId);
        return n == null ? 0L : n.longValue();
    }

    /**
     * 读采集位点（{@code sync_checkpoint} 的 POLLER 行）。
     *
     * @param taskId    任务 ID
     * @param mappingId 映射 ID
     * @return 位点；不存在返回 empty
     */
    protected java.util.Optional<com.dbsync.common.model.Checkpoint> pollerCheckpoint(long taskId,
                                                                                     long mappingId) {
        return checkpointDao.load(taskId, mappingId, com.dbsync.common.enums.ScopeType.POLLER, 0);
    }

    // ---------------------------------------------------------------- 断言辅助
    /**
     * 读目标表某行的 name。
     *
     * @param table 表名
     * @param id    主键
     * @return 值；不存在返回 null
     */
    protected String nameOf(String table, long id) {
        try (Connection connection = targetDs.getConnection();
             java.sql.PreparedStatement ps = connection.prepareStatement(
                     "SELECT name FROM " + table + " WHERE id=?")) {
            ps.setLong(1, id);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询目标表失败：" + e.getMessage(), e);
        }
    }

    /**
     * 读组水位。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @return 水位（不存在返回 empty）
     */
    protected Optional<Watermark> watermark(long taskId, int groupNo) {
        return watermarkDao.load(taskId, groupNo);
    }

    /**
     * 轮询等待条件成立（默认 10 秒）。
     *
     * @param condition 条件
     * @param what      描述（失败信息）
     */
    protected void awaitUntil(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断：" + what, e);
            }
        }
        throw new AssertionError("等待超时（10s）：" + what);
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

    // ---------------------------------------------------------------- 内部装配

    /**
     * 建一个 H2 内存库（与 {@code ApiTestSupport} 同一套 URL 参数，保证大小写/模式一致）。
     *
     * @param name 库名
     * @return 数据源
     */
    private JdbcDataSource h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        ds.setUser("sa");
        ds.setPassword("sa");
        return ds;
    }

    /**
     * 数据源定义（MYSQL 方言 + H2 连接池）。
     *
     * @param id   ID
     * @param name 名称
     * @return 定义
     */
    private static DataSourceDef dataSourceDef(long id, String name) {
        return DataSourceDef.builder(id, name)
                .dbType(DbType.MYSQL).env(EnvType.DEV)
                .endpoint(new JdbcEndpoint("127.0.0.1", 3306, "demo", "", Map.of()))
                .username("sa").passwordCipher("").keyVersion((short) 1)
                .identifierCase(IdentifierCase.LOWER).poolMaxSize(2).poolMinIdle(1)
                .fetchSize(100).healthStatus("UNKNOWN").build();
    }

    /**
     * H2 数据源注册表：绕过 Hikari 与驱动加载。
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
}
