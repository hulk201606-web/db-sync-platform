package com.dbsync.metadata.dao;

import com.dbsync.common.enums.ScopeType;
import com.dbsync.common.model.Checkpoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 断点 DAO（{@code sync_checkpoint} 一张表统一承载 SHARD / GROUP / POLLER 三种 scope）。
 *
 * <p><b>注意分层职责</b>：本类只负责"把给定的 Checkpoint 写进库"，
 * <b>不</b>负责"是否允许推进"——I1 顺序约束（先 commit 再 save）在
 * {@code db-sync-core} 的 {@code CheckpointStore} 中强制（架构 §4.5 I1），
 * 因为只有它才持有 {@code WriteResult.committed}。
 */
@Repository
public class CheckpointDao extends AbstractMetaDao {

    private final RowMapper<Checkpoint> mapper = new RowMapper<Checkpoint>() {
        @Override
        public Checkpoint mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Checkpoint(rs.getLong("task_id"), rs.getLong("table_mapping_id"),
                    ScopeType.valueOf(rs.getString("scope_type")), rs.getInt("scope_no"),
                    rs.getString("last_pk"), instantOf(rs, "last_commit_ts"),
                    nullableLong(rs, "last_seq"), nullableLong(rs, "last_log_id"),
                    rs.getLong("committed_rows"), rs.getLong("committed_batches"),
                    rs.getInt("version"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public CheckpointDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 读取断点。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID（GROUP/POLLER scope 传 0）
     * @param scopeType      scope 类型
     * @param scopeNo        scope 号
     * @return 断点；不存在返回 empty
     */
    public Optional<Checkpoint> load(long taskId, long tableMappingId, ScopeType scopeType,
                                     int scopeNo) {
        List<Checkpoint> list = jdbc.query("SELECT * FROM sync_checkpoint WHERE task_id=? "
                        + "AND table_mapping_id=? AND scope_type=? AND scope_no=?",
                mapper, taskId, tableMappingId, scopeType.name(), scopeNo);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 保存断点（幂等 upsert：先 UPDATE，0 行则 INSERT；并发 INSERT 冲突时退化为 UPDATE）。
     *
     * <p>不使用 {@code MERGE}/{@code ON DUPLICATE KEY}，因为元数据库脚本要保持方言无关（硬规则 3）。
     *
     * <p>语义：{@code ckpt.getVersion()} 是<b>期望版本</b>；UPDATE 命中时库里 version 自增 1，
     * 返回 0 说明被其他写入者抢先推进（调用方必须重新 {@link #load} 后再决定）。
     * 首次保存（version=0 且无行）走 INSERT。
     *
     * @param ckpt 断点
     * @return 影响行数
     */
    public int save(Checkpoint ckpt) {
        int rows = update(ckpt);
        if (rows > 0) {
            return rows;
        }
        try {
            return jdbc.update("INSERT INTO sync_checkpoint (id,task_id,table_mapping_id,scope_type,"
                            + "scope_no,last_pk,last_commit_ts,last_seq,last_log_id,committed_rows,"
                            + "committed_batches,version,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    nextId(), ckpt.getTaskId(), ckpt.getTableMappingId(), ckpt.getScopeType().name(),
                    ckpt.getScopeNo(), ckpt.getLastPk(), toTimestamp(ckpt.getLastCommitTs()),
                    ckpt.getLastSeq(), ckpt.getLastLogId(), ckpt.getCommittedRows(),
                    ckpt.getCommittedBatches(), ckpt.getVersion(), toTimestamp(now()));
        } catch (DuplicateKeyException race) {
            // 另一实例抢先插入了同一 scope：退化为 UPDATE（乐观版本仍由 version 保护）
            return update(ckpt);
        }
    }

    /**
     * 执行 UPDATE 部分（CAS：只有 version 匹配才推进，DB 中 version 自增 1）。
     *
     * @param ckpt 断点
     * @return 影响行数（0 表示版本已被其他写入者推进）
     */
    private int update(Checkpoint ckpt) {
        return jdbc.update("UPDATE sync_checkpoint SET last_pk=?,last_commit_ts=?,last_seq=?,"
                        + "last_log_id=?,committed_rows=?,committed_batches=?,"
                        + "version=version+1,updated_at=? "
                        + "WHERE task_id=? AND table_mapping_id=? AND scope_type=? AND scope_no=? "
                        + "AND version=?",
                ckpt.getLastPk(), toTimestamp(ckpt.getLastCommitTs()), ckpt.getLastSeq(),
                ckpt.getLastLogId(), ckpt.getCommittedRows(), ckpt.getCommittedBatches(),
                toTimestamp(now()), ckpt.getTaskId(),
                ckpt.getTableMappingId(), ckpt.getScopeType().name(), ckpt.getScopeNo(),
                ckpt.getVersion());
    }

    /**
     * <b>采集权认领</b>（CAS + 空闲窗口）：只在位点行"已经空闲了 {@code idleBefore} 这么久"
     * 时才允许认领，单条条件 UPDATE 原子完成，多实例下只有一个能成功。
     *
     * <p>为什么用 {@code updated_at} 当租约而不是加一列：{@code updated_at} 本来就是
     * "这一行最后一次被动过的时间"，正好等于"上一次认领/推进位点的时刻"。
     * 于是"空闲窗口"天然就是<b>采集租约</b>：
     * <ul>
     *   <li>同一实例按周期再认领：窗口 = 轮询周期，下一次认领恰好落在窗口边界，可继续；</li>
     *   <li>另一实例在周期内认领：窗口未过，UPDATE 命中 0 行 → 本轮跳过；</li>
     *   <li>持有者宕机：窗口最多一个周期后自动失效，另一实例接手（无需选主）。</li>
     * </ul>
     *
     * <p><b>局限（诚实声明）</b>：若单轮采集耗时超过窗口（周期），另一个实例可能在
     * 上一轮还没结束时抢到认领 → 同一批日志被采两遍。这不影响正确性
     * （应用层 upsert/delete 幂等），只多花一遍 IO；要彻底杜绝需要
     * {@code sync_change_log} 上的 {@code (task_id, seq)} 唯一约束，属于建表变更。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param scopeType      scope 类型
     * @param scopeNo        scope 号
     * @param idleBefore     空闲下界（{@code updated_at <= idleBefore} 才可认领）
     * @return 影响行数（1 = 认领成功；0 = 被其他实例持有或行不存在）
     */
    public int claimIfIdle(long taskId, long tableMappingId, ScopeType scopeType, int scopeNo,
                           Instant idleBefore) {
        return jdbc.update("UPDATE sync_checkpoint SET updated_at=?,version=version+1 "
                        + "WHERE task_id=? AND table_mapping_id=? AND scope_type=? AND scope_no=? "
                        + "AND updated_at<=?",
                toTimestamp(now()), taskId, tableMappingId, scopeType.name(), scopeNo,
                toTimestamp(idleBefore));
    }

    /**
     * 插入位点行（<b>不吞冲突</b>）：用于把"建行"本身当作一次认领。
     *
     * <p>与 {@link #save(Checkpoint)} 的区别：{@code save} 遇到唯一键冲突会退化为
     * UPDATE（适合"幂等写入"），而本方法把冲突直接抛给调用方——
     * 采集场景下"有两个实例同时建这一行"意味着只有一个该继续，
     * 退化成 UPDATE 会让两个实例都以为自己是采集者。
     *
     * @param ckpt 位点
     * @return 1（成功）
     * @throws DuplicateKeyException 已被其他实例抢先建立
     */
    public int insertIfAbsent(Checkpoint ckpt) {
        return jdbc.update("INSERT INTO sync_checkpoint (id,task_id,table_mapping_id,scope_type,"
                        + "scope_no,last_pk,last_commit_ts,last_seq,last_log_id,committed_rows,"
                        + "committed_batches,version,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                nextId(), ckpt.getTaskId(), ckpt.getTableMappingId(), ckpt.getScopeType().name(),
                ckpt.getScopeNo(), ckpt.getLastPk(), toTimestamp(ckpt.getLastCommitTs()),
                ckpt.getLastSeq(), ckpt.getLastLogId(), ckpt.getCommittedRows(),
                ckpt.getCommittedBatches(), ckpt.getVersion(), toTimestamp(now()));
    }

    /**
     * 列出任务下全部断点（恢复/诊断用）。
     *
     * @param taskId 任务 ID
     * @return 断点列表
     */
    public List<Checkpoint> findByTask(long taskId) {
        return jdbc.query("SELECT * FROM sync_checkpoint WHERE task_id=? "
                + "ORDER BY scope_type, scope_no", mapper, taskId);
    }

    /**
     * 列出某类 scope 的断点。
     *
     * @param taskId    任务 ID
     * @param scopeType scope 类型
     * @return 断点列表
     */
    public List<Checkpoint> findByScope(long taskId, ScopeType scopeType) {
        return jdbc.query("SELECT * FROM sync_checkpoint WHERE task_id=? AND scope_type=? "
                + "ORDER BY scope_no", mapper, taskId, scopeType.name());
    }

    /**
     * 删除任务下全部断点。
     *
     * @param taskId 任务 ID
     * @return 影响行数
     */
    public int deleteByTask(long taskId) {
        return jdbc.update("DELETE FROM sync_checkpoint WHERE task_id=?", taskId);
    }
}
