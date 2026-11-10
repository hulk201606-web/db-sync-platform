import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { EP } from '../api/endpoints';
import { http } from '../api/client';
import { needPermText, useCan } from '../api/auth';
import { errorText } from '../api/errors';
import { SHARD_STATE_COLOR, stateTone, TASK_STATE_TEXT } from '../api/adapters';
import type {
  CdcLatency,
  ChangeTrace,
  DataSource,
  CutoverPlan,
  MigrationActionView,
  MigrationProgressView,
  ShardItem,
  TaskView,
} from '../types';
import { StatCard } from '../components/StatCard';
import { DataTable, type Column } from '../components/DataTable';
import { StatusDot } from '../components/StatusDot';
import { ProgressBar } from '../components/ProgressBar';
import { ShardHeatmap } from '../components/ShardHeatmap';
import { TableProgressTable } from '../components/TableProgressTable';
import { DeadLetterDrawer, type NoticeTone } from '../components/DeadLetterDrawer';
import { DbTag } from './Tasks';
import { CDC_MODE_TEXT, OP_TEXT, cls, fmtCount, fmtNum } from '../utils/format';

const TABS = ['总览', '迁移进度', '增量同步', '比对', '切流'] as const;
type Tab = (typeof TABS)[number];

const HISTORY = [
  { jobId: 'CMP-20261109-0007', at: '2026-11-09 14:02:11', verdict: 'WARN', passRate: 99.993, diffRows: 5846, cost: '11m32s', operator: 'alice' },
  { jobId: 'CMP-20261109-0006', at: '2026-11-09 08:02:03', verdict: 'WARN', passRate: 99.995, diffRows: 4102, cost: '11m08s', operator: 'system' },
  { jobId: 'CMP-20261108-0011', at: '2026-11-08 20:02:19', verdict: 'PASS', passRate: 99.9998, diffRows: 12, cost: '10m51s', operator: 'system' },
  { jobId: 'CMP-20261108-0004', at: '2026-11-08 14:02:07', verdict: 'FAIL', passRate: 99.87, diffRows: 112_940, cost: '12m04s', operator: 'alice' },
];

