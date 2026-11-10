package com.dbsync.migration;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PrecheckReport;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.util.IdentifierValidator;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.dialect.Dialect;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 迁移预检（PRD MIG-06）：8 类阻断项 + N 类警告。
 *
 * <p><b>为什么预检必须"阻断而非警告"</b>：这 8 类问题一旦开始迁移，
 * 表现是"跑到第 4 小时才失败"或"跑完了但数据错"——两者都比"启动前 30 秒报错"贵几个数量级。
 * 因此除"无法判定"的情况（例如目标库不暴露剩余空间）一律进 {@code blocking}。
 *
 * <p><b>8 类阻断项与判定方式</b>：
 * <table border="1">
 *   <caption>阻断项</caption>
 *   <tr><th>#</th><th>阻断项</th><th>判定</th></tr>
 *   <tr><td>1</td><td>目标表缺失</td><td>{@code MetadataReader.loadTable} 返回空</td></tr>
 *   <tr><td>2</td><td>无 CREATE 权限</td><td>探针建表失败，错误归类为权限</td></tr>
 *   <tr><td>3</td><td>目标空间不足</td><td>探针建表失败，错误归类为空间</td></tr>
 *   <tr><td>4</td><td>目标无主键/唯一约束</td><td>非 INSERT 模式下 upsert 无法幂等</td></tr>
 *   <tr><td>5</td><td>标识符超长</td><td>{@code Capabilities.maxIdentifierLength}</td></tr>
 *   <tr><td>6</td><td>类型不支持</td><td>{@code Dialect.mapType} 抛错或返回空</td></tr>
 *   <tr><td>7</td><td>主键列为 LOB</td><td>LOB 不能做区间切分，也不能做 upsert 键</td></tr>
 *   <tr><td>8</td><td>循环外键</td><td>目标库 FK 图存在环（建表顺序无解）</td></tr>
 * </table>
 *
 * <p><b>已知局限（诚实声明，不藏在注释里）</b>：
 * 第 2/3 项用的是"在目标库建一张探针表再删掉"的通用手法，
 * 它能抓住"整个表空间满"和"账号无 CREATE"，但抓不到"单张表的配额已满"——
 * 后者需要方言级查询（Oracle dba_free_space / MySQL information_schema），
 * 属于方言能力扩展，本阶段以警告形式提示。
 */
public class MigrationPrecheck {

    private static final Logger log = LoggerFactory.getLogger(MigrationPrecheck.class);

    /** 探针表名前缀（带随机后缀，避免并发预检互相踩）。 */
    private static final String PROBE_PREFIX = "dbsync_precheck_";

    private final SyncTaskDao syncTaskDao;
    private final TableMappingDao tableMappingDao;
    private final DataSourceRegistry registry;

    /**
     * 构造。
     *
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param registry        数据源注册表
     */
    public MigrationPrecheck(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao,
                             DataSourceRegistry registry) {
        this.syncTaskDao = syncTaskDao;
        this.tableMappingDao = tableMappingDao;
        this.registry = registry;
    }

    /**
     * 执行预检。
     *
     * @param taskId 任务 ID
     * @return 预检报告
     */
    public PrecheckReport run(long taskId) {
        SyncTask task = syncTaskDao.findById(taskId)
                .orElseThrow(() -> new DbSyncException(ErrorCode.TASK_NOT_FOUND,
                        "任务不存在：" + taskId));
        List<TableMapping> mappings = tableMappingDao.findEnabledByTask(taskId);
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (mappings.isEmpty()) {
            blocking.add("未配置任何启用的表映射");
            return new PrecheckReport(taskId, blocking, warnings);
        }

        DataSourceHandle source = registry.handle(task.getSourceDsId());
        DataSourceHandle target = registry.handle(task.getTargetDsId());

        // 第 2/3 项：目标库可写探针（权限 + 空间）
        probeTargetWritability(target, blocking, warnings);

        for (TableMapping mapping : mappings) {
            TableMeta sourceTable = loadQuietly(source, mapping.getSourceSchema(),
                    mapping.getSourceTable(), warnings, "源");
            TableMeta targetTable = loadQuietly(target, mapping.getTargetSchema(),
                    mapping.getTargetTable(), warnings, "目标");

            checkTargetTableExists(mapping, targetTable, blocking);
            checkUniqueKey(mapping, targetTable, blocking);
            checkIdentifierLength(mapping, target.dialect(), blocking);
            checkPkNotLob(mapping, sourceTable, blocking);
            checkTypeSupported(mapping, sourceTable, target.dialect(), blocking);
        }

        // 第 8 项：目标库 FK 图是否有环（同一连接内完成，避免为检查多借一次连接）
        try {
            target.withConnection(connection -> {
                checkForeignKeyCycle(connection, mappings, blocking);
                return Boolean.TRUE;
            });
        } catch (RuntimeException e) {
            warnings.add("[外键检查跳过] 无法读取目标库外键元数据：" + e.getMessage());
        }

        log.info("预检完成 task={} blocking={} warnings={}", taskId, blocking.size(),
                warnings.size());
        return new PrecheckReport(taskId, blocking, warnings);
    }

