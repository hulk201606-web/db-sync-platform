package com.dbsync.dialect;

import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.IndexMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMeta;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 元数据读取 SPI（架构 §5.2）。
 *
 * <p>实现必须一次填充 {@link TableMeta} 的：columns（序号/类型/长度/精度/小数/可空/默认值/注释/
 * 自增/无符号）+ primaryKey + indexes（含唯一索引，按 index_name 聚合多列）。
 */
public interface MetadataReader {

    /**
     * 列出 schema/owner。
     *
     * @param c 连接
     * @return schema 列表
     * @throws SQLException 读取失败
     */
    List<String> listSchemas(Connection c) throws SQLException;

    /**
     * 列出表。
     *
     * @param c      连接
     * @param schema schema
     * @return 表名列表
     * @throws SQLException 读取失败
     */
    List<String> listTables(Connection c, String schema) throws SQLException;

    /**
     * 加载完整表元数据。
     *
     * @param c      连接
     * @param schema schema
     * @param table  表名
     * @return 表元数据
     * @throws SQLException 读取失败
     */
    TableMeta loadTable(Connection c, String schema, String table) throws SQLException;

    /**
     * 加载列。
     *
     * @param c      连接
     * @param schema schema
     * @param table  表名
     * @return 列列表（按物理顺序）
     * @throws SQLException 读取失败
     */
    List<ColumnMeta> loadColumns(Connection c, String schema, String table) throws SQLException;

    /**
     * 加载主键。
     *
     * @param c      连接
     * @param schema schema
     * @param table  表名
     * @return 主键（无主键返回 {@link PrimaryKeyMeta#NONE}）
     * @throws SQLException 读取失败
     */
    PrimaryKeyMeta loadPrimaryKey(Connection c, String schema, String table) throws SQLException;

    /**
     * 加载索引（含唯一索引）。
     *
     * @param c      连接
     * @param schema schema
     * @param table  表名
     * @return 索引列表
     * @throws SQLException 读取失败
     */
    List<IndexMeta> loadIndexes(Connection c, String schema, String table) throws SQLException;

    /**
     * 估算行数（统计信息，失败返回 -1，由调用方降级为抽样 COUNT）。
     *
     * @param c      连接
     * @param schema schema
     * @param table  表名
     * @return 估算行数，-1 表示未知
     * @throws SQLException 读取失败
     */
    long estimateRows(Connection c, String schema, String table) throws SQLException;
}
