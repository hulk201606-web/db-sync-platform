package com.dbsync.common.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局错误码（唯一数据源：04-impl-tasks §1.2 段位表）。
 *
 * <p>每个值包含四要素：
 * <ul>
 *   <li>{@code code}：对外错误码字符串，形如 {@code E1001}；</li>
 *   <li>{@code httpStatus}：HTTP 状态码；</li>
 *   <li>{@code retryable}：是否允许 {@code RetryTemplate} 重试；</li>
 *   <li>{@code messageTemplate}：{@link String#format} 风格的模板，占位符个数 = {@link #argCount()}。</li>
 * </ul>
 *
 * <p>段位约定：E1xxx 通用 / E2xxx 数据源 / E3xxx 任务分片 / E4xxx 增量 / E5xxx 比对 /
 * E6xxx 切流 Gate / E7xxx 驱动方言 / E8xxx 权限审计 / E9xxx 预留外部集成。
 */
public enum ErrorCode {

    // ---------------- E1xxx 通用/参数/工具 ----------------
    /** 参数非法。 */
    PARAM_INVALID("E1001", 400, false, "参数非法：%s"),
    /** 标识符非法（SQL 注入防线）。 */
    IDENTIFIER_INVALID("E1002", 400, false, "标识符非法：%s"),
    /** JSON 序列化/反序列化失败。 */
    JSON_SERIALIZE_FAILED("E1003", 500, false, "JSON 处理失败：%s"),
    /** 加解密失败。 */
    CRYPTO_FAILED("E1010", 500, false, "加解密失败：%s"),
    /** 重试耗尽。 */
    RETRY_EXHAUSTED("E1020", 500, true, "重试耗尽：%s"),
    /** 内部错误（未分类异常兜底）。 */
    INTERNAL_ERROR("E1099", 500, false, "内部错误：%s"),

    // ---------------- E2xxx 数据源/连接/池 ----------------
    /** 连接失败-认证。 */
    CONN_AUTH_FAILED("E2001", 400, false, "连接失败（认证）：%s"),
    /** 连接失败-网络。 */
    CONN_NETWORK_FAILED("E2002", 502, false, "连接失败（网络）：%s"),
    /** 数据源被任务引用，禁止删除。 */
    DATASOURCE_IN_USE("E2003", 409, false, "数据源被引用：%s"),
    /** 连接池超限（违反 0.6×max_connections 硬约束）。 */
    POOL_LIMIT_EXCEEDED("E2010", 400, false, "连接池超限：%s"),
    /** 探活失败。 */
    PROBE_FAILED("E2020", 502, false, "探活失败：%s"),
    /** 数据源不存在。 */
    DATASOURCE_NOT_FOUND("E2004", 404, false, "数据源不存在：%s"),
    /** 数据源配置非法。 */
    DATASOURCE_CONFIG_INVALID("E2005", 400, false, "数据源配置非法：%s"),

    // ---------------- E3xxx 任务/迁移/分片 ----------------
    /** 非法任务状态。 */
    TASK_STATE_INVALID("E3001", 409, false, "非法任务状态：%s"),
    /** 预检失败。 */
    PRECHECK_FAILED("E3002", 400, false, "预检失败：%s"),
    /** 分片区间非法。 */
    SHARD_RANGE_INVALID("E3003", 400, false, "分片区间非法：%s"),
    /** 乐观锁冲突。 */
    OPTIMISTIC_LOCK_CONFLICT("E3005", 409, true, "乐观锁冲突：%s"),
    /** 分片重试耗尽。 */
    SHARD_RETRY_EXHAUSTED("E3010", 500, false, "分片重试耗尽：%s"),
    /** 任务不存在。 */
    TASK_NOT_FOUND("E3004", 404, false, "任务不存在：%s"),

    // ---------------- E4xxx 增量/CDC ----------------
    /** 分组计算失败。 */
    GROUP_ASSIGN_FAILED("E4001", 500, true, "分组计算失败：%s"),
    /** 水位线回退（禁止）。 */
    WATERMARK_REGRESSION("E4002", 500, false, "水位线回退：%s"),
    /** 触发器部署失败。 */
    TRIGGER_DEPLOY_FAILED("E4003", 500, false, "触发器部署失败：%s"),
    /** 变更应用失败（可重试）。 */
    CHANGE_APPLY_FAILED("E4010", 500, true, "变更应用失败：%s"),

    // ---------------- E5xxx 比对/修复 ----------------
    /** 比对分块失败。 */
    COMPARE_BLOCK_FAILED("E5001", 500, true, "比对分块失败：%s"),
    /** 归一化规则冲突。 */
    NORMALIZE_CONFLICT("E5002", 500, false, "归一化规则冲突：%s"),
    /** 修复证据不足。 */
    REPAIR_EVIDENCE_INSUFFICIENT("E5003", 400, false, "修复证据不足：%s"),
    /** 报告生成失败。 */
    REPORT_BUILD_FAILED("E5010", 500, true, "报告生成失败：%s"),

    // ---------------- E6xxx 切流/Gate ----------------
    /** 非法状态跃迁。 */
    ILLEGAL_TRANSITION("E6001", 409, false, "非法状态跃迁：%s"),
    /** Gate 阻断。 */
    GATE_BLOCKED("E6002", 409, false, "Gate 阻断：%s"),
    /** 路由下发未确认（ack 超时）。 */
    ROUTE_ACK_TIMEOUT("E6003", 504, true, "路由下发未确认：%s"),
    /** 状态不一致需人工介入。 */
    STATE_INCONSISTENT("E6004", 409, false, "状态不一致需人工介入：%s"),
    /** 灰度规则非法。 */
    GRAY_RULE_INVALID("E6010", 400, false, "灰度规则非法：%s"),

    // ---------------- E7xxx 驱动/方言/环境 ----------------
    /** 驱动缺失。 */
    DRIVER_MISSING("E7001", 500, false, "驱动缺失：%s"),
    /** 方言不支持该能力。 */
    DIALECT_UNSUPPORTED("E7002", 400, false, "方言不支持该能力：%s"),
    /** 标识符超长。 */
    IDENTIFIER_TOO_LONG("E7003", 400, false, "标识符超长：%s"),
    /** 版本不支持。 */
    VERSION_UNSUPPORTED("E7010", 400, false, "版本/协议不支持：%s"),

    // ---------------- E8xxx 权限/审计/安全 ----------------
    /** 无权限。 */
    NO_PERMISSION("E8001", 403, false, "无权限：%s"),
    /** 需要二次确认。 */
    SECOND_CONFIRM_REQUIRED("E8002", 400, false, "需要二次确认：%s"),
    /** 审计写入失败。 */
    AUDIT_WRITE_FAILED("E8003", 500, true, "审计写入失败：%s"),
    /** 令牌无效/过期。 */
    TOKEN_INVALID("E8004", 401, false, "令牌无效或已过期：%s"),
    /** 告警发送失败。 */
    ALERT_SEND_FAILED("E8005", 502, true, "告警发送失败：%s"),

    // ---------------- E9xxx 预留（外部集成） ----------------
    /** 外部集成未实现。 */
    EXTERNAL_NOT_IMPLEMENTED("E9001", 501, false, "外部集成未实现：%s");

    private final String code;
    private final int httpStatus;
    private final boolean retryable;
    private final String messageTemplate;

    ErrorCode(String code, int httpStatus, boolean retryable, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.messageTemplate = messageTemplate;
    }

    /**
     * @return 对外错误码字符串（如 {@code E1001}）
     */
    public String code() {
        return code;
    }

    /**
     * @return 建议的 HTTP 状态码
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * @return true 表示该错误允许 {@code RetryTemplate} 重试
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * @return 消息模板
     */
    public String messageTemplate() {
        return messageTemplate;
    }

    /**
     * 模板中 {@code %s} 占位符的个数，用于校验调用方参数个数是否匹配。
     *
     * @return 占位符个数
     */
    public int argCount() {
        int count = 0;
        for (int i = 0; i + 1 < messageTemplate.length(); i++) {
            if (messageTemplate.charAt(i) == '%' && messageTemplate.charAt(i + 1) == 's') {
                count++;
                i++;
            }
        }
        return count;
    }

    /**
     * 按模板格式化消息。
     *
     * @param args 模板参数；个数不足时 {@link String#format} 会保留 {@code %s} 原样，不抛异常
     * @return 格式化后的消息
     */
    public String format(Object... args) {
        if (args == null || args.length == 0) {
            return messageTemplate;
        }
        return String.format(messageTemplate, args);
    }

    /**
     * 按错误码反查枚举。
     *
     * @param code 错误码字符串
     * @return 匹配的枚举；未匹配返回 {@code null}
     */
    public static ErrorCode ofCode(String code) {
        if (code == null) {
            return null;
        }
        for (ErrorCode ec : values()) {
            if (ec.code.equals(code)) {
                return ec;
            }
        }
        return null;
    }

    /**
     * 段位数字（1000 段），用于单测校验段位与归属一致。
     *
     * @return 段位，如 {@code E1001 → 1000}
     */
    public int segment() {
        return Integer.parseInt(code.substring(1)) / 1000 * 1000;
    }

    /**
     * @return 不可变的 code → 枚举 映射，便于反查
     */
    public static Map<String, ErrorCode> index() {
        Map<String, ErrorCode> map = new LinkedHashMap<>();
        for (ErrorCode ec : values()) {
            map.put(ec.code, ec);
        }
        return Collections.unmodifiableMap(map);
    }
}
