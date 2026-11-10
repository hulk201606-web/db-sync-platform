package com.dbsync.api.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 同步指标（架构 §11 的 15 个 {@code dbsync.*} 指标，口径严格按文档）。
 *
 * <p><b>关于 TPS</b>（T44 验收 4 的坑）：必须是<b>滑窗</b>（12 个 5 秒桶，共 60 秒）而不是
 * 瞬时值，否则"批提交抖动"会让大盘 TPS 在 0 与峰值之间跳变。
 * 实现：每 5 秒把计数滚入环形数组，TPS = 12 桶合计 / 60。
 *
 * <p><b>关于 Prometheus 可选</b>：{@code db-sync.observability.prometheus-enabled=false} 时
 * 服务端装配的是 {@code SimpleMeterRegistry}（不暴露 {@code /actuator/prometheus}），
 * 本类不感知差异，只面向 {@link MeterRegistry} 编程。
 *
 * <p><b>Gauge 的 key 约定</b>：{@code "指标名|tag1=v1,tag2=v2"}，例如
 * {@code "migration.tps|taskId=1001"}。首次出现时注册到 Micrometer，
 * 之后只更新值（Gauge 是"读时求值"，不会因更新而重复注册）。
 */
public class SyncMetrics {

    /** 指标前缀。 */
    public static final String PREFIX = "dbsync.";

    /** 滑窗桶数（12 × 5s = 60s）。 */
    public static final int TPS_BUCKETS = 12;
    /** 桶宽度（毫秒）。 */
    public static final long TPS_BUCKET_MS = 5000L;

    /** 指标名（不含前缀）。 */
    private static final String[] GAUGE_NAMES = {
        "migration.tps", "migration.shards", "cdc.lag.p95.ms", "cdc.backlog",
        "cdc.apply.fail.rate", "deadletter.open", "compare.blocks", "compare.pass.rate",
        "cutover.state", "instance.shards", "pool.active"
    };

    private final MeterRegistry registry;
    private final Map<String, Counter> rowsCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> batchTimers = new ConcurrentHashMap<>();
    private final Map<Long, TpsWindow> tpsWindows = new ConcurrentHashMap<>();
    private final Map<String, Holder> gauges = new ConcurrentHashMap<>();
    private final Counter leaseTakeover;
    private final Counter cdcApplied;

