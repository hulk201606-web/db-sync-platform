package com.dbsync.server.config;

import com.dbsync.common.model.DeadLetter;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.WriteOptions;
import com.dbsync.common.util.JsonUtil;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.write.JdbcBatchWriter;
import com.dbsync.core.write.WriteResult;
import com.dbsync.dialect.Dialect;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import com.dbsync.migration.DeadLetterService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 变更类死信的重放器（把 {@code payload_json} 里的 after 镜像按主键 upsert 回目标库）。
 *
 * <p>// ADDED: {@code DeadLetterService.ReplayApplier} 由 04 文档定义为"由 cdc 提供实现"，
 * 但 cdc 模块不依赖 core 的写库组件组装（避免环依赖），因此把"取连接 + 取方言 + 组装行"
 * 这部分留在 server 装配层实现，语义严格对齐 {@code UpsertApplier}：
 * <b>按主键幂等 upsert，不产生重复行</b>。
 *
 * <p>安全边界：
 * <ul>
 *   <li>表名/列名全部来自元数据（{@link TableMeta}），不接受载荷里的任意标识符；</li>
 *   <li>值一律走 {@link JdbcBatchWriter} 的 {@code PreparedStatement} 绑定；</li>
 *   <li>重放在自己的事务里提交（{@code autoCommit=false} → upsert → commit/rollback）。</li>
 * </ul>
 */
public class ChangeReplayApplier implements DeadLetterService.ReplayApplier {

    private static final Logger log = LoggerFactory.getLogger(ChangeReplayApplier.class);

    private final TableMappingDao tableMappingDao;
    private final SyncTaskDao syncTaskDao;
    private final DataSourceRegistry registry;
    private final JdbcBatchWriter writer = new JdbcBatchWriter();

    /**
     * 构造。
     *
     * @param tableMappingDao 表映射 DAO
     * @param syncTaskDao     任务 DAO
     * @param registry        数据源注册表
     */
    public ChangeReplayApplier(TableMappingDao tableMappingDao, SyncTaskDao syncTaskDao,
                               DataSourceRegistry registry) {
        this.tableMappingDao = tableMappingDao;
        this.syncTaskDao = syncTaskDao;
        this.registry = registry;
    }

    @Override
    public int apply(DeadLetter letter) {
        if (letter.getTableMappingId() == null) {
            throw new IllegalStateException("CHANGE 死信缺少 table_mapping_id：" + letter.getId());
        }
        SyncTask task = syncTaskDao.findById(letter.getTaskId()).orElseThrow(
                () -> new IllegalStateException("任务不存在：" + letter.getTaskId()));
        TableMapping mapping = tableMappingDao.findById(letter.getTableMappingId().longValue())
                .orElseThrow(() -> new IllegalStateException(
                        "表映射不存在：" + letter.getTableMappingId()));
        Map<String, Object> after = JsonUtil.parseObject(letter.getPayloadJson());
        DataSourceHandle handle = registry.handle(task.getTargetDsId());
        Dialect dialect = handle.dialect();
        try (Connection connection = handle.connection()) {
            TableMeta table = dialect.metadata().loadTable(connection, mapping.getTargetSchema(),
                    mapping.getTargetTable());
            Object[] row = toRow(table, after);
            List<Object[]> rows = new ArrayList<Object[]>(1);
            rows.add(row);
            connection.setAutoCommit(false);
            WriteResult result = writer.upsert(connection, dialect, table, rows,
                    WriteOptions.upsert());
            if (!result.committed()) {
                connection.commit();
            }
            log.info("变更死信重放成功 letter={} table={} rows={}", Long.valueOf(letter.getId()),
                    mapping.getTargetTable(), Long.valueOf(result.rowsAffected()));
            return (int) Math.min((long) Integer.MAX_VALUE, result.rowsAffected());
        } catch (SQLException e) {
            throw new IllegalStateException("重放失败（目标库异常）：" + e.getMessage(), e);
        }
    }

    /**
     * 按目标表列顺序把 after 镜像折叠成一行（列名大小写不敏感）。
     *
     * @param table 目标表元数据
     * @param after after 镜像
     * @return 行数组
     */
    private static Object[] toRow(TableMeta table, Map<String, Object> after) {
        List<com.dbsync.common.model.ColumnMeta> columns = table.getColumns();
        Object[] row = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i).getName();
            Object value = after.get(name);
            if (value == null) {
                for (Map.Entry<String, Object> entry : after.entrySet()) {
                    if (entry.getKey() != null
                            && entry.getKey().toUpperCase(Locale.ROOT)
                            .equals(name.toUpperCase(Locale.ROOT))) {
                        value = entry.getValue();
                        break;
                    }
                }
            }
            row[i] = value;
        }
        return row;
    }
}
