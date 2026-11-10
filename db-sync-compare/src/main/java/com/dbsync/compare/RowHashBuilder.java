package com.dbsync.compare;

import com.dbsync.common.model.ColumnMeta;
import java.util.List;
import java.util.Map;

/**
 * 行哈希构建器（T34）。
 *
 * <p><b>K4 门禁</b>：本类<b>只</b>接受构造器注入的 {@link Normalizer}，
 * 内部不得出现任何归一化逻辑、也不得出现 {@code new Normalizer(...)}。
 * 理由见 {@link Normalizer} 类注释：哈希与逐列比较必须用同一个实例、同一套规则，
 * 否则"哈希说有差异、比较说没差异"，误报率 100%。
 *
 * <p>本类无状态、线程安全，可被多块并发复用。
 */
public final class RowHashBuilder {

    private final Normalizer normalizer;

    /**
     * 构造（唯一的构造方式）。
     *
     * @param normalizer 归一化器（不得为 null，且必须与 {@link RowDiffer} 共用同一实例）
     */
    public RowHashBuilder(Normalizer normalizer) {
        if (normalizer == null) {
            throw new IllegalArgumentException("RowHashBuilder 必须注入 Normalizer（禁止内部自建）");
        }
        this.normalizer = normalizer;
    }

    /**
     * 计算行哈希。
     *
     * @param row  行数据（键大小写不敏感）
     * @param cols 参与哈希的列
     * @return 32 位十六进制 md5
     */
    public String hash(Map<String, Object> row, List<ColumnMeta> cols) {
        return normalizer.hashRow(row, cols);
    }

    /**
     * @return 注入的归一化器（便于断言两处共用同一实例）
     */
    public Normalizer normalizer() {
        return normalizer;
    }

    /**
     * 把流式读出的列值数组转成"列名 → 值"的行（{@code JdbcReader} 的回调签名是数组）。
     *
     * <p>数组顺序 = {@code TableMeta.columnNames()}，与传入的列列表一一对应；
     * 越界的尾列忽略（防御性：元数据与结果集列数不一致时不应抛异常打断整块比对）。
     *
     * @param values 列值数组
     * @param cols   列元数据
     * @return 行（保持列顺序）
     */
    public java.util.Map<String, Object> rowOf(Object[] values, List<ColumnMeta> cols) {
        java.util.Map<String, Object> row =
                new java.util.LinkedHashMap<String, Object>(cols.size() * 2);
        for (int i = 0; i < cols.size(); i++) {
            row.put(cols.get(i).getName(), i < values.length ? values[i] : null);
        }
        return row;
    }
}