    /**
     * 构造并预注册全部 Gauge（保证 {@code /actuator/prometheus} 里 15 个指标都存在）。
     *
     * @param registry Micrometer 注册表
     */
    public SyncMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.leaseTakeover = Counter.builder(PREFIX + "lease.takeover.total")
                .description("分片租约接管次数（>0 说明有实例不稳定）")
                .register(registry);
        this.cdcApplied = Counter.builder(PREFIX + "cdc.apply.rate")
                .description("增量应用成功条数（速率由 Prometheus 按秒计算）")
                .register(registry);
        for (String name : GAUGE_NAMES) {
            holder(name, Map.of());
        }
    }

    // ------------------------------------------------------------------
    // 写路径
    // ------------------------------------------------------------------

    /**
     * 单批提交成功回调（{@code JdbcBatchWriter} 提交后调用）。
     *
     * @param taskId     任务 ID
     * @param table      表名
     * @param rows       影响行数
     * @param durationMs 批次耗时
     */
    public void onBatchCommitted(long taskId, String table, long rows, long durationMs) {
        if (rows > 0L) {
            rowsCounter(taskId, table).increment((double) rows);
            tpsWindow(taskId).add(rows, System.currentTimeMillis());
        }
        if (durationMs >= 0L) {
            batchTimer(table).record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 增量应用成功回调。
     *
     * @param rows 条数
     */
    public void onChangesApplied(long rows) {
        if (rows > 0L) {
            cdcApplied.increment((double) rows);
        }
    }

    /**
     * 租约接管回调（{@code ShardLeaseManager.takeOver} 后调用）。
     *
     * @param count 接管数
     */
    public void onLeaseTakeover(long count) {
        if (count > 0L) {
            leaseTakeover.increment((double) count);
        }
    }

    /**
     * 设置 Gauge 值。
     *
     * @param key   形如 {@code migration.tps|taskId=1001}；只写指标名表示无 tag
     * @param value 值
     */
    public void setGauge(String key, double value) {
        int bar = key.indexOf('|');
        String name = bar < 0 ? key : key.substring(0, bar);
        Map<String, String> tags = bar < 0 ? Map.of() : parseTags(key.substring(bar + 1));
        holder(name, tags).value = value;
    }

    /**
     * 读取 Gauge 当前值（测试与大盘用）。
     *
     * @param key 与 {@link #setGauge} 相同
     * @return 值；不存在返回 0
     */
    public double gauge(String key) {
        Holder holder = gauges.get(canonical(key));
        return holder == null ? 0.0d : holder.value;
    }

    /**
     * 当前 TPS（12×5s 滑窗）。
     *
     * @param taskId 任务 ID
     * @return rows/秒
     */
    public double tps(long taskId) {
        TpsWindow window = tpsWindows.get(Long.valueOf(taskId));
        double value = window == null ? 0.0d : window.tps(System.currentTimeMillis());
        setGauge("migration.tps|taskId=" + taskId, value);
        return value;
    }

    /**
     * 已提交总行数。
     *
     * @param taskId 任务 ID
     * @param table  表名
     * @return 行数
     */
    public double totalRows(long taskId, String table) {
        Counter counter = rowsCounters.get(rowKey(taskId, table));
        return counter == null ? 0.0d : counter.count();
    }

    /**
     * 注册表（供 server 装配 / 测试断言）。
     *
     * @return 注册表
     */
    public MeterRegistry registry() {
        return registry;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Holder holder(String name, Map<String, String> tags) {
        String key = canonical(name, tags);
        Holder existing = gauges.get(key);
        if (existing != null) {
            return existing;
        }
        Holder created = new Holder();
        Holder previous = gauges.putIfAbsent(key, created);
        if (previous != null) {
            return previous;
        }
        Gauge.Builder<Holder> builder = Gauge.builder(PREFIX + name, created, h -> h.value)
                .description(descriptionOf(name))
                .strongReference(true);
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            builder = builder.tag(tag.getKey(), tag.getValue());
        }
        builder.register(registry);
        return created;
    }

    private static String descriptionOf(String name) {
        switch (name) {
            case "migration.tps":
                return "最近 60 秒迁移行数 / 60（12 × 5s 滑窗）";
            case "migration.shards":
                return "分片数（按 task/state）";
            case "cdc.lag.p95.ms":
                return "增量延迟 P95（毫秒，15 分钟窗口）";
            case "cdc.backlog":
                return "增量积压条数（status=NEW）";
            case "cdc.apply.fail.rate":
                return "最近 15 分钟失败率（分母不含 DEAD）";
            case "deadletter.open":
                return "未处理死信数";
            case "compare.blocks":
                return "比对块数（按 matched）";
            case "compare.pass.rate":
                return "比对通过率";
            case "cutover.state":
                return "切流状态（DRAFT=0 … ROLLED_BACK=7，按 planId）";
            case "instance.shards":
                return "本实例持有的 RUNNING 分片数";
            case "pool.active":
                return "连接池活跃连接数（按数据源名）";
            default:
                return name;
        }
    }

    private Counter rowsCounter(long taskId, String table) {
        return rowsCounters.computeIfAbsent(rowKey(taskId, table),
                key -> Counter.builder(PREFIX + "migration.rows.total")
                        .description("累计迁移行数（Σ WriteResult.rowsAffected）")
                        .tag("taskId", String.valueOf(taskId))
                        .tag("table", table == null ? "unknown" : table)
                        .register(registry));
    }

    private Timer batchTimer(String table) {
        return batchTimers.computeIfAbsent(table == null ? "unknown" : table,
                key -> Timer.builder(PREFIX + "batch.duration")
                        .description("单批 upsert 耗时")
                        .tag("table", key)
                        .publishPercentiles(0.5d, 0.95d, 0.99d)
                        .register(registry));
    }

    private TpsWindow tpsWindow(long taskId) {
        return tpsWindows.computeIfAbsent(Long.valueOf(taskId),
                key -> new TpsWindow(System.currentTimeMillis()));
    }

    private static String rowKey(long taskId, String table) {
        return taskId + "|" + (table == null ? "unknown" : table);
    }

    private static String canonical(String key) {
        int bar = key.indexOf('|');
        if (bar < 0) {
            return key;
        }
        return canonical(key.substring(0, bar), parseTags(key.substring(bar + 1)));
    }

    private static String canonical(String name, Map<String, String> tags) {
        if (tags.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name).append('|');
        boolean first = true;
        for (Map.Entry<String, String> tag : new java.util.TreeMap<>(tags).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(tag.getKey()).append('=').append(tag.getValue());
            first = false;
        }
        return sb.toString();
    }

    private static Map<String, String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        for (String part : raw.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                tags.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return tags;
    }

    /** Gauge 值容器（Gauge 读时取值，故只暴露 volatile double）。 */
    private static final class Holder {
        private volatile double value;
    }

    /**
     * 12 × 5s 环形滑窗。
     */
    static final class TpsWindow {
        private final long[] buckets = new long[TPS_BUCKETS];
        private final long[] bucketStarts = new long[TPS_BUCKETS];
        private int cursor;

        TpsWindow(long now) {
            for (int i = 0; i < TPS_BUCKETS; i++) {
                bucketStarts[i] = now - (long) i * TPS_BUCKET_MS;
                buckets[i] = 0L;
            }
        }

        synchronized void add(long rows, long now) {
            rotate(now);
            buckets[cursor] = buckets[cursor] + rows;
        }

        synchronized double tps(long now) {
            rotate(now);
            long sum = 0L;
            for (long bucket : buckets) {
                sum += bucket;
            }
            return sum / ((TPS_BUCKETS * TPS_BUCKET_MS) / 1000.0d);
        }

        private void rotate(long now) {
            long start = bucketStarts[cursor];
            long elapsedBuckets = (now - start) / TPS_BUCKET_MS;
            if (elapsedBuckets <= 0L) {
                return;
            }
            long steps = Math.min(elapsedBuckets, TPS_BUCKETS);
            for (long i = 0L; i < steps; i++) {
                cursor = (cursor + 1) % TPS_BUCKETS;
                buckets[cursor] = 0L;
                bucketStarts[cursor] = start + (i + 1L) * TPS_BUCKET_MS;
            }
        }
    }
}
