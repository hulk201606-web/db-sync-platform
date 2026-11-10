package com.dbsync.api.dto.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DDL 差异对比结果（PRD MAP-05：源结构 / 生成的 DDL / 目标库现有结构 三方比对）。
 *
 * <p>为什么需要它：{@code GET .../ddl} 只回答"我们准备建什么表"，
 * 但运维真正要判断的是"照着执行会不会出问题"——目标库已经存在这张表时，
 * 缺列（数据写不进去）、类型变窄（静默截断）、索引缺失（查询变慢）才是要处理的。
 *
 * <p><b>目标表不存在时不报错</b>：返回 {@code targetExists=false} 且所有列标记
 * {@code NEW}，让前端展示"这是一张新表"，而不是抛异常让人以为接口坏了。
 *
 * <p><b>状态取值固定四种</b>（前端按字符串分支渲染，不允许新增同义值）：
 * <ul>
 *   <li>{@code OK}：目标已有该列且类型兼容；</li>
 *   <li>{@code NEW}：目标缺该列；</li>
 *   <li>{@code TYPE_MISMATCH}：列都在，但归一化后类型不兼容（可能截断/写失败）；</li>
 *   <li>{@code EXTRA}：目标多出来的列（源没有），迁移不会写它，通常是手工加的审计列。</li>
 * </ul>
 *
 * @param mappingId    表映射 ID
 * @param sourceTable  源表名
 * @param targetTable  目标表名
 * @param targetExists 目标库是否已存在该表
 * @param generatedDdl 平台将执行的 DDL 语句（建表 + 建索引 + 注释）
 * @param columns      列级差异（按源列顺序，末尾追加 EXTRA 列）
 * @param indexes      索引级差异（只含"生成 DDL 里声明过的索引"）
 * @param summary      计数汇总（与 columns / indexes 明细逐项一致）
 */
public record DdlDiffView(long mappingId, String sourceTable, String targetTable,
                          boolean targetExists, List<String> generatedDdl,
                          List<ColumnDiff> columns, List<IndexDiff> indexes, Summary summary) {

    /**
     * 列级差异。
     *
     * @param name          列名（源列名；{@code EXTRA} 时为目标列名）
     * @param sourceType    源类型串（{@code EXTRA} 时为 null）
     * @param generatedType 生成 DDL 里的类型（{@code EXTRA} 时为 null）
     * @param targetType    目标库现有类型（{@code NEW} 时为 null）
     * @param status        {@code OK} / {@code NEW} / {@code TYPE_MISMATCH} / {@code EXTRA}
     * @param note          人话说明（为什么判定不一致），可为 null
     */
    public record ColumnDiff(String name, String sourceType, String generatedType,
                             String targetType, String status, String note) {

        /**
         * @return 有序 Map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("sourceType", sourceType);
            map.put("generatedType", generatedType);
            map.put("targetType", targetType);
            map.put("status", status);
            map.put("note", note);
            return map;
        }
    }

    /**
     * 索引级差异。
     *
     * @param name   索引名
     * @param status {@code OK}（目标已有）或 {@code MISSING}（目标缺失）
     */
    public record IndexDiff(String name, String status) {

        /**
         * @return 有序 Map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("status", status);
            return map;
        }
    }

    /**
     * 计数汇总。
     *
     * @param newColumns     目标缺列数
     * @param typeMismatch   类型不一致列数
     * @param missingIndexes 目标缺失索引数
     * @param extraColumns   目标多出的列数
     */
    public record Summary(int newColumns, int typeMismatch, int missingIndexes, int extraColumns) {

        /**
         * @return 有序 Map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("newColumns", newColumns);
            map.put("typeMismatch", typeMismatch);
            map.put("missingIndexes", missingIndexes);
            map.put("extraColumns", extraColumns);
            return map;
        }
    }

    /**
     * @return 有序 Map（直接作为接口响应体，字段顺序即前端阅读顺序）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mappingId", mappingId);
        map.put("sourceTable", sourceTable);
        map.put("targetTable", targetTable);
        map.put("targetExists", targetExists);
        map.put("generatedDdl", generatedDdl);
        List<Map<String, Object>> columnRows = new ArrayList<>(columns.size());
        for (ColumnDiff column : columns) {
            columnRows.add(column.toMap());
        }
        map.put("columns", columnRows);
        List<Map<String, Object>> indexRows = new ArrayList<>(indexes.size());
        for (IndexDiff index : indexes) {
            indexRows.add(index.toMap());
        }
        map.put("indexes", indexRows);
        map.put("summary", summary.toMap());
        return map;
    }
}
