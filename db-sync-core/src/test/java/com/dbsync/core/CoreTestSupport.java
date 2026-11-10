package com.dbsync.core;

import com.dbsync.metadata.dao.ChangeLogDao;
import com.dbsync.metadata.dao.CheckpointDao;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.WatermarkDao;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * core 模块测试基类：一套独立的 H2 元数据库 + 常用 DAO。
 *
 * <p>不起 Spring 容器（core 的类都是普通对象，手工装配更可控也更快）。
 * 每个测试类用独立内存库名，避免 {@code DB_CLOSE_DELAY=-1} 下互相污染。
 */
public abstract class CoreTestSupport {

    /** 元数据库 DataSource。 */
    protected JdbcDataSource dataSource;

    /** 元数据库 JdbcTemplate。 */
    protected JdbcTemplate jdbc;

    /** 元数据库事务模板。 */
    protected TransactionTemplate tx;

    protected ShardDao shardDao;
    protected CheckpointDao checkpointDao;
    protected WatermarkDao watermarkDao;
    protected ChangeLogDao changeLogDao;
    protected DataSourceDao dataSourceDao;

    /**
     * 建库 + 迁移 + 装配 DAO。
     */
    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + dbName() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        shardDao = new ShardDao(jdbc, tx, com.dbsync.common.enums.ClaimStrategy.SKIP_LOCKED);
        checkpointDao = new CheckpointDao(jdbc);
        watermarkDao = new WatermarkDao(jdbc);
        changeLogDao = new ChangeLogDao(jdbc);
        dataSourceDao = new DataSourceDao(jdbc);
    }

    /**
     * 库名（默认简单类名小写）。
     *
     * @return 库名
     */
    protected String dbName() {
        return getClass().getSimpleName().toLowerCase();
    }
}
