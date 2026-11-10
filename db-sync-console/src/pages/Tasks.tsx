import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { EP } from '../api/endpoints';
import { http } from '../api/client';
import { needPermText, useCan } from '../api/auth';
import { errorText } from '../api/errors';
import { stateTone, TASK_STATE_TEXT, toTaskRow } from '../api/adapters';
import type { DataSource, MigrationActionView, MigrationProgressView, TaskRow, TaskView } from '../types';
import { DataTable, type Column } from '../components/DataTable';
import { ProgressBar } from '../components/ProgressBar';
import { StatusDot } from '../components/StatusDot';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { cls, fmtCount } from '../utils/format';

const DB_ICON: Record<string, string> = {
  MySQL: 'MY',
  Oracle: 'OR',
  PostgreSQL: 'PG',
  DM8: 'DM',
  DB2: 'DB',
};

export function DbTag({ type }: { type: string }) {
  return (
    <span className="row" style={{ gap: 5 }}>
      <span
        className="mono"
        style={{
          fontSize: 9.5,
          fontWeight: 700,
          letterSpacing: 0.5,
          padding: '1px 4px',
          borderRadius: 3,
          border: '1px solid var(--line-strong)',
          background: 'var(--bg-elevated)',
          color: 'var(--muted)',
        }}
      >
        {DB_ICON[type] ?? type.slice(0, 2).toUpperCase()}
      </span>
      <span className="small">{type}</span>
    </span>
  );
}

