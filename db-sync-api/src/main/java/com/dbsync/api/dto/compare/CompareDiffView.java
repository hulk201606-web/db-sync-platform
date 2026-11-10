package com.dbsync.api.dto.compare;

import com.dbsync.common.model.DiffRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 差异明细视图（T37；已脱敏，SEC-04）。
 *
 * @param id             差异 ID
 * @param jobId          作业 ID
 * @param blockId        块 ID
 * @param tableMappingId 表映射 ID
 * @param pkJson         主键 JSON
 * @param pkHash         主键哈希
 * @param diffType       差异类型
 * @param columnDiffs    列级差异（列名 → {src, tgt}，值已脱敏）
 * @param diffColumns    差异列名
 * @param srcRowJson     源行 JSON（已脱敏）
 * @param tgtRowJson     目标行 JSON（已脱敏）
 */
public record CompareDiffView(long id, long jobId, long blockId, long tableMappingId,
                              String pkJson, long pkHash, String diffType,
                              Map<String, Map<String, String>> columnDiffs,
                              List<String> diffColumns, String srcRowJson, String tgtRowJson) {

    /**
     * 从模型转换。
     *
     * @param record 差异记录
     * @return 视图
     */
    public static CompareDiffView of(DiffRecord record) {
        return new CompareDiffView(record.getId(), record.getJobId(), record.getBlockId(),
                record.getTableMappingId(), record.getPkJson(), record.getPkHash(),
                record.getDiffType() == null ? null : record.getDiffType().name(),
                record.getColumnDiffs(), record.diffColumnNames(), record.getSrcRowJson(),
                record.getTgtRowJson());
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("jobId", jobId);
        map.put("blockId", blockId);
        map.put("tableMappingId", tableMappingId);
        map.put("pkJson", pkJson);
        map.put("pkHash", pkHash);
        map.put("diffType", diffType);
        map.put("columnDiffs", columnDiffs);
        map.put("diffColumns", diffColumns);
        map.put("srcRowJson", srcRowJson);
        map.put("tgtRowJson", tgtRowJson);
        return map;
    }
}
