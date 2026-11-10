package com.dbsync.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.options.TriggerOptions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T31 验收：触发器日志轮询"连续两批无重复无遗漏" + DDL 文本 + 安装幂等判定。
 *
 * <p><b>为什么不在 H2 上真跑触发器</b>：H2 的 MySQL 兼容模式不支持
 * {@code CREATE TRIGGER ... FOR EACH ROW ... JSON_ARRAY(...)} 这套语法，
 * 真跑只能验证"H2 支不支持"，验证不了"我们的轮询是否正确"。
 * 因此测试直接构造**方言生成的日志表结构**并写入符合约定的日志行，
 * 再断言轮询器解析出的事件序列 —— 这正是轮询器要保证的东西。
 */
class TriggerPollerTest extends CdcTestSupport {

    private TriggerLogPoller poller;
    private JdbcDataSource sourceDs;
    private String logTable;
    private long taskId;
    private TableMapping mapping;

    /**
     * 建日志表（结构与 MySQL 方言生成的一致）。
     */
    @BeforeEach
    void setUpPoller() {
        sourceDs = h2(metaName() + "_src");
        logTable = dialect.trigger().changeLogTableName("t_src");
        // H2 内存库在同一个测试类内共享（DB_CLOSE_DELAY=-1），必须显式清理，
        // 否则第二个测试方法会撞上 "Table already exists"
        exec(sourceDs, "DROP TABLE IF EXISTS " + logTable,
                "CREATE TABLE " + logTable + " (id BIGINT NOT NULL AUTO_INCREMENT, "
                        + "op VARCHAR(8) NOT NULL, pk_json VARCHAR(2048) NOT NULL, "
                        + "before_json LONGTEXT NULL, after_json LONGTEXT NULL, "
                        + "commit_ts TIMESTAMP NOT NULL, PRIMARY KEY (id))",
                "CREATE INDEX " + logTable + "_TS ON " + logTable + " (commit_ts)");
        poller = new TriggerLogPoller(null, null, null, null, null, 100);
        taskId = newTask(4, 2, IncrementalMode.TRIGGER_LOG).getId();
        mapping = newMapping(taskId, "t_src");
    }

    /**
     * 写一条日志。
     *
     * @param op        操作
     * @param pkJson    主键 JSON
     * @param afterJson 后像
     * @param beforeJson 前像
     */
    private void log(String op, String pkJson, String afterJson, String beforeJson) {
        String after = afterJson == null ? "NULL" : "'" + afterJson + "'";
        String before = beforeJson == null ? "NULL" : "'" + beforeJson + "'";
        exec(sourceDs, "INSERT INTO " + logTable
                + " (op,pk_json,before_json,after_json,commit_ts) VALUES ('" + op + "','" + pkJson
                + "'," + before + "," + after + ",CURRENT_TIMESTAMP)");
    }

    /**
     * 走一次轮询 SQL 读取。
     *
     * @param lastLogId 上次位点
     * @return 事件
     * @throws SQLException 读取失败
     */
    private List<ChangeEvent> read(long lastLogId) throws SQLException {
        String sql = dialect.trigger().pollSql(dialect.quote(logTable), lastLogId, 100);
        try (Connection connection = sourceDs.getConnection()) {
            return poller.read(connection, sql, taskId, mapping.getId(), tableMeta("t_src"), 4);
        }
    }