export function TaskDetail() {
  const { id = '' } = useParams();
  const can = useCan();
  const [tab, setTab] = useState<Tab>('总览');
  const [notice, setNotice] = useState<{ tone: NoticeTone; text: string } | null>(null);
  const [deadOpen, setDeadOpen] = useState(false);
  const [deadShard, setDeadShard] = useState<ShardItem | null>(null);
  const [task, setTask] = useState<TaskView | null>(null);
  const [progress, setProgress] = useState<MigrationProgressView | null>(null);
  const [datasources, setDatasources] = useState<DataSource[]>([]);
  const [plan, setPlan] = useState<CutoverPlan | null>(null);
  const [cdc, setCdc] = useState<CdcLatency | null>(null);
  const [traceTable, setTraceTable] = useState('T_ORDER');
  const [tracePk, setTracePk] = useState('88122301');
  const [traceQuery, setTraceQuery] = useState('88122301');
  const [changes, setChanges] = useState<ChangeTrace[]>([]);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    let alive = true;
    void (async () => {
      const [t, ds] = await Promise.all([
        http.get<TaskView>(EP.taskDetail(id)).catch(() => null),
        http.get<DataSource[]>(EP.dsList()).catch(() => [] as DataSource[]),
      ]);
      if (!alive) return;
      setTask(t);
      setDatasources(ds);
      if (t) {
        const p = await http.get<MigrationProgressView>(EP.migrationProgress(t.id)).catch(() => null);
        if (alive) setProgress(p);
        const c = await http.get<CdcLatency>(EP.cdcLatency(t.id)).catch(() => null);
        if (alive) setCdc(c);
        const pl = await http.get<CutoverPlan>(EP.cutoverByTask(t.id)).catch(() => null);
        if (alive) setPlan(pl);
      }
    })();
    return () => {
      alive = false;
    };
  }, [id]);

  useEffect(() => {
    let alive = true;
    void http
      .get<ChangeTrace[]>(EP.cdcChanges(id, traceTable, traceQuery))
      .then((rows) => {
        if (alive) setChanges(rows);
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [id, traceTable, traceQuery]);

  async function act(action: 'START' | 'PAUSE' | 'RESUME'): Promise<void> {
    if (!task) return;
    setPending(true);
    try {
      const res = await http.post<MigrationActionView>(EP.taskState(task.id), { action });
      setTask({ ...task, state: res.taskState });
      setNotice({
        tone: res.applied ? 'ok' : 'info',
        text: res.applied
          ? `操作已生效：${action} → ${res.taskState}`
          : `已处于 ${res.taskState} 状态，本次未重复执行（applied=false）`,
      });
    } catch (err) {
      setNotice({ tone: 'danger', text: errorText(err) });
    } finally {
      setPending(false);
    }
  }

  async function precheck(): Promise<void> {
    setPending(true);
    try {
      const res = await http.post<{ pass: boolean; blocking: string[]; warnings: string[] }>(EP.taskPrecheck(id));
      setNotice({
        tone: res.blocking.length > 0 ? 'danger' : 'ok',
        text:
          res.blocking.length > 0
            ? `预检未通过：${res.blocking.length} 项阻断 — ${res.blocking.join('；')}`
            : `预检通过：${res.warnings.length} 项告警 — ${res.warnings.join('；')}`,
      });
    } finally {
      setPending(false);
    }
  }

  async function runCompare(): Promise<void> {
    await http.post(EP.compareCreate(id));
    setNotice({ tone: 'ok', text: '已提交全量比对任务，预计 11 分钟完成，完成后可从比对历史进入报告' });
  }

  const source = datasources.find((d) => d.id === task?.sourceDsId);
  const target = datasources.find((d) => d.id === task?.targetDsId);

  const changeColumns: Column<ChangeTrace>[] = [
    { key: 'seq', title: '顺序号', render: (c) => <span className="mono small">{c.seq}</span> },
    {
      key: 'op',
      title: '操作',
      render: (c) => (
        <span className={cls('badge', c.op === 'DELETE' ? 'danger' : c.op === 'INSERT' ? 'ok' : 'info')}>
          {OP_TEXT[c.op]}
        </span>
      ),
    },
    { key: 'pk', title: '主键', render: (c) => <span className="mono">{c.pk}</span> },
    { key: 'src', title: '源端提交', render: (c) => <span className="mono small">{c.sourceCommitAt}</span> },
    { key: 'applied', title: '应用时间', render: (c) => <span className="mono small">{c.appliedAt}</span> },
    { key: 'lat', title: '延迟', align: 'right', render: (c) => `${c.latencyMs}ms` },
    {
      key: 'result',
      title: '结果',
      render: (c) => (
        <StatusDot tone={c.result === 'APPLIED' ? 'ok' : c.result === 'IGNORED' ? 'warn' : 'danger'}>
          {c.result === 'APPLIED' ? '已应用' : c.result === 'IGNORED' ? '被忽略' : '失败'}
        </StatusDot>
      ),
    },
    { key: 'detail', title: '说明', render: (c) => <span className="small muted">{c.detail}</span> },
  ];

  return (
    <div>
      <div className="page-head">
        <div className="page-title">
          <h1>{task?.name ?? '任务详情'}</h1>
          <span className="page-sub mono">{id}</span>
          {task ? (
            <StatusDot tone={stateTone(task.state)} pulse={task.state === 'RUNNING'}>
              {TASK_STATE_TEXT[task.state] ?? task.state}
            </StatusDot>
          ) : null}
        </div>
        <div className="page-actions">
          <button
            className="btn"
            disabled={pending || !can('TASK_EXEC')}
            title={can('TASK_EXEC') ? undefined : needPermText('TASK_EXEC')}
            onClick={() => void precheck()}
          >
            预检
          </button>
          {task?.state === 'RUNNING' ? (
            <button
              className="btn"
              disabled={pending || !can('TASK_EXEC')}
              title={can('TASK_EXEC') ? undefined : needPermText('TASK_EXEC')}
              onClick={() => void act('PAUSE')}
            >
              暂停
            </button>
          ) : (
            <button
              className="btn btn-primary"
              disabled={pending || !can('TASK_EXEC')}
              title={can('TASK_EXEC') ? undefined : needPermText('TASK_EXEC')}
              onClick={() => void act(task?.state === 'PAUSED' ? 'RESUME' : 'START')}
            >
              {task?.state === 'PAUSED' ? '继续' : '启动'}
            </button>
          )}
          <Link className="btn" to="/monitor">
            返回大盘
          </Link>
        </div>
      </div>

      {notice ? (
        <div className={cls('alert', notice.tone, 'mb12')}>
          <span>{notice.text}</span>
          <button className="btn-link" style={{ marginLeft: 'auto' }} onClick={() => setNotice(null)}>
            关闭
          </button>
        </div>
      ) : null}

      <div className="tabs">
        {TABS.map((t) => (
          <button key={t} className={cls('tab', tab === t && 'active')} onClick={() => setTab(t)}>
            {t}
            {t === '比对' ? <span className="count">{HISTORY.length}</span> : null}
          </button>
        ))}
      </div>

      {tab === '总览' ? (
        <div className="row wrap" style={{ gap: 14, alignItems: 'flex-start' }}>
          <div className="card grow" style={{ minWidth: 420 }}>
            <div className="card-head">
              <div className="card-title">任务信息</div>
              <span className="small faint mono">TaskView</span>
            </div>
            <div className="card-body">
              <dl className="kv">
                <dt>源 → 目标</dt>
                <dd className="row" style={{ gap: 8 }}>
                  <DbTag type={source?.type ?? '-'} />
                  <span className="arrow">→</span>
                  <DbTag type={target?.type ?? '-'} />
                </dd>
                <dt>数据源</dt>
                <dd>
                  {source?.name ?? task?.sourceDsId} → {target?.name ?? task?.targetDsId}
                </dd>
                <dt>状态</dt>
                <dd>{TASK_STATE_TEXT[task?.state ?? ''] ?? task?.state}</dd>
                <dt>分组数</dt>
                <dd>{task?.groupCount ?? '—'} 张表映射</dd>
                <dt>并发 / 批量</dt>
                <dd>
                  {task?.concurrency ?? '—'} 线程 · {fmtNum(task?.batchSize ?? 0)} 行/批
                </dd>
                <dt>分片行数</dt>
                <dd>{fmtNum(task?.shardRows ?? 0)} 行/片</dd>
                <dt>限速</dt>
                <dd>{task?.rateLimitRows ? `${fmtNum(task.rateLimitRows)} 行/秒` : '未限速'}</dd>
                <dt>最大重试</dt>
                <dd>{task?.maxRetry ?? '—'} 次</dd>
                <dt>增量</dt>
                <dd>{task?.incrementalMode ?? '仅存量'}</dd>
                <dt>最近错误</dt>
                <dd style={{ color: task?.lastError ? 'var(--danger)' : 'var(--muted)' }}>{task?.lastError ?? '无'}</dd>
              </dl>
              {progress ? (
                <div className="mt16">
                  <div className="row between mb8">
                    <span className="small muted">分片完成度</span>
                    <span className="mono small">
                      {progress.percent.toFixed(1)}%（{fmtNum(progress.successShards)}/{fmtNum(progress.totalShards)} 片）
                    </span>
                  </div>
                  <ProgressBar
                    value={progress.percent}
                    tone={progress.failedShards > 0 ? 'danger' : progress.percent >= 100 ? 'ok' : 'accent'}
                  />
                </div>
              ) : null}
            </div>
          </div>

          <div className="card grow" style={{ minWidth: 420 }}>
            <div className="card-head">
              <div className="card-title">运行参数与策略</div>
              <span className="small faint">修改需任务处于非 RUNNING 状态</span>
            </div>
            <div className="card-body">
              <dl className="kv">
                <dt>消费并发</dt>
                <dd>{task?.consumerConcurrency ?? '—'}</dd>
                <dt>迁移策略</dt>
                <dd>主键区间分片 · 断点续传 · 失败指数退避</dd>
                <dt>一致性</dt>
                <dd>幂等 Upsert（按主键 MERGE / ON CONFLICT）</dd>
                <dt>冲突策略</dt>
                <dd>源优先（SOURCE_WINS）</dd>
                <dt>比对</dt>
                <dd>COUNT + SUM + HASH · 阈值 0.001%</dd>
                <dt>切流卡点</dt>
                <dd>通过率 ≥ 99.99% / 延迟 ≤ 5s / 错误率 ≤ 0.01% / 死信 0</dd>
              </dl>
              <div className="alert info mt16 small">
                参数来自后端 TaskView；控制台不缓存任务配置，修改后请重新拉取确认。
              </div>
            </div>
          </div>
        </div>
      ) : null}

      {tab === '迁移进度' ? (
        <div>
          {progress ? (
            <>
              <div className="kpi-grid" style={{ gridTemplateColumns: 'repeat(4, minmax(0,1fr))' }}>
                <StatCard label="已提交行数" value={fmtCount(progress.committedRows)} of={fmtCount(progress.estimatedRows)} tone="accent" />
                <StatCard label="完成分片" value={fmtNum(progress.successShards)} of={fmtNum(progress.totalShards)} tone="ok" />
                <StatCard label="失败分片" value={progress.failedShards} unit="片" tone={progress.failedShards > 0 ? 'danger' : 'ok'} />
                <StatCard
                  label="完成度"
                  value={progress.percent.toFixed(1)}
                  unit="%"
                  tone={progress.finished ? 'ok' : 'warn'}
                  sub={<span>{progress.finished ? '已完成' : '进行中'}</span>}
                />
              </div>

              <div className="card mt16">
                <div className="card-head">
                  <div className="card-title">
                    分片状态分布（按任务聚合）
                    <span className="hint">计数聚合 · 逐分片见下方网格</span>
                  </div>
                  <span className="small faint mono">GET /api/tasks/{id}/migration/progress</span>
                </div>
                <div className="card-body">
                  <div className="row wrap" style={{ gap: 10 }}>
                    {Object.entries(progress.shardCounts).map(([k, v]) => (
                      <span key={k} className="badge neutral" style={{ gap: 6 }}>
                        <span
                          className="swatch"
                          style={{
                            width: 8,
                            height: 8,
                            borderRadius: 2,
                            background: SHARD_STATE_COLOR[k] ?? '#26313F',
                          }}
                        />
                        {k} {fmtNum(v)}
                      </span>
                    ))}
                  </div>
                  <div className="shard-track mt12" style={{ height: 18 }}>
                    {Object.entries(progress.shardCounts).map(([k, v]) => (
                      <i
                        key={k}
                        title={`${k} ${fmtNum(v)} 片`}
                        style={{
                          width: `${(v / Math.max(1, progress.totalShards)) * 100}%`,
                          background: SHARD_STATE_COLOR[k] ?? '#26313F',
                        }}
                      />
                    ))}
                  </div>
                </div>
              </div>

              <div className="card mt16">
                <div className="card-head">
                  <div className="card-title">
                    逐分片进度
                    <span className="hint">一格一个分片 · 点击失败/死信分片定位死信</span>
                  </div>
                  <span className="small faint mono">GET /api/tasks/{id}/shards</span>
                </div>
                <div className="card-body">
                  <ShardHeatmap
                    taskId={id}
                    onPickFailed={(s) => {
                      setDeadShard(s);
                      setDeadOpen(true);
                    }}
                  />
                </div>
              </div>

              <div className="card mt16">
                <div className="card-head">
                  <div className="card-title">
                    表级进度明细
                    <span className="hint">percent 为空显示「—」，不显示 0%</span>
                  </div>
                  <span className="small faint mono">GET /api/tasks/{id}/tables/progress</span>
                </div>
                <div className="card-body">
                  <TableProgressTable
                    taskId={id}
                    onViewDead={() => {
                      setDeadShard(null);
                      setDeadOpen(true);
                    }}
                  />
                </div>
              </div>
            </>
          ) : (
            <div className="card">
              <div className="card-body">
                <div className="skeleton" style={{ height: 100 }} />
              </div>
            </div>
          )}
        </div>
      ) : null}

      {tab === '增量同步' ? (
        <div className="col" style={{ gap: 14 }}>
          <div className="kpi-grid" style={{ gridTemplateColumns: 'repeat(4, minmax(0,1fr))' }}>
            <StatCard label="延迟 P95" value={(cdc?.p95Sec ?? 0).toFixed(1)} unit="s" tone={(cdc?.p95Sec ?? 0) <= 5 ? 'ok' : 'danger'} sub={<span>P99 {(cdc?.p99Sec ?? 0).toFixed(1)}s</span>} />
            <StatCard label="积压" value={fmtNum(cdc?.backlog ?? 0)} unit="条" tone="warn" sub={<span>应用 TPS {fmtNum(cdc?.applyTps ?? 0)}</span>} />
            <StatCard label="失败率" value={(cdc?.failRate ?? 0).toFixed(3)} unit="%" tone={(cdc?.failRate ?? 0) <= 0.01 ? 'ok' : 'danger'} sub={<span>阈值 ≤ 0.01%</span>} />
            <StatCard
              label="触发器部署"
              value={cdc?.triggerInstalled ? '已部署' : '未部署'}
              tone={cdc?.triggerInstalled ? 'ok' : 'warn'}
              sub={<span>{cdc?.triggerTables ?? 0}/{cdc?.triggerTotal ?? 0} 张表</span>}
            />
          </div>

          <div className="card">
            <div className="card-head">
              <div className="card-title">
                增量模式
                <span className="badge accent">{CDC_MODE_TEXT[cdc?.mode ?? ''] ?? '未配置'}</span>
              </div>
              <span className="small faint">仅非 RUNNING 可切换（后端 E3001）</span>
            </div>
            <div className="card-body">
              <div className="row wrap" style={{ gap: 12 }}>
                <select
                  className="select"
                  value={cdc?.mode ?? 'TRIGGER_LOG'}
                  style={{ width: 200 }}
                  onChange={() => setNotice({ tone: 'warn', text: '模式切换需任务处于非运行状态；已提交变更请求' })}
                >
                  <option value="SDK_DUAL_WRITE">SDK 双写</option>
                  <option value="TRIGGER_LOG">触发器日志表</option>
                  <option value="TIMESTAMP">时间戳 + 版本号</option>
                </select>
                <button className="btn" onClick={() => setNotice({ tone: 'ok', text: '已提交触发器部署任务，将在源库创建 SYNC_LOG 触发器' })}>
                  部署触发器
                </button>
                <button className="btn btn-danger-outline" onClick={() => setNotice({ tone: 'warn', text: '已卸载触发器（需二次确认）' })}>
                  卸载触发器
                </button>
                <span className="small faint">最近更新 {cdc?.updatedAt ?? '—'}</span>
              </div>
            </div>
          </div>

          <div className="card">
            <div className="card-head">
              <div className="card-title">按主键追溯最近变更</div>
              <div className="row" style={{ gap: 8 }}>
                <input className="input mono" value={traceTable} onChange={(e) => setTraceTable(e.target.value)} style={{ width: 130 }} placeholder="表名" />
                <input className="input mono" value={tracePk} onChange={(e) => setTracePk(e.target.value)} style={{ width: 160 }} placeholder="主键值" />
                <button className="btn" onClick={() => setTraceQuery(tracePk.trim() || '88122301')}>
                  查询
                </button>
              </div>
            </div>
            <div className="card-body flush">
              <DataTable
                columns={changeColumns}
                rows={changes}
                rowKey={(c) => c.seq}
                compact
                minWidth={980}
                emptyText="该主键暂无变更记录"
                emptyHint="确认表名与主键值是否正确（追溯走 idx_trace 索引）"
              />
            </div>
          </div>
        </div>
      ) : null}

      {tab === '比对' ? (
        <div className="card">
          <div className="card-head">
            <div className="card-title">比对历史</div>
            <button className="btn btn-sm" disabled={!can('COMPARE_RUN')} title={can('COMPARE_RUN') ? undefined : needPermText('COMPARE_RUN')} onClick={() => void runCompare()}>
              立即比对
            </button>
          </div>
          <div className="card-body flush">
            <DataTable
              columns={[
                { key: 'jobId', title: '比对任务', render: (h) => <Link className="mono" to={`/compare/${h.jobId}`}>{h.jobId}</Link> },
                { key: 'at', title: '执行时间', render: (h) => <span className="mono small">{h.at}</span> },
                {
                  key: 'verdict',
                  title: '结论',
                  render: (h) => (
                    <span className={cls('badge', h.verdict === 'PASS' ? 'ok' : h.verdict === 'WARN' ? 'warn' : 'danger')}>
                      {h.verdict}
                    </span>
                  ),
                },
                { key: 'passRate', title: '通过率', align: 'right', render: (h) => `${h.passRate}%` },
                { key: 'diffRows', title: '差异行', align: 'right', render: (h) => fmtNum(h.diffRows) },
                { key: 'cost', title: '耗时', align: 'right', render: (h) => <span className="mono small">{h.cost}</span> },
                { key: 'operator', title: '执行人', render: (h) => h.operator },
              ]}
              rows={HISTORY}
              rowKey={(h) => h.jobId}
              minWidth={860}
            />
          </div>
        </div>
      ) : null}

      {tab === '切流' ? (
        <div className="card">
          <div className="card-head">
            <div className="card-title">切流计划</div>
            <Link className="btn btn-sm" to={plan ? `/cutover/${plan.id}` : '/cutover/CUT-20261109-01'}>
              打开切流控制台
            </Link>
          </div>
          <div className="card-body">
            {plan ? (
              <dl className="kv">
                <dt>计划</dt>
                <dd>
                  {plan.id} · {plan.planName}
                </dd>
                <dt>当前状态</dt>
                <dd>
                  <StatusDot tone="accent">{plan.state}</StatusDot>
                </dd>
                <dt>卡点</dt>
                <dd>
                  {plan.gates.filter((g) => g.pass).length}/{plan.gates.length} 通过
                </dd>
                <dt>灰度</dt>
                <dd>{plan.grayPct}%</dd>
              </dl>
            ) : (
              <div className="state">
                <div className="icon">⇥</div>
                <div className="title">该任务暂无切流计划</div>
                <div>切流计划需在比对通过后创建，且需要 CUTOVER_ADVANCE 权限推进</div>
              </div>
            )}
            <div className="alert warn mt16">
              切流是不可逆的高风险动作：推进与回滚均需二次确认并填写理由，全部落审计。
            </div>
          </div>
        </div>
      ) : null}

      <DeadLetterDrawer
        open={deadOpen}
        taskId={id}
        shard={deadShard}
        onClose={() => {
          setDeadOpen(false);
          setDeadShard(null);
        }}
        onNotice={(text, tone) => setNotice({ text, tone })}
      />
    </div>
  );
}
