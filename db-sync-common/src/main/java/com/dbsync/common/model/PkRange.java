package com.dbsync.common.model;

import com.dbsync.common.enums.RangeKind;
import java.util.Comparator;

/**
 * 主键区间，语义固定为 <b>左开右闭</b> {@code (start, end]}（与 Oracle 习惯一致）。
 *
 * <p>{@code null} 表示该端无界：{@code start == null} 表示 -∞，{@code end == null} 表示 +∞。
 *
 * <p><b>为什么必须写清</b>：SQL 层由 {@code Dialect.buildRangePredicate} 生成
 * {@code pk <= ?} 或 {@code pk < ?}，若模型层与 SQL 层对"开闭"理解相反，
 * 会造成分片区间重叠或漏行（重复迁移 / 静默漏数据），且极难排查。
 * 本类只提供**唯一**语义：{@code start < pk <= end}。
 */
public final class PkRange {

    private final Object start;
    private final Object end;
    private final RangeKind kind;

    /**
     * 构造。
     *
     * @param start 下界（不含），null 表示无界
     * @param end   上界（含），null 表示无界
     * @param kind  区间类型，null 视为 NUMERIC
     */
    public PkRange(Object start, Object end, RangeKind kind) {
        this.start = start;
        this.end = end;
        this.kind = kind == null ? RangeKind.NUMERIC : kind;
    }

    /**
     * 无界区间（全表）。
     *
     * @param kind 区间类型
     * @return {@code (null, null]}
     */
    public static PkRange unbounded(RangeKind kind) {
        return new PkRange(null, null, kind);
    }

    /** @return 下界（不含），可能为 null */
    public Object getStart() {
        return start;
    }

    /** @return 上界（含），可能为 null */
    public Object getEnd() {
        return end;
    }

    /** @return 区间类型 */
    public RangeKind getKind() {
        return kind;
    }

    /** @return true 表示下界无界 */
    public boolean isLeftOpen() {
        return start == null;
    }

    /** @return true 表示上界无界 */
    public boolean isRightOpen() {
        return end == null;
    }

    /** @return true 表示两端都无界 */
    public boolean isUnbounded() {
        return start == null && end == null;
    }

    /**
     * 判断主键是否落在 {@code (start, end]} 内。
     *
     * @param pk  待判定主键
     * @param cmp 比较器（由调用方按主键类型提供）
     * @return true 表示命中
     */
    public boolean contains(Object pk, Comparator<Object> cmp) {
        if (pk == null) {
            return false;
        }
        if (start != null && cmp.compare(start, pk) >= 0) {
            return false;
        }
        if (end != null && cmp.compare(pk, end) > 0) {
            return false;
        }
        return true;
    }

    /**
     * 生成下一片：把本片的 end 作为新片的 start。
     *
     * @param newEnd 新片上界
     * @return 新区间
     */
    public PkRange next(Object newEnd) {
        return new PkRange(this.end, newEnd, this.kind);
    }

    @Override
    public String toString() {
        return "(" + (start == null ? "-inf" : start) + ", " + (end == null ? "+inf" : end) + "]";
    }
}