    @Test
    @DisplayName("should_noDuplicateNoGap_When_twoConsecutivePolls：两批 log_id 无重复无遗漏")
    void should_noDuplicateNoGap_When_twoConsecutivePolls() throws Exception {
        log("INSERT", "[1]", "{\"ID\":1,\"NAME\":\"a\"}", null);
        log("UPDATE", "[2]", "{\"ID\":2,\"NAME\":\"b\"}", "{\"ID\":2,\"NAME\":\"b0\"}");
        log("DELETE", "[3]", null, "{\"ID\":3,\"NAME\":\"c\"}");

        List<ChangeEvent> first = read(0L);
        assertEquals(3, first.size(), "第一批应拉到 3 条");
        long last = first.get(first.size() - 1).getSeq();

        log("INSERT", "[4]", "{\"ID\":4,\"NAME\":\"d\"}", null);
        log("UPDATE", "[5]", "{\"ID\":5,\"NAME\":\"e\"}", null);
        List<ChangeEvent> second = read(last);
        assertEquals(2, second.size(), "第二批只应拉到新增的 2 条");
        assertEquals(4L, second.get(0).getSeq(), "第二批起点必须紧接上一批（无遗漏）");

        Set<Long> seqs = new LinkedHashSet<>();
        for (ChangeEvent event : first) {
            seqs.add(Long.valueOf(event.getSeq()));
        }
        for (ChangeEvent event : second) {
            seqs.add(Long.valueOf(event.getSeq()));
        }
        assertEquals(5, seqs.size(), "5 条日志必须对应 5 个不同的 seq（无重复）");
        assertEquals(ChangeOp.DELETE, first.get(2).getOp(), "op 必须正确解析");
        assertEquals(List.of("1"), first.get(0).getPkTuple().stream().map(String::valueOf).toList(),
                "pk_json 必须解析成主键元组");
    }

    @Test
    @DisplayName("should_parseOpAndImage_When_logWritten：op/前后像解析")
    void should_parseOpAndImage_When_logWritten() throws Exception {
        log("UPDATE", "[9]", "{\"ID\":9,\"NAME\":\"new\"}", "{\"ID\":9,\"NAME\":\"old\"}");

        ChangeEvent event = read(0L).get(0);

        assertEquals(ChangeOp.UPDATE, event.getOp());
        assertEquals("new", event.getAfter().get("NAME"));
        assertEquals("old", event.getBefore().get("NAME"));
    }

    @Test
    @DisplayName("should_buildInstallDdl_When_dialectGenerates：安装 DDL 全部来自方言")
    void should_buildInstallDdl_When_dialectGenerates() {
        List<String> tableDdl = dialect.trigger()
                .createChangeLogTableDdl(tableMeta("t_src"), TriggerOptions.defaults());
        List<String> triggerDdl = dialect.trigger()
                .createTriggerDdl(tableMeta("t_src"), TriggerOptions.defaults());
        List<String> dropDdl = dialect.trigger()
                .dropTriggerDdl(tableMeta("t_src"), TriggerOptions.defaults());

        assertFalse(tableDdl.isEmpty());
        assertTrue(tableDdl.get(0).toUpperCase().contains("CREATE TABLE"), tableDdl.get(0));
        assertEquals(3, triggerDdl.size(), "INSERT/UPDATE/DELETE 各一个触发器");
        for (String ddl : triggerDdl) {
            assertTrue(ddl.toUpperCase().contains("CREATE TRIGGER"), ddl);
        }
        assertFalse(dropDdl.isEmpty());
        assertTrue(dropDdl.get(dropDdl.size() - 1).toUpperCase()
                .contains(dropDdl.size() > 3 ? "DROP TABLE" : "DROP TRIGGER"));
    }

    @Test
    @DisplayName("should_treatAlreadyExistsAsSuccess_When_reinstall：重复安装视为成功")
    void should_treatAlreadyExistsAsSuccess_When_reinstall() {
        assertTrue(TriggerInstaller.isAlreadyExists(
                new SQLException("Table 'X' already exists", "42S01", 1050)));
        assertTrue(TriggerInstaller.isAlreadyExists(
                new SQLException("ORA-00955: name is already used", "42000", 955)));
        assertFalse(TriggerInstaller.isAlreadyExists(
                new SQLException("syntax error", "42000", 1064)));
    }

    @Test
    @DisplayName("should_rejectUnknownOp_When_logCorrupted：未知 op 直接报错")
    void should_rejectUnknownOp_When_logCorrupted() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.dbsync.common.exception.DbSyncException.class,
                () -> TriggerLogPoller.parseOp("TRUNCATE"));
        assertEquals(ChangeOp.INSERT, TriggerLogPoller.parseOp("insert"));
    }

    @Test
    @DisplayName("should_returnEmpty_When_logTableEmpty：空日志表不产生事件")
    void should_returnEmpty_When_logTableEmpty() throws Exception {
        assertTrue(read(0L).isEmpty());
    }

}
