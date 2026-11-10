import { useMemo, useState } from 'react';
import { EP } from '../api/endpoints';
import { downloadFile, http } from '../api/client';
import { useQuery } from '../api/useQuery';
import { PERM_META, useCan, useSession } from '../api/auth';
import { errorText } from '../api/errors';
import type { AlertsPayload, AuditLog, RecentAlertItem, SysUser } from '../types';
import { DataTable, type Column } from '../components/DataTable';
import { StatusDot } from '../components/StatusDot';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { cls } from '../utils/format';

const TABS = ['审计日志', '用户与权限', '告警通道'] as const;
type Tab = (typeof TABS)[number];

const CHANNEL_TEXT: Record<string, string> = {
  LOG: '本地日志',
  WEBHOOK: 'Webhook',
  EMAIL: '邮件',
  WECOM: '企业微信',
};

const ACTION_OPTIONS = [
  'TASK_STATE',
  'TASK_CREATE',
  'CUTOVER_ADVANCE',
  'CUTOVER_SKIP_GATE',
  'CUTOVER_ROLLBACK',
  'DEAD_LETTER_REPLAY',
  'DEAD_LETTER_DISCARD',
  'COMPARE_REPAIR_FIX_SOURCE',
  'DS_UPDATE',
  'USER_ENABLED',
];

