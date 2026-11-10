package com.dbsync.core.port;

import com.dbsync.common.enums.AuditResult;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计条目（不可变值对象）。
 *
 * <p>{@code paramsDigest} <b>必须</b>是脱敏后的摘要：口令、密钥、连接串一律不得出现明文
 * （架构 §10 原则 4）。摘要生成由 {@code AuditService} 负责，本类不做二次处理。
 */
public final class AuditEntry {

    private final String actor;
    private final String actorRole;
    private final String action;
    private final String targetType;
    private final Long targetId;
    private final String targetName;
    private final String paramsDigest;
    private final AuditResult result;
    private final String errorCode;
    private final String ip;
    private final String userAgent;
    private final String traceId;
    private final Instant occurredAt;
    private final Map<String, Object> extras;

    /**
     * 构造。
     *
     * @param actor        操作人
     * @param actorRole    操作人角色
     * @param action       动作编码
     * @param targetType   目标类型
     * @param targetId     目标 ID（可 null）
     * @param targetName   目标名（可 null）
     * @param paramsDigest 参数摘要（已脱敏）
     * @param result       结果
     * @param errorCode    错误码（成功时 null）
     * @param ip           来源 IP
     * @param userAgent    UA
     * @param traceId      链路 ID
     * @param occurredAt   发生时间
     * @param extras       附加信息（可 null）
     */
    public AuditEntry(String actor, String actorRole, String action, String targetType,
                      Long targetId, String targetName, String paramsDigest, AuditResult result,
                      String errorCode, String ip, String userAgent, String traceId,
                      Instant occurredAt, Map<String, Object> extras) {
        this.actor = actor == null ? "system" : actor;
        this.actorRole = actorRole == null ? "SYSTEM" : actorRole;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetName = targetName;
        this.paramsDigest = paramsDigest;
        this.result = result == null ? AuditResult.SUCCESS : result;
        this.errorCode = errorCode;
        this.ip = ip;
        this.userAgent = userAgent;
        this.traceId = traceId;
        this.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        this.extras = extras == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(extras));
    }

    /**
     * 便捷工厂：成功条目。
     *
     * @param actor   操作人
     * @param role    角色
     * @param action  动作
     * @param target  目标类型
     * @param targetId 目标 ID
     * @return 条目
     */
    public static AuditEntry success(String actor, String role, String action, String target,
                                     Long targetId) {
        return new AuditEntry(actor, role, action, target, targetId, null, null,
                AuditResult.SUCCESS, null, null, null, null, Instant.now(), null);
    }

    /**
     * 便捷工厂：被拒绝条目。
     *
     * @param actor  操作人
     * @param role   角色
     * @param action 动作
     * @param target 目标类型
     * @param errorCode 错误码
     * @return 条目
     */
    public static AuditEntry denied(String actor, String role, String action, String target,
                                    String errorCode) {
        return new AuditEntry(actor, role, action, target, null, null, null, AuditResult.DENIED,
                errorCode, null, null, null, Instant.now(), null);
    }

    /** @return 操作人 */
    public String getActor() {
        return actor;
    }

    /** @return 操作人角色 */
    public String getActorRole() {
        return actorRole;
    }

    /** @return 动作 */
    public String getAction() {
        return action;
    }

    /** @return 目标类型 */
    public String getTargetType() {
        return targetType;
    }

    /** @return 目标 ID */
    public Long getTargetId() {
        return targetId;
    }

    /** @return 目标名 */
    public String getTargetName() {
        return targetName;
    }

    /** @return 参数摘要 */
    public String getParamsDigest() {
        return paramsDigest;
    }

    /** @return 结果 */
    public AuditResult getResult() {
        return result;
    }

    /** @return 错误码 */
    public String getErrorCode() {
        return errorCode;
    }

    /** @return 来源 IP */
    public String getIp() {
        return ip;
    }

    /** @return UA */
    public String getUserAgent() {
        return userAgent;
    }

    /** @return 链路 ID */
    public String getTraceId() {
        return traceId;
    }

    /** @return 发生时间 */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** @return 附加信息 */
    public Map<String, Object> getExtras() {
        return extras;
    }

    @Override
    public String toString() {
        return "AuditEntry[" + action + " by " + actor + " -> " + targetType + "#" + targetId
                + ", result=" + result + "]";
    }
}
