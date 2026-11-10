package com.dbsync.api.audit;

import com.dbsync.common.enums.AuditResult;
import com.dbsync.common.util.JsonUtil;
import com.dbsync.core.port.AuditEntry;
import com.dbsync.core.port.AuditPort;
import com.dbsync.metadata.dao.AuditLogDao;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计服务（{@link AuditPort} 的生产实现）。
 *
 * <p><b>硬约束（04 文档 T42 验收 5 / 架构 §10）</b>：{@link #record(AuditEntry)} 永不抛异常。
 * 原因：切流/回滚这类动作一旦落库成功，审计写失败若抛给调用方，用户会看到"失败"而实际已生效，
 * 这是最危险的不一致。实现里 try/catch 全部异常、打 WARN（携带完整堆栈）并累加失败计数，
 * 通过 {@link #writeFailureCount()} 让运维/监控可感知。
 *
 * <p><b>脱敏（SEC-04）</b>：{@code params_digest} 落库前统一走 {@link #maskParams(Map)}，
 * 口令/令牌/密钥类字段固定写成 {@code ******}，不允许出现明文。
 */
public class AuditService implements AuditPort {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 脱敏占位。 */
    public static final String MASK = "******";

    /** 命中即脱敏的键片段（小写比较）。 */
    private static final String[] SENSITIVE_KEYS = {
        "password", "passwd", "pwd", "secret", "token", "cipher", "credential", "privatekey",
        "masterkey", "accesskey"
    };

    private final AuditLogDao auditLogDao;
    /** 审计写入失败累计次数（只增不减，供监控/排障读取；record 永不抛异常）。 */
    private final AtomicLong writeFailures = new AtomicLong(0L);

    /**
     * 构造。
     *
     * @param auditLogDao 审计 DAO
     */
    public AuditService(AuditLogDao auditLogDao) {
        this.auditLogDao = auditLogDao;
    }

    @Override
    public void record(AuditEntry entry) {
        if (entry == null) {
            return;
        }
        try {
            auditLogDao.append(entry.getActor(), entry.getActorRole(), entry.getAction(),
                    entry.getTargetType(), entry.getTargetId(), entry.getTargetName(),
                    entry.getParamsDigest(), entry.getResult(), entry.getErrorCode(),
                    entry.getIp(), entry.getUserAgent(), entry.getTraceId());
        } catch (RuntimeException e) {
            long failures = writeFailures.incrementAndGet();
            // 完整堆栈 + 明确的失败计数：既不影响主流程，也不再"只留一行 err=xxx"让人无从排障
            log.warn("审计写入失败（已忽略，不影响主流程）action={} actor={} 累计失败={}",
                    entry.getAction(), entry.getActor(), Long.valueOf(failures), e);
        }
    }

    /**
     * 审计写入失败累计次数（监控可观测点）。
     *
     * @return 自进程启动以来写入失败次数
     */
    public long writeFailureCount() {
        return writeFailures.get();
    }

    /**
     * 四维筛选查询（时间 / 人 / 对象 / 动作），T46 要求。
     *
     * @param from       起始时间（可 null）
     * @param to         结束时间（可 null）
     * @param actor      操作人（可 null / 空串）
     * @param targetType 目标类型（可 null / 空串）
     * @param targetId   目标 ID（可 null）
     * @param action     动作（可 null / 空串）
     * @param result     结果（可 null / 空串）
     * @param limit      上限
     * @return 审计行（按时间倒序）
     */
    public List<AuditLogDao.AuditLogRow> search(Instant from, Instant to, String actor,
                                                String targetType, Long targetId, String action,
                                                String result, int limit) {
        return auditLogDao.search(from, to, trimToNull(actor), trimToNull(targetType), targetId,
                trimToNull(action), trimToNull(result), limit <= 0 ? 500 : limit);
    }

    /**
     * 导出 CSV（T46 验收 2）。
     *
     * <p>复用已脱敏的 {@code params_digest}，<b>不重新查业务库</b>（SEC-04：脱敏不能通过导出绕过）。
     *
     * @param rows 审计行
     * @return CSV 文本（含表头，UTF-8 BOM 由调用方决定是否添加）
     */
    public String exportCsv(List<AuditLogDao.AuditLogRow> rows) {
        StringBuilder sb = new StringBuilder(256 + rows.size() * 96);
        sb.append("id,createdAt,actor,actorRole,action,targetType,targetId,targetName,")
                .append("result,errorCode,ip,traceId,paramsDigest\n");
        for (AuditLogDao.AuditLogRow row : rows) {
            sb.append(row.getId()).append(',')
                    .append(csv(row.getCreatedAt() == null ? "" : row.getCreatedAt().toString()))
                    .append(',')
                    .append(csv(row.getActor())).append(',')
                    .append(csv(row.getActorRole())).append(',')
                    .append(csv(row.getAction())).append(',')
                    .append(csv(row.getTargetType())).append(',')
                    .append(row.getTargetId() == null ? "" : row.getTargetId()).append(',')
                    .append(csv(row.getTargetName())).append(',')
                    .append(csv(row.getResult())).append(',')
                    .append(csv(row.getErrorCode())).append(',')
                    .append(csv(row.getIp())).append(',')
                    .append(csv(row.getTraceId())).append(',')
                    .append(csv(row.getParamsDigest())).append('\n');
        }
        return sb.toString();
    }

    /**
     * 参数脱敏并序列化为摘要。
     *
     * @param params 原始参数（顺序保留）
     * @return JSON 摘要；无参数返回 null
     */
    public static String digest(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        String json = JsonUtil.toJson(maskParams(params));
        return json.length() > 8000 ? json.substring(0, 8000) : json;
    }

    /**
     * 参数脱敏（SEC-04）。
     *
     * @param params 原始参数
     * @return 脱敏后的新 Map（不修改入参）
     */
    public static Map<String, Object> maskParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isSensitive(key)) {
                masked.put(key, value == null ? null : MASK);
            } else if (value instanceof Map) {
                masked.put(key, maskParams(castMap(value)));
            } else {
                masked.put(key, value);
            }
        }
        return masked;
    }

    /**
     * 判定键是否敏感。
     *
     * @param key 键
     * @return 敏感返回 true
     */
    public static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String fragment : SENSITIVE_KEYS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 便捷构造：操作成功审计条目。
     *
     * @param actor      操作人
     * @param role       角色
     * @param action     动作
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @param params     参数（自动脱敏）
     * @return 审计条目
     */
    public static AuditEntry success(String actor, String role, String action, String targetType,
                                     Long targetId, Map<String, Object> params) {
        return new AuditEntry(actor, role, action, targetType, targetId, null, digest(params),
                AuditResult.SUCCESS, null, null, null, null, Instant.now(), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.indexOf(',') >= 0 || escaped.indexOf('\n') >= 0
                || escaped.indexOf('"') >= 0) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 行转 Map（供 Controller 组装响应，避免 DTO 与 DAO 行耦合）。
     *
     * @param row 审计行
     * @return 有序 Map
     */
    public static Map<String, Object> toView(AuditLogDao.AuditLogRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("actor", row.getActor());
        view.put("actorRole", row.getActorRole());
        view.put("action", row.getAction());
        view.put("targetType", row.getTargetType());
        view.put("targetId", row.getTargetId());
        view.put("targetName", row.getTargetName());
        view.put("result", row.getResult());
        view.put("errorCode", row.getErrorCode());
        view.put("ip", row.getIp());
        view.put("traceId", row.getTraceId());
        view.put("paramsDigest", row.getParamsDigest());
        view.put("createdAt", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
        return view;
    }

    /**
     * 批量转视图。
     *
     * @param rows 审计行
     * @return 视图列表
     */
    public static List<Map<String, Object>> toViews(List<AuditLogDao.AuditLogRow> rows) {
        List<Map<String, Object>> views = new ArrayList<>(rows.size());
        for (AuditLogDao.AuditLogRow row : rows) {
            views.add(toView(row));
        }
        return views;
    }
}
