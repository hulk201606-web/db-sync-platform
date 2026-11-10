// ADDED: T19 的 4 个测试类共用同一张表/区间/选项，抽一个 public 夹具避免 4 份复制。
package com.dbsync.core.read;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.ReadOptions;
import java.sql.Types;
import java.util.List;

/**
 * 读测试夹具。
 */
public final class ReadFixture {

    private ReadFixture() {
    }

    /**
     * 两列表（id 主键 + name）。
     *
     * @return 表元数据
     */
    public static TableMeta table() {
        ColumnMeta id = ColumnMeta.builder("id", 0, "BIGINT").jdbcType(Types.BIGINT).size(19)
                .nullable(false).build();
        ColumnMeta name = ColumnMeta.builder("name", 1, "VARCHAR").jdbcType(Types.VARCHAR)
                .size(64).nullable(true).build();
        return TableMeta.of(null, "t_order", List.of(id, name),
                new PrimaryKeyMeta("pk_id", List.of("id")));
    }

    /**
     * 全区间。
     *
     * @return 区间 (null, null]
     */
    public static PkRange fullRange() {
        return new PkRange(null, null, RangeKind.NUMERIC);
    }

    /**
     * 默认读选项。
     *
     * @return 选项
     */
    public static ReadOptions options() {
        return ReadOptions.of(1000);
    }
}
