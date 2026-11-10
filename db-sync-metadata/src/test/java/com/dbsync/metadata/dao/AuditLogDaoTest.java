package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.AuditResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审计 DAO 测试：只追加、按目标/时间窗可查、拒绝操作可统计。
 */
class AuditLogDaoTest extends MetaDaoTestSupport {

    @Test
    @DisplayName("should_appendAndQuery：按操作人/目标/时间窗都能查到")
    void should_appendAndQuery() {
        AuditLogDao dao = new AuditLogDao(jdbc);
        assertThat(dao.append("alice", "ADMIN", "CUTOVER_ADVANCE", "CUTOVER_PLAN",
                Long.valueOf(1L), "PLAN-001", "{\"from\":\"GRAY_READ\"}", AuditResult.SUCCESS,
                null, "10.0.0.1", "Mozilla", "trace-1")).isEqualTo(1);
        assertThat(dao.append("bob", "VIEWER", "CUTOVER_ADVANCE", "CUTOVER_PLAN",
                Long.valueOf(1L), "PLAN-001", "{}", AuditResult.DENIED, "E8001",
                "10.0.0.2", "curl", "trace-2")).isEqualTo(1);

        List<AuditLogDao.AuditLogRow> byActor = dao.findByActor("alice", 10);
        assertThat(byActor).hasSize(1);
        assertThat(byActor.get(0).getParamsDigest()).contains("GRAY_READ");
        assertThat(dao.findByTarget("CUTOVER_PLAN", 1L, 10)).hasSize(2);
        assertThat(dao.findByTimeRange(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60),
                10)).hasSize(2);
        assertThat(dao.countByAction("CUTOVER_ADVANCE")).isEqualTo(2L);
    }

    @Test
    @DisplayName("should_countDenied：越权尝试可统计（安全告警数据源）")
    void should_countDenied() {
        AuditLogDao dao = new AuditLogDao(jdbc);
        dao.append("bob", "VIEWER", "TASK_DELETE", "TASK", Long.valueOf(9L), "t9", "{}",
                AuditResult.DENIED, "E8001", "10.0.0.3", "curl", null);
        dao.append("alice", "ADMIN", "TASK_DELETE", "TASK", Long.valueOf(9L), "t9", "{}",
                AuditResult.SUCCESS, null, "10.0.0.1", "Mozilla", null);
        assertThat(dao.countDenied(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60)))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("should_deleteBefore：保留策略按时间清理")
    void should_deleteBefore() {
        AuditLogDao dao = new AuditLogDao(jdbc);
        dao.append("alice", "ADMIN", "LOGIN", "USER", null, "admin", "{}", AuditResult.SUCCESS,
                null, "10.0.0.1", "Mozilla", null);
        jdbc.update("UPDATE sys_audit_log SET created_at=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(86400 * 400L)));
        assertThat(dao.deleteBefore(Instant.now().minusSeconds(86400 * 365L), 100)).isEqualTo(1);
        assertThat(dao.countByAction("LOGIN")).isZero();
    }
}
