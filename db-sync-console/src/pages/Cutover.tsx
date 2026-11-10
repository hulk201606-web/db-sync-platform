import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { EP } from '../api/endpoints';
import { ApiError, http } from '../api/client';
import { useQuery } from '../api/useQuery';
import { needPermText, useCan } from '../api/auth';
import { errorText } from '../api/errors';
import type { CutoverPlan, CutoverState } from '../types';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Modal } from '../components/Modal';
import { StatusDot } from '../components/StatusDot';
import { DataTable } from '../components/DataTable';
import { CUTOVER_STATE_TEXT, cls } from '../utils/format';

/** 与后端 CutoverState.java 一致的 9 状态（主链 7 步 + 预检失败分支 + 回滚终态） */
const FLOW: CutoverState[] = [
  'DRAFT',
  'PRE_CHECK',
  'PRE_CHECK_FAILED',
  'GRAY_READ',
  'GRAY_WRITE',
  'FULL_TARGET',
  'OBSERVE',
  'DONE',
  'ROLLED_BACK',
];

/** 按钮文案与后端 forwardTargets() 实际跃迁一致；DONE / ROLLED_BACK 为终态不配置 */
const NEXT_LABEL: Record<string, string> = {
  DRAFT: '推进到 PRE_CHECK',
  PRE_CHECK: '推进到 GRAY_READ',
  PRE_CHECK_FAILED: '重新预检（PRE_CHECK）',
  GRAY_READ: '推进到 GRAY_WRITE',
  GRAY_WRITE: '推进到 FULL_TARGET',
  FULL_TARGET: '进入观察期（OBSERVE）',
  OBSERVE: '完成切流（DONE）',
};

function gateText(g: { actual: number; unit: string; key: string }): string {
  if (g.key === 'passRate' || g.key === 'failRate') return `${g.actual}%`;
  if (g.key === 'latencyP95') return `${g.actual}s`;
  return `${g.actual}${g.unit === '条' ? '' : g.unit}`;
}

/** 计划 DTO 容错：后端字段缺失时给安全默认值，避免出现 undefined 文案 */
function normalizePlan(p: CutoverPlan | null): CutoverPlan | null {
  if (!p) return null;
  return {
    ...p,
    planName: p.planName ?? p.id,
    state: (p.state ?? 'DRAFT') as CutoverState,
    gates: p.gates ?? [],
    timeline: p.timeline ?? [],
    grayDimensions: p.grayDimensions ?? [],
    grayTables: p.grayTables ?? [],
    gateSkipped: p.gateSkipped ?? [],
    matrix:
      p.matrix ?? { readSourcePct: 100, readTargetPct: 0, writeSource: true, writeTarget: false, writeDual: false },
  };
}

