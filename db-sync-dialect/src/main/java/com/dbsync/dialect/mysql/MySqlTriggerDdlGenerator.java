package com.dbsync.dialect.mysql;

import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.TriggerOptions;
import com.dbsync.dialect.TriggerDdlGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * MySQL 触发器日志 DDL 生成器。
 *
 * <p>三个坑（架构 §5.3）：
 * <ol>
 *   <li>一个触发器只能挂<b>一个</b>事件，因此必须建 3 个触发器（AI/AU/AD）；</li>
 *   <li>触发器里读不到提交时间，只能用 {@code NOW(3)}（应用层再用 {@code commit_ts} 做延迟基准）；</li>
 *   <li>日志写入与业务 DML 同事务，因此业务回滚时日志一起回滚 —— 这正是我们想要的（不记录未提交变更）。</li>
 * </ol>
 */
public final class MySqlTriggerDdlGenerator implements TriggerDdlGenerator {

    private static final int MAX_IDENTIFIER = 64;

    @Override
    public String changeLogTableName(String baseTable) {
        String suffix = "_SYNC_LOG";
        String name = baseTable + suffix;
        if (name.length() > MAX_IDENTIFIER) {
            // 保留后缀，截断业务表名部分，避免不同表截断后撞名
            int keep = MAX_IDENTIFIER - suffix.length();
            name = baseTable.substring(0, Math.max(1, keep)) + suffix;
        }
        return name;
    }

    @Override
    public List<String> createChangeLogTableDdl(TableMeta t, TriggerOptions opts) {
        String log = quote(changeLogTableName(t.getTableName()));
        String ddl = "CREATE TABLE IF NOT EXISTS " + log + " ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT, "
                + "`op` VARCHAR(8) NOT NULL, "
                + "`pk_json` VARCHAR(2048) NOT NULL, "
                + "`before_json` LONGTEXT NULL, "
                + "`after_json` LONGTEXT NULL, "
                + "`commit_ts` DATETIME(3) NOT NULL, "
                + "PRIMARY KEY (`id`))";
        String seq = "CREATE INDEX " + quote(truncate(changeLogTableName(t.getTableName()) + "_TS_IDX"))
                + " ON " + log + " (`commit_ts`)";
        return List.of(ddl, seq);
    }

    @Override
    public List<String> createTriggerDdl(TableMeta t, TriggerOptions opts) {
        TriggerOptions options = opts == null ? TriggerOptions.defaults() : opts;
        String table = quote(t.getTableName());
        String log = quote(changeLogTableName(t.getTableName()));
        String pkJson = pkJsonExpression(t, "NEW");
        String afterJson = jsonObjectExpression(t, "NEW");
        String beforeJson = jsonObjectExpression(t, "OLD");
        List<String> ddl = new ArrayList<>();
        ddl.add("CREATE TRIGGER " + quote(triggerName(t, "AI")) + " AFTER INSERT ON " + table
                + " FOR EACH ROW INSERT INTO " + log
                + " (`op`,`pk_json`,`after_json`,`commit_ts`) VALUES ('INSERT', "
                + pkJson.replace("NEW", "NEW") + ", " + afterJson + ", NOW(3))");
        ddl.add("CREATE TRIGGER " + quote(triggerName(t, "AU")) + " AFTER UPDATE ON " + table
                + " FOR EACH ROW INSERT INTO " + log
                + " (`op`,`pk_json`,`before_json`,`after_json`,`commit_ts`) VALUES ('UPDATE', "
                + pkJson + ", " + (options.isIncludeBeforeImage() ? beforeJson : "NULL")
                + ", " + afterJson + ", NOW(3))");
        ddl.add("CREATE TRIGGER " + quote(triggerName(t, "AD")) + " AFTER DELETE ON " + table
                + " FOR EACH ROW INSERT INTO " + log
                + " (`op`,`pk_json`,`before_json`,`commit_ts`) VALUES ('DELETE', "
                + pkJsonExpression(t, "OLD") + ", " + beforeJson + ", NOW(3))");
        return List.copyOf(ddl);
    }

    @Override
    public List<String> dropTriggerDdl(TableMeta t, TriggerOptions opts) {
        List<String> ddl = new ArrayList<>();
        ddl.add("DROP TRIGGER IF EXISTS " + quote(triggerName(t, "AI")));
        ddl.add("DROP TRIGGER IF EXISTS " + quote(triggerName(t, "AU")));
        ddl.add("DROP TRIGGER IF EXISTS " + quote(triggerName(t, "AD")));
        TriggerOptions options = opts == null ? TriggerOptions.defaults() : opts;
        if (options.isDropLogTableOnUninstall()) {
            ddl.add("DROP TABLE IF EXISTS " + quote(changeLogTableName(t.getTableName())));
        }
        return List.copyOf(ddl);
    }

    @Override
    public String pollSql(String quotedLogTable, long lastLogId, int batchSize) {
        int limit = batchSize <= 0 ? 500 : Math.min(batchSize, 5000);
        return "SELECT `id`,`op`,`pk_json`,`before_json`,`after_json`,`commit_ts` FROM "
                + quotedLogTable + " WHERE `id` > " + lastLogId + " ORDER BY `id` LIMIT " + limit;
    }

    /**
     * 触发器名（表名 + 动作后缀，超长截断）。
     *
     * @param t      表元数据
     * @param action AI/AU/AD
     * @return 触发器名
     */
    private String triggerName(TableMeta t, String action) {
        return truncate(t.getTableName() + "_SYNC_" + action);
    }

    /**
     * 主键 JSON 数组表达式（MySQL 用 {@code JSON_ARRAY}）。
     *
     * @param t      表元数据
     * @param prefix NEW / OLD
     * @return SQL 表达式
     */
    private String pkJsonExpression(TableMeta t, String prefix) {
        List<String> cols = t.primaryKeyNames();
        if (cols.isEmpty()) {
            cols = List.of(t.getColumns().get(0).getName());
        }
        return "JSON_ARRAY(" + cols.stream().map(c -> prefix + "." + quote(c))
                .collect(Collectors.joining(", ")) + ")";
    }

    /**
     * 行 JSON 对象表达式（MySQL 用 {@code JSON_OBJECT}）。
     *
     * @param t      表元数据
     * @param prefix NEW / OLD
     * @return SQL 表达式
     */
    private String jsonObjectExpression(TableMeta t, String prefix) {
        List<String> parts = new ArrayList<>();
        for (ColumnMeta c : t.getColumns()) {
            parts.add("'" + c.getName().toUpperCase(Locale.ROOT) + "'");
            parts.add(prefix + "." + quote(c.getName()));
        }
        return "JSON_OBJECT(" + String.join(", ", parts) + ")";
    }

    /**
     * 加反引号。
     *
     * @param id 标识符
     * @return 引用后的标识符
     */
    private String quote(String id) {
        return "`" + id.replace("`", "``") + "`";
    }

    /**
     * 截断到 MySQL 标识符上限。
     *
     * @param name 名称
     * @return 截断结果
     */
    private String truncate(String name) {
        return name.length() <= MAX_IDENTIFIER ? name : name.substring(0, MAX_IDENTIFIER);
    }
}