export function System() {
  const [tab, setTab] = useState<Tab>('审计日志');
  const [notice, setNotice] = useState<string | null>(null);
  const can = useCan();
  const session = useSession();

  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [result, setResult] = useState('ALL');

  // 后端真实结构：{ channels, rules, recent }，规则只读（阈值在代码/配置里）
  const alertsQ = useQuery('sys:alerts', () => http.get<AlertsPayload>(EP.sysAlerts()));
  const alerts: AlertsPayload = alertsQ.data ?? { channels: [], rules: [], recent: [] };

  const auditKey = `sys:audit:${actor}:${action}`;
  const auditsQ = useQuery(auditKey, () =>
    http.get<AuditLog[]>(EP.sysAudit({ user: actor || undefined, action: action || undefined })),
  );
  const usersQ = useQuery('sys:users', () => http.get<SysUser[]>(EP.sysUsers()));
  const [users, setUsers] = useState<SysUser[] | null>(null);
  const userList = users ?? usersQ.data ?? [];
  const [resetTarget, setResetTarget] = useState<SysUser | null>(null);

  const audits = useMemo(() => {
    const all = auditsQ.data ?? [];
    return result === 'ALL' ? all : all.filter((a) => a.result === result);
  }, [auditsQ.data, result]);

  async function sendTest(rule?: string): Promise<void> {
    try {
      const res = rule
        ? await http.post<{ ok: boolean; message: string }>(EP.sysAlertTestRule(rule))
        : await http.post<{ ok: boolean; message: string }>(EP.sysAlertTest());
      setNotice(res.message);
    } catch (err) {
      setNotice(errorText(err));
    }
  }

  function channelLabel(c: string | { name?: string; kind?: string }): string {
    if (typeof c === 'string') return CHANNEL_TEXT[c] ?? c;
    return c.name ?? c.kind ?? '未知通道';
  }

  async function toggleUser(u: SysUser): Promise<void> {
    try {
      await http.put(EP.sysUserEnabled(u.account), { enabled: !u.enabled });
      setUsers(userList.map((x) => (x.id === u.id ? { ...x, enabled: !x.enabled } : x)));
      setNotice(`${u.account}：已${!u.enabled ? '启用' : '停用'}`);
    } catch (err) {
      setNotice(errorText(err));
    }
  }

  async function resetPassword(u: SysUser): Promise<void> {
    try {
      await http.post(EP.sysUserPassword(u.account), { password: 'Init@12345' });
      setResetTarget(null);
      setNotice(`${u.account}：口令已重置为初始口令，下次登录需强制改密`);
    } catch (err) {
      setNotice(errorText(err));
    }
  }

  const auditColumns: Column<AuditLog>[] = [
    { key: 'at', title: '时间', sortable: true, render: (a) => <span className="mono small">{a.at}</span> },
    { key: 'actor', title: '操作人', sortable: true, render: (a) => <span className="mono">{a.actor}</span> },
    { key: 'action', title: '动作', render: (a) => <span className="badge neutral mono">{a.action}</span> },
    { key: 'object', title: '对象', render: (a) => <span className="small">{a.object}</span> },
    {
      key: 'result',
      title: '结果',
      render: (a) => (
        <StatusDot tone={a.result === 'SUCCESS' ? 'ok' : a.result === 'DENIED' ? 'warn' : 'danger'}>
          {a.result === 'SUCCESS' ? '成功' : a.result === 'DENIED' ? '被拒绝' : '失败'}
        </StatusDot>
      ),
    },
    { key: 'ip', title: 'IP', render: (a) => <span className="mono small">{a.ip}</span> },
    { key: 'trace', title: 'TraceId', render: (a) => <span className="mono small faint">{a.traceId}</span> },
  ];

  const userColumns: Column<SysUser>[] = [
    { key: 'account', title: '账号', render: (u) => <span className="mono cell-strong">{u.account}</span> },
    { key: 'name', title: '姓名', render: (u) => u.name },
    {
      key: 'role',
      title: '角色',
      render: (u) => (
        <span className={cls('badge', u.role === 'ADMIN' ? 'danger' : u.role === 'OPERATOR' ? 'info' : 'neutral')}>
          {u.role}
        </span>
      ),
    },
    { key: 'source', title: '来源', render: (u) => <span className="small">{u.source}</span> },
    { key: 'last', title: '最近登录', render: (u) => <span className="mono small">{u.lastLogin}</span> },
    {
      key: 'enabled',
      title: '状态',
      render: (u) => <StatusDot tone={u.enabled ? 'ok' : 'neutral'}>{u.enabled ? '启用' : '已停用'}</StatusDot>,
    },
    {
      key: 'op',
      title: '操作',
      render: (u) => (
        <div className="row" style={{ gap: 10 }}>
          <button
            className={cls('btn-link', u.enabled && 'danger')}
            disabled={!can('USER_MANAGE')}
            onClick={() => void toggleUser(u)}
          >
            {u.enabled ? '停用' : '启用'}
          </button>
          <button className="btn-link" disabled={!can('USER_MANAGE')} onClick={() => setResetTarget(u)}>
            重置口令
          </button>
        </div>
      ),
    },
  ];

  return (
    <div>
      <div className="page-head">
        <div className="page-title">
          <h1>系统设置</h1>
          <span className="page-sub">
            当前账号 <span className="mono">{session?.username}</span> · 角色 {session?.role} · 持 {session?.permissions.length ?? 0} 个权限点
          </span>
        </div>
        <div className="page-actions">
          <button
            className="btn"
            disabled={!can('USER_MANAGE')}
            title={can('USER_MANAGE') ? undefined : '需要权限：USER_MANAGE'}
            onClick={() => void downloadFile(EP.sysAuditExport(), 'audit.csv')}
          >
            导出审计
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

      <div className="tabs">
        {TABS.map((t) => (
          <button key={t} className={cls('tab', tab === t && 'active')} onClick={() => setTab(t)}>
            {t}
          </button>
        ))}
      </div>

      {tab === '审计日志' ? (
        <div className="card">
          <div className="filter-bar">
            <span className="filter-label">筛选</span>
            <input
              className="input"
              placeholder="操作人（username）"
              value={actor}
              onChange={(e) => setActor(e.target.value)}
              style={{ width: 160 }}
            />
            <select className="select" value={action} onChange={(e) => setAction(e.target.value)} style={{ width: 220 }}>
              <option value="">全部动作</option>
              {ACTION_OPTIONS.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
            </select>
            <select className="select" value={result} onChange={(e) => setResult(e.target.value)} style={{ width: 120 }}>
              <option value="ALL">全部结果</option>
              <option value="SUCCESS">成功</option>
              <option value="FAILED">失败</option>
              <option value="DENIED">被拒绝</option>
            </select>
            <button className="btn btn-sm" onClick={() => auditsQ.reload()}>
              查询
            </button>
            <span className="small faint" style={{ marginLeft: 'auto' }}>
              共 {audits.length} 条 · 保留 180 天 · 服务端过滤 user/action
            </span>
          </div>
          <DataTable
            columns={auditColumns}
            rows={audits}
            rowKey={(a) => a.id}
            loading={auditsQ.loading}
            compact
            minWidth={1100}
            emptyText="没有匹配的审计记录"
          />
        </div>
      ) : null}

      {tab === '用户与权限' ? (
        <div className="col" style={{ gap: 14 }}>
          <div className="card">
            <div className="card-head">
              <div className="card-title">用户</div>
              <span className="small faint">GET /api/system/users · 启停与重置口令需 USER_MANAGE</span>
            </div>
            <div className="card-body flush">
              <DataTable columns={userColumns} rows={userList} rowKey={(u) => u.id} loading={usersQ.loading} compact minWidth={900} />
            </div>
          </div>

          <div className="card">
            <div className="card-head">
              <div className="card-title">权限点（后端枚举 12 个）</div>
              <span className="small faint">按钮置灰依据 permissions 数组，不按角色推导</span>
            </div>
            <div className="card-body flush">
              <div className="table-wrap">
                <table className="tbl compact" style={{ minWidth: 720 }}>
                  <thead>
                    <tr>
                      <th style={{ width: 200 }}>权限点</th>
                      <th>说明</th>
                      <th style={{ width: 90 }} className="center">
                        风险
                      </th>
                      <th style={{ width: 120 }} className="center">
                        当前账号
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(PERM_META).map(([perm, meta]) => (
                      <tr key={perm}>
                        <td className="mono">{perm}</td>
                        <td className="small dim">{meta.desc}</td>
                        <td className="center">
                          {meta.danger ? <span className="badge danger">高危</span> : <span className="faint">—</span>}
                        </td>
                        <td className="center">
                          {can(perm) ? (
                            <span style={{ color: 'var(--ok)' }}>✓ 有</span>
                          ) : (
                            <span className="faint">未授予</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
            <div className="card-body small faint" style={{ borderTop: '1px solid var(--line)' }}>
              12 个权限点由后端 <span className="mono">@RequiresPermission</span> 拦截器校验（RbacInterceptor）：
              403 + E8001 表示权限不足（不跳页），401 + E8004 表示 token 失效（清 token 回登录页）。
            </div>
          </div>
        </div>
      ) : null}

      {tab === '告警通道' ? (
        <div className="col" style={{ gap: 14 }}>
          <div className="alert info">
            数据来自 <span className="mono">GET /api/system/alerts</span>：启用通道 + 告警规则 + 最近告警。
            规则阈值在代码/配置中定义（<span className="mono">editable:false</span>），控制台只读，不支持在线修改。
          </div>

          <div className="card">
            <div className="card-head">
              <div className="card-title">
                启用通道
                <span className="hint">共 {alerts.channels.length} 个</span>
              </div>
              <button
                className="btn btn-sm"
                onClick={() => void sendTest()}
                disabled={!can('USER_MANAGE')}
                title={can('USER_MANAGE') ? undefined : '需要权限：USER_MANAGE'}
              >
                发送测试告警
              </button>
            </div>
            <div className="card-body">
              {alerts.channels.length === 0 ? (
                <div className="state">
                  <div className="icon">◌</div>
                  <div className="title">未启用任何告警通道</div>
                  <div>请在服务端 application.yml 的 dbsync.alert 下配置通道后重启</div>
                </div>
              ) : (
                <div className="row wrap" style={{ gap: 10 }}>
                  {alerts.channels.map((c, i) => (
                    <span key={`${channelLabel(c)}-${i}`} className="badge accent" style={{ height: 22, padding: '0 10px' }}>
                      <i className="dot ok" />
                      {channelLabel(c)}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="card">
            <div className="card-head">
              <div className="card-title">
                告警规则
                <span className="hint">{alerts.rules.length} 条 · 后端代码定义</span>
              </div>
              <span className="small faint">单条规则可发测试告警，规则本身不可编辑</span>
            </div>
            <div className="card-body flush">
              <table className="tbl compact" style={{ minWidth: 720 }}>
                <thead>
                  <tr>
                    <th style={{ width: 190 }}>规则编码</th>
                    <th>说明</th>
                    <th style={{ width: 96 }} className="center">
                      可编辑
                    </th>
                    <th style={{ width: 110 }} className="center">
                      操作
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {alerts.rules.map((r) => (
                    <tr key={r.code}>
                      <td className="mono cell-strong">{r.code}</td>
                      <td className="small dim">{r.description}</td>
                      <td className="center">
                        {r.editable ? (
                          <span className="badge warn">可编辑</span>
                        ) : (
                          <span className="badge neutral">只读</span>
                        )}
                      </td>
                      <td className="center">
                        <button
                          className="btn-link"
                          onClick={() => void sendTest(r.code)}
                          disabled={!can('USER_MANAGE')}
                          title={can('USER_MANAGE') ? undefined : '需要权限：USER_MANAGE'}
                        >
                          测试
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="card">
            <div className="card-head">
              <div className="card-title">
                最近告警
                <span className="hint">{alerts.recent.length} 条</span>
              </div>
              <span className="small faint">含触发规则与投递通道</span>
            </div>
            <div className="card-body flush">
              <DataTable<RecentAlertItem>
                columns={[
                  { key: 'firedAt', title: '时间', render: (r) => <span className="mono small">{r.firedAt ?? '—'}</span> },
                  { key: 'rule', title: '规则', render: (r) => <span className="badge warn mono">{r.rule}</span> },
                  {
                    key: 'title',
                    title: '标题 / 详情',
                    render: (r) => (
                      <div>
                        <div className="dim">{r.title}</div>
                        {r.detail ? <div className="small faint">{r.detail}</div> : null}
                      </div>
                    ),
                  },
                  {
                    key: 'channels',
                    title: '通道',
                    render: (r) => (
                      <div className="row wrap" style={{ gap: 4 }}>
                        {(r.channels ?? []).map((c) => (
                          <span key={c} className="badge neutral">
                            {CHANNEL_TEXT[c] ?? c}
                          </span>
                        ))}
                      </div>
                    ),
                  },
                ]}
                rows={alerts.recent}
                rowKey={(r) => `${r.rule}-${r.firedAt ?? ''}-${r.title}`}
                loading={alertsQ.loading}
                compact
                minWidth={880}
                emptyText="暂无告警记录"
              />
            </div>
          </div>
        </div>
      ) : null}

      <ConfirmDialog
        open={resetTarget !== null}
        title="重置用户口令"
        confirmText={resetTarget?.account ?? ''}
        confirmLabel="确认重置"
        onCancel={() => setResetTarget(null)}
        onConfirm={() => {
          if (resetTarget) void resetPassword(resetTarget);
        }}
      >
        <div className="alert danger">
          将把 <b className="mono">{resetTarget?.account}</b> 的口令重置为初始口令，该账号下次登录会被强制改密；操作落审计。
        </div>
      </ConfirmDialog>
    </div>
  );
}
