package com.dbsync.migration;

import com.dbsync.common.enums.ScopeType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.exception.LeaseLostException;
import com.dbsync.common.model.Checkpoint;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.Shard;
import com.dbsync.common.util.RetryTemplate;
import com.dbsync.core.checkpoint.CheckpointStore;
import com.dbsync.core.lease.LeaseHandle;
import com.dbsync.core.read.JdbcReader;
import com.dbsync.core.throttle.Throttle;
import com.dbsync.core.write.JdbcBatchWriter;
import com.dbsync.core.write.WriteResult;
import com.dbsync.metadata.dao.ShardDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分片执行器 —— 平台第一不变式（I1）的<b>唯一实现点</b>。
 *
 * <p>时序<b>不可交换</b>（架构 §4.1.2）：
 * <pre>
 * load checkpoint
 * loop:
 *   throttle → read batch → target BEGIN → upsert → COMMIT → checkpoint save → lease assert
 * SUCCESS / FAILED / DEAD
 * </pre>
 *
 * <p><b>为什么 "checkpoint save" 必须写在 "COMMIT" 之后</b>：
 * 反过来的话，进程在"推了断点但还没提交"之间宕机，重启后从新断点开始读，
 * 这一批数据既没进目标库、也不会再被读一次 —— 数据静默丢失，且任何比对都发现不了
 * （目标库本来就"应该"没有它）。6 个违反场景见架构 §4.1.3。
 *
 * <p>本类把顺序写成代码：{@code checkpointStore.save(ckpt, WriteResult.afterCommit(...))}
 * 直接写在 {@code conn.commit()} 的下一行，中间不放任何分支；
 * {@code WriteResult.afterCommit} 是<b>唯一</b>能构造出 {@code committed=true} 的入口，
 * 因此"忘了提交却推了断点"会立刻抛 {@link IllegalStateException}。
 *
 * <p><b>失败策略</b>（架构 V6）：整批重试，不做部分补偿。
 * {@code executeBatch()} 失败后无法可靠得知"哪几行进去了"，
 * 任何"逐行补偿"都是在猜；而 upsert 本身幂等，整批重放一定收敛。
 */
public class ShardWorker {

    private static final Logger log = LoggerFactory.getLogger(ShardWorker.class);

    /** 每处理这么多批校验一次租约（架构 §4.2.5：容忍 1 次心跳丢失）。 */
    static final int LEASE_CHECK_EVERY_BATCHES = 5;

    private final ShardContext ctx;
    private final JdbcReader reader;
    private final JdbcBatchWriter writer;
    private final CheckpointStore checkpointStore;
    private final ShardDao shardDao;
    private final DeadLetterService deadLetterService;
    private final Throttle throttle;
    private final RetryTemplate retry;

    /**
     * 构造。
     *
     * @param ctx              执行上下文
     * @param reader           流式读取器
     * @param writer           批量写入器
     * @param checkpointStore  断点存储（I1 守卫）
     * @param shardDao         分片 DAO
     * @param deadLetterService 死信服务
     * @param throttle         限速器
     * @param retry            重试模板
     */
    public ShardWorker(ShardContext ctx, JdbcReader reader, JdbcBatchWriter writer,
                       CheckpointStore checkpointStore, ShardDao shardDao,
                       DeadLetterService deadLetterService, Throttle throttle,
                       RetryTemplate retry) {
        this.ctx = ctx;
        this.reader = reader;
        this.writer = writer;
        this.checkpointStore = checkpointStore;
        this.shardDao = shardDao;
        this.deadLetterService = deadLetterService;
        this.throttle = throttle == null ? Throttle.unlimited() : throttle;
        this.retry = retry == null ? RetryTemplate.defaults() : retry;
    }

