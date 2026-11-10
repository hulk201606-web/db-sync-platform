package com.dbsync.dialect.oracle;

import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.TriggerOptions;
import com.dbsync.dialect.TriggerDdlGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Oracle 触发器日志 DDL 生成器。
 *
 * <p>三个坑（架构 §5.3）：
 * <ol>
 *   <li>必须 {@code FOR EACH ROW} 才是行级触发器；</li>
 *   <li>{@code :NEW.col} 的<b>冒号</b>是编译期语法，拼接时最容易漏；</li>
 *   <li>日志表<b>不用</b> {@code AUTONOMOUS_TRANSACTION}：与业务同事务，业务回滚则日志一起回滚
 *       （这正是"不记录未提交变更"的保证）。</li>
 * </ol>
 */
public class OracleTriggerDdlGenerator implements TriggerDdlGenerator {

    private static final int MAX_IDENTIFIER = 30;

    @Override
    public String changeLogTableName(String baseTable) {
        String suffix = "_SYNC_LOG";
        if (baseTable.length() + suffix.length() <= MAX_IDENTIFIER) {
            return baseTable + suffix;
        }
        return baseTable.substring(0, Math.max(1, MAX_IDENTIFIER - suffix.length())) + suffix;
    }

    @Override
    public List<String> createChangeLogTableDdl(TableMeta t, TriggerOptions opts) {
        String log = quote(changeLogTableName(t.getTableName()));
        String ddl = "CREATE TABLE " + log + " ("
                + "\"ID\" NUMBER(19) NOT NULL, "
                + "\"OP\" VARCHAR2(8) NOT NULL, "
                + "\"PK_JSON\" VARCHAR2(2048) NOT NULL, "
                + "\"BEFORE_JSON\" CLOB NULL, "
                + "\"AFTER_JSON\" CLOB NULL, "
                + "\"COMMIT_TS\" TIMESTAMP(6) NOT NULL, "
                + "CONSTRAINT " + quote(changeLogTableName(t.getTableName()) + "_PK")
                + " PRIMARY KEY (\"ID\"))";
        String seq = "CREATE SEQUENCE " + quote(changeLogTableName(t.getTableName()) + "_SEQ")
                + " START WITH 1 INCREMENT BY 1 NOCACHE";
        return List.of(ddl, seq);
    }

    @Override
    public List<String> createTriggerDdl(TableMeta t, TriggerOptions opts) {
        TriggerOptions options = opts == null ? TriggerOptions.defaults() : opts;
        String table = quote(t.getTableName());
        String log = quote(changeLogTableName(t.getTableName()));
        String seqExpr = log.replace("\"", "") + "_SEQ.NEXTVAL";
        List<String> ddl = new ArrayList<>();

        ddl.add("CREATE OR REPLACE TRIGGER " + quote(triggerName(t, "AI")) + " AFTER INSERT ON "
                + table + " FOR EACH ROW BEGIN INSERT INTO " + log
                + " (\"ID\",\"OP\",\"PK_JSON\",\"AFTER_JSON\",\"COMMIT_TS\") VALUES ("
                + seqExpr + ", 'INSERT', " + pkJsonExpression(t, ":NEW") + ", "
                + jsonObjectExpression(t, ":NEW") + ", SYSTIMESTAMP); END;");

        ddl.add("CREATE OR REPLACE TRIGGER " + quote(triggerName(t, "AU")) + " AFTER UPDATE ON "
                + table + " FOR EACH ROW BEGIN INSERT INTO " + log
                + " (\"ID\",\"OP\",\"PK_JSON\",\"BEFORE_JSON\",\"AFTER_JSON\",\"COMMIT_TS\") VALUES ("
                + seqExpr + ", 'UPDATE', " + pkJsonExpression(t, ":NEW") + ", "
                + (options.isIncludeBeforeImage() ? jsonObjectExpression(t, ":OLD") : "NULL") + ", "
                + jsonObjectExpression(t, ":NEW") + ", SYSTIMESTAMP); END;");

        ddl.add("CREATE OR REPLACE TRIGGER " + quote(triggerName(t, "AD")) + " AFTER DELETE ON "
                + table + " FOR EACH ROW BEGIN INSERT INTO " + log
                + " (\"ID\",\"OP\",\"PK_JSON\",\"BEFORE_JSON\",\"COMMIT_TS\") VALUES ("
                + seqExpr + ", 'DELETE', " + pkJsonExpression(t, ":OLD") + ", "
                + jsonObjectExpression(t, ":OLD") + ", SYSTIMESTAMP); END;");
        return List.copyOf(ddl);
    }

    @Override
    public List<String> dropTriggerDdl(TableMeta t, TriggerOptions opts) {
        List<String> ddl = new ArrayList<>();
        ddl.add("DROP TRIGGER " + quote(triggerName(t, "AI")));
        ddl.add("DROP TRIGGER " + quote(triggerName(t, "AU")));
        ddl.add("DROP TRIGGER " + quote(triggerName(t, "AD")));
        TriggerOptions options = opts == null ? TriggerOptions.defaults() : opts;
        if (options.isDropLogTableOnUninstall()) {
            ddl.add("DROP TABLE " + quote(changeLogTableName(t.getTableName())));
        }
        return List.copyOf(ddl);
    }

    @Override
    public String pollSql(String quotedLogTable, long lastLogId, int batchSize) {
        long upper = lastLogId + (batchSize <= 0 ? 500 : Math.min(batchSize, 5000));
        return "SELECT \"ID\",\"OP\",\"PK_JSON\",\"BEFORE_JSON\",\"AFTER_JSON\",\"COMMIT_TS\" FROM "
                + quotedLogTable + " WHERE \"ID\" > " + lastLogId + " AND \"ID\" <= " + upper
                + " ORDER BY \"ID\"";
    }

    private String triggerName(TableMeta t, String action) {
        String name = t.getTableName() + "_SN_" + action;
        return name.length() <= MAX_IDENTIFIER ? name : name.substring(0, MAX_IDENTIFIER);
    }

    private String pkJsonExpression(TableMeta t, String prefix) {
        List<String> cols = t.primaryKeyNames().isEmpty()
                ? List.of(t.getColumns().get(0).getName()) : t.primaryKeyNames();
        return "JSON_ARRAY(" + cols.stream().map(c -> prefix + "." + quote(c))
                .collect(Collectors.joining(", ")) + ")";
    }

    private String jsonObjectExpression(TableMeta t, String prefix) {
        List<String> parts = new ArrayList<>();
        for (ColumnMeta c : t.getColumns()) {
            parts.add("'" + c.getName().toUpperCase(Locale.ROOT) + "'");
            parts.add(prefix + "." + quote(c.getName()));
        }
        return "JSON_OBJECT(" + String.join(", ", parts) + ")";
    }

    private String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}
