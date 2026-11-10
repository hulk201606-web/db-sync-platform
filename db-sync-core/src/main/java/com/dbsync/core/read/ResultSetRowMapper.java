package com.dbsync.core.read;

import com.dbsync.common.model.ColumnMeta;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ResultSet → {@code Object[]} 映射器。
 *
 * <p>只做"驱动类型 → Java 类型"的收敛，不做任何归一化（归一化是
 * {@code Normalizer} 的唯一职责，K4：此处再写一遍就是双份真相）。
 *
 * <p>LOB 处理：{@code BLOB/CLOB/LONGVARBINARY/LONGVARCHAR} 统一读成 {@code byte[]}/{@code String}，
 * 但设 16MB 上限——超限抛异常而不是静默截断（静默截断会写坏目标库，比失败更糟）。
 * 配合 {@code ReadOptions.lobSafe()} 的 batchSize=1，内存占用可控。
 */
public class ResultSetRowMapper {

    private static final Logger log = LoggerFactory.getLogger(ResultSetRowMapper.class);

    /** 单列 LOB 的内存上限（16MB）。 */
    public static final int LOB_BYTE_LIMIT = 16 * 1024 * 1024;

    /**
     * 映射一行。
     *
     * @param rs      结果集（游标已定位到目标行）
     * @param columns 列元数据（顺序与查询列顺序一致）
     * @return 列值数组
     * @throws SQLException 读取失败（含 LOB 超限）
     */
    public Object[] map(ResultSet rs, List<ColumnMeta> columns) throws SQLException {
        Object[] row = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            ColumnMeta column = columns.get(i);
            int index = i + 1;
            row[i] = read(rs, index, column);
        }
        return row;
    }

    /**
     * 读单列。
     *
     * @param rs     结果集
     * @param index  列序号（1 基）
     * @param column 列元数据
     * @return 值
     * @throws SQLException 读取失败
     */
    private Object read(ResultSet rs, int index, ColumnMeta column) throws SQLException {
        if (!column.isLob()) {
            return rs.getObject(index);
        }
        String type = column.getTypeName() == null ? ""
                : column.getTypeName().toUpperCase(java.util.Locale.ROOT);
        if (type.contains("BLOB") || type.contains("BINARY")) {
            byte[] value = readBytes(rs, index, column.getName());
            return value;
        }
        return readString(rs, index, column.getName());
    }

    /**
     * 读二进制大对象。
     *
     * @param rs     结果集
     * @param index  列序号
     * @param column 列名（错误消息用）
     * @return 字节；NULL 返回 null
     * @throws SQLException 读取失败或超限
     */
    private byte[] readBytes(ResultSet rs, int index, String column) throws SQLException {
        Blob blob = rs.getBlob(index);
        if (blob != null) {
            try {
                long length = blob.length();
                if (length > LOB_BYTE_LIMIT) {
                    throw new SQLException("列 " + column + " 的 BLOB 长度 " + length
                            + " 超过单列上限 " + LOB_BYTE_LIMIT + " 字节：请改用流式分块迁移");
                }
                byte[] data = blob.getBytes(1L, (int) length);
                blob.free();
                return data;
            } catch (SQLException e) {
                freeQuietly(blob);
                throw e;
            }
        }
        InputStream stream = rs.getBinaryStream(index);
        if (stream == null) {
            return null;
        }
        return drain(stream, column);
    }

    /**
     * 读字符大对象。
     *
     * @param rs     结果集
     * @param index  列序号
     * @param column 列名
     * @return 字符串；NULL 返回 null
     * @throws SQLException 读取失败或超限
     */
    private String readString(ResultSet rs, int index, String column) throws SQLException {
        Clob clob = rs.getClob(index);
        if (clob != null) {
            try {
                long length = clob.length();
                if (length > LOB_BYTE_LIMIT) {
                    throw new SQLException("列 " + column + " 的 CLOB 长度 " + length
                            + " 超过单列上限 " + LOB_BYTE_LIMIT + " 字符：请改用流式分块迁移");
                }
                String data = clob.getSubString(1L, (int) length);
                clob.free();
                return data;
            } catch (SQLException e) {
                freeQuietly(clob);
                throw e;
            }
        }
        Reader reader = rs.getCharacterStream(index);
        if (reader == null) {
            return null;
        }
        return drainReader(reader, column);
    }

    /**
     * 排空输入流（带上限）。
     *
     * @param stream 输入流
     * @param column 列名
     * @return 字节
     * @throws SQLException 读取失败或超限
     */
    private byte[] drain(InputStream stream, String column) throws SQLException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        try (InputStream in = stream) {
            int read;
            int total = 0;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > LOB_BYTE_LIMIT) {
                    throw new SQLException("列 " + column + " 的二进制流超过单列上限 "
                            + LOB_BYTE_LIMIT + " 字节：请改用流式分块迁移");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new SQLException("读取列 " + column + " 的二进制流失败：" + e.getMessage(), e);
        }
    }

    /**
     * 排空字符流（带上限）。
     *
     * @param reader 字符流
     * @param column 列名
     * @return 字符串
     * @throws SQLException 读取失败或超限
     */
    private String drainReader(Reader reader, String column) throws SQLException {
        StringBuilder builder = new StringBuilder(8192);
        char[] chunk = new char[8192];
        try (Reader in = reader) {
            int read;
            int total = 0;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > LOB_BYTE_LIMIT) {
                    throw new SQLException("列 " + column + " 的字符流超过单列上限 "
                            + LOB_BYTE_LIMIT + " 字符：请改用流式分块迁移");
                }
                builder.append(chunk, 0, read);
            }
            return builder.toString();
        } catch (IOException e) {
            throw new SQLException("读取列 " + column + " 的字符流失败：" + e.getMessage(), e);
        }
    }

    /**
     * 释放 Blob（释放失败只记日志）。
     *
     * @param blob Blob
     */
    private static void freeQuietly(Blob blob) {
        try {
            blob.free();
        } catch (SQLException e) {
            log.debug("释放 Blob 失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 释放 Clob。
     *
     * @param clob Clob
     */
    private static void freeQuietly(Clob clob) {
        try {
            clob.free();
        } catch (SQLException e) {
            log.debug("释放 Clob 失败（忽略）：{}", e.getMessage());
        }
    }
}
