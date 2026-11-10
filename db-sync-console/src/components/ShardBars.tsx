import { SHARD_STATE_COLOR, SHARD_STATE_TEXT, sumShardStates } from '../api/adapters';
import { fmtNum } from '../utils/format';

export interface ShardDistRow {
  id: string;
  name: string;
  dist: Record<string, number>;
}

const ORDER = ['SUCCESS', 'RUNNING', 'FAILED', 'PENDING'];

/**
 * 分片状态分布（按任务聚合的概览）。
 * 后端 /api/monitor/overview 只给"每个任务下各状态的分片数量"，没有分片清单，
 * 因此概览用堆叠比例条；逐分片热力图由 ShardHeatmap 通过 /api/tasks/{id}/shards 提供，
 * 两者并存，概览不代表逐分片明细。
 */
export function ShardBars({ rows, onSelect }: { rows: ShardDistRow[]; onSelect?: (id: string) => void }) {
  return (
    <div>
      {rows.map((row) => {
        const s = sumShardStates(row.dist);
        const total = Math.max(1, s.total);
        return (
          <div
            key={row.id}
            className="shard-row"
            onClick={() => onSelect?.(row.id)}
            role={onSelect ? 'button' : undefined}
            tabIndex={onSelect ? 0 : undefined}
          >
            <div className="shard-name" title={row.name}>
              {row.name}
            </div>
            <div className="shard-track">
              {ORDER.map((state) => {
                const v = state === 'FAILED' ? (row.dist.FAILED ?? 0) + (row.dist.DEAD ?? 0) : row.dist[state] ?? 0;
                if (v <= 0) return null;
                return (
                  <i
                    key={state}
                    title={`${SHARD_STATE_TEXT[state] ?? state} ${fmtNum(v)} 片`}
                    style={{ width: `${(v / total) * 100}%`, background: SHARD_STATE_COLOR[state] }}
                  />
                );
              })}
            </div>
            <div className="shard-nums mono">
              <span style={{ color: 'var(--ok)' }}>{fmtNum(s.success)}</span>
              {s.running > 0 ? <span style={{ color: 'var(--warn)' }}>{fmtNum(s.running)}</span> : null}
              {s.failed > 0 ? <span style={{ color: 'var(--danger)' }}>{fmtNum(s.failed)}</span> : null}
              <span className="faint">/ {fmtNum(s.total)}</span>
            </div>
          </div>
        );
      })}
      <div className="legend mt12">
        {ORDER.map((state) => (
          <span className="item" key={state}>
            <span className="swatch" style={{ background: SHARD_STATE_COLOR[state] }} />
            {SHARD_STATE_TEXT[state] ?? state}
          </span>
        ))}
        <span className="faint" style={{ marginLeft: 'auto' }}>
          按任务聚合 · 逐分片明细见下方热力图
        </span>
      </div>
    </div>
  );
}
