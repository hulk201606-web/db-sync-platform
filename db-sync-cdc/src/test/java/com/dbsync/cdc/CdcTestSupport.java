package com.dbsync.cdc;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.util.HashUtil;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.DialectRegistry;
import com.dbsync.metadata.dao.ChangeLogDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import com.dbsync.metadata.dao.TriggerDefDao;
import com.dbsync.metadata.dao.WatermarkDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * cdc 模块测试基类：H2 元数据库（真实 DAO）+ H2 目标库（真实 SQL）。
 *
 * <p>不起 Spring 容器：cdc 的类都是普通对象，手工装配更快、也更能定位失败点。
 */
abstract class CdcTestSupport {

    /** 目标表主键列。 */
    static final String PK = "id";

    /** 目标表非主键列。 */
    static final String NAME = "name";

    private static final AtomicLong SEQ = new AtomicLong(20_000L);

    protected JdbcDataSource metaDs;
    protected JdbcTemplate jdbc;
    protected ChangeLogDao changeLogDao;
    protected WatermarkDao watermarkDao;
    protected SyncTaskDao syncTaskDao;
    protected TableMappingDao tableMappingDao;
    protected TriggerDefDao triggerDefDao;
    protected DeadLetterDao deadLetterDao;

    protected JdbcDataSource targetDs;
    protected Dialect dialect = DialectRegistry.get(com.dbsync.common.enums.DbType.MYSQL);
    protected GroupAssigner groupAssigner = new GroupAssigner();
    protected final SnowflakeId idGenerator = new SnowflakeId(SnowflakeId.defaultWorkerId());

    /**
     * 建元数据库与目标库。
     */
    @BeforeEach
    void setUpDatabases() {
        metaDs = h2(metaName());
        Flyway flyway = Flyway.configure().dataSource(metaDs)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(metaDs);
        changeLogDao = new ChangeLogDao(jdbc);
        watermarkDao = new WatermarkDao(jdbc);
        syncTaskDao = new SyncTaskDao(jdbc);
        tableMappingDao = new TableMappingDao(jdbc);
        triggerDefDao = new TriggerDefDao(jdbc);
        deadLetterDao = new DeadLetterDao(jdbc);
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
     * 元数据库名（每类独立）。
     *
     * @return 库名
     */
    protected String metaName() {
        return "cdc_" + getClass().getSimpleName().toLowerCase();
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
     * 建目标表（id BIGINT 主键，name VARCHAR）。
     *
     * @param table 表名
     */
    protected void createTargetTable(String table) {
        exec(targetDs, "DROP TABLE IF EXISTS " + table,
                "CREATE TABLE " + table + " (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(64))");
    }

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
            throw new IllegalStateException("查询失败：" + e.getMessage(), e);
        }
    }

    /**
     * 统计行数。
     *
     * @param table 表名
     * @return 行数
     */
    protected long countRows(String table) {
        try (Connection connection = targetDs.getConnection();
             Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("统计失败：" + e.getMessage(), e);
        }
    }

    /**
     * 建任务。
     *
     * @param groupCount   组数
     * @param concurrency  消费并发
     * @param incremental  增量模式
     * @return 任务
     */
    protected SyncTask newTask(int groupCount, int concurrency, IncrementalMode incremental) {
        long id = SEQ.getAndIncrement();
        SyncTask task = new SyncTask(id, "c" + id, 1L, 2L, groupCount, 2, 200, 100_000, 0L, 3,
                incremental, concurrency, TaskState.READY);
        syncTaskDao.insert(task, "test");
        return syncTaskDao.findById(id).orElseThrow();
    }

    /**
     * 建表映射。
     *
     * @param taskId 任务 ID
     * @param table  表名
     * @return 映射
     */
    protected TableMapping newMapping(long taskId, String table) {
        long id = SEQ.getAndIncrement();
        tableMappingDao.insert(new TableMapping(id, taskId, "PUBLIC", table, "PUBLIC", table, true,
                "NUMERIC", 1000L, null, true, true));
        return tableMappingDao.findById(id).orElseThrow();
    }

