// ADDED: 04 文档 T17 的 DataSourceHandle 签名里用到 `SqlFunction<Connection,T>`，但未在文件清单中
// 给出该接口的位置。放在 datasource 包内（它只服务于连接借还场景），不做通用函数库。
package com.dbsync.core.datasource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 在连接上执行的函数（自动借还）。
 *
 * @param <T> 返回值类型
 */
@FunctionalInterface
public interface SqlFunction<T> {

    /**
     * 执行。
     *
     * @param connection 连接（由调用方保证有效，函数内<b>不得</b>关闭）
     * @return 结果
     * @throws SQLException 数据库异常
     */
    T apply(Connection connection) throws SQLException;
}
