package com.dbsync.compare;

import com.dbsync.common.enums.CompareVerdict;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.BlockResult;
import com.dbsync.common.model.CompareResult;
import com.dbsync.common.model.DiffRecord;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.CompareOptions;
import com.dbsync.common.options.NormalizeOptions;
import com.dbsync.common.util.JsonUtil;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.common.util.ThreadPoolFactory;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.read.JdbcReader;
import com.dbsync.metadata.dao.CompareBlockDao;
import com.dbsync.metadata.dao.CompareDiffDao;
import com.dbsync.metadata.dao.CompareJobDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 比对引擎（T35）：编排"分块 → 并发三方校验和 → 差异块下钻 → 汇总"。
 *
 * <p><b>K4 强约束</b>：一次 {@code run} 内部只创建一个 {@link Normalizer} 实例，
 * 同时注入 {@link ChecksumCalculator}（算哈希）与 {@link RowDiffer}（逐列比较），
 * 且该实例的 {@link NormalizeOptions#toReportMap()} 快照写进 {@code sync_compare_job.normalize_json}
 * （CMP-05：报告中必须能证明"当时开了哪些规则"）。
 *
 * <p><b>取消语义</b>：{@link #cancel(long)} 置位后，已完成块的结果仍会落库（便于排查），
 * 但<b>不再提交新块</b>、不再下钻，作业状态置 {@code CANCELED}。
 * {@link #cancelTask(long)} 是"预先取消"钩子：作业创建后立刻进入 {@code CANCELED}，
 * 一个块都不算（用于 API 侧先取消后仍收到调度请求的竞态）。
 *
 * <p>线程安全：本类可被多个任务并发调用（状态都在方法内部的局部变量 + 两个 ConcurrentHashMap 里）。
 */
public class CompareEngine {

    private static final Logger log = LoggerFactory.getLogger(CompareEngine.class);

    /** 每提交多少块刷新一次作业进度（避免每块一次 UPDATE 把元数据库打满）。 */
    private static final int PROGRESS_INTERVAL = 8;

    /** 块结果批量落库的批大小。 */
    private static final int BLOCK_INSERT_BATCH = 200;

    /** 差异明细批量落库的批大小。 */
    private static final int DIFF_INSERT_BATCH = 500;

    private final SyncTaskDao syncTaskDao;
    private final TableMappingDao tableMappingDao;
    private final CompareJobDao jobDao;
    private final CompareBlockDao blockDao;
    private final CompareDiffDao diffDao;
    private final DataSourceRegistry registry;
    private final BlockPlanner blockPlanner;
    private final SnowflakeId idGenerator;
    private final JdbcReader reader;

    /** 作业级取消标志。 */
    private final Map<Long, AtomicBoolean> jobCancels = new ConcurrentHashMap<Long, AtomicBoolean>();
    /** 任务级"预先取消"标志（作业创建时消费一次）。 */
    private final Map<Long, AtomicBoolean> taskCancels = new ConcurrentHashMap<Long, AtomicBoolean>();

    /**
     * 构造。
     *
     * @param syncTaskDao    任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param jobDao         作业 DAO
     * @param blockDao       块 DAO
     * @param diffDao        差异 DAO
     * @param registry       数据源注册表
     * @param blockPlanner   块规划器
     * @param idGenerator    ID 生成器
     */
    public CompareEngine(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao,
                         CompareJobDao jobDao, CompareBlockDao blockDao, CompareDiffDao diffDao,
                         DataSourceRegistry registry, BlockPlanner blockPlanner,
                         SnowflakeId idGenerator) {
        this.syncTaskDao = syncTaskDao;
        this.tableMappingDao = tableMappingDao;
        this.jobDao = jobDao;
        this.blockDao = blockDao;
        this.diffDao = diffDao;
        this.registry = registry;
        this.blockPlanner = blockPlanner == null ? new BlockPlanner() : blockPlanner;
        this.idGenerator = idGenerator == null ? new SnowflakeId(SnowflakeId.defaultWorkerId())
                : idGenerator;
        this.reader = new JdbcReader();
    }

    /**
     * 执行一次比对。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 只比对某张表（null 表示任务下全部启用的表）
     * @param opts           比对选项（null 取默认）
     * @return 比对结果
     * @throws DbSyncException 任务不存在 / 无启用映射 / 分块失败
     */
    public CompareResult run(long taskId, Long tableMappingId, CompareOptions opts) {
        CompareOptions options = opts == null ? CompareOptions.defaults() : opts;
        NormalizeOptions normalize = options.getNormalize();
        Normalizer normalizer = new Normalizer(normalize);
        RowHashBuilder hashBuilder = new RowHashBuilder(normalizer);
        ChecksumCalculator checksumCalculator = new ChecksumCalculator(reader, hashBuilder);
        RowDiffer rowDiffer = new RowDiffer(reader, hashBuilder);

        SyncTask task = syncTaskDao.findById(taskId).orElseThrow(
                () -> new DbSyncException(ErrorCode.TASK_NOT_FOUND, "任务不存在：" + taskId));
        long jobId = idGenerator.nextId();
        jobDao.insert(jobId, taskId, tableMappingId, "MANUAL", options.getBlockRows(),
                options.getParallelism(), JsonUtil.toJson(normalize.toReportMap()), "system");
        AtomicBoolean cancel = new AtomicBoolean(consumeTaskCancel(taskId));
        jobCancels.put(Long.valueOf(jobId), cancel);

        ExecutorService pool = null;
        try {
            List<TableMapping> mappings = enabledMappings(taskId, tableMappingId);
            DataSourceHandle source = registry.handle(task.getSourceDsId());
            DataSourceHandle target = registry.handle(task.getTargetDsId());

            List<BlockWork> works = planBlocks(mappings, source, target, options);
            jobDao.markRunning(jobId, works.size());
            if (cancel.get() || works.isEmpty()) {
                jobDao.markCanceled(jobId);
                log.info("比对作业在开跑前被取消或无可比块 job={} task={} blocks={}", jobId, taskId,
                        works.size());
                return new CompareResult(jobId, taskId, 0, 0, 0L, 0L, CompareVerdict.FAIL, List.of());
            }

            int threads = Math.max(1, Math.min(options.getParallelism(), works.size()));
            pool = ThreadPoolFactory.newFixed("cmp-" + jobId, threads);
            List<Future<BlockOutcome>> futures = new ArrayList<Future<BlockOutcome>>(works.size());
            for (BlockWork work : works) {
                final BlockWork current = work;
                futures.add(pool.submit(() -> evaluate(current, source, target,
                        checksumCalculator, normalizer, cancel)));
            }

            List<BlockResult> blocks = new ArrayList<BlockResult>(works.size());
            List<BlockOutcome> mismatched = new ArrayList<BlockOutcome>();
            int done = 0;
            int diffBlocks = 0;
            long totalRows = 0L;
            for (Future<BlockOutcome> future : futures) {
                BlockOutcome outcome = future.get();
                if (outcome == null) {
                    // 取消后未执行的任务返回 null：不落库、不计数
                    continue;
                }
                blocks.add(outcome.block);
                totalRows += outcome.block.getSrcCount();
                if (!outcome.block.isMatched()) {
                    diffBlocks++;
                    mismatched.add(outcome);
                }
                done++;
                if (done % PROGRESS_INTERVAL == 0) {
                    jobDao.updateProgress(jobId, done, diffBlocks);
                }
            }
            jobDao.updateProgress(jobId, blocks.size(), diffBlocks);
            persistBlocks(jobId, blocks);

            if (cancel.get()) {
                jobDao.markCanceled(jobId);
                log.warn("比对作业被取消 job={} task={} 已完成块={}", jobId, taskId, blocks.size());
                return new CompareResult(jobId, taskId, blocks.size(), diffBlocks, totalRows,
                        estimateDiffRows(mismatched), CompareVerdict.FAIL, blocks);
            }

            long diffRows;
            java.util.Map<Integer, Integer> drilledRows = new java.util.HashMap<Integer, Integer>();
            if (options.isDrillDown()) {
                diffRows = drillDown(jobId, mismatched, source, target, rowDiffer, normalizer,
                        cancel, drilledRows);
            } else {
                diffRows = estimateDiffRows(mismatched);
            }
            if (cancel.get()) {
                jobDao.markCanceled(jobId);
                return new CompareResult(jobId, taskId, blocks.size(), diffBlocks, totalRows,
                        diffRows, CompareVerdict.FAIL, applyDrill(blocks, drilledRows));
            }

            CompareVerdict verdict = CompareResult.verdictOf(totalRows, diffRows,
                    options.getPassThreshold());
            double passRate = totalRows <= 0 ? 1.0d : (double) (totalRows - diffRows) / (double) totalRows;
            jobDao.finish(jobId, totalRows, diffRows, passRate, verdict.name(), null);
            log.info("比对完成 job={} task={} blocks={} diffBlocks={} rows={} diffRows={} verdict={}",
                    jobId, taskId, blocks.size(), diffBlocks, totalRows, diffRows, verdict);
            return new CompareResult(jobId, taskId, blocks.size(), diffBlocks, totalRows, diffRows,
                    verdict, applyDrill(blocks, drilledRows));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jobDao.markFailed(jobId);
            throw new DbSyncException(ErrorCode.COMPARE_BLOCK_FAILED, e, "比对被中断：" + taskId);
        } catch (ExecutionException e) {
            jobDao.markFailed(jobId);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new DbSyncException(ErrorCode.COMPARE_BLOCK_FAILED, cause,
                    "比对块失败：" + cause.getMessage());
        } catch (RuntimeException e) {
            jobDao.markFailed(jobId);
            throw e;
        } finally {
            jobCancels.remove(Long.valueOf(jobId));
            if (pool != null) {
                pool.shutdownNow();
            }
        }
    }

    /**
     * 取消一个作业（已完成块保留，不再提交新块）。
     *
     * @param jobId 作业 ID
     */
    public void cancel(long jobId) {
        AtomicBoolean flag = jobCancels.get(Long.valueOf(jobId));
        if (flag != null) {
            flag.set(true);
            log.info("收到比对取消请求 job={}", jobId);
        }
    }

    /**
     * 预先取消某任务的下一次比对（作业创建后立即置 CANCELED，不算任何块）。
     *
     * @param taskId 任务 ID
     */
    public void cancelTask(long taskId) {
        taskCancels.put(Long.valueOf(taskId), new AtomicBoolean(true));
        log.info("登记任务级预取消 task={}", taskId);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 取任务下启用的映射（可按 ID 过滤）。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 映射 ID（可 null）
     * @return 映射列表
     */
    private List<TableMapping> enabledMappings(long taskId, Long tableMappingId) {
        List<TableMapping> all = tableMappingDao.findEnabledByTask(taskId);
        List<TableMapping> result = new ArrayList<TableMapping>();
        for (TableMapping mapping : all) {
            if (tableMappingId == null || mapping.getId() == tableMappingId.longValue()) {
                result.add(mapping);
            }
        }
        if (result.isEmpty()) {
            throw new DbSyncException(ErrorCode.TASK_NOT_FOUND,
                    "任务 " + taskId + " 没有可比的启用映射（tableMappingId=" + tableMappingId + "）");
        }
        return result;
    }

    /**
     * 为每张表规划块并装配工作项。
     *
     * @param mappings 映射
     * @param source   源句柄
     * @param target   目标句柄
     * @param options  选项
     * @return 工作项列表（顺序即全局块号）
     */
    private List<BlockWork> planBlocks(List<TableMapping> mappings, DataSourceHandle source,
                                       DataSourceHandle target, CompareOptions options) {
        List<BlockWork> works = new ArrayList<BlockWork>();
        int blockNo = 0;
        for (TableMapping mapping : mappings) {
            TableMeta sourceTable = loadTable(source, mapping.getSourceSchema(),
                    mapping.getSourceTable(), "源");
            TableMeta targetTable = loadTable(target, mapping.getTargetSchema(),
                    mapping.getTargetTable(), "目标");
            long estimate = resolveEstimate(mapping, sourceTable);
            List<PkRange> ranges = blockPlanner.plan(sourceTable, options.getBlockRows(), estimate);
            for (PkRange range : ranges) {
                works.add(new BlockWork(idGenerator.nextId(), blockNo++, mapping.getId(), range,
                        sourceTable, targetTable));
            }
        }
        return works;
    }

    /**
     * 估算行数：映射估算 → 元数据估算 → 实时 COUNT（三级回退，保证分块不退化）。
     *
     * @param mapping 映射
     * @param table   源表元数据
     * @return 估算行数（&gt;0；取不到返回 0，此时退化为单块）
     */
    private long resolveEstimate(TableMapping mapping, TableMeta table) {
        if (mapping.getRowCountEstimate() > 0) {
            return mapping.getRowCountEstimate();
        }
        if (table.getEstimatedRows() > 0) {
            return table.getEstimatedRows();
        }
        DataSourceHandle handle = registry.handle(
                syncTaskDao.findById(mapping.getTaskId()).map(SyncTask::getSourceDsId)
                        .orElseThrow(() -> new DbSyncException(ErrorCode.TASK_NOT_FOUND,
                                "任务不存在：" + mapping.getTaskId())));
        try {
            return handle.withConnection(connection -> Long.valueOf(countRows(connection,
                    handle.dialect(), table)));
        } catch (RuntimeException e) {
            log.warn("实时 COUNT 失败，退化为单块：{}（{}）", table.qualifiedName(), e.getMessage());
            return 0L;
        }
    }

    /**
     * 实时 COUNT。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      表元数据
     * @return 行数
     */
    private static long countRows(Connection connection, com.dbsync.dialect.Dialect dialect,
                                  TableMeta table) {
        String sql = dialect.buildCountSql(table, null);
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.COMPARE_BLOCK_FAILED, e,
                    "COUNT 失败：" + table.qualifiedName());
        }
    }

    /**
     * 计算单块（三方校验和）。
     *
     * @param work       工作项
     * @param source     源句柄
     * @param target     目标句柄
     * @param calculator 校验和计算器
     * @param normalizer 归一化器
     * @param cancel     取消标志
     * @return 块结果；已取消返回 null
     */
    private BlockOutcome evaluate(BlockWork work, DataSourceHandle source, DataSourceHandle target,
                                  ChecksumCalculator calculator, Normalizer normalizer,
                                  AtomicBoolean cancel) {
        if (cancel.get()) {
            return null;
        }
        Connection srcConn = null;
        Connection tgtConn = null;
        try {
            srcConn = source.connection();
            tgtConn = target.connection();
            BlockChecksum src = calculator.checksum(srcConn, source.dialect(), work.sourceTable,
                    work.range, normalizer).withIdentity(work.blockId, work.blockNo, work.mappingId);
            BlockChecksum tgt = calculator.checksum(tgtConn, target.dialect(), work.targetTable,
                    work.range, normalizer).withIdentity(work.blockId, work.blockNo, work.mappingId);
            boolean matched = src.matches(tgt);
            return new BlockOutcome(src.toBlockResult(tgt, matched, 0), work, src, tgt);
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.COMPARE_BLOCK_FAILED, e,
                    "块比对取连接失败：" + work.sourceTable.qualifiedName() + " " + work.range);
        } finally {
            closeQuietly(tgtConn);
            closeQuietly(srcConn);
        }
    }

    /**
     * 差异块下钻并落库。
     *
     * @param jobId      作业 ID
     * @param mismatched 不一致块
     * @param source     源句柄
     * @param target     目标句柄
     * @param differ     下钻器
     * @param normalizer 归一化器
     * @param cancel     取消标志
     * @param drilledRows 出参：块号 → 差异行数（用于把内存里的块结果标记为"已下钻"）
     * @return 差异行数
     */
    private long drillDown(long jobId, List<BlockOutcome> mismatched, DataSourceHandle source,
                           DataSourceHandle target, RowDiffer differ, Normalizer normalizer,
                           AtomicBoolean cancel, Map<Integer, Integer> drilledRows) {
        long diffRows = 0L;
        List<DiffRecord> pending = new ArrayList<DiffRecord>(DIFF_INSERT_BATCH);
        for (BlockOutcome outcome : mismatched) {
            if (cancel.get()) {
                break;
            }
            Connection srcConn = null;
            Connection tgtConn = null;
            try {
                srcConn = source.connection();
                tgtConn = target.connection();
                List<DiffRecord> diffs = differ.diff(srcConn, source.dialect(), tgtConn,
                        target.dialect(), outcome.work.sourceTable, outcome.work.range, jobId,
                        outcome.work.blockId, outcome.work.mappingId, normalizer);
                diffRows += diffs.size();
                blockDao.markDrilled(jobId, outcome.work.blockNo, diffs.size());
                drilledRows.put(Integer.valueOf(outcome.work.blockNo), Integer.valueOf(diffs.size()));
                for (DiffRecord diff : diffs) {
                    pending.add(new DiffRecord(idGenerator.nextId(), diff.getJobId(),
                            diff.getBlockId(), diff.getTableMappingId(), diff.getPkJson(),
                            diff.getPkHash(), diff.getDiffType(), diff.getColumnDiffs(),
                            diff.getSrcRowJson(), diff.getTgtRowJson()));
                    if (pending.size() >= DIFF_INSERT_BATCH) {
                        diffDao.insertBatch(pending);
                        pending.clear();
                    }
                }
            } catch (SQLException e) {
                throw new DbSyncException(ErrorCode.COMPARE_BLOCK_FAILED, e,
                        "下钻取连接失败：" + outcome.work.sourceTable.qualifiedName());
            } finally {
                closeQuietly(tgtConn);
                closeQuietly(srcConn);
            }
        }
        if (!pending.isEmpty()) {
            diffDao.insertBatch(pending);
        }
        return diffRows;
    }

    /**
     * 批量落库块结果。
     *
     * @param jobId  作业 ID
     * @param blocks 块结果
     */
    private void persistBlocks(long jobId, List<BlockResult> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        for (int from = 0; from < blocks.size(); from += BLOCK_INSERT_BATCH) {
            int to = Math.min(from + BLOCK_INSERT_BATCH, blocks.size());
            blockDao.insertBatch(jobId, blocks.subList(from, to));
        }
    }

    /**
     * 把下钻得到的差异行数回填到内存块结果（DB 侧已由 {@code markDrilled} 更新）。
     *
     * @param blocks      原始块结果
     * @param drilledRows 块号 → 差异行数
     * @return 回填后的块结果
     */
    private static List<BlockResult> applyDrill(List<BlockResult> blocks,
                                                Map<Integer, Integer> drilledRows) {
        if (drilledRows.isEmpty()) {
            return blocks;
        }
        List<BlockResult> result = new ArrayList<BlockResult>(blocks.size());
        for (BlockResult block : blocks) {
            Integer rows = drilledRows.get(Integer.valueOf(block.getBlockNo()));
            result.add(rows == null ? block : block.drilled(rows.intValue()));
        }
        return result;
    }

    /**
     * 未下钻时按"行数差"估算差异行数（保守：按块累加绝对差）。
     *
     * @param mismatched 不一致块
     * @return 估算差异行数
     */
    private static long estimateDiffRows(List<BlockOutcome> mismatched) {
        long sum = 0L;
        for (BlockOutcome outcome : mismatched) {
            sum += Math.abs(outcome.block.getSrcCount() - outcome.block.getTgtCount());
        }
        return sum;
    }

    /**
     * 消费任务级预取消标志。
     *
     * @param taskId 任务 ID
     * @return true 表示已预先取消
     */
    private boolean consumeTaskCancel(long taskId) {
        AtomicBoolean flag = taskCancels.remove(Long.valueOf(taskId));
        return flag != null && flag.get();
    }

    /**
     * 读表元数据。
     *
     * @param handle 句柄
     * @param schema schema
     * @param table  表名
     * @param side   侧别
     * @return 表元数据
     */
    private static TableMeta loadTable(DataSourceHandle handle, String schema, String table,
                                       String side) {
        TableMeta meta = handle.withConnection(connection ->
                handle.dialect().metadata().loadTable(connection, schema, table));
        if (meta == null) {
            throw new DbSyncException(ErrorCode.TASK_NOT_FOUND,
                    side + "表不存在：" + (schema == null ? "" : schema + ".") + table);
        }
        return meta;
    }

    /**
     * 静默关闭连接。
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

    /** 一个块的工作项（映射 + 区间 + 两侧表结构）。 */
    private static final class BlockWork {

        private final long blockId;
        private final int blockNo;
        private final long mappingId;
        private final PkRange range;
        private final TableMeta sourceTable;
        private final TableMeta targetTable;

        private BlockWork(long blockId, int blockNo, long mappingId, PkRange range,
                          TableMeta sourceTable, TableMeta targetTable) {
            this.blockId = blockId;
            this.blockNo = blockNo;
            this.mappingId = mappingId;
            this.range = range;
            this.sourceTable = sourceTable;
            this.targetTable = targetTable;
        }
    }

    /** 一个块的比对产物。 */
    private static final class BlockOutcome {

        private final BlockResult block;
        private final BlockWork work;
        private final BlockChecksum source;
        private final BlockChecksum target;

        private BlockOutcome(BlockResult block, BlockWork work, BlockChecksum source,
                             BlockChecksum target) {
            this.block = block;
            this.work = work;
            this.source = source;
            this.target = target;
        }
    }
}
