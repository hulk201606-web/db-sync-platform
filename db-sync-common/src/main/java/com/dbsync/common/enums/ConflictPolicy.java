package com.dbsync.common.enums;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import java.util.Locale;

/**
 * 增量冲突解决策略（语义见 04 文档 T30 表）。
 *
 * <table border="1">
 *   <caption>三策略 × 三操作</caption>
 *   <tr><th>策略</th><th>INSERT</th><th>UPDATE</th><th>DELETE</th></tr>
 *   <tr><td>SOURCE_WINS</td><td>upsert 覆盖</td><td>upsert 覆盖</td><td>删除目标行</td></tr>
 *   <tr><td>TARGET_WINS</td><td>已存在则跳过</td><td>跳过</td><td>跳过</td></tr>
 *   <tr><td>TIMESTAMP_NEWER</td><td>upsert</td><td>commitTs 更新才应用</td><td>同 UPDATE</td></tr>
 * </table>
 */
public enum ConflictPolicy {
    /** 源端优先。 */
    SOURCE_WINS,
    /** 目标端优先。 */
    TARGET_WINS,
    /** 时间戳更新的优先。 */
    TIMESTAMP_NEWER;

    /**
     * 解析策略文本（<b>唯一</b>入口：DB 列、HTTP 请求、YAML 配置都走这里）。
     *
     * <p><b>不静默兜底</b>：非法值一律抛 {@link ErrorCode#PARAM_INVALID}，
     * 绝不"猜一个默认值继续跑"。原因：冲突策略决定了"源端还是目标端赢"，
     * 猜错的后果是数据被静默覆盖，而这种错误在比对阶段才会暴露，届时已无法回滚。
     *
     * <p>null/空串同样视为非法：{@code sync_table_mapping.conflict_policy} 是
     * {@code NOT NULL DEFAULT 'SOURCE_WINS'}，读出来为空只可能是代码/迁移出了问题，
     * 应当立刻失败而不是替它做决定。
     *
     * @param value 策略文本（大小写不敏感，允许首尾空白）
     * @return 策略
     * @throws DbSyncException 值为 null/空白或不在三种策略之内（{@code E1001}）
     */
    public static ConflictPolicy parse(String value) {
        if (value == null || value.isBlank()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "冲突策略不能为空（仅支持 SOURCE_WINS / TARGET_WINS / TIMESTAMP_NEWER）");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "无法识别的冲突策略：" + value
                            + "（仅支持 SOURCE_WINS / TARGET_WINS / TIMESTAMP_NEWER）")
                    .with("conflictPolicy", value);
        }
    }

    /**
     * 宽松解析：仅当值为 null/空白时返回 {@code fallback}，其余情况与
     * {@link #parse(String)} 完全一致（非法值仍然抛异常）。
     *
     * <p>用于"任务级策略可空 → 落到默认值"这一种合法场景，
     * 不是给非法值兜底用的。
     *
     * @param value    策略文本（可空）
     * @param fallback 值为空时使用的策略（不可为 null）
     * @return 策略
     * @throws DbSyncException 值非空但非法（{@code E1001}）
     */
    public static ConflictPolicy parseOrDefault(String value, ConflictPolicy fallback) {
        if (value == null || value.isBlank()) {
            if (fallback == null) {
                throw new DbSyncException(ErrorCode.PARAM_INVALID, "默认冲突策略不能为空");
            }
            return fallback;
        }
        return parse(value);
    }
}
