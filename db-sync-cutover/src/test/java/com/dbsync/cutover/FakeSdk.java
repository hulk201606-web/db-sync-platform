package com.dbsync.cutover;

import java.time.Duration;

/**
 * 假 SDK：模拟"外部路由消费者"——看到新版本就生效并回 ack。
 *
 * <p><b>为什么需要</b>：两段式状态机的第二段（写 confirmed）依赖外部 ack。
 * 单测里没有真实 SDK，用一个轮询 {@link RouteRegistry#currentVersion} 的线程即可真实地
 * 复现"下发 → 生效 → ack"的时序；不启动它即可复现"SDK 挂了不 ack"（ack 超时场景）。
 *
 * <p>用法：{@code try (FakeSdk sdk = new FakeSdk(registry, planId, 20)) { ... }}。
 */
final class FakeSdk implements AutoCloseable {

    private final RouteRegistry registry;
    private final long planId;
    private final long ackDelayMs;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile boolean failing;
    private volatile long lastVersion;

    /**
     * 构造并启动。
     *
     * @param registry   路由注册表
     * @param planId     计划 ID
     * @param ackDelayMs 收到下发后延迟多久 ack（毫秒）
     */
    FakeSdk(RouteRegistry registry, long planId, long ackDelayMs) {
        this.registry = registry;
        this.planId = planId;
        this.ackDelayMs = Math.max(0L, ackDelayMs);
        // 基准版本必须在构造线程上读：若放到 loop() 里读，线程调度可能晚于构造后的
        // 第一次 publish，SDK 会把新版本误当成"启动前就存在"，于是永远不 ack。
        this.lastVersion = registry.currentVersion(planId);
        this.thread = new Thread(this::loop, "fake-sdk-" + planId);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /**
     * 让 SDK 进入"不 ack"状态（模拟网关不可用）。
     */
    void fail() {
        this.failing = true;
    }

    /**
     * 恢复 ack。
     */
    void recover() {
        this.failing = false;
    }

    /**
     * 等待"已 ack 到指定版本"（最长等待 timeout）。
     *
     * @param version 版本
     * @param timeout 超时
     * @return true 表示已确认
     */
    boolean awaitAcked(long version, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (registry.ackedVersion(planId) >= version) {
                return true;
            }
            sleep(5L);
        }
        return registry.ackedVersion(planId) >= version;
    }

    /**
     * 轮询循环。
     */
    private void loop() {
        while (running) {
            long current = registry.currentVersion(planId);
            if (current > lastVersion) {
                sleep(ackDelayMs);
                if (!running) {
                    return;
                }
                if (failing) {
                    // 模拟"下发到达但网关拒绝/不可用"：不 ack，等下一条
                    sleep(5L);
                    continue;
                }
                try {
                    registry.ack(planId, current);
                    lastVersion = current;
                } catch (RuntimeException e) {
                    // 旧版本（期间又发布了新版本）→ 忽略，循环会追上最新版本
                    lastVersion = current;
                }
            } else {
                sleep(5L);
            }
        }
    }

    /**
     * 可中断休眠。
     *
     * @param millis 毫秒
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
