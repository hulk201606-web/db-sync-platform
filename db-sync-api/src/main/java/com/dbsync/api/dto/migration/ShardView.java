package com.dbsync.api.dto.migration;

import com.dbsync.common.model.ShardSnapshot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分片视图（MIG-06 分片级进度，前端热力图的一格）。
 *
 * <p><b>刻意不返回</b> {@code sync_shard} 的 {@code version/heartbeat_at/created_at/
 * updated_at}：这些是并发控制与运维诊断字段，热力图用不到，
 * 400 个分片 × 200 条分页时纯属带宽浪费。
 *
 * <p>主键区间 {@code pkStart/pkEnd} 用字符串（原样）而不是数字：
 * 主键可能是字符串/复合区间，强转数字会在前端解析失败；
 * 时间用 ISO-8601 字符串（null 保持 null）。
 *
 * @param shardId       分片 ID
 * @param mappingId     表映射 ID
 * @param shardNo       分片号
 * @param rangeKind     区间类型
 * @param pkStart       主键起点（可 null）
 * @param pkEnd         主键终点（可 null）
 * @param state         状态
 * @param leaseOwner    租约持有者（可 null）
 * @param leaseExpireAt 租约到期时间（ISO-8601，可 null）
 * @param lastPk        断点主键（可 null，排查"卡在哪个键"用）
 * @param committedRows 已提交行数
 * @param sourceRows    源侧行数（-1 表示未知）
 * @param attempt       已尝试次数
 * @param lastError     最近错误摘要（可 null）
 * @param startedAt     开始时间（ISO-8601，可 null）
 * @param finishedAt    结束时间（ISO-8601，可 null）
 */
public record ShardView(long shardId, long mappingId, int shardNo, String rangeKind, String pkStart,
                        String pkEnd, String state, String leaseOwner, String leaseExpireAt,
                        String lastPk, long committedRows, long sourceRows, int attempt,
                        String lastError,
                        String startedAt, String finishedAt) {

    /**
     * 从模型转换。
     *
     * @param snapshot 分片快照
     * @return 视图
     */
    public static ShardView of(ShardSnapshot snapshot) {
        return new ShardView(snapshot.getId(), snapshot.getTableMappingId(), snapshot.getShardNo(),
                snapshot.getRangeKind() == null ? null : snapshot.getRangeKind().name(),
                snapshot.getPkStart(), snapshot.getPkEnd(),
                snapshot.getState() == null ? null : snapshot.getState().name(),
                snapshot.getLeaseOwner(), iso(snapshot.getLeaseExpireAt()),
                snapshot.getLastPk(), snapshot.getCommittedRows(), snapshot.getSourceRows(), snapshot.getAttempt(),
                snapshot.getLastError(), iso(snapshot.getStartedAt()),
                iso(snapshot.getFinishedAt()));
    }

    /**
     * 时间格式化（UTC ISO-8601）。
     *
     * @param instant 时间（可 null）
     * @return 字符串；null 原样返回
     */
    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("shardId", shardId);
        map.put("mappingId", mappingId);
        map.put("shardNo", shardNo);
        map.put("rangeKind", rangeKind);
        map.put("pkStart", pkStart);
        map.put("pkEnd", pkEnd);
        map.put("state", state);
        map.put("leaseOwner", leaseOwner);
        map.put("leaseExpireAt", leaseExpireAt);
        map.put("lastPk", lastPk);
        map.put("committedRows", committedRows);
        map.put("sourceRows", sourceRows);
        map.put("attempt", attempt);
        map.put("lastError", lastError);
        map.put("startedAt", startedAt);
        map.put("finishedAt", finishedAt);
        return map;
    }
}
