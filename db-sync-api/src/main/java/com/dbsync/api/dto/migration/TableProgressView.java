package com.dbsync.api.dto.migration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单表迁移进度（MIG-06 任务 → 表 → 分片三级中的"表级"）。
 *
 * <p>{@code percent} 用"已提交行数 / 源侧行数"表达，与任务级进度（用分片完成度）
 * 刻意不同：表级进度要回答"这张表搬到哪一行的主键了"，
 * 行数口径比"分片个数"更贴合运维直觉。
 *
 * <p><b>{@code percent} 可为 null</b>：{@code source_rows} 默认 -1（未知），
 * 未采到源表行数时无意义，返回 null 让前端显示"—"，
 * 而不是 0（看起来像"一行没搬"）或 NaN（JSON 无法序列化，前端会报错）。
 *
 * <p><b>{@code rowsFromEstimate}</b>：分母的来源标记。分片全部成功但一个
 * {@code source_rows} 都没写（历史数据 / 分片仍在跑）时，服务端退回到
 * {@code sync_table_mapping.row_count_estimate}（统计信息估算值）当分母；
 * 此时 {@code percent} 仍有意义但精度较差，前端据此显示"约 xx%"，
 * 不要让估算值伪装成实测值。
 *
 * @param mappingId        表映射 ID
 * @param sourceTable      源表名
 * @param targetTable      目标表名
 * @param totalShards      分片总数
 * @param successShards    成功分片数
 * @param failedShards     失败分片数
 * @param deadShards       死信分片数
 * @param committedRows    已提交行数
 * @param sourceRows       源侧行数（≤0 表示未知）
 * @param percent          完成百分比（0~100，保留 2 位；未知为 null）
 * @param rowsFromEstimate true 表示 {@code sourceRows} 取自估算值而非实测值
 */
public record TableProgressView(long mappingId, String sourceTable, String targetTable,
                                int totalShards, int successShards, int failedShards,
                                int deadShards, long committedRows, long sourceRows,
                                Double percent, boolean rowsFromEstimate) {

    /** 百分比保留的小数位（与前端展示精度一致）。 */
    private static final int PERCENT_SCALE = 2;

    /**
     * 计算百分比（源侧行数未知时返回 null）。
     *
     * @param committedRows 已提交行数
     * @param sourceRows    源侧行数（≤0 表示未知）
     * @return 百分比；未知返回 null
     */
    public static Double percentOf(long committedRows, long sourceRows) {
        if (sourceRows <= 0L) {
            return null;
        }
        double raw = committedRows * 100d / sourceRows;
        double clamped = Math.max(0d, Math.min(100d, raw));
        double factor = Math.pow(10d, PERCENT_SCALE);
        return Double.valueOf(Math.round(clamped * factor) / factor);
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mappingId", mappingId);
        map.put("sourceTable", sourceTable);
        map.put("targetTable", targetTable);
        map.put("totalShards", totalShards);
        map.put("successShards", successShards);
        map.put("failedShards", failedShards);
        map.put("deadShards", deadShards);
        map.put("committedRows", committedRows);
        map.put("sourceRows", sourceRows);
        map.put("percent", percent);
        map.put("rowsFromEstimate", rowsFromEstimate);
        return map;
    }
}
