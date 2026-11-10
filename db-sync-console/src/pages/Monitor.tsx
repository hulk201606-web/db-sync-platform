import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { EP } from '../api/endpoints';
import { http } from '../api/client';
import { buildSeries, stateTone, sumShardStates, TASK_STATE_TEXT, toTaskRow } from '../api/adapters';
import { needPermText, useCan } from '../api/auth';
import { errorText } from '../api/errors';
import type {
  DataSource,
  LimitResult,
  MigrationActionView,
  MigrationProgressView,
  MonitorOverview,
  ShardItem,
  TaskRow,
  TaskView,
} from '../types';
import { DataTable, type Column } from '../components/DataTable';
import { ShardBars } from '../components/ShardBars';
import { ShardHeatmap } from '../components/ShardHeatmap';
import { TableProgressTable } from '../components/TableProgressTable';
import { DeadLetterDrawer } from '../components/DeadLetterDrawer';
import { StatCard } from '../components/StatCard';
import { StackedAreaChart } from '../components/StackedAreaChart';
import { StatusDot } from '../components/StatusDot';
import { ProgressBar } from '../components/ProgressBar';
import { Modal } from '../components/Modal';
import { LOG_LINES } from '../api/mock/data';
import { cls, fmtCount, fmtNum } from '../utils/format';

interface Notice {
  tone: 'ok' | 'warn' | 'danger' | 'info';
  text: string;
}

const POLL_MS = 5000;
const MAX_POINTS = 60;

/** 限速上限与后端一致：> 1e8 前端先拦（后端同样返回 E1001） */
const LIMIT_MAX = 100_000_000;
const LIMIT_QUICK = [
  { label: '不限速', value: 0 },
  { label: '1 万/秒', value: 10_000 },
  { label: '5 万/秒', value: 50_000 },
  { label: '10 万/秒', value: 100_000 },
];

function limitText(v: number | null): string {
  return v === null || v === 0 ? '不限速' : `${fmtNum(v)} 行/秒`;
}

