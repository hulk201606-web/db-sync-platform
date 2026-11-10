package com.dbsync.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.api.dto.mapping.DdlDiffView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 类型归一化规则的单元测试（PRD MAP-05）。
 *
 * <p>这些断言存在的意义只有一个：<b>锁住"不许误报"</b>。
 * 归一化规则一旦被"优化"掉（比如觉得 {@code NUMERIC} 和 {@code DECIMAL} 应该分开），
 * 差异报告会立刻变成满屏红条，而集成测试未必覆盖到每一条同义写法。
 */
class DdlDifferTest {

    @Test
    @DisplayName("同义类型不报不一致：numeric/decimal、大小写、长度单位")
    void should_reportOk_When_onlySynonymOrCaseDiffers() {
        assertEquals(DdlDiffer.STATUS_OK,
                DdlDiffer.column("amt", "NUMBER", "DECIMAL(38,10)", "NUMERIC(38,10)").status());
        assertEquals(DdlDiffer.STATUS_OK,
                DdlDiffer.column("name", "VARCHAR2", "VARCHAR(64)", "varchar(64)").status());
        assertEquals(DdlDiffer.STATUS_OK,
                DdlDiffer.column("name", "VARCHAR2", "VARCHAR(32)", "VARCHAR2(32 BYTE)").status());
        assertEquals(DdlDiffer.STATUS_OK,
                DdlDiffer.column("flag", "INT", "INTEGER", "INT").status());
        assertEquals(DdlDiffer.STATUS_OK,
                DdlDiffer.column("ts", "DATE", "DATETIME(3)", "TIMESTAMP").status());
    }

    @Test
    @DisplayName("目标更宽不报不一致（不会截断）")
    void should_reportOk_When_targetWider() {
        assertEquals(DdlDiffer.STATUS_OK,
                DdlDiffer.column("amt", "NUMBER", "DECIMAL(30,6)", "DECIMAL(38,10)").status());
        assertEquals(DdlDiffer.STATUS_OK,
                DdlDiffer.column("name", "VARCHAR2", "VARCHAR(32)", "VARCHAR(64)").status());
    }

    @Test
    @DisplayName("目标更窄报 TYPE_MISMATCH（可能截断）")
    void should_reportMismatch_When_targetNarrower() {
        DdlDiffView.ColumnDiff narrowed = DdlDiffer.column("amt", "NUMBER", "DECIMAL(38,10)",
                "DECIMAL(30,6)");
        assertEquals(DdlDiffer.STATUS_TYPE_MISMATCH, narrowed.status());
        assertTrue(narrowed.note().contains("截断"), "说明必须写清后果：" + narrowed.note());

        assertEquals(DdlDiffer.STATUS_TYPE_MISMATCH,
                DdlDiffer.column("name", "VARCHAR2", "VARCHAR(64)", "VARCHAR(16)").status());
    }

    @Test
    @DisplayName("缺长度声明时按 OK 处理（不知道不等于不一致）")
    void should_reportOk_When_lengthUnknown() {
        assertEquals(DdlDiffer.STATUS_OK,
                DdlDiffer.column("amt", "NUMBER", "DECIMAL(38,10)", "DECIMAL").status());
        assertEquals(DdlDiffer.STATUS_OK,
                DdlDiffer.column("name", "VARCHAR2", "VARCHAR(64)", "VARCHAR").status());
    }

    @Test
    @DisplayName("基础类型不同必须报不一致")
    void should_reportMismatch_When_baseTypeDiffers() {
        assertEquals(DdlDiffer.STATUS_TYPE_MISMATCH,
                DdlDiffer.column("amt", "NUMBER", "DECIMAL(38,10)", "VARCHAR(64)").status());
    }

    @Test
    @DisplayName("目标缺列 → NEW；目标多列 → EXTRA")
    void should_distinguishNewAndExtra() {
        assertEquals(DdlDiffer.STATUS_NEW,
                DdlDiffer.column("c_time", "TIMESTAMP", "DATETIME(3)", null).status());
        assertEquals(DdlDiffer.STATUS_EXTRA, DdlDiffer.extraColumn("audit_by", "VARCHAR(32)")
                .status());
    }

    @Test
    @DisplayName("normalize/sameType：归一化结果稳定且可直接比较")
    void should_normalizeAndCompare() {
        assertEquals("DECIMAL(38,10)", DdlDiffer.normalize("numeric(38, 10)"));
        assertEquals("VARCHAR(64)", DdlDiffer.normalize("varchar2(64 byte)"));
        assertEquals("TIMESTAMP", DdlDiffer.normalize("datetime"));
        assertTrue(DdlDiffer.sameType("decimal(38,10)", "NUMERIC(38,10)"));
        assertFalse(DdlDiffer.sameType("decimal(38,10)", "decimal(30,6)"));
    }
}
