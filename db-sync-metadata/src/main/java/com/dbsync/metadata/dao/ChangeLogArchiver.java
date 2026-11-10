package com.dbsync.metadata.dao;

import com.dbsync.common.enums.ChangeStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 变更日志归档器（架构 §7.4 容量与维护）。
 *
 * <p>{@code sync_change_log} 是写放大最严重的表（每行源库变更写一行），
 * 必须按时间窗清理，否则元数据库会被撑爆。
 *
 * <p><b>安全底线</b>：
 * <ul>
 *   <li>只清理 {@code APPLIED/SKIPPED}——{@code NEW}（未消费）与 {@code DEAD}（待处理）<b>永不</b>清理；</li>
 *   <li>分批删除（每批 ≤ batchLimit），避免长事务与主从延迟；</li>
 *   <li>死信表只清理 {@code DISCARDED/REPLAYED}（人工已决策的），{@code OPEN} 永不清理。</li>
 * </ul>
 *
 * <p>// TODO(P1): 生产推荐按 {@code created_at} 做 MySQL 分区表（每月一分区，{@code ALTER TABLE ... DROP PARTITION} 秒级清理）。
 * 当前实现用"分批 DELETE"覆盖功能，H2 与 MySQL 通用；分区是性能优化而非功能缺失。
 */
@Repository
public class ChangeLogArchiver {

    private static final Logger log = LoggerFactory.getLogger(ChangeLogArchiver.class);

    private final JdbcTemplate jdbc;
    private final ChangeLogDao changeLogDao;

    /**
     * 构造。
     *
     * @param jdbc        元数据库 JdbcTemplate
     * @param changeLogDao 变更日志 DAO
     */
    public ChangeLogArchiver(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc,
                             ChangeLogDao changeLogDao) {
        this.jdbc = jdbc;
        this.changeLogDao = changeLogDao;
    }

    /**
     * 归档（此处为直接删除）已应用且早于给定时间的变更日志。
     *
     * <p>返回删除行数：一次调用最多删 {@code batchLimit × 10} 行（内部循环 10 批），
     * 由调度器每分钟跑一次即可追上写入量；不追求"一次调用删干净"，避免长事务。
     *
     * @param before     时间上界（{@code created_at < before}）
     * @param batchLimit 单批最大行数
     * @return 删除行数
     */
    public int archiveApplied(Instant before, int batchLimit) {
        int limit = batchLimit <= 0 ? 2000 : batchLimit;
        int total = 0;
        for (int round = 0; round < 10; round++) {
            int deleted = changeLogDao.deleteAppliedBefore(before, limit);
            if (deleted <= 0) {
                break;
            }
            total += deleted;
        }
        if (total > 0) {
            log.info("变更日志归档完成：deleted={} before={}", total, before);
        }
        return total;
    }

    /**
     * 归档<b>指定任务</b>的已应用日志（按任务清理，便于按租户/任务配额控制）。
     *
     * @param taskId     任务 ID
     * @param before     时间上界
     * @param batchLimit 单批最大行数
     * @return 删除行数
     */
    public int archiveAppliedOfTask(long taskId, Instant before, int batchLimit) {
        int limit = batchLimit <= 0 ? 2000 : batchLimit;
        int total = 0;
        for (int round = 0; round < 10; round++) {
            int deleted = changeLogDao.deleteAppliedBefore(taskId, before, limit);
            if (deleted <= 0) {
                break;
            }
            total += deleted;
        }
        if (total > 0) {
            log.info("变更日志归档完成：taskId={} deleted={} before={}", taskId, total, before);
        }
        return total;
    }

    /**
     * 清理死信表中人工已决策（DISCARDED / REPLAYED）的行。
     *
     * <p>{@code OPEN} 行永不清理（它们是 Gate G4 的输入）。
     *
     * @param before 时间上界
     * @return 删除行数
     */
    public int purgeDeadLetters(Instant before) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM sync_dead_letter WHERE created_at < ? "
                        + "AND status IN (?,?) LIMIT 5000", Long.class, java.sql.Timestamp.from(before),
                "DISCARDED", "REPLAYED");
        if (ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (int from = 0; from < ids.size(); from += 200) {
            List<Long> chunk = ids.subList(from, Math.min(from + 200, ids.size()));
            StringBuilder sql = new StringBuilder("DELETE FROM sync_dead_letter WHERE id IN (");
            List<Object> args = new ArrayList<Object>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append('?');
                args.add(chunk.get(i));
            }
            sql.append(')');
            deleted += jdbc.update(sql.toString(), args.toArray());
        }
        if (deleted > 0) {
            log.info("死信清理完成：deleted={} before={}", deleted, before);
        }
        return deleted;
    }

    /**
     * 待归档行数估算（运维大盘/容量预警）。
     *
     * @param before 时间上界
     * @return 行数
     */
    public long countArchivable(Instant before) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_change_log WHERE created_at < ? "
                        + "AND status IN (?,?)", Long.class, java.sql.Timestamp.from(before),
                ChangeStatus.APPLIED.name(), ChangeStatus.SKIPPED.name());
        return n == null ? 0L : n;
    }
}
