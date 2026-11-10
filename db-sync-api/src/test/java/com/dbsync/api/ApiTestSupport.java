package com.dbsync.api;

import com.dbsync.api.audit.AuditAspect;
import com.dbsync.api.audit.AuditService;
import com.dbsync.api.exception.GlobalExceptionHandler;
import com.dbsync.api.security.AuthService;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.RbacInterceptor;
import com.dbsync.api.security.Role;
import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.EnvType;
import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.JdbcEndpoint;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.metadata.dao.AuditLogDao;
import com.dbsync.metadata.dao.ChangeLogDao;
import com.dbsync.metadata.dao.ColumnMappingDao;
import com.dbsync.metadata.dao.CompareBlockDao;
import com.dbsync.metadata.dao.CompareDiffDao;
import com.dbsync.metadata.dao.CompareJobDao;
import com.dbsync.metadata.dao.CutoverPlanDao;
import com.dbsync.metadata.dao.CutoverStepLogDao;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.metadata.dao.InstanceDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import com.dbsync.metadata.dao.UserDao;
import com.dbsync.metadata.dao.WatermarkDao;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * api 模块测试基类：一套 H2 元数据库 + 真实 DAO + 真实 RBAC/审计拦截器 + 独立 MockMvc。
 *
 * <p>为什么用 {@code MockMvcBuilders.standaloneSetup} 而不是 {@code @SpringBootTest}：
 * <ul>
 *   <li>api 层类全是普通对象（构造注入），手动装配更快、更可控；</li>
 *   <li>拦截器顺序（RBAC → 审计）必须被真实执行，standalone 也能做到；</li>
 *   <li>不用起 Tomcat / 不依赖 server 模块的 Bean 装配，测试边界清晰。</li>
 * </ul>
 *
 * <p>每个测试类用独立 H2 库名，避免 {@code DB_CLOSE_DELAY=-1} 下互相污染。
 */
public abstract class ApiTestSupport {

    /** 默认测试口令。 */
    protected static final String PASSWORD = "Admin@12345";

    protected JdbcDataSource metaDs;
    protected JdbcTemplate jdbc;
    protected DataSourceDao dataSourceDao;
    protected SyncTaskDao taskDao;
    protected TableMappingDao mappingDao;
    protected ColumnMappingDao columnMappingDao;
    protected AuditLogDao auditLogDao;
    protected UserDao userDao;
    protected DeadLetterDao deadLetterDao;
    protected ShardDao shardDao;
    protected InstanceDao instanceDao;
    protected CompareJobDao compareJobDao;
    protected CompareDiffDao compareDiffDao;
    protected CompareBlockDao compareBlockDao;
    protected CutoverPlanDao cutoverPlanDao;
    protected CutoverStepLogDao cutoverStepLogDao;
    protected ChangeLogDao changeLogDao;
    protected WatermarkDao watermarkDao;
    protected SnowflakeId idGenerator;
    protected AuthService authService;
    protected AuditService auditService;
    protected RbacInterceptor rbacInterceptor;
    protected AuditAspect auditAspect;

    /**
     * 建库、跑 Flyway、装配 DAO 与安全组件。
     */
    @BeforeEach
    void setUpApi() {
        metaDs = h2(metaName());
        Flyway flyway = Flyway.configure().dataSource(metaDs)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(metaDs);

        dataSourceDao = new DataSourceDao(jdbc);
        taskDao = new SyncTaskDao(jdbc);
        mappingDao = new TableMappingDao(jdbc);
        columnMappingDao = new ColumnMappingDao(jdbc);
        auditLogDao = new AuditLogDao(jdbc);
        userDao = new UserDao(jdbc);
        deadLetterDao = new DeadLetterDao(jdbc);
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(metaDs));
        shardDao = new ShardDao(jdbc, tx, ClaimStrategy.SKIP_LOCKED);
        instanceDao = new InstanceDao(jdbc);
        compareJobDao = new CompareJobDao(jdbc);
        compareDiffDao = new CompareDiffDao(jdbc);
        compareBlockDao = new CompareBlockDao(jdbc);
        cutoverPlanDao = new CutoverPlanDao(jdbc);
        cutoverStepLogDao = new CutoverStepLogDao(jdbc);
        changeLogDao = new ChangeLogDao(jdbc);
        watermarkDao = new WatermarkDao(jdbc);
        idGenerator = new SnowflakeId(1L);

