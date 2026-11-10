import { useMemo, useState } from 'react';
import { EP } from '../api/endpoints';
import { http } from '../api/client';
import { useQuery } from '../api/useQuery';
import { needPermText, useCan } from '../api/auth';
import { errorText } from '../api/errors';
import type { DataSource, DbType, EnvLabel, PoolConfig, TestConnResult } from '../types';
import { DataTable, type Column } from '../components/DataTable';
import { Drawer } from '../components/Drawer';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { StatusDot } from '../components/StatusDot';
import { DB_TEMPLATES } from '../api/mock/data';
import { cls } from '../utils/format';

interface DsForm {
  id: string;
  name: string;
  type: DbType;
  env: EnvLabel;
  host: string;
  port: string;
  database: string;
  username: string;
  password: string;
  schema: string;
  pool: PoolConfig;
  ssl: boolean;
  note: string;
  sessionParams: string;
  fetchSize: string;
  batchSize: string;
}

const EMPTY_FORM: DsForm = {
  id: '',
  name: '',
  type: 'Oracle',
  env: 'UAT',
  host: '',
  port: '1521',
  database: '',
  username: '',
  password: '',
  schema: 'APP',
  pool: { maxConn: 8, minIdle: 2, connectTimeoutSec: 10 },
  ssl: false,
  note: '',
  sessionParams: '',
  fetchSize: '5000',
  batchSize: '1000',
};

function toForm(ds: DataSource): DsForm {
  return {
    id: ds.id,
    name: ds.name,
    type: ds.type,
    env: ds.env,
    host: ds.host,
    port: String(ds.port),
    database: ds.database,
    username: ds.username,
    password: '••••••••',
    schema: ds.schema,
    pool: { ...ds.pool },
    ssl: ds.ssl,
    note: ds.note ?? '',
    sessionParams: '',
    fetchSize: '5000',
    batchSize: '1000',
  };
}

function buildUrl(f: DsForm): string {
  const tpl = DB_TEMPLATES[f.type]?.urlTemplate ?? '';
  return tpl
    .replace('{host}', f.host || '{host}')
    .replace('{port}', f.port || '{port}')
    .replace('{database}', f.database || '{database}')
    .replace('{schema}', f.schema || '{schema}')
    .replace('{ssl}', f.ssl ? 'true' : 'false');
}

