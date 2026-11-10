package com.dbsync.api.dto.mapping;

import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;

/**
 * 表级冲突策略更新请求（{@code PUT /api/tasks/{taskId}/mappings/{mid}/conflict-policy}）。
 *
 * <p><b>为什么需要这个请求体</b>：PRD CDC-06 要求"三种策略按<b>表</b>可配"，
 * 而策略列此前不存在（V3 才加）。没有写入口的话该需求只能靠手工改库，
 * 等于"支持但没开放"，运维无从配置。
 *
 * <p><b>校验策略</b>：只接受 {@code SOURCE_WINS} / {@code TARGET_WINS} /
 * {@code TIMESTAMP_NEWER}（大小写不敏感）。空值与非法值都抛
 * {@link ErrorCode#PARAM_INVALID}，<b>不静默兜底</b>——猜错策略会让数据被
 * 错误的一方覆盖，而这种错误直到比对阶段才会暴露。
 *
 * @param conflictPolicy 策略文本
 */
public record MappingPolicyRequest(String conflictPolicy) {

    /**
     * 解析为枚举（唯一校验入口）。
     *
     * @return 策略
     * @throws DbSyncException 值为空或非法（{@code E1001}）
     */
    public ConflictPolicy toPolicy() {
        return ConflictPolicy.parse(conflictPolicy);
    }
}
