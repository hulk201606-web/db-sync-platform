package com.dbsync.api.service;

import com.dbsync.api.audit.AuditService;
import com.dbsync.api.dto.cutover.CutoverPlanCreateRequest;
import com.dbsync.api.dto.cutover.CutoverPlanView;
import com.dbsync.api.dto.cutover.CutoverStepView;
import com.dbsync.api.dto.cutover.DryRunView;
import com.dbsync.api.dto.cutover.GateView;
import com.dbsync.api.dto.cutover.RollbackRequest;
import com.dbsync.api.dto.cutover.SkipGateRequest;
import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.model.CutoverStep;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.GateResult;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.options.GateThresholds;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.port.AuditEntry;
import com.dbsync.core.port.AuditPort;
import com.dbsync.cutover.CutoverOrchestrator;
import com.dbsync.cutover.CutoverStateMachine;
import com.dbsync.cutover.GateEvaluator;
import com.dbsync.cutover.RouteRegistry;
import com.dbsync.metadata.dao.CutoverPlanDao;
import com.dbsync.metadata.dao.CutoverStepLogDao;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 切流服务（T41：创建 / 查询 / Gate / 推进 / 跳过 Gate / 回滚 / dry-run）。
 *
 * <p><b>跳过 Gate 三件套</b>（架构 §4.6.3，T41 验收 2/3）：
 * <ol>
 *   <li>非 ADMIN → {@code E8001}（拦截器已挡一层，这里再挡一层防内部调用绕过）；</li>
 *   <li>理由 &lt;10 字 → {@code E1001}；</li>
 *   <li>确认文本 ≠ {@code SKIP-GATE} → {@code E1001}；</li>
 *   <li>PROD 环境缺 {@code secondConfirmUser} → {@code E8002}；</li>
 *   <li>通过后<b>先落审计</b>（含 4 项实测值）再推进，保证"跳过了什么"可追溯。</li>
 * </ol>
 *
 * <p><b>dry-run 不写任何状态</b>（T41 验收 4）：只调 {@link CutoverOrchestrator#dryRun}。
 */
public class CutoverService {

    private static final Logger log = LoggerFactory.getLogger(CutoverService.class);

    /** 跳过理由最小长度。 */
    public static final int MIN_SKIP_REASON_LENGTH = 10;
    /** 回滚理由最小长度。 */
    public static final int MIN_ROLLBACK_REASON_LENGTH = 5;

    private static final DateTimeFormatter PLAN_NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault());

    private final CutoverPlanDao planDao;
    private final CutoverStepLogDao stepLogDao;
    private final CutoverOrchestrator orchestrator;
    private final GateEvaluator gateEvaluator;
    private final CutoverStateMachine stateMachine;
    private final RouteRegistry routeRegistry;
    private final SyncTaskDao syncTaskDao;
    private final DataSourceDao dataSourceDao;
    private final AuditPort auditPort;
    private final SnowflakeId idGenerator;

    /**
     * 构造。
     *
     * @param planDao       计划 DAO
     * @param stepLogDao    步骤 DAO
     * @param orchestrator  编排器
     * @param gateEvaluator Gate 评估器
     * @param stateMachine  状态机
     * @param routeRegistry 路由注册表
     * @param syncTaskDao   任务 DAO
     * @param dataSourceDao 数据源 DAO（判定 PROD 环境）
     * @param auditPort     审计端口
     * @param idGenerator   雪花 ID
     */
    public CutoverService(CutoverPlanDao planDao, CutoverStepLogDao stepLogDao,
                          CutoverOrchestrator orchestrator, GateEvaluator gateEvaluator,
                          CutoverStateMachine stateMachine, RouteRegistry routeRegistry,
                          SyncTaskDao syncTaskDao, DataSourceDao dataSourceDao,
                          AuditPort auditPort, SnowflakeId idGenerator) {
        this.planDao = planDao;
        this.stepLogDao = stepLogDao;
        this.orchestrator = orchestrator;
        this.gateEvaluator = gateEvaluator;
        this.stateMachine = stateMachine;
        this.routeRegistry = routeRegistry;
        this.syncTaskDao = syncTaskDao;
        this.dataSourceDao = dataSourceDao;
        this.auditPort = auditPort;
        this.idGenerator = idGenerator == null
                ? new SnowflakeId(SnowflakeId.defaultWorkerId()) : idGenerator;
    }

    /**
     * 创建计划（初始 DRAFT/DRAFT，路由版本 0）。
     *
     * @param req      请求
     * @param operator 操作人
     * @return 计划视图
     */
    public CutoverPlanView create(CutoverPlanCreateRequest req, String operator) {
        if (req == null || req.taskId() == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "taskId 不能为空");
        }
        SyncTask task = syncTaskDao.findById(req.taskId().longValue()).orElseThrow(
                () -> new DbSyncException(ErrorCode.TASK_NOT_FOUND, req.taskId()));
        GateThresholds defaults = GateThresholds.defaults();
        GateThresholds thresholds = new GateThresholds(
                req.comparePassRate() == null ? defaults.getComparePassRate()
                        : req.comparePassRate().doubleValue(),
                req.lagP95Ms() == null ? defaults.getLagP95Ms() : req.lagP95Ms().longValue(),
                req.dualWriteFailRate() == null ? defaults.getDualWriteFailRate()
                        : req.dualWriteFailRate().doubleValue(),
                req.openDeadLetters() == null ? defaults.getOpenDeadLetters()
                        : req.openDeadLetters().longValue(),
                defaults.getCompareResultMaxAgeMinutes());
        long id = idGenerator.nextId();
        CutoverPlan plan = new CutoverPlan(id, "CT-" + PLAN_NO_FORMAT.format(Instant.now()) + "-"
                + Long.toString(id % 10000L),
                task.getId(), req.name(), CutoverState.DRAFT, CutoverState.DRAFT, 0L,
                CutoverPlanDao.STATUS_ACTIVE, thresholds,
                req.grayRatio() == null ? 5 : req.grayRatio().intValue(),
                req.writeGrayRatio() == null ? 0 : req.writeGrayRatio().intValue());
        planDao.insert(plan, "[]", operator);
        log.info("切流计划已创建 id={} planNo={} task={} by={}", id, plan.getPlanNo(), task.getId(),
                operator);
        return CutoverPlanView.of(planDao.findById(id).orElse(plan));
    }

    /**
     * 计划详情（含步骤历史）。
     *
     * @param planId 计划 ID
     * @return 详情 Map
     */
    public Map<String, Object> detail(long planId) {
        CutoverPlan plan = loadOrThrow(planId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plan", CutoverPlanView.of(plan).toMap());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (CutoverStep step : stepLogDao.findByPlan(planId)) {
            steps.add(CutoverStepView.of(step).toMap());
        }
        data.put("steps", steps);
        data.put("routeVersion", Long.valueOf(routeRegistry.currentVersion(planId)));
        data.put("ackedVersion", Long.valueOf(routeRegistry.ackedVersion(planId)));
        return data;
    }

    /**
     * 列出某任务的全部计划。
     *
     * @param taskId 任务 ID
     * @return 计划列表
     */
    public List<CutoverPlanView> listByTask(long taskId) {
        List<CutoverPlanView> views = new ArrayList<>();
        for (CutoverPlan plan : planDao.findByTask(taskId)) {
            views.add(CutoverPlanView.of(plan));
        }
        return views;
    }

    /**
     * 评估 Gate（并落 {@code gate_snapshot_json}）。
     *
     * @param planId 计划 ID
     * @return Gate 视图
     */
    public GateView gate(long planId) {
        loadOrThrow(planId);
        return GateView.of(orchestrator.evaluateGate(planId));
    }

    /**
     * 推进一步。
     *
     * @param planId   计划 ID
     * @param operator 操作人
     * @return 步骤视图
     */
    public CutoverStepView advance(long planId, String operator) {
        loadOrThrow(planId);
        return CutoverStepView.of(orchestrator.advance(planId, operator));
    }

    /**
     * 跳过 Gate 并推进（三件套校验 + 审计）。
     *
     * @param planId   计划 ID
     * @param req      请求
     * @param operator 操作人（第一个复核人）
     * @param role     操作人角色
     * @return 步骤视图
     */
    public CutoverStepView skipGate(long planId, SkipGateRequest req, String operator, String role) {
        CutoverPlan plan = loadOrThrow(planId);
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new DbSyncException(ErrorCode.NO_PERMISSION, "跳过 Gate 仅 ADMIN 可执行")
                    .with("role", role);
        }
        String reason = req == null ? null : req.reason();
        if (reason == null || reason.trim().length() < MIN_SKIP_REASON_LENGTH) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "跳过 Gate 的理由需 ≥" + MIN_SKIP_REASON_LENGTH + " 字")
                    .with("field", "reason");
        }
        String confirmText = req == null ? null : req.confirmText();
        if (!SkipGateRequest.CONFIRM_TEXT.equals(confirmText)) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "确认文本错误，需精确输入 " + SkipGateRequest.CONFIRM_TEXT)
                    .with("field", "confirmText");
        }
        String env = resolveEnv(plan.getTaskId());
        if ("PROD".equals(env) && (req.secondConfirmUser() == null
                || req.secondConfirmUser().isBlank())) {
            throw new DbSyncException(ErrorCode.SECOND_CONFIRM_REQUIRED,
                    "PROD 环境跳过 Gate 需要双人复核（secondConfirmUser）")
                    .with("env", env);
        }
        // 先评估 Gate 留证据（即使要跳过，也要记录"跳过了什么"）
        GateResult gate = gateEvaluator.evaluate(planId);
        recordSkipAudit(plan, req, operator, role, env, gate);
        CutoverStep step = orchestrator.advanceSkippingGate(planId, operator, reason.trim());
        log.warn("跳过了 Gate plan={} by={} second={} reason={}", planId, operator,
                req.secondConfirmUser(), reason.trim());
        return CutoverStepView.of(step);
    }

    /**
     * 回滚。
     *
     * @param planId   计划 ID
     * @param req      请求
     * @param operator 操作人
     * @return 步骤视图
     */
    public CutoverStepView rollback(long planId, RollbackRequest req, String operator) {
        loadOrThrow(planId);
        String reason = req == null ? null : req.reason();
        if (reason == null || reason.trim().length() < MIN_ROLLBACK_REASON_LENGTH) {
            throw new DbSyncException(ErrorCode.SECOND_CONFIRM_REQUIRED,
                    "回滚原因需 ≥" + MIN_ROLLBACK_REASON_LENGTH + " 字").with("field", "reason");
        }
        if (req.confirmText() != null && !RollbackRequest.CONFIRM_TEXT.equals(req.confirmText())) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "确认文本错误，需精确输入 " + RollbackRequest.CONFIRM_TEXT)
                    .with("field", "confirmText");
        }
        return CutoverStepView.of(orchestrator.rollback(planId, operator, reason.trim()));
    }

    /**
     * dry-run（只列步骤，不写状态）。
     *
     * @param planId 计划 ID
     * @return 视图
     */
    public DryRunView dryRun(long planId) {
        loadOrThrow(planId);
        return DryRunView.of(orchestrator.dryRun(planId));
    }

    /**
     * 计划允许的下一状态（供前端置灰按钮）。
     *
     * @param planId 计划 ID
     * @return 状态名列表
     */
    public List<String> allowedNext(long planId) {
        CutoverPlan plan = loadOrThrow(planId);
        List<String> names = new ArrayList<>();
        for (CutoverState state : plan.getIntentState().allowedNext()) {
            names.add(state.name());
        }
        return names;
    }

    /**
     * 状态机自检（非法跃迁返回 false，供 dry-run 展示）。
     *
     * @param from 源
     * @param to   目标
     * @return 是否允许
     */
    public boolean canTransition(String from, String to) {
        try {
            return stateMachine.canTransition(CutoverState.valueOf(from), CutoverState.valueOf(to));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private CutoverPlan loadOrThrow(long planId) {
        return planDao.findById(planId).orElseThrow(
                () -> new DbSyncException(ErrorCode.PARAM_INVALID, "切流计划不存在：" + planId));
    }

    /**
     * 环境判定：以任务源库环境为准（源库是生产即视为 PROD，最严格）。
     */
    private String resolveEnv(long taskId) {
        return syncTaskDao.findById(taskId)
                .flatMap(task -> dataSourceDao.findById(task.getSourceDsId()))
                .map(DataSourceDef::getEnv)
                .map(env -> env.name())
                .orElse("DEV");
    }

    private void recordSkipAudit(CutoverPlan plan, SkipGateRequest req, String operator, String role,
                                 String env, GateResult gate) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("planNo", plan.getPlanNo());
        params.put("env", env);
        params.put("reason", req.reason().trim());
        params.put("confirmText", req.confirmText());
        params.put("secondConfirmUser", req.secondConfirmUser());
        params.put("gatePass", Boolean.valueOf(gate.isPass()));
        List<Map<String, Object>> items = new ArrayList<>();
        for (GateResult.GateItem item : gate.getItems()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", item.getCode() == null ? null : item.getCode().name());
            row.put("actual", Double.valueOf(item.getActual()));
            row.put("threshold", Double.valueOf(item.getThreshold()));
            row.put("pass", Boolean.valueOf(item.isPass()));
            items.add(row);
        }
        params.put("gateItems", items);
        try {
            auditPort.record(AuditService.success(operator, role, "CUTOVER_SKIP_GATE",
                    "CUTOVER_PLAN", Long.valueOf(plan.getId()), params));
        } catch (RuntimeException e) {
            // AuditPort 实现本应吞异常；这里再兜一层，绝不因审计阻塞跳卡点流程
            log.warn("跳卡点审计失败（已忽略） plan={} err={}", plan.getId(), e.toString());
        }
    }
}
