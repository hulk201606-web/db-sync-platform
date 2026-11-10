package com.dbsync.dialect.postgresql;

import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.TriggerOptions;
import com.dbsync.dialect.TriggerDdlGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * PostgreSQL 触发器日志 DDL 生成器（<b>两段式</b>：函数 + 触发器）。
 *
 * <p>三个坑（架构 §5.3）：
 * <ol>
 *   <li>{@code $$} 在 Java 字符串里安全，但不要在 JDBC 里用分号拆分语句 —— PG 会把 {@code $$...$$} 当整体；</li>
 *   <li>DELETE 分支必须 {@code RETURN OLD}（返回 NEW 会报错）；</li>
 *   <li>{@code EXECUTE FUNCTION} 是 PG 11+（10 及以前是 {@code EXECUTE PROCEDURE}）。</li>
 * </ol>
 */
public final class PostgresTriggerDdlGenerator implements TriggerDdlGenerator {

    private static final int MAX_IDENTIFIER = 63;

    @Override
    public String changeLogTableName(String baseTable) {
        String name = baseTable + "_SYNC_LOG";
        return name.length() <= MAX_IDENTIFIER ? name : baseTable.substring(0,
                Math.max(1, MAX_IDENTIFIER - "_SYNC_LOG".length())) + "_SYNC_LOG";
    }

    @Override
    public List<String> createChangeLogTableDdl(TableMeta t, TriggerOptions opts) {
        String log = quote(changeLogTableName(t.getTableName()));
        String ddl = "CREATE TABLE IF NOT EXISTS " + log + " ("
                + "\"id\" BIGSERIAL PRIMARY KEY, "
                + "\"op\" VARCHAR(8) NOT NULL, "
                + "\"pk_json\" VARCHAR(2048) NOT NULL, "
                + "\"before_json\" TEXT NULL, "
                + "\"after_json\" TEXT NULL, "
                + "\"commit_ts\" TIMESTAMP(3) NOT NULL)";
        String idx = "CREATE INDEX " + quote(changeLogTableName(t.getTableName()) + "_TS_IDX")
                + " ON " + log + " (\"commit_ts\")";
        return List.of(ddl, idx);
    }

    @Override
    public List<String> createTriggerDdl(TableMeta t, TriggerOptions opts) {
        TriggerOptions options = opts == null ? TriggerOptions.defaults() : opts;
        String table = quote(t.getTableName());
        String log = quote(changeLogTableName(t.getTableName()));
        String fnName = quote(funcName(t));
        String pkJson = pkJsonExpression(t, "NEW");
        String afterJson = jsonObjectExpression(t, "NEW");
        String beforeJson = jsonObjectExpression(t, "OLD");

        StringBuilder fn = new StringBuilder();
        fn.append("CREATE OR REPLACE FUNCTION ").append(fnName)
                .append("() RETURNS trigger AS $$ BEGIN ")
                .append("IF (TG_OP = 'DELETE') THEN ")
                .append("INSERT INTO ").append(log)
                .append(" (\"op\",\"pk_json\",\"before_json\",\"commit_ts\") VALUES ('DELETE', ")
                .append(pkJsonExpression(t, "OLD")).append(", ").append(beforeJson)
                .append(", LOCALTIMESTAMP(3)); RETURN OLD; ")
                .append("ELSIF (TG_OP = 'UPDATE') THEN ")
                .append("INSERT INTO ").append(log)
                .append(" (\"op\",\"pk_json\",\"before_json\",\"after_json\",\"commit_ts\") VALUES ('UPDATE', ")
                .append(pkJson).append(", ")
                .append(options.isIncludeBeforeImage() ? beforeJson : "NULL").append(", ")
                .append(afterJson).append(", LOCALTIMESTAMP(3)); RETURN NEW; ")
                .append("ELSE ")
                .append("INSERT INTO ").append(log)
                .append(" (\"op\",\"pk_json\",\"after_json\",\"commit_ts\") VALUES ('INSERT', ")
                .append(pkJson).append(", ").append(afterJson)
                .append(", LOCALTIMESTAMP(3)); RETURN NEW; ")
                .append("END IF; END; $$ LANGUAGE plpgsql");

        String trigger = "CREATE TRIGGER " + quote(triggerName(t))
                + " AFTER INSERT OR UPDATE OR DELETE ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION " + fnName + "()";
        return List.of(fn.toString(), trigger);
    }

    @Override
    public List<String> dropTriggerDdl(TableMeta t, TriggerOptions opts) {
        List<String> ddl = new ArrayList<>();
        ddl.add("DROP TRIGGER IF EXISTS " + quote(triggerName(t)) + " ON " + quote(t.getTableName()));
        ddl.add("DROP FUNCTION IF EXISTS " + quote(funcName(t)) + "()");
        TriggerOptions options = opts == null ? TriggerOptions.defaults() : opts;
        if (options.isDropLogTableOnUninstall()) {
            ddl.add("DROP TABLE IF EXISTS " + quote(changeLogTableName(t.getTableName())));
        }
        return List.copyOf(ddl);
    }

    @Override
    public String pollSql(String quotedLogTable, long lastLogId, int batchSize) {
        int limit = batchSize <= 0 ? 500 : Math.min(batchSize, 5000);
        return "SELECT \"id\",\"op\",\"pk_json\",\"before_json\",\"after_json\",\"commit_ts\" FROM "
                + quotedLogTable + " WHERE \"id\" > " + lastLogId + " ORDER BY \"id\" LIMIT " + limit;
    }

    private String funcName(TableMeta t) {
        String name = t.getTableName() + "_SYNC_FN";
        return name.length() <= MAX_IDENTIFIER ? name : name.substring(0, MAX_IDENTIFIER);
    }

    private String triggerName(TableMeta t) {
        String name = t.getTableName() + "_SYNC";
        return name.length() <= MAX_IDENTIFIER ? name : name.substring(0, MAX_IDENTIFIER);
    }

    private String pkJsonExpression(TableMeta t, String prefix) {
        List<String> cols = t.primaryKeyNames().isEmpty()
                ? List.of(t.getColumns().get(0).getName()) : t.primaryKeyNames();
        return "json_build_array(" + cols.stream().map(c -> prefix + "." + quote(c))
                .collect(Collectors.joining(", ")) + ")::text";
    }

    private String jsonObjectExpression(TableMeta t, String prefix) {
        List<String> parts = new ArrayList<>();
        for (ColumnMeta c : t.getColumns()) {
            parts.add("'" + c.getName().toUpperCase(Locale.ROOT) + "'");
            parts.add(prefix + "." + quote(c.getName()));
        }
        return "json_build_object(" + String.join(", ", parts) + ")::text";
    }

    private String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}
