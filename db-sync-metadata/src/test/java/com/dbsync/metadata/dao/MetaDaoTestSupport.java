package com.dbsync.metadata.dao;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 元数据模块 DAO 测试基类。
 *
 * <p>不起 Spring 容器：DAO 只需 {@link JdbcTemplate} + {@link TransactionTemplate}，
 * 手工装配比 {@code @SpringBootTest} 快一个数量级（DAO 测试有 17 个类）。
 *
 * <p>每个测试类用<b>独立的</b>内存库名（{@code DB_CLOSE_DELAY=-1} 下同名库在 JVM 内共享，
 * 若不隔离会互相污染）。
 */
public abstract class MetaDaoTestSupport {

    /** 元数据库 H2 DataSource（MODE=MySQL，与生产 MySQL 8.0 语法子集一致）。 */
    protected JdbcDataSource dataSource;

    /** 元数据库 JdbcTemplate。 */
    protected JdbcTemplate jdbc;

    /** 元数据库事务模板（REQUIRES_NEW，与生产配置一致）。 */
    protected TransactionTemplate tx;

    /**
     * 每个测试前重建库并执行 Flyway 迁移。
     */
    @BeforeEach
    void setUpSchema() {
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
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 子类可覆盖以指定库名（默认用简单类名，天然唯一）。
     *
     * @return 库名
     */
    protected String dbName() {
        return getClass().getSimpleName().toLowerCase();
    }
}
