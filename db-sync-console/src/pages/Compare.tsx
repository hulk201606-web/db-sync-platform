import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { EP } from '../api/endpoints';
import { downloadFile, http } from '../api/client';
import { useQuery } from '../api/useQuery';
import { needPermText, useCan } from '../api/auth';
import { adaptDiffPage, adaptReport } from '../api/adapters';
import type { DiffRow } from '../types';
import { DataTable, type Column } from '../components/DataTable';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { cls, DIFF_TYPE_TEXT, fmtNum } from '../utils/format';

const TYPE_OPTIONS = [
  { value: 'ALL', label: '全部类型' },
  { value: 'VALUE_DIFF', label: '值不同' },
  { value: 'ONLY_SOURCE', label: '仅源有' },
  { value: 'ONLY_TARGET', label: '仅目标有' },
];

export function Compare() {
  const { jobId = 'CMP-20261109-0007' } = useParams();
  const can = useCan();
  const [page, setPage] = useState(1);
  const [type, setType] = useState('ALL');
  const [onlyDiff, setOnlyDiff] = useState(false);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'warn' | 'info'; text: string } | null>(null);
  const [fixOpen, setFixOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const size = 20;

  const reportQ = useQuery(`report:${jobId}`, () => http.get<unknown>(EP.compareReport(jobId)).then(adaptReport));
  const diffsQ = useQuery(`diffs:${jobId}:${page}:${type}`, () =>
    http.get<unknown>(EP.compareDiffs(jobId, page, size, type)).then((raw) => adaptDiffPage(raw, page, size)),
  );

  const report = reportQ.data;
  const pageData = diffsQ.data;
  const rows = pageData?.items ?? [];
  const total = pageData?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / size));

  const visible = useMemo(() => (onlyDiff ? rows.filter((r) => r.type === 'VALUE_DIFF') : rows), [rows, onlyDiff]);

  const maxDist = Math.max(1, ...(report?.distribution.map((d) => d.rows) ?? [1]));

  async function replay(): Promise<void> {
    setPending(true);
    try {
      await http.post(EP.compareReplay(jobId), { tables: ['T_LOG', 'T_ACCT'], onlyDiff: true });
      setNotice({ tone: 'ok', text: `已提交重放：5,846 行将 Upsert 到目标库，修复前自动备份目标行；完成后自动重比对` });
    } finally {
      setPending(false);
    }
  }

  async function fixSource(payload: { confirmText: string; reason: string }): Promise<void> {
    setPending(true);
    try {
      await http.post(EP.compareFixSource(jobId), { reason: payload.reason, confirmText: payload.confirmText });
      setFixOpen(false);
      setNotice({
        tone: 'warn',
        text: '反向修复已提交（ADMIN 双人复核）：将把目标端修正值写回源库，备份表 diff_backup_20261109 可回滚',
      });
    } finally {
      setPending(false);
    }
  }

  const columns: Column<DiffRow>[] = [
    { key: 'table', title: '表', render: (d) => <span className="mono small">{d.table}</span> },
    { key: 'pk', title: '主键', render: (d) => <span className="mono">{d.pk}</span> },
    {
      key: 'type',
      title: '差异类型',
      render: (d) => (
        <span className={cls('badge', d.type === 'VALUE_DIFF' ? 'warn' : d.type === 'ONLY_SOURCE' ? 'danger' : 'info')}>
          {DIFF_TYPE_TEXT[d.type]}
        </span>
      ),
    },
    { key: 'column', title: '差异列', render: (d) => <span className="mono small">{d.column ?? <span className="faint">整行</span>}</span> },
    {
      key: 'src',
      title: '源值',
      render: (d) => <span className="mono small" style={{ color: 'var(--text-dim)' }}>{d.sourceValue ?? <span className="faint">-</span>}</span>,
    },
    {
      key: 'tgt',
      title: '目标值',
      render: (d) => (
        <span className="mono small" style={{ color: d.targetValue === null ? 'var(--faint)' : 'var(--warn)' }}>
          {d.targetValue ?? '-'}
        </span>
      ),
    },
    { key: 'block', title: '差异块', render: (d) => <span className="mono small faint">{d.blockId}</span> },
    {
      key: 'op',
      title: '操作',
      render: (d) => (
        <button className="btn-link" onClick={() => setNotice({ tone: 'info', text: `已提交单行重放：${d.table} 主键 ${d.pk}` })}>
          {d.type === 'ONLY_TARGET' ? '反向修复' : '重放'}
        </button>
      ),
    },
  ];

  return (
    <div>
      <div className="page-head">
        <div className="page-title">
          <h1>比对报告</h1>
          <span className="page-sub mono">{jobId}</span>
        </div>
        <div className="page-actions">
          <button className="btn" onClick={() => void downloadFile(EP.compareExport(jobId), `compare-${jobId}.csv`)}>
            导出 CSV
          </button>
          <button
            className="btn btn-primary"
            onClick={() => void replay()}
            disabled={pending || !can('COMPARE_RUN')}
            title={can('COMPARE_RUN') ? undefined : needPermText('COMPARE_RUN')}
          >
            重放到目标（{fmtNum(report?.diffRows ?? 0)}）
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

      {!report ? (
        <div className="card">
          <div className="card-body">
            <div className="skeleton" style={{ height: 22, width: '40%' }} />
            <div className="skeleton" style={{ height: 12, width: '70%', marginTop: 12 }} />
            <div className="skeleton" style={{ height: 12, width: '55%', marginTop: 8 }} />
          </div>
        </div>
      ) : (
        <>
          <div className="card">
            <div className="card-body">
              <div className="row wrap" style={{ gap: 18, alignItems: 'center' }}>
                <span className={cls('verdict', report.verdict.toLowerCase())}>
                  {report.verdict === 'PASS' ? '✓ PASS' : report.verdict === 'WARN' ? '⚠ WARN' : '✕ FAIL'}
                </span>
                <div>
                  <div className="small muted">通过率</div>
                  <div className="row" style={{ gap: 10, alignItems: 'baseline' }}>
                    <span className="mono" style={{ fontSize: 26, fontWeight: 600, lineHeight: 1.2 }}>
                      {report.passRate}%
                    </span>
                    <span className="small faint">阈值 PASS &lt; {report.threshold}% 差异</span>
                  </div>
                  <div className="small faint mt8">
                    {report.taskName} · 算法 {report.algorithm} · 开始于 {report.startedAt}
                  </div>
                </div>
                <div className="spacer" />
                <div className="small muted" style={{ textAlign: 'right', lineHeight: 1.9 }}>
                  <div>
                    比对行数 <span className="mono dim">{fmtNum(report.comparedRows)}</span>
                  </div>
                  <div>
                    差异块 <span className="mono" style={{ color: 'var(--warn)' }}>{report.diffBlocks}</span> / {fmtNum(report.totalBlocks)}
                  </div>
                  <div>
                    差异行 <span className="mono" style={{ color: 'var(--danger)' }}>{fmtNum(report.diffRows)}</span>
                  </div>
                </div>
              </div>

              <div className="divider" />

              <div className="row wrap" style={{ gap: 26 }}>
                <div>
                  <div className="small faint">耗时</div>
                  <div className="mono dim">{report.costText}</div>
                </div>
                <div>
                  <div className="small faint">执行人</div>
                  <div className="mono dim">{report.operator}</div>
                </div>
                <div>
                  <div className="small faint">源端快照</div>
                  <div className="mono dim">{report.sourceSnapshotAt}</div>
                </div>
                <div>
                  <div className="small faint">目标端快照</div>
                  <div className="mono dim">{report.targetSnapshotAt}</div>
                </div>
                <div>
                  <div className="small faint">差异构成</div>
                  <div className="mono dim small">
                    值不同 {fmtNum(report.diffByType.VALUE_DIFF)} · 仅源有 {report.diffByType.ONLY_SOURCE} · 仅目标有{' '}
                    {report.diffByType.ONLY_TARGET}
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div className="row mt16 wrap" style={{ gap: 14, alignItems: 'stretch' }}>
            <div className="card grow" style={{ minWidth: 380 }}>
              <div className="card-head">
                <div className="card-title">差异分布 Top 表</div>
                <span className="small faint">按差异行数降序</span>
              </div>
              <div className="card-body">
                {report.distribution.map((d) => (
                  <div className="dist-row" key={d.table}>
                    <span className="mono small">{d.table}</span>
                    <div className="dist-bar">
                      <i style={{ width: `${(d.rows / maxDist) * 100}%` }} />
                    </div>
                    <span className="mono small" style={{ textAlign: 'right' }}>
                      {fmtNum(d.rows)}
                    </span>
                  </div>
                ))}
                <div className="small faint mt12">
                  差异集中在 T_LOG 大字段列 MEMO（5,102 行），疑似字符集截断；T_ACCT 主要为 UPD_TIME 毫秒级差异。
                </div>
              </div>
            </div>

            <div className="card grow" style={{ minWidth: 380 }}>
              <div className="card-head">
                <div className="card-title">忽略规则（报告中显式列出）</div>
                <span className="small faint">不得静默忽略</span>
              </div>
              <div className="card-body">
                <table className="tbl compact">
                  <tbody>
                    {report.ignoreRules.map((r) => (
                      <tr key={r.name}>
                        <td style={{ width: 90 }} className="dim">
                          {r.name}
                        </td>
                        <td className="mono small">{r.detail}</td>
                        <td style={{ width: 70 }} className="center">
                          <span className={cls('badge', r.enabled ? 'ok' : 'neutral')}>{r.enabled ? '已启用' : '关闭'}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="alert info mt12 small">
                  忽略规则由任务配置继承而来；本报告未对“大小写”做忽略，因此 MEMO 列的大小写变化仍计入差异。
                </div>
              </div>
            </div>
          </div>

          <div className="card mt16">
            <div className="card-head">
              <div className="card-title">
                差异明细
                <span className="hint">共 {fmtNum(total)} 行 · 第 {page}/{pages} 页</span>
              </div>
              <div className="row" style={{ gap: 8 }}>
                <select
                  className="select"
                  value={type}
                  onChange={(e) => {
                    setType(e.target.value);
                    setPage(1);
                  }}
                  style={{ width: 130 }}
                >
                  {TYPE_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
                <label className="check">
                  <input type="checkbox" checked={onlyDiff} onChange={(e) => setOnlyDiff(e.target.checked)} />
                  仅看差异
                </label>
                <button className="btn btn-sm" onClick={() => void downloadFile(EP.compareExport(jobId), `diff-${jobId}.csv`)}>
                  导出 CSV
                </button>
              </div>
            </div>
            <div className="card-body flush">
              <DataTable
                columns={columns}
                rows={visible}
                rowKey={(d) => String(d.id)}
                loading={diffsQ.loading}
                compact
                minWidth={1120}
                emptyText={onlyDiff ? '当前页没有列级差异行' : '当前筛选条件下无差异'}
              />
            </div>
            <div className="card-body row between" style={{ borderTop: '1px solid var(--line)' }}>
              <span className="small faint">
                每页 {size} 行 · 数据源：差异块下钻结果（源值 / 目标值并列，供研发定位）
              </span>
              <div className="row" style={{ gap: 6 }}>
                <button className="btn btn-sm" disabled={page <= 1} onClick={() => setPage((p) => Math.max(1, p - 1))}>
                  上一页
                </button>
                <span className="mono small muted" style={{ padding: '0 8px' }}>
                  {page} / {pages}
                </span>
                <button className="btn btn-sm" disabled={page >= pages} onClick={() => setPage((p) => Math.min(pages, p + 1))}>
                  下一页
                </button>
              </div>
            </div>
          </div>

          <div className="card mt16">
            <div className="card-head">
              <div className="card-title">修复动作</div>
              <span className="small faint">修复后自动重比对；反向修复需 ADMIN + 原因</span>
            </div>
            <div className="card-body">
              <div className="row wrap" style={{ gap: 10 }}>
                <button
                  className="btn btn-primary"
                  onClick={() => void replay()}
                  disabled={pending || !can('COMPARE_RUN')}
                  title={can('COMPARE_RUN') ? undefined : needPermText('COMPARE_RUN')}
                >
                  重放到目标（REPLAY）
                </button>
                <button
                  className="btn btn-danger-outline"
                  onClick={() => setFixOpen(true)}
                  disabled={!can('COMPARE_FIX_SOURCE')}
                  title={can('COMPARE_FIX_SOURCE') ? undefined : needPermText('COMPARE_FIX_SOURCE')}
                >
                  反向修复源端（需 ADMIN）
                </button>
                <label className="check" style={{ marginLeft: 8 }}>
                  <input type="checkbox" defaultChecked readOnly />
                  修复后自动重比对
                </label>
                <span className="small faint" style={{ marginLeft: 'auto' }}>
                  修复前自动备份目标行 → 备份表 diff_backup_20261109
                </span>
              </div>
              <div className="alert warn mt12">
                反向修复会把目标端数据写回 <b>生产源库</b>，属于最高风险操作：需 ADMIN 角色、填写原因（≥ 10 字）、
                并在弹窗内输入比对任务号二次确认，全部落审计。
              </div>
            </div>
          </div>
        </>
      )}

      <ConfirmDialog
        open={fixOpen}
        title="反向修复源端（FIX_TO_SOURCE）"
        confirmText={jobId}
        confirmLabel="确认反向修复"
        requireReason
        reasonMinLength={10}
        reasonLabel="修复原因（≥ {n} 字，落审计）"
        extraCheckbox="我已确认源库连接正常，且已通知业务方"
        pending={pending}
        onCancel={() => setFixOpen(false)}
        onConfirm={(p) => void fixSource(p)}
      >
        <div className="alert danger">
          将把目标端 <b className="mono">2</b> 行“仅目标有”的数据反向写入源库 <b>ora-core-prod（PROD）</b>，
          影响表 T_LOG / T_ACCT。修复前会备份目标行与源行，可在 24 小时内回滚。
        </div>
      </ConfirmDialog>
    </div>
  );
}
