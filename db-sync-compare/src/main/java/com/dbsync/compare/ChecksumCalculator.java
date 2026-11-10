package com.dbsync.compare;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.ReadOptions;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.read.JdbcReader;
import com.dbsync.dialect.Dialect;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一级校验和计算器（T35）：单块的 COUNT + SUM 串联哈希 + 行哈希异或聚合。
 *
 * <p><b>为什么先算校验和再下钻</b>：1 亿行的表若直接逐行比对，网络与 CPU 都会被打满；
 * 分块校验和让"绝大多数一致的块"只付一次全扫的代价（架构 §8.4）。
 *
 * <p><b>SQL 生成约束</b>（05 硬规则 3）：本类不含任何库专属字面量，
 * 标识符一律经 {@link Dialect#quote}，值一律 {@link PreparedStatement} 绑定。
 *
 * <p><b>已知限制（显式说明）</b>：
 * <ol>
 *   <li>区间谓词只用<b>首列</b>主键（与 {@code ShardPlanner} 的切分口径一致）；
 *       联合主键的其余列不参与边界，仍能保证"不重不漏"，只是块内行数不严格等于目标值。</li>
 *   <li>行哈希用异或聚合，对"块内两行互换"不敏感；这是刻意的取舍（异或可流式、O(1) 内存），
 *       互换型差异由 SUM 哈希与下钻兜底。</li>
 * </ol>
 */
public class ChecksumCalculator {

    private static final Logger log = LoggerFactory.getLogger(ChecksumCalculator.class);

    /** 流式读的批大小 / fetchSize。 */
    private static final int FETCH_SIZE = 2000;

    /** SUM 结果为 NULL 时的编码（避免与字符串 {@code "null"} 混同）。 */
    private static final String NULL_SUM_TEXT = "\u0000NULL";

    private final JdbcReader reader;
    private final RowHashBuilder hashBuilder;

    /**
     * 构造。
     *
     * @param normalizer 归一化器（必须与 {@link RowDiffer} 共用同一实例）
     */
    public ChecksumCalculator(Normalizer normalizer) {
        this(new JdbcReader(), new RowHashBuilder(normalizer));
    }

    /**
     * 构造（测试可注入 reader）。
     *
     * @param reader      流式读取器
     * @param hashBuilder 行哈希构建器
     */
    public ChecksumCalculator(JdbcReader reader, RowHashBuilder hashBuilder) {
        this.reader = reader == null ? new JdbcReader() : reader;
        this.hashBuilder = hashBuilder;
    }

    /**
     * 计算单块校验和（借还连接由本方法负责）。
     *
     * @param handle 数据源句柄
     * @param table  表元数据
     * @param block  块区间
     * @param n      归一化器（须与 {@link #hashBuilder} 同一实例）
     * @return 校验和
     */
    public BlockChecksum checksum(DataSourceHandle handle, TableMeta table, PkRange block,
                                  Normalizer n) {
        Connection connection = null;
        try {
            connection = handle.connection();
            return checksum(connection, handle.dialect(), table, block, n);
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.COMPARE_BLOCK_FAILED, e,
                    "取连接失败：" + table.qualifiedName() + " " + block);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 归还失败由连接池自行淘汰
                }
            }
        }
    }

    /**
     * 计算单块校验和（连接由调用方提供，<b>不会</b>被关闭）。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      表元数据
     * @param block      块区间
     * @param n          归一化器
     * @return 校验和（blockId=0 / blockNo=-1 / mappingId=0，由引擎 {@code withIdentity} 补齐）
     */
    public BlockChecksum checksum(Connection connection, Dialect dialect, TableMeta table,
                                  PkRange block, Normalizer n) {
        Normalizer normalizer = n == null ? hashBuilder.normalizer() : n;
        List<ColumnMeta> columns = table.getColumns();
        final long[] accumulator = new long[] {0L};
        long count;
        try {
            // 一次流式全扫同时得到 count 与行哈希异或聚合（不做第二遍扫描）
            count = reader.read(connection, dialect, table, block, ReadOptions.of(FETCH_SIZE),
                    (row, rowIndex) -> accumulator[0] ^= first64Bits(
                            hashBuilder.hash(hashBuilder.rowOf(row, columns), columns)));
        } catch (RuntimeException e) {
            throw new DbSyncException(ErrorCode.COMPARE_BLOCK_FAILED, e,
                    "块读取失败：" + table.qualifiedName() + " " + block);
        }
        String sumHash = sumHash(connection, dialect, table, block, normalizer);
        log.debug("块校验和 table={} block={} count={} sumHash={} rowHash={}",
                table.qualifiedName(), block, count, sumHash, Long.toHexString(accumulator[0]));
        return new BlockChecksum(0L, -1, 0L, block, count, sumHash,
                Long.toHexString(accumulator[0]));
    }

    /**
     * 计算数值列 SUM 的串联哈希。
     *
     * <p>数值列由 {@link ColumnMeta#getJdbcType()} 判定，不含 LOB/BLOB。
     * 没有任何数值列时返回固定串 {@code "NONE"}（两侧一致，不影响判定）。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      表元数据
     * @param block      块区间
     * @param normalizer 归一化器（SUM 结果按 DECIMAL 归一化，吃同一套 P3 规则）
     * @return 串联哈希
     */
    private String sumHash(Connection connection, Dialect dialect, TableMeta table, PkRange block,
                           Normalizer normalizer) {
        List<ColumnMeta> numeric = new ArrayList<ColumnMeta>();
        for (ColumnMeta column : table.getColumns()) {
            if (isNumeric(column)) {
                numeric.add(column);
            }
        }
        if (numeric.isEmpty()) {
            return "NONE";
        }
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < numeric.size(); i++) {
            if (i > 0) {
                select.append(',');
            }
            select.append("SUM(").append(dialect.quote(numeric.get(i).getName())).append(')');
        }
        select.append(" FROM ").append(qualified(dialect, table));
        List<Object> args = new ArrayList<Object>(2);
        String where = whereClause(dialect, table, block, args);
        if (where != null) {
            select.append(" WHERE ").append(where);
        }
        StringBuilder text = new StringBuilder();
        try (PreparedStatement statement = connection.prepareStatement(select.toString())) {
            int index = 1;
            for (Object arg : args) {
                statement.setObject(index++, arg);
            }
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    for (int i = 0; i < numeric.size(); i++) {
                        BigDecimal value = rs.getBigDecimal(i + 1);
                        String encoded = value == null
                                ? NULL_SUM_TEXT
                                : normalizer.canonicalText(value, numeric.get(i));
                        text.append(numeric.get(i).getName()).append('=').append(encoded)
                                .append(';');
                    }
                }
            }
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.COMPARE_BLOCK_FAILED, e,
                    "SUM 计算失败：" + table.qualifiedName() + " " + block + "：" + e.getMessage());
        }
        return com.dbsync.common.util.HashUtil.md5Hex(
                text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 生成块区间的 WHERE 片段（无主键时返回 null）。
     *
     * @param dialect 方言
     * @param table   表元数据
     * @param block   块区间
     * @param args    参数收集器（按生成顺序 append）
     * @return where 片段（不含 WHERE 关键字）或 null
     */
    private static String whereClause(Dialect dialect, TableMeta table, PkRange block,
                                      List<Object> args) {
        List<String> pkNames = table.primaryKeyNames();
        if (pkNames.isEmpty() || block == null || block.isUnbounded()) {
            return null;
        }
        String quoted = dialect.quote(pkNames.get(0));
        StringBuilder where = new StringBuilder();
        if (block.getStart() != null) {
            where.append(dialect.buildLowerPredicate(quoted));
            args.add(typedBound(block.getStart(), block));
        }
        if (block.getEnd() != null) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append(dialect.buildRangePredicate(quoted, true));
            args.add(typedBound(block.getEnd(), block));
        }
        return where.length() == 0 ? null : where.toString();
    }

    /**
     * 边界值转成与主键同类型的值（数值区间传字符串时驱动可能按文本比较，导致少行）。
     *
     * @param value 边界值
     * @param block 区间（提供 {@code kind}）
     * @return 已定型值
     */
    private static Object typedBound(Object value, PkRange block) {
        if (block.getKind() == RangeKind.NUMERIC && !(value instanceof Number)) {
            try {
                return com.dbsync.common.util.PkRangeUtil.parse(String.valueOf(value),
                        RangeKind.NUMERIC);
            } catch (RuntimeException e) {
                return value;
            }
        }
        return value;
    }

    /**
     * 拼 {@code schema.table}（schema 为空时只拼表名）。
     *
     * @param dialect 方言
     * @param table   表元数据
     * @return 已加引号的限定表名
     */
    static String qualified(Dialect dialect, TableMeta table) {
        String schema = table.getSchema();
        String quotedTable = dialect.quote(table.getTableName());
        if (schema == null || schema.isBlank()) {
            return quotedTable;
        }
        return dialect.quote(schema) + '.' + quotedTable;
    }

    /**
     * 是否数值列（排除 LOB / 时间 / 布尔）。
     *
     * @param column 列元数据
     * @return true 表示参与 SUM
     */
    private static boolean isNumeric(ColumnMeta column) {
        if (column.isLob()) {
            return false;
        }
        switch (column.getJdbcType()) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.NUMERIC:
            case Types.DECIMAL:
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
                return true;
            default:
                return false;
        }
    }

    /**
     * 取 md5 前 8 字节作为 long（异或聚合用）。
     *
     * @param md5Hex 32 位十六进制
     * @return 64 位值
     */
    static long first64Bits(String md5Hex) {
        if (md5Hex == null || md5Hex.length() < 16) {
            return 0L;
        }
        return Long.parseUnsignedLong(md5Hex.substring(0, 16), 16);
    }
}