    /**
     * 第 1 项：目标表缺失。
     *
     * @param mapping     表映射
     * @param targetTable 目标表元数据（可 null）
     * @param blocking    阻断项收集器
     */
    void checkTargetTableExists(TableMapping mapping, TableMeta targetTable,
                                List<String> blocking) {
        if (targetTable == null) {
            blocking.add("[目标表缺失] " + mapping.getTargetTable()
                    + "：目标库不存在该表，且本阶段不自动建表");
        }
    }

    /**
     * 第 4 项：目标表无主键或唯一约束。
     *
     * <p>为什么阻断：本平台的写入是 upsert（幂等重放的前提）。
     * 没有主键/唯一约束时，"重跑一次"会变成"插两遍"，
     * 这在迁移场景里是最容易造成生产事故的一条。
     *
     * @param mapping     表映射
     * @param targetTable 目标表元数据（可 null）
     * @param blocking    阻断项收集器
     */
    void checkUniqueKey(TableMapping mapping, TableMeta targetTable, List<String> blocking) {
        if (targetTable == null) {
            return;
        }
        if (targetTable.hasPrimaryKey()) {
            return;
        }
        blocking.add("[无主键/唯一约束] " + mapping.getTargetTable()
                + "：目标表既无主键也无唯一索引，upsert 无法幂等，重跑会重复插入");
    }

    /**
     * 第 5 项：标识符超长。
     *
     * @param mapping  表映射
     * @param dialect  目标方言
     * @param blocking 阻断项收集器
     */
    void checkIdentifierLength(TableMapping mapping, Dialect dialect, List<String> blocking) {
        int maxLength = dialect.capabilities() == null
                ? 128 : dialect.capabilities().maxIdentifierLength();
        checkOneIdentifier(mapping.getTargetTable(), maxLength, dialect.type(), blocking);
        if (mapping.getTargetSchema() != null && !mapping.getTargetSchema().isBlank()) {
            checkOneIdentifier(mapping.getTargetSchema(), maxLength, dialect.type(), blocking);
        }
    }

    /**
     * 单个标识符长度校验（超长直接给可执行建议，而不是只报错）。
     *
     * @param identifier 标识符
     * @param maxLength  上限
     * @param dbType     目标库类型
     * @param blocking   阻断项收集器
     */
    private void checkOneIdentifier(String identifier, int maxLength, DbType dbType,
                                    List<String> blocking) {
        if (identifier == null || identifier.length() <= maxLength) {
            return;
        }
        String suggestion = maxLength >= 30
                ? "请重命名为 ≤" + maxLength + " 字符的别名"
                : "请重命名为 ≤" + maxLength + " 字符的别名（该库上限极低，注意兼容）";
        blocking.add("[标识符超长] " + identifier + "（" + identifier.length() + " > "
                + maxLength + "，" + dbType + "）：" + suggestion);
    }

    /**
     * 第 7 项：主键列为 LOB。
     *
     * @param mapping     表映射
     * @param sourceTable 源表元数据（可 null）
     * @param blocking    阻断项收集器
     */
    void checkPkNotLob(TableMapping mapping, TableMeta sourceTable, List<String> blocking) {
        if (sourceTable == null || !sourceTable.hasPrimaryKey()) {
            return;
        }
        for (ColumnMeta pk : sourceTable.primaryKeyColumns()) {
            if (pk.isLob()) {
                blocking.add("[主键为 LOB] " + mapping.getSourceTable() + "." + pk.getName()
                        + "：LOB 列不能做区间切分，也不能做 upsert 键");
            }
        }
    }

    /**
     * 第 6 项：类型不支持（目标方言无法映射源列类型）。
     *
     * @param mapping     表映射
     * @param sourceTable 源表元数据（可 null）
     * @param dialect     目标方言
     * @param blocking    阻断项收集器
     */
    void checkTypeSupported(TableMapping mapping, TableMeta sourceTable, Dialect dialect,
                            List<String> blocking) {
        if (sourceTable == null) {
            return;
        }
        for (ColumnMeta column : sourceTable.getColumns()) {
            try {
                String mapped = dialect.mapType(column, null);
                if (mapped == null || mapped.isBlank()) {
                    blocking.add("[类型不支持] " + mapping.getSourceTable() + "." + column.getName()
                            + " (" + column.getTypeName() + ") 无法映射到 " + dialect.type());
                }
            } catch (RuntimeException e) {
                blocking.add("[类型不支持] " + mapping.getSourceTable() + "." + column.getName()
                        + " (" + column.getTypeName() + ")： " + e.getMessage());
            }
        }
    }

