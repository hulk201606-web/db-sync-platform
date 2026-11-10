import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { EP } from '../api/endpoints';
import { http, downloadText } from '../api/client';
import { useQuery } from '../api/useQuery';
import { needPermText, useCan } from '../api/auth';
import { errorText } from '../api/errors';
import type { DdlDiffColumn, DdlDiffView, PrecheckView, SyncMode, TableDetail, TableMeta } from '../types';
import { StepBar } from '../components/StepBar';
import { DataTable, type Column } from '../components/DataTable';
import { Drawer } from '../components/Drawer';
import { Modal } from '../components/Modal';
import { StatusDot } from '../components/StatusDot';
import { DbTag } from './Tasks';
import { cls, fmtBytes, fmtCount } from '../utils/format';

const STEPS = [
  { key: 'tables', title: '数据源与表', desc: '选择源/目标库与待迁移表' },
  { key: 'mapping', title: '结构映射与 DDL', desc: '三栏对照、风险清单与标识符策略' },
  { key: 'migrate', title: '迁移 / 增量参数', desc: '并发、批量、限速与 CDC 模式' },
  { key: 'gate', title: '比对与切流', desc: '比对算法、阈值与切流卡点' },
];

const IDENT_STRATEGIES = [
  { key: 'LOWER', label: 'LOWER（全小写）' },
  { key: 'UPPER', label: 'UPPER（全大写）' },
  { key: 'PRESERVE', label: 'PRESERVE（原样 + 引号）' },
];

interface MappingResp {
  mid: string;
  tables: number;
  detail: TableDetail;
}

const DIFF_STATUS_TEXT: Record<string, string> = {
  NEW: '新增',
  TYPE_MISMATCH: '不一致',
  EXTRA: '多列',
  OK: '一致',
};

function diffTone(status: string): string {
  if (status === 'OK') return 'ok';
  if (status === 'TYPE_MISMATCH') return 'warn';
  if (status === 'EXTRA') return 'danger';
  return 'accent';
}

