package com.dbsync.api.dto.mapping;

import java.util.List;

/**
 * 自动生成映射请求（PRD MAP-01）。
 *
 * @param schema           源 schema（空则用数据源默认）
 * @param tables           指定表（空则全库发现）
 * @param targetSchema     目标 schema（空则用目标数据源默认）
 * @param includeIndexes   是否生成索引 DDL
 * @param includeComments  是否生成注释 DDL
 * @param includeUniqueKeys 是否包含唯一键
 * @param dropTableIfExists 是否先 DROP（危险，仅 DEV 建议）
 * @param overwrite        已存在映射时是否重建（默认 false → 冲突报错）
 */
public record GenerateMappingRequest(String schema, List<String> tables, String targetSchema,
                                     Boolean includeIndexes, Boolean includeComments,
                                     Boolean includeUniqueKeys, Boolean dropTableIfExists,
                                     Boolean overwrite) {
}
