package com.dbsync.cutover;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.options.GateThresholds;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.metadata.dao.CutoverPlanDao;
import com.dbsync.metadata.dao.CutoverStepLogDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * cutover 模块测试基类：一套 H2 元数据库 + 真实 DAO。
 *
 * <p>不起 Spring 容器：切流编排是纯状态机 + DB 写入，手工装配最可控。
 * 每个测试类用独立内存库名，避免 {@code DB_CLOSE_DELAY=-1} 下互相污染。
 */
abstract class CutoverTestSupport {

    private static final AtomicLong SEQ = new AtomicLong(1_000L);

    protected JdbcDataSource metaDs;
    protected JdbcTemplate jdbc;
    protected CutoverPlanDao planDao;
    protected CutoverStepLogDao stepLogDao;
    protected DeadLetterDao deadLetterDao;
    protected SnowflakeId idGenerator;
    protected RouteRegistry routeRegistry;

    /**
     * 建库并装配 DAO。
     */
    @BeforeEach
    void setUpCutover() {
        metaDs = h2(metaName());
        Flyway flyway = Flyway.configure().dataSource(metaDs)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(metaDs);
        planDao = new CutoverPlanDao(jdbc);
        stepLogDao = new CutoverStepLogDao(jdbc);
        deadLetterDao = new DeadLetterDao(jdbc);
        idGenerator = new SnowflakeId(SnowflakeId.defaultWorkerId());
        routeRegistry = new RouteRegistry();
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
        return "ct_" + getClass().getSimpleName().toLowerCase();
    }

    /**
     * 执行 SQL。
     *
     * @param sqls 语句
     */
    protected void exec(String... sqls) {
        try (Connection connection = metaDs.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : sqls) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("执行 SQL 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 建计划。
     *
     * @param intent     intent 状态
     * @param confirmed  confirmed 状态
     * @return 计划 ID
     */
    protected long newPlan(CutoverState intent, CutoverState confirmed) {
        long planId = idGenerator.nextId();
        long taskId = SEQ.getAndIncrement();
        CutoverPlan plan = new CutoverPlan(planId, "P" + planId, taskId, "测试计划", intent,
                confirmed, 0L, CutoverPlanDao.STATUS_ACTIVE, GateThresholds.defaults(), 0, 0);
        planDao.insert(plan, "[]", "tester");
        return planId;
    }

    /**
     * 读计划。
     *
     * @param planId 计划 ID
     * @return 计划
     */
    protected CutoverPlan plan(long planId) {
        return planDao.findById(planId).orElseThrow();
    }

    /**
     * 把计划直接推到某个"已确认"的状态（模拟历史推进结果，跳过编排流程）。
     *
     * @param planId 计划 ID
     * @param state  目标状态
     * @param routeVersion 路由版本
     */
    protected void forceState(long planId, CutoverState state, long routeVersion) {
        exec("UPDATE cutover_plan SET intent_state='" + state.name() + "',confirmed_state='"
                + state.name() + "',route_version=" + routeVersion + " WHERE id=" + planId);
    }
}
