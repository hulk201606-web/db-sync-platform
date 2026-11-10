package com.dbsync.api.service;

import com.dbsync.api.dto.migration.DeadLetterView;
import com.dbsync.api.dto.migration.ReplayRequest;
import com.dbsync.common.enums.DeadLetterStatus;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.DeadLetter;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.migration.DeadLetterService;
import com.dbsync.migration.ReplayResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 死信管理（T45：列表 / 按错误类型分组 / 重放 / 丢弃）。
 *
 * <p>为什么叫 {@code DeadLetterAdminService} 而不是 {@code DeadLetterService}：
 * 迁移模块已占用该类名（{@code com.dbsync.migration.DeadLetterService}），
 * 这里只做"API 侧编排 + 参数校验"，真正的重放/丢弃逻辑仍复用迁移模块实现
 * （保证与迁移链路完全一致，不出现两套重放语义）。{@code // ADDED: 类名避让}
 */
public class DeadLetterAdminService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAdminService.class);

    /** 默认列表上限。 */
    private static final int DEFAULT_LIMIT = 200;

    private final DeadLetterDao deadLetterDao;
    private final DeadLetterService deadLetterService;

    /**
     * 构造。
     *
     * @param deadLetterDao     死信 DAO
     * @param deadLetterService 迁移模块的死信服务（重放/丢弃）
     */
    public DeadLetterAdminService(DeadLetterDao deadLetterDao,
                                  DeadLetterService deadLetterService) {
        this.deadLetterDao = deadLetterDao;
        this.deadLetterService = deadLetterService;
    }

    /**
     * 列表。
     *
     * @param taskId 任务 ID
     * @param status 状态（可空，默认 OPEN）
     * @param limit  上限
     * @return 死信列表
     */
    public List<DeadLetterView> list(long taskId, String status, int limit) {
        DeadLetterStatus parsed = parseStatus(status);
        List<DeadLetterView> views = new ArrayList<>();
        for (DeadLetter letter : deadLetterDao.list(taskId, parsed,
                limit <= 0 ? DEFAULT_LIMIT : limit)) {
            views.add(DeadLetterView.of(letter));
        }
        return views;
    }

    /**
     * 按错误类型分组（T45 验收 5）。
     *
     * @param taskId 任务 ID
     * @param status 状态（可空）
     * @return 错误码 → 条数（有序）
     */
    public Map<String, Long> groups(long taskId, String status) {
        DeadLetterStatus parsed = parseStatus(status);
        Map<String, Long> groups = new TreeMap<>();
        for (DeadLetter letter : deadLetterDao.list(taskId, parsed, 5000)) {
            String key = letter.getErrorCode() == null || letter.getErrorCode().isBlank()
                    ? "UNKNOWN" : letter.getErrorCode();
            Long previous = groups.get(key);
            groups.put(key, Long.valueOf(previous == null ? 1L : previous.longValue() + 1L));
        }
        return groups;
    }

    /**
     * 重放单条。
     *
     * @param deadLetterId 死信 ID
     * @param operator     操作人
     * @return 结果视图
     */
    public Map<String, Object> replay(long deadLetterId, String operator) {
        ReplayResult result = deadLetterService.replay(deadLetterId, operator);
        return toView(result);
    }

    /**
     * 批量重放（逐条，单条失败不影响其他）。
     *
     * @param req      请求
     * @param operator 操作人
     * @return 汇总结果
     */
    public Map<String, Object> replayBatch(ReplayRequest req, String operator) {
        List<Long> ids = requireIds(req);
        List<Map<String, Object>> items = new ArrayList<>(ids.size());
        int applied = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                ReplayResult result = deadLetterService.replay(id.longValue(), operator);
                if (result.isApplied()) {
                    applied++;
                } else {
                    failed++;
                }
                items.add(toView(result));
            } catch (RuntimeException e) {
                failed++;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("deadLetterId", id);
                item.put("applied", Boolean.FALSE);
                item.put("message", e.getMessage());
                items.add(item);
                log.warn("死信重放失败 id={} err={}", id, e.toString());
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", Integer.valueOf(ids.size()));
        summary.put("applied", Integer.valueOf(applied));
        summary.put("failed", Integer.valueOf(failed));
        summary.put("items", items);
        return summary;
    }

    /**
     * 丢弃（必须填理由）。
     *
     * @param deadLetterId 死信 ID
     * @param reason       理由
     * @param operator     操作人
     * @return 结果
     */
    public Map<String, Object> discard(long deadLetterId, String reason, String operator) {
        if (reason == null || reason.trim().length() < 5) {
            throw new DbSyncException(ErrorCode.SECOND_CONFIRM_REQUIRED, "丢弃死信需填写理由（≥5 字）");
        }
        deadLetterService.discard(deadLetterId, operator, reason.trim());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deadLetterId", Long.valueOf(deadLetterId));
        view.put("status", DeadLetterStatus.DISCARDED.name());
        view.put("reason", reason.trim());
        return view;
    }

    /**
     * 未处理数。
     *
     * @param taskId 任务 ID
     * @return 条数
     */
    public long countOpen(long taskId) {
        return deadLetterDao.countOpen(taskId);
    }

    private static List<Long> requireIds(ReplayRequest req) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "ids 不能为空");
        }
        return req.ids();
    }

    private static Map<String, Object> toView(ReplayResult result) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deadLetterId", Long.valueOf(result.getDeadLetterId()));
        view.put("status", result.getStatus() == null ? null : result.getStatus().name());
        view.put("applied", Boolean.valueOf(result.isApplied()));
        view.put("duplicate", Boolean.valueOf(result.isDuplicate()));
        view.put("rowsAffected", Integer.valueOf(result.getRowsAffected()));
        view.put("message", result.getMessage());
        return view;
    }

    private static DeadLetterStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return DeadLetterStatus.OPEN;
        }
        try {
            return DeadLetterStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "不支持的死信状态：" + status);
        }
    }
}
