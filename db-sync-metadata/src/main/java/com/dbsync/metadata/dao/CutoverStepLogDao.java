package com.dbsync.metadata.dao;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.GateItemCode;
import com.dbsync.common.model.CutoverStep;
import com.dbsync.common.model.GateResult;
import com.dbsync.common.options.GateThresholds;
import com.dbsync.common.util.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 切流步骤留痕 DAO。
 *
 * <p>每一次状态跃迁（含被 Gate 拦截、被跳过）都必须落一行，用于事后复盘
 * "谁在什么时候、基于什么指标、把状态从哪推到哪"。审计意义上这张表比 {@code sys_audit_log} 更细。
 */
@Repository
public class CutoverStepLogDao extends AbstractMetaDao {

    private final RowMapper<CutoverStep> mapper = new RowMapper<CutoverStep>() {
        @Override
        public CutoverStep mapRow(ResultSet rs, int rowNum) throws SQLException {
            long duration = rs.getLong("duration_ms");
            Long durationOrNull = rs.wasNull() ? null : duration;
            return new CutoverStep(rs.getLong("plan_id"), rs.getInt("step_seq"),
                    CutoverState.valueOf(rs.getString("from_state")),
                    CutoverState.valueOf(rs.getString("to_state")),
                    rs.getString("action"), rs.getString("result"), rs.getString("error_msg"),
                    durationOrNull == null ? 0L : durationOrNull.longValue(),
                    rs.getString("operator"), rs.getLong("route_version_before"),
                    rs.getLong("route_version_after"), rs.getInt("gate_skipped") != 0,
                    rs.getString("skip_reason"), parseGateResult(rs.getString("gate_result_json")));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public CutoverStepLogDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 追加一条步骤留痕。
     *
     * @param step           步骤
     * @param operatorIp     操作来源 IP（可 null）
     * @return 影响行数
     */
    public int append(CutoverStep step, String operatorIp) {
        String gateJson = step.getGateResult() == null ? null
                : toGateJson(step.getGateResult());
        String error = step.getErrorMsg() != null && step.getErrorMsg().length() > 1000
                ? step.getErrorMsg().substring(0, 1000) : step.getErrorMsg();
        String skipReason = step.getSkipReason() != null && step.getSkipReason().length() > 512
                ? step.getSkipReason().substring(0, 512) : step.getSkipReason();
        return jdbc.update("INSERT INTO cutover_step_log (id,plan_id,step_seq,from_state,to_state,"
                        + "action,gate_result_json,gate_skipped,skip_reason,route_version_before,"
                        + "route_version_after,result,error_msg,duration_ms,operator,operator_ip,"
                        + "created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                nextId(), step.getPlanId(), step.getStepSeq(), step.getFromState().name(),
                step.getToState().name(), step.getAction(), gateJson,
                step.isGateSkipped() ? 1 : 0, skipReason, step.getRouteVersionBefore(),
                step.getRouteVersionAfter(), step.getResult(), error, step.getDurationMs(),
                step.getOperator() == null ? "system" : step.getOperator(), operatorIp,
                toTimestamp(now()));
    }

    /**
     * 按计划列出步骤（按 step_seq 升序，即时间顺序）。
     *
     * @param planId 计划 ID
     * @return 步骤列表
     */
    public List<CutoverStep> findByPlan(long planId) {
        return jdbc.query("SELECT * FROM cutover_step_log WHERE plan_id=? ORDER BY step_seq",
                mapper, planId);
    }

    /**
     * 下一个步骤序号。
     *
     * @param planId 计划 ID
     * @return 序号（无步骤返回 1）
     */
    public int nextStepSeq(long planId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(step_seq),0) FROM cutover_step_log "
                + "WHERE plan_id=?", Integer.class, planId);
        return max == null ? 1 : max + 1;
    }

    /**
     * 最近一步。
     *
     * @param planId 计划 ID
     * @return 步骤；无记录返回 null
     */
    public CutoverStep lastStep(long planId) {
        List<CutoverStep> list = jdbc.query("SELECT * FROM cutover_step_log WHERE plan_id=? "
                + "ORDER BY step_seq DESC LIMIT 1", mapper, planId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 统计计划下落败的步骤数（回滚判定参考）。
     *
     * @param planId 计划 ID
     * @return 数量
     */
    public long countFailed(long planId) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM cutover_step_log WHERE plan_id=? "
                + "AND result<>?", Long.class, planId, "SUCCESS");
        return n == null ? 0L : n;
    }

