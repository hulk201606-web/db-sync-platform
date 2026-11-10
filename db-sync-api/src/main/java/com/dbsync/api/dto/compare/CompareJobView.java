package com.dbsync.api.dto.compare;

import com.dbsync.metadata.dao.CompareJobDao;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 比对作业视图（T37）。
 *
 * @param jobId          作业 ID
 * @param taskId         任务 ID
 * @param tableMappingId 表映射 ID
 * @param triggerType    触发方式（MANUAL/SCHEDULED）
 * @param state          状态
 * @param totalBlocks    总块数
 * @param doneBlocks     已完成块数
 * @param diffBlocks     差异块数
 * @param totalRows      总行数
 * @param diffRows       差异行数
 * @param passRate       通过率（可 null）
 * @param verdict        结论（PASS/WARN/FAIL，可 null）
 * @param startedAt      开始时间
 * @param finishedAt     结束时间
 */
public record CompareJobView(long jobId, long taskId, Long tableMappingId, String triggerType,
                             String state, int totalBlocks, int doneBlocks, int diffBlocks,
                             long totalRows, long diffRows, Double passRate, String verdict,
                             String startedAt, String finishedAt) {

    /**
     * 从 DAO 行转换。
     *
     * @param row DAO 行
     * @return 视图
     */
    public static CompareJobView of(CompareJobDao.CompareJobRow row) {
        return new CompareJobView(row.getId(), row.getTaskId(), row.getTableMappingId(),
                row.getTriggerType(), row.getState(), row.getTotalBlocks(), row.getDoneBlocks(),
                row.getDiffBlocks(), row.getTotalRows(), row.getDiffRows(), row.getPassRate(),
                row.getVerdict(),
                row.getStartedAt() == null ? null : row.getStartedAt().toString(),
                row.getFinishedAt() == null ? null : row.getFinishedAt().toString());
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", jobId);
        map.put("taskId", taskId);
        map.put("tableMappingId", tableMappingId);
        map.put("triggerType", triggerType);
        map.put("state", state);
        map.put("totalBlocks", totalBlocks);
        map.put("doneBlocks", doneBlocks);
        map.put("diffBlocks", diffBlocks);
        map.put("totalRows", totalRows);
        map.put("diffRows", diffRows);
        map.put("passRate", passRate);
        map.put("verdict", verdict);
        map.put("startedAt", startedAt);
        map.put("finishedAt", finishedAt);
        return map;
    }
}