        authService = new AuthService(userDao);
        auditService = new AuditService(auditLogDao);
        rbacInterceptor = new RbacInterceptor(authService, auditService);
        auditAspect = new AuditAspect(auditService);
    }

    /**
     * 构建 MockMvc：注册 RBAC → 审计（顺序即 T42 要求）→ 全局异常。
     *
     * @param controllers 控制器
     * @return MockMvc
     */
    protected MockMvc mockMvc(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .addInterceptors(rbacInterceptor, auditAspect)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 建一个用户并返回其令牌。
     *
     * @param username 用户名
     * @param role     角色
     * @return 令牌
     */
    protected String loginAs(String username, Role role) {
        if (userDao.findByUsername(username).isEmpty()) {
            String salt = AuthService.newSaltHex();
            userDao.insert(username, username, AuthService.hashPassword(PASSWORD, salt), salt,
                    role.name());
        }
        Optional<CurrentUser> user = authService.login(username, PASSWORD);
        return user.orElseThrow(() -> new IllegalStateException("登录失败：" + username)).getToken();
    }

    /**
     * 造一个数据源。
     *
     * @param name 名称
     * @param type 类型
     * @return 数据源 ID
     */
    protected long newDataSource(String name, DbType type) {
        long id = idGenerator.nextId();
        DataSourceDef def = DataSourceDef.builder(id, name)
                .dbType(type)
                .env(EnvType.DEV)
                .endpoint(new JdbcEndpoint("127.0.0.1", 1, "demo", "", Map.of()))
                .username("u")
                .passwordCipher("cipher")
                .keyVersion((short) 1)
                .identifierCase(IdentifierCase.LOWER)
                .poolMaxSize(2)
                .poolMinIdle(1)
                .fetchSize(100)
                .healthStatus("UNKNOWN")
                .build();
        dataSourceDao.insert(def, "test");
        return id;
    }

    /**
     * 造一个任务（DRAFT）。
     *
     * @param sourceDsId 源数据源
     * @param targetDsId 目标数据源
     * @return 任务 ID
     */
    protected long newTask(long sourceDsId, long targetDsId) {
        long id = idGenerator.nextId();
        SyncTask task = new SyncTask(id, "t-" + id, sourceDsId, targetDsId, 16, 4, 1000,
                10000, 0L, 3, IncrementalMode.TRIGGER_LOG, 4, TaskState.DRAFT);
        taskDao.insert(task, "test");
        return id;
    }

    /**
     * 审计条数（便捷断言）。
     *
     * @return 条数
     */
    protected long auditCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sys_audit_log", Long.class);
        return n == null ? 0L : n.longValue();
    }

    /**
     * 取最近一条审计的关键字段。
     *
     * @return {action, result, params}
     */
    protected Map<String, String> lastAudit() {
        Map<String, String> row = new LinkedHashMap<>();
        jdbc.query("SELECT action,result,params_digest FROM sys_audit_log "
                + "ORDER BY created_at DESC, id DESC LIMIT 1", rs -> {
                    row.put("action", rs.getString("action"));
                    row.put("result", rs.getString("result"));
                    row.put("params", rs.getString("params_digest"));
                });
        return row;
    }

    /**
     * @return 每个测试类的独立 H2 库名
     */
    protected String metaName() {
        return "api_" + getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    private static JdbcDataSource h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("sa");
        return ds;
    }
}
