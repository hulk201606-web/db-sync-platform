package com.dbsync.metadata.dao;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.EnvType;
import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.JdbcEndpoint;
import com.dbsync.common.util.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 数据源 DAO。
 *
 * <p>密码只以 {@code password_cipher} 形态进出本表，<b>永不出库明文</b>（架构 §12.1）。
 */
@Repository
public class DataSourceDao extends AbstractMetaDao {

    private final RowMapper<DataSourceDef> mapper = new RowMapper<DataSourceDef>() {
        @Override
        public DataSourceDef mapRow(ResultSet rs, int rowNum) throws SQLException {
            Map<String, String> params = parseParams(rs.getString("jdbc_params"));
            JdbcEndpoint endpoint = new JdbcEndpoint(rs.getString("host"), rs.getInt("port"),
                    rs.getString("database_name"), rs.getString("schema_name"), params);
            return DataSourceDef.builder(rs.getLong("id"), rs.getString("name"))
                    .dbType(DbType.valueOf(rs.getString("db_type")))
                    .env(EnvType.valueOf(rs.getString("env")))
                    .endpoint(endpoint)
                    .username(rs.getString("username"))
                    .passwordCipher(rs.getString("password_cipher"))
                    .keyVersion(rs.getShort("key_version"))
                    .identifierCase(IdentifierCase.valueOf(rs.getString("identifier_case")))
                    .poolMaxSize(rs.getInt("pool_max_size"))
                    .poolMinIdle(rs.getInt("pool_min_idle"))
                    .fetchSize(rs.getInt("fetch_size"))
                    .healthStatus(rs.getString("health_status"))
                    .build();
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public DataSourceDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 新增数据源。
     *
     * @param def    数据源定义
     * @param by     创建人
     * @return 影响行数
     */
    public int insert(DataSourceDef def, String by) {
        Instant now = now();
        return jdbc.update("INSERT INTO ds_datasource (id,name,db_type,env,host,port,database_name,"
                        + "schema_name,username,password_cipher,key_version,jdbc_params,identifier_case,"
                        + "pool_max_size,pool_min_idle,conn_timeout_ms,fetch_size,health_status,version,"
                        + "created_by,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?)",
                def.getId(), def.getName(), def.getDbType().name(), def.getEnv().name(),
                def.getEndpoint().getHost(), def.getEndpoint().getPort(),
                def.getEndpoint().getDatabase(), def.getEndpoint().getSchema(), def.getUsername(),
                def.getPasswordCipher(), def.getKeyVersion(), paramsToJson(def.getEndpoint()),
                def.getIdentifierCase().name(), def.getPoolMaxSize(), def.getPoolMinIdle(), 10000,
                def.getFetchSize(), def.getHealthStatus(), by, toTimestamp(now), toTimestamp(now));
    }

    /**
     * 更新数据源（乐观锁）。
     *
     * @param def     数据源定义
     * @param version 期望版本
     * @return 影响行数（0 表示版本冲突）
     */
    public int update(DataSourceDef def, int version) {
        return jdbc.update("UPDATE ds_datasource SET name=?,db_type=?,env=?,host=?,port=?,"
                        + "database_name=?,schema_name=?,username=?,password_cipher=?,key_version=?,"
                        + "jdbc_params=?,identifier_case=?,pool_max_size=?,pool_min_idle=?,fetch_size=?,"
                        + "version=version+1,updated_at=? WHERE id=? AND version=?",
                def.getName(), def.getDbType().name(), def.getEnv().name(),
                def.getEndpoint().getHost(), def.getEndpoint().getPort(),
                def.getEndpoint().getDatabase(), def.getEndpoint().getSchema(), def.getUsername(),
                def.getPasswordCipher(), def.getKeyVersion(), paramsToJson(def.getEndpoint()),
                def.getIdentifierCase().name(), def.getPoolMaxSize(), def.getPoolMinIdle(),
                def.getFetchSize(), toTimestamp(now()), def.getId(), version);
    }

    /**
     * 读取乐观锁版本号。
     *
     * <p>// ADDED: 04 文档要求数据源编辑走乐观锁，但 {@code DataSourceDef} 未携带
     * {@code version} 字段（模型保持"业务字段"纯净），因此单独提供本方法。
     *
     * @param id 数据源 ID
     * @return 版本号；不存在返回 -1
     */
    public int versionOf(long id) {
        List<Integer> list = jdbc.queryForList(
                "SELECT version FROM ds_datasource WHERE id=? AND deleted_at IS NULL",
                Integer.class, id);
        return list.isEmpty() || list.get(0) == null ? -1 : list.get(0).intValue();
    }

    /**
     * 按 ID 查询（排除软删除）。
     *
     * @param id 数据源 ID
     * @return 数据源
     */
    public Optional<DataSourceDef> findById(long id) {
        List<DataSourceDef> list = jdbc.query(
                "SELECT * FROM ds_datasource WHERE id=? AND deleted_at IS NULL", mapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 按名称查询（唯一键）。
     *
     * @param name 名称
     * @return 数据源
     */
    public Optional<DataSourceDef> findByName(String name) {
        List<DataSourceDef> list = jdbc.query(
                "SELECT * FROM ds_datasource WHERE name=? AND deleted_at IS NULL", mapper, name);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 列表。
     *
     * @return 全部未删除数据源
     */
    public List<DataSourceDef> findAll() {
        return jdbc.query("SELECT * FROM ds_datasource WHERE deleted_at IS NULL ORDER BY id", mapper);
    }

    /**
     * 软删除。
     *
     * @param id 数据源 ID
     * @return 影响行数
     */
    public int softDelete(long id) {
        return jdbc.update("UPDATE ds_datasource SET deleted_at=?,updated_at=? WHERE id=?",
                toTimestamp(now()), toTimestamp(now()), id);
    }

    /**
     * 统计引用该数据源的任务数（删除前校验，违反抛 E2003）。
     *
     * @param dsId 数据源 ID
     * @return 引用数
     */
    public int countTaskReferences(long dsId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sync_task "
                + "WHERE deleted_at IS NULL AND (source_ds_id=? OR target_ds_id=?)", Integer.class,
                dsId, dsId);
        return n == null ? 0 : n;
    }

    /**
     * 更新健康状态（探活结果）。
     *
     * @param id     数据源 ID
     * @param status UP/DOWN/SLOW/UNKNOWN
     * @param error  错误信息（可 null）
     * @return 影响行数
     */
    public int updateHealth(long id, String status, String error) {
        return jdbc.update("UPDATE ds_datasource SET health_status=?,last_probe_at=?,"
                + "last_probe_error=?,updated_at=? WHERE id=?",
                status, toTimestamp(now()), error, toTimestamp(now()), id);
    }

    /**
     * 参数串 → JSON。
     *
     * @param endpoint 端点
     * @return JSON 或 null
     */
    private String paramsToJson(JdbcEndpoint endpoint) {
        return endpoint.getParams().isEmpty() ? null : JsonUtil.toJson(endpoint.getParams());
    }

    /**
     * 参数 JSON → map。
     *
     * @param json JSON
     * @return 参数 map
     */
    static Map<String, String> parseParams(String json) {
        Map<String, String> params = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return params;
        }
        for (Map.Entry<String, Object> e : JsonUtil.parseObject(json).entrySet()) {
            params.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return params;
    }
}