export function Monitor() {
  const navigate = useNavigate();
  const can = useCan();

  const [overview, setOverview] = useState<MonitorOverview | null>(null);
  const [rows, setRows] = useState<TaskRow[]>([]);
  const [acc, setAcc] = useState<Record<string, number[]>>({});
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [selected, setSelected] = useState<string>('');
  const [deadOpen, setDeadOpen] = useState(false);
  const [deadShard, setDeadShard] = useState<ShardItem | null>(null);
  const [limitOpen, setLimitOpen] = useState(false);
  const [limitInput, setLimitInput] = useState('');
  const [limitErr, setLimitErr] = useState<string | null>(null);
  const [limitResult, setLimitResult] = useState<LimitResult | null>(null);
  const [logOpen, setLogOpen] = useState(false);
  const [logLines, setLogLines] = useState(LOG_LINES);
  const [pending, setPending] = useState(false);
  const [lastSample, setLastSample] = useState('');

  const accRef = useRef(acc);
  accRef.current = acc;

  const appendSeries = useCallback((ov: MonitorOverview) => {
    const stamp = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    setLastSample(stamp);
    setAcc((prev) => {
      const next: Record<string, number[]> = { ...prev };
      for (const [id, v] of Object.entries(ov.tps)) {
        const arr = [...(next[id] ?? []), Math.round(v)];
        next[id] = arr.slice(-MAX_POINTS);
      }
      return next;
    });
  }, []);

  const loadAll = useCallback(async () => {
    try {
      const [ov, ds, tvs] = await Promise.all([
        http.get<MonitorOverview>(EP.monitorOverview()),
        http.get<DataSource[]>(EP.dsList()),
        http.get<TaskView[]>(EP.taskList()),
      ]);
      const needProgress = tvs.filter((t) => ['RUNNING', 'PAUSED', 'FAILED', 'FINISHED'].includes(t.state));
      const progresses = await Promise.all(
        needProgress.map((t) =>
          http
            .get<MigrationProgressView>(EP.migrationProgress(t.id))
            .catch(() => null as MigrationProgressView | null),
        ),
      );
      const progressMap = new Map<string, MigrationProgressView>();
      needProgress.forEach((t, i) => {
        const p = progresses[i];
        if (p) progressMap.set(t.id, p);
      });
      const nextRows = tvs.map((t) => toTaskRow(t, ds, progressMap.get(t.id) ?? null));
      setOverview(ov);
      setRows(nextRows);
      if (!ov.tpsHistory) appendSeries(ov);
      setError(null);
      setSelected((cur) => cur || nextRows[0]?.id || '');
    } catch (err) {
      setError(errorText(err));
    }
  }, [appendSeries]);

  useEffect(() => {
    void loadAll();
    const timer = window.setInterval(() => {
      http
        .get<MonitorOverview>(EP.monitorOverview())
        .then((ov) => {
          setOverview(ov);
          if (!ov.tpsHistory) appendSeries(ov);
        })
        .catch(() => undefined);
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [loadAll, appendSeries]);

  const series = useMemo(
    () => (overview ? buildSeries(overview, rows, accRef.current) : []),
    [overview, rows, acc],
  );

  const current = rows.find((t) => t.id === selected);
  const shardTotals = sumShardStates(overview?.shardCounts);

  function openDead(taskId: string, shard?: ShardItem): void {
    setSelected(taskId);
    setDeadShard(shard ?? null);
    setDeadOpen(true);
  }

  function openLimit(taskId: string): void {
    const row = rows.find((t) => t.id === taskId);
    setSelected(taskId);
    setLimitInput(String(row?.rateLimitRows ?? ''));
    setLimitErr(null);
    setLimitResult(null);
    setLimitOpen(true);
  }

  async function toggleRun(): Promise<void> {
    if (!current) return;
    const running = current.state === 'RUNNING';
    setPending(true);
    try {
      const res = await http.post<MigrationActionView>(EP.taskState(current.id), {
        action: running ? 'PAUSE' : 'RESUME',
      });
      setRows((prev) =>
        prev.map((r) => (r.id === current.id ? { ...r, state: res.taskState } : r)),
      );
      setNotice({
        tone: res.applied ? (running ? 'warn' : 'ok') : 'info',
        text: res.applied
          ? running
            ? `${current.name} 已暂停：运行中分片 ≤ 10 秒内停止提交`
            : `${current.name} 已恢复：从 checkpoint 续跑`
          : `${current.name} 已处于 ${TASK_STATE_TEXT[res.taskState] ?? res.taskState} 状态（幂等，未重复执行）`,
      });
    } catch (err) {
      setNotice({ tone: 'danger', text: errorText(err) });
    } finally {
      setPending(false);
    }
  }

  async function applyLimit(raw: string): Promise<void> {
    if (!current) return;
    const text = raw.trim();
    if (text === '') {
      setLimitErr('请选择快捷值或输入正整数（0 表示不限速）');
      return;
    }
    const value = Number(text);
    if (!Number.isFinite(value) || !Number.isInteger(value) || value < 0) {
      setLimitErr('限速值必须是不小于 0 的整数（0 表示不限速）');
      return;
    }
    if (value > LIMIT_MAX) {
      setLimitErr(`限速值不能超过 ${fmtNum(LIMIT_MAX)} 行/秒`);
      return;
    }
    setLimitErr(null);
    setPending(true);
    try {
      const res = await http.post<LimitResult>(EP.migrationLimit(current.id), { rowsPerSec: value });
      setRows((prev) =>
        prev.map((r) => (r.id === current.id ? { ...r, rateLimitRows: res.readLimit } : r)),
      );
      setLimitResult(res);
      setNotice({
        tone: res.effective === 'IMMEDIATE' ? 'ok' : 'warn',
        text:
          res.effective === 'IMMEDIATE'
            ? `${current.name} ${limitText(res.readLimit)} 已热生效（运行中立即按新速率）`
            : `${current.name} ${limitText(res.readLimit)} 已保存，任务未在运行，将在下次启动时生效`,
      });
    } catch (err) {
      setLimitErr(errorText(err));
    } finally {
      setPending(false);
    }
  }

  async function openLogs(): Promise<void> {
    if (!current) return;
    setLogOpen(true);
    try {
      const lines = await http.get<typeof LOG_LINES>(EP.taskLogs(current.id));
      setLogLines(lines);
    } catch {
      /* 降级由 client 处理 */
    }
  }

  const tableColumns: Column<TaskRow>[] = [
    {
      key: 'name',
      title: '任务',
      sortable: true,
      render: (t) => (
        <div>
          <div className="cell-strong">{t.name}</div>
          <div className="small faint mono">{t.id}</div>
        </div>
      ),
    },
    {
      key: 'link',
      title: '源 → 目标',
      render: (t) => (
        <span className="small">
          {t.sourceName} <span className="mono faint">→</span> {t.targetName}
        </span>
      ),
    },
    {
      key: 'state',
      title: '状态',
      sortable: true,
      render: (t) => (
        <StatusDot tone={stateTone(t.state)} pulse={t.state === 'RUNNING'}>
          {TASK_STATE_TEXT[t.state] ?? t.state}
        </StatusDot>
      ),
    },
    {
      key: 'percent',
      title: '分片进度',
      width: 200,
      sortable: true,
      sortValue: (t) => t.progress?.percent ?? -1,
      render: (t) =>
        t.progress ? (
          <div>
            <ProgressBar
              value={t.progress.percent}
              showPct
              tone={t.progress.failedShards > 0 ? 'danger' : t.progress.percent >= 100 ? 'ok' : 'accent'}
            />
            <div className="small faint mono" style={{ marginTop: 2 }}>
              {fmtNum(t.progress.successShards)}/{fmtNum(t.progress.totalShards)} 片
              {t.progress.failedShards > 0 ? (
                <span style={{ color: 'var(--danger)' }}> · 失败 {t.progress.failedShards}</span>
              ) : null}
            </div>
          </div>
        ) : (
          <span className="faint">—</span>
        ),
    },
    {
      key: 'rows',
      title: '已提交行',
      align: 'right',
      sortable: true,
      render: (t) => (t.progress ? fmtCount(t.progress.committedRows) : <span className="faint">—</span>),
    },
    {
      key: 'tps',
      title: 'TPS',
      align: 'right',
      sortable: true,
      sortValue: (t) => overview?.tps[t.id] ?? 0,
      render: (t) => {
        const v = overview?.tps[t.id] ?? 0;
        return v > 0 ? fmtNum(v) : <span className="faint">—</span>;
      },
    },
    {
      key: 'op',
      title: '操作',
      render: (t) => (
        <div className="row" style={{ gap: 10 }}>
          <button className="btn-link" onClick={() => navigate(`/tasks/${t.id}`)}>
            详情
          </button>
          <button className="btn-link" onClick={() => openDead(t.id)}>
            死信
          </button>
          <button className="btn-link" onClick={() => openLimit(t.id)}>
            限速
          </button>
        </div>
      ),
    },
  ];

  return (
    <div>
      <div className="page-head">
        <div className="page-title">
          <h1>迁移监控大盘</h1>
          <span className="page-sub">数据源：/api/monitor/overview（全局聚合）· 每 {POLL_MS / 1000}s 轮询</span>
        </div>
        <div className="page-actions">
          <select
            className="select"
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
            style={{ width: 220 }}
          >
            {rows.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
          {current ? (
            <StatusDot tone={stateTone(current.state)} pulse={current.state === 'RUNNING'}>
              {TASK_STATE_TEXT[current.state] ?? current.state}
            </StatusDot>
          ) : null}
          <button
            className="btn"
            disabled={pending || !current || !can('TASK_EXEC')}
            title={can('TASK_EXEC') ? undefined : needPermText('TASK_EXEC')}
            onClick={() => void toggleRun()}
          >
            {current?.state === 'RUNNING' ? '暂停' : '继续'}
          </button>
          <button
            className="btn"
            disabled={!current || !can('TASK_EXEC')}
            title={can('TASK_EXEC') ? undefined : needPermText('TASK_EXEC')}
            onClick={() => {
              if (current) openLimit(current.id);
            }}
          >
            限速调整
          </button>
          <button className="btn" onClick={() => void openLogs()} disabled={!current}>
            查看日志
          </button>
          <button className="btn" onClick={() => void loadAll()}>
            刷新
          </button>
        </div>
      </div>

      {notice ? (
        <div className={cls('alert', notice.tone)} style={{ marginBottom: 12 }}>
          <span>{notice.text}</span>
          <button className="btn-link" style={{ marginLeft: 'auto' }} onClick={() => setNotice(null)}>
            关闭
          </button>
        </div>
      ) : null}

      {error ? (
        <div className="alert danger mb12">
          大盘接口异常：{error}
          <button className="btn-link" style={{ marginLeft: 'auto' }} onClick={() => void loadAll()}>
            重试
          </button>
        </div>
      ) : null}

      {!overview && error ? (
        <div className="card">
          <div className="card-body">
            <div className="state">
              <div className="icon">⚠</div>
              <div className="title">大盘数据加载失败</div>
              <div className="mono small" style={{ maxWidth: 720, wordBreak: 'break-all' }}>
                {error}
              </div>
              <div className="alert warn mt12" style={{ maxWidth: 720, textAlign: 'left' }}>
                接口返回 404 说明路径写错或后端未部署该端点，控制台不会静默降级成演示数据——请对照
                <span className="mono"> /api/openapi.json </span>核对路径。
              </div>
              <button className="btn mt12" onClick={() => void loadAll()}>
                重试
              </button>
            </div>
          </div>
        </div>
      ) : !overview ? (
        <div className="kpi-grid">
          {Array.from({ length: 6 }, (_, i) => (
            <div className="stat" key={i}>
              <div className="skeleton" style={{ width: '60%', height: 10 }} />
              <div className="skeleton" style={{ width: '80%', height: 26, marginTop: 10 }} />
              <div className="skeleton" style={{ width: '40%', height: 9, marginTop: 8 }} />
            </div>
          ))}
        </div>
      ) : (
        <>
          <div className="kpi-grid">
            <StatCard
              label="已提交行数"
              value={fmtCount(overview.committedRows)}
              unit="行"
              tone="accent"
              sub={<span>全部任务合计 · 采样 {lastSample || '—'}</span>}
            />
            <StatCard
              label="实测 TPS"
              value={fmtNum(overview.totalTps)}
              unit="行/秒"
              tone="ok"
              sub={<span>{overview.aliveInstances} 个实例合计</span>}
            />
            <StatCard
              label="分片完成率"
              value={shardTotals.total > 0 ? ((shardTotals.success / shardTotals.total) * 100).toFixed(1) : '0.0'}
              unit="%"
              tone="accent"
              sub={
                <span className="mono">
                  {fmtNum(shardTotals.success)}/{fmtNum(shardTotals.total)} 片 · 失败 {shardTotals.failed}
                </span>
              }
            />
            <StatCard
              label="未处理死信"
              value={overview.deadLetterOpen}
              unit="条"
              tone={overview.deadLetterOpen > 0 ? 'danger' : 'ok'}
              right={
                <button className="btn-link" onClick={() => openDead(selected)}>
                  处置
                </button>
              }
              sub={<span>按任务下钻，可直接重放</span>}
            />
            <StatCard
              label="活跃实例"
              value={overview.aliveInstances}
              unit="个"
              tone="accent"
              sub={<span>租约正常 · 续约 30s</span>}
            />
            <StatCard
              label="运行中任务"
              value={overview.taskCounts.RUNNING ?? 0}
              of={String(overview.totalTasks)}
              tone="warn"
              sub={
                <span>
                  暂停 {overview.taskCounts.PAUSED ?? 0} · 失败 {overview.taskCounts.FAILED ?? 0}
                </span>
              }
            />
          </div>

          <div className="card mt16">
            <div className="card-head">
              <div className="card-title">
                TPS 趋势（按任务）
                <span className="hint">后端只提供当前值，前端按 {POLL_MS / 1000}s 轮询累积，最多 {MAX_POINTS} 点</span>
              </div>
              <span className="small faint">采样 {lastSample || '—'}</span>
            </div>
            <div className="card-body">
              {series.length > 0 && (series[0]?.values.length ?? 0) > 0 ? (
                <StackedAreaChart series={series} height={196} />
              ) : (
                <div className="state">
                  <div className="icon">◷</div>
                  <div className="title">正在累积采样点</div>
                  <div>每 {POLL_MS / 1000}s 采集一次，约 1 分钟后曲线成形</div>
                </div>
              )}
            </div>
          </div>

          <div className="card mt16">
            <div className="card-head">
              <div className="card-title">
                分片状态分布
                <span className="hint">按任务聚合</span>
              </div>
              <span className="small faint">聚合概览 · 逐分片明细见下方「逐分片进度」</span>
            </div>
            <div className="card-body">
              <ShardBars
                rows={rows.map((t) => ({ id: t.id, name: t.name, dist: overview.shardHeat[t.id] ?? {} }))}
                onSelect={(id) => setSelected(id)}
              />
            </div>
          </div>

          <div className="card mt16">
            <div className="card-head">
              <div className="card-title">
                逐分片进度 · {current?.name ?? '—'}
                <span className="hint">一格一个分片 · 绿=成功 黄=运行中 红=失败/死信 灰=待运行 浅灰=暂停</span>
              </div>
              <span className="small faint mono">
                GET /api/tasks/{selected || '{id}'}/shards · 每页 200
              </span>
            </div>
            <div className="card-body">
              {selected ? (
                <ShardHeatmap
                  taskId={selected}
                  onPickFailed={(s) => openDead(selected, s)}
                />
              ) : (
                <div className="state">
                  <div className="icon">▦</div>
                  <div className="title">请选择一个任务</div>
                </div>
              )}
            </div>
          </div>

          <div className="card mt16">
            <div className="card-head">
              <div className="card-title">
                表级进度明细 · {current?.name ?? '—'}
                <span className="hint">GET /api/tasks/{`{taskId}`}/tables/progress</span>
              </div>
              <span className="small faint">percent 为空显示「—」，不显示 0%</span>
            </div>
            <div className="card-body">
              {selected ? (
                <TableProgressTable taskId={selected} onViewDead={() => openDead(selected)} />
              ) : (
                <div className="state">
                  <div className="icon">▤</div>
                  <div className="title">请选择一个任务</div>
                </div>
              )}
            </div>
          </div>

          <div className="card mt16">
            <div className="card-head">
              <div className="card-title">任务明细</div>
              <span className="small faint">点击表头排序 · 死信按任务下钻</span>
            </div>
            <div className="card-body flush">
              <DataTable columns={tableColumns} rows={rows} rowKey={(t) => t.id} minWidth={1040} />
            </div>
          </div>

          {overview.recentAlerts.length > 0 ? (
            <div className="card mt16">
              <div className="card-head">
                <div className="card-title">最近告警</div>
                <span className="small faint">来自 overview.recentAlerts</span>
              </div>
              <div className="card-body">
                {overview.recentAlerts.map((al) => (
                  <div key={`${al.at}-${al.rule}`} className="row" style={{ gap: 10, padding: '4px 0' }}>
                    <span className="mono small faint">{al.at}</span>
                    <span className="badge warn">{al.rule}</span>
                    <span className="small dim">{al.title}</span>
                  </div>
                ))}
              </div>
            </div>
          ) : null}
        </>
      )}

      <DeadLetterDrawer
        open={deadOpen}
        taskId={selected}
        shard={deadShard}
        onClose={() => {
          setDeadOpen(false);
          setDeadShard(null);
        }}
        onNotice={(text, tone) => setNotice({ text, tone })}
      />

      <Modal
        open={limitOpen}
        title={`限速调整 · ${current?.name ?? '-'}`}
        onClose={() => setLimitOpen(false)}
        footer={
          <>
            <button className="btn" onClick={() => setLimitOpen(false)}>
              {limitResult ? '关闭' : '取消'}
            </button>
            <button
              className="btn btn-primary"
              disabled={pending}
              title="POST /api/tasks/{id}/migration/limit"
              onClick={() => void applyLimit(limitInput)}
            >
              {pending ? '提交中…' : '应用'}
            </button>
          </>
        }
      >
        <div className="col" style={{ gap: 12 }}>
          <div className="alert info">
            <div>
              当前：<b>{limitText(current?.rateLimitRows ?? null)}</b>
            </div>
            <div className="small" style={{ marginTop: 4 }}>
              运行中的任务热生效（IMMEDIATE）；未运行的任务只落库，下次启动生效（NEXT_START）。0 表示不限速。
            </div>
          </div>

          <div className="field">
            <label>行/秒上限（0 = 不限速）</label>
            <input
              className="input mono"
              type="number"
              min={0}
              max={LIMIT_MAX}
              step={1000}
              value={limitInput}
              onChange={(e) => {
                setLimitInput(e.target.value);
                setLimitErr(null);
              }}
              placeholder="例如 50000"
            />
            <span className="small faint">取值范围 0 ~ {fmtNum(LIMIT_MAX)} 行/秒；负数或超上限前端直接拦截</span>
          </div>

          <div className="row wrap" style={{ gap: 6 }}>
            {LIMIT_QUICK.map((q) => (
              <button
                key={q.value}
                className={cls('btn btn-sm', limitInput === String(q.value) && 'btn-primary')}
                onClick={() => {
                  setLimitInput(String(q.value));
                  setLimitErr(null);
                }}
              >
                {q.label}
              </button>
            ))}
          </div>

          {limitErr ? <div className="alert danger">{limitErr}</div> : null}

          {limitResult ? (
            <div className={cls('alert', limitResult.effective === 'IMMEDIATE' ? 'ok' : 'warn')}>
              <div style={{ fontWeight: 700 }}>
                {limitResult.effective === 'IMMEDIATE'
                  ? '已热生效：运行中分片立即按新速率限速'
                  : '已保存，任务下次启动生效'}
                <span className={cls('badge', limitResult.effective === 'IMMEDIATE' ? 'ok' : 'warn')} style={{ marginLeft: 8 }}>
                  {limitResult.effective}
                </span>
              </div>
              <div className="small" style={{ marginTop: 4 }}>
                读 {limitText(limitResult.readLimit)} · 写 {limitText(limitResult.writeLimit)}
                {limitResult.effective === 'NEXT_START'
                  ? ' · 当前运行中的任务不受影响，请勿误认为已生效'
                  : ''}
              </div>
            </div>
          ) : null}
        </div>
      </Modal>

      <Modal
        open={logOpen}
        wide
        title={`任务日志 · ${current?.id ?? ''}`}
        onClose={() => setLogOpen(false)}
        footer={
          <>
            <button className="btn" onClick={() => setLogOpen(false)}>
              关闭
            </button>
            <button className="btn" onClick={() => void openLogs()}>
              刷新
            </button>
          </>
        }
      >
        <div className="log-view">
          {logLines.map((line, i) => (
            <div className="log-line" key={`${line.ts}-${i}`}>
              <span className="ts">{line.ts}</span>{' '}
              <span className={line.level === 'ERROR' ? 'lv-error' : line.level === 'WARN' ? 'lv-warn' : 'lv-info'}>
                {line.level.padEnd(5, ' ')}
              </span>{' '}
              {line.text}
            </div>
          ))}
        </div>
        <div className="small faint mt8">日志为最近 200 行滚动缓冲，完整日志见服务器 logback 归档。</div>
      </Modal>
    </div>
  );
}
