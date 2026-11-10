package com.dbsync.metadata.dao;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.options.GateThresholds;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 切流计划 DAO —— 两段式状态机的持久化层（架构 §4.6.2）。
 *
 * <p><b>两段式的意义</b>：{@code intent_state} 表示"我要去哪里"，
 * {@code confirmed_state} 表示"外部世界确认已经到了哪里"。
 * 只有两者相等（一致）才允许继续推进（I7）。
 * 中间态（例如"已通知网关切 10%，但网关未回执"）必须能被别的实例看出来并修复，
 * 所以 {@code updateIntent} 与 {@code confirm} 必须是两个独立 UPDATE，
 * <b>绝不能</b>一条 SQL 同时改两列。
 */
@Repository
public class CutoverPlanDao extends AbstractMetaDao {

    /** 计划状态：正常。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 计划状态：意图与确认不一致（已冻结，禁止推进）。 */
    public static final String STATUS_INCONSISTENT = "INCONSISTENT";
    /** 计划状态：已冻结（人工介入）。 */
    public static final String STATUS_FROZEN = "FROZEN";
    /** 计划状态：已关闭。 */
    public static final String STATUS_CLOSED = "CLOSED";

    private final RowMapper<CutoverPlan> mapper = new RowMapper<CutoverPlan>() {
        @Override
        public CutoverPlan mapRow(ResultSet rs, int rowNum) throws SQLException {
            GateThresholds thresholds = new GateThresholds(
                    rs.getBigDecimal("gate_compare_pass").doubleValue(),
                    rs.getLong("gate_lag_p95_ms"),
                    rs.getBigDecimal("gate_fail_rate").doubleValue(),
                    rs.getLong("gate_dead_letters"),
                    360L);
            CutoverPlan plan = new CutoverPlan(rs.getLong("id"), rs.getString("plan_no"),
                    rs.getLong("task_id"), rs.getString("name"),
                    CutoverState.valueOf(rs.getString("intent_state")),
                    CutoverState.valueOf(rs.getString("confirmed_state")),
                    rs.getLong("route_version"), rs.getString("plan_status"), thresholds,
                    rs.getInt("gray_ratio"), rs.getInt("write_gray_ratio"));
            plan.setRollbackReason(rs.getString("rollback_reason"));
            return plan;
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public CutoverPlanDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 新增计划。
     *
     * @param plan      计划
     * @param grayJson  灰度维度 JSON（非空）
     * @param createdBy 创建人
     * @return 影响行数
     */
    public int insert(CutoverPlan plan, String grayJson, String createdBy) {
        GateThresholds t = plan.getGateThresholds();
        java.time.Instant now = now();
        return jdbc.update("INSERT INTO cutover_plan (id,plan_no,task_id,name,intent_state,"
                        + "confirmed_state,route_version,plan_status,gray_dimensions,gray_ratio,"
                        + "write_gray_ratio,gate_compare_pass,gate_lag_p95_ms,gate_fail_rate,"
                        + "gate_dead_letters,created_by,version,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
                plan.getId(), plan.getPlanNo(), plan.getTaskId(), plan.getName(),
                plan.getIntentState().name(), plan.getConfirmedState().name(),
                plan.getRouteVersion(), plan.getPlanStatus(),
                grayJson == null ? "[]" : grayJson, plan.getGrayRatio(), plan.getWriteGrayRatio(),
                t.getComparePassRate(), t.getLagP95Ms(), t.getDualWriteFailRate(),
                t.getOpenDeadLetters(), createdBy, toTimestamp(now), toTimestamp(now));
    }

    /**
     * 按 ID 查询。
     *
     * @param id 计划 ID
     * @return 计划
     */
    public Optional<CutoverPlan> findById(long id) {
        List<CutoverPlan> list = jdbc.query("SELECT * FROM cutover_plan WHERE id=?", mapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 按计划编号查询。
     *
     * @param planNo 计划编号
     * @return 计划
     */
    public Optional<CutoverPlan> findByPlanNo(String planNo) {
        List<CutoverPlan> list = jdbc.query("SELECT * FROM cutover_plan WHERE plan_no=?",
                mapper, planNo);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 按任务列出计划（倒序，最新在前）。
     *
     * @param taskId 任务 ID
     * @return 计划列表
     */
    public List<CutoverPlan> findByTask(long taskId) {
        return jdbc.query("SELECT * FROM cutover_plan WHERE task_id=? ORDER BY created_at DESC",
                mapper, taskId);
    }

    /**
     * 两段式第一段：只更新 intent（<b>不动</b> confirmed）。
     *
     * @param planId          计划 ID
     * @param intent          目标意图状态
     * @param expectedVersion 期望版本（乐观锁）
     * @return 影响行数（0 表示版本冲突，调用方需重读）
     */
    public int updateIntent(long planId, CutoverState intent, long expectedVersion) {
        return jdbc.update("UPDATE cutover_plan SET intent_state=?,plan_status=?,"
                        + "version=version+1,updated_at=? WHERE id=? AND version=?",
                intent.name(), STATUS_INCONSISTENT, toTimestamp(now()), planId, expectedVersion);
    }

    /**
     * 两段式第二段：确认外部已生效，并把 confirmed 与 route_version 一并推进。
     *
     * <p>语义上"先让外部生效、再记录 confirmed"，所以 routeVersion 必须由调用方在
     * 外部动作成功之后传入（不能预先 +1）。
     *
     * @param planId          计划 ID
     * @param confirmed       已确认状态（必须等于 intent，否则 I5 被破坏）
     * @param routeVersion    外部生效后的路由版本
     * @param expectedVersion 期望版本
     * @return 影响行数（0 表示版本冲突或 intent 不匹配）
     */
    public int confirm(long planId, CutoverState confirmed, long routeVersion,
                       long expectedVersion) {
        return jdbc.update("UPDATE cutover_plan SET confirmed_state=?,route_version=?,"
                        + "plan_status=?,version=version+1,updated_at=? "
                        + "WHERE id=? AND version=? AND intent_state=?",
                confirmed.name(), routeVersion, STATUS_ACTIVE, toTimestamp(now()),
                planId, expectedVersion, confirmed.name());
    }

    /**
     * 启动对齐扫描：{@code status='ACTIVE' AND intent_state <> confirmed_state}。
     *
     * <p>命中说明上次进程在"两段之间"挂了，必须由 {@code CutoverRecoveryService} 修复。
     *
     * @return 不一致计划列表
     */
    public List<CutoverPlan> findInconsistent() {
        return jdbc.query("SELECT * FROM cutover_plan WHERE plan_status=? "
                        + "AND intent_state <> confirmed_state ORDER BY updated_at",
                mapper, STATUS_ACTIVE);
    }

    /**
     * 启动对齐扫描（// ADDED：{@link #updateIntent} 会把 plan_status 改成 INCONSISTENT，
     * 而 {@link #findInconsistent()} 只匹配 ACTIVE，导致"写完 intent 就崩"的计划反而扫不到。
     * 该方法把 ACTIVE 与 INCONSISTENT 一并纳入，是 {@code CutoverRecoveryService} 的正确入口）。
     *
     * @return 待对齐计划（intent != confirmed）
     */
    public List<CutoverPlan> findNeedingReconcile() {
        return jdbc.query("SELECT * FROM cutover_plan WHERE intent_state <> confirmed_state "
                        + "AND plan_status IN (?,?) ORDER BY updated_at",
                mapper, STATUS_ACTIVE, STATUS_INCONSISTENT);
    }

    /**
     * 强制把计划标记为不一致（冻结，供人工确认）。
     *
     * @param planId 计划 ID
     * @return 影响行数
     */
    public int markInconsistent(long planId) {
        return jdbc.update("UPDATE cutover_plan SET plan_status=?,version=version+1,"
                + "updated_at=? WHERE id=?", STATUS_INCONSISTENT, toTimestamp(now()), planId);
    }

    /**
     * 冻结/解冻计划。
     *
     * @param planId 计划 ID
     * @param status 目标状态
     * @return 影响行数
     */
    public int updatePlanStatus(long planId, String status) {
        return jdbc.update("UPDATE cutover_plan SET plan_status=?,version=version+1,"
                + "updated_at=? WHERE id=?", status, toTimestamp(now()), planId);
    }

    /**
     * 更新灰度比例（读写分别推进，两者可不同步，故一条 SQL 同时写没问题）。
     *
     * @param planId        计划 ID
     * @param grayRatio     读灰度百分比 0..100
     * @param writeGrayRatio 写灰度百分比 0..100
     * @return 影响行数
     */
    public int updateGray(long planId, int grayRatio, int writeGrayRatio) {
        return jdbc.update("UPDATE cutover_plan SET gray_ratio=?,write_gray_ratio=?,"
                        + "version=version+1,updated_at=? WHERE id=?",
                clampPercent(grayRatio), clampPercent(writeGrayRatio), toTimestamp(now()), planId);
    }

    /**
     * 记录 Gate 评估快照（切流留痕，事后可复现"为什么当时允许切"）。
     *
     * @param planId      计划 ID
     * @param snapshotJson 快照 JSON
     * @return 影响行数
     */
    public int updateGateSnapshot(long planId, String snapshotJson) {
        return jdbc.update("UPDATE cutover_plan SET gate_snapshot_json=?,version=version+1,"
                + "updated_at=? WHERE id=?", snapshotJson, toTimestamp(now()), planId);
    }

    /**
     * 开始观察窗口。
     *
     * @param planId 计划 ID
     * @return 影响行数
     */
    public int startObserve(long planId) {
        return jdbc.update("UPDATE cutover_plan SET observe_started_at=?,version=version+1,"
                + "updated_at=? WHERE id=?", toTimestamp(now()), toTimestamp(now()), planId);
    }

    /**
     * 记录回滚。
     *
     * @param planId 计划 ID
     * @param reason 回滚原因
     * @return 影响行数
     */
    public int markRolledBack(long planId, String reason) {
        String msg = reason != null && reason.length() > 512 ? reason.substring(0, 512) : reason;
        return jdbc.update("UPDATE cutover_plan SET rolled_back_at=?,rollback_reason=?,"
                        + "version=version+1,updated_at=? WHERE id=?",
                toTimestamp(now()), msg, toTimestamp(now()), planId);
    }

    /**
     * 读取乐观锁版本（// ADDED：{@code CutoverPlan} 模型只承载 routeVersion，
     * 没有承载乐观锁 {@code version} 字段，而 {@link #updateIntent} / {@link #confirm}
     * 都需要 expectedVersion；补这个方法让编排器能拿到正确的期望版本）。
     *
     * @param planId 计划 ID
     * @return 版本号；不存在返回 -1
     */
    public int versionOf(long planId) {
        Integer v = jdbc.queryForObject("SELECT version FROM cutover_plan WHERE id=?", Integer.class,
                planId);
        return v == null ? -1 : v.intValue();
    }

    /**
     * 读取路由版本（供路由表发布/校验）。
     *
     * @param planId 计划 ID
     * @return 版本；不存在返回 -1
     */
    public long routeVersionOf(long planId) {
        Long v = jdbc.queryForObject("SELECT route_version FROM cutover_plan WHERE id=?",
                Long.class, planId);
        return v == null ? -1L : v;
    }

    /**
     * 读取 SDK 已确认生效的路由版本（// ADDED：PRD CUT-01 的闭环字段，V4 迁移新增）。
     *
     * @param planId 计划 ID
     * @return 已 ack 版本；计划不存在返回 -1
     */
    public long ackedVersionOf(long planId) {
        Long v = jdbc.queryForObject("SELECT acked_version FROM cutover_plan WHERE id=?",
                Long.class, planId);
        return v == null ? -1L : v;
    }

    /**
     * 单调推进 SDK 已确认版本（// ADDED：PRD CUT-01）。
     *
     * <p><b>单调性写在 SQL 的 WHERE 里</b>而不是"先查后写"：多实例/多 SDK 并发 ack 时，
     * 先查后写会出现两个请求都读到 5、都写入 7 的竞态；把
     * {@code acked_version < ?} 放进 WHERE 后，数据库行锁天然保证只有更大的版本能落地，
     * 回退的 ack（v3 &lt; v5）影响 0 行，调用方据此返回 {@code applied=false}。
     *
     * @param planId       计划 ID
     * @param ackedVersion 被确认的版本（调用方已保证 ≥0）
     * @return 影响行数（0 表示版本未前进，或计划不存在）
     */
    public int updateAckedVersion(long planId, long ackedVersion) {
        return jdbc.update("UPDATE cutover_plan SET acked_version=?,updated_at=? "
                        + "WHERE id=? AND acked_version < ?",
                ackedVersion, toTimestamp(now()), planId, ackedVersion);
    }

    /**
     * 百分比收敛到 0..100。
     *
     * @param value 原值
     * @return 收敛值
     */
    private static int clampPercent(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 100);
    }
}
