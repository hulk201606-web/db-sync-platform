package com.dbsync.api.service;

import com.dbsync.api.dto.sdk.RouteAckRequest;
import com.dbsync.api.dto.sdk.RouteAckView;
import com.dbsync.api.dto.sdk.RouteSnapshotView;
import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.RouteTarget;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.model.RouteRule;
import com.dbsync.common.model.RouteSnapshot;
import com.dbsync.cutover.RouteRegistry;
import com.dbsync.cutover.RouteRuleEngine;
import com.dbsync.metadata.dao.CutoverPlanDao;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务侧 SDK 的路由服务（PRD CUT-01：每次变更广播给 SDK，≤3 秒生效）。
 *
 * <p><b>数据来源（谁决定 readFrom / writeTo）</b>——这是本类最容易实现错的点：
 * <ol>
 *   <li>状态取 {@code cutover_plan.confirmed_state}（<b>已确认</b>状态），<b>不是</b>
 *       {@code intent_state}。两段式状态机的语义是"intent 是我们想去的、confirmed 是外部
 *       确认已经到了的"（不变式 I5/I7）；把 intent 下发给业务侧，等于在"网关还没确认生效"
 *       时就告诉所有业务实例改路由，一旦外部动作失败就是全站级不一致；</li>
 *   <li>版本取 {@code cutover_plan.route_version}（平台已下发的版本）；</li>
 *   <li>{@code readFrom / writeTo / grayRatio / rules} 全部来自
 *       {@link RouteRuleEngine#snapshotFor}——<b>不在这里重新实现一遍规则计算</b>，
 *       否则平台侧灰度与 SDK 侧灰度会漂移（两套公式 = 两个真相）。</li>
 * </ol>
 *
 * <p><b>无计划时的兜底</b>（不能 404）：{@code routeVersion=0, state=NONE,
 * readFrom=SOURCE, writeTo=SOURCE, grayRatio=0}。业务侧必须永远拿到一个可用决策，
 * 否则"平台还没建计划/计划被删"会直接把业务打挂。
 *
 * <p>本类不写 {@code @Service}（与其它 api 服务一致，由 server 模块显式装配）。
 */
public class SdkRouteService {

    private static final Logger log = LoggerFactory.getLogger(SdkRouteService.class);

    /** 快照陈旧上限默认值（PRD CUT-01 的 3 秒生效要求）。 */
    public static final long DEFAULT_SNAPSHOT_TTL_MS = 3000L;

    /** 无计划状态码。 */
    public static final String STATE_NONE = "NONE";

    /** ack 结果说明：已采纳。 */
    public static final String NOTE_APPLIED = "APPLIED";
    /** ack 结果说明：版本不新。 */
    public static final String NOTE_STALE = "STALE_ACK";
    /** ack 结果说明：无计划。 */
    public static final String NOTE_NO_PLAN = "NO_PLAN";

    private final CutoverPlanDao planDao;
    private final RouteRuleEngine ruleEngine;
    private final RouteRegistry routeRegistry;
    private final long snapshotTtlMs;

    /**
     * 构造（默认 TTL 3000ms）。
     *
     * @param planDao       计划 DAO
     * @param ruleEngine    规则引擎（快照计算的唯一来源）
     * @param routeRegistry 内存路由注册表（ack 尽力转发，可 null）
     */
    public SdkRouteService(CutoverPlanDao planDao, RouteRuleEngine ruleEngine,
                           RouteRegistry routeRegistry) {
        this(planDao, ruleEngine, routeRegistry, DEFAULT_SNAPSHOT_TTL_MS);
    }

    /**
     * 构造。
     *
     * @param planDao       计划 DAO
     * @param ruleEngine    规则引擎
     * @param routeRegistry 内存路由注册表（可 null）
     * @param snapshotTtlMs 快照陈旧上限（≤0 → 3000）
     */
    public SdkRouteService(CutoverPlanDao planDao, RouteRuleEngine ruleEngine,
                           RouteRegistry routeRegistry, long snapshotTtlMs) {
        this.planDao = planDao;
        this.ruleEngine = ruleEngine;
        this.routeRegistry = routeRegistry;
        this.snapshotTtlMs = snapshotTtlMs <= 0L ? DEFAULT_SNAPSHOT_TTL_MS : snapshotTtlMs;
    }

    /**
     * 取任务当前应下发给 SDK 的路由快照。
     *
     * @param taskId 任务 ID
     * @return 快照视图（无计划时是全源兜底，不抛 404）
     */
    public RouteSnapshotView snapshot(long taskId) {
        if (taskId <= 0L) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "taskId 必须为正数：" + taskId);
        }
        Instant now = Instant.now();
        Optional<CutoverPlan> planOpt = latestPlan(taskId);
        if (planOpt.isEmpty()) {
            log.debug("任务无切流计划，下发全源兜底快照 task={}", Long.valueOf(taskId));
            return new RouteSnapshotView(taskId, 0L, STATE_NONE, RouteTarget.SOURCE.name(),
                    RouteTarget.SOURCE.name(), 0, List.of(), snapshotTtlMs, now.toString());
        }
        CutoverPlan plan = planOpt.get();
        CutoverState confirmed = plan.getConfirmedState();
        long version = plan.getRouteVersion() <= 0L ? 0L : plan.getRouteVersion();
        RouteSnapshot snapshot = ruleEngine.snapshotFor(plan, confirmed, version, now);
        String writeTo = writeToOf(snapshot);
        int grayRatio = confirmed == CutoverState.GRAY_WRITE
                ? snapshot.getWriteGrayRatio() : snapshot.getReadGrayRatio();
        return new RouteSnapshotView(taskId, version, confirmed.name(),
                snapshot.getReadTarget().name(), writeTo, grayRatio,
                renderRules(snapshot.getRules()), snapshotTtlMs, now.toString());
    }

    /**
     * 记录 SDK 已生效的路由版本（只允许单调递增）。
     *
     * <p><b>两道 ack 的分层</b>：库里的 {@code cutover_plan.acked_version} 是权威
     * （跨重启、跨实例可见）；内存 {@link RouteRegistry#ack} 只是"通知正在等待的编排器"
     * （{@code CutoverOrchestrator} 在 publish 后等 ack）。因此这里<b>先落库</b>，
     * 再尽力转发给内存注册表；转发失败（版本不是最新已发布 / 进程重启后内存为空）只记 debug，
     * 不影响返回结果。
     *
     * @param req 请求
     * @return 结果（{@code applied=false} 表示未采纳，原因见 {@code note}）
     */
    public RouteAckView ack(RouteAckRequest req) {
        if (req == null || req.taskId() == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "taskId 不能为空");
        }
        long taskId = req.taskId().longValue();
        if (taskId <= 0L) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "taskId 必须为正数：" + taskId);
        }
        if (req.ackedVersion() == null || req.ackedVersion().longValue() < 0L) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "ackedVersion 必须 ≥0");
        }
        long ackedVersion = req.ackedVersion().longValue();
        String instanceId = req.instanceId();
        Optional<CutoverPlan> planOpt = latestPlan(taskId);
        if (planOpt.isEmpty()) {
            log.info("SDK ack 被忽略（任务无切流计划）task={} instance={} ack={}", Long.valueOf(taskId),
                    instanceId, Long.valueOf(ackedVersion));
            return new RouteAckView(taskId, 0L, instanceId, ackedVersion, 0L, false, NOTE_NO_PLAN);
        }
        CutoverPlan plan = planOpt.get();
        long planId = plan.getId();
        long previous = planDao.ackedVersionOf(planId);
        long base = previous < 0L ? 0L : previous;
        int updated = planDao.updateAckedVersion(planId, ackedVersion);
        if (updated <= 0) {
            log.info("SDK ack 未推进（版本不新）plan={} instance={} ack={} 当前={}",
                    Long.valueOf(planId), instanceId, Long.valueOf(ackedVersion),
                    Long.valueOf(base));
            return new RouteAckView(taskId, planId, instanceId, ackedVersion, base, false,
                    NOTE_STALE);
        }
        forwardToRegistry(planId, ackedVersion);
        log.info("SDK ack 已落库 plan={} instance={} version {}->{}", Long.valueOf(planId),
                instanceId, Long.valueOf(base), Long.valueOf(ackedVersion));
        return new RouteAckView(taskId, planId, instanceId, ackedVersion, base, true, NOTE_APPLIED);
    }

    /**
     * 尽力把 ack 转发给内存注册表（解除编排器的 {@code awaitAck}）。
     *
     * @param planId 计划 ID
     * @param version 版本
     */
    private void forwardToRegistry(long planId, long version) {
        if (routeRegistry == null) {
            return;
        }
        try {
            routeRegistry.ack(planId, version);
        } catch (RuntimeException e) {
            // 正常情形：该版本不是"最新已发布版本"（SDK 的 ack 落后于下一次下发），
            // 或进程刚重启内存里还没有快照。都不影响库里的权威记录。
            log.debug("ack 转发内存注册表失败（不影响落库结果）plan={} version={} err={}",
                    Long.valueOf(planId), Long.valueOf(version), e.toString());
        }
    }

    /**
     * 最新计划（{@code created_at DESC}，DAO 已按倒序返回）。
     *
     * @param taskId 任务 ID
     * @return 计划
     */
    private Optional<CutoverPlan> latestPlan(long taskId) {
        List<CutoverPlan> plans = planDao.findByTask(taskId);
        return plans.isEmpty() ? Optional.empty() : Optional.of(plans.get(0));
    }

    /**
     * 写目标：由快照的 {@code writeTarget + writeGrayRatio} 折叠成三态字符串。
     *
     * @param snapshot 快照
     * @return {@code SOURCE}/{@code BOTH}/{@code TARGET}
     */
    private static String writeToOf(RouteSnapshot snapshot) {
        if (!snapshot.isWriteTarget()) {
            return RouteTarget.SOURCE.name();
        }
        return snapshot.getWriteGrayRatio() >= 100 ? RouteTarget.TARGET.name() : "BOTH";
    }

    /**
     * 规则渲染：既给机器字段（SDK 解析用），也给 {@code dim/expr}（人读）。
     *
     * @param rules 规则列表
     * @return 渲染结果
     */
    private static List<Map<String, Object>> renderRules(List<RouteRule> rules) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (rules == null) {
            return rows;
        }
        for (RouteRule rule : rules) {
            if (rule == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            List<String> dims = new ArrayList<>();
            List<String> exprs = new ArrayList<>();
            if (rule.getRatio() != null) {
                dims.add("RATIO");
                exprs.add("hash(pk)%100 < " + rule.getRatio().intValue());
            }
            if (rule.getTableWhitelist() != null && !rule.getTableWhitelist().isEmpty()) {
                dims.add("TABLE_WHITELIST");
                exprs.add("table in (" + String.join(", ",
                        rule.getTableWhitelist().toArray(new String[0])) + ")");
            }
            if (rule.getHashBucketMod() != null && rule.getHashBucketMod().intValue() > 0) {
                dims.add("HASH_BUCKET");
                int index = rule.getHashBucketIndex() == null ? 0
                        : rule.getHashBucketIndex().intValue();
                exprs.add("fnv1a64(pk)%" + rule.getHashBucketMod().intValue() + " == " + index);
            }
            String dim = dims.isEmpty() ? "ALL" : (dims.size() == 1 ? dims.get(0) : "COMBO");
            row.put("dim", dim.toUpperCase(Locale.ROOT));
            row.put("expr", exprs.isEmpty() ? "true" : String.join(" AND ", exprs));
            row.put("ratio", rule.getRatio());
            row.put("tableWhitelist", rule.getTableWhitelist());
            row.put("hashBucket", rule.getHashBucketMod() == null ? null
                    : Map.of("mod", rule.getHashBucketMod(),
                            "index", Integer.valueOf(rule.getHashBucketIndex() == null ? 0
                                    : rule.getHashBucketIndex().intValue())));
            row.put("enabled", Boolean.valueOf(rule.isEnabled()));
            rows.add(row);
        }
        return rows;
    }

    /** @return 快照陈旧上限（毫秒） */
    public long snapshotTtlMs() {
        return snapshotTtlMs;
    }
}