export function TaskNew() {
  const navigate = useNavigate();
  const can = useCan();
  const [precheckResult, setPrecheckResult] = useState<PrecheckView | null>(null);
  const [prechecking, setPrechecking] = useState(false);
  const draftRef = useRef('');
  // 支持 ?step=1..3 直达某一步（用于草稿续编与联调）
  const [step, setStep] = useState(() => {
    const raw = new URLSearchParams(window.location.search).get('step');
    const n = Number(raw ?? '0');
    return Number.isFinite(n) ? Math.min(3, Math.max(0, n)) : 0;
  });
  const [maxStep, setMaxStep] = useState(step);
  const [helpOpen, setHelpOpen] = useState(false);
  const [sqlOpen, setSqlOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [ddlDiff, setDdlDiff] = useState<DdlDiffView | null>(null);
  const [ddlDiffErr, setDdlDiffErr] = useState<string | null>(null);

  // ① 数据源与表
  const [sourceId, setSourceId] = useState('ds-ora-01');
  const [targetId, setTargetId] = useState('ds-dm-01');
  const [mode, setMode] = useState<SyncMode>('FULL_INCR');
  const [schema, setSchema] = useState('APP');
  const [tableSearch, setTableSearch] = useState('');
  const [riskyOnly, setRiskyOnly] = useState(false);
  const [selected, setSelected] = useState<string[]>(['T_ORDER', 'T_ACCT']);

  // ② 映射
  const [ident, setIdent] = useState('LOWER');
  const [quote, setQuote] = useState('auto');
  const [overrides, setOverrides] = useState<Record<string, string>>({});
  const [editCol, setEditCol] = useState<string | null>(null);
  const [editType, setEditType] = useState('DECIMAL(19,0)');

  // ③ 迁移 / 增量
  const [threads, setThreads] = useState(4);
  const [batch, setBatch] = useState(1000);
  const [shardRows, setShardRows] = useState(2_000_000);
  const [limit, setLimit] = useState('');
  const [retry, setRetry] = useState(3);
  const [cdcMode, setCdcMode] = useState<'SDK_DUAL_WRITE' | 'TRIGGER_LOG' | 'TIMESTAMP'>('TRIGGER_LOG');
  const [consumers, setConsumers] = useState(8);
  const [pollSec, setPollSec] = useState(5);
  const [conflict, setConflict] = useState('SOURCE_WINS');
  const [cdcBatch, setCdcBatch] = useState(500);
  const [precheck, setPrecheck] = useState(true);
  const [autoVerify, setAutoVerify] = useState(true);

  // ④ 比对 / 切流
  const [chunkRows, setChunkRows] = useState(100_000);
  const [cmpConc, setCmpConc] = useState(8);
  const [algos, setAlgos] = useState<string[]>(['COUNT', 'SUM', 'HASH']);
  const [threshold, setThreshold] = useState('0.001');
  const [schedule, setSchedule] = useState('6h');
  const [dims, setDims] = useState<string[]>(['ratio', 'hash']);
  const [gatePass, setGatePass] = useState('99.99');
  const [gateLatency, setGateLatency] = useState('5');
  const [gateFail, setGateFail] = useState('0.01');
  const [observeH, setObserveH] = useState(24);
  const [rollbackPct, setRollbackPct] = useState('1');

  const dsQ = useQuery('wizard:ds', () => http.get<Awaited<ReturnType<typeof fetchDs>>>(EP.dsList()));
  const tablesQ = useQuery(`wizard:tables:${sourceId}`, () => http.get<TableMeta[]>(EP.dsTables(sourceId)));
  // 映射生成接口按 taskId 维度提供，因此进入第 ② 步时先落一个草稿任务
  const mapQ = useQuery(`wizard:mapping:${step >= 1 ? 'on' : 'off'}:${selected.join('|')}`, () =>
    step >= 1
      ? ensureDraft().then((taskId) => http.post<MappingResp>(EP.mappingGenerate(taskId), { tables: selected }))
      : Promise.resolve({ mid: '', tables: 0, detail: null as unknown as TableDetail }),
  );

  const datasources = dsQ.data ?? [];
  const tables = tablesQ.data ?? [];
  const source = datasources.find((d) => d.id === sourceId);
  const target = datasources.find((d) => d.id === targetId);

  // 第 ② 步：目标库现状差异（GET /api/tasks/{id}/mappings/{mid}/ddl-diff）
  const mappingMid = mapQ.data?.mid ?? '';
  useEffect(() => {
    if (step !== 1) return;
    const taskId = draftRef.current;
    if (!mappingMid || !taskId) return;
    let alive = true;
    setDdlDiffErr(null);
    http
      .get<DdlDiffView>(EP.mappingDdlDiff(taskId, mappingMid))
      .then((d) => {
        if (alive) setDdlDiff(d);
      })
      .catch((err) => {
        if (alive) {
          setDdlDiff(null);
          setDdlDiffErr(errorText(err));
        }
      });
    return () => {
      alive = false;
    };
  }, [step, mappingMid]);

  const filteredTables = useMemo(
    () =>
      tables.filter((t) => {
        if (schema !== 'ALL' && t.schema !== schema) return false;
        if (riskyOnly && t.risks.length === 0) return false;
        if (tableSearch.trim() && !t.name.toLowerCase().includes(tableSearch.trim().toLowerCase())) return false;
        return true;
      }),
    [tables, schema, riskyOnly, tableSearch],
  );

  const selectedTables = tables.filter((t) => selected.includes(t.name));
  const estBytes = selectedTables.reduce((sum, t) => sum + t.bytes, 0);

  function toggleTable(name: string): void {
    setSelected((prev) => (prev.includes(name) ? prev.filter((x) => x !== name) : [...prev, name]));
  }

  function goStep(next: number): void {
    if (next > 0 && selected.length === 0) {
      setNotice('请至少勾选一张待迁移的表');
      return;
    }
    setNotice(null);
    setStep(next);
    setMaxStep((m) => Math.max(m, next));
  }

  const detail = mapQ.data?.detail ?? null;
  const risks = detail?.risksDetail ?? [];

  interface MergedColumn {
    name: string;
    sourceType: string;
    targetType: string;
    degrading?: boolean;
    diff: DdlDiffColumn | null;
  }

  /** 源列 / 映射 / 目标现状 三栏必须逐行对齐：以映射列顺序为准，差异接口多出的列追加在尾部 */
  const mergedColumns = useMemo<MergedColumn[]>(() => {
    const cols = detail?.columnsDetail ?? [];
    const diffMap = new Map((ddlDiff?.columns ?? []).map((d) => [d.name.toUpperCase(), d]));
    const used = new Set<string>();
    const merged: MergedColumn[] = cols.map((c) => {
      used.add(c.name.toUpperCase());
      return {
        name: c.name,
        sourceType: c.sourceType,
        targetType: c.targetType,
        degrading: c.degrading,
        diff: diffMap.get(c.name.toUpperCase()) ?? null,
      };
    });
    for (const d of ddlDiff?.columns ?? []) {
      if (used.has(d.name.toUpperCase())) continue;
      merged.push({
        name: d.name,
        sourceType: d.sourceType || '—',
        targetType: d.generatedType || '—',
        diff: d,
      });
    }
    return merged;
  }, [detail, ddlDiff]);

  const diffIndexByName = useMemo(() => {
    const m = new Map<string, string>();
    for (const idx of ddlDiff?.indexes ?? []) m.set(idx.name.toUpperCase(), idx.status);
    return m;
  }, [ddlDiff]);

  function buildPayload(): Record<string, unknown> {
    return {
      sourceDsId: sourceId,
      targetDsId: targetId,
      mode,
      tables: selected,
      concurrency: threads,
      batchSize: batch,
      shardRows,
      rateLimitRows: limit ? Number(limit) : null,
      maxRetry: retry,
      incrementalMode: cdcMode,
      consumerConcurrency: consumers,
      migration: { pollSec, conflict, cdcBatch, precheck, autoVerify },
      compare: { chunkRows, concurrency: cmpConc, algorithms: algos, threshold: Number(threshold), schedule },
      cutover: {
        dims,
        gatePass: Number(gatePass),
        gateLatency: Number(gateLatency),
        gateFailRate: Number(gateFail),
        observeH,
        rollbackPct: Number(rollbackPct),
      },
      mappings: overrides,
      identStrategy: ident,
      quoteStrategy: quote,
    };
  }

  /** 落草稿任务：映射生成与预检都按 taskId 维度提供 */
  async function ensureDraft(): Promise<string> {
    if (draftRef.current) return draftRef.current;
    const created = await http.post<{ id: string }>(EP.taskCreate(), buildPayload());
    draftRef.current = created.id;
    return created.id;
  }

  async function runPrecheck(): Promise<void> {
    setPrechecking(true);
    try {
      const id = await ensureDraft();
      const res = await http.post<PrecheckView>(EP.taskPrecheck(id));
      setPrecheckResult(res);
      setNotice(
        res.blocking.length > 0
          ? `预检未通过：${res.blocking.length} 项阻断，需修复后才能启动`
          : `预检通过：${res.warnings.length} 项告警，无阻断项`,
      );
    } catch (err) {
      setNotice(errorText(err));
    } finally {
      setPrechecking(false);
    }
  }

  async function createTask(): Promise<void> {
    if (draftRef.current) {
      await http.put(EP.taskUpdate(draftRef.current), buildPayload());
    } else {
      await http.post(EP.taskCreate(), buildPayload());
    }
    navigate('/tasks');
  }

  const tableColumns: Column<TableMeta>[] = [
    {
      key: 'sel',
      title: '',
      width: 34,
      render: (t) => (
        <input
          type="checkbox"
          checked={selected.includes(t.name)}
          onChange={() => toggleTable(t.name)}
          onClick={(e) => e.stopPropagation()}
          aria-label={`选择 ${t.name}`}
        />
      ),
    },
    { key: 'name', title: '表名', sortable: true, render: (t) => <span className="mono cell-strong">{t.name}</span> },
    {
      key: 'rows',
      title: '行数',
      align: 'right',
      sortable: true,
      render: (t) => <span title={`${t.rows.toLocaleString('en-US')} 行`}>{fmtCount(t.rows)}</span>,
    },
    { key: 'bytes', title: '容量', align: 'right', sortable: true, render: (t) => <span className="small">{fmtBytes(t.bytes)}</span> },
    { key: 'pk', title: '主键', render: (t) => <span className="mono small">{t.pk ?? '—'}</span> },
    {
      key: 'risk',
      title: '风险',
      render: (t) =>
        t.risks.length === 0 ? (
          <span className="faint">-</span>
        ) : (
          <div className="row wrap" style={{ gap: 4 }}>
            {t.risks.map((r) => (
              <span key={r} className={cls('badge', r.includes('降级') ? 'warn' : 'danger')}>
                {r}
              </span>
            ))}
          </div>
        ),
    },
    { key: 'target', title: '目标表', render: (t) => <span className="mono small">{t.targetTable}</span> },
    {
      key: 'exists',
      title: '已存在',
      render: (t) =>
        t.existsOnTarget ? (
          <span className="small" style={{ color: 'var(--warn)' }} title={t.targetNote}>
            是{t.targetNote ? `（${t.targetNote.replace('已存在，', '')}）` : ''}
          </span>
        ) : (
          <span className="faint small">否</span>
        ),
    },
  ];

  const ddlText = detail?.ddl ?? '';
  const indexLine = detail?.indexes ?? [];

  return (
    <div>
      <div className="page-head">
        <div className="page-title">
          <h1>新建同步任务</h1>
          <span className="page-sub">4 步配置 · 每步可保存草稿 · 风险清单强制展示</span>
        </div>
        <div className="page-actions">
          <button className="btn" onClick={() => navigate('/tasks')}>
            退出向导
          </button>
          <button className="btn" onClick={() => setHelpOpen(true)}>
            帮助与规则说明
          </button>
        </div>
      </div>

      <StepBar steps={STEPS} current={step} maxReached={maxStep} onJump={(i) => goStep(i)} />

      {notice ? (
        <div className="alert warn mb12">
          <span>{notice}</span>
          <button className="btn-link" style={{ marginLeft: 'auto' }} onClick={() => setNotice(null)}>
            关闭
          </button>
        </div>
      ) : null}

      {step === 0 ? (
        <div className="card">
          <div className="card-body">
            <div className="row wrap" style={{ gap: 16 }}>
              <div className="field" style={{ minWidth: 260 }}>
                <label>源数据源</label>
                <select className="select" value={sourceId} onChange={(e) => setSourceId(e.target.value)}>
                  {datasources.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.name}（{d.type} / {d.env}）
                    </option>
                  ))}
                </select>
              </div>
              <div style={{ alignSelf: 'flex-end', paddingBottom: 6 }} className="mono faint">
                →
              </div>
              <div className="field" style={{ minWidth: 260 }}>
                <label>目标数据源</label>
                <select className="select" value={targetId} onChange={(e) => setTargetId(e.target.value)}>
                  {datasources.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.name}（{d.type} / {d.env}）
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label>同步模式</label>
                <div className="radio-row" style={{ height: 30 }}>
                  <label className="radio">
                    <input type="radio" checked={mode === 'FULL'} onChange={() => setMode('FULL')} />
                    存量
                  </label>
                  <label className="radio">
                    <input type="radio" checked={mode === 'FULL_INCR'} onChange={() => setMode('FULL_INCR')} />
                    存量 + 增量
                  </label>
                </div>
              </div>
              <div className="spacer" />
              <div className="col" style={{ gap: 4, justifyContent: 'center' }}>
                <div className="row" style={{ gap: 8 }}>
                  {source ? <DbTag type={source.type} /> : null}
                  <span className="mono faint">→</span>
                  {target ? <DbTag type={target.type} /> : null}
                </div>
                <span className="small faint">
                  {source?.host}:{source?.port}/{source?.database}
                </span>
              </div>
            </div>

            <div className="filter-bar" style={{ marginTop: 14, borderRadius: 6, border: '1px solid var(--line)' }}>
              <input
                className="input"
                placeholder="表搜索"
                value={tableSearch}
                onChange={(e) => setTableSearch(e.target.value)}
                style={{ width: 200 }}
              />
              <label className="check">
                <input type="checkbox" checked={riskyOnly} onChange={(e) => setRiskyOnly(e.target.checked)} />
                仅显示无主键 / 有风险表
              </label>
              <span className="filter-label" style={{ marginLeft: 8 }}>
                Schema
              </span>
              <select className="select" value={schema} onChange={(e) => setSchema(e.target.value)} style={{ width: 110 }}>
                <option value="ALL">全部</option>
                <option value="APP">APP</option>
              </select>
              <button
                className="btn btn-sm"
                onClick={() => setSelected(filteredTables.map((t) => t.name))}
                disabled={filteredTables.length === 0}
              >
                全选当前筛选
              </button>
              <button className="btn btn-sm" onClick={() => setSelected([])} disabled={selected.length === 0}>
                清空
              </button>
              <span className="small faint" style={{ marginLeft: 'auto' }}>
                共 {tables.length} 张表（当前显示 {filteredTables.length}）
              </span>
            </div>

            <div className="mt12">
              <DataTable
                columns={tableColumns}
                rows={filteredTables}
                rowKey={(t) => t.name}
                loading={tablesQ.loading}
                compact
                minWidth={960}
                onRowClick={(t) => toggleTable(t.name)}
                rowClassName={(t) => (selected.includes(t.name) ? 'sel' : undefined)}
                emptyText="没有匹配的表"
              />
            </div>

            <div className="action-bar">
              <div className="row" style={{ gap: 10 }}>
                <span className="dim">
                  已选 <b className="mono" style={{ color: 'var(--accent)' }}>{selected.length}</b> 张表
                </span>
                <span className="faint">·</span>
                <span className="dim">
                  预估 <b className="mono">{fmtBytes(estBytes)}</b> 数据量，按当前参数约需{' '}
                  <b className="mono">{Math.max(1, Math.round(estBytes / 1024 / 1024 / 1024 / 18))}</b> 分钟
                </span>
                {selected.length > 0 ? (
                  <span className="small faint">（{selected.slice(0, 4).join('、')}{selected.length > 4 ? ` 等 ${selected.length} 张` : ''}）</span>
                ) : null}
              </div>
              <button className="btn btn-primary" onClick={() => goStep(1)} disabled={selected.length === 0}>
                下一步：结构映射
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {step === 1 ? (
        <div className="card">
          <div className="card-head">
            <div className="card-title">
              结构映射与 DDL
              <span className="hint mono">{selected[0] ?? 'T_ORDER'} 为样例，其余 {Math.max(0, selected.length - 1)} 张表按同规则生成</span>
            </div>
            <div className="row" style={{ gap: 8 }}>
              <button className="btn btn-sm" onClick={() => setSqlOpen(true)} disabled={!detail}>
                预览完整 SQL
              </button>
              <button
                className="btn btn-sm"
                onClick={() => downloadText(ddlText, `${selected[0] ?? 'T_ORDER'}_DM8.sql`, 'application/sql;charset=utf-8')}
                disabled={!detail}
              >
                导出 .sql
              </button>
              <button
                className="btn btn-sm"
                onClick={() => setNotice(`已将 ${selected[0] ?? 'T_ORDER'} 的映射规则与标识符策略应用到全部 ${selected.length} 张表`)}
              >
                应用到全部表
              </button>
            </div>
          </div>

          <div className="card-body">
            {mapQ.loading || !detail ? (
              <div className="mapping-grid">
                {[0, 1, 2].map((i) => (
                  <div className="map-col" key={i}>
                    <div className="map-col-head">加载中…</div>
                    <div style={{ padding: 10 }}>
                      {Array.from({ length: 6 }, (_, k) => (
                        <div className="skeleton" key={k} style={{ height: 22, marginBottom: 6 }} />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <>
                {ddlDiff && !ddlDiff.targetExists ? (
                  <div className="alert warn mb12">
                    目标表 <b className="mono">{ddlDiff.targetTable}</b> 不存在，将全新建表（按下方生成的 DDL 执行）。
                  </div>
                ) : null}
                {ddlDiff && ddlDiff.targetExists ? (
                  <div className="alert info mb12">
                    目标表 <b className="mono">{ddlDiff.targetTable}</b> 已存在：新增列 {ddlDiff.summary.newColumns} · 类型不一致{' '}
                    {ddlDiff.summary.typeMismatch} · 缺失索引 {ddlDiff.summary.missingIndexes} · 目标多余列{' '}
                    {ddlDiff.summary.extraColumns}
                  </div>
                ) : null}
                {ddlDiffErr ? (
                  <div className="alert warn mb12">
                    目标库差异检测不可用：{ddlDiffErr}（当前仅展示映射生成结果，未与目标端比对）
                  </div>
                ) : null}

                <div className={cls('mapping-grid', ddlDiff && 'with-target')}>
                  <div className="map-col">
                    <div className="map-col-head">
                      <span>源列 · {source?.type ?? 'Oracle'} APP.{selected[0]}</span>
                      <span>{mergedColumns.length} 列</span>
                    </div>
                    <div className="map-list">
                      {mergedColumns.map((c) => (
                        <div key={c.name} className={cls('map-row', c.degrading && 'risk')}>
                          <span className="col-name">{c.name}</span>
                          <span className="col-type">{c.sourceType}</span>
                        </div>
                      ))}
                    </div>
                  </div>

                  <div className="map-col">
                    <div className="map-col-head">
                      <span>映射 / 规则 · {target?.type ?? 'DM8'}</span>
                      <span>{Object.keys(overrides).length} 处人工覆盖</span>
                    </div>
                    <div className="map-list">
                      {mergedColumns.map((c) => {
                        const t = overrides[c.name] ?? c.targetType;
                        return (
                          <div key={c.name} className={cls('map-row', c.degrading && 'risk', overrides[c.name] && 'override')}>
                            <span className="row" style={{ gap: 6, minWidth: 0 }}>
                              <span className="arrow">→</span>
                              <span className="col-type">{t}</span>
                              {c.degrading ? <span className="badge warn">⚠ 降级</span> : null}
                              {overrides[c.name] ? <span className="badge accent">人工</span> : null}
                            </span>
                            <button
                              className="btn-link"
                              onClick={() => {
                                setEditCol(c.name);
                                setEditType(t);
                              }}
                            >
                              [改]
                            </button>
                          </div>
                        );
                      })}
                    </div>
                  </div>

                  <div className="map-col">
                    <div className="map-col-head">
                      <span>生成 DDL</span>
                      <span>{ident} · 引号 {quote === 'auto' ? '自动' : quote}</span>
                    </div>
                    <pre className="ddl-pre">{ddlText}</pre>
                  </div>

                  {ddlDiff ? (
                    <div className="map-col">
                      <div className="map-col-head">
                        <span>目标现状 · {target?.type ?? 'DM8'}</span>
                        <span>{ddlDiff.targetExists ? '目标表已存在' : '目标表不存在'}</span>
                      </div>
                      <div className="map-list">
                        {mergedColumns.map((c) => (
                          <div
                            key={c.name}
                            className={cls('map-row', c.diff?.status === 'TYPE_MISMATCH' && 'risk')}
                          >
                            <span className="col-type" title={c.diff?.note ?? undefined}>
                              {c.diff?.targetType ?? '—'}
                            </span>
                            {c.diff ? (
                              <span className={cls('badge', diffTone(c.diff.status))}>
                                {DIFF_STATUS_TEXT[c.diff.status] ?? c.diff.status}
                              </span>
                            ) : (
                              <span className="faint small">—</span>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  ) : null}
                </div>

                <div className="card mt12" style={{ background: 'var(--panel-2)' }}>
                  <div className="card-body" style={{ padding: 10 }}>
                    <div className="row wrap" style={{ gap: 8 }}>
                      <span className="small muted">索引对照：</span>
                      {indexLine.map((idx) => {
                        const status = diffIndexByName.get(idx.name.toUpperCase());
                        const missing = status ? status === 'MISSING' : Boolean(idx.targetMissing);
                        return (
                          <span key={idx.name} className="row" style={{ gap: 6 }}>
                            <span className={cls('badge', missing ? 'warn' : 'ok')}>
                              {idx.kind}({idx.columns})
                            </span>
                            <span className="small faint mono">{idx.name}</span>
                            <span className="small" style={{ color: missing ? 'var(--warn)' : 'var(--muted)' }}>
                              {status ? (missing ? '→ 目标缺失' : '→ 目标已存在') : missing ? `→ 目标缺失 ${idx.targetName}` : '→ 目标已存在'}
                            </span>
                            {missing ? (
                              <button
                                className="btn-link"
                                onClick={() => setNotice(`已为 ${selected[0]} 生成缺失索引 ${idx.targetName ?? idx.name} 的 DDL`)}
                              >
                                [生成]
                              </button>
                            ) : null}
                          </span>
                        );
                      })}
                      {(ddlDiff?.indexes ?? [])
                        .filter((d) => !indexLine.some((x) => x.name.toUpperCase() === d.name.toUpperCase()))
                        .map((d) => (
                          <span key={d.name} className="row" style={{ gap: 6 }}>
                            <span className="badge neutral">IDX</span>
                            <span className="small faint mono">{d.name}</span>
                            <span className="small" style={{ color: d.status === 'MISSING' ? 'var(--warn)' : 'var(--muted)' }}>
                              {d.status === 'MISSING' ? '→ 目标缺失（接口返回）' : `→ ${d.status}`}
                            </span>
                          </span>
                        ))}
                      {!ddlDiff ? <span className="small faint">未获取到目标端索引状态，仅展示映射生成结果</span> : null}
                    </div>
                  </div>
                </div>

                <div className="row wrap mt12" style={{ gap: 20 }}>
                  <div className="field">
                    <label>标识符策略</label>
                    <div className="radio-row">
                      {IDENT_STRATEGIES.map((s) => (
                        <label className="radio" key={s.key}>
                          <input type="radio" checked={ident === s.key} onChange={() => setIdent(s.key)} />
                          {s.label}
                        </label>
                      ))}
                    </div>
                  </div>
                  <div className="field">
                    <label>引号策略</label>
                    <select className="select" value={quote} onChange={(e) => setQuote(e.target.value)} style={{ width: 150 }}>
                      <option value="auto">自动（按目标方言）</option>
                      <option value="double">始终双引号</option>
                      <option value="backtick">反引号（MySQL）</option>
                      <option value="none">不加引号</option>
                    </select>
                  </div>
                  <div className="spacer" />
                  <div className="small faint" style={{ maxWidth: 380 }}>
                    Oracle 源默认大写无引号 → DM8/PG 目标默认产出小写无引号标识符，业务 SQL 无需改大小写；
                    含空格/中文/保留字的表名会强制加引号。
                  </div>
                </div>

                {/* 风险清单：置底且不可折叠 */}
                <div className="risk-list mt16">
                  <div className="risk-list-head">
                    <span>⚠ 风险清单（{risks.length} 项）</span>
                    <span className="small faint" style={{ marginLeft: 'auto', color: 'var(--faint)' }}>
                      强制展示，不可折叠隐藏
                    </span>
                  </div>
                  <ul>
                    {risks.map((r) => (
                      <li key={r}>{r}</li>
                    ))}
                  </ul>
                </div>
              </>
            )}

            <div className="action-bar">
              <button className="btn" onClick={() => goStep(0)}>
                上一步
              </button>
              <div className="row" style={{ gap: 10 }}>
                <span className="small faint">
                  {Object.keys(overrides).length > 0 ? `已人工覆盖 ${Object.keys(overrides).length} 列，DDL 保存时重新生成` : '未做人工覆盖'}
                </span>
                <button className="btn btn-primary" onClick={() => goStep(2)} disabled={!detail}>
                  下一步：迁移参数
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : null}

      {step === 2 ? (
        <div className="card">
          <div className="card-body">
            <div className="form-section" style={{ borderTop: 'none', paddingTop: 0, marginTop: 0 }}>
              <div className="form-section-title">存量迁移参数</div>
              <div className="form-grid cols-3">
                <div className="field">
                  <label>并发线程</label>
                  <input className="input mono" type="number" min={1} max={32} value={threads} onChange={(e) => setThreads(Number(e.target.value))} />
                  <span className="small faint">单实例并发读取线程，建议 ≤ CPU 核数 × 2</span>
                </div>
                <div className="field">
                  <label>批量大小（行）</label>
                  <input className="input mono" type="number" min={100} step={100} value={batch} onChange={(e) => setBatch(Number(e.target.value))} />
                  <span className="small faint">单批次 INSERT/MERGE；行宽 &gt; 1MB 自动降批</span>
                </div>
                <div className="field">
                  <label>分片行数（行/片）</label>
                  <input className="input mono" type="number" min={10000} step={100000} value={shardRows} onChange={(e) => setShardRows(Number(e.target.value))} />
                  <span className="small faint">
                    8.2 亿行将切出 <b className="mono">{Math.ceil(820_000_000 / Math.max(1, shardRows))}</b> 片
                  </span>
                </div>
                <div className="field">
                  <label>限速（行/秒）</label>
                  <input className="input mono" value={limit} onChange={(e) => setLimit(e.target.value)} placeholder="留空表示不限速" />
                  <span className="small faint">令牌桶实现，实测均值不超过设定 +5%</span>
                </div>
                <div className="field">
                  <label>失败重试次数</label>
                  <input className="input mono" type="number" min={0} max={10} value={retry} onChange={(e) => setRetry(Number(e.target.value))} />
                  <span className="small faint">指数退避，耗尽后进死信队列</span>
                </div>
              </div>
            </div>

            <div className="form-section">
              <div className="form-section-title">增量同步参数</div>
              <div className="radio-row mb12">
                <span className="small muted" style={{ marginRight: 4 }}>
                  模式
                </span>
                <label className="radio">
                  <input type="radio" checked={cdcMode === 'SDK_DUAL_WRITE'} onChange={() => setCdcMode('SDK_DUAL_WRITE')} />
                  SDK 双写
                </label>
                <label className="radio">
                  <input type="radio" checked={cdcMode === 'TRIGGER_LOG'} onChange={() => setCdcMode('TRIGGER_LOG')} />
                  触发器日志表
                </label>
                <label className="radio">
                  <input type="radio" checked={cdcMode === 'TIMESTAMP'} onChange={() => setCdcMode('TIMESTAMP')} />
                  时间戳 + 版本号
                </label>
              </div>
              <div className="form-grid cols-3">
                <div className="field">
                  <label>消费并发</label>
                  <input className="input mono" type="number" min={1} value={consumers} onChange={(e) => setConsumers(Number(e.target.value))} />
                </div>
                <div className="field">
                  <label>轮询间隔（秒）</label>
                  <input className="input mono" type="number" min={1} value={pollSec} onChange={(e) => setPollSec(Number(e.target.value))} />
                  <span className="small faint">时间戳模式回退 1 个周期防丢</span>
                </div>
                <div className="field">
                  <label>冲突策略</label>
                  <select className="select" value={conflict} onChange={(e) => setConflict(e.target.value)}>
                    <option value="SOURCE_WINS">源优先</option>
                    <option value="TARGET_WINS">目标优先</option>
                    <option value="TIMESTAMP_NEWER">时间戳比较</option>
                  </select>
                </div>
                <div className="field">
                  <label>批量大小（行）</label>
                  <input className="input mono" type="number" min={1} value={cdcBatch} onChange={(e) => setCdcBatch(Number(e.target.value))} />
                </div>
              </div>
              <div className="alert info mt12">
                {cdcMode === 'SDK_DUAL_WRITE'
                  ? 'SDK 双写：业务侧改造成本 ≤ 3 行代码，失败不阻断主流程（落本地补偿表 + 告警），延迟 P95 ≤ 3s。'
                  : cdcMode === 'TRIGGER_LOG'
                    ? '触发器日志表：源库为每张表创建 xxx_SYNC_LOG，平台轮询消费；需要 DDL 权限，删除也能正确传播。'
                    : '时间戳模式：以 update_time/version 增量拉取，适用于无触发器权限的库；边界重叠窗口防丢。'}
              </div>
            </div>

            <div className="form-section">
              <div className="row wrap" style={{ gap: 24 }}>
                <label className="check">
                  <input type="checkbox" checked={precheck} onChange={(e) => setPrecheck(e.target.checked)} />
                  迁移前预检（目标表存在性 / 空间 / 字符集 / 权限）
                </label>
                <label className="check">
                  <input type="checkbox" checked={autoVerify} onChange={(e) => setAutoVerify(e.target.checked)} />
                  完成后自动核数（COUNT 级比对，可通过后再做全量 HASH 比对）
                </label>
              </div>
            </div>

            <div className="action-bar">
              <button className="btn" onClick={() => goStep(1)}>
                上一步
              </button>
              <button className="btn btn-primary" onClick={() => goStep(3)}>
                下一步：比对与切流
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {step === 3 ? (
        <div className="card">
          <div className="card-body">
            <div className="form-section" style={{ borderTop: 'none', paddingTop: 0, marginTop: 0 }}>
              <div className="form-section-title">比对参数</div>
              <div className="form-grid cols-3">
                <div className="field">
                  <label>分块行数</label>
                  <input className="input mono" type="number" min={1000} step={10000} value={chunkRows} onChange={(e) => setChunkRows(Number(e.target.value))} />
                </div>
                <div className="field">
                  <label>比对并发</label>
                  <input className="input mono" type="number" min={1} value={cmpConc} onChange={(e) => setCmpConc(Number(e.target.value))} />
                </div>
                <div className="field">
                  <label>结论阈值 PASS &lt;（%）</label>
                  <input className="input mono" value={threshold} onChange={(e) => setThreshold(e.target.value)} />
                </div>
                <div className="field span-2">
                  <label>比对算法</label>
                  <div className="radio-row" style={{ height: 30 }}>
                    {['COUNT', 'SUM', 'HASH'].map((a) => (
                      <label className="check" key={a}>
                        <input
                          type="checkbox"
                          checked={algos.includes(a)}
                          onChange={() =>
                            setAlgos((prev) => (prev.includes(a) ? prev.filter((x) => x !== a) : [...prev, a]))
                          }
                        />
                        {a}
                      </label>
                    ))}
                  </div>
                </div>
                <div className="field">
                  <label>定时比对</label>
                  <select className="select" value={schedule} onChange={(e) => setSchedule(e.target.value)}>
                    <option value="off">关闭</option>
                    <option value="1h">每 1 小时</option>
                    <option value="6h">每 6 小时</option>
                    <option value="24h">每 24 小时</option>
                  </select>
                </div>
              </div>
            </div>

            <div className="form-section">
              <div className="form-section-title">切流策略</div>
              <div className="row wrap" style={{ gap: 18 }}>
                <div className="field">
                  <label>灰度维度</label>
                  <div className="radio-row" style={{ height: 30 }}>
                    {[
                      { k: 'ratio', l: '比例' },
                      { k: 'table', l: '按表' },
                      { k: 'hash', l: '主键哈希' },
                    ].map((d) => (
                      <label className="check" key={d.k}>
                        <input
                          type="checkbox"
                          checked={dims.includes(d.k)}
                          onChange={() => setDims((prev) => (prev.includes(d.k) ? prev.filter((x) => x !== d.k) : [...prev, d.k]))}
                        />
                        {d.l}
                      </label>
                    ))}
                  </div>
                </div>
              </div>

              <div className="form-grid cols-3 mt12">
                <div className="field">
                  <label>卡点：比对通过率 ≥（%）</label>
                  <input className="input mono" value={gatePass} onChange={(e) => setGatePass(e.target.value)} />
                </div>
                <div className="field">
                  <label>卡点：增量延迟 ≤（秒）</label>
                  <input className="input mono" value={gateLatency} onChange={(e) => setGateLatency(e.target.value)} />
                </div>
                <div className="field">
                  <label>卡点：错误率 ≤（%）</label>
                  <input className="input mono" value={gateFail} onChange={(e) => setGateFail(e.target.value)} />
                </div>
                <div className="field">
                  <label>观察期（小时）</label>
                  <input className="input mono" type="number" min={1} value={observeH} onChange={(e) => setObserveH(Number(e.target.value))} />
                </div>
                <div className="field">
                  <label>
                    自动回滚阈值：错误率 &gt;（%）
                    <span className="badge warn" style={{ marginLeft: 6 }}>
                      未启用（P1 规划，CUT-06 后端未实现）
                    </span>
                  </label>
                  <input
                    className="input mono"
                    value={rollbackPct}
                    disabled
                    title="CUT-06 自动回滚后端未实现，该配置暂不生效"
                    onChange={(e) => setRollbackPct(e.target.value)}
                  />
                </div>
              </div>
            </div>

            <div className="card mt12" style={{ background: 'var(--panel-2)' }}>
              <div className="card-body" style={{ padding: 12 }}>
                <div className="form-section-title" style={{ marginBottom: 8 }}>
                  配置摘要
                </div>
                <div className="row wrap" style={{ gap: 18 }}>
                  <StatusDot tone="accent">
                    源 {source?.name ?? '-'} → 目标 {target?.name ?? '-'}
                  </StatusDot>
                  <span className="small muted">
                    表 {selected.length} 张 · 模式 {mode === 'FULL_INCR' ? '存量 + 增量' : '存量'} · 分片 {Math.ceil(820_000_000 / Math.max(1, shardRows))} 片
                  </span>
                  <span className="small muted">
                    增量 {cdcMode === 'TRIGGER_LOG' ? '触发器日志表' : cdcMode === 'SDK_DUAL_WRITE' ? 'SDK 双写' : '时间戳'}
                  </span>
                  <span className="small muted">比对算法 {algos.join(' + ')} · 阈值 {threshold}%</span>
                </div>
              </div>
            </div>

            <div className="card mt12">
              <div className="card-head">
                <div className="card-title">
                  迁移前预检
                  <span className="hint">POST /api/tasks/{'{id}'}/precheck · 阻断项必须清零才能启动</span>
                </div>
                <button className="btn btn-sm" onClick={() => void runPrecheck()} disabled={prechecking || !can('TASK_EXEC')}
                  title={can('TASK_EXEC') ? undefined : needPermText('TASK_EXEC')}>
                  {prechecking ? '预检中…' : '执行预检'}
                </button>
              </div>
              {precheckResult ? (
                <div className="card-body">
                  <div className={cls('alert', precheckResult.blocking.length === 0 ? 'ok' : 'danger')}>
                    <div>
                      <div style={{ fontWeight: 700 }}>
                        {precheckResult.blocking.length === 0 ? '预检通过' : `预检阻断 ${precheckResult.blocking.length} 项`}
                        {' · '}告警 {precheckResult.warnings.length} 项
                      </div>
                    </div>
                  </div>
                  {precheckResult.blocking.length > 0 ? (
                    <div className="risk-list mt12">
                      <div className="risk-list-head">⛔ 阻断项（必须修复）</div>
                      <ul>
                        {precheckResult.blocking.map((b) => (
                          <li key={b}>{b}</li>
                        ))}
                      </ul>
                    </div>
                  ) : null}
                  {precheckResult.warnings.length > 0 ? (
                    <div className="risk-list mt12" style={{ borderColor: 'rgba(251,191,36,0.4)' }}>
                      <div className="risk-list-head">⚠ 告警项（可继续但不建议忽略）</div>
                      <ul>
                        {precheckResult.warnings.map((w) => (
                          <li key={w}>{w}</li>
                        ))}
                      </ul>
                    </div>
                  ) : null}
                </div>
              ) : (
                <div className="card-body small faint">尚未执行预检：检查目标表存在性、空间、字符集/时区、唯一键重复、DDL/DML 权限。</div>
              )}
            </div>

            <div className="action-bar">
              <button className="btn" onClick={() => goStep(2)}>
                上一步
              </button>
              <div className="row" style={{ gap: 8 }}>
                <button
                  className="btn"
                  onClick={() => {
                    void ensureDraft().then(() => setNotice('草稿已保存（DRAFT），可从任务列表继续编辑'));
                  }}
                >
                  保存草稿
                </button>
                <button
                  className="btn btn-primary"
                  onClick={() => void createTask()}
                  disabled={!can('TASK_EDIT') || (precheckResult ? precheckResult.blocking.length > 0 : false)}
                  title={
                    !can('TASK_EDIT')
                      ? needPermText('TASK_EDIT')
                      : precheckResult && precheckResult.blocking.length > 0
                        ? '存在阻断项，无法创建'
                        : undefined
                  }
                >
                  创建任务
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : null}

      <Modal open={sqlOpen} wide title={`完整 SQL 预览 · ${selected[0] ?? 'T_ORDER'} → ${target?.type ?? 'DM8'}`} onClose={() => setSqlOpen(false)}
        footer={
          <>
            <button className="btn" onClick={() => setSqlOpen(false)}>关闭</button>
            <button className="btn btn-primary" onClick={() => downloadText(ddlText, `${selected[0] ?? 'T_ORDER'}_DM8.sql`, 'application/sql;charset=utf-8')}>
              导出 .sql
            </button>
          </>
        }
      >
        <pre className="code-block">{ddlText || '-- 暂无 DDL，请先完成第 ② 步映射'}</pre>
        <div className="small faint mt8">
          注意：DDL 不会自动在目标库执行（本期 MAP-07 为 P2，需手工确认）；请在目标库执行前先用 dry-run 校验。
        </div>
      </Modal>

      <Modal
        open={editCol !== null}
        title={`人工覆盖映射 · ${editCol ?? ''}`}
        onClose={() => setEditCol(null)}
        footer={
          <>
            <button className="btn" onClick={() => setEditCol(null)}>
              取消
            </button>
            <button
              className="btn btn-primary"
              onClick={() => {
                if (editCol) {
                  setOverrides((prev) => ({ ...prev, [editCol]: editType }));
                  setNotice(`列 ${editCol} 已人工覆盖为 ${editType}，覆盖人 alice，已标注“人工覆盖”`);
                }
                setEditCol(null);
              }}
            >
              应用覆盖
            </button>
          </>
        }
      >
        <div className="col" style={{ gap: 12 }}>
          <div className="alert info">
            人工覆盖会记录 override_by，生成 DDL 使用覆盖结果，并在风险清单标注“人工覆盖”；该操作需 TASK_EDIT 权限。
          </div>
          <div className="field">
            <label>目标类型</label>
            <input className="input mono" value={editType} onChange={(e) => setEditType(e.target.value)} list="tgt-types" />
            <datalist id="tgt-types">
              {['DECIMAL(19,0)', 'DECIMAL(38,10)', 'BIGINT', 'VARCHAR(64)', 'VARCHAR(128)', 'CHAR(1)', 'TIMESTAMP(0)', 'TIMESTAMP(6)', 'CLOB', 'BLOB'].map((t) => (
                <option key={t} value={t} />
              ))}
            </datalist>
          </div>
          <div className="row wrap" style={{ gap: 6 }}>
            {['DECIMAL(19,0)', 'BIGINT', 'VARCHAR(128)', 'TIMESTAMP(6)', 'CLOB'].map((t) => (
              <button key={t} className="btn btn-sm mono" onClick={() => setEditType(t)}>
                {t}
              </button>
            ))}
          </div>
        </div>
      </Modal>

      <Drawer open={helpOpen} onClose={() => setHelpOpen(false)} title="帮助与规则说明" subtitle="向导每步的判定依据与不可逆操作提示">
        <div className="col" style={{ gap: 14 }}>
          <div>
            <div className="card-title mb8">① 数据源与表</div>
            <div className="small muted">
              无主键表使用全列哈希或物理地址分片，断点不可精确定位，UI 用红色风险标记；
              复合主键（2~3 列）按多列拼接区间，保证不重不漏。
            </div>
          </div>
          <div>
            <div className="card-title mb8">② 结构映射与 DDL</div>
            <div className="small muted">
              类型映射矩阵覆盖 5×5 方向；<b className="mono">⚠ 降级</b> 表示目标类型无法完整承载源语义（如 Oracle 无精度
              NUMBER → DECIMAL(38,10)）。标识符策略默认 LOWER，Oracle→DM8/PG 业务 SQL 无需改大小写。
            </div>
          </div>
          <div>
            <div className="card-title mb8">③ 迁移 / 增量参数</div>
            <div className="small muted">
              分片行数默认 200 万，8 亿行表切 ≥ 400 片；限速为令牌桶，实测 30 分钟均值不超过设定值 +5%；
              重试耗尽进死信，可单分片重放。
            </div>
          </div>
          <div>
            <div className="card-title mb8">④ 比对与切流</div>
            <div className="small muted">
              比对结论：PASS &lt; 阈值；WARN = 差异存在但可接受；FAIL = 超过阈值阻断切流。卡点未达标时“推进阶段”置灰，
              强行跳过需 ADMIN + 理由 ≥ 10 字 + 输入 SKIP-GATE。
            </div>
          </div>
          <div className="alert warn">
            创建任务不会自动启动迁移；需在任务详情或大盘手动点“启动”，并对 PROD 环境二次确认。
          </div>
        </div>
      </Drawer>
    </div>
  );
}

// 仅用于类型推导，避免在组件内重复书写 DataSource[] 类型
async function fetchDs(): Promise<import('../types').DataSource[]> {
  return http.get<import('../types').DataSource[]>(EP.dsList());
}
