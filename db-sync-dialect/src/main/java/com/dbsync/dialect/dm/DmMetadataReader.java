package com.dbsync.dialect.dm;

import com.dbsync.dialect.oracle.OracleMetadataReader;

/**
 * DM8 元数据读取器：DM 兼容 Oracle 的 {@code ALL_TABLES} 视图，因此直接复用 Oracle 实现。
 *
 * <p>注意：部分 DM 版本的 {@code ALL_TAB_COLUMNS} 列名与 Oracle 有细微差异，
 * 本实现走 JDBC 标准元数据（{@code DatabaseMetaData}）规避该差异，只把行数估算交给
 * {@code ALL_TABLES.NUM_ROWS}。
 */
public final class DmMetadataReader extends OracleMetadataReader {
}
