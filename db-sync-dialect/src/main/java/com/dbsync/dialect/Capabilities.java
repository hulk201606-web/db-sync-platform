package com.dbsync.dialect;

/**
 * 能力声明（架构 §5.5）。
 *
 * <p>能力是"编译期就可判定的常量组合"，用 record 让 IDE 能补全、让单测能逐一断言，
 * 避免 {@code Map<String,Boolean>} 的字符串魔法。
 *
 * @param supportsSkipLocked         是否支持 {@code FOR UPDATE SKIP LOCKED}（元数据库抢占用）
 * @param supportsMerge              是否支持 {@code MERGE INTO}（Oracle/DM/DB2）
 * @param supportsOnConflict         是否支持 {@code ON CONFLICT}（PG）
 * @param supportsOnDuplicateKey     是否支持 {@code ON DUPLICATE KEY UPDATE}（MySQL）
 * @param supportsIdentityInsert     是否支持显式写入自增列
 * @param supportsIdentityRestart    是否支持重设自增起点
 * @param needsFetchSizeCursor       是否必须 autocommit=false + setFetchSize 才走游标（Oracle）
 * @param supportsCommentOn          是否支持 {@code COMMENT ON}（MySQL 为 false）
 * @param supportsMultiRowValues     是否支持 {@code INSERT ... VALUES (...),(...)}
 * @param supportsBatchGeneratedKeys 是否支持批量返回自增键
 * @param supportsStreamingBlob      是否支持流式读写大字段
 * @param caseSensitiveIdentifiers   引号内标识符是否大小写敏感（DM 的 CASE_SENSITIVE）
 * @param maxIdentifierLength        标识符最大长度
 * @param recommendedBatchSize       推荐批大小
 * @param defaultSchemaExpression    默认 schema 的 SQL 表达式
 */
public record Capabilities(
        boolean supportsSkipLocked,
        boolean supportsMerge,
        boolean supportsOnConflict,
        boolean supportsOnDuplicateKey,
        boolean supportsIdentityInsert,
        boolean supportsIdentityRestart,
        boolean needsFetchSizeCursor,
        boolean supportsCommentOn,
        boolean supportsMultiRowValues,
        boolean supportsBatchGeneratedKeys,
        boolean supportsStreamingBlob,
        boolean caseSensitiveIdentifiers,
        int maxIdentifierLength,
        int recommendedBatchSize,
        String defaultSchemaExpression) {
}