    /**
     * 执行单个分片。
     *
     * <p>返回 {@link ShardOutcome#ABORTED} 表示"收到暂停/取消信号，本批之后主动收手"，
     * 此时分片状态已被 {@link ShardDao#release} 交还（可被重新抢占），
     * 断点保持在最后一次已提交批次的位置。
     *
     * @param shard 分片
     * @param lease 租约句柄（保证同一时刻只有本线程写这个分片）
     * @return 结果
     * @throws LeaseLostException 租约丢失时<b>立即</b>抛出（不写断点、不置状态）
     */
    public ShardOutcome run(Shard shard, LeaseHandle lease) {
        final String owner = lease.owner();
        final long taskId = shard.getTaskId();
        final long mappingId = shard.getTableMappingId();
        final int shardNo = shard.getShardNo();

        shardDao.markRunning(shard.getId(), owner);

        // 1) 加载断点：重启/暂停恢复都从这里续跑，绝不从 0 开始（I2/I3）
        Checkpoint initial = checkpointStore
                .load(taskId, mappingId, ScopeType.SHARD, shardNo)
                .orElseGet(() -> Checkpoint.initial(taskId, mappingId, ScopeType.SHARD, shardNo));
        String lastPk = initial.getLastPk();
        long committedRows = initial.getCommittedRows();
        long committedBatches = initial.getCommittedBatches();
        int version = initial.getVersion();
        int batchesSinceLeaseCheck = 0;
        // 本分片"实际读到多少行"（按表进度的分母）。
        // 断点里已有的 committedRows 是上一次运行的成果（那部分行也是"读到过的"），
        // 因此总读取行数 = 断点已提交行数 + 本次运行读到的行数；两个数分开记，
        // 才能覆盖"重启续跑"与"无主键降级表（读到多于落库）"两种场景。
        final long committedRowsBeforeRun = initial.getCommittedRows();
        long readRowsThisRun = 0L;

        try {
            while (true) {
                if (ctx.stopRequested()) {
                    log.info("分片收到暂停/取消信号，交还租约：shard={} no={} rows={}", shard.getId(),
                            shardNo, committedRows);
                    shardDao.release(shard.getId(), owner);
                    return ShardOutcome.ABORTED;
                }
                if (batchesSinceLeaseCheck >= LEASE_CHECK_EVERY_BATCHES) {
                    // 三层保护的第三层：不靠"以为还持有"，定期向元数据库要答案
                    lease.assertHeld();
                    batchesSinceLeaseCheck = 0;
                }

                // 2) 限速 + 流式读一批（语义 (lastPk, pkEnd]）
                int permits = throttle.acquireReadRows(ctx.getBatchSize());
                Batch batch = readBatch(shard, lastPk, permits);
                if (batch.rows.isEmpty()) {
                    break;
                }
                readRowsThisRun += batch.rows.size();

                // 3) 目标库事务：BEGIN → upsert → COMMIT（整批重试）
                long startedAt = System.currentTimeMillis();
                WriteResult committed = writeWithRetry(batch.rows);
                long cost = System.currentTimeMillis() - startedAt;

                // 4) 只有提交成功之后才允许推进断点（I1）
                Checkpoint next = new Checkpoint(taskId, mappingId, ScopeType.SHARD, shardNo,
                        batch.lastPk, initial.getLastCommitTs(), initial.getLastSeq(),
                        initial.getLastLogId(), committedRows + batch.rows.size(),
                        committedBatches + 1, version);
                checkpointStore.save(next, WriteResult.afterCommit(committed.rowsAffected(), cost));

                // 版本号以元数据库为准：INSERT 路径保留原版本、UPDATE 路径 +1，
                // 本地自增无法同时覆盖两条路径（自增会在首次保存后立刻 CAS 失败）。
                // 这是一次按主键的单行查询，相对一整批数据的搬动可忽略。
                version = checkpointStore
                        .load(taskId, mappingId, ScopeType.SHARD, shardNo)
                        .map(Checkpoint::getVersion).orElse(version + 1);
                lastPk = batch.lastPk;
                committedRows = next.getCommittedRows();
                committedBatches = next.getCommittedBatches();
                batchesSinceLeaseCheck++;

                if (log.isDebugEnabled()) {
                    log.debug("分片进度 shard={} no={} rows={} lastPk={} cost={}ms", shard.getId(),
                            shardNo, committedRows, lastPk, cost);
                }
            }

            // 成功落库时把"实际读取行数"写进 source_rows：按表进度（MIG-06）用它作分母。
            // 用 readRows 而不是 committedRows 作源侧行数，是因为无主键降级表读到的行数
            // 可能多于真正落库的行数，拿提交数冒充会让百分比恒为 100%（掩盖漏数）。
            long sourceRows = committedRowsBeforeRun + readRowsThisRun;
            shardDao.markSuccess(shard.getId(), owner, committedRows, sourceRows);
            log.info("分片完成 shard={} no={} rows={} sourceRows={}", shard.getId(), shardNo,
                    committedRows, sourceRows);
            return ShardOutcome.SUCCESS;
        } catch (LeaseLostException e) {
            // 租约丢了：立刻停手，绝不写断点（写进去会覆盖别人的进度）
            log.warn("分片租约丢失，立即中止：shard={} no={} rows={}", shard.getId(), shardNo,
                    committedRows);
            throw e;
        } catch (RuntimeException e) {
            return handleFailure(shard, owner, committedRows, e);
        }
    }

