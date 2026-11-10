package com.dbsync.cdc.sdk;

import com.dbsync.cdc.ChangeQueue;
import com.dbsync.cdc.GroupAssigner;
import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.util.HashUtil;
import com.dbsync.common.util.SnowflakeId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单节点双写实现：把业务侧的写操作登记成 {@link ChangeEvent} 并刷入变更队列。
 *
 * <p><b>适用场景</b>：业务与平台部署在同一机房、能共享元数据库，
 * 且源库不允许建触发器时，由业务代码显式调用 {@link #write} / {@link #delete}。
 *
 * <p><b>为什么不直接写目标库</b>：直接写目标库会把"顺序"与"重试"这两件难事
 * 塞给业务代码。登记到队列后，顺序由 {@code seq} + 分组保证，
 * 重试由 {@code GroupWorker} 保证，业务侧只承担一次"本地 INSERT"的开销。
 *
 * <p><b>线程安全</b>：缓冲区用 {@code synchronized} 保护（业务写入路径通常已是单线程/短临界区，
 * 无锁队列在这里没有收益）；{@code seq} 用 {@link AtomicLong} 单调递增。
 */
public class SingleNodeDbSyncWriter implements DbSyncWriter {

    private static final Logger log = LoggerFactory.getLogger(SingleNodeDbSyncWriter.class);

    private final ChangeQueue queue;
    private final long taskId;
    private final Map<String, Long> mappingIdByTable;
    private final Map<String, List<String>> pkColumnsByTable;
    private final int groupCount;
    private final int flushThreshold;
    private final GroupAssigner groupAssigner = new GroupAssigner();
    private final SnowflakeId idGenerator = new SnowflakeId(SnowflakeId.defaultWorkerId());
    private final AtomicLong sequence = new AtomicLong(0L);
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final List<ChangeEvent> buffer = new ArrayList<>();

    /**
     * 构造。
     *
     * @param queue             变更队列
     * @param taskId            任务 ID
     * @param mappingIdByTable  表名 → 表映射 ID（表名大小写不敏感）
     * @param pkColumnsByTable  表名 → 主键列名（顺序即元组顺序）
     * @param groupCount        组数
     * @param flushThreshold    攒够多少条自动刷（≤0 表示只手动刷）
     */
    public SingleNodeDbSyncWriter(ChangeQueue queue, long taskId,
                                  Map<String, Long> mappingIdByTable,
                                  Map<String, List<String>> pkColumnsByTable, int groupCount,
                                  int flushThreshold) {
        this.queue = queue;
        this.taskId = taskId;
        this.mappingIdByTable = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : mappingIdByTable.entrySet()) {
            this.mappingIdByTable.put(entry.getKey().toUpperCase(Locale.ROOT), entry.getValue());
        }
        this.pkColumnsByTable = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : pkColumnsByTable.entrySet()) {
            this.pkColumnsByTable.put(entry.getKey().toUpperCase(Locale.ROOT),
                    List.copyOf(entry.getValue()));
        }
        this.groupCount = groupCount <= 0 ? 16 : groupCount;
        this.flushThreshold = flushThreshold;
    }

    @Override
    public long write(String table, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "双写行数据不能为空");
        }
        long mappingId = mappingIdOf(table);
        List<String> pkColumns = pkColumnsOf(table);
        List<Object> pkTuple = new ArrayList<>(pkColumns.size());
        for (String pk : pkColumns) {
            pkTuple.add(lookup(row, pk));
        }
        append(new ChangeEvent(idGenerator.nextId(), taskId, mappingId,
                groupOf(pkTuple), HashUtil.pkHash(pkTuple), nextSeq(), Instant.now(),
                ChangeOp.UPDATE, pkTuple, null, uppercaseKeys(row), null, ChangeStatus.NEW, 0,
                null, null));
        return 1L;
    }

    @Override
    public long delete(String table, List<Object> pkTuple) {
        long mappingId = mappingIdOf(table);
        List<String> pkColumns = pkColumnsOf(table);
        if (pkTuple == null || pkTuple.size() != pkColumns.size()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "删除主键元组长度与表 " + table + " 的主键列数不一致");
        }
        append(new ChangeEvent(idGenerator.nextId(), taskId, mappingId,
                groupOf(pkTuple), HashUtil.pkHash(pkTuple), nextSeq(), Instant.now(),
                ChangeOp.DELETE, pkTuple, null, null, null, ChangeStatus.NEW, 0, null, null));
        return 1L;
    }

    @Override
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<ChangeEvent> batch = new ArrayList<>(buffer);
        buffer.clear();
        try {
            queue.enqueue(batch);
            healthy.set(true);
            log.debug("双写刷入 {} 条变更 task={}", batch.size(), taskId);
        } catch (RuntimeException e) {
            // 刷失败不能丢：把批次放回缓冲头部，等下次重试（顺序不能乱）
            buffer.addAll(0, batch);
            healthy.set(false);
            throw e;
        }
    }

    @Override
    public boolean isHealthy() {
        return healthy.get();
    }

    /**
     * 当前缓冲区大小（诊断用）。
     *
     * @return 条数
     */
    public synchronized int buffered() {
        return buffer.size();
    }

    /**
     * 追加到缓冲（达到阈值自动刷）。
     *
     * @param event 事件
     */
    private void append(ChangeEvent event) {
        boolean needFlush;
        synchronized (this) {
            buffer.add(event);
            needFlush = flushThreshold > 0 && buffer.size() >= flushThreshold;
        }
        if (needFlush) {
            flush();
        }
    }

    /**
     * 单调序号。
     *
     * @return 序号
     */
    private long nextSeq() {
        return sequence.incrementAndGet();
    }

    /**
     * 计算分组。
     *
     * @param pkTuple   主键元组
     * @param pkColumns 主键列名
     * @return 组号
     */
    private int groupOf(List<Object> pkTuple) {
        // 复用 GroupAssigner 的归一化哈希（K4：归一化单点）
        return groupAssigner.groupOf(pkTuple, null, groupCount);
    }

    /**
     * 表名 → 映射 ID。
     *
     * @param table 表名
     * @return 映射 ID
     */
    private long mappingIdOf(String table) {
        Long id = mappingIdByTable.get(table == null ? "" : table.toUpperCase(Locale.ROOT));
        if (id == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "表 " + table + " 未配置同步映射，无法双写");
        }
        return id.longValue();
    }

    /**
     * 表名 → 主键列。
     *
     * @param table 表名
     * @return 主键列
     */
    private List<String> pkColumnsOf(String table) {
        List<String> columns = pkColumnsByTable.get(table == null ? ""
                : table.toUpperCase(Locale.ROOT));
        if (columns == null || columns.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "表 " + table + " 未配置主键列，无法双写（无主键表不能走增量）");
        }
        return columns;
    }

    /**
     * 大小写不敏感取值。
     *
     * @param row    行
     * @param column 列名
     * @return 值
     */
    private static Object lookup(Map<String, Object> row, String column) {
        if (row.containsKey(column)) {
            return row.get(column);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 键统一大写（与触发器写入的镜像格式保持一致，便于目标端大小写不敏感匹配）。
     *
     * @param row 行
     * @return 大写键的只读行
     */
    private static Map<String, Object> uppercaseKeys(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey() == null ? null
                    : entry.getKey().toUpperCase(Locale.ROOT);
            result.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }
}
