package com.dbsync.cutover;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.RouteTarget;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.RouteSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 路由注册表（T38）：路由快照的<b>版本化发布中心</b>。
 *
 * <p><b>I6 不变量</b>：{@code routeVersion} 严格单调递增，SDK 只接受
 * {@code routeVersion > localRouteVersion} 的快照（幂等 + 防乱序）。
 * 本类保证两件事：
 * <ol>
 *   <li>同一计划连续 {@link #publish} 一定得到递增版本（即使调用方传了旧版本号）；</li>
 *   <li>{@link #ack} 只接受"当前最新已发布版本"，旧版本 ack 一律拒绝（{@code E6003}）——
 *       否则乱序到达的旧 ack 会把 confirmed 拉回旧状态。</li>
 * </ol>
 *
 * <p><b>本期实现边界（显式说明）</b>：快照保存在进程内存里，<b>不落库</b>。
 * 这样做的理由是：路由的权威副本在 SDK/网关侧（架构 §4.5.1 的"外部生效"），
 * 平台侧只需要"每次下发都能重放当前意图"。多实例部署时由
 * {@link CutoverRecoveryService} 在启动时按 {@code cutover_plan.intent_state} 重新下发，
 * 因此内存丢失不会导致状态错乱（只是多一次下发）。
 *
 * <p>线程安全：内部用 {@link ConcurrentHashMap} + {@link CountDownLatch} 实现 ack 等待。
 */
public class RouteRegistry {

    private static final Logger log = LoggerFactory.getLogger(RouteRegistry.class);

    /** 路由状态桶（把 9 个业务状态折叠成 4 个可观测的路由形态）。 */
    public enum Bucket {
        /** 全源（未切流 / 已回滚）。 */
        SOURCE_ONLY,
        /** 读灰度。 */
        GRAY_READ,
        /** 写灰度（双写）。 */
        GRAY_WRITE,
        /** 读写全目标。 */
        TARGET_FULL
    }

    /** 每个计划的路由运行时状态。 */
    private static final class PlanRoute {

        private volatile RouteSnapshot current;
        private volatile long ackedVersion;
        private final Map<Long, CountDownLatch> waiters = new ConcurrentHashMap<Long, CountDownLatch>();
    }

    private final Map<Long, PlanRoute> routes = new ConcurrentHashMap<Long, PlanRoute>();

    /**
     * 发布快照（版本号由本类统一分配，保证严格递增）。
     *
     * @param planId   计划 ID
     * @param snapshot 快照（其 version 视作"期望版本"，实际发布版本取 max(期望, 当前+1)）
     * @return 实际发布的版本号
     */
    public long publish(long planId, RouteSnapshot snapshot) {
        if (snapshot == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "路由快照不得为空，plan=" + planId);
        }
        PlanRoute route = routeOf(planId);
        synchronized (route) {
            long currentVersion = route.current == null ? 0L : route.current.getVersion();
            long version = Math.max(snapshot.getVersion(), currentVersion + 1L);
            RouteSnapshot published = new RouteSnapshot(planId, version, snapshot.getReadTarget(),
                    snapshot.getReadGrayRatio(), snapshot.isWriteTarget(),
                    snapshot.getWriteGrayRatio(), snapshot.getRules(),
                    snapshot.getCreatedAt() == null ? Instant.now() : snapshot.getCreatedAt());
            route.current = published;
            log.info("路由快照已发布 plan={} version={} read={}/{}% write={}/{}%", planId, version,
                    published.getReadTarget(), published.getReadGrayRatio(),
                    published.isWriteTarget() ? "TARGET" : "SOURCE",
                    published.getWriteGrayRatio());
            return version;
        }
    }

    /**
     * 当前生效（最新发布）的快照。
     *
     * @param planId 计划 ID
     * @return 快照；从未发布过返回 {@code null}
     */
    public RouteSnapshot current(long planId) {
        PlanRoute route = routes.get(Long.valueOf(planId));
        return route == null ? null : route.current;
    }

    /**
     * 当前最新已发布版本。
     *
     * @param planId 计划 ID
     * @return 版本号；从未发布过返回 0
     */
    public long currentVersion(long planId) {
        RouteSnapshot snapshot = current(planId);
        return snapshot == null ? 0L : snapshot.getVersion();
    }

    /**
     * SDK ack（I6：只接受最新已发布版本）。
     *
     * @param planId  计划 ID
     * @param version 被确认的版本
     * @throws DbSyncException {@code E6003} 版本不存在或已过期
     */
    public void ack(long planId, long version) {
        PlanRoute route = routeOf(planId);
        RouteSnapshot snapshot = route.current;
        if (snapshot == null) {
            throw new DbSyncException(ErrorCode.ROUTE_ACK_TIMEOUT,
                    "没有已发布的路由快照，无法确认 plan=" + planId + " version=" + version);
        }
        if (version != snapshot.getVersion()) {
            throw new DbSyncException(ErrorCode.ROUTE_ACK_TIMEOUT,
                    "拒绝旧版本 ack：plan=" + planId + " ack=" + version
                            + " latest=" + snapshot.getVersion());
        }
        route.ackedVersion = version;
        CountDownLatch latch = route.waiters.remove(Long.valueOf(version));
        if (latch != null) {
            latch.countDown();
        }
        log.info("路由已确认 plan={} version={}", planId, version);
    }

    /**
     * 等待 ack（编排器下发后调用）。
     *
     * @param planId  计划 ID
     * @param version 期望版本
     * @param timeout 超时
     * @return true 表示在超时前收到 ack
     */
    public boolean awaitAck(long planId, long version, java.time.Duration timeout) {
        PlanRoute route = routeOf(planId);
        if (isAcked(route, version)) {
            return true;
        }
        long millis = timeout == null ? 30_000L : Math.max(1L, timeout.toMillis());
        CountDownLatch latch = route.waiters.computeIfAbsent(Long.valueOf(version),
                key -> new CountDownLatch(1));
        // 双检：ack 可能恰好落在"首次检查 ackedVersion"与"登记 waiter"之间——
        // 此时 ack() 已把 ackedVersion 推到目标版本，却因找不到 waiter 而不会 countDown。
        // 若不在登记后再查一次，本次调用会白等到超时，即使 ack 早已到达。
        if (isAcked(route, version)) {
            route.waiters.remove(Long.valueOf(version));
            return true;
        }
        boolean awaited = false;
        try {
            awaited = latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            route.waiters.remove(Long.valueOf(version));
        }
        // 兜底：即便等待超时/被中断，只要 ack 已经到达就不能误判为超时
        return awaited || isAcked(route, version);
    }

    /**
     * 判断目标版本是否已被确认。
     *
     * @param route   计划路由槽位
     * @param version 期望版本
     * @return true 表示该版本已被 ack
     */
    private static boolean isAcked(PlanRoute route, long version) {
        return version > 0L && route.ackedVersion >= version;
    }

    /**
     * 最近一次已确认的版本。
     *
     * @param planId 计划 ID
     * @return 版本号（从未 ack 返回 0）
     */
    public long ackedVersion(long planId) {
        PlanRoute route = routes.get(Long.valueOf(planId));
        return route == null ? 0L : route.ackedVersion;
    }

    /**
     * 把快照折叠成可观测的路由形态（恢复服务判断"外部到底在哪"用）。
     *
     * @param snapshot 快照（可 null）
     * @return 状态桶
     */
    public static Bucket bucketOf(RouteSnapshot snapshot) {
        if (snapshot == null) {
            return Bucket.SOURCE_ONLY;
        }
        boolean readTarget = snapshot.getReadTarget() == RouteTarget.TARGET;
        if (!readTarget && !snapshot.isWriteTarget()) {
            return Bucket.SOURCE_ONLY;
        }
        if (readTarget && !snapshot.isWriteTarget()) {
            return Bucket.GRAY_READ;
        }
        if (snapshot.isWriteTarget() && snapshot.getWriteGrayRatio() < 100) {
            return Bucket.GRAY_WRITE;
        }
        return Bucket.TARGET_FULL;
    }

    /**
     * 业务状态对应的路由形态（用于比较"外部形态是否等于 intent 形态"）。
     *
     * @param state 业务状态
     * @return 状态桶
     */
    public static Bucket bucketOf(CutoverState state) {
        if (state == null) {
            return Bucket.SOURCE_ONLY;
        }
        switch (state) {
            case GRAY_READ:
                return Bucket.GRAY_READ;
            case GRAY_WRITE:
                return Bucket.GRAY_WRITE;
            case FULL_TARGET:
            case OBSERVE:
            case DONE:
                return Bucket.TARGET_FULL;
            case DRAFT:
            case PRE_CHECK:
            case PRE_CHECK_FAILED:
            case ROLLED_BACK:
            default:
                return Bucket.SOURCE_ONLY;
        }
    }

    /**
     * 构造全源快照（未切流 / 回滚目标形态）。
     *
     * @param planId  计划 ID
     * @param version 版本
     * @return 快照
     */
    public static RouteSnapshot sourceSnapshot(long planId, long version) {
        return RouteSnapshot.fullSource(planId, version, Instant.now());
    }

    /**
     * 取或建计划的路由槽位。
     *
     * @param planId 计划 ID
     * @return 槽位
     */
    private PlanRoute routeOf(long planId) {
        return routes.computeIfAbsent(Long.valueOf(planId), key -> new PlanRoute());
    }
}
