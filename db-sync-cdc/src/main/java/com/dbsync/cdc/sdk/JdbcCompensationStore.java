package com.dbsync.cdc.sdk;

import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.IndexMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.MappingOptions;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.dialect.Dialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 补偿表的 JDBC 实现：落<b>业务侧数据库</b>的 {@code sync_sdk_outbox}。
 *
 * <p><b>为什么不用 Spring 的 JdbcTemplate</b>：SDK 是给业务方手工 {@code new} 的库，
 * 不能假设业务应用的容器里有 Spring（更不能假设它扫描我们的包）。因此这里只用
 * {@link DataSource} + 原生 JDBC + 预处理语句，零框架依赖。
 *
 * <p><b>建表方式与方言兼容性</b>（任务要求写清）：
 * <ul>
 *   <li>建表语句<b>不手写</b>，而是构造一张 {@link TableMeta} 交给
 *       {@link Dialect#createTableDdl}、索引交给 {@link Dialect#createIndexDdl} 生成——
 *       这样 "反引号 vs 双引号 / LONGTEXT vs CLOB / DATETIME(3) vs TIMESTAMP(6)"
 *       这些差异全部由方言层承担，SDK 里不出现任何库专属 SQL 字面量
 *       （硬约束：{@code db-sync-dialect} 之外不得有库专属 SQL）；</li>
 *   <li>列类型只用跨方言都能映射的名字：{@code BIGINT / INT / VARCHAR / TEXT / TIMESTAMP}。
 *       映射结果举例（{@code Dialect.mapType} 实测）：
 *       MySQL {@code BIGINT/VARCHAR(n)/LONGTEXT/DATETIME(3)}；
 *       PostgreSQL {@code BIGINT/VARCHAR(n)/TEXT/TIMESTAMP(3)}；
 *       Oracle {@code NUMBER(19)/VARCHAR2(n)/CLOB/TIMESTAMP(6)}；
 *       DB2 {@code BIGINT/VARCHAR(n)/CLOB(1M)/TIMESTAMP(3)}，DM 同族；</li>
 *   <li>{@link #ensureTable()} 可重复调用：建表用 {@code CREATE TABLE [IF NOT EXISTS]}，
 *       建索引失败（"已存在"）只记 debug —— 运维不需要为了升级 SDK 先手工改库。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：无共享可变状态（{@link SnowflakeId} 自身线程安全，
 * {@code schemaReady} 只用于跳过重复 DDL）。
 */
public final class JdbcCompensationStore implements CompensationStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcCompensationStore.class);

    /** 补偿表名（任务书的固定契约）。 */
    public static final String TABLE_NAME = "sync_sdk_outbox";

    /** 补偿表索引名（重放扫描用：{@code task_id + status + next_retry_at}）。 */
    public static final String INDEX_NAME = "idx_sdk_outbox_scan";

    /** 默认可重放条数上限。 */
    public static final int DEFAULT_LIMIT = 100;

    private final DataSource dataSource;
    private final Dialect dialect;
    private final String schema;
    private final Clock clock;
    private final SnowflakeId idGenerator;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    /**
     * 构造（不指定 schema，用当前连接的默认 schema）。
     *
     * @param dataSource 业务库数据源（<b>不是</b>平台元数据库）
     * @param dialect    业务库方言（决定建表语句的引号/类型写法）
     */
    public JdbcCompensationStore(DataSource dataSource, Dialect dialect) {
        this(dataSource, dialect, null, Clock.systemUTC());
    }

    /**
     * 构造（完整）。
     *
     * @param dataSource 业务库数据源
     * @param dialect    业务库方言
     * @param schema     表所在 schema（可 null/空：使用连接默认 schema）
     * @param clock      时钟（重放退避判定用，测试可注入）
     */
    public JdbcCompensationStore(DataSource dataSource, Dialect dialect, String schema,
                                 Clock clock) {
        if (dataSource == null) {
            throw new IllegalArgumentException("补偿表数据源不能为空");
        }
        if (dialect == null) {
            throw new IllegalArgumentException("补偿表方言不能为空");
        }
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.schema = schema == null || schema.isBlank() ? null : schema;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.idGenerator = new SnowflakeId(SnowflakeId.defaultWorkerId());
    }

    /**
     * 补偿表的元数据（建表语句的唯一来源，同时供运维核对实际列）。
     *
     * <p>字段与任务书一致：{@code id / task_id / table_name / op / pk_json / payload /
     * status / retry / last_error / created_at / next_retry_at / replayed_at}。
     *
     * @return 表元数据
     */
    public TableMeta outboxMeta() {
        List<ColumnMeta> columns = List.of(
                ColumnMeta.builder("id", 1, "BIGINT").nullable(false).build(),
                ColumnMeta.builder("task_id", 2, "BIGINT").nullable(false).build(),
                ColumnMeta.builder("table_name", 3, "VARCHAR").size(128).nullable(false).build(),
                ColumnMeta.builder("op", 4, "VARCHAR").size(1).nullable(false).build(),
                ColumnMeta.builder("pk_json", 5, "VARCHAR").size(2000).nullable(false).build(),
                ColumnMeta.builder("payload", 6, "TEXT").lob(true).nullable(true).build(),
                ColumnMeta.builder("status", 7, "VARCHAR").size(16).nullable(false).build(),
                ColumnMeta.builder("retry", 8, "INT").nullable(false).build(),
                ColumnMeta.builder("last_error", 9, "VARCHAR").size(1000).nullable(true).build(),
                ColumnMeta.builder("created_at", 10, "TIMESTAMP").nullable(false).build(),
                ColumnMeta.builder("next_retry_at", 11, "TIMESTAMP").nullable(true).build(),
                ColumnMeta.builder("replayed_at", 12, "TIMESTAMP").nullable(true).build());
        return new TableMeta(schema, TABLE_NAME, columns,
                new PrimaryKeyMeta("pk_sdk_outbox", List.of("id")),
                List.of(new IndexMeta(INDEX_NAME, List.of("task_id", "status", "next_retry_at"),
                        false)),
                0L, "SDK 双写失败补偿表（业务侧库）");
    }

    /**
     * 建表 + 建索引（幂等，可重复调用）。
     *
     * <p>建索引失败只记 debug：索引已存在时数据库会报错，而"索引在不在"不影响正确性，
     * 只影响重放扫描性能，因此不阻断 SDK 启动。
     */
    public void ensureTable() {
        if (schemaReady.get()) {
            return;
        }
        TableMeta meta = outboxMeta();
        MappingOptions options = new MappingOptions(
                com.dbsync.common.options.TypeMappingOptions.defaults(),
                com.dbsync.common.enums.IdentifierCase.PRESERVE,
                false, true, true, false, schema);
        List<String> statements = new ArrayList<>();
        statements.add(dialect.createTableDdl(meta, options));
        statements.addAll(dialect.createIndexDdl(meta, options));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String ddl : statements) {
                try {
                    statement.execute(ddl);
                } catch (SQLException e) {
                    // 已存在（或方言不支持该索引语法）都不影响补偿语义
                    log.debug("补偿表 DDL 跳过：{} 原因={}", ddl, e.getMessage());
                }
            }
            schemaReady.set(true);
            log.info("补偿表已就绪 table={} dialect={}", TABLE_NAME, dialect.type());
        } catch (SQLException e) {
            throw new IllegalStateException("补偿表建表失败：" + TABLE_NAME + " " + e.getMessage(), e);
        }
    }

    @Override
    public long record(CompensationRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("补偿记录不能为空");
        }
        ensureTable();
        long id = record.getId() > 0L ? record.getId() : idGenerator.nextId();
        Instant created = record.getCreatedAt() == null ? clock.instant() : record.getCreatedAt();
        Instant next = record.getNextRetryAt() == null ? created : record.getNextRetryAt();
        String sql = "INSERT INTO " + qualifiedTable()
                + " (id, task_id, table_name, op, pk_json, payload, status, retry, last_error,"
                + " created_at, next_retry_at, replayed_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, record.getTaskId());
            ps.setString(3, record.getTableName());
            ps.setString(4, record.getOp());
            ps.setString(5, record.getPkJson());
            ps.setString(6, record.getPayload());
            ps.setString(7, record.getStatus().name());
            ps.setInt(8, record.getRetry());
            ps.setString(9, CompensationRecord.truncate(record.getLastError()));
            ps.setTimestamp(10, Timestamp.from(created));
            ps.setTimestamp(11, Timestamp.from(next));
            if (record.getReplayedAt() == null) {
                ps.setNull(12, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(12, Timestamp.from(record.getReplayedAt()));
            }
            ps.executeUpdate();
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException("补偿记录写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<CompensationRecord> listPending(long taskId, int limit) {
        ensureTable();
        int max = limit <= 0 ? DEFAULT_LIMIT : limit;
        String base = "SELECT id, task_id, table_name, op, pk_json, payload, status, retry,"
                + " last_error, created_at, next_retry_at, replayed_at FROM " + qualifiedTable()
                + " WHERE task_id = ? AND status = ?"
                + " AND (next_retry_at IS NULL OR next_retry_at <= ?)"
                + " ORDER BY next_retry_at, created_at";
        String sql = dialect.buildPageSql(base, 0L, max);
        List<CompensationRecord> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, taskId);
            ps.setString(2, CompensationStatus.PENDING.name());
            ps.setTimestamp(3, Timestamp.from(clock.instant()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("补偿记录查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean markReplayed(long id) {
        ensureTable();
        String sql = "UPDATE " + qualifiedTable()
                + " SET status = ?, replayed_at = ? WHERE id = ? AND status = ?";
        return update(sql, CompensationStatus.REPLAYED.name(), Timestamp.from(clock.instant()),
                Long.valueOf(id), CompensationStatus.PENDING.name());
    }

    @Override
    public boolean markDiscarded(long id, String reason) {
        ensureTable();
        String sql = "UPDATE " + qualifiedTable()
                + " SET status = ?, last_error = ? WHERE id = ? AND status = ?";
        return update(sql, CompensationStatus.DISCARDED.name(),
                CompensationRecord.truncate(reason), Long.valueOf(id),
                CompensationStatus.PENDING.name());
    }

    @Override
    public boolean markRetry(long id, String lastError, Instant nextRetryAt) {
        ensureTable();
        String sql = "UPDATE " + qualifiedTable()
                + " SET retry = retry + 1, last_error = ?, next_retry_at = ?"
                + " WHERE id = ? AND status = ?";
        return update(sql, CompensationRecord.truncate(lastError),
                Timestamp.from(nextRetryAt == null ? clock.instant() : nextRetryAt),
                Long.valueOf(id), CompensationStatus.PENDING.name());
    }

    /**
     * 按状态统计条数（运维视图/测试断言用）。
     *
     * @param taskId 任务 ID
     * @param status 状态
     * @return 条数
     */
    public long count(long taskId, CompensationStatus status) {
        ensureTable();
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable()
                + " WHERE task_id = ? AND status = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, taskId);
            ps.setString(2, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("补偿记录统计失败：" + e.getMessage(), e);
        }
    }

    /**
     * 按 ID 读取一条（运维排查用）。
     *
     * @param id 记录 ID
     * @return 记录；不存在返回 {@code null}
     */
    public CompensationRecord findById(long id) {
        ensureTable();
        String sql = "SELECT id, task_id, table_name, op, pk_json, payload, status, retry,"
                + " last_error, created_at, next_retry_at, replayed_at FROM " + qualifiedTable()
                + " WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("补偿记录读取失败：" + e.getMessage(), e);
        }
    }

    /**
     * 加引号的表名（含 schema）。
     *
     * @return 形如 {@code `db`.`sync_sdk_outbox`}
     */
    private String qualifiedTable() {
        return schema == null ? dialect.quote(TABLE_NAME)
                : dialect.quote(schema) + "." + dialect.quote(TABLE_NAME);
    }

    /**
     * 执行 UPDATE。
     *
     * @param sql    语句
     * @param params 参数（String / Timestamp / Long）
     * @return true 表示影响行数 &gt; 0
     */
    private boolean update(String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object param = params[i];
                if (param instanceof Timestamp) {
                    ps.setTimestamp(i + 1, (Timestamp) param);
                } else if (param instanceof Long) {
                    ps.setLong(i + 1, ((Long) param).longValue());
                } else if (param instanceof Integer) {
                    ps.setInt(i + 1, ((Integer) param).intValue());
                } else {
                    ps.setString(i + 1, param == null ? null : String.valueOf(param));
                }
            }
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("补偿记录更新失败：" + e.getMessage(), e);
        }
    }

    /**
     * 结果集 → 记录。
     *
     * @param rs 结果集（当前行有效）
     * @return 记录
     * @throws SQLException 读取失败
     */
    private static CompensationRecord map(ResultSet rs) throws SQLException {
        return new CompensationRecord(rs.getLong("id"), rs.getLong("task_id"),
                rs.getString("table_name"), rs.getString("op"), rs.getString("pk_json"),
                rs.getString("payload"), CompensationStatus.parse(rs.getString("status")),
                rs.getInt("retry"), rs.getString("last_error"),
                instantOf(rs.getTimestamp("created_at")), instantOf(rs.getTimestamp("next_retry_at")),
                instantOf(rs.getTimestamp("replayed_at")));
    }

    /**
     * {@link Timestamp} → {@link Instant}（null 安全）。
     *
     * @param timestamp 时间戳
     * @return 时间；null 输入返回 null
     */
    private static Instant instantOf(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
