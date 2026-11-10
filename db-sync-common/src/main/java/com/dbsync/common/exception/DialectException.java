package com.dbsync.common.exception;

/**
 * 方言层异常：SQL 生成、类型映射、能力不支持等。
 */
public class DialectException extends DbSyncException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造。
     *
     * @param cause 根因，可为 null
     * @param args  消息模板参数
     */
    public DialectException(Throwable cause, Object... args) {
        super(ErrorCode.DIALECT_UNSUPPORTED, cause, args);
    }

    /**
     * 指定错误码构造（例如标识符超长用 E7003）。
     *
     * @param code  错误码
     * @param cause 根因，可为 null
     * @param args  消息模板参数
     */
    public DialectException(ErrorCode code, Throwable cause, Object... args) {
        super(code, cause, args);
    }
}
