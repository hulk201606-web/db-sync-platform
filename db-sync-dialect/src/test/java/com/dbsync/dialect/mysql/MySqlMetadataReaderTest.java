package com.dbsync.dialect.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MySQL 元数据读取的「参数位」回归测试。
 *
 * <p><b>背景</b>：2026-11-10 MySQL→PostgreSQL 真库 E2E 中，映射生成把实例里<b>所有库</b>的表
 * 都读了进来（连 `dbsync_meta` 的 `sync_column_mapping`、`cutover_plan` 都被当成业务表），
 * 根因是父类 {@code JdbcMetadataReader} 把 schema 传给 {@code DatabaseMetaData.getTables}
 * 的 <b>catalog 位</b>且默认传 {@code null}，MySQL 驱动会把 catalog=null 理解为「不限制」。
 *
 * <p>{@code MySqlMetadataReaderTest} 锁定两条契约：
 * <ul>
 *   <li>库名必须走 <b>catalog</b>（第 1 个参数）；</li>
 *   <li>schemaPattern（第 2 个参数）对 MySQL 必须为 {@code null}。</li>
 * </ul>
 *
 * <p>这类缺陷无法由 H2 测试暴露：H2 同时支持 catalog 与 schema，参数位错位后仍能返回结果。
 */
class MySqlMetadataReaderTest {

    private final MySqlMetadataReader reader = new MySqlMetadataReader();

    @Test
    @DisplayName("listTables：schema 为空时取当前连接 catalog，schemaPattern 必须为 null")
    void listTables_shouldUseConnectionCatalog_whenSchemaBlank() throws Exception {
        Connection c = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        when(c.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("MySQL");
        when(c.getCatalog()).thenReturn("dbsync_src");
        when(md.getTables(eq("dbsync_src"), isNull(), eq("%"), any(String[].class)))
                .thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertThat(reader.listTables(c, "")).isEmpty();

        verify(md).getTables(eq("dbsync_src"), isNull(), eq("%"), any(String[].class));
    }

    @Test
    @DisplayName("listTables：显式 schema 优先作为 catalog")
    void listTables_shouldPreferExplicitSchema_asCatalog() throws Exception {
        Connection c = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        when(c.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("MySQL");
        when(md.getTables(eq("other_db"), isNull(), eq("%"), any(String[].class)))
                .thenReturn(rs);
        when(rs.next()).thenReturn(false);

        reader.listTables(c, "other_db");

        verify(md).getTables(eq("other_db"), isNull(), eq("%"), any(String[].class));
    }

    @Test
    @DisplayName("loadColumns：catalog=库名、schemaPattern=null")
    void loadColumns_shouldUseCatalog_andNullSchemaPattern() throws Exception {
        Connection c = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        when(c.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("MySQL");
        when(c.getCatalog()).thenReturn("dbsync_src");
        when(md.getColumns(eq("dbsync_src"), isNull(), eq("t_order"), eq("%"))).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertThat(reader.loadColumns(c, "", "t_order")).isEmpty();

        verify(md).getColumns(eq("dbsync_src"), isNull(), eq("t_order"), eq("%"));
    }
}
