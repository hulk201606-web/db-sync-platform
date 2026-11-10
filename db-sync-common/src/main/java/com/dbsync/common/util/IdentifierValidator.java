package com.dbsync.common.util;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import java.util.regex.Pattern;

/**
 * 标识符白名单校验（架构 §12.2 / SEC-03，SQL 注入的第一道也是唯一一道防线）。
 *
 * <p>规则：{@code ^[A-Za-z_][A-Za-z0-9_$#]{0,127}$}，且长度 ≤ 方言 {@code maxIdentifierLength}。
 * <b>任何</b>要拼进 SQL 的表名/列名/别名都必须先过这里；值一律走 {@code PreparedStatement} 绑定。
 */
public final class IdentifierValidator {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$#]{0,127}$");

    private IdentifierValidator() {
    }

    /**
     * 校验并返回原值。
     *
     * @param identifier 标识符
     * @param maxLength  方言允许的最大长度（如 Oracle 30、MySQL 64、DB2 128）
     * @param dbType     数据库类型（用于错误上下文）
     * @return 原标识符
     * @throws DbSyncException {@code E1002} 非法标识符 / {@code E7003} 超长
     */
    public static String validate(String identifier, int maxLength, DbType dbType) {
        if (!isValid(identifier)) {
            throw new DbSyncException(ErrorCode.IDENTIFIER_INVALID, identifier)
                    .with("identifier", identifier)
                    .with("dbType", dbType == null ? "UNKNOWN" : dbType.name());
        }
        if (maxLength > 0 && identifier.length() > maxLength) {
            throw new DbSyncException(ErrorCode.IDENTIFIER_TOO_LONG, identifier)
                    .with("identifier", identifier)
                    .with("length", identifier.length())
                    .with("maxLength", maxLength);
        }
        return identifier;
    }

    /**
     * 校验（不区分方言长度）。
     *
     * @param identifier 标识符
     * @return 原标识符
     */
    public static String validate(String identifier) {
        return validate(identifier, 128, null);
    }

    /**
     * 是否为合法标识符（不抛异常，便于条件判断）。
     *
     * @param identifier 标识符
     * @return true 合法
     */
    public static boolean isValid(String identifier) {
        return identifier != null && VALID.matcher(identifier).matches();
    }
}