    /**
     * 目标表元数据（id 主键 + name 列）。
     *
     * @param table 表名
     * @return 表元数据
     */
    protected TableMeta tableMeta(String table) {
        ColumnMeta id = ColumnMeta.builder(PK, 0, "BIGINT")
                .jdbcType(java.sql.Types.BIGINT).size(19).nullable(false).build();
        ColumnMeta name = ColumnMeta.builder(NAME, 1, "VARCHAR")
                .jdbcType(java.sql.Types.VARCHAR).size(64).nullable(true).build();
        return new TableMeta("PUBLIC", table, List.of(id, name),
                new PrimaryKeyMeta("pk_" + table, List.of(PK)), List.of(), 1000L, null);
    }

    /**
     * 造一条 UPDATE 变更（after 里的键是大写，模拟触发器输出）。
     *
     * @param taskId  任务 ID
     * @param mapping 映射
     * @param pk      主键
     * @param name    值
     * @param seq     组内序号
     * @param group   组号
     * @return 事件
     */
    protected ChangeEvent change(long taskId, TableMapping mapping, long pk, String name, long seq,
                                 int group) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ID", Long.valueOf(pk));
        after.put("NAME", name);
        List<Object> pkTuple = List.of(Long.valueOf(pk));
        return new ChangeEvent(idGenerator.nextId(), taskId, mapping.getId(), group,
                HashUtil.pkHash(pkTuple), seq, Instant.now().minusSeconds(1), ChangeOp.UPDATE,
                pkTuple, null, after, null, ChangeStatus.NEW, 0, null, null);
    }

    /**
     * 造一条 DELETE 变更。
     *
     * @param taskId  任务 ID
     * @param mapping 映射
     * @param pk      主键
     * @param seq     序号
     * @param group   组号
     * @return 事件
     */
    protected ChangeEvent deleteChange(long taskId, TableMapping mapping, long pk, long seq,
                                       int group) {
        List<Object> pkTuple = List.of(Long.valueOf(pk));
        return new ChangeEvent(idGenerator.nextId(), taskId, mapping.getId(), group,
                HashUtil.pkHash(pkTuple), seq, Instant.now(), ChangeOp.DELETE, pkTuple, null, null,
                null, ChangeStatus.NEW, 0, null, null);
    }

    /**
     * 组装上下文。
     *
     * @param task    任务
     * @param mapping 表映射
     * @param table   目标表名
     * @param policy  冲突策略
     * @param applier 应用器（null 用默认）
     * @return 上下文
     */
    protected CdcContext context(SyncTask task, TableMapping mapping, String table,
                                 ConflictPolicy policy, UpsertApplier applier) {
        Map<Long, TableMeta> tables = new LinkedHashMap<>();
        tables.put(Long.valueOf(mapping.getId()), tableMeta(table));
        return CdcContext.builder()
                .taskId(task.getId())
                .consumerConcurrency(2)
                .owner("cdc-test")
                .queue(new ChangeQueue(changeLogDao))
                .watermarks(new WatermarkStore(watermarkDao, changeLogDao))
                .resolver(new ConflictResolver(dialect))
                .applier(applier)
                .dialect(dialect)
                .tables(tables)
                .policy(policy)
                .batchSize(100)
                .retry(new com.dbsync.common.util.RetryTemplate(3, Duration.ofMillis(1L), 1.0d, 0.0d))
                .deadLetters(letter -> deadLetterDao.insert(new com.dbsync.common.model.DeadLetter(
                        idGenerator.nextId(), letter.getTaskId(), letter.getTableMappingId(),
                        letter.getSourceType(), letter.getRefId(), letter.getPkJson(),
                        letter.getPayloadJson(), letter.getErrorCode(), letter.getErrorMsg(),
                        letter.getStackDigest(), letter.getStatus(), letter.getRetry())))
                .targetConnections(targetDs::getConnection)
                .groupLease(Duration.ofSeconds(30L))
                .pollInterval(Duration.ofMillis(5L))
                .build();
    }

    /**
     * 消费到队列清空。
     *
     * @param ctx     上下文
     * @param groupNo 组号
     * @param maxRuns 最大轮数（防止死循环）
     * @return 处理的事件数
     */
    protected int drainAll(CdcContext ctx, int groupNo, int maxRuns) {
        int total = 0;
        for (int i = 0; i < maxRuns; i++) {
            List<ChangeEvent> batch = ctx.getQueue().pollBatch(ctx.getTaskId(), groupNo,
                    ctx.getBatchSize());
            if (batch.isEmpty()) {
                return total;
            }
            total += batch.size();
            new GroupWorker(ctx, groupNo).drain(batch);
        }
        return total;
    }
}
