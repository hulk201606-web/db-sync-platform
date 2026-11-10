package com.dbsync.common.exception;

/**
 * JDBC 驱动缺失（E7001）。消息必须包含驱动类名 + 三种加载方式 + 当前可用驱动清单。
 */
public class DriverMissingException extends DbSyncException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造。
     *
     * @param driverClassName 缺失的驱动类名
     * @param detail          完整消息（由 {@code JdbcDriverLoader#buildMissingMessage} 生成）
     */
    public DriverMissingException(String driverClassName, String detail) {
        super(ErrorCode.DRIVER_MISSING, null, driverClassName + "  " + detail);
        with("driver", driverClassName);
    }
}
