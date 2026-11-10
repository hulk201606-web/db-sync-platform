package com.dbsync.migration;

import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.ReadOptions;
import com.dbsync.common.options.WriteOptions;
import com.dbsync.common.util.IdentifierValidator;
import com.dbsync.dialect.Dialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 单张表映射的执行上下文（源/目标两侧的连接、方言、元数据与选项）。
 *
 * <p>// ADDED: 04 文档只给出 {@code ShardWorker.run(Shard, LeaseHandle)} 签名，
 * 但分片执行还需要"源/目标是哪个数据源、表结构长什么样、批多大、限速多少"。
 * 若不收敛到一个对象，{@code ShardWorker} 的构造函数会膨胀到 12+ 个参数，
 * 且单测无法注入假连接。故新增本上下文类型，由
 * {@link TaskRuntimeFactory} 构造、{@link ShardDispatcher} 复用。
 *
 * <p><b>为什么连接用 {@link ConnectionSupplier} 而不是直接持有 {@code DataSourceHandle}</b>：
 * {@code DataSourceHandle} 是 final 类、且内部持有真实 Hikari 池，无法在单测里替身；
 * 用 supplier 后，生产接线是 {@code handle::connection}，单测可直接给一个假连接。
 *
 * <p>本类不可变、线程安全；同一 {@code TaskRuntime} 内多线程共享无副作用。
 */
public final class ShardContext {

    /**
     * 连接提供者（生产为连接池借出，单测为假连接）。
     *
     * <p>// ADDED: 与 {@code com.dbsync.core.datasource.SqlFunction} 的区别是
     * 本接口"只借不还"——归还由 {@code ShardWorker} 在 finally 里负责，
     * 与 {@code JdbcReader} 的借还语义保持一致。
     */
    @FunctionalInterface
    public interface ConnectionSupplier {

        /**
         * 借一个连接。
         *
         * @return 连接
         * @throws SQLException 借出失败
         */
        Connection get() throws SQLException;
    }

    private final long taskId;
    private final TableMapping mapping;
    private final TableMeta sourceTable;
    private final TableMeta targetTable;
    private final Dialect sourceDialect;
    private final Dialect targetDialect;
    private final ConnectionSupplier sourceConnections;
    private final ConnectionSupplier targetConnections;
    private final ReadOptions readOptions;
    private final WriteOptions writeOptions;
    private final BooleanSupplier stopSignal;

    /**
     * 构造。
     *
     * @param taskId            任务 ID
     * @param mapping           表映射
     * @param sourceTable       源表元数据
     * @param targetTable       目标表元数据
     * @param sourceDialect     源方言
     * @param targetDialect     目标方言
     * @param sourceConnections 源连接提供者
     * @param targetConnections 目标连接提供者
     * @param readOptions       读取选项
     * @param writeOptions      写入选项
     * @param stopSignal        暂停/取消信号（返回 true 表示本批之后停下）
     */
    public ShardContext(long taskId, TableMapping mapping, TableMeta sourceTable, TableMeta targetTable,
                        Dialect sourceDialect, Dialect targetDialect,
                        ConnectionSupplier sourceConnections, ConnectionSupplier targetConnections,
                        ReadOptions readOptions, WriteOptions writeOptions,
                        BooleanSupplier stopSignal) {
        this.taskId = taskId;
        this.mapping = mapping;
        this.sourceTable = sourceTable;
        this.targetTable = targetTable;
        this.sourceDialect = sourceDialect;
        this.targetDialect = targetDialect;
        this.sourceConnections = sourceConnections;
        this.targetConnections = targetConnections;
        this.readOptions = readOptions == null ? ReadOptions.of(1000) : readOptions;
        this.writeOptions = writeOptions == null ? WriteOptions.upsert() : writeOptions;
        this.stopSignal = stopSignal == null ? () -> false : stopSignal;
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 表映射 */
    public TableMapping getMapping() {
        return mapping;
    }

    /** @return 表映射 ID */
    public long getTableMappingId() {
        return mapping.getId();
    }

    /** @return 源表元数据 */
    public TableMeta getSourceTable() {
        return sourceTable;
    }

    /** @return 目标表元数据 */
    public TableMeta getTargetTable() {
        return targetTable;
    }

    /** @return 源方言 */
    public Dialect getSourceDialect() {
        return sourceDialect;
    }

    /** @return 目标方言 */
    public Dialect getTargetDialect() {
        return targetDialect;
    }

    /** @return 源连接提供者 */
    public ConnectionSupplier getSourceConnections() {
        return sourceConnections;
    }

    /** @return 目标连接提供者 */
    public ConnectionSupplier getTargetConnections() {
        return targetConnections;
    }

    /** @return 读取选项 */
    public ReadOptions getReadOptions() {
        return readOptions;
    }

    /** @return 写入选项 */
    public WriteOptions getWriteOptions() {
        return writeOptions;
    }

    /**
     * 批大小（读批大小是"上限"，写批随读批走，避免二次缓冲）。
     *
     * @return 批大小
     */
    public int getBatchSize() {
        return readOptions.effectiveBatchSize();
    }

    /**
     * @return true 表示收到暂停/取消信号，本批处理完应停下
     */
    public boolean stopRequested() {
        return stopSignal.getAsBoolean();
    }

    /**
     * 主键列在"行数组"中的下标。
     *
     * <p>{@code JdbcReader} 按 {@code TableMeta.getColumns()} 的顺序产出 {@code Object[]}，
     * 因此这里用同名查找即可（不做任何重排，K4：归一化只发生在 Normalizer）。
     *
     * @return 下标；无主键返回 -1
     */
    public int primaryKeyIndex() {
        if (!sourceTable.hasPrimaryKey()) {
            return -1;
        }
        String pkName = sourceTable.primaryKeyColumns().get(0).getName();
        List<String> names = sourceTable.columnNames();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(pkName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 目标表名（已过标识符白名单校验）。
     *
     * @return 已校验的表名
     */
    public String validatedTargetTable() {
        String table = targetTable.getTableName();
        int maxLength = targetDialect.capabilities() == null
                ? 128 : targetDialect.capabilities().maxIdentifierLength();
        return IdentifierValidator.validate(table, maxLength, targetDialect.type());
    }

    @Override
    public String toString() {
        return "ShardContext[task=" + taskId + "," + sourceTable.qualifiedName() + "->"
                + targetTable.qualifiedName() + ",batch=" + getBatchSize() + "]";
    }
}
