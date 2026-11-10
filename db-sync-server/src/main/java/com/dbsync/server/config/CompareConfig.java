package com.dbsync.server.config;

import com.dbsync.compare.BlockPlanner;
import com.dbsync.compare.CompareEngine;
import com.dbsync.compare.CompareReportBuilder;
import com.dbsync.compare.CompareVerdictAdapter;
import com.dbsync.compare.DiffRepairer;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.port.CompareVerdictPort;
import com.dbsync.metadata.dao.CompareBlockDao;
import com.dbsync.metadata.dao.CompareDiffDao;
import com.dbsync.metadata.dao.CompareJobDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.util.function.Predicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 比对与修复装配（T34~T37）。
 *
 * <p>反修权限检查器在这里注入：{@code FIX_TO_SOURCE} 必须 ADMIN，
 * 且 service 层还会再查一次（双保险，见 T37 验收 1）。
 */
@Configuration
public class CompareConfig {

    /**
     * 分块规划器（按键范围切块，保证并行比对不重不漏）。
     *
     * @return 规划器
     */
    @Bean
    public BlockPlanner blockPlanner() {
        return new BlockPlanner();
    }

    /**
     * 比对引擎。
     *
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param jobDao          作业 DAO
     * @param blockDao        块 DAO
     * @param diffDao         差异 DAO
     * @param registry        数据源注册表
     * @param blockPlanner    分块规划器
     * @param snowflakeId     雪花 ID
     * @return 引擎
     */
    @Bean
    public CompareEngine compareEngine(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao,
                                       CompareJobDao jobDao, CompareBlockDao blockDao,
                                       CompareDiffDao diffDao, DataSourceRegistry registry,
                                       BlockPlanner blockPlanner, SnowflakeId snowflakeId) {
        return new CompareEngine(syncTaskDao, tableMappingDao, jobDao, blockDao, diffDao, registry,
                blockPlanner, snowflakeId);
    }

    /**
     * 差异修复器（重放到目标 + 反向修复源库）。
     *
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param jobDao          作业 DAO
     * @param diffDao         差异 DAO
     * @param registry        数据源注册表
     * @param adminChecker    操作人是否 ADMIN（与登录态对齐）
     * @return 修复器
     */
    @Bean
    public DiffRepairer diffRepairer(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao,
                                     CompareJobDao jobDao, CompareDiffDao diffDao,
                                     DataSourceRegistry registry, Predicate<String> adminChecker) {
        return new DiffRepairer(syncTaskDao, tableMappingDao, jobDao, diffDao, registry,
                new com.dbsync.core.write.JdbcBatchWriter(), adminChecker);
    }

    /**
     * 报告生成器（HTML + CSV 落盘）。
     *
     * @param properties 配置
     * @return 生成器
     */
    @Bean
    public CompareReportBuilder compareReportBuilder(DbSyncProperties properties) {
        return new CompareReportBuilder(properties.getReportDir());
    }

    /**
     * 比对结论端口（Gate G1 的唯一取数口）。
     *
     * @param compareJobDao 作业 DAO
     * @return 端口实现
     */
    @Bean
    public CompareVerdictPort compareVerdictPort(CompareJobDao compareJobDao) {
        return new CompareVerdictAdapter(compareJobDao);
    }
}