    /**
     * 第 8 项：目标库外键图存在环。
     *
     * <p>判定方式：用 {@code DatabaseMetaData.getImportedKeys} 读出目标表的 FK 边，
     * 在内存图里跑 DFS 找环。存在环意味着"按拓扑序建表"无解，
     * 迁移脚本/DDL 执行会随机失败。
     *
     * @param connection 目标库连接
     * @param mappings   表映射
     * @param blocking   阻断项收集器
     */
    void checkForeignKeyCycle(Connection connection, List<TableMapping> mappings,
                              List<String> blocking) {
        // 原始大小写用于查元数据（H2/PG 的小写表名、Oracle 的大写表名都要能查到），
        // 图里的键统一大写用于比对（不同库的大小写折叠规则不同，比对必须与库无关）
        Map<String, String> tableByUpper = new LinkedHashMap<>();
        for (TableMapping mapping : mappings) {
            if (mapping.getTargetTable() != null) {
                tableByUpper.put(mapping.getTargetTable().toUpperCase(Locale.ROOT),
                        mapping.getTargetTable());
            }
        }
        if (tableByUpper.isEmpty()) {
            return;
        }
        Map<String, Set<String>> graph;
        try {
            graph = loadForeignKeyGraph(connection, tableByUpper);
        } catch (SQLException | RuntimeException e) {
            // 读不到 FK 元数据时不能阻断（有些库/低权限账号看不到），降级为不判定
            log.debug("读取外键元数据失败，跳过循环外键检查：{}", e.getMessage());
            return;
        }
        List<String> cycle = findCycle(graph);
        if (!cycle.isEmpty()) {
            blocking.add("[循环外键] " + String.join(" → ", cycle)
                    + "：目标库存在环形外键，建表顺序无解，请先手工去掉其中一条外键");
        }
    }

    /**
     * 读出目标表的 FK 依赖图（键与值均为大写表名）。
     *
     * @param connection  连接
     * @param tableByUpper 大写表名 → 原始表名
     * @return 图：表 → 它引用的表
     * @throws SQLException 读取失败
     */
    private Map<String, Set<String>> loadForeignKeyGraph(Connection connection,
                                                         Map<String, String> tableByUpper)
            throws SQLException {
        Map<String, Set<String>> graph = new HashMap<>();
        for (String upper : tableByUpper.keySet()) {
            graph.putIfAbsent(upper, new HashSet<>());
        }
        DatabaseMetaData metaData = connection.getMetaData();
        for (Map.Entry<String, String> entry : tableByUpper.entrySet()) {
            String upper = entry.getKey();
            try (ResultSet rs = metaData.getImportedKeys(connection.getCatalog(), null,
                    entry.getValue())) {
                while (rs.next()) {
                    String child = upper(rs.getString("FKTABLE_NAME"));
                    String parent = upper(rs.getString("PKTABLE_NAME"));
                    if (child == null || parent == null) {
                        continue;
                    }
                    if (!graph.containsKey(child) || !graph.containsKey(parent)) {
                        continue;
                    }
                    graph.computeIfAbsent(child, k -> new HashSet<>()).add(parent);
                }
            }
        }
        return graph;
    }

