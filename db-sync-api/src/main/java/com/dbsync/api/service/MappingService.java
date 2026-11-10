package com.dbsync.api.service;

import com.dbsync.api.dto.mapping.ColumnMappingView;
import com.dbsync.api.dto.mapping.DdlDiffView;
import com.dbsync.api.dto.mapping.DdlView;
import com.dbsync.api.dto.mapping.GenerateMappingRequest;
import com.dbsync.api.dto.mapping.MappingDetailView;
import com.dbsync.api.dto.mapping.MappingView;
import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ColumnMapping;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.IndexMeta;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.MappingOptions;
import com.dbsync.common.options.TypeMappingOptions;
import com.dbsync.common.util.IdentifierValidator;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.dialect.Dialect;
import com.dbsync.metadata.dao.ColumnMappingDao;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 表/列映射服务（PRD MAP-01~04）。
 *
 * <p>职责：
 * <ol>
 *   <li><b>自动发现</b>：读源库表清单与元数据 → 逐列调用<b>目标方 dialect</b> 的
 *       {@link Dialect#mapType} 得到目标类型 → 生成 {@code CREATE TABLE} DDL；</li>
 *   <li><b>人工覆盖</b>：单列改类型，落 {@code override_by}（T43 验收 4）；</li>
 *   <li><b>DDL 预览</b>：不落库，直接按当前元数据+覆盖情况生成。</li>
 * </ol>
 *
 * <p><b>方言隔离（硬规则 3）</b>：本类不出现任何 DB 专有 SQL 字面量，
 * 建表语句全部由 {@link Dialect#createTableDdl} 产出。
 */
public class MappingService {

    private static final Logger log = LoggerFactory.getLogger(MappingService.class);

    /** R9 风险标签：Oracle 无界 NUMBER 映射为 DECIMAL(38,10)。 */
    public static final String RISK_ORACLE_NUMBER = "R9-NUMBER-38-10";

    private final SyncTaskDao syncTaskDao;
    private final DataSourceDao dataSourceDao;
    private final TableMappingDao tableMappingDao;
    private final ColumnMappingDao columnMappingDao;
    private final DataSourceRegistry registry;
    private final SnowflakeId idGenerator;

    /**
     * 构造。
     *
     * @param syncTaskDao     任务 DAO
     * @param dataSourceDao   数据源 DAO
     * @param tableMappingDao 表映射 DAO
     * @param columnMappingDao 列映射 DAO
     * @param registry        数据源注册表
     * @param idGenerator     雪花 ID
     */
    public MappingService(SyncTaskDao syncTaskDao, DataSourceDao dataSourceDao,
                          TableMappingDao tableMappingDao, ColumnMappingDao columnMappingDao,
                          DataSourceRegistry registry, SnowflakeId idGenerator) {
        this.syncTaskDao = syncTaskDao;
        this.dataSourceDao = dataSourceDao;
        this.tableMappingDao = tableMappingDao;
        this.columnMappingDao = columnMappingDao;
        this.registry = registry;
        this.idGenerator = idGenerator == null
                ? new SnowflakeId(SnowflakeId.defaultWorkerId()) : idGenerator;
    }

    /**
     * 自动生成映射 + DDL。
     *
     * @param taskId 任务 ID
     * @param req    请求（可 null → 全库发现）
     * @return 生成的表映射列表
     */
    public List<MappingView> generate(long taskId, GenerateMappingRequest req) {
        SyncTask task = loadTask(taskId);
        GenerateMappingRequest request = req == null
                ? new GenerateMappingRequest(null, null, null, null, null, null, null, null) : req;
        DataSourceDef srcDef = loadDataSource(task.getSourceDsId());
        DataSourceDef tgtDef = loadDataSource(task.getTargetDsId());
        String sourceSchema = blankToDefault(request.schema(), srcDef.getEndpoint().getSchema());
        String targetSchema = blankToDefault(request.targetSchema(),
                tgtDef.getEndpoint().getSchema());
        Dialect targetDialect = registry.handle(task.getTargetDsId()).dialect();
        MappingOptions options = new MappingOptions(TypeMappingOptions.defaults(),
                tgtDef.getIdentifierCase() == null ? IdentifierCase.PRESERVE
                        : tgtDef.getIdentifierCase(),
                Boolean.TRUE.equals(request.includeComments()),
                Boolean.TRUE.equals(request.includeIndexes()),
                Boolean.TRUE.equals(request.includeUniqueKeys()),
                Boolean.TRUE.equals(request.dropTableIfExists()),
                targetSchema);

        List<TableMapping> existing = tableMappingDao.findByTask(taskId);
        if (!existing.isEmpty() && !Boolean.TRUE.equals(request.overwrite())) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "任务已有表映射，如需重建请显式 overwrite=true")
                    .with("existingMappings", Integer.valueOf(existing.size()));
        }
        if (!existing.isEmpty()) {
            tableMappingDao.deleteByTask(taskId);
        }

        List<String> tables = resolveTables(task, sourceSchema, request.tables());
        List<MappingView> created = new ArrayList<>(tables.size());
        for (String table : tables) {
            TableMeta sourceMeta = loadTable(task, sourceSchema, table);
            TableMeta targetMeta = new TableMeta(targetSchema, table, sourceMeta.getColumns(),
                    sourceMeta.getPrimaryKey(), sourceMeta.getIndexes(),
                    sourceMeta.getEstimatedRows(), sourceMeta.getComment());
            String ddl = targetDialect.createTableDdl(targetMeta, options);
            List<String> indexDdl = targetDialect.createIndexDdl(targetMeta, options);
            if (!indexDdl.isEmpty()) {
                ddl = ddl + ";\n" + String.join(";\n", indexDdl);
            }
            List<String> commentDdl = targetDialect.commentDdl(targetMeta, options);
            if (!commentDdl.isEmpty()) {
                ddl = ddl + ";\n" + String.join(";\n", commentDdl);
            }
            String rangeKind = sourceMeta.hasPrimaryKey()
                    ? (isNumericPk(sourceMeta) ? "NUMERIC" : "TEXT") : "NONE";
            long mappingId = idGenerator.nextId();
            TableMapping mapping = new TableMapping(mappingId, taskId, sourceSchema, table,
                    targetSchema, table, sourceMeta.hasPrimaryKey(), rangeKind,
                    sourceMeta.getEstimatedRows(), ddl, false, true);
            tableMappingDao.insert(mapping);
            List<ColumnMapping> columns = buildColumns(sourceMeta, targetDialect);
            columnMappingDao.insertBatch(mappingId, columns);
            created.add(MappingView.of(mapping));
        }
        log.info("映射已生成 task={} tables={} overwrite={}", taskId, created.size(),
                Boolean.TRUE.equals(request.overwrite()));
        return created;
    }

    /**
     * 列出任务下全部表映射。
     *
     * @param taskId 任务 ID
     * @return 映射列表
     */
    public List<MappingView> list(long taskId) {
        loadTask(taskId);
        List<MappingView> views = new ArrayList<>();
        for (TableMapping mapping : tableMappingDao.findByTask(taskId)) {
            views.add(MappingView.of(mapping));
        }
        return views;
    }

    /**
     * 详情（映射 + 列）。
     *
     * @param taskId    任务 ID
     * @param mappingId 映射 ID
     * @return 详情
     */
    public MappingDetailView detail(long taskId, long mappingId) {
        TableMapping mapping = loadMapping(taskId, mappingId);
        List<ColumnMappingView> columns = new ArrayList<>();
        for (ColumnMapping column : columnMappingDao.findByTableMappingId(mappingId)) {
            columns.add(ColumnMappingView.of(column));
        }
        return new MappingDetailView(MappingView.of(mapping, false), columns);
    }

    /**
     * DDL 预览。
     *
     * @param taskId    任务 ID
     * @param mappingId 映射 ID
     * @return DDL 视图
     */
    public DdlView ddl(long taskId, long mappingId) {
        TableMapping mapping = loadMapping(taskId, mappingId);
        SyncTask task = loadTask(taskId);
        DataSourceDef tgtDef = loadDataSource(task.getTargetDsId());
        Dialect targetDialect = registry.handle(task.getTargetDsId()).dialect();
        MappingOptions options = new MappingOptions(TypeMappingOptions.defaults(),
                tgtDef.getIdentifierCase() == null ? IdentifierCase.PRESERVE
                        : tgtDef.getIdentifierCase(),
                true, true, true, false, mapping.getTargetSchema());
        TableMeta sourceMeta = loadTable(task, mapping.getSourceSchema(), mapping.getSourceTable());
        TableMeta targetMeta = new TableMeta(mapping.getTargetSchema(), mapping.getTargetTable(),
                sourceMeta.getColumns(), sourceMeta.getPrimaryKey(), sourceMeta.getIndexes(),
                mapping.getRowCountEstimate(), sourceMeta.getComment());
        List<ColumnMapping> columnMappings = columnMappingDao.findByTableMappingId(mappingId);
        DdlParts parts = buildDdl(targetDialect, targetMeta, options, columnMappings);
        List<String> riskTags = new ArrayList<>();
        if (hasUnboundedNumber(sourceMeta) && "ORACLE".equals(
                loadDataSource(task.getSourceDsId()).getDbType().name())) {
            riskTags.add(RISK_ORACLE_NUMBER);
        }
        return new DdlView(mappingId, mapping.getSourceTable(), mapping.getTargetTable(),
                parts.createTable, parts.indexDdl, parts.commentDdl, riskTags);
    }

    /**
     * DDL 差异对比（PRD MAP-05：源结构 / 生成 DDL / 目标库现有结构）。
     *
     * <p>判定口径：
     * <ul>
     *   <li><b>列</b>：目标表不存在 → 全部 {@code NEW}；列都在但归一化后类型不兼容 →
     *       {@code TYPE_MISMATCH}（归一化规则见 {@link DdlDiffer}）；目标多出的列 → {@code EXTRA}；</li>
     *   <li><b>索引</b>：按映射意图（{@code includeIndexes=true}）应存在的非唯一索引，
     *       在目标库缺失即 {@code MISSING}；唯一键已内联在 {@code CREATE TABLE} 里，不单独比对；</li>
     *   <li><b>目标表不存在不抛异常</b>：返回 {@code targetExists=false}，让前端展示"新表"，
     *       而不是让一个只读预览接口 500。</li>
     * </ul>
     *
     * <p><b>方言隔离</b>：目标库现有结构一律通过 {@link Dialect#metadata()} 读取，
     * 本类不出现任何 {@code information_schema} 之类的库专属 SQL。
     *
     * @param taskId    任务 ID
     * @param mappingId 映射 ID
     * @return 差异对比结果
     */
    public DdlDiffView ddlDiff(long taskId, long mappingId) {
        TableMapping mapping = loadMapping(taskId, mappingId);
        SyncTask task = loadTask(taskId);
        DataSourceDef tgtDef = loadDataSource(task.getTargetDsId());
        Dialect targetDialect = registry.handle(task.getTargetDsId()).dialect();
        MappingOptions options = new MappingOptions(TypeMappingOptions.defaults(),
                tgtDef.getIdentifierCase() == null ? IdentifierCase.PRESERVE
                        : tgtDef.getIdentifierCase(),
                true, true, true, false, mapping.getTargetSchema());
        TableMeta sourceMeta = loadTable(task, mapping.getSourceSchema(), mapping.getSourceTable());
        TableMeta generatedMeta = new TableMeta(mapping.getTargetSchema(), mapping.getTargetTable(),
                sourceMeta.getColumns(), sourceMeta.getPrimaryKey(), sourceMeta.getIndexes(),
                mapping.getRowCountEstimate(), sourceMeta.getComment());
        List<ColumnMapping> columnMappings = columnMappingDao.findByTableMappingId(mappingId);
        DdlParts parts = buildDdl(targetDialect, generatedMeta, options, columnMappings);

        List<String> generatedDdl = new ArrayList<>(
                1 + parts.indexDdl.size() + parts.commentDdl.size());
        generatedDdl.add(parts.createTable);
        generatedDdl.addAll(parts.indexDdl);
        generatedDdl.addAll(parts.commentDdl);

        TableMeta targetMeta = loadTargetTable(task, mapping);
        Map<String, ColumnMapping> byTargetColumn = indexByTargetColumn(columnMappings);
        Map<String, ColumnMeta> targetColumns = indexColumns(targetMeta);

        List<DdlDiffView.ColumnDiff> columns = new ArrayList<>(sourceMeta.getColumns().size());
        int newColumns = 0;
        int typeMismatch = 0;
        List<String> matchedTargetColumns = new ArrayList<>(sourceMeta.getColumns().size());
        for (ColumnMeta col : sourceMeta.getColumns()) {
            ColumnMapping columnMapping = byTargetColumn.get(key(col.getName()));
            String targetColumnName = columnMapping == null ? col.getName()
                    : columnMapping.getTargetColumn();
            matchedTargetColumns.add(key(targetColumnName));
            String generatedType = columnMapping == null || columnMapping.effectiveType() == null
                    || columnMapping.effectiveType().isBlank()
                    ? targetDialect.mapType(col, TypeMappingOptions.defaults())
                    : columnMapping.effectiveType();
            ColumnMeta targetColumn = targetColumns.get(key(targetColumnName));
            DdlDiffView.ColumnDiff diff = DdlDiffer.column(col.getName(), col.typeWithSize(),
                    generatedType, targetColumn == null ? null : targetColumn.typeWithSize());
            if (DdlDiffer.STATUS_NEW.equals(diff.status())) {
                newColumns++;
            } else if (DdlDiffer.STATUS_TYPE_MISMATCH.equals(diff.status())) {
                typeMismatch++;
            }
            columns.add(diff);
        }

        int extraColumns = 0;
        if (targetMeta != null) {
            for (ColumnMeta targetColumn : targetMeta.getColumns()) {
                if (matchedTargetColumns.contains(key(targetColumn.getName()))) {
                    continue;
                }
                columns.add(DdlDiffer.extraColumn(targetColumn.getName(),
                        targetColumn.typeWithSize()));
                extraColumns++;
            }
        }

        List<DdlDiffView.IndexDiff> indexes = new ArrayList<>();
        int missingIndexes = 0;
        Set<String> targetIndexNames = indexNames(targetMeta);
        for (IndexMeta index : sourceMeta.getIndexes()) {
            if (index.isUnique()) {
                continue;
            }
            boolean present = targetIndexNames.contains(key(index.getName()));
            indexes.add(new DdlDiffView.IndexDiff(index.getName(),
                    present ? DdlDiffer.INDEX_OK : DdlDiffer.INDEX_MISSING));
            if (!present) {
                missingIndexes++;
            }
        }

        log.info("DDL 差异对比 task={} mapping={} targetExists={} new={} mismatch={} missingIdx={}",
                taskId, mappingId, Boolean.valueOf(targetMeta != null), Integer.valueOf(newColumns),
                Integer.valueOf(typeMismatch), Integer.valueOf(missingIndexes));
        return new DdlDiffView(mappingId, mapping.getSourceTable(), mapping.getTargetTable(),
                targetMeta != null, generatedDdl, columns, indexes,
                new DdlDiffView.Summary(newColumns, typeMismatch, missingIndexes, extraColumns));
    }

    /**
     * 读取目标库现有表结构（不存在返回 null）。
     *
     * <p>"不存在"的判定用 {@code columns.isEmpty()}：JDBC 标准的
     * {@link java.sql.DatabaseMetaData#getColumns} 对缺表返回空结果集而不是异常，
     * 因此这里既不需要借库错误码，也不需要 {@code information_schema} 之类的库专属 SQL。
     *
     * @param task    任务
     * @param mapping 表映射
     * @return 目标表元数据；目标表不存在返回 null
     */
    private TableMeta loadTargetTable(SyncTask task, TableMapping mapping) {
        DataSourceHandle handle = registry.handle(task.getTargetDsId());
        Connection conn = null;
        try {
            conn = handle.connection();
            TableMeta meta = handle.dialect().metadata()
                    .loadTable(conn, mapping.getTargetSchema(), mapping.getTargetTable());
            return meta.getColumns().isEmpty() ? null : meta;
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.DATASOURCE_CONFIG_INVALID, e,
                    "读取目标表元数据失败：" + mapping.getTargetSchema() + "."
                            + mapping.getTargetTable() + " -> " + e.getMessage());
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 归还连接（异常只记日志：此时已在读取路径上，关闭失败不应覆盖真实原因）。
     *
     * @param connection 连接（可 null）
     */
    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("归还连接失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 列映射按目标列名建索引（比较用，统一大小写）。
     *
     * @param columnMappings 列映射
     * @return 目标列名（大写）→ 列映射
     */
    private static Map<String, ColumnMapping> indexByTargetColumn(
            List<ColumnMapping> columnMappings) {
        Map<String, ColumnMapping> map = new HashMap<>();
        for (ColumnMapping columnMapping : columnMappings) {
            map.put(key(columnMapping.getTargetColumn()), columnMapping);
        }
        return map;
    }

    /**
     * 目标列按列名建索引（比较用，统一大小写）。
     *
     * @param targetMeta 目标表元数据（可 null）
     * @return 列名（大写）→ 列元数据
     */
    private static Map<String, ColumnMeta> indexColumns(TableMeta targetMeta) {
        Map<String, ColumnMeta> map = new HashMap<>();
        if (targetMeta == null) {
            return map;
        }
        for (ColumnMeta col : targetMeta.getColumns()) {
            map.put(key(col.getName()), col);
        }
        return map;
    }

    /**
     * 目标表索引名集合（大小写归一）。
     *
     * @param targetMeta 目标表元数据（可 null）
     * @return 索引名集合
     */
    private static Set<String> indexNames(TableMeta targetMeta) {
        Set<String> names = new HashSet<>();
        if (targetMeta == null) {
            return names;
        }
        for (IndexMeta index : targetMeta.getIndexes()) {
            names.add(key(index.getName()));
        }
        return names;
    }

    /**
     * 标识符归一化（跨库大小写差异：Oracle 大写、PG 小写、MySQL 原样）。
     *
     * @param identifier 标识符
     * @return 大写形式
     */
    private static String key(String identifier) {
        return identifier == null ? "" : identifier.toUpperCase(Locale.ROOT);
    }

    /**
     * 人工覆盖列类型。
     *
     * @param taskId    任务 ID
     * @param mappingId 映射 ID
     * @param column    源列名
     * @param req       覆盖请求（{@code targetType} 为空表示撤销）
     * @param operator  操作人（落 {@code override_by}）
     * @return 覆盖后的列视图
     */
    public ColumnMappingView overrideColumn(long taskId, long mappingId, String column,
                                            com.dbsync.api.dto.mapping.ColumnOverrideRequest req,
                                            String operator) {
        TableMapping mapping = loadMapping(taskId, mappingId);
        if (column == null || column.isBlank()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "列名不能为空");
        }
        IdentifierValidator.validate(column);
        List<ColumnMapping> columns = columnMappingDao.findByTableMappingId(mappingId);
        if (columns.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "映射下无列信息，请先重新生成映射")
                    .with("tableMappingId", Long.valueOf(mapping.getId()));
        }
        String targetType = req == null || req.targetType() == null
                || req.targetType().isBlank() ? null : req.targetType().trim();
        List<ColumnMapping> updated = new ArrayList<>(columns.size());
        ColumnMapping hit = null;
        for (ColumnMapping c : columns) {
            if (!c.getSourceColumn().equalsIgnoreCase(column)) {
                updated.add(c);
                continue;
            }
            hit = new ColumnMapping(c.getSourceColumn(), c.getTargetColumn(), c.getSourceType(),
                    c.getTargetType(), targetType, targetType == null ? null : operator,
                    c.isPrimaryKey(), c.isAutoIncrement(), c.getOrdinal());
            updated.add(hit);
        }
        if (hit == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "列不存在：" + column)
                    .with("column", column);
        }
        columnMappingDao.deleteByTableMappingId(mappingId);
        columnMappingDao.insertBatch(mappingId, updated);
        log.info("列类型已覆盖 mapping={} column={} type={} by={}", mappingId, column, targetType,
                operator);
        return ColumnMappingView.of(hit);
    }

    /**
     * 更新表级冲突策略（PRD CDC-06「三种策略按表可配」）。
     *
     * <p>校验只接受 {@code SOURCE_WINS} / {@code TARGET_WINS} / {@code TIMESTAMP_NEWER}，
     * 非法值抛 {@code E1001}（{@link ConflictPolicy#parse}），<b>不静默兜底</b>。
     *
     * <p>只写策略列，不动 {@code enabled}：策略与"是否参与同步"正交，
     * 禁用中的表也应能先配好策略，恢复时立即生效。
     *
     * @param taskId         任务 ID
     * @param mappingId      映射 ID
     * @param conflictPolicy 策略文本
     * @return 更新后的映射视图
     */
    public MappingView updateConflictPolicy(long taskId, long mappingId, String conflictPolicy) {
        TableMapping mapping = loadMapping(taskId, mappingId);
        ConflictPolicy policy = ConflictPolicy.parse(conflictPolicy);
        int rows = tableMappingDao.updateConflictPolicy(mappingId, policy);
        if (rows == 0) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "表映射不存在：" + mappingId)
                    .with("tableMappingId", Long.valueOf(mappingId));
        }
        mapping.setConflictPolicy(policy);
        log.info("表级冲突策略已更新 task={} mapping={} table={} policy={}", taskId, mappingId,
                mapping.getSourceTable(), policy);
        return MappingView.of(mapping);
    }

    /**
     * 读取表映射（含任务归属校验）。
     *
     * @param taskId    任务 ID
     * @param mappingId 映射 ID
     * @return 映射
     */
    public TableMapping loadMapping(long taskId, long mappingId) {
        TableMapping mapping = tableMappingDao.findById(mappingId).orElseThrow(
                () -> new DbSyncException(ErrorCode.PARAM_INVALID, "表映射不存在：" + mappingId));
        if (mapping.getTaskId() != taskId) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "表映射不属于该任务：mapping=" + mappingId + " task=" + taskId);
        }
        return mapping;
    }

    private SyncTask loadTask(long taskId) {
        return syncTaskDao.findById(taskId).orElseThrow(
                () -> new DbSyncException(ErrorCode.TASK_NOT_FOUND, taskId));
    }

    private DataSourceDef loadDataSource(long id) {
        return dataSourceDao.findById(id).orElseThrow(
                () -> new DbSyncException(ErrorCode.DATASOURCE_NOT_FOUND, id));
    }

    private List<String> resolveTables(SyncTask task, String schema, List<String> explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            List<String> names = new ArrayList<>(explicit.size());
            for (String table : explicit) {
                names.add(IdentifierValidator.validate(table));
            }
            return names;
        }
        DataSourceHandle handle = registry.handle(task.getSourceDsId());
        try {
            return handle.dialect().metadata().listTables(handle.connection(), schema);
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.DATASOURCE_CONFIG_INVALID, e,
                    "读取源表清单失败：" + e.getMessage());
        }
    }

    private TableMeta loadTable(SyncTask task, String schema, String table) {
        DataSourceHandle handle = registry.handle(task.getSourceDsId());
        try {
            Connection conn = handle.connection();
            return handle.dialect().metadata().loadTable(conn, schema, table);
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.DATASOURCE_CONFIG_INVALID, e,
                    "读取表元数据失败：" + schema + "." + table + " -> " + e.getMessage());
        }
    }

    private List<ColumnMapping> buildColumns(TableMeta sourceMeta, Dialect targetDialect) {
        List<ColumnMeta> pkColumns = sourceMeta.primaryKeyColumns();
        List<ColumnMapping> columns = new ArrayList<>(sourceMeta.getColumns().size());
        for (ColumnMeta col : sourceMeta.getColumns()) {
            boolean pk = pkColumns.stream()
                    .anyMatch(p -> p.getName().equalsIgnoreCase(col.getName()));
            String targetType = targetDialect.mapType(col, TypeMappingOptions.defaults());
            columns.add(new ColumnMapping(col.getName(), col.getName(), col.typeWithSize(),
                    targetType, null, null, pk, col.isAutoIncrement(), col.getOrdinal()));
        }
        return columns;
    }

    /**
     * 结构化生成 DDL 片段（替代旧的 {@code applyOverrides} 文本替换）。
     *
     * <p>把「列类型覆盖表」按列名传给方言，方言在 {@code buildColumnDef} 里直接采用覆盖类型，
     * 覆盖与源列当前类型、{@code DEFAULT}/{@code COMMENT} 字面量完全解耦：
     * 不再有 needle 失配（源类型一变就静默丢覆盖）或误替换字面量内部的问题。
     * 索引 / 注释 DDL 走同一覆盖上下文，覆盖成 LOB/TEXT 时方言跳过不可索引的索引。
     *
     * <p>本方法是 {@link #ddl(long, long)} 与 {@link #ddlDiff(long, long)} 的唯一 DDL 生成入口，
     * 保证两处口径一致，不会一处改了另一处漏改；包内可见以便
     * {@code MappingOverrideDdlTest} 无需任何 DAO/连接即可直接验证。
     *
     * @param dialect        目标方言
     * @param targetMeta     目标表元数据（列名为源端原始大小写）
     * @param options        映射选项（含 identifierCase）
     * @param columnMappings 列映射（人工覆盖来源）
     * @return DDL 片段集合
     */
    static DdlParts buildDdl(Dialect dialect, TableMeta targetMeta, MappingOptions options,
                             List<ColumnMapping> columnMappings) {
        Map<String, String> overrides = columnOverrides(columnMappings);
        warnUnmatchedOverrides(targetMeta, overrides);
        String createTable = dialect.createTableDdl(targetMeta, options, overrides);
        List<String> indexDdl = dialect.createIndexDdl(targetMeta, options, overrides);
        List<String> commentDdl = dialect.commentDdl(targetMeta, options, overrides);
        return new DdlParts(createTable, indexDdl, commentDdl);
    }

    /**
     * 由列映射构造「目标列名（大写）→ 覆盖类型」表。仅收录真正的人工覆盖
     * （{@code mappedType} 非空）。键只依赖列名，与源列当前类型、生成映射时的类型快照无关。
     *
     * @param columnMappings 列映射
     * @return 覆盖表（键统一大写，供方言大小写不敏感查找）
     */
    private static Map<String, String> columnOverrides(List<ColumnMapping> columnMappings) {
        Map<String, String> overrides = new HashMap<>();
        for (ColumnMapping c : columnMappings) {
            String override = c.getMappedType();
            if (override != null && !override.isBlank()) {
                overrides.put(key(c.getTargetColumn()), override.trim());
            }
        }
        return overrides;
    }

    /**
     * 覆盖未命中任何目标列时明确 WARN（禁止静默跳过，例如源列已改名/删除导致旧覆盖失效）。
     *
     * @param targetMeta 目标表元数据
     * @param overrides  覆盖表
     */
    private static void warnUnmatchedOverrides(TableMeta targetMeta,
                                               Map<String, String> overrides) {
        if (overrides.isEmpty()) {
            return;
        }
        Set<String> columnKeys = new HashSet<>();
        for (ColumnMeta col : targetMeta.getColumns()) {
            columnKeys.add(key(col.getName()));
        }
        for (String overrideKey : overrides.keySet()) {
            if (!columnKeys.contains(overrideKey)) {
                log.warn("列类型覆盖未命中任何目标列（源列可能已改名或删除），该覆盖被忽略：column={}",
                        overrideKey);
            }
        }
    }

    /** DDL 片段集合（包内可见，便于结构化生成单测直接断言）。 */
    static final class DdlParts {
        final String createTable;
        final List<String> indexDdl;
        final List<String> commentDdl;

        DdlParts(String createTable, List<String> indexDdl, List<String> commentDdl) {
            this.createTable = createTable;
            this.indexDdl = indexDdl;
            this.commentDdl = commentDdl;
        }
    }

    private static boolean isNumericPk(TableMeta meta) {
        for (ColumnMeta pk : meta.primaryKeyColumns()) {
            if (pk.isLob()) {
                return false;
            }
            int type = pk.getJdbcType();
            boolean numeric = type == java.sql.Types.INTEGER || type == java.sql.Types.BIGINT
                    || type == java.sql.Types.SMALLINT || type == java.sql.Types.TINYINT
                    || type == java.sql.Types.NUMERIC || type == java.sql.Types.DECIMAL
                    || type == java.sql.Types.REAL || type == java.sql.Types.DOUBLE
                    || type == java.sql.Types.FLOAT;
            if (!numeric) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasUnboundedNumber(TableMeta meta) {
        for (ColumnMeta col : meta.getColumns()) {
            String typeName = col.getTypeName() == null ? ""
                    : col.getTypeName().toUpperCase(Locale.ROOT);
            if (typeName.equals("NUMBER") && (col.getSize() <= 0 || col.getSize() > 38)) {
                return true;
            }
        }
        return false;
    }

    private static String blankToDefault(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return IdentifierValidator.validate(value.trim());
        }
        return fallback == null ? "" : fallback;
    }

    /**
     * 便捷：读取单条映射（不存在返回 empty）。
     *
     * @param mappingId 映射 ID
     * @return 映射
     */
    public Optional<TableMapping> findMapping(long mappingId) {
        return tableMappingDao.findById(mappingId);
    }
}
