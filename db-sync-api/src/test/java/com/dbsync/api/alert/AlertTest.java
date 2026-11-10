package com.dbsync.api.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 告警测试（T45 验收 1/2）：6 条规则必须"持续够久"才触发；静默窗口去重。
 *
 * <p>用可控时钟驱动"持续 60 秒"，不 sleep 真实时间。
 */
class AlertTest {

    /** 可控时钟。 */
    static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
        }
    }

    /** 可调数据源。 */
    static final class FakeSource implements AlertService.AlertSource {
        long lag;
        long lagThreshold = 5000L;
        double failRate;
        double failThreshold = 0.01d;
        List<Long> failedTasks = List.of();
        long deadLetterOpen;
        int downDataSources;
        int takeover;
        /** 任务 ID → 任务名（邮件正文用；未配置时正文退化为展示 ID）。 */
        final Map<Long, String> taskNames = new java.util.HashMap<>();

        @Override
        public long lagP95Ms() {
            return lag;
        }

        @Override
        public long lagThresholdMs() {
            return lagThreshold;
        }

        @Override
        public double failRate() {
            return failRate;
        }

        @Override
        public double failRateThreshold() {
            return failThreshold;
        }

        @Override
        public List<Long> failedTaskIds() {
            return failedTasks;
        }

        @Override
        public long deadLetterOpen() {
            return deadLetterOpen;
        }

        @Override
        public int downDataSourceCount() {
            return downDataSources;
        }

        @Override
        public int takeoverInLast10Minutes() {
            return takeover;
        }

        @Override
        public String taskNameOf(long taskId) {
            return taskNames.get(Long.valueOf(taskId));
        }
    }

    @Test
    @DisplayName("延迟超阈值必须持续 60 秒才告警")
    void should_fireLag_Only_AfterSustained60s() {
        MutableClock clock = new MutableClock();
        FakeSource source = new FakeSource();
        AlertService service = new AlertService(source, clock, EnumSet.of(AlertService.Channel.LOG),
                null, Duration.ofSeconds(1L));
        source.lag = 9_000L;

        assertTrue(service.evaluate().isEmpty(), "条件刚成立不应立即告警");
        clock.advance(Duration.ofSeconds(59L));
        assertTrue(service.evaluate().isEmpty(), "59 秒仍未达持续时长");
        clock.advance(Duration.ofSeconds(2L));
        List<AlertService.AlertRecord> fired = service.evaluate();
        assertEquals(1, fired.size());
        assertEquals(AlertService.RULE_LAG, fired.get(0).getRule());
    }

    @Test
    @DisplayName("条件中断后重新计时（不累积）")
    void should_resetTimer_When_conditionClears() {
        MutableClock clock = new MutableClock();
        FakeSource source = new FakeSource();
        AlertService service = new AlertService(source, clock, EnumSet.of(AlertService.Channel.LOG),
                null, Duration.ofSeconds(1L));
        source.lag = 9_000L;
        service.evaluate();
        clock.advance(Duration.ofSeconds(40L));
        source.lag = 100L;
        assertTrue(service.evaluate().isEmpty());
        source.lag = 9_000L;
        service.evaluate();
        clock.advance(Duration.ofSeconds(30L));
        assertTrue(service.evaluate().isEmpty(), "中断后需重新计满 60 秒");
    }

    @Test
    @DisplayName("静默窗口内不重复发（30 分钟）")
    void should_dedupe_WithinSilenceWindow() {
        MutableClock clock = new MutableClock();
        FakeSource source = new FakeSource();
        AlertService service = new AlertService(source, clock, EnumSet.of(AlertService.Channel.LOG),
                null, Duration.ofSeconds(1L));
        source.lag = 9_000L;
        service.evaluate();
        clock.advance(Duration.ofSeconds(61L));
        assertEquals(1, service.evaluate().size());
        clock.advance(Duration.ofMinutes(5L));
        assertTrue(service.evaluate().isEmpty(), "静默期内不重复");
        assertTrue(service.isSilenced(AlertService.RULE_LAG));
        clock.advance(Duration.ofMinutes(26L));
        assertEquals(1, service.evaluate().size(), "静默窗口过后再发一次");
    }

    @Test
    @DisplayName("任务失败立即告警（持续时长 0）")
    void should_fireImmediately_When_taskFailed() {
        MutableClock clock = new MutableClock();
        FakeSource source = new FakeSource();
        AlertService service = new AlertService(source, clock, EnumSet.of(AlertService.Channel.LOG),
                null, Duration.ofSeconds(1L));
        source.failedTasks = List.of(1001L, 1002L);
        List<AlertService.AlertRecord> fired = service.evaluate();
        assertTrue(fired.stream().anyMatch(r -> AlertService.RULE_TASK_FAILED.equals(r.getRule())));
    }

    @Test
    @DisplayName("死信只在新增时告警（首次仅记录基线）")
    void should_fireDeadLetter_Only_WhenGrew() {
        MutableClock clock = new MutableClock();
        FakeSource source = new FakeSource();
        AlertService service = new AlertService(source, clock, EnumSet.of(AlertService.Channel.LOG),
                null, Duration.ofSeconds(1L));
        source.deadLetterOpen = 3L;
        assertTrue(service.evaluate().isEmpty(), "首次只建基线");
        source.deadLetterOpen = 3L;
        assertTrue(service.evaluate().isEmpty(), "未增长不告警");
        source.deadLetterOpen = 4L;
        assertTrue(service.evaluate().stream()
                .anyMatch(r -> AlertService.RULE_DEAD_LETTER.equals(r.getRule())));
    }

    @Test
    @DisplayName("失败率 / 数据源 DOWN / 租约接管 三条规则可触发")
    void should_fireOtherRules() {
        MutableClock clock = new MutableClock();
        FakeSource source = new FakeSource();
        AlertService service = new AlertService(source, clock, EnumSet.of(AlertService.Channel.LOG),
                null, Duration.ofSeconds(1L));
        source.failRate = 0.5d;
        source.downDataSources = 1;
        source.takeover = 5;
        // 接管规则 sustained=0（立即触发）+ 静默 1h（架构 §11「每任务每小时 1 次」），
        // 因此在首次 evaluate() 就会命中，而不是等 61s 后。
        List<String> firstRules = new ArrayList<>();
        for (AlertService.AlertRecord record : service.evaluate()) {
            firstRules.add(record.getRule());
        }
        assertTrue(firstRules.contains(AlertService.RULE_LEASE_TAKEOVER), "接管规则应触发：" + firstRules);
        clock.advance(Duration.ofSeconds(61L));
        List<String> rules = new ArrayList<>();
        for (AlertService.AlertRecord record : service.evaluate()) {
            rules.add(record.getRule());
        }
        // 失败率 / 数据源 DOWN 需持续 60s，故第二次 evaluate() 才触发。
        assertTrue(rules.contains(AlertService.RULE_FAIL_RATE), "失败率规则应触发：" + rules);
        assertTrue(rules.contains(AlertService.RULE_DATASOURCE_DOWN), "数据源规则应触发：" + rules);
        // 接管规则仍处于 1h 静默窗口内，不应重复告警。
        assertFalse(rules.contains(AlertService.RULE_LEASE_TAKEOVER), "静默窗口内不重复：" + rules);
    }

    @Test
    @DisplayName("邮件渠道未配置：sendEmail 返回 false（不再抛 UnsupportedOperationException）")
    void should_skipEmail_When_notConfigured() {
        AlertService service = new AlertService(new FakeSource());
        assertFalse(service.sendEmail("RULE", "title", "detail", Map.of()),
                "未配置邮件时必须静默降级为 false，而不是把异常抛给告警链路");
    }

    @Test
    @DisplayName("手动 fire 直接下发并记录历史")
    void should_recordHistory_When_fireManually() {
        AlertService service = new AlertService(new FakeSource());
        service.fire("MANUAL", "人工告警", "详情", Map.of("k", "v"));
        assertEquals(1, service.recent().size());
        assertEquals("MANUAL", service.recent().get(0).getRule());
        assertFalse(service.channels().isEmpty());
    }

    @Test
    @DisplayName("数据源异常不打断评估（取数失败按 0 处理）")
    void should_notThrow_When_sourceFails() {
        AlertService.AlertSource broken = new AlertService.AlertSource() {
            @Override
            public long lagP95Ms() {
                throw new IllegalStateException("boom");
            }

            @Override
            public long lagThresholdMs() {
                return 1000L;
            }

            @Override
            public double failRate() {
                throw new IllegalStateException("boom");
            }

            @Override
            public double failRateThreshold() {
                return 0.01d;
            }

            @Override
            public List<Long> failedTaskIds() {
                throw new IllegalStateException("boom");
            }

            @Override
            public long deadLetterOpen() {
                throw new IllegalStateException("boom");
            }

            @Override
            public int downDataSourceCount() {
                throw new IllegalStateException("boom");
            }

            @Override
            public int takeoverInLast10Minutes() {
                throw new IllegalStateException("boom");
            }
        };
        AlertService service = new AlertService(broken);
        assertTrue(service.evaluate().isEmpty());
    }
}