    /**
     * DFS 找环（三色标记；找到第一个环即返回其路径）。
     *
     * @param graph 依赖图
     * @return 环路径（空表示无环）
     */
    static List<String> findCycle(Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        List<String> path = new ArrayList<>();
        for (String node : graph.keySet()) {
            List<String> cycle = dfs(node, graph, visited, inStack, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    /**
     * DFS 单点递归。
     *
     * @param node    当前节点
     * @param graph   图
     * @param visited 已完全访问
     * @param inStack 当前递归栈
     * @param path    路径
     * @return 环路径（空表示无环）
     */
    private static List<String> dfs(String node, Map<String, Set<String>> graph, Set<String> visited,
                                    Set<String> inStack, List<String> path) {
        if (inStack.contains(node)) {
            int start = path.indexOf(node);
            List<String> cycle = new ArrayList<>(
                    path.subList(start < 0 ? 0 : start, path.size()));
            cycle.add(node);
            return cycle;
        }
        if (visited.contains(node)) {
            return List.of();
        }
        inStack.add(node);
        path.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            List<String> cycle = dfs(next, graph, visited, inStack, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        inStack.remove(node);
        visited.add(node);
        return List.of();
    }

    /**
     * 第 2/3 项：目标库可写探针。
     *
     * @param target   目标库句柄
     * @param blocking 阻断项收集器
     * @param warnings 警告项收集器
     */
    void probeTargetWritability(DataSourceHandle target, List<String> blocking,
                                List<String> warnings) {
        String probeTable = PROBE_PREFIX + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12);
        try {
            Optional<String> failure = target.withConnection(
                    connection -> probe(connection, target.dialect(), probeTable));
            failure.ifPresent(blocking::add);
        } catch (RuntimeException e) {
            warnings.add("[可写探针失败] 无法判定目标库 CREATE 权限与剩余空间：" + e.getMessage());
            return;
        }
        warnings.add("[空间提醒] 通用 JDBC 无法读取剩余表空间；如为大表迁移，请人工确认目标库容量");
    }

    /**
     * 建一张探针表再删掉（同时验证 CREATE 权限与"还有地方可写"）。
     *
     * @param connection 连接
     * @param dialect    目标方言
     * @param probeTable 探针表名
     * @return 阻断消息（空表示通过）
     */
    Optional<String> probe(Connection connection, Dialect dialect, String probeTable) {
        String quoted = dialect.quote(probeTable);
        String create = "CREATE TABLE " + quoted + " (id INT)";
        try (Statement statement = connection.createStatement()) {
            statement.execute(create);
            statement.execute("DROP TABLE " + quoted);
            return Optional.empty();
        } catch (SQLException e) {
            ProbeFailure kind = classifyProbeFailure(e);
            if (kind == ProbeFailure.PRIVILEGE) {
                return Optional.of("[无 CREATE 权限] 目标库拒绝建表探针：" + e.getMessage());
            }
            if (kind == ProbeFailure.SPACE) {
                return Optional.of("[目标空间不足] 目标库建表探针失败（空间）：" + e.getMessage());
            }
            return Optional.of("[目标库不可写] 建表探针失败：" + e.getMessage());
        }
    }

    /**
     * 探针失败归类。
     *
     * <p>归类依据是驱动返回的 SQLState + 主流厂商错误码片段（不引入方言依赖）：
     * <ul>
     *   <li>空间：{@code 53100}(PG disk full)、{@code 01653/01654/01688}(Oracle)、
     *       {@code 1114}(MySQL 表满)、{@code 90002}(H2)</li>
     *   <li>权限：{@code 42501}(PG)、{@code 01031/01950}(Oracle)、{@code 1142/1044}(MySQL)</li>
     * </ul>
     *
     * @param e 探针异常
     * @return 归类
     */
    static ProbeFailure classifyProbeFailure(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toUpperCase(Locale.ROOT);
        if ("53100".equals(state) || message.contains("NO SPACE LEFT") || message.contains("DISK FULL")
                || message.contains("UNABLE TO EXTEND") || message.contains("TABLESPACE")
                || message.contains("OUT OF SPACE") || message.contains("THE DATABASE OR TABLE IS FULL")) {
            return ProbeFailure.SPACE;
        }
        if ("42501".equals(state) || "42000".equals(state) || message.contains("PERMISSION")
                || message.contains("PRIVILEGE") || message.contains("ACCESS DENIED")
                || message.contains("NOT AUTHORIZED") || message.contains("INSUFFICIENT")) {
            return ProbeFailure.PRIVILEGE;
        }
        return ProbeFailure.OTHER;
    }

    /**
     * 探针失败归类结果。
     */
    enum ProbeFailure {
        /** 权限不足。 */
        PRIVILEGE,
        /** 空间不足。 */
        SPACE,
        /** 其他。 */
        OTHER
    }

    /**
     * 读表元数据（失败只记警告，不阻断——第 1 项会单独判定"目标表缺失"）。
     *
     * @param handle   数据源句柄
     * @param schema   schema
     * @param table    表名
     * @param warnings 警告项收集器
     * @param side     侧别（"源"/"目标"）
     * @return 表元数据，失败为 null
     */
    private TableMeta loadQuietly(DataSourceHandle handle, String schema, String table,
                                  List<String> warnings, String side) {
        try {
            return handle.withConnection(connection -> handle.dialect().metadata()
                    .loadTable(connection, schema, table));
        } catch (RuntimeException e) {
            warnings.add("[" + side + "表读取失败] " + table + "：" + e.getMessage());
            return null;
        }
    }

    /**
     * 大写入前表名截断（报告用）。
     *
     * @param value 原值
     * @return 大写值
     */
    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    /**
     * 校验单个标识符是否合法（供 API 层复用的门面）。
     *
     * @param identifier 标识符
     * @param maxLength  上限
     * @param dbType     库类型
     * @return 已校验标识符
     */
    public static String validateIdentifier(String identifier, int maxLength, DbType dbType) {
        return IdentifierValidator.validate(identifier, maxLength, dbType);
    }
}
