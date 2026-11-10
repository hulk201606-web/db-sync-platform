package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 触发器台账 DAO 测试：幂等登记（同表重复登记不产生第二行）+ 状态流转。
 */
class TriggerDefDaoTest extends MetaDaoTestSupport {

    private static final long TASK_ID = 4901L;
    private static final long TABLE_MAPPING_ID = 61L;

    @Test
    @DisplayName("should_upsertIdempotently：同表重复登记只有一行，且回到 PENDING")
    void should_upsertIdempotently() {
        TriggerDefDao dao = new TriggerDefDao(jdbc);
        assertThat(dao.upsertPending(TASK_ID, TABLE_MAPPING_ID, 1L, "t_order_log",
                "trg_ins,trg_upd,trg_del", "[\"DDL1\"]")).isEqualTo(1);
        assertThat(dao.markDeployed(TASK_ID, TABLE_MAPPING_ID)).isEqualTo(1);
        assertThat(dao.findByTaskAndTable(TASK_ID, TABLE_MAPPING_ID).orElseThrow().getState())
                .isEqualTo("DEPLOYED");
        // 重新登记（例如 DDL 升级）→ 仍一行，状态回 PENDING
        assertThat(dao.upsertPending(TASK_ID, TABLE_MAPPING_ID, 1L, "t_order_log",
                "trg_ins,trg_upd,trg_del", "[\"DDL2\"]")).isEqualTo(1);
        assertThat(dao.findByTask(TASK_ID)).hasSize(1);
        TriggerDefDao.TriggerDefRecord record =
                dao.findByTaskAndTable(TASK_ID, TABLE_MAPPING_ID).orElseThrow();
        assertThat(record.getState()).isEqualTo("PENDING");
        assertThat(record.getDdlJson()).contains("DDL2");
    }

    @Test
    @DisplayName("should_markFailedAndDropped：失败留错误，切流后可整任务置 DROPPED")
    void should_markFailedAndDropped() {
        TriggerDefDao dao = new TriggerDefDao(jdbc);
        dao.upsertPending(TASK_ID, TABLE_MAPPING_ID, 1L, "t_order_log", "trg_ins", "[]");
        assertThat(dao.markFailed(TASK_ID, TABLE_MAPPING_ID, "ORA-00955 名称已被占用")).isEqualTo(1);
        assertThat(dao.findByState("FAILED")).hasSize(1);
        assertThat(dao.markAllDropped(TASK_ID)).isEqualTo(1);
        assertThat(dao.findByState("DEPLOYED")).isEmpty();
        assertThat(dao.findByTaskAndTable(TASK_ID, TABLE_MAPPING_ID).orElseThrow().getLastError())
                .contains("ORA-00955");
    }
}
