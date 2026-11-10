package com.dbsync.core.shard;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.dialect.Dialect;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主键采样器：取 min/max 与分位点，供 {@link ShardPlanner} 精确切分。
 *
 * <p>为什么需要分位采样：自增主键被大量删除后（如 id 1..100 只剩 90..100），
 * 等宽切分会得到 99% 的空分片——分片数虚高、调度浪费。用分位点校正可让每片行数接近均匀。
 *
 * <p>所有 SQL 的标识符都经 {@link Dialect#quote}，分页都走
 * {@link Dialect#buildPageSql}（<b>不</b>在本模块写 {@code LIMIT}/{@code ROWNUM}）。
 */
public class PkSampler {

    private static final Logger log = LoggerFactory.getLogger(PkSampler.class);

    /** 分位点上限（架构：最多 8 个）。 */
    private static final int MAX_QUANTILES = 8;

    /**
     * 取主键最小值。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      表元数据
     * @return 最小值；表空或无主键返回 empty
     */
    public Optional<Object> min(Connection connection, Dialect dialect, TableMeta table) {
        return aggregate(connection, dialect, table, "MIN");
    }

    /**
     * 取主键最大值。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      表元数据
     * @return 最大值；表空或无主键返回 empty
     */
    public Optional<Object> max(Connection connection, Dialect dialect, TableMeta table) {
        return aggregate(connection, dialect, table, "MAX");
    }

    /**
     * 分位采样（最多 8 个分位点）。
     *
     * <p>做法：先按 min/max 等分算出候选边界，再用
     * {@code SELECT pk FROM t WHERE pk > ? ORDER BY pk <分页取 1 行>} 校正到"真实存在的下一个主键"，
     * 这样即使数据稀疏也能得到可用的切分点。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      表元数据
     * @param buckets    桶数（2..8，超出范围自动收敛）
     * @return 分位点（升序，长度 ≤ buckets-1）
     */
    public List<Object> quantiles(Connection connection, Dialect dialect, TableMeta table,
                                  int buckets) {
        int k = Math.max(2, Math.min(buckets, MAX_QUANTILES));
        Optional<Object> minValue = min(connection, dialect, table);
        Optional<Object> maxValue = max(connection, dialect, table);
        if (minValue.isEmpty() || maxValue.isEmpty()) {
            return List.of();
        }
        BigDecimal low;
        BigDecimal high;
        try {
            low = toBigDecimal(minValue.get());
            high = toBigDecimal(maxValue.get());
        } catch (RuntimeException e) {
            log.debug("主键非数值，跳过分位采样：{}", e.getMessage());
            return List.of();
        }
        if (low.compareTo(high) >= 0) {
            return List.of();
        }
        BigDecimal span = high.subtract(low);
        List<Object> result = new ArrayList<Object>(k - 1);
        for (int i = 1; i < k; i++) {
            BigDecimal step = span.multiply(BigDecimal.valueOf(i))
                    .divide(BigDecimal.valueOf(k), new MathContext(20, RoundingMode.HALF_UP));
            String candidate = low.add(step).setScale(10, RoundingMode.CEILING)
                    .stripTrailingZeros().toPlainString();
            Optional<Object> exact = nextExisting(connection, dialect, table, candidate);
            exact.ifPresent(result::add);
        }
        return result;
    }

    /**
     * 聚合查询（MIN/MAX）。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      表元数据
     * @param function   MIN 或 MAX
     * @return 值
     */
    private Optional<Object> aggregate(Connection connection, Dialect dialect, TableMeta table,
                                       String function) {
        if (!table.hasPrimaryKey()) {
            return Optional.empty();
        }
        ColumnMeta pk = table.primaryKeyColumns().get(0);
        String sql = "SELECT " + function + "(" + dialect.quote(pk.getName()) + ") FROM "
                + qualified(dialect, table);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Object value = rs.getObject(1);
                return value == null ? Optional.empty() : Optional.of(value);
            }
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.CONN_NETWORK_FAILED,
                    function + " 采样失败：" + e.getMessage()).with("table", table.qualifiedName());
        }
    }

    /**
     * 找到严格大于给定值的最小已存在主键。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      表元数据
     * @param boundary   候选边界
     * @return 主键值；不存在返回 empty
     */
    private Optional<Object> nextExisting(Connection connection, Dialect dialect, TableMeta table,
                                          String boundary) {
        ColumnMeta pk = table.primaryKeyColumns().get(0);
        String quotedPk = dialect.quote(pk.getName());
        String base = "SELECT " + quotedPk + " FROM " + qualified(dialect, table)
                + " WHERE " + quotedPk + " > ? ORDER BY " + quotedPk;
        String sql = dialect.buildPageSql(base, 0L, 1);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBigDecimal(1, new BigDecimal(boundary));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Object value = rs.getObject(1);
                return value == null ? Optional.empty() : Optional.of(value);
            }
        } catch (SQLException e) {
            log.debug("分位点校正失败（沿用算术边界）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 拼 schema.table 的引号全名。
     *
     * @param dialect 方言
     * @param table   表元数据
     * @return 全名
     */
    private static String qualified(Dialect dialect, TableMeta table) {
        String schema = table.getSchema();
        if (schema == null || schema.isEmpty()) {
            return dialect.quote(table.getTableName());
        }
        return dialect.quote(schema) + "." + dialect.quote(table.getTableName());
    }

    /**
     * 宽松转 BigDecimal。
     *
     * @param value 值
     * @return BigDecimal
     */
    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        return new BigDecimal(String.valueOf(value).trim());
    }
}
