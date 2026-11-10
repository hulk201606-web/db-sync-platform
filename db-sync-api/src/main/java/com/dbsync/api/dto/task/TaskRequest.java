package com.dbsync.api.dto.task;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 任务新建/编辑请求（PRD ORC-01）。
 *
 * <p>{@code groupCount} 只在新建时生效：它决定增量保序的分区数，
 * 变更会使已有 watermark 的 {@code group_no} 语义错位，因此编辑时传入不同值会被拒（E1001）。
 *
 * @param name                名称
 * @param sourceDsId          源数据源
 * @param targetDsId          目标数据源
 * @param groupCount          分组数（1~256）
 * @param concurrency         迁移并发分片数
 * @param batchSize           批大小
 * @param shardRows           每分片目标行数
 * @param rateLimitRows       读限速（0 表示不限）
 * @param maxRetry            最大重试次数
 * @param incrementalMode     增量模式（SDK_WRITE/TRIGGER_LOG/TIMESTAMP_VERSION）
 * @param consumerConcurrency 增量消费并发
 */
public record TaskRequest(
        @NotBlank(message = "任务名不能为空")
        @Size(max = 128, message = "任务名长度需 ≤128")
        String name,
        @NotNull(message = "源数据源不能为空")
        Long sourceDsId,
        @NotNull(message = "目标数据源不能为空")
        Long targetDsId,
        @Min(value = 1, message = "groupCount 需 ≥1")
        @Max(value = 256, message = "groupCount 需 ≤256")
        Integer groupCount,
        @Min(value = 1, message = "并发需 ≥1")
        @Max(value = 32, message = "并发需 ≤32（架构 §13.1 硬上限）")
        Integer concurrency,
        @Min(value = 1, message = "batchSize 需 ≥1")
        @Max(value = 100000, message = "batchSize 需 ≤100000")
        Integer batchSize,
        @Min(value = 1000, message = "shardRows 需 ≥1000")
        Long shardRows,
        @Min(value = 0, message = "限速需 ≥0")
        Long rateLimitRows,
        @Min(value = 1, message = "maxRetry 需 ≥1")
        @Max(value = 20, message = "maxRetry 需 ≤20")
        Integer maxRetry,
        String incrementalMode,
        @Min(value = 1, message = "消费并发需 ≥1")
        @Max(value = 32, message = "消费并发需 ≤32")
        Integer consumerConcurrency) {
}
