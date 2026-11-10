import { useCallback, useEffect, useState } from 'react';
import { EP } from '../api/endpoints';
import { http } from '../api/client';
import { errorText } from '../api/errors';
import { SHARD_STATE_TEXT, shardCellTone } from '../api/adapters';
import type { ShardItem, ShardPage } from '../types';
import { cls, fmtNum } from '../utils/format';

const PAGE_SIZE = 200;

const STATE_FILTERS = [
  { value: '', label: '全部状态' },
  { value: 'SUCCESS', label: '已完成' },
  { value: 'RUNNING', label: '运行中' },
  { value: 'FAILED', label: '失败' },
  { value: 'DEAD', label: '死信' },
  { value: 'PENDING', label: '待运行' },
  { value: 'PAUSED', label: '已暂停' },
];

const LEGEND: Array<{ state: string; label: string }> = [
  { state: 'SUCCESS', label: '成功' },
  { state: 'RUNNING', label: '运行中/已认领' },
  { state: 'FAILED', label: '失败/死信' },
  { state: 'PENDING', label: '待运行' },
  { state: 'PAUSED', label: '已暂停' },
];

function shardTip(s: ShardItem): string {
  const lines = [
    `分片 #${s.shardNo}`,
    `主键区间 (${s.pkStart ?? '—'}, ${s.pkEnd ?? '—'}]`,
    `状态 ${SHARD_STATE_TEXT[s.state] ?? s.state}`,
    `已提交 ${fmtNum(s.committedRows)} 行`,
    `所属实例 ${s.leaseOwner ?? '—'}`,
    `attempt ${s.attempt}`,
  ];
  if (s.lastError) lines.push(`lastError ${s.lastError}`);
  return lines.join('\n');
}

function badgeOf(state: string): 'ok' | 'warn' | 'danger' | 'neutral' {
  const tone = shardCellTone(state);
  if (tone === 'ok') return 'ok';
  if (tone === 'run') return 'warn';
  if (tone === 'fail') return 'danger';
  return 'neutral';
}

interface ShardHeatmapProps {
  taskId: string;
  /** 点击失败/死信分片：打开死信抽屉并定位 */
  onPickFailed?: (shard: ShardItem) => void;
}

/**
 * 逐分片热力图（PRD §9.3）：一格一个分片，不合并、不补空格子。
 * 数据来自 GET /api/tasks/{taskId}/shards，分页拉取（每页 200，上限 1000），
 * 分片多时用「加载更多」追加，绝不一次请求全部再渲染。
 */
