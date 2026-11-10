package com.dbsync.server;

import com.dbsync.server.config.DbSyncProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 平台启动类。
 *
 * <p>包根 {@code com.dbsync}，因此 {@code scanBasePackages = "com.dbsync"} 会把
 * api / core / metadata / migration / cdc / compare / cutover 各模块的 Bean 全部装配。
 *
 * <p>Flyway 自动装配被显式排除（见 {@code application.yml}）：元数据库是独立的
 * {@code HikariDataSource}，迁移由 {@code MetaDataSourceConfig} 以编程方式执行，
 * 避免 Spring Boot 用 {@code spring.datasource.*} 去推断元数据库。
 */
@SpringBootApplication(scanBasePackages = "com.dbsync")
@EnableScheduling
@EnableConfigurationProperties(DbSyncProperties.class)
public class DbSyncApplication {

    /**
     * 进程入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(DbSyncApplication.class, args);
    }
}