    /**
     * 读一批。
     *
     * <p>实现要点：{@code JdbcReader} 没有 "LIMIT n" 参数（方言隔离要求分页 SQL 只出现在
     * {@code db-sync-dialect}，而区间读用的是 {@code pk > ? AND pk <= ?}），
     * 因此这里用"回调攒够 batchSize 就抛一个私有信号"的方式提前结束结果集。
     * 下一轮从新的 {@code lastPk} 继续，索引区间扫描的代价与被跳过的行数成正比，
     * 不存在"重扫整个区间"的问题。
     *
     * @param shard   分片
     * @param fromPk  起点（不含），null 表示区间左端无界
     * @param limit   本批最多行数
     * @return 批次
     */
    private Batch readBatch(Shard shard, String fromPk, int limit) {
        int batchSize = Math.max(1, Math.min(limit <= 0 ? ctx.getBatchSize() : limit,
                ctx.getBatchSize()));
        PkRange range = new PkRange(fromPk, shard.getPkEnd(), shard.getRangeKind());
        List<Object[]> rows = new ArrayList<>(batchSize);
        int pkIndex = ctx.primaryKeyIndex();
        String[] lastPkHolder = new String[1];

        Connection connection = null;
        try {
            connection = ctx.getSourceConnections().get();
            reader.read(connection, ctx.getSourceDialect(), ctx.getSourceTable(), range,
                    ctx.getReadOptions(), (row, rowIndex) -> {
                        rows.add(row);
                        if (pkIndex >= 0 && pkIndex < row.length && row[pkIndex] != null) {
                            lastPkHolder[0] = String.valueOf(row[pkIndex]);
                        }
                        if (rows.size() >= batchSize) {
                            throw BatchFullSignal.INSTANCE;
                        }
                    });
        } catch (BatchFullSignal signal) {
            // 预期内的提前收手：结果集已在 JdbcReader 的 finally 里关闭
            log.trace("本批已满 {} 行，提前结束结果集", rows.size());
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.CONN_NETWORK_FAILED,
                    "借源库连接失败：" + e.getMessage()).with("ds", ctx.getMapping().getSourceTable());
        } finally {
            closeQuietly(connection);
        }

