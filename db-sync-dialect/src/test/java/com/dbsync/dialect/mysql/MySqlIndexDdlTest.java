package com.dbsync.dialect.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.IndexMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.MappingOptions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MAP-03 / MAP-05 回归：MySQL 目标端的索引 DDL 完整性。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li>1 个唯一键 + 2 个非唯一索引 → 生成 <b>2</b> 条 {@code CREATE INDEX}，
 *       且唯一键<b>不</b>重复出现在 {@code CREATE INDEX} 里（它内联在 {@code CREATE TABLE}）；</li>
 *   <li>无索引的表不产生多余语句；</li>
 *   <li>多行 DDL 按顺序在 H2 {@code MODE=MySQL} 上可执行，
 *       执行完索引数量与源端一致（这正是 MAP-05 判 {@code MISSING} 的前提）。</li>
 * </ol>
 */
class MySqlIndexDdlTest {

    private MySqlDialect dialect;

    @BeforeEach
    void setUpDialect() {
        dialect = new MySqlDialect();
    }

    @Test
    @DisplayName("1 唯一键 + 2 非唯一索引 → 2 条 CREATE INDEX，唯一键不重复")
    void should_emitTwoCreateIndex_When_oneUniqueTwoPlain() {
        TableMeta table = indexedTable();
        String createTable = dialect.createTableDdl(table, MappingOptions.defaults());
        List<String> indexDdl = dialect.createIndexDdl(table, MappingOptions.defaults());

        assertThat(indexDdl).hasSize(2);
        assertThat(indexDdl.get(0)).isEqualTo("CREATE INDEX `idx_amt` ON `t_order` (`amt`)");
        assertThat(indexDdl.get(1))
                .isEqualTo("CREATE INDEX `idx_name_amt` ON `t_order` (`name`, `amt`)");
        // 唯一键只在建表语句里出现一次，且不带 CREATE INDEX 形式
        assertThat(createTable).contains("UNIQUE KEY `uk_order_name` (`name`)");
        assertThat(indexDdl).noneMatch(sql -> sql.contains("uk_order_name"));
        assertThat(indexDdl).noneMatch(sql -> sql.toUpperCase().contains("UNIQUE"));
    }

    @Test
    @DisplayName("无索引表 → 不产生任何 CREATE INDEX")
    void should_emitNothing_When_noIndex() {
        TableMeta table = new TableMeta(null, "t_plain",
                List.of(ColumnMeta.builder("id", 1, "BIGINT").nullable(false).build(),
                        ColumnMeta.builder("name", 2, "VARCHAR").size(32).nullable(true).build()),
                new PrimaryKeyMeta("pk_t_plain", List.of("id")), List.of(), 0L, null);

        assertThat(dialect.createIndexDdl(table, MappingOptions.defaults())).isEmpty();
    }

    @Test
    @DisplayName("多行 DDL 按顺序在 H2 MODE=MySQL 上可执行，索引数量与源端一致")
    void should_executeInOrder_When_h2MySqlMode() throws SQLException {
        TableMeta table = indexedTable();
        List<String> statements = new ArrayList<>();
        statements.add(dialect.createTableDdl(table, MappingOptions.defaults()));
        statements.addAll(dialect.createIndexDdl(table, MappingOptions.defaults()));

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mysql_idx_ddl;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        try (Connection connection = ds.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
            // 唯一键生效：同名插入失败
            statement.execute("INSERT INTO t_order (id, amt, name) VALUES (1, 10, 'a')");
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO t_order (id, amt, name) VALUES (2, 20, 'a')"))
                    .isInstanceOf(SQLException.class);

            int indexes = 0;
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.indexes"
                            + " WHERE LOWER(table_name) = 't_order'")) {
                if (rs.next()) {
                    indexes = rs.getInt(1);
                }
            }
            // 2 个非唯一索引 + 1 个唯一键，按"索引条目"计（H2 把唯一约束也记成索引）
            assertThat(indexes).isGreaterThanOrEqualTo(3);
        }
    }

    /**
     * 测试夹具：t_order（id 主键、1 个唯一键、2 个非唯一索引）。
     *
     * @return 表元数据
     */
    private static TableMeta indexedTable() {
        List<ColumnMeta> columns = List.of(
                ColumnMeta.builder("id", 1, "BIGINT").nullable(false).build(),
                ColumnMeta.builder("amt", 2, "BIGINT").nullable(true).build(),
                ColumnMeta.builder("name", 3, "VARCHAR").size(64).nullable(true).build());
        List<IndexMeta> indexes = List.of(
                new IndexMeta("uk_order_name", List.of("name"), true),
                new IndexMeta("idx_amt", List.of("amt"), false),
                new IndexMeta("idx_name_amt", List.of("name", "amt"), false));
        return new TableMeta(null, "t_order", columns,
                new PrimaryKeyMeta("pk_t_order", List.of("id")), indexes, 0L, null);
    }
}
