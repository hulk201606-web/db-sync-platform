package com.dbsync.common.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 提交序号：先比 {@code commitTs}，再比 {@code seq}（可比较）。
 *
 * <p>用于增量保序判定：同一毫秒内的多个变更靠 {@code seq} 定序；
 * 跨毫秒的变更靠时间戳定序。时间精度（毫秒/微秒）不影响比较结果。
 */
public final class CommitSequence implements Comparable<CommitSequence> {

    private final Instant commitTs;
    private final long seq;

    /**
     * 构造。
     *
     * @param commitTs 提交时间，不可空
     * @param seq      组内序号
     */
    public CommitSequence(Instant commitTs, long seq) {
        this.commitTs = Objects.requireNonNull(commitTs, "commitTs");
        this.seq = seq;
    }

    /** @return 提交时间 */
    public Instant getCommitTs() {
        return commitTs;
    }

    /** @return 序号 */
    public long getSeq() {
        return seq;
    }

    @Override
    public int compareTo(CommitSequence o) {
        int byTs = this.commitTs.compareTo(o.commitTs);
        if (byTs != 0) {
            return byTs;
        }
        return Long.compare(this.seq, o.seq);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommitSequence)) {
            return false;
        }
        CommitSequence that = (CommitSequence) o;
        return seq == that.seq && commitTs.equals(that.commitTs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commitTs, seq);
    }

    @Override
    public String toString() {
        return commitTs + "#" + seq;
    }
}
