package com.dbsync.dialect;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.exception.DriverMissingException;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.IndexMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMeta;
import java.util.List;

/**
 * 方言单测共用的表元数据构造器（3 张典型表：单主键 / 复合主键 / 含 LOB）。
 */
public final class DialectFixtures {

    private DialectFixtures() {
    }

    /**
     * 单主键表。
     *
     * @return 表元数据
     */
    public static TableMeta singlePk() {
        List<ColumnMeta> cols = List.of(
                ColumnMeta.builder("ID", 1, "NUMBER").size(19).decimalDigits(0)
                        .nullable(false).autoIncrement(true).comment("主键").build(),
                ColumnMeta.builder("AMT", 2, "NUMBER").size(38).decimalDigits(10)
                        .nullable(true).build(),
                ColumnMeta.builder("NAME", 3, "VARCHAR2").size(64).nullable(true)
                        .comment("名称").build(),
                ColumnMeta.builder("CREATED_AT", 4, "DATE").size(7).nullable(true).build());
        return new TableMeta("APP", "T_ORDER", cols,
                new PrimaryKeyMeta("PK_T_ORDER", List.of("ID")),
                List.of(new IndexMeta("UK_ORDER_NAME", List.of("NAME"), true),
                        new IndexMeta("IDX_ORDER_AMT", List.of("AMT"), false)),
                1_000_000L, "订单表");
    }

    /**
     * 复合主键表。
     *
     * @return 表元数据
     */
    public static TableMeta compositePk() {
        List<ColumnMeta> cols = List.of(
                ColumnMeta.builder("TENANT_ID", 1, "BIGINT").nullable(false).build(),
                ColumnMeta.builder("ORDER_ID", 2, "BIGINT").nullable(false).build(),
                ColumnMeta.builder("QTY", 3, "INT").nullable(true).build());
        return new TableMeta("APP", "T_ORDER_ITEM", cols,
                new PrimaryKeyMeta("PK_ITEM", List.of("TENANT_ID", "ORDER_ID")),
                List.of(), 5000L, null);
    }

    /**
     * 含 LOB 表。
     *
     * @return 表元数据
     */
    public static TableMeta withLob() {
        List<ColumnMeta> cols = List.of(
                ColumnMeta.builder("ID", 1, "BIGINT").nullable(false).build(),
                ColumnMeta.builder("CONTENT", 2, "CLOB").lob(true).nullable(true).build(),
                ColumnMeta.builder("RAW", 3, "BLOB").lob(true).nullable(true).build());
        return new TableMeta("APP", "T_DOC", cols,
                new PrimaryKeyMeta("PK_DOC", List.of("ID")), List.of(), 100L, null);
    }

    /**
     * 验证驱动缺失消息的公共断言。
     *
     * @param ex 期望的异常
     */
    public static void assertDriverMessage(DriverMissingException ex) {
        String msg = ex.getMessage();
        if (!msg.contains("lib/") || !msg.contains("loader.path")
                || !msg.contains("classpath 中可用的 JDBC 驱动")) {
            throw new AssertionError("驱动缺失消息不完整：" + msg);
        }
    }

    /**
     * 断言 5 种方言都已注册。
     *
     * @return 注册数量
     */
    public static int registeredCount() {
        int count = 0;
        for (DbType t : DbType.values()) {
            if (DialectRegistry.get(t) != null) {
                count++;
            }
        }
        return count;
    }
}