export function Tasks() {
  const navigate = useNavigate();
  const can = useCan();
  const [rows, setRows] = useState<TaskRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [state, setState] = useState('ALL');
  const [kw, setKw] = useState('');
  const [cancelTarget, setCancelTarget] = useState<TaskRow | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [ds, tvs] = await Promise.all([
        http.get<DataSource[]>(EP.dsList()),
        http.get<TaskView[]>(EP.taskList()),
      ]);
      const need = tvs.filter((t) => ['RUNNING', 'PAUSED', 'FAILED', 'FINISHED'].includes(t.state));
      const progresses = await Promise.all(
        need.map((t) =>
          http.get<MigrationProgressView>(EP.migrationProgress(t.id)).catch(() => null as MigrationProgressView | null),
        ),
      );
      const map = new Map<string, MigrationProgressView>();
      need.forEach((t, i) => {
        const p = progresses[i];
        if (p) map.set(t.id, p);
      });
      setRows(tvs.map((t) => toTaskRow(t, ds, map.get(t.id) ?? null)));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const filtered = useMemo(
    () =>
      rows.filter((t) => {
        if (state !== 'ALL' && t.state !== state) return false;
        if (kw.trim() && !`${t.name} ${t.sourceName} ${t.targetName}`.toLowerCase().includes(kw.trim().toLowerCase()))
          return false;
        return true;
      }),
    [rows, state, kw],
  );

  async function act(task: TaskRow, action: 'PAUSE' | 'RESUME' | 'CANCEL'): Promise<void> {
    setPending(true);
    try {
      const res = await http.post<MigrationActionView>(EP.taskState(task.id), { action });
      setRows((prev) => prev.map((t) => (t.id === task.id ? { ...t, state: res.taskState } : t)));
      setNotice(
        res.applied
          ? `${task.name}：${action === 'PAUSE' ? '已暂停' : action === 'RESUME' ? '已恢复' : '已取消'}（状态 ${res.taskState}）`
          : `${task.name}：已处于 ${TASK_STATE_TEXT[res.taskState] ?? res.taskState}，本次操作未重复执行（applied=false）`,
      );
      setCancelTarget(null);
    } catch (err) {
      setNotice(errorText(err));
    } finally {
      setPending(false);
    }
  }

  const columns: Column<TaskRow>[] = [
    {
      key: 'name',
      title: '任务名',
      sortable: true,
      render: (t) => (
        <div>
          <div className="cell-strong">{t.name}</div>
          <div className="small faint mono">
            {t.id} · 分组 {t.groups} · 并发 {t.concurrency} · 批量 {t.batchSize}
          </div>
        </div>
      ),
    },
    {
      key: 'link',
      title: '源 → 目标',
      render: (t) => (
        <div className="row" style={{ gap: 8 }}>
          <DbTag type={t.sourceType} />
          <span className="mono faint">→</span>
          <DbTag type={t.targetType} />
        </div>
      ),
    },
    {
      key: 'mode',
      title: '增量模式',
      render: (t) =>
        t.incrementalMode ? (
          <span className="badge accent mono">{t.incrementalMode}</span>
        ) : (
          <span className="badge neutral">仅存量</span>
        ),
    },
    {
      key: 'state',
      title: '状态',
      sortable: true,
      render: (t) => (
        <div>
          <StatusDot tone={stateTone(t.state)} pulse={t.state === 'RUNNING'}>
            {TASK_STATE_TEXT[t.state] ?? t.state}
          </StatusDot>
          {t.lastError ? (
            <div className="small" style={{ color: 'var(--danger)', maxWidth: 240, whiteSpace: 'normal' }}>
              {t.lastError}
            </div>
          ) : null}
        </div>
      ),
    },
    {
      key: 'progress',
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
              {fmtCount(t.progress.committedRows)} / {fmtCount(t.progress.estimatedRows)} 行
            </div>
          </div>
        ) : (
          <span className="faint">—</span>
        ),
    },
    {
      key: 'limit',
      title: '限速',
      align: 'right',
      render: (t) =>
        t.rateLimitRows ? <span className="mono small">{fmtCount(t.rateLimitRows)}/s</span> : <span className="faint">—</span>,
    },
    {
      key: 'op',
      title: '操作',
      render: (t) => (
        <div className="row" style={{ gap: 10 }}>
          <button className="btn-link" onClick={() => navigate(`/tasks/${t.id}`)}>
            查看
          </button>
          {t.state === 'RUNNING' ? (
            <button
              className="btn-link"
              disabled={!can('TASK_EXEC')}
              title={can('TASK_EXEC') ? undefined : needPermText('TASK_EXEC')}
              onClick={() => void act(t, 'PAUSE')}
            >
              暂停
            </button>
          ) : null}
          {t.state === 'PAUSED' ? (
            <button
              className="btn-link"
              disabled={!can('TASK_EXEC')}
              title={can('TASK_EXEC') ? undefined : needPermText('TASK_EXEC')}
              onClick={() => void act(t, 'RESUME')}
            >
              继续
            </button>
          ) : null}
          {['RUNNING', 'PAUSED', 'READY', 'FAILED'].includes(t.state) ? (
            <button
              className="btn-link danger"
              disabled={!can('TASK_EXEC')}
              title={can('TASK_EXEC') ? undefined : needPermText('TASK_EXEC')}
              onClick={() => setCancelTarget(t)}
            >
              取消
            </button>
          ) : null}
        </div>
      ),
    },
  ];

  return (
    <div>
      <div className="page-head">
        <div className="page-title">
          <h1>同步任务</h1>
          <span className="page-sub">存量迁移 / 增量双写 / 比对与切流的编排单元</span>
        </div>
        <div className="page-actions">
          <button
            className="btn btn-primary"
            disabled={!can('TASK_EDIT')}
            title={can('TASK_EDIT') ? undefined : needPermText('TASK_EDIT')}
            onClick={() => navigate('/tasks/new')}
          >
            + 新建任务
          </button>
        </div>
      </div>

      {notice ? (
        <div className="alert ok mb12">
          <span>{notice}</span>
          <button className="btn-link" style={{ marginLeft: 'auto' }} onClick={() => setNotice(null)}>
            关闭
          </button>
        </div>
      ) : null}

      <div className="card">
        <div className="filter-bar">
          <span className="filter-label">筛选</span>
          <select className="select" value={state} onChange={(e) => setState(e.target.value)} style={{ width: 130 }}>
            <option value="ALL">全部状态</option>
            {Object.entries(TASK_STATE_TEXT).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
          <input
            className="input"
            placeholder="搜索任务名 / 数据源"
            value={kw}
            onChange={(e) => setKw(e.target.value)}
            style={{ width: 240 }}
          />
          <button className="btn btn-sm" onClick={() => void load()}>
            刷新
          </button>
          <span className="small faint" style={{ marginLeft: 'auto' }}>
            共 {filtered.length} 个任务
          </span>
        </div>
        <DataTable
          columns={columns}
          rows={filtered}
          rowKey={(t) => t.id}
          loading={loading}
          onRowClick={(t) => navigate(`/tasks/${t.id}`)}
          minWidth={1120}
          emptyText="暂无同步任务"
          emptyHint="点击右上角新建任务，通过 4 步向导完成配置"
        />
      </div>

      <div className={cls('mt12', 'small', 'faint')}>
        进度与行数来自 <span className="mono">/api/tasks/{'{id}'}/migration/progress</span>（仅对已启动任务按需拉取）；
        状态跃迁统一走 <span className="mono">POST /api/tasks/{'{id}'}/state</span>，applied=false 表示该动作已处于目标态。
      </div>

      <ConfirmDialog
        open={cancelTarget !== null}
        title="取消任务"
        confirmText={cancelTarget?.id ?? ''}
        confirmLabel="确认取消"
        pending={pending}
        onCancel={() => setCancelTarget(null)}
        onConfirm={() => {
          if (cancelTarget) void act(cancelTarget, 'CANCEL');
        }}
      >
        <div className="alert danger">
          取消后任务进入 CANCELLED，正在运行的分片 ≤ 10 秒内停止提交，已提交数据保留；如需继续需重新启动（可选择断点续跑）。
        </div>
      </ConfirmDialog>
    </div>
  );
}
