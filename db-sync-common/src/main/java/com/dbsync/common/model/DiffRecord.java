package com.dbsync.common.model;

import com.dbsync.common.enums.DiffType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 差异明细（不可变）。值已脱敏（脱敏发生在 {@code RowDiffer} 生成记录之前）。
 */
public final class DiffRecord {

    private final long id;
    private final long jobId;
    private final long blockId;
    private final long tableMappingId;
    private final String pkJson;
    private final long pkHash;
    private final DiffType diffType;
    private final Map<String, Map<String, String>> columnDiffs;
    private final String srcRowJson;
    private final String tgtRowJson;

    /**
     * 构造。
     *
     * @param id             差异 ID（未落库为 0）
     * @param jobId          比对作业 ID
     * @param blockId        块 ID
     * @param tableMappingId 表映射 ID
     * @param pkJson         主键 JSON
     * @param pkHash         主键哈希
     * @param diffType       差异类型
     * @param columnDiffs    列级差异：列名 → {src, tgt}
     * @param srcRowJson     源行（脱敏，可 null）
     * @param tgtRowJson     目标行（脱敏，可 null）
     */
    public DiffRecord(long id, long jobId, long blockId, long tableMappingId, String pkJson, long pkHash,
                      DiffType diffType, Map<String, Map<String, String>> columnDiffs,
                      String srcRowJson, String tgtRowJson) {
        this.id = id;
        this.jobId = jobId;
        this.blockId = blockId;
        this.tableMappingId = tableMappingId;
        this.pkJson = pkJson;
        this.pkHash = pkHash;
        this.diffType = diffType;
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        if (columnDiffs != null) {
            for (Map.Entry<String, Map<String, String>> e : columnDiffs.entrySet()) {
                copy.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
            }
        }
        this.columnDiffs = Collections.unmodifiableMap(copy);
        this.srcRowJson = srcRowJson;
        this.tgtRowJson = tgtRowJson;
    }

    /** @return 差异 ID */
    public long getId() {
        return id;
    }

    /** @return 作业 ID */
    public long getJobId() {
        return jobId;
    }

    /** @return 块 ID */
    public long getBlockId() {
        return blockId;
    }

    /** @return 表映射 ID */
    public long getTableMappingId() {
        return tableMappingId;
    }

    /** @return 主键 JSON */
    public String getPkJson() {
        return pkJson;
    }

    /** @return 主键哈希 */
    public long getPkHash() {
        return pkHash;
    }

    /** @return 差异类型 */
    public DiffType getDiffType() {
        return diffType;
    }

    /** @return 列级差异（不可变） */
    public Map<String, Map<String, String>> getColumnDiffs() {
        return columnDiffs;
    }

    /** @return 源行 JSON（脱敏） */
    public String getSrcRowJson() {
        return srcRowJson;
    }

    /** @return 目标行 JSON（脱敏） */
    public String getTgtRowJson() {
        return tgtRowJson;
    }

    /**
     * @return 差异列名列表
     */
    public List<String> diffColumnNames() {
        return List.copyOf(new ArrayList<>(columnDiffs.keySet()));
    }

    @Override
    public String toString() {
        return "Diff[" + diffType + ",pk=" + pkJson + ",cols=" + columnDiffs.keySet() + "]";
    }
}