    /**
     * 原始 Gate 快照 JSON（完整指标明细；模型对象只保留摘要，细节从库里取）。
     *
     * @param planId  计划 ID
     * @param stepSeq 步骤序号
     * @return JSON；不存在返回 null
     */
    public String gateJsonOf(long planId, int stepSeq) {
        List<String> list = jdbc.queryForList("SELECT gate_result_json FROM cutover_step_log "
                        + "WHERE plan_id=? AND step_seq=?",
                String.class, planId, stepSeq);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 删除计划下全部留痕。
     *
     * @param planId 计划 ID
     * @return 影响行数
     */
    public int deleteByPlan(long planId) {
        return jdbc.update("DELETE FROM cutover_step_log WHERE plan_id=?", planId);
    }

    /**
     * GateResult → 紧凑 JSON（含 4 项指标明细，便于事后复盘）。
     *
     * @param result Gate 结果
     * @return JSON
     */
    private static String toGateJson(GateResult result) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("pass", Boolean.valueOf(result.isPass()));
        root.put("evaluatedAt", result.getEvaluatedAt() == null ? null
                : Long.valueOf(result.getEvaluatedAt().toEpochMilli()));
        List<Object> items = new ArrayList<Object>();
        for (GateResult.GateItem item : result.getItems()) {
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("code", item.getCode().name());
            node.put("actual", Double.valueOf(item.getActual()));
            node.put("threshold", Double.valueOf(item.getThreshold()));
            node.put("pass", Boolean.valueOf(item.isPass()));
            node.put("source", item.getSource());
            node.put("hint", item.getHint());
            items.add(node);
        }
        root.put("items", items);
        root.put("reasons", new ArrayList<Object>(result.getReasons()));
        return JsonUtil.toJson(root);
    }

    /**
     * 紧凑 JSON → GateResult 摘要（items 从 JSON 还原，reasons 保留）。
     *
     * @param json JSON 串（可 null）
     * @return GateResult；null/空返回 null
     */
    private static GateResult parseGateResult(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Map<String, Object> root = JsonUtil.parseObject(json);
        if (root.isEmpty()) {
            return null;
        }
        List<GateResult.GateItem> items = new ArrayList<GateResult.GateItem>();
        Object rawItems = root.get("items");
        if (rawItems instanceof List) {
            for (Object raw : (List<?>) rawItems) {
                if (!(raw instanceof Map)) {
                    continue;
                }
                Map<?, ?> node = (Map<?, ?>) raw;
                GateItemCode code = safeCode(node.get("code"));
                if (code == null) {
                    continue;
                }
                items.add(new GateResult.GateItem(code, toDouble(node.get("actual")),
                        toDouble(node.get("threshold")), Boolean.TRUE.equals(node.get("pass")),
                        node.get("source") == null ? null : String.valueOf(node.get("source")),
                        node.get("hint") == null ? null : String.valueOf(node.get("hint"))));
            }
        }
        List<String> reasons = new ArrayList<String>();
        Object rawReasons = root.get("reasons");
        if (rawReasons instanceof List) {
            for (Object raw : (List<?>) rawReasons) {
                reasons.add(String.valueOf(raw));
            }
        }
        Instant evaluatedAt = null;
        Object rawTs = root.get("evaluatedAt");
        if (rawTs instanceof Number) {
            evaluatedAt = Instant.ofEpochMilli(((Number) rawTs).longValue());
        }
        return new GateResult(Boolean.TRUE.equals(root.get("pass")), evaluatedAt, items, reasons,
                GateThresholds.defaults());
    }

    /**
     * 安全解析指标编码。
     *
     * @param raw 原始值
     * @return 编码；非法返回 null
     */
    private static GateItemCode safeCode(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return GateItemCode.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 数值宽松转换。
     *
     * @param raw 原始值
     * @return double（无法转换返回 0）
     */
    private static double toDouble(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        if (raw == null) {
            return 0d;
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
