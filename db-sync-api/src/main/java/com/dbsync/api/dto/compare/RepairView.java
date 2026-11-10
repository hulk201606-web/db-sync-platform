package com.dbsync.api.dto.compare;

import com.dbsync.compare.RepairResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修复结果视图。
 *
 * @param jobId       作业 ID
 * @param total       请求条数
 * @param applied     成功条数
 * @param skipped     跳过条数
 * @param failed      失败条数
 * @param backupTable 备份表名（{@code fix-source} 专用）
 * @param failedIds   失败 ID
 * @param messages    说明
 * @param success     是否全部成功
 */
public record RepairView(long jobId, int total, int applied, int skipped, int failed,
                          String backupTable, List<Long> failedIds, List<String> messages,
                          boolean success) {

    /**
     * 从服务结果转换。
     *
     * @param result 修复结果
     * @return 视图
     */
    public static RepairView of(RepairResult result) {
        return new RepairView(result.getJobId(), result.getTotal(), result.getApplied(),
                result.getSkipped(), result.getFailed(), result.getBackupTable(),
                result.getFailedIds(), result.getMessages(), result.isSuccess());
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", jobId);
        map.put("total", total);
        map.put("applied", applied);
        map.put("skipped", skipped);
        map.put("failed", failed);
        map.put("backupTable", backupTable);
        map.put("failedIds", failedIds);
        map.put("messages", messages);
        map.put("success", success);
        return map;
    }
}
