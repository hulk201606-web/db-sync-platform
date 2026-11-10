package com.dbsync.compare;

import com.dbsync.common.enums.DiffType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.DiffRecord;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.ReadOptions;
import com.dbsync.common.util.HashUtil;
import com.dbsync.common.util.JsonUtil;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.read.JdbcReader;
import com.dbsync.dialect.Dialect;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 差异下钻器（T36）：只对"校验和不一致"的块做逐行比对。
 *
 * <p><b>三步</b>：
 * <ol>
 *   <li>两端按块区间拉全行，用"归一化后的主键文本"做键放进 Map；</li>
 *   <li>主键差集 → {@link DiffType#ONLY_IN_SOURCE} / {@link DiffType#ONLY_IN_TARGET}；</li>
 *   <li>同主键 → <b>逐列全量比较</b>（不因第一处差异短路，PRD CMP-03 要求列出全部差异列）。</li>
 * </ol>
 *
 * <p><b>归一化必须是同一个实例（K4）</b>：本类通过 {@link RowHashBuilder} 复用注入的
 * {@link Normalizer}，自身不包含任何归一化规则。
 *
 * <p><b>P8 浮点容差</b>：容差关系不可传递，所以容差<b>只在</b>这里的逐列比较中生效
 * （{@code |a-b| <= eps}），不参与哈希——哈希阶段开容差会让"哈希说有差异、比较说没差异"。
 *
 * <p><b>脱敏</b>：{@code columnDiffs} 里的值经 {@link SensitiveMasker} 处理后才落库/展示；
 * 完整行快照保留在 {@code srcRowJson/tgtRowJson}（仅 ADMIN 可见，供修复回滚取证）。
 */
public class RowDiffer {

    private static final Logger log = LoggerFactory.getLogger(RowDiffer.class);

    /** 下钻时单次流式读的 fetchSize。 */
    private static final int FETCH_SIZE = 2000;

    private final JdbcReader reader;
    private final RowHashBuilder hashBuilder;

    /**
     * 构造。
     *
     * @param normalizer 归一化器（必须与 {@code ChecksumCalculator} 共用同一实例）
     */
    public RowDiffer(Normalizer normalizer) {
        this(new JdbcReader(), new RowHashBuilder(normalizer));
    }

    /**
     * 构造（测试可注入 reader）。
     *
     * @param reader      流式读取器
     * @param hashBuilder 行哈希构建器（提供共用的 Normalizer）
     */
    public RowDiffer(JdbcReader reader, RowHashBuilder hashBuilder) {
        this.reader = reader == null ? new JdbcReader() : reader;
        this.hashBuilder = hashBuilder;
    }

    /**
     * 下钻一个块（借还连接由本方法负责）。
     *
     * @param src   源端句柄
     * @param tgt   目标端句柄
     * @param table 表元数据（源端结构；目标端按同名同序读取）
     * @param block 块区间
     * @param n     归一化器
     * @return 差异记录（{@code id=0, jobId=0, blockId=0}，由引擎补齐）
     */
    public List<DiffRecord> diff(DataSourceHandle src, DataSourceHandle tgt, TableMeta table,
                                 PkRange block, Normalizer n) {
        Connection srcConn = null;
        Connection tgtConn = null;
        try {
            srcConn = src.connection();
            tgtConn = tgt.connection();
            return diff(srcConn, src.dialect(), tgtConn, tgt.dialect(), table, block, 0L, 0L, 0L, n);
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.COMPARE_BLOCK_FAILED, e,
                    "下钻取连接失败：" + table.qualifiedName() + " " + block);
        } finally {
            closeQuietly(tgtConn);
            closeQuietly(srcConn);
        }
    }

    /**
     * 下钻一个块（连接由调用方提供，<b>不会</b>被关闭）。
     *
     * @param srcConn     源连接
     * @param srcDialect  源方言
     * @param tgtConn     目标连接
     * @param tgtDialect  目标方言
     * @param table       表元数据
     * @param block       块区间
     * @param jobId       作业 ID
     * @param blockId     块 ID
     * @param mappingId   表映射 ID
     * @param n           归一化器
     * @return 差异记录
     */
    public List<DiffRecord> diff(Connection srcConn, Dialect srcDialect, Connection tgtConn,
                                 Dialect tgtDialect, TableMeta table, PkRange block, long jobId,
                                 long blockId, long mappingId, Normalizer n) {
        Normalizer normalizer = n == null ? hashBuilder.normalizer() : n;
        List<ColumnMeta> columns = table.getColumns();
        Map<String, Map<String, Object>> sourceRows =
                readRows(srcConn, srcDialect, table, block, normalizer);
        Map<String, Map<String, Object>> targetRows =
                readRows(tgtConn, tgtDialect, table, block, normalizer);

        List<DiffRecord> diffs = new ArrayList<DiffRecord>();
        // ① 仅源端有 + 同主键逐列比较（保持源端顺序，结果稳定便于对比测试）
        for (Map.Entry<String, Map<String, Object>> entry : sourceRows.entrySet()) {
            Map<String, Object> srcRow = entry.getValue();
            Map<String, Object> tgtRow = targetRows.get(entry.getKey());
            if (tgtRow == null) {
                diffs.add(record(jobId, blockId, mappingId, table, srcRow, null,
                        DiffType.ONLY_IN_SOURCE, new LinkedHashMap<String, Map<String, String>>(),
                        normalizer));
                continue;
            }
            Map<String, Map<String, String>> columnDiffs =
                    compareColumns(srcRow, tgtRow, columns, normalizer);
            if (!columnDiffs.isEmpty()) {
                diffs.add(record(jobId, blockId, mappingId, table, srcRow, tgtRow,
                        DiffType.VALUE_DIFF, columnDiffs, normalizer));
            }
        }
        // ② 仅目标端有（源端没有的键）
        for (Map.Entry<String, Map<String, Object>> entry : targetRows.entrySet()) {
            if (!sourceRows.containsKey(entry.getKey())) {
                diffs.add(record(jobId, blockId, mappingId, table, null, entry.getValue(),
                        DiffType.ONLY_IN_TARGET, new LinkedHashMap<String, Map<String, String>>(),
                        normalizer));
            }
        }
        log.debug("下钻完成 table={} block={} diffs={}", table.qualifiedName(), block, diffs.size());
        return diffs;
    }

    /**
     * 流式读取块内全部行并按归一化主键建索引。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      表元数据
     * @param block      块区间
     * @param normalizer 归一化器
     * @return 主键文本 → 行
     */
    private Map<String, Map<String, Object>> readRows(Connection connection, Dialect dialect,
                                                      TableMeta table, PkRange block,
                                                      Normalizer normalizer) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<String, Map<String, Object>>();
        List<ColumnMeta> columns = table.getColumns();
        reader.read(connection, dialect, table, block, ReadOptions.of(FETCH_SIZE),
                (values, rowIndex) -> {
                    Map<String, Object> row = hashBuilder.rowOf(values, columns);
                    rows.put(pkKey(row, table, normalizer), row);
                });
        return rows;
    }

    /**
     * 归一化主键文本（多列用 0x02 连接，与哈希分隔符一致）。
     *
     * @param row        行
     * @param table      表元数据
     * @param normalizer 归一化器
     * @return 主键文本；无主键表退化为整行哈希
     */
    private static String pkKey(Map<String, Object> row, TableMeta table, Normalizer normalizer) {
        List<String> pkNames = table.primaryKeyNames();
        if (pkNames.isEmpty()) {
            return normalizer.hashRow(row, table.getColumns());
        }
        StringBuilder sb = new StringBuilder(64);
        for (String name : pkNames) {
            ColumnMeta column = table.column(name).orElse(null);
            sb.append(normalizer.canonicalText(Normalizer.lookup(row, name), column))
                    .append('\u0002');
        }
        return sb.toString();
    }

    /**
     * 逐列比较（<b>不短路</b>：全部差异列都要列出）。
     *
     * @param srcRow     源行
     * @param tgtRow     目标行
     * @param columns    列
     * @param normalizer 归一化器
     * @return 列名 → {src, tgt}（已脱敏）
     */
    private Map<String, Map<String, String>> compareColumns(Map<String, Object> srcRow,
                                                            Map<String, Object> tgtRow,
                                                            List<ColumnMeta> columns,
                                                            Normalizer normalizer) {
        Map<String, Map<String, String>> diffs = new LinkedHashMap<String, Map<String, String>>();
        for (ColumnMeta column : columns) {
            Object srcNormalized = normalizer.normalize(Normalizer.lookup(srcRow, column.getName()),
                    column);
            Object tgtNormalized = normalizer.normalize(Normalizer.lookup(tgtRow, column.getName()),
                    column);
            if (sameValue(srcNormalized, tgtNormalized, normalizer)) {
                continue;
            }
            Map<String, String> pair = new LinkedHashMap<String, String>(2);
            pair.put("src", SensitiveMasker.mask(column.getName(),
                    display(srcNormalized, normalizer)));
            pair.put("tgt", SensitiveMasker.mask(column.getName(),
                    display(tgtNormalized, normalizer)));
            diffs.put(column.getName(), pair);
        }
        return diffs;
    }

    /**
     * 归一化值是否相等（P8 容差只在这里生效）。
     *
     * @param a          源归一化值
     * @param b          目标归一化值
     * @param normalizer 归一化器（提供 {@code floatTolerance}）
     * @return true 表示视为相等
     */
    static boolean sameValue(Object a, Object b, Normalizer normalizer) {
        if (a == null || b == null) {
            return a == b;
        }
        double eps = normalizer.options().getFloatTolerance();
        if (a instanceof Double && b instanceof Double) {
            double x = ((Double) a).doubleValue();
            double y = ((Double) b).doubleValue();
            if (Double.compare(x, y) == 0) {
                return true;
            }
            return eps > 0.0d && Math.abs(x - y) <= eps;
        }
        if (a instanceof BigDecimal && b instanceof BigDecimal) {
            return ((BigDecimal) a).compareTo((BigDecimal) b) == 0;
        }
        if (a instanceof Normalizer.LobValue || b instanceof Normalizer.LobValue) {
            return a.equals(b);
        }
        return a.equals(b);
    }

    /**
     * 展示文本（截断的 LOB 显式标注 TRUNCATED）。
     *
     * @param normalized 归一化值
     * @param normalizer 归一化器
     * @return 展示文本
     */
    private static String display(Object normalized, Normalizer normalizer) {
        if (normalized instanceof Normalizer.LobValue) {
            return ((Normalizer.LobValue) normalized).displayText();
        }
        return normalizer.textOf(normalized);
    }

    /**
     * 组装一条差异记录（主键 JSON / 主键哈希 / 完整行快照）。
     *
     * @param jobId       作业 ID
     * @param blockId     块 ID
     * @param mappingId   表映射 ID
     * @param table       表元数据
     * @param srcRow      源行（可 null）
     * @param tgtRow      目标行（可 null）
     * @param type        差异类型
     * @param columnDiffs 列差异
     * @param normalizer  归一化器
     * @return 差异记录（id=0）
     */
    private static DiffRecord record(long jobId, long blockId, long mappingId, TableMeta table,
                                     Map<String, Object> srcRow, Map<String, Object> tgtRow,
                                     DiffType type, Map<String, Map<String, String>> columnDiffs,
                                     Normalizer normalizer) {
        Map<String, Object> present = srcRow != null ? srcRow : tgtRow;
        List<Object> pkTuple = new ArrayList<Object>();
        for (String pkName : table.primaryKeyNames()) {
            pkTuple.add(Normalizer.lookup(present, pkName));
        }
        long pkHash = HashUtil.pkHash(pkTuple);
        return new DiffRecord(0L, jobId, blockId, mappingId,
                JsonUtil.toJson(pkTupleToMap(table.primaryKeyNames(), pkTuple)), pkHash, type,
                columnDiffs,
                srcRow == null ? null : JsonUtil.toJson(srcRow),
                tgtRow == null ? null : JsonUtil.toJson(tgtRow));
    }

    /**
     * 主键元组 → 可读 Map（JSON 里带列名，便于人工定位）。
     *
     * @param pkNames 主键列名
     * @param pkTuple 主键值
     * @return 有序 Map
     */
    private static Map<String, Object> pkTupleToMap(List<String> pkNames, List<Object> pkTuple) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pkNames.size(); i++) {
            map.put(pkNames.get(i), i < pkTuple.size() ? pkTuple.get(i) : null);
        }
        return map;
    }

    /**
     * 静默关闭连接。
     *
     * @param connection 连接
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
}
