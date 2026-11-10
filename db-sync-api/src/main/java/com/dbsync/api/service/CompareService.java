package com.dbsync.api.service;

import com.dbsync.api.dto.compare.CompareDiffView;
import com.dbsync.api.dto.compare.CompareJobView;
import com.dbsync.api.dto.compare.CompareRunRequest;
import com.dbsync.api.dto.compare.DiffPageView;
import com.dbsync.api.dto.compare.RepairRequest;
import com.dbsync.api.dto.compare.RepairView;
import com.dbsync.api.dto.compare.ReportView;
import com.dbsync.common.enums.DiffType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.BlockResult;
import com.dbsync.common.model.CompareResult;
import com.dbsync.common.enums.CompareVerdict;
import com.dbsync.common.model.DiffRecord;
import com.dbsync.common.options.CompareOptions;
import com.dbsync.common.options.NormalizeOptions;
import com.dbsync.common.util.JsonUtil;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.compare.CompareEngine;
import com.dbsync.compare.CompareReportBuilder;
import com.dbsync.compare.DiffRepairer;
import com.dbsync.compare.ReportFile;
import com.dbsync.metadata.dao.CompareBlockDao;
import com.dbsync.metadata.dao.CompareDiffDao;
import com.dbsync.metadata.dao.CompareJobDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 比对服务（T37：发起 / 查询 / 报告 / 差异分页 / 修复）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>报告按需重建</b>：报告不落"报告表"，而是从 {@code sync_compare_block} +
 *       {@code sync_compare_job} 重建 {@link CompareResult} 再交给
 *       {@link CompareReportBuilder}，因此历史作业随时可导出（架构 §8.4）；</li>
 *   <li><b>反修必须双人</b>：{@code fix-source} 走 {@link DiffRepairer#fixToSource}，
 *       非 ADMIN 由 service 直接拒（403），并在 DiffRepairer 内二次校验；</li>
 *   <li><b>脱敏不可绕过</b>（SEC-04）：差异明细与 CSV 都来自已脱敏的
 *       {@link DiffRecord#getColumnDiffs()}，导出不重新查业务库。</li>
 * </ul>
 */
public class CompareService {

    private static final Logger log = LoggerFactory.getLogger(CompareService.class);

    /** 默认通过阈值（与 GateThresholds.defaults() 一致）。 */
    private static final double DEFAULT_PASS_THRESHOLD = 0.9999d;
    /** 默认每块行数。 */
    private static final int DEFAULT_BLOCK_ROWS = 100_000;
    /** 默认并发。 */
    private static final int DEFAULT_PARALLELISM = 8;

    private final CompareEngine compareEngine;
    private final CompareJobDao compareJobDao;
    private final CompareDiffDao compareDiffDao;
    private final CompareBlockDao compareBlockDao;
    private final DiffRepairer diffRepairer;
    private final CompareReportBuilder reportBuilder;
    private final SyncTaskDao syncTaskDao;
    private final Predicate<String> adminChecker;

    /**
     * 构造。
     *
     * @param compareEngine  比对引擎
     * @param compareJobDao  作业 DAO
     * @param compareDiffDao 差异 DAO
     * @param compareBlockDao 块 DAO
     * @param diffRepairer   差异修复器
     * @param reportBuilder  报告生成器
     * @param syncTaskDao    任务 DAO
     * @param adminChecker   操作人是否 ADMIN
     */
    public CompareService(CompareEngine compareEngine, CompareJobDao compareJobDao,
                          CompareDiffDao compareDiffDao, CompareBlockDao compareBlockDao,
                          DiffRepairer diffRepairer, CompareReportBuilder reportBuilder,
                          SyncTaskDao syncTaskDao, Predicate<String> adminChecker) {
        this.compareEngine = compareEngine;
        this.compareJobDao = compareJobDao;
        this.compareDiffDao = compareDiffDao;
        this.compareBlockDao = compareBlockDao;
        this.diffRepairer = diffRepairer;
        this.reportBuilder = reportBuilder;
        this.syncTaskDao = syncTaskDao;
        this.adminChecker = adminChecker == null ? operator -> false : adminChecker;
    }

    /**
     * 发起比对（同步执行，适合单表或小库；大库由定时作业触发）。
     *
     * @param taskId 任务 ID
     * @param req    请求（可 null）
     * @return 作业视图
     */
    public CompareJobView run(long taskId, CompareRunRequest req) {
        loadTask(taskId);
        CompareOptions options = buildOptions(req);
        CompareResult result = compareEngine.run(taskId,
                req == null ? null : req.tableMappingId(), options);
        log.info("比对完成 task={} job={} verdict={} passRate={}", taskId, result.getJobId(),
                result.getVerdict(), result.passRate());
        return compareJobDao.findById(result.getJobId()).map(CompareJobView::of)
                .orElseGet(() -> new CompareJobView(result.getJobId(), taskId, null, "MANUAL",
                        "FINISHED", result.getTotalBlocks(), result.getTotalBlocks(),
                        result.getDiffBlocks(), result.getTotalRows(), result.getDiffRows(),
                        result.passRate(),
                        result.getVerdict() == null ? null : result.getVerdict().name(),
                        null, null));
    }

    /**
     * 作业详情。
     *
     * @param jobId 作业 ID
     * @return 作业
     */
    public CompareJobView job(long jobId) {
        return CompareJobView.of(loadJob(jobId));
    }

    /**
     * 差异分页。
     *
     * @param jobId 作业 ID
     * @param page  页码（1 起）
     * @param size  每页条数
     * @param type  类型过滤（可 null）
     * @return 分页结果
     */
    public DiffPageView diffs(long jobId, int page, int size, String type) {
        loadJob(jobId);
        DiffType diffType = parseType(type);
        int effectivePage = page <= 0 ? 1 : page;
        int effectiveSize = size <= 0 ? 100 : Math.min(size, 1000);
        int offset = (effectivePage - 1) * effectiveSize;
        List<DiffRecord> records = compareDiffDao.findPage(jobId, diffType, offset,
                effectiveSize);
        long total = diffType == null ? compareDiffDao.countByJob(jobId)
                : countByType(jobId, diffType);
        List<Map<String, Object>> items = new ArrayList<>(records.size());
        for (DiffRecord record : records) {
            items.add(CompareDiffView.of(record).toMap());
        }
        return new DiffPageView(jobId, effectivePage, effectiveSize, total,
                diffType == null ? null : diffType.name(), items);
    }

    /**
     * 生成/获取报告。
     *
     * @param jobId 作业 ID
     * @return 报告视图（含 HTML 内容）
     */
    public ReportView report(long jobId) {
        CompareJobDao.CompareJobRow job = loadJob(jobId);
        CompareResult result = rebuildResult(job);
        ReportFile file = reportBuilder.build(jobId, result, NormalizeOptions.defaults());
        return new ReportView(jobId, file.getHtmlPath(), file.getCsvPath(), file.getHtml(), true);
    }

    /**
     * 导出 CSV（T37 验收 2：{@code text/csv}）。
     *
     * @param jobId 作业 ID
     * @return CSV 内容
     */
    public String exportCsv(long jobId) {
        CompareJobDao.CompareJobRow job = loadJob(jobId);
        CompareResult result = rebuildResult(job);
        ReportFile file = reportBuilder.build(jobId, result, NormalizeOptions.defaults());
        return readCsv(file.getCsvPath());
    }

    /**
     * 重放到目标（{@code REPLAY_TO_TARGET}，OPERATOR 可用）。
     *
     * @param jobId 作业 ID
     * @param req   请求
     * @return 修复结果
     */
    public RepairView replayToTarget(long jobId, RepairRequest req) {
        loadJob(jobId);
        List<Long> ids = requireIds(req);
        return RepairView.of(diffRepairer.replayToTarget(jobId, ids));
    }

    /**
     * 反向修复源库（{@code FIX_TO_SOURCE}，仅 ADMIN）。
     *
     * @param jobId    作业 ID
     * @param req      请求
     * @param operator 当前操作人
     * @return 修复结果
     */
    public RepairView fixToSource(long jobId, RepairRequest req, String operator) {
        loadJob(jobId);
        if (!adminChecker.test(operator)) {
            throw new DbSyncException(ErrorCode.NO_PERMISSION, "FIX_TO_SOURCE 仅 ADMIN 可执行")
                    .with("operator", operator);
        }
        List<Long> ids = requireIds(req);
        String reason = req == null ? null : req.reason();
        if (reason == null || reason.trim().length() < 10) {
            throw new DbSyncException(ErrorCode.SECOND_CONFIRM_REQUIRED,
                    "反向修复理由需 ≥10 字（双人复核要求）");
        }
        String secondOperator = req == null || req.operator() == null ? operator : req.operator();
        return RepairView.of(diffRepairer.fixToSource(jobId, ids, secondOperator, reason));
    }

    /**
     * 取消作业。
     *
     * @param jobId 作业 ID
     */
    public void cancel(long jobId) {
        loadJob(jobId);
        compareEngine.cancel(jobId);
    }

    private CompareJobDao.CompareJobRow loadJob(long jobId) {
        return compareJobDao.findById(jobId).orElseThrow(
                () -> new DbSyncException(ErrorCode.PARAM_INVALID, "比对作业不存在：" + jobId));
    }

    private void loadTask(long taskId) {
        syncTaskDao.findById(taskId).orElseThrow(
                () -> new DbSyncException(ErrorCode.TASK_NOT_FOUND, taskId));
    }

    private long countByType(long jobId, DiffType type) {
        Map<DiffType, Long> counts = compareDiffDao.countByType(jobId);
        Long value = counts.get(type);
        return value == null ? 0L : value.longValue();
    }

    private CompareResult rebuildResult(CompareJobDao.CompareJobRow job) {
        List<BlockResult> blocks = compareBlockDao.findByJob(job.getId());
        CompareVerdict verdict = job.getVerdict() == null ? null
                : CompareVerdict.valueOf(job.getVerdict().toUpperCase(Locale.ROOT));
        return new CompareResult(job.getId(), job.getTaskId(), job.getTotalBlocks(),
                job.getDiffBlocks(), job.getTotalRows(), job.getDiffRows(), verdict, blocks);
    }

    private static List<Long> requireIds(RepairRequest req) {
        if (req == null || req.diffIds() == null || req.diffIds().isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "diffIds 不能为空");
        }
        return req.diffIds();
    }

    private static DiffType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return DiffType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "不支持的差异类型：" + type);
        }
    }

    private static CompareOptions buildOptions(CompareRunRequest req) {
        CompareRunRequest request = req == null
                ? new CompareRunRequest(null, null, null, null, null, null) : req;
        int blockRows = request.blockRows() == null ? DEFAULT_BLOCK_ROWS
                : Math.max(1000, request.blockRows().intValue());
        int parallelism = request.parallelism() == null ? DEFAULT_PARALLELISM
                : Math.max(1, Math.min(32, request.parallelism().intValue()));
        double threshold = request.passThreshold() == null ? DEFAULT_PASS_THRESHOLD
                : request.passThreshold().doubleValue();
        if (threshold <= 0.0d || threshold > 1.0d) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "passThreshold 需在 (0,1] 区间");
        }
        boolean drillDown = request.drillDown() == null || request.drillDown().booleanValue();
        NormalizeOptions normalize = parseNormalize(request.normalizeJson());
        return new CompareOptions(blockRows, parallelism, threshold, normalize, drillDown);
    }

    /**
     * 解析归一化选项 JSON（只支持已实现的 8 个开关，未知键忽略）。
     *
     * @param json JSON 字符串（可 null）
     * @return 归一化选项
     */
    static NormalizeOptions parseNormalize(String json) {
        NormalizeOptions options = NormalizeOptions.defaults();
        if (json == null || json.isBlank()) {
            return options;
        }
        Map<String, Object> map;
        try {
            map = JsonUtil.parseObject(json);
        } catch (RuntimeException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, e, "normalizeJson 不是合法 JSON 对象");
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "emptyAsNull":
                    options = options.withEmptyAsNull(asBool(value, options.isEmptyAsNull()));
                    break;
                case "decimalTrimZeros":
                    options = options.withDecimalTrimZeros(
                            asBool(value, options.isDecimalTrimZeros()));
                    break;
                case "trimWhitespace":
                    options = options.withTrimWhitespace(
                            asBool(value, options.isTrimWhitespace()));
                    break;
                case "caseInsensitive":
                    options = options.withCaseInsensitive(
                            asBool(value, options.isCaseInsensitive()));
                    break;
                case "floatTolerance":
                    options = options.withFloatTolerance(asDouble(value,
                            options.getFloatTolerance()));
                    break;
                case "lobCompareLimitBytes":
                    options = options.withLobCompareLimitBytes(asInt(value,
                            options.getLobCompareLimitBytes()));
                    break;
                case "timePrecision":
                    options = options.withTimePrecision(parsePrecision(value,
                            options.getTimePrecision()));
                    break;
                case "sourceTimeZone":
                    options = options.withSourceTimeZone(value == null
                            ? options.getSourceTimeZone()
                            : java.time.ZoneId.of(String.valueOf(value)));
                    break;
                default:
                    // 未知键忽略：避免前端多传字段导致 500
                    break;
            }
        }
        return options;
    }

    private static boolean asBool(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return fallback;
    }

    private static double asDouble(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static NormalizeOptions.TimePrecision parsePrecision(
            Object value, NormalizeOptions.TimePrecision fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return NormalizeOptions.TimePrecision.valueOf(
                    String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "不支持的 timePrecision：" + value);
        }
    }

    private static String readCsv(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new DbSyncException(ErrorCode.REPORT_BUILD_FAILED, e, "读取报告 CSV 失败");
        }
    }

    /**
     * 作业概览 Map（供监控聚合）。
     *
     * @param jobId 作业 ID
     * @return 有序 Map
     */
    public Map<String, Object> summary(long jobId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("job", job(jobId).toMap());
        map.put("diffByType", compareDiffDao.countByType(jobId));
        map.put("matchedBlocks", Long.valueOf(compareBlockDao.countMatched(jobId)));
        map.put("mismatchedBlocks", Long.valueOf(compareBlockDao.countMismatched(jobId)));
        return map;
    }

    /**
     * 生成作业 ID（供定时比对预分配）。
     *
     * @param generator 雪花生成器
     * @return 新 ID
     */
    public static long nextJobId(SnowflakeId generator) {
        return generator == null ? new SnowflakeId(SnowflakeId.defaultWorkerId()).nextId()
                : generator.nextId();
    }
}
