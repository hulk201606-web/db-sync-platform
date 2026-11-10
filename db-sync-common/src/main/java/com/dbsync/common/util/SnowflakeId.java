package com.dbsync.common.util;

/**
 * 雪花 ID（64 位：1 位符号 + 41 位时间戳 + 10 位 workerId + 12 位序列）。
 *
 * <p>元数据库主键统一由本类生成（架构 §7.1：不用 AUTO_INCREMENT，避免批量插入往返与跨库迁移问题）。
 * 线程安全；同一毫秒内序列耗尽时自旋到下一毫秒。
 */
public final class SnowflakeId {

    private static final long EPOCH = 1_700_000_000_000L;
    private static final long WORKER_BITS = 10L;
    private static final long SEQ_BITS = 12L;
    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1;
    private static final long MAX_SEQ = (1L << SEQ_BITS) - 1;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    /**
     * 构造。
     *
     * @param workerId 机器号 0..1023
     */
    public SnowflakeId(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("workerId 必须 0.." + MAX_WORKER);
        }
        this.workerId = workerId;
    }

    /**
     * 生成下一个 ID（严格递增）。
     *
     * @return 雪花 ID
     */
    public synchronized long nextId() {
        long ts = System.currentTimeMillis();
        if (ts < lastTimestamp) {
            // 时钟回拨：等待追平（本平台不重启时钟，回拨幅度极小）
            ts = lastTimestamp;
        }
        if (ts == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQ;
            if (sequence == 0) {
                ts = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = ts;
        return ((ts - EPOCH) << (WORKER_BITS + SEQ_BITS)) | (workerId << SEQ_BITS) | sequence;
    }

    /**
     * @param last 上一次时间戳
     * @return 下一毫秒
     */
    private long waitNextMillis(long last) {
        long ts = System.currentTimeMillis();
        while (ts <= last) {
            Thread.onSpinWait();
            ts = System.currentTimeMillis();
        }
        return ts;
    }

    /**
     * 依据主机名派生的默认 workerId（多实例部署时建议显式配置）。
     *
     * @return 0..1023
     */
    public static long defaultWorkerId() {
        String host = System.getenv("HOSTNAME");
        if (host == null) {
            host = "localhost";
        }
        return Math.floorMod((long) host.hashCode(), MAX_WORKER + 1);
    }
}