export function Cutover() {
  const { id = '' } = useParams();
  const can = useCan();
  const q = useQuery(`cutover:${id}`, () => http.get<CutoverPlan>(EP.cutoverPlan(id)));
  const [live, setLive] = useState<CutoverPlan | null>(null);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'warn' | 'danger' | 'info'; text: string } | null>(null);
  const [advanceOpen, setAdvanceOpen] = useState(false);
  const [skipOpen, setSkipOpen] = useState(false);
  const [rollbackOpen, setRollbackOpen] = useState(false);
  const [dryOpen, setDryOpen] = useState(false);
  const [drySteps, setDrySteps] = useState<Array<{ step: string; gate: string; expected: string }>>([]);
  const [grayPct, setGrayPct] = useState<number>(50);
  const [hashPct, setHashPct] = useState('100');
  const [pending, setPending] = useState(false);
  const [allowed, setAllowed] = useState<string[]>([]);

  const plan = normalizePlan(live ?? q.data);
  const curIndex = plan ? FLOW.indexOf(plan.state) : -1;
  const gates = plan?.gates ?? [];
  const gatesPass = gates.length > 0 && gates.every((g) => g.pass);
  const rolledBack = plan?.state === 'ROLLED_BACK';
  const done = plan?.state === 'DONE';
  const terminal = rolledBack || done;

  function appendTimeline(p: CutoverPlan, action: string, detail: string, level: 'info' | 'warn' | 'danger'): CutoverPlan {
    const now = new Date();
    const at = `2026-11-09 ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(
      now.getSeconds(),
    ).padStart(2, '0')}`;
    return { ...p, timeline: [{ at, actor: 'alice', action, detail, level }, ...(p.timeline ?? [])] };
  }

  function showError(err: unknown): void {
    if (err instanceof ApiError) {
      const data = err.data as { allowed?: string[]; current?: string; requested?: string } | undefined;
      if (data?.allowed && data.allowed.length > 0) {
        setAllowed(data.allowed);
        setNotice({
          tone: 'danger',
          text: `非法跃迁：当前 ${data.current ?? plan?.state} 不能直接到 ${data.requested ?? '未知'}，允许的目标：${data.allowed.join(' / ')} · traceId ${
            err.traceLocal ? '本地 ' : ''
          }${err.traceId}`,
        });
        return;
      }
      setAllowed([]);
      setNotice({ tone: 'danger', text: errorText(err) });
      return;
    }
    setAllowed([]);
    setNotice({ tone: 'danger', text: errorText(err) });
  }

  async function recheck(): Promise<void> {
    if (!plan) return;
    setPending(true);
    try {
      const res = await http.post<CutoverPlan>(EP.cutoverGate(id));
      setLive(appendTimeline({ ...plan, gates: res.gates ?? plan.gates }, '卡点校验', '重新拉取卡点实测值', 'info'));
      setNotice({ tone: 'ok', text: '卡点已重新校验（实测值见卡片）' });
    } catch (err) {
      showError(err);
    } finally {
      setPending(false);
    }
  }

  async function advance(): Promise<void> {
    if (!plan) return;
    setPending(true);
    try {
      const res = await http.post<CutoverPlan>(EP.cutoverAdvance(id));
      const next = (res.state as CutoverState) ?? plan.state;
      setLive(appendTimeline({ ...plan, state: next }, '推进阶段', `${plan.state} → ${next}`, 'warn'));
      setAdvanceOpen(false);
      setAllowed([]);
      setNotice({ tone: 'ok', text: `已推进到 ${next}（${CUTOVER_STATE_TEXT[next] ?? ''}），变更已广播给 SDK 客户端，≤ 3 秒生效` });
    } catch (err) {
      setAdvanceOpen(false);
      showError(err);
    } finally {
      setPending(false);
    }
  }

  async function skipGate(p: { reason: string; confirmText: string }): Promise<void> {
    if (!plan) return;
    setPending(true);
    try {
      await http.post(EP.cutoverSkipGate(id), {
        reason: p.reason,
        confirmText: p.confirmText,
        secondConfirmUser: 'bob',
      });
      setLive(
        appendTimeline(
          { ...plan, gateSkipped: [...(plan.gateSkipped ?? []), 'gate'] },
          '跳过卡点',
          `ADMIN alice 跳过卡点，理由：${p.reason}（第二复核人 bob）`,
          'danger',
        ),
      );
      setSkipOpen(false);
      setNotice({ tone: 'warn', text: '已跳过卡点并落审计（含全部实测值），该操作会在评审中被重点检查' });
    } catch (err) {
      setSkipOpen(false);
      showError(err);
    } finally {
      setPending(false);
    }
  }

  async function rollback(p: { reason: string; confirmText: string }): Promise<void> {
    if (!plan) return;
    setPending(true);
    try {
      await http.post(EP.cutoverRollback(id), { reason: p.reason, confirmText: p.confirmText });
      setLive(
        appendTimeline({ ...plan, state: 'ROLLED_BACK' }, '一键回滚', `读流量全部切回源库，冻结目标端写入。原因：${p.reason}`, 'danger'),
      );
      setRollbackOpen(false);
      setNotice({ tone: 'danger', text: '已回滚：读流量全部回到源库，目标端写入已冻结，事件记录已生成' });
    } catch (err) {
      setRollbackOpen(false);
      showError(err);
    } finally {
      setPending(false);
    }
  }

  async function applyGray(): Promise<void> {
    if (!plan) return;
    setPending(true);
    try {
      await http.post(EP.cutoverGray(id), { grayPct, hashPct: Number(hashPct) });
      setLive(appendTimeline({ ...plan, grayPct }, '灰度放量', `读流量灰度 → ${grayPct}%（主键哈希 ${hashPct}%）`, 'info'));
      setNotice({ tone: 'ok', text: `灰度已调整为 ${grayPct}%：同一主键在比例不变时路由稳定，不抖动` });
    } catch (err) {
      showError(err);
    } finally {
      setPending(false);
    }
  }

  async function dryRun(): Promise<void> {
    setPending(true);
    try {
      const res = await http.post<{ steps: Array<{ step: string; gate: string; expected: string }>; estimatedMinutes: number }>(
        EP.cutoverDryRun(id),
      );
      setDrySteps(res.steps);
      setDryOpen(true);
    } catch (err) {
      showError(err);
    } finally {
      setPending(false);
    }
  }

  if (!plan) {
    return (
      <div className="card">
        <div className="card-body">
          <div className="skeleton" style={{ height: 26, width: '45%' }} />
          <div className="skeleton" style={{ height: 12, width: '75%', marginTop: 14 }} />
          <div className="skeleton" style={{ height: 90, marginTop: 14 }} />
        </div>
      </div>
    );
  }

  return (
    <div>
      <div className="page-head">
        <div className="page-title">
          <h1>切流控制台</h1>
          <span className="page-sub mono">
            {plan.sourceName}（{plan.sourceType}）→ {plan.targetName}（{plan.targetType}）
          </span>
          <span className="badge neutral mono">{plan.id}</span>
          <StatusDot tone={rolledBack ? 'danger' : done ? 'ok' : 'accent'} pulse={!terminal}>
            {CUTOVER_STATE_TEXT[plan.state] ?? plan.state}
          </StatusDot>
        </div>
        <div className="page-actions">
          <button className="btn" onClick={() => void dryRun()} disabled={pending}>
            dry-run 演练
          </button>
          <button
            className="btn btn-danger-outline"
            onClick={() => setRollbackOpen(true)}
            disabled={terminal || !can('CUTOVER_ROLLBACK')}
            title={can('CUTOVER_ROLLBACK') ? undefined : needPermText('CUTOVER_ROLLBACK')}
          >
            一键回滚（≤30s）
          </button>
        </div>
      </div>

      {notice ? (
        <div className={cls('alert', notice.tone)} style={{ marginBottom: 12 }}>
          <span>{notice.text}</span>
          {allowed.length > 0 ? (
            <span className="row" style={{ gap: 6, marginLeft: 10 }}>
              <span className="small">允许目标：</span>
              {allowed.map((s) => (
                <span key={s} className="badge warn mono">
                  {s}
                </span>
              ))}
            </span>
          ) : null}
          <button className="btn-link" style={{ marginLeft: 'auto' }} onClick={() => setNotice(null)}>
            关闭
          </button>
        </div>
      ) : null}

      <div className="card">
        <div className="card-head">
          <div className="card-title">状态机</div>
          <span className="small faint">
            当前 {plan.state} · 已持续 {plan.stateElapsed ?? '—'} · 进入时间 {plan.stateSince ?? '—'}
          </span>
        </div>
        <div className="card-body">
          <div className="stateflow">
            {FLOW.map((s, i) => {
              const active = plan.state === s;
              const passed = !terminal && curIndex >= 0 && i < curIndex;
              return (
                <div key={s} className={cls('sf-node', passed && 'done', active && 'active')}>
                  <span className="sf-code">{s}</span>
                  <span style={{ fontSize: 10.5 }}>{CUTOVER_STATE_TEXT[s]}</span>
                </div>
              );
            })}
          </div>
          {rolledBack ? (
            <div className="alert danger mt12">计划已回滚（ROLLED_BACK）：读流量全部回源，目标端写入冻结；如需继续需新建计划。</div>
          ) : null}
          {plan.state === 'PRE_CHECK_FAILED' ? (
            <div className="alert warn mt12">
              预检不通过（PRE_CHECK_FAILED）：请修复卡点后重新预检，或由 ADMIN 跳过卡点后再推进。
            </div>
          ) : null}
          <div className="row wrap mt12" style={{ gap: 26 }}>
            <div>
              <div className="small faint">计划名称</div>
              <div className="dim">{plan.planName}</div>
            </div>
            <div>
              <div className="small faint">关联任务</div>
              <div className="mono dim">{plan.taskId}</div>
            </div>
            <div>
              <div className="small faint">计划所有者</div>
              <div className="mono dim">{plan.adminUser ?? '—'}</div>
            </div>
            <div>
              <div className="small faint">观察期 / 自动回滚</div>
              <div className="mono dim">
                观察期 {plan.observeWindowH ?? '—'}h · 自动回滚
                <span className="badge warn" style={{ marginLeft: 6 }}>
                  未启用（P1 规划，CUT-06 后端未实现）
                </span>
              </div>
            </div>
          </div>
          <div className="small faint mt8">
            状态以 <span className="mono">GET /api/cutover/plans/{'{id}'}</span> 返回的实际字符串为准，前端不硬编码序号配色。
          </div>
        </div>
      </div>

      <div className="card mt16">
        <div className="card-head">
          <div className="card-title">读写开关矩阵</div>
          <span className="small faint">不可跳步前进；回滚可跳步</span>
        </div>
        <div className="card-body flush">
          <table className="tbl compact">
            <thead>
              <tr>
                <th style={{ width: 140 }}>阶段</th>
                <th className="center">读源</th>
                <th className="center">读目标</th>
                <th className="center">写源</th>
                <th className="center">写目标</th>
                <th className="center">写双</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className="cell-strong">当前（{CUTOVER_STATE_TEXT[plan.state] ?? plan.state}）</td>
                <td className="center mono">{plan.matrix?.readSourcePct ?? '—'}%</td>
                <td className="center mono" style={{ color: 'var(--accent)' }}>
                  {plan.matrix?.readTargetPct ?? '—'}%
                </td>
                <td className="center">{plan.matrix?.writeSource ? <span style={{ color: 'var(--ok)' }}>✓</span> : '—'}</td>
                <td className="center">{plan.matrix?.writeTarget ? <span style={{ color: 'var(--ok)' }}>✓</span> : '—'}</td>
                <td className="center">
                  {plan.matrix?.writeDual ? <span style={{ color: 'var(--ok)' }}>✓（灰度 {plan.grayPct}%）</span> : '—'}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div className="card mt16">
        <div className="card-head">
          <div className="card-title">
            卡点校验（Gate）
            <span className="hint">实测值 / 阈值 / 是否通过 三列并排</span>
          </div>
          <div className="row" style={{ gap: 8 }}>
            <button className="btn btn-sm" onClick={() => void recheck()} disabled={pending}>
              重新校验
            </button>
            <button
              className="btn btn-sm btn-danger-outline"
              onClick={() => setSkipOpen(true)}
              disabled={terminal || !can('SKIP_GATE')}
              title={can('SKIP_GATE') ? undefined : needPermText('SKIP_GATE')}
            >
              跳过卡点（ADMIN）
            </button>
          </div>
        </div>
        <div className="card-body">
          <div className="gate-grid">
            {gates.map((g) => (
              <div key={g.key} className={cls('gate', g.pass ? 'pass' : 'block')}>
                <div className="gate-name">
                  <span>{g.name}</span>
                  <span className={cls('badge', g.pass ? 'ok' : 'danger')}>{g.pass ? '✓ 达标' : '✕ 未达标'}</span>
                </div>
                <div className="gate-value" style={{ color: g.pass ? 'var(--ok)' : 'var(--danger)' }}>
                  {gateText(g)}
                  <span className="gate-thresh" style={{ color: 'var(--faint)' }}>
                    {g.compare} {g.threshold}
                    {g.key === 'deadLetters' ? '' : g.unit}
                  </span>
                </div>
                <div className="small faint">{g.evidence}</div>
                <div className="small faint mono">{g.at}</div>
              </div>
            ))}
          </div>
          {gates.length === 0 ? (
            <div className="state">
              <div className="icon">◌</div>
              <div className="title">尚未执行卡点校验</div>
              <div>点击「重新校验」拉取实测值</div>
            </div>
          ) : !gatesPass ? (
            <div className="alert danger mt12">存在未达标卡点：推进按钮已置灰，如需强行跳过必须 ADMIN + 理由 ≥10 字 + 双人复核。</div>
          ) : (
            <div className="alert ok mt12">全部卡点达标，可推进到下一阶段。</div>
          )}
        </div>
      </div>

      <div className="card mt16">
        <div className="card-head">
          <div className="card-title">灰度放量</div>
          <span className="small faint">维度：{(plan.grayDimensions ?? []).join(' + ') || '—'}</span>
        </div>
        <div className="card-body">
          <div className="row wrap" style={{ gap: 16 }}>
            <div className="field" style={{ minWidth: 230 }}>
              <label>读流量比例（滑杆）</label>
              <div className="row" style={{ gap: 10 }}>
                <input
                  type="range"
                  min={1}
                  max={100}
                  value={grayPct}
                  onChange={(e) => setGrayPct(Number(e.target.value))}
                  style={{ accentColor: 'var(--accent)', width: 150 }}
                  aria-label="灰度比例"
                />
                <span className="mono" style={{ color: 'var(--accent)' }}>
                  {grayPct}%
                </span>
              </div>
            </div>
            <div className="field">
              <label>快捷比例</label>
              <div className="pill-group">
                {[1, 5, 10, 50, 100].map((p) => (
                  <button key={p} className={grayPct === p ? 'active' : ''} onClick={() => setGrayPct(p)}>
                    {p}%
                  </button>
                ))}
              </div>
            </div>
            <div className="field">
              <label>主键哈希桶（hash(pk) % 100 &lt; n）</label>
              <div className="row">
                <input className="input mono" value={hashPct} onChange={(e) => setHashPct(e.target.value)} style={{ width: 90 }} />
                <span className="small faint">%</span>
              </div>
            </div>
            <div className="spacer" />
            <div className="field" style={{ justifyContent: 'flex-end' }}>
              <label>&nbsp;</label>
              <button
                className="btn btn-primary"
                onClick={() => void applyGray()}
                disabled={pending || terminal || !can('CUTOVER_ADVANCE')}
                title={can('CUTOVER_ADVANCE') ? undefined : needPermText('CUTOVER_ADVANCE')}
              >
                应用
              </button>
            </div>
          </div>
          <div className="bar mt12">
            <i style={{ width: `${plan.grayPct}%` }} />
          </div>
          <div className="small faint mt8">当前灰度 {plan.grayPct}%（读目标 {plan.matrix?.readTargetPct ?? '—'}%）</div>
        </div>
      </div>

      <div className="card mt16">
        <div className="card-head">
          <div className="card-title">操作区</div>
          <span className="small faint">所有操作落审计并广播 SDK 客户端（≤ 3 秒生效）</span>
        </div>
        <div className="card-body">
          <div className="row wrap" style={{ gap: 10 }}>
            <button
              className="btn btn-primary btn-lg"
              disabled={!gatesPass || terminal || pending || !can('CUTOVER_ADVANCE')}
              onClick={() => setAdvanceOpen(true)}
              title={
                !can('CUTOVER_ADVANCE')
                  ? needPermText('CUTOVER_ADVANCE')
                  : !gatesPass
                    ? '存在未达标卡点，推进已被拦截'
                    : undefined
              }
            >
              {NEXT_LABEL[plan.state] ?? '推进阶段'}
            </button>
            <button
              className="btn btn-lg btn-danger-outline"
              onClick={() => setRollbackOpen(true)}
              disabled={terminal || !can('CUTOVER_ROLLBACK')}
              title={can('CUTOVER_ROLLBACK') ? undefined : needPermText('CUTOVER_ROLLBACK')}
            >
              一键回滚（≤30s）
            </button>
            <button className="btn btn-lg" onClick={() => void dryRun()} disabled={pending}>
              dry-run 演练
            </button>
          </div>
          <div className="alert info mt12">
            推进顺序：DRAFT → PRE_CHECK → GRAY_READ → GRAY_WRITE → FULL_TARGET → OBSERVE → DONE；预检不通过进入
            PRE_CHECK_FAILED，任意非终态可回滚到 ROLLED_BACK。 后端拒绝非法跃迁（E6001，返回 allowed 列表，前端已在提示条中渲染）。
          </div>
        </div>
      </div>

      <div className="card mt16">
        <div className="card-head">
          <div className="card-title">操作时间线（审计）</div>
          <span className="small faint">谁在何时把灰度从 5% 调到 10%，全部可追溯</span>
        </div>
        <div className="card-body">
          {(plan.timeline ?? []).length === 0 ? (
            <div className="state">
              <div className="icon">◷</div>
              <div className="title">暂无操作记录</div>
            </div>
          ) : (
            <div className="timeline">
              {(plan.timeline ?? []).map((t, i) => (
                <div className={cls('tl-item', i === 0 ? 'active' : 'done')} key={`${t.at}-${t.action}-${i}`}>
                  <div className="tl-dot" />
                  <div className="tl-body">
                    <div className="tl-title">
                      <span className="mono small faint">{t.at}</span>
                      <b>{t.actor}</b>
                      <span style={{ color: t.level === 'danger' ? 'var(--danger)' : t.level === 'warn' ? 'var(--warn)' : 'var(--text)' }}>
                        {t.action}
                      </span>
                    </div>
                    <div className="tl-meta">{t.detail}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={advanceOpen}
        title="推进切流阶段"
        confirmText={plan.planName}
        confirmLabel="确认推进"
        danger={false}
        pending={pending}
        onCancel={() => setAdvanceOpen(false)}
        onConfirm={() => void advance()}
      >
        <div className="alert warn">
          将执行 <b>{NEXT_LABEL[plan.state] ?? '推进'}</b>，读写路由立即变更并广播给所有 SDK 客户端（≤ 3 秒生效）。
          当前灰度 {plan.grayPct}%，卡点已全部通过。推进后如需回退，只能走回滚（读流量全回源、冻结目标写入）。
        </div>
      </ConfirmDialog>

      <ConfirmDialog
        open={skipOpen}
        title="跳过卡点（需 ADMIN + 双人复核）"
        confirmText="SKIP-GATE"
        confirmLabel="确认跳过"
        requireReason
        reasonMinLength={10}
        reasonLabel="跳过理由（≥ {n} 字，落审计，含全部实测值）"
        pending={pending}
        onCancel={() => setSkipOpen(false)}
        onConfirm={(p) => void skipGate(p)}
      >
        <div className="alert danger">
          后端校验：SKIP_GATE 权限、理由 ≥ 10 字、确认文本必须为 SKIP-GATE、生产环境需第二人复核（本次附带
          secondConfirmUser=bob）。审计将记录全部卡点实测值。
        </div>
      </ConfirmDialog>

      <ConfirmDialog
        open={rollbackOpen}
        title="一键回滚"
        confirmText={plan.planName}
        confirmLabel="确认回滚"
        requireReason
        reasonMinLength={5}
        reasonLabel="回滚原因（≥ {n} 字，必填）"
        extraCheckbox="我已确认源库连接正常，且源库数据完整"
        pending={pending}
        onCancel={() => setRollbackOpen(false)}
        onConfirm={(p) => void rollback(p)}
      >
        <div className="alert danger">
          将把读流量全部切回 <b>{plan.sourceName}</b>，并冻结 <b>{plan.targetName}</b> 写入。
          影响：100% 读流量；回滚不删除任何数据，目标端已有数据保留。回滚后计划进入 ROLLED_BACK，需新建计划才能再次切流。
        </div>
      </ConfirmDialog>

      <Modal
        open={dryOpen}
        wide
        title="dry-run 演练结果（不改变线上路由）"
        onClose={() => setDryOpen(false)}
        footer={
          <>
            <button className="btn" onClick={() => setDryOpen(false)}>
              关闭
            </button>
          </>
        }
      >
        <div className="alert info mb12">演练与真实切流共用同一套 Gate 逻辑；本次演练未写入任何状态。</div>
        <DataTable
          columns={[
            { key: 'step', title: '阶段', render: (s) => <span className="mono">{s.step}</span> },
            { key: 'gate', title: '校验点', render: (s) => s.gate },
            { key: 'expected', title: '预计耗时', render: (s) => <span className="mono small">{s.expected}</span> },
          ]}
          rows={drySteps}
          rowKey={(s) => s.step}
          compact
          minWidth={620}
        />
      </Modal>
    </div>
  );
}