        if (rows.isEmpty()) {
            return Batch.EMPTY;
        }
        if (lastPkHolder[0] == null) {
            // 无主键表：区间退化为单区间，用区间右端作为"已读完"的标志，避免死循环
            lastPkHolder[0] = shard.getPkEnd();
        }
        return new Batch(rows, lastPkHolder[0]);
    }

    /**
     * 写一批并提交（整批重试）。
     *
     * @param rows 行数据
     * @return 已提交的写结果（{@code committed=true}）
     */
    private WriteResult writeWithRetry(List<Object[]> rows) {
        return retry.execute(() -> attemptWrite(rows), ShardWorker::isRetryable);
    }

    /**
     * 单次写入尝试：BEGIN → upsert → COMMIT。
     *
     * <p>每次尝试都用<b>新借的</b>连接，避免"上一次尝试留下的坏事务状态"污染下一次。
     *
     * @param rows 行数据
     * @return 已提交的写结果
     */
    private WriteResult attemptWrite(List<Object[]> rows) {
        Connection connection = null;
        try {
            connection = ctx.getTargetConnections().get();
            connection.setAutoCommit(false);
            WriteResult written = writer.upsert(connection, ctx.getTargetDialect(),
                    ctx.getTargetTable(), rows, ctx.getWriteOptions());
            // ★ 提交：这一行成功之后，数据才真正落库
            connection.commit();
            return WriteResult.afterCommit(written.rowsAffected(), written.durationMs());
        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, e,
                    "目标库写入/提交失败：" + e.getMessage())
                    .with("rows", rows.size()).with("table", ctx.getTargetTable().qualifiedName());
        } catch (RuntimeException e) {
            rollbackQuietly(connection);
            throw e;
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * 失败收尾：区分"重试耗尽（毒分片）"与"不可重试（配置/数据问题）"。
     *
     * @param shard         分片
     * @param owner         持有者
     * @param committedRows 已提交行数
     * @param e             异常
     * @return 结果
     */
    private ShardOutcome handleFailure(Shard shard, String owner, long committedRows,
                                       RuntimeException e) {
        boolean exhausted = isExhausted(e);
        String message = describe(e);
        if (exhausted) {
            shardDao.markDead(shard.getId(), message);
            deadLetterService.createShardFailure(shard, ctx.getTableMappingId(), message, e);
            log.error("分片重试耗尽，转入死信：shard={} no={} rows={} cause={}", shard.getId(),
                    shard.getShardNo(), committedRows, message);
            return ShardOutcome.DEAD;
        }
        shardDao.markFailed(shard.getId(), owner, message);
        log.error("分片失败（可重新抢占）：shard={} no={} rows={} cause={}", shard.getId(),
                shard.getShardNo(), committedRows, message, e);
        return ShardOutcome.FAILED;
    }

    /**
     * 是否可重试（依赖 {@link DbSyncException#isRetryable()} 的单一判定，避免两处标准漂移）。
     *
     * @param t 异常
     * @return true 表示可重试
     */
    static boolean isRetryable(Throwable t) {
        if (t instanceof DbSyncException) {
            return ((DbSyncException) t).isRetryable();
        }
        return false;
    }

    /**
     * 是否"重试耗尽"（{@link RetryTemplate} 的收敛结果）。
     *
     * @param t 异常
     * @return true 表示耗尽
     */
    static boolean isExhausted(Throwable t) {
        return t instanceof DbSyncException
                && ((DbSyncException) t).errorCode() == ErrorCode.RETRY_EXHAUSTED;
    }

    /**
     * 异常转可读摘要。
     *
     * @param t 异常
     * @return 摘要
     */
    private static String describe(Throwable t) {
        if (t instanceof DbSyncException) {
            DbSyncException e = (DbSyncException) t;
            return e.errorCode().code() + " " + e.getMessage();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /**
     * 回滚（异常只记日志：此时已在错误路径上，回滚失败不应覆盖真实原因）。
     *
     * @param connection 连接
     */
    private static void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.debug("回滚失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 归还连接（异常只记日志）。
     *
     * @param connection 连接
     */
    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("归还连接失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 一批数据（行 + 本批最后主键）。
     */
    private static final class Batch {

        /** 空批（读取结束）。 */
        private static final Batch EMPTY = new Batch(new ArrayList<>(), null);

        private final List<Object[]> rows;
        private final String lastPk;

        /**
         * 构造。
         *
         * @param rows   行
         * @param lastPk 本批最后主键
         */
        private Batch(List<Object[]> rows, String lastPk) {
            this.rows = rows;
            this.lastPk = lastPk;
        }

        /**
         * @return true 表示没有数据
         */
        private boolean isEmpty() {
            return rows.isEmpty();
        }

        @Override
        public String toString() {
            return "Batch[rows=" + rows.size() + ",lastPk=" + lastPk + "]";
        }
    }

    /**
     * "本批已满"信号（无栈、无消息：纯控制流，不参与日志）。
     */
    private static final class BatchFullSignal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** 单例（无状态）。 */
        private static final BatchFullSignal INSTANCE = new BatchFullSignal();

        /**
         * 构造：关闭栈捕获与抑制，避免每次批满都生成栈（每批一次的热路径）。
         */
        private BatchFullSignal() {
            super(null, null, false, false);
        }
    }

    /** @return 执行上下文（多分片共用，只读） */
    ShardContext context() {
        return ctx;
    }
}
