package com.dbsync.compare;

/**
 * 敏感列脱敏器（// ADDED：04 文档 T36 验收要求"手机号列在 {@code DiffRecord} 中已脱敏"，
 * 但 common 未列脱敏组件；为不改动 common 的模型契约，脱敏能力收敛在本模块内）。
 *
 * <p><b>作用范围</b>：只作用于 {@code DiffRecord.columnDiffs}（报告展示层）。
 * {@code DiffRecord.srcRowJson/tgtRowJson} 保留完整行——这是"修复可回滚"的证据链
 * （{@code REPAIR_EVIDENCE_INSUFFICIENT}），且只在 ADMIN 权限的 API 上返回，永不渲染进 HTML。
 *
 * <p><b>识别方式</b>：按列名关键字（大小写不敏感）。生产环境如需按列级策略配置，
 * 可在映射配置里扩展；本期采用保守的"名字命中即脱敏"。
 */
public final class SensitiveMasker {

    /** 命中即脱敏的列名关键字。 */
    private static final String[] SENSITIVE_KEYS = {
        "phone", "mobile", "tel", "id_card", "idcard", "idno", "email",
        "password", "passwd", "pwd", "secret", "token", "bank_card", "card_no", "cvv"
    };

    private SensitiveMasker() {
    }

    /**
     * 列名是否敏感。
     *
     * @param columnName 列名（可为 null）
     * @return true 表示需要脱敏
     */
    public static boolean isSensitive(String columnName) {
        if (columnName == null) {
            return false;
        }
        String lower = columnName.toLowerCase(java.util.Locale.ROOT);
        for (String key : SENSITIVE_KEYS) {
            if (lower.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 脱敏单个值。
     *
     * <p>规则：长度 &gt; 8 时保留前 3 后 4，中间以 {@code ****} 替代；更短的值整体替换为
     * {@code ***}；{@code null}/空串原样返回（空值本身不泄露信息，且需保留"空 vs 非空"的可读性）。
     *
     * @param columnName 列名
     * @param value      展示文本（已归一化编码）
     * @return 脱敏文本
     */
    public static String mask(String columnName, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (!isSensitive(columnName)) {
            return value;
        }
        if (value.length() <= 8) {
            return "***";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }
}
