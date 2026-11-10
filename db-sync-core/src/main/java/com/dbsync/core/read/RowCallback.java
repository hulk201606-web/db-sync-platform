package com.dbsync.core.read;

import java.sql.SQLException;

/**
 * 逐行回调（流式读取的消费端）。
 *
 * <p><b>约定</b>：
 * <ul>
 *   <li>回调内<b>不得</b>关闭 ResultSet/Statement/Connection（由 {@code JdbcReader} 负责）；</li>
 *   <li>回调抛异常 → 读取立即中止，ResultSet/Statement 仍会被关闭（T19 验收 5）；</li>
 *   <li>{@code row} 是本次调用的<b>专有</b>数组，回调可以安全持有；不要期望它与下一行共享内存。</li>
 * </ul>
 */
@FunctionalInterface
public interface RowCallback {

    /**
     * 处理一行。
     *
     * @param row      列值数组（顺序 = {@code TableMeta.columnNames()}）
     * @param rowIndex 行序号（从 0 开始，全局单调递增）
     * @throws SQLException 处理失败
     */
    void onRow(Object[] row, long rowIndex) throws SQLException;
}
