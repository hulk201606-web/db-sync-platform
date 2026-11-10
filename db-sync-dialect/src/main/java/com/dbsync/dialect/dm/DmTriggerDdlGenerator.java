package com.dbsync.dialect.dm;

import com.dbsync.dialect.oracle.OracleTriggerDdlGenerator;

/**
 * DM8 触发器日志 DDL 生成器（与 Oracle 近似，差异只有标识符上限与"必须用 VALUES"）。
 *
 * <p>DM 要求：
 * <ul>
 *   <li>标识符上限 128（Oracle 11g 是 30）；</li>
 *   <li>触发器内<b>统一用 {@code VALUES}</b>，不使用 {@code INSERT ... SELECT}
 *       （部分 DM 版本不支持）；Oracle 实现本身已是 {@code VALUES} 形式，故复用。</li>
 * </ul>
 */
public final class DmTriggerDdlGenerator extends OracleTriggerDdlGenerator {

    private static final int DM_MAX_IDENTIFIER = 128;

    @Override
    public String changeLogTableName(String baseTable) {
        String suffix = "_SYNC_LOG";
        if (baseTable.length() + suffix.length() <= DM_MAX_IDENTIFIER) {
            return baseTable + suffix;
        }
        return baseTable.substring(0, Math.max(1, DM_MAX_IDENTIFIER - suffix.length())) + suffix;
    }
}
