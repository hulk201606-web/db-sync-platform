package com.dbsync.api.dto.cutover;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建切流计划请求（T41）。
 *
 * @param taskId         任务 ID
 * @param name           计划名称
 * @param grayRatio      读灰度百分比（1~100）
 * @param writeGrayRatio 写灰度百分比（0~100）
 * @param comparePassRate 比对通过率阈值（可空 → 默认 0.9999）
 * @param lagP95Ms       增量延迟 P95 阈值（可空 → 默认 60000）
 * @param dualWriteFailRate 双写失败率阈值（可空 → 默认 0.001）
 * @param openDeadLetters 死信阈值（可空 → 默认 0）
 */
public record CutoverPlanCreateRequest(
        @NotNull(message = "taskId 不能为空")
        Long taskId,
        @NotBlank(message = "计划名称不能为空")
        String name,
        @Min(value = 1, message = "读灰度需 ≥1")
        @Max(value = 100, message = "读灰度需 ≤100")
        Integer grayRatio,
        @Min(value = 0, message = "写灰度需 ≥0")
        @Max(value = 100, message = "写灰度需 ≤100")
        Integer writeGrayRatio,
        Double comparePassRate,
        Long lagP95Ms,
        Double dualWriteFailRate,
        Long openDeadLetters) {
}
