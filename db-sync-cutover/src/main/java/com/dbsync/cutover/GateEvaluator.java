package com.dbsync.cutover;

import com.dbsync.common.enums.GateItemCode;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.model.CompareVerdictSnapshot;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.model.GateResult;
import com.dbsync.common.options.GateThresholds;
import com.dbsync.core.port.CompareVerdictPort;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.metadata.dao.CutoverPlanDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gate 评估器（T39）：四项指标的<b>唯一</b>判定点（卡点不可绕过）。
 *
 * <p><b>为什么阈值只在服务端</b>（架构 §4.6.2）：前端只拿 {@code items[].pass} 展示，
 * 不做任何比较。这样"绕过卡点"必须改服务端代码/审计，而不是改前端 JS 或伪造请求参数。
 *
 * <table border="1">
 *   <tr><th>指标</th><th>取数</th><th>特殊处理</th></tr>
 *   <tr><td>G1 COMPARE_PASS_RATE</td><td>{@link CompareVerdictPort#latestVerdict}</td>
 *       <td>无结果 → BLOCK；超过 {@code compareResultMaxAgeMinutes}（默认 6h）→ BLOCK（"比对结果已过期"）</td></tr>
 *   <tr><td>G2 INCREMENT_LAG_P95</td><td>{@link IncrementalMetricsPort#latencyP95}</td>
 *       <td>0 表示"无增量数据" → 视为通过并给 hint</td></tr>
 *   <tr><td>G3 DUAL_WRITE_FAIL_RATE</td><td>{@link IncrementalMetricsPort#failRate}</td>
 *       <td>窗口固定 15 分钟（与架构 §4.6.1 一致）</td></tr>
 *   <tr><td>G4 OPEN_DEAD_LETTERS</td><td>{@link DeadLetterDao#countOpen}</td>
 *       <td>必须 {@code == 阈值}（默认 0），不是 ≤</td></tr>
 * </table>
 *
 * <p><b>端口异常也算不通过</b>：端口契约要求"永不抛异常"，但 Gate 是最后一道闸门，
 * 这里仍做兜底捕获 —— 取数失败绝不等于通过（宁可拦住，不可放过）。
 */
public class GateEvaluator {

    private static final Logger log = LoggerFactory.getLogger(GateEvaluator.class);

    /** 双写失败率的统计窗口（架构 §4.6.1）。 */
    public static final Duration FAIL_RATE_WINDOW = Duration.ofMinutes(15L);

    private final CompareVerdictPort compareVerdictPort;
    private final IncrementalMetricsPort metricsPort;
    private final DeadLetterDao deadLetterDao;
    private final CutoverPlanDao planDao;

    /**
     * 构造。
     *
     * @param compareVerdictPort 比对结论端口
     * @param metricsPort        增量指标端口
     * @param deadLetterDao      死信 DAO
     * @param planDao            计划 DAO（取任务 ID 与阈值）
     */
    public GateEvaluator(CompareVerdictPort compareVerdictPort, IncrementalMetricsPort metricsPort,
                         DeadLetterDao deadLetterDao, CutoverPlanDao planDao) {
        this.compareVerdictPort = compareVerdictPort;
        this.metricsPort = metricsPort;
        this.deadLetterDao = deadLetterDao;
        this.planDao = planDao;
    }

    /**
     * 评估四项指标。
     *
     * @param planId 计划 ID
     * @return 判定结果（含每项 actual/threshold/pass/source/hint）
     * @throws DbSyncException 计划不存在
     */
    public GateResult evaluate(long planId) {
        CutoverPlan plan = planDao.findById(planId).orElseThrow(
                () -> new DbSyncException(com.dbsync.common.exception.ErrorCode.PARAM_INVALID,
                        "切流计划不存在：" + planId));
        GateThresholds thresholds = plan.getGateThresholds();
        long taskId = plan.getTaskId();
        Instant now = Instant.now();

        List<GateResult.GateItem> items = new ArrayList<GateResult.GateItem>(4);
        items.add(compareItem(taskId, thresholds, now));
        items.add(lagItem(taskId, thresholds));
        items.add(failRateItem(taskId, thresholds));
        items.add(deadLetterItem(taskId, thresholds));

        GateResult result = GateResult.of(items, thresholds, now);
        log.info("Gate 评估 plan={} task={} pass={} items={}", planId, taskId, result.isPass(),
                items);
        return result;
    }

    /**
     * G1：比对通过率（含新鲜度校验）。
     *
     * @param taskId     任务 ID
     * @param thresholds 阈值
     * @param now        当前时间
     * @return 指标项
     */
    private GateResult.GateItem compareItem(long taskId, GateThresholds thresholds, Instant now) {
        Optional<CompareVerdictSnapshot> snapshot;
        try {
            snapshot = compareVerdictPort.latestVerdict(taskId);
        } catch (RuntimeException e) {
            return new GateResult.GateItem(GateItemCode.COMPARE_PASS_RATE, 0.0d,
                    thresholds.getComparePassRate(), false, "compare-verdict-port",
                    "取比对结论失败：" + e.getMessage());
        }
        if (snapshot == null || !snapshot.isPresent()) {
            return new GateResult.GateItem(GateItemCode.COMPARE_PASS_RATE, 0.0d,
                    thresholds.getComparePassRate(), false, "compare_verdict",
                    "无有效比对结论，请先执行比对");
        }
        CompareVerdictSnapshot verdict = snapshot.get();
        long ageMinutes = Duration.between(verdict.getFinishedAt(), now).toMinutes();
        if (ageMinutes > thresholds.getCompareResultMaxAgeMinutes()) {
            return new GateResult.GateItem(GateItemCode.COMPARE_PASS_RATE, verdict.getPassRate(),
                    thresholds.getComparePassRate(), false, "compare_verdict(" + ageMinutes + "min)",
                    "比对结果已过期，请重新比对");
        }
        boolean pass = verdict.isAcceptable()
                && verdict.getPassRate() >= thresholds.getComparePassRate();
        String hint = hintFor(verdict, pass, ageMinutes);
        return new GateResult.GateItem(GateItemCode.COMPARE_PASS_RATE, verdict.getPassRate(),
                thresholds.getComparePassRate(), pass,
                "compare_verdict(job=" + verdict.getJobId() + "," + ageMinutes + "min)", hint);
    }

    /**
     * G1 的提示语（WARN 也要让运维看清"为什么算通过"）。
     *
     * @param verdict    结论
     * @param pass       是否通过
     * @param ageMinutes 结果年龄
     * @return 提示
     */
    private static String hintFor(CompareVerdictSnapshot verdict, boolean pass, long ageMinutes) {
        if (!pass) {
            return "通过率 " + verdict.getPassRate() + " 低于阈值或结论为 " + verdict.getVerdict();
        }
        if ("WARN".equals(verdict.getVerdict())) {
            return "结论为 WARN：差异在容忍区间内，建议关注报告（" + ageMinutes + " 分钟前）";
        }
        return "结论 PASS（" + ageMinutes + " 分钟前）";
    }

    /**
     * G2：增量延迟 P95。
     *
     * @param taskId     任务 ID
     * @param thresholds 阈值
     * @return 指标项
     */
    private GateResult.GateItem lagItem(long taskId, GateThresholds thresholds) {
        long lag;
        try {
            lag = metricsPort.latencyP95(taskId);
        } catch (RuntimeException e) {
            return new GateResult.GateItem(GateItemCode.INCREMENT_LAG_P95, -1.0d,
                    thresholds.getLagP95Ms(), false, "incremental-metrics-port",
                    "取延迟失败：" + e.getMessage());
        }
        if (lag <= 0L) {
            // 0 表示窗口内没有任何增量数据：没有积压，等价于"延迟 0"，放行并显式提示
            return new GateResult.GateItem(GateItemCode.INCREMENT_LAG_P95, 0.0d,
                    thresholds.getLagP95Ms(), true, "watermark.p95", "无增量数据");
        }
        return new GateResult.GateItem(GateItemCode.INCREMENT_LAG_P95, lag,
                thresholds.getLagP95Ms(), lag <= thresholds.getLagP95Ms(), "watermark.p95", null);
    }

    /**
     * G3：双写/应用失败率。
     *
     * @param taskId     任务 ID
     * @param thresholds 阈值
     * @return 指标项
     */
    private GateResult.GateItem failRateItem(long taskId, GateThresholds thresholds) {
        double rate;
        try {
            rate = metricsPort.failRate(taskId, FAIL_RATE_WINDOW);
        } catch (RuntimeException e) {
            return new GateResult.GateItem(GateItemCode.DUAL_WRITE_FAIL_RATE, 1.0d,
                    thresholds.getDualWriteFailRate(), false, "incremental-metrics-port",
                    "取失败率失败：" + e.getMessage());
        }
        return new GateResult.GateItem(GateItemCode.DUAL_WRITE_FAIL_RATE, rate,
                thresholds.getDualWriteFailRate(), rate <= thresholds.getDualWriteFailRate(),
                "change_log.fail_rate(15min)", null);
    }

    /**
     * G4：未处理死信数（必须严格等于阈值，默认 0）。
     *
     * @param taskId     任务 ID
     * @param thresholds 阈值
     * @return 指标项
     */
    private GateResult.GateItem deadLetterItem(long taskId, GateThresholds thresholds) {
        long open;
        try {
            open = deadLetterDao.countOpen(taskId);
        } catch (RuntimeException e) {
            return new GateResult.GateItem(GateItemCode.OPEN_DEAD_LETTERS, -1.0d,
                    thresholds.getOpenDeadLetters(), false, "dead_letter",
                    "取死信数失败：" + e.getMessage());
        }
        boolean pass = open == thresholds.getOpenDeadLetters();
        return new GateResult.GateItem(GateItemCode.OPEN_DEAD_LETTERS, open,
                thresholds.getOpenDeadLetters(), pass, "dead_letter.open",
                pass ? null : "存在未处理死信，请先在死信中心重放或丢弃");
    }
}