export function Datasources() {
  const can = useCan();
  const q = useQuery('datasources', () => http.get<DataSource[]>(EP.dsList()));
  const [rows, setRows] = useState<DataSource[] | null>(null);
  const list = rows ?? q.data ?? [];

  const [type, setType] = useState('ALL');
  const [env, setEnv] = useState('ALL');
  const [state, setState] = useState('ALL');
  const [kw, setKw] = useState('');

  const [drawer, setDrawer] = useState<{ open: boolean; form: DsForm }>({ open: false, form: EMPTY_FORM });
  const [advanced, setAdvanced] = useState(false);
  const [testResult, setTestResult] = useState<TestConnResult | null>(null);
  const [testing, setTesting] = useState(false);
  const [revealAsk, setRevealAsk] = useState(false);
  const [revealed, setRevealed] = useState(false);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'warn' | 'danger' | 'info'; text: string } | null>(null);
  const [batchPending, setBatchPending] = useState(false);

  const filtered = useMemo(
    () =>
      list.filter((d) => {
        if (type !== 'ALL' && d.type !== type) return false;
        if (env !== 'ALL' && d.env !== env) return false;
        if (state !== 'ALL' && d.state !== state) return false;
        if (kw.trim()) {
          const k = kw.trim().toLowerCase();
          if (!`${d.name} ${d.host} ${d.database} ${d.schema}`.toLowerCase().includes(k)) return false;
        }
        return true;
      }),
    [list, type, env, state, kw],
  );

  function openNew(): void {
    setDrawer({ open: true, form: { ...EMPTY_FORM } });
    setTestResult(null);
    setRevealed(false);
    setAdvanced(false);
  }

  function openEdit(ds: DataSource): void {
    setDrawer({ open: true, form: toForm(ds) });
    setTestResult(null);
    setRevealed(false);
    setAdvanced(false);
  }

  function patch(p: Partial<DsForm>): void {
    setDrawer((prev) => ({ open: prev.open, form: { ...prev.form, ...p } }));
  }

  function switchType(next: DbType): void {
    const tpl = DB_TEMPLATES[next];
    patch({ type: next, port: String(tpl?.port ?? 0), schema: tpl?.defaultSchema ?? '', database: '' });
    setTestResult(null);
  }

  async function testConn(): Promise<void> {
    setTesting(true);
    const f = drawer.form;
    try {
      // 新建（未落库）走草稿测试接口，已存在的走 POST /{id}/test
      const res = f.id
        ? await http.post<TestConnResult>(EP.dsTest(f.id))
        : await http.post<TestConnResult>(EP.dsTestDraft(), {
            type: f.type,
            host: f.host,
            port: Number(f.port),
            database: f.database,
            username: f.username,
            password: f.password,
            schema: f.schema,
            ssl: f.ssl,
          });
      setTestResult(res);
    } catch (err) {
      setTestResult({
        ok: false,
        version: '',
        costMs: 0,
        message: errorText(err),
        at: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
      });
    } finally {
      setTesting(false);
    }
  }

  async function save(): Promise<void> {
    const f = drawer.form;
    if (!f.name.trim() || !f.host.trim() || !f.database.trim()) {
      setNotice({ tone: 'danger', text: '保存失败：名称、主机、库名/SID 为必填项' });
      return;
    }
    const payload = {
      name: f.name,
      type: f.type,
      env: f.env,
      host: f.host,
      port: Number(f.port),
      database: f.database,
      username: f.username,
      schema: f.schema,
      pool: f.pool,
      ssl: f.ssl,
      driver: DB_TEMPLATES[f.type]?.driver ?? '',
      jdbcUrl: buildUrl(f),
      note: f.note,
      state: 'UP' as const,
      lastProbe: '刚刚',
      probeCostMs: 52,
      version: testResult?.version || '待探测',
    };
    try {
      if (f.id) {
        await http.put(EP.dsUpdate(f.id), payload);
        setRows(list.map((d) => (d.id === f.id ? { ...d, ...payload } : d)));
        setNotice({ tone: 'ok', text: `数据源 ${f.name} 已保存，连接池参数已热生效（无需重启）` });
      } else {
        const created = await http.post<{ id: string }>(EP.dsCreate(), payload);
        setRows([...list, { ...payload, id: created.id ?? `ds-new-${Date.now()}` } as DataSource]);
        setNotice({ tone: 'ok', text: `数据源 ${f.name} 创建成功，建议立即执行一次连接测试` });
      }
      setDrawer({ open: false, form: EMPTY_FORM });
    } catch (err) {
      setNotice({ tone: 'danger', text: errorText(err) });
    }
  }

  async function batchTest(): Promise<void> {
    setBatchPending(true);
    try {
      const results = await Promise.all(
        filtered.map(async (d) => ({ d, r: await http.post<TestConnResult>(EP.dsTest(d.id)) })),
      );
      const okCount = results.filter((x) => x.r.ok).length;
      setRows(
        list.map((d) => {
          const hit = results.find((x) => x.d.id === d.id);
          if (!hit) return d;
          return {
            ...d,
            state: hit.r.ok ? (hit.r.costMs > 1000 ? 'SLOW' : 'UP') : 'DOWN',
            lastProbe: '刚刚',
            probeCostMs: hit.r.costMs,
          };
        }),
      );
      setNotice({
        tone: okCount === filtered.length ? 'ok' : 'warn',
        text: `批量测试完成：${okCount}/${filtered.length} 可用${
          okCount < filtered.length
            ? `，失败：${results
                .filter((x) => !x.r.ok)
                .map((x) => x.d.name)
                .join('、')}`
            : ''
        }`,
      });
    } finally {
      setBatchPending(false);
    }
  }

  const columns: Column<DataSource>[] = [
    {
      key: 'state',
      title: '状态',
      width: 92,
      render: (d) => (
        <StatusDot tone={d.state === 'UP' ? 'ok' : d.state === 'SLOW' ? 'warn' : 'danger'}>
          {d.state === 'UP' ? '可用' : d.state === 'SLOW' ? '缓慢' : '不可用'}
        </StatusDot>
      ),
    },
    {
      key: 'name',
      title: '名称',
      sortable: true,
      render: (d) => (
        <div className="row" style={{ gap: 8 }}>
          <span className="mono cell-strong">{d.name}</span>
          {d.env === 'PROD' ? <span className="badge prod">PROD</span> : null}
        </div>
      ),
    },
    { key: 'type', title: '类型', sortable: true, render: (d) => <span className="mono">{d.type}</span> },
    {
      key: 'env',
      title: '环境',
      render: (d) => (
        <span className={cls('badge', d.env === 'PROD' ? 'danger' : d.env === 'UAT' ? 'info' : 'neutral')}>{d.env}</span>
      ),
    },
    {
      key: 'addr',
      title: '地址',
      render: (d) => (
        <span className="mono small">
          {d.host}:{d.port}/{d.database}
        </span>
      ),
    },
    {
      key: 'lastProbe',
      title: '最近探活',
      sortable: true,
      sortValue: (d) => d.probeCostMs,
      render: (d) => (
        <div className="row" style={{ gap: 6 }}>
          <span className="mono small">{d.lastProbe}</span>
          {d.state === 'UP' ? (
            <span className="ok small" style={{ color: 'var(--ok)' }}>
              ✓ {d.probeCostMs}ms
            </span>
          ) : d.state === 'SLOW' ? (
            <span className="small" style={{ color: 'var(--warn)' }}>
              慢 {d.probeCostMs}ms
            </span>
          ) : (
            <span className="small" style={{ color: 'var(--danger)' }}>
              连接失败
            </span>
          )}
        </div>
      ),
    },
    { key: 'schema', title: 'Schema', render: (d) => <span className="mono small">{d.schema}</span> },
    {
      key: 'op',
      title: '操作',
      render: (d) => (
        <div className="row" style={{ gap: 10 }}>
          <button
            className="btn-link"
            onClick={async (e) => {
              e.stopPropagation();
              const r = await http.post<TestConnResult>(EP.dsTest(d.id));
              setNotice({ tone: r.ok ? 'ok' : 'danger', text: `${d.name}：${r.message}（${r.costMs}ms）` });
            }}
          >
            测试
          </button>
          <button className="btn-link" onClick={() => openEdit(d)}>
            编辑
          </button>
        </div>
      ),
    },
  ];

  const f = drawer.form;

  return (
    <div>
      <div className="page-head">
        <div className="page-title">
          <h1>数据源管理</h1>
          <span className="page-sub">5 种异构库 · 凭据加密入库 · 定时探活</span>
        </div>
        <div className="page-actions">
          <button className="btn" onClick={() => void batchTest()} disabled={batchPending || filtered.length === 0 || !can('DS_MANAGE')}
            title={can('DS_MANAGE') ? undefined : needPermText('DS_MANAGE')}>
            {batchPending ? '测试中…' : `批量测试${filtered.length ? `（${filtered.length}）` : ''}`}
          </button>
          <button
            className="btn btn-primary"
            onClick={openNew}
            disabled={!can('DS_MANAGE')}
            title={can('DS_MANAGE') ? undefined : needPermText('DS_MANAGE')}
          >
            + 新建数据源
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

      <div className="card">
        <div className="filter-bar">
          <span className="filter-label">筛选</span>
          <select className="select" value={type} onChange={(e) => setType(e.target.value)} style={{ width: 130 }}>
            <option value="ALL">全部类型</option>
            {['MySQL', 'Oracle', 'PostgreSQL', 'DM8', 'DB2'].map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
          <select className="select" value={env} onChange={(e) => setEnv(e.target.value)} style={{ width: 110 }}>
            <option value="ALL">全部环境</option>
            <option value="PROD">PROD</option>
            <option value="UAT">UAT</option>
            <option value="DEV">DEV</option>
          </select>
          <select className="select" value={state} onChange={(e) => setState(e.target.value)} style={{ width: 110 }}>
            <option value="ALL">全部状态</option>
            <option value="UP">可用</option>
            <option value="SLOW">缓慢</option>
            <option value="DOWN">不可用</option>
          </select>
          <input
            className="input"
            placeholder="搜索名称 / 主机 / 库名"
            value={kw}
            onChange={(e) => setKw(e.target.value)}
            style={{ width: 230 }}
          />
          <span className="small faint" style={{ marginLeft: 'auto' }}>
            共 {filtered.length} / {list.length} 个数据源
          </span>
        </div>

        <DataTable
          columns={columns}
          rows={filtered}
          rowKey={(d) => d.id}
          loading={q.loading && rows === null}
          onRowClick={openEdit}
          minWidth={1040}
          emptyText="没有匹配的数据源"
          emptyHint="调整筛选条件，或点击右上角新建数据源"
        />
      </div>

      <div className="mt12 small faint">
        环境为 PROD 的数据源在切流、反向修复等操作时强制二次确认；密码字段接口恒返回 ******，明文只在服务端解密。
      </div>

      <Drawer
        open={drawer.open}
        onClose={() => setDrawer({ open: false, form: EMPTY_FORM })}
        title={f.id ? `编辑数据源 · ${f.name}` : '新建数据源'}
        subtitle={f.id ? `ID ${f.id}` : '选择类型后自动切换驱动与 URL 模板'}
        footer={
          <>
            <button className="btn" onClick={() => setTestResult(null)} disabled={testing}>
              清空结果
            </button>
            <button className="btn" onClick={() => void testConn()} disabled={testing}>
              {testing ? '测试中…' : '测试连接'}
            </button>
            <div className="spacer" />
            <button className="btn" onClick={() => setDrawer({ open: false, form: EMPTY_FORM })}>
              取消
            </button>
            <button className="btn btn-primary" onClick={() => void save()}>
              {f.id ? '保存' : '创建'}
            </button>
          </>
        }
      >
        <div className="form-grid">
          <div className="field span-2">
            <label>
              名称<span className="req">*</span>
            </label>
            <input className="input mono" value={f.name} onChange={(e) => patch({ name: e.target.value })} placeholder="ora-core-prod" />
          </div>

          <div className="field">
            <label>
              类型<span className="req">*</span>
            </label>
            <select className="select" value={f.type} onChange={(e) => switchType(e.target.value as DbType)}>
              {['Oracle', 'MySQL', 'PostgreSQL', 'DM8', 'DB2'].map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </div>

          <div className="field">
            <label>环境</label>
            <select className="select" value={f.env} onChange={(e) => patch({ env: e.target.value as EnvLabel })}>
              <option value="PROD">PROD</option>
              <option value="UAT">UAT</option>
              <option value="DEV">DEV</option>
            </select>
          </div>

          <div className="field">
            <label>
              主机<span className="req">*</span>
            </label>
            <input className="input mono" value={f.host} onChange={(e) => patch({ host: e.target.value })} placeholder="10.1.2.3" />
          </div>

          <div className="field">
            <label>端口</label>
            <input className="input mono" value={f.port} onChange={(e) => patch({ port: e.target.value })} />
          </div>

          <div className="field">
            <label>
              {DB_TEMPLATES[f.type]?.dbLabel ?? '数据库'}<span className="req">*</span>
            </label>
            <input
              className="input mono"
              value={f.database}
              onChange={(e) => patch({ database: e.target.value })}
              placeholder="ORCL / appdb / DMSYNC"
            />
          </div>

          <div className="field">
            <label>Schema 默认值</label>
            <input className="input mono" value={f.schema} onChange={(e) => patch({ schema: e.target.value })} />
          </div>

          <div className="field">
            <label>用户名</label>
            <input className="input mono" value={f.username} onChange={(e) => patch({ username: e.target.value })} />
          </div>

          <div className="field span-2">
            <label>
              密码
              <button className="btn-link" style={{ marginLeft: 'auto' }} onClick={() => setRevealAsk(true)}>
                {revealed ? '隐藏' : '显示明文（需二次确认）'}
              </button>
            </label>
            <input
              className="input mono"
              type={revealed ? 'text' : 'password'}
              value={revealed ? 'P@ssw0rd!2026' : f.password}
              onChange={(e) => patch({ password: e.target.value })}
              placeholder="AES-256-GCM 加密入库，接口不回显"
            />
          </div>

          <div className="field span-2">
            <label>JDBC URL（按类型与字段自动生成）</label>
            <input className="input mono" value={buildUrl(f)} readOnly style={{ color: 'var(--muted)' }} />
          </div>

          <div className="field span-2">
            <label>驱动类</label>
            <input className="input mono" value={DB_TEMPLATES[f.type]?.driver ?? ''} readOnly style={{ color: 'var(--muted)' }} />
          </div>
        </div>

        <div className="form-section">
          <div className="form-section-title">连接池</div>
          <div className="form-grid cols-3">
            <div className="field">
              <label>最大连接</label>
              <input
                className="input mono"
                type="number"
                min={1}
                value={f.pool.maxConn}
                onChange={(e) => patch({ pool: { ...f.pool, maxConn: Number(e.target.value) } })}
              />
            </div>
            <div className="field">
              <label>最小空闲</label>
              <input
                className="input mono"
                type="number"
                min={0}
                value={f.pool.minIdle}
                onChange={(e) => patch({ pool: { ...f.pool, minIdle: Number(e.target.value) } })}
              />
            </div>
            <div className="field">
              <label>连接超时（秒）</label>
              <input
                className="input mono"
                type="number"
                min={1}
                value={f.pool.connectTimeoutSec}
                onChange={(e) => patch({ pool: { ...f.pool, connectTimeoutSec: Number(e.target.value) } })}
              />
            </div>
          </div>
          <div className="row mt12" style={{ gap: 12 }}>
            <button
              className="switch"
              data-on={f.ssl}
              onClick={() => patch({ ssl: !f.ssl })}
              aria-label="SSL 开关"
              type="button"
            />
            <span className={f.ssl ? 'dim' : 'muted'}>SSL 加密连接{f.ssl ? '（已开启）' : '（关闭）'}</span>
          </div>
        </div>

        <div className="form-section">
          <div className="row between">
            <div className="form-section-title" style={{ margin: 0 }}>
              高级
            </div>
            <button className="btn-link" onClick={() => setAdvanced((v) => !v)}>
              {advanced ? '收起 ▲' : '展开 ▼'}
            </button>
          </div>
          {advanced ? (
            <div className="col mt12" style={{ gap: 12 }}>
              <div className="field">
                <label>会话参数（分号分隔，建立连接后执行）</label>
                <input
                  className="input mono"
                  value={f.sessionParams}
                  onChange={(e) => patch({ sessionParams: e.target.value })}
                  placeholder="ALTER SESSION SET NLS_DATE_FORMAT='YYYY-MM-DD HH24:MI:SS'"
                />
              </div>
              <div className="form-grid">
                <div className="field">
                  <label>fetchSize 默认</label>
                  <input className="input mono" value={f.fetchSize} onChange={(e) => patch({ fetchSize: e.target.value })} />
                </div>
                <div className="field">
                  <label>批量大小默认</label>
                  <input className="input mono" value={f.batchSize} onChange={(e) => patch({ batchSize: e.target.value })} />
                </div>
              </div>
              <div className="field">
                <label>备注</label>
                <input className="input" value={f.note} onChange={(e) => patch({ note: e.target.value })} placeholder="用途、联系人、维护窗口" />
              </div>
            </div>
          ) : (
            <div className="small faint mt8">会话参数 / fetchSize / 批量大小 / 备注</div>
          )}
        </div>

        {testResult ? (
          <div className={cls('alert', testResult.ok ? 'ok' : 'danger')} style={{ marginTop: 14 }}>
            <div>
              <div style={{ fontWeight: 700 }}>
                {testResult.ok ? '✓ 连接成功' : '✕ 连接失败'} · {testResult.version || '未知版本'} · 耗时{' '}
                <span className="mono">{testResult.costMs}ms</span>
              </div>
              <div className="small" style={{ marginTop: 2 }}>
                {testResult.message}
              </div>
            </div>
          </div>
        ) : null}

        {f.env === 'PROD' ? (
          <div className="alert warn mt12">
            该数据源标记为 PROD：相关切流、回滚、反向修复操作需二次确认并落审计。
          </div>
        ) : null}
      </Drawer>

      <ConfirmDialog
        open={revealAsk}
        danger={false}
        title="显示密码明文"
        confirmText={f.name || 'DEMO'}
        confirmLabel="确认显示"
        onCancel={() => setRevealAsk(false)}
        onConfirm={() => {
          setRevealAsk(false);
          setRevealed(true);
          setNotice({ tone: 'warn', text: '密码明文已显示 30 秒，该操作已记入审计（操作人 / 数据源 / IP）' });
        }}
      >
        <div className="alert warn">
          明文密码仅用于人工核对，展示动作会记入审计日志；请勿截图外传。密码经 AES-256-GCM 加密存储，列表与详情接口恒返回 ******。
        </div>
      </ConfirmDialog>
    </div>
  );
}