export function ShardHeatmap({ taskId, onPickFailed }: ShardHeatmapProps) {
  const [items, setItems] = useState<ShardItem[]>([]);
  const [total, setTotal] = useState(0);
  const [state, setState] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [active, setActive] = useState<ShardItem | null>(null);

  const load = useCallback(
    async (pageNo: number, append: boolean) => {
      setLoading(true);
      try {
        const res = await http.get<ShardPage>(EP.taskShards(taskId, state, pageNo, PAGE_SIZE));
        const list = Array.isArray(res.items) ? res.items : [];
        setItems((prev) => (append ? [...prev, ...list] : list));
        setTotal(typeof res.total === 'number' ? res.total : list.length);
        setError(null);
      } catch (err) {
        if (!append) {
          setItems([]);
          setTotal(0);
        }
        setError(errorText(err));
      } finally {
        setLoading(false);
      }
    },
    [taskId, state],
  );

  useEffect(() => {
    setActive(null);
    void load(1, false);
  }, [load]);

  const loaded = items.length;
  const remain = Math.max(0, total - loaded);

  function onCell(shard: ShardItem): void {
    setActive(shard);
    if (shard.state === 'FAILED' || shard.state === 'DEAD') onPickFailed?.(shard);
  }

  return (
    <div>
      <div className="row wrap" style={{ gap: 10, marginBottom: 10 }}>
        <select
          className="select"
          value={state}
          onChange={(e) => setState(e.target.value)}
          style={{ width: 140 }}
          aria-label="按分片状态过滤"
        >
          {STATE_FILTERS.map((f) => (
            <option key={f.value} value={f.value}>
              {f.label}
            </option>
          ))}
        </select>
        <span className="small faint mono">
          已加载 {fmtNum(loaded)} / 共 {fmtNum(total)} 片 · 每页 {PAGE_SIZE}（上限 1000）
        </span>
        <button className="btn btn-sm" style={{ marginLeft: 'auto' }} onClick={() => void load(1, false)} disabled={loading}>
          重新加载
        </button>
      </div>

      {loading && loaded === 0 ? (
        <div className="shard-grid" aria-busy="true">
          {Array.from({ length: 60 }, (_, i) => (
            <span key={i} className="shard-cell idle skeleton" />
          ))}
        </div>
      ) : loaded === 0 ? (
        <div className="state">
          <div className="icon">▦</div>
          <div className="title">该任务尚未生成分片</div>
          <div>
            {error
              ? `分片接口暂不可用：${error}`
              : '任务启动并完成分片规划后，这里会按「一格一个分片」展示进度'}
          </div>
          {error ? (
            <button className="btn btn-sm mt12" onClick={() => void load(1, false)}>
              重试
            </button>
          ) : null}
        </div>
      ) : (
        <>
          <div className="shard-grid">
            {items.map((s) => (
              <button
                key={s.shardId}
                type="button"
                className={cls(
                  'shard-cell',
                  shardCellTone(s.state),
                  active?.shardId === s.shardId && 'active',
                )}
                title={shardTip(s)}
                aria-label={`分片 ${s.shardNo}`}
                onMouseEnter={() => setActive(s)}
                onFocus={() => setActive(s)}
                onClick={() => onCell(s)}
              />
            ))}
          </div>

          {remain > 0 ? (
            <div className="row" style={{ gap: 12, marginTop: 10 }}>
              <button
                className="btn btn-sm"
                disabled={loading}
                onClick={() => void load(Math.floor(loaded / PAGE_SIZE) + 1, true)}
              >
                {loading ? '加载中…' : `加载更多（剩余 ${fmtNum(remain)} 片）`}
              </button>
              <span className="small faint">避免一次拉取全部分片，按页追加渲染</span>
            </div>
          ) : null}

          <div className="shard-inspect">
            {active ? (
              <>
                <span className="mono" style={{ color: 'var(--text)' }}>
                  分片 #{active.shardNo}
                </span>
                <span className="mono faint">
                  主键 ({active.pkStart ?? '—'}, {active.pkEnd ?? '—'}]
                </span>
                <span className={cls('badge', badgeOf(active.state))}>
                  {SHARD_STATE_TEXT[active.state] ?? active.state}
                </span>
                <span className="small faint">已提交 {fmtNum(active.committedRows)} 行</span>
                <span className="small faint">实例 {active.leaseOwner ?? '—'}</span>
                <span className="small faint">attempt {active.attempt}</span>
                {active.lastError ? (
                  <span className="small" style={{ color: 'var(--danger)' }} title={active.lastError}>
                    {active.lastError.length > 64 ? `${active.lastError.slice(0, 64)}…` : active.lastError}
                  </span>
                ) : null}
              </>
            ) : (
              <span className="small faint">悬停或点击任一格子查看分片详情；点击失败/死信分片可直接定位死信</span>
            )}
          </div>

          <div className="legend mt12">
            {LEGEND.map((l) => (
              <span className="item" key={l.state}>
                <span className={cls('shard-cell', shardCellTone(l.state))} style={{ width: 10, height: 10 }} />
                {l.label}
              </span>
            ))}
            <span className="faint" style={{ marginLeft: 'auto' }}>
              一格一个分片 · 未加载的分片不占格子
            </span>
          </div>
        </>
      )}
    </div>
  );
}
