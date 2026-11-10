package com.dbsync.api.cdc;

import com.dbsync.cdc.ChangeConsumer;
import com.dbsync.core.pool.NamedThreadPoolManager;
import com.dbsync.metadata.dao.SyncTaskDao;

/**
 * 控制器层测试用的增量运行期替身工厂。
 *
 * <p>用途：{@code TaskService} / {@code MigrationService} / {@code CdcService} 现在都要求注入
 * {@link CdcRuntimeManager}（生产语义：状态一变就要启停消费）。控制器测试关注的是
 * "接口入参/幂等/审计"，不需要真的起消费线程，因此注入一个"一旦真去装配上下文就立刻失败"的
 * 工厂：既满足构造契约，又能在测试里把"不该启动消费却启动了"变成一眼可见的失败。
 */
public final class CdcTestRuntimes {

    /** 本实例标识（测试固定值，便于断言 ownerInstance）。 */
    public static final String INSTANCE = "test-instance";

    private CdcTestRuntimes() {
    }

    /**
     * 构造一个不会真正消费的运行期管理器。
     *
     * @param taskDao 任务 DAO（运行态视图读配置用）
     * @return 管理器
     */
    public static CdcRuntimeManager idle(SyncTaskDao taskDao) {
        ChangeConsumer.ContextFactory neverCalled = (taskId, stopSignal) -> {
            throw new IllegalStateException("本测试不应装配增量上下文：task=" + taskId);
        };
        return new CdcRuntimeManager(new NamedThreadPoolManager(), neverCalled, taskDao, INSTANCE);
    }
}
