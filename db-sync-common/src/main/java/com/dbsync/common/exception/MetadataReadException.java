package com.dbsync.common.exception;

/**
 * 元数据读取失败（系统视图查询异常、表不存在、权限不足）。
 */
public class MetadataReadException extends DbSyncException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造。
     *
     * @param cause 根因
     * @param args  消息模板参数
     */
    public MetadataReadException(Throwable cause, Object... args) {
        super(ErrorCode.PROBE_FAILED, cause, args);
    }
}
