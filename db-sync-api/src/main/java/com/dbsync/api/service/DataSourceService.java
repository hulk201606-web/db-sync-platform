package com.dbsync.api.service;

import com.dbsync.api.dto.datasource.ConnectionTestView;
import com.dbsync.api.dto.datasource.DataSourceRequest;
import com.dbsync.api.dto.datasource.DataSourceView;
import com.dbsync.api.dto.datasource.TableInfoView;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.EnvType;
import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.JdbcEndpoint;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.util.AesGcmCipher;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.datasource.ConnectionTestResult;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据源服务（T43：CRUD / 连接测试 / 表清单 / 单表元数据）。
 *
 * <p>硬约束：
 * <ul>
 *   <li><b>口令加密落库</b>（PRD DS-02）：{@link AesGcmCipher#encrypt} 后写 {@code password_cipher}，
 *       响应里永不回显（连密文都不回）；</li>
 *   <li><b>删除前校验引用</b>（E2003）：被任务引用即拒绝，避免"删了数据源、任务跑不起来"；</li>
 *   <li><b>标识符白名单</b>（架构 §12.2）：表名/列名一律经 {@code IdentifierValidator} 校验，
 *       用户无法把 {@code t; DROP TABLE x} 透传到 SQL。</li>
 * </ul>
 */
public class DataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);

    /** 连接池上限默认值。 */
    private static final int DEFAULT_POOL_MAX = 8;
    /** 最小空闲默认值。 */
    private static final int DEFAULT_POOL_MIN_IDLE = 1;
    /** fetchSize 默认值。 */
    private static final int DEFAULT_FETCH_SIZE = 1000;

    private final DataSourceDao dataSourceDao;
    private final SyncTaskDao syncTaskDao;
    private final DataSourceRegistry registry;
    private final AesGcmCipher cipher;
    private final SnowflakeId idGenerator;

    /**
     * 构造。
     *
     * @param dataSourceDao 数据源 DAO
     * @param syncTaskDao   任务 DAO（删除前查引用）
     * @param registry      数据源注册表（连接测试/元数据）
     * @param cipher        AES-GCM 加解密器
     * @param idGenerator   雪花 ID
     */
    public DataSourceService(DataSourceDao dataSourceDao, SyncTaskDao syncTaskDao,
                             DataSourceRegistry registry, AesGcmCipher cipher,
                             SnowflakeId idGenerator) {
        this.dataSourceDao = dataSourceDao;
        this.syncTaskDao = syncTaskDao;
        this.registry = registry;
        this.cipher = cipher;
        this.idGenerator = idGenerator == null
                ? new SnowflakeId(SnowflakeId.defaultWorkerId()) : idGenerator;
    }

    /**
     * 新建数据源。
     *
     * @param req      请求
     * @param operator 操作人
     * @return 视图
     */
    public DataSourceView create(DataSourceRequest req, String operator) {
        validate(req, true);
        if (dataSourceDao.findByName(req.name()).isPresent()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "数据源名称已存在：" + req.name())
                    .with("field", "name");
        }
        long id = idGenerator.nextId();
        // 口令必须加密落库（PRD DS-02）：明文只在请求体中出现一次，绝不写库
        String passwordCipher = cipher.encrypt(req.password());
        short keyVersion = cipher.currentVersion();
        DataSourceDef def = build(id, req, passwordCipher, keyVersion);
        dataSourceDao.insert(def, operator);
        log.info("数据源已创建 id={} name={} by={}", id, req.name(), operator);
        return DataSourceView.of(dataSourceDao.findById(id).orElse(def));
    }

    /**
     * 编辑数据源（乐观锁）。
     *
     * @param id       数据源 ID
     * @param req      请求
     * @param operator 操作人
     * @return 视图
     */
    public DataSourceView update(long id, DataSourceRequest req, String operator) {
        DataSourceDef existing = loadOrThrow(id);
        validate(req, false);
        Optional<DataSourceDef> sameName = dataSourceDao.findByName(req.name());
        if (sameName.isPresent() && sameName.get().getId() != id) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "数据源名称已存在：" + req.name());
        }
        String passwordCipher = existing.getPasswordCipher();
        short keyVersion = existing.getKeyVersion();
        if (req.password() != null && !req.password().isEmpty()) {
            passwordCipher = cipher.encrypt(req.password());
            keyVersion = cipher.currentVersion();
        }
        DataSourceDef def = build(id, req, passwordCipher, keyVersion);
        int version = dataSourceDao.versionOf(id);
        if (dataSourceDao.update(def, version) == 0) {
            throw new DbSyncException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "数据源", id);
        }
        registry.evict(id);
        log.info("数据源已更新 id={} by={}", id, operator);
        return DataSourceView.of(dataSourceDao.findById(id).orElse(def));
    }

    /**
     * 删除数据源（软删除 + 引用校验）。
     *
     * @param id       数据源 ID
     * @param operator 操作人
     */
    public void delete(long id, String operator) {
        loadOrThrow(id);
        int refs = dataSourceDao.countTaskReferences(id);
        if (refs > 0) {
            throw new DbSyncException(ErrorCode.DATASOURCE_IN_USE, id)
                    .with("datasourceId", Long.valueOf(id))
                    .with("referencedTasks", Integer.valueOf(refs));
        }
        dataSourceDao.softDelete(id);
        registry.evict(id);
        log.info("数据源已删除（软删） id={} by={}", id, operator);
    }

    /**
     * 列表。
     *
     * @return 视图列表
     */
    public List<DataSourceView> list() {
        List<DataSourceView> views = new ArrayList<>();
        for (DataSourceDef def : dataSourceDao.findAll()) {
            views.add(DataSourceView.of(def));
        }
        return views;
    }

    /**
     * 详情。
     *
     * @param id 数据源 ID
     * @return 视图
     */
    public DataSourceView get(long id) {
        return DataSourceView.of(loadOrThrow(id));
    }

    /**
     * 对已保存的数据源做连接测试。
     *
     * @param id 数据源 ID
     * @return 测试结果
     */
    public ConnectionTestView test(long id) {
        loadOrThrow(id);
        ConnectionTestResult result = registry.handle(id).test();
        dataSourceDao.updateHealth(id, result.isSuccess() ? "UP" : "DOWN", result.getMessage());
        return ConnectionTestView.of(result);
    }

    /**
     * 对未保存的配置做连接测试（新建前先试）。
     *
     * @param req 请求
     * @return 测试结果
     */
    public ConnectionTestView test(DataSourceRequest req) {
        validate(req, req.password() != null && !req.password().isEmpty());
        DataSourceDef def = build(-1L, req, "tmp", (short) 0);
        return ConnectionTestView.of(registry.test(def));
    }

    /**
     * 表清单（含估算行数，供映射生成页选择）。
     *
     * @param id     数据源 ID
     * @param schema schema（可空 → 用数据源默认 schema）
     * @return 表清单
     */
    public List<TableInfoView> listTables(long id, String schema) {
        DataSourceDef def = loadOrThrow(id);
        String effectiveSchema = schema == null || schema.isBlank()
                ? def.getEndpoint().getSchema() : schema.trim();
        List<TableInfoView> tables = new ArrayList<>();
        // 注意：handle 由注册表缓存并共享，不能 try-with-resources 关闭（close 会销毁连接池）
        DataSourceHandle handle = registry.handle(id);
        try {
            Connection conn = handle.connection();
            List<String> names = handle.dialect().metadata().listTables(conn, effectiveSchema);
            for (String name : names) {
                tables.add(TableInfoView.brief(effectiveSchema, name));
            }
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.PROBE_FAILED, e, "读取表清单失败：" + e.getMessage());
        }
        return tables;
    }

    /**
     * 单表元数据。
     *
     * @param id     数据源 ID
     * @param schema schema（可空）
     * @param table  表名
     * @return 表元数据视图
     */
    public TableInfoView tableMeta(long id, String schema, String table) {
        DataSourceDef def = loadOrThrow(id);
        String effectiveSchema = schema == null || schema.isBlank()
                ? def.getEndpoint().getSchema() : schema.trim();
        try {
            DataSourceHandle handle = registry.handle(id);
            TableMeta meta = handle.dialect().metadata()
                    .loadTable(handle.connection(), effectiveSchema, table);
            return TableInfoView.of(meta);
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.PROBE_FAILED, e, "读取表元数据失败：" + e.getMessage());
        }
    }

    /**
     * 读取数据源定义（供其它服务复用）。
     *
     * @param id 数据源 ID
     * @return 定义
     */
    public DataSourceDef loadOrThrow(long id) {
        return dataSourceDao.findById(id).orElseThrow(
                () -> new DbSyncException(ErrorCode.DATASOURCE_NOT_FOUND, id));
    }

    /**
     * 任务引用数（删除前提示用）。
     *
     * @param id 数据源 ID
     * @return 引用任务数
     */
    public int countReferences(long id) {
        return dataSourceDao.countTaskReferences(id);
    }

    private void validate(DataSourceRequest req, boolean requirePassword) {
        if (req == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        parseDbType(req.dbType());
        parseEnv(req.env());
        parseCase(req.identifierCase());
        if (requirePassword && (req.password() == null || req.password().isEmpty())) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "新建数据源必须提供口令")
                    .with("field", "password");
        }
        if (req.poolMaxSize() != null && req.poolMinIdle() != null
                && req.poolMinIdle() > req.poolMaxSize()) {
            throw new DbSyncException(ErrorCode.DATASOURCE_CONFIG_INVALID, "poolMinIdle 不能大于 poolMaxSize");
        }
    }

    private DataSourceDef build(long id, DataSourceRequest req, String passwordCipher,
                                short keyVersion) {
        Map<String, String> params = new LinkedHashMap<>();
        if (req.params() != null) {
            params.putAll(req.params());
        }
        JdbcEndpoint endpoint = new JdbcEndpoint(req.host(), req.port(), req.database(),
                req.schema(), params);
        return DataSourceDef.builder(id, req.name())
                .dbType(parseDbType(req.dbType()))
                .env(parseEnv(req.env()))
                .endpoint(endpoint)
                .username(req.username())
                .passwordCipher(passwordCipher)
                .keyVersion(keyVersion)
                .identifierCase(parseCase(req.identifierCase()))
                .poolMaxSize(req.poolMaxSize() == null ? DEFAULT_POOL_MAX
                        : req.poolMaxSize().intValue())
                .poolMinIdle(req.poolMinIdle() == null ? DEFAULT_POOL_MIN_IDLE
                        : req.poolMinIdle().intValue())
                .fetchSize(req.fetchSize() == null ? DEFAULT_FETCH_SIZE
                        : req.fetchSize().intValue())
                .healthStatus("UNKNOWN")
                .build();
    }

    private static DbType parseDbType(String value) {
        if (value == null || value.isBlank()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "dbType 不能为空");
        }
        try {
            return DbType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "不支持的 dbType：" + value);
        }
    }

    private static EnvType parseEnv(String value) {
        if (value == null || value.isBlank()) {
            return EnvType.DEV;
        }
        try {
            return EnvType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "不支持的 env：" + value);
        }
    }

    private static IdentifierCase parseCase(String value) {
        if (value == null || value.isBlank()) {
            return IdentifierCase.PRESERVE;
        }
        try {
            return IdentifierCase.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "不支持的 identifierCase：" + value);
        }
    }
}
