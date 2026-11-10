package com.dbsync.dialect;

import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.TriggerOptions;
import java.util.List;

/**
 * 触发器 / 变更日志表 DDL 生成 SPI（架构 §5.3）。
 *
 * <p>事务语义：触发器写日志表与业务 DML <b>同一事务</b>（不用 {@code AUTONOMOUS_TRANSACTION}），
 * 因此日志可见性 = 业务数据可见性，天然保证"日志不丢、且不记录未提交变更"。
 */
public interface TriggerDdlGenerator {

    /**
     * 变更日志表名。
     *
     * @param baseTable 业务表名
     * @return 日志表名（按方言长度规则截断）
     */
    String changeLogTableName(String baseTable);

    /**
     * 建变更日志表 DDL。
     *
     * @param t    业务表元数据
     * @param opts 触发器选项
     * @return 语句列表
     */
    List<String> createChangeLogTableDdl(TableMeta t, TriggerOptions opts);

    /**
     * 建触发器 DDL（INSERT/UPDATE/DELETE 各一条；PG 为函数 + 触发器两条）。
     *
     * @param t    业务表元数据
     * @param opts 触发器选项
     * @return 语句列表
     */
    List<String> createTriggerDdl(TableMeta t, TriggerOptions opts);

    /**
     * 卸载触发器（含日志表，可选保留）。
     *
     * @param t    业务表元数据
     * @param opts 触发器选项
     * @return 语句列表
     */
    List<String> dropTriggerDdl(TableMeta t, TriggerOptions opts);

    /**
     * 轮询日志表的 SQL（按 log_id 递增拉取，不锁未提交行）。
     *
     * @param quotedLogTable 已加引号的日志表名
     * @param lastLogId      上一次的最大 log_id
     * @param batchSize      批大小
     * @return SQL（已内联数值，不含用户输入；表名已加引号）
     */
    String pollSql(String quotedLogTable, long lastLogId, int batchSize);
}
