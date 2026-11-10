package com.dbsync.metadata.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * 元数据库装配（架构 §7 / §1.5 "元数据库事务独立"）。
 *
 * <p>元数据库使用<b>独立</b>的 {@link HikariDataSource}：
 * <ul>
 *   <li>不与任何业务数据源混用（业务数据源由 {@code DataSourceRegistry} 动态创建，不是 Spring Bean）；</li>
 *   <li>Flyway 在本类中以编程方式执行（{@code spring.autoconfigure.exclude} 已排除 Flyway 自动装配），
 *       这样迁移目标是"元数据库"，不会被 {@code spring.datasource.*} 推断错；</li>
 *   <li>标记为 {@code @Primary}，使 {@code JdbcTemplate} 自动装配到元数据库而非业务库。</li>
 * </ul>
 */
@Configuration
public class MetaDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(MetaDataSourceConfig.class);

    /**
     * 元数据库连接池（含 Flyway 迁移）。
     *
     * <p><b>@Value 兜底与 application.yml 的 {@code db-sync.meta.*} 完全一致</b>：
     * 默认 H2 <b>文件库</b>（{@code ./data/meta}）、{@code sa/sa}、池 10/2。
     * 严禁再兜底到内存库——内存库重启即丢台账，且多实例各写各的会让租约/心跳协作失效。
     *
     * @param url           JDBC URL（默认 H2 文件库，仅供单机/联调）
     * @param username      用户名
     * @param password      密码
     * @param poolMaxSize   池上限
     * @param poolMinIdle   最小空闲
     * @param migrateAtStart 是否启动时执行迁移
     * @return 元数据库 DataSource
     */
    @Bean(name = "metaDataSource", destroyMethod = "close")
    @Primary
    public DataSource metaDataSource(
            @Value("${db-sync.meta.jdbc-url:jdbc:h2:file:./data/meta;MODE=MySQL;DATABASE_TO_LOWER=TRUE}")
            String url,
            @Value("${db-sync.meta.username:sa}") String username,
            @Value("${db-sync.meta.password:sa}") String password,
            @Value("${db-sync.meta.pool-max-size:10}") int poolMaxSize,
            @Value("${db-sync.meta.pool-min-idle:2}") int poolMinIdle,
            @Value("${db-sync.meta.migrate-at-start:true}") boolean migrateAtStart) {

        HikariConfig config = new HikariConfig();
        config.setPoolName("dbsync-meta");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, poolMaxSize));
        config.setMinimumIdle(Math.max(1, poolMinIdle));
        config.setAutoCommit(true); // 元数据库用自动提交 + 显式 TransactionTemplate 管多语句原子
        HikariDataSource dataSource = new HikariDataSource(config);

        if (migrateAtStart) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();
            int applied = flyway.migrate().migrationsExecuted;
            log.info("元数据库迁移完成：applied={}", applied);
        }
        return dataSource;
    }

    /**
     * 元数据库专用 JdbcTemplate（DAO 统一按名字注入，避免误用业务数据源）。
     *
     * @param metaDataSource 元数据库 DataSource
     * @return JdbcTemplate
     */
    @Bean(name = "metaJdbcTemplate")
    @Primary
    public JdbcTemplate metaJdbcTemplate(DataSource metaDataSource) {
        return new JdbcTemplate(metaDataSource);
    }

    /**
     * 元数据库事务管理器（供 {@code TransactionTemplate} 使用）。
     *
     * @param metaDataSource 元数据库 DataSource
     * @return 事务管理器
     */
    @Bean(name = "metaTransactionManager")
    @Primary
    public PlatformTransactionManager metaTransactionManager(DataSource metaDataSource) {
        return new DataSourceTransactionManager(metaDataSource);
    }

    /**
     * 元数据库事务模板（多语句原子 DAO 方法专用，04 文档 §1.5）。
     *
     * <p>传播行为 {@code REQUIRES_NEW}：DAO 方法必须自带独立事务，
     * 不能被调用方（业务线程/HTTP 请求）的长事务牵着走，否则抢占分片的行锁会长时间持有。
     *
     * @param metaTransactionManager 元数据库事务管理器
     * @return TransactionTemplate
     */
    @Bean(name = "metaTransactionTemplate")
    public TransactionTemplate metaTransactionTemplate(
            @Qualifier("metaTransactionManager") PlatformTransactionManager metaTransactionManager) {
        TransactionTemplate template = new TransactionTemplate(metaTransactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
