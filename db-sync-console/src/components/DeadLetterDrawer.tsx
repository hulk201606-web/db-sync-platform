import { useEffect, useState } from 'react';
import { EP } from '../api/endpoints';
import { http } from '../api/client';
import { needPermText, useCan } from '../api/auth';
import { errorText } from '../api/errors';
import { matchShardDeadLetters } from '../api/adapters';
import type { DeadLetterView, ShardItem } from '../types';
import { ConfirmDialog } from './ConfirmDialog';
import { DataTable, type Column } from './DataTable';
import { Drawer } from './Drawer';
import { fmtNum } from '../utils/format';

export type NoticeTone = 'ok' | 'warn' | 'danger' | 'info';

interface DeadLetterDrawerProps {
  open: boolean;
  taskId: string;
  /** 从热力图点进来的分片：按主键区间过滤死信 */
  shard?: ShardItem | null;
  onClose: () => void;
  onNotice: (text: string, tone: NoticeTone) => void;
}

/**
 * 死信抽屉（复用 GET /api/deadletters?taskId=）。
 * 后端 DeadLetterView 不带 shardId，定位分片时按主键区间匹配，匹配不到则回落到全量并提示。
 */
export function DeadLetterDrawer({ open, taskId, shard, onClose, onNotice }: DeadLetterDrawerProps) {
  const can = useCan();
  const [list, setList] = useState<DeadLetterView[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [discardTarget, setDiscardTarget] = useState<DeadLetterView | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    if (!open || !taskId) return;
    let alive = true;
    setLoading(true);
    setError(null);
    http
      .get<DeadLetterView[]>(EP.deadLetters(taskId))
      .then((rows) => {
        if (alive) setList(Array.isArray(rows) ? rows : []);
      })
      .catch((err) => {
        if (alive) {
          setList([]);
          setError(errorText(err));
        }
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [open, taskId]);

  const matched = shard ? matchShardDeadLetters(list, shard) : list;
  const fallback = shard !== null && shard !== undefined && matched.length === 0 && list.length > 0;
  const rows = fallback ? list : matched;

  async function replay(dl: DeadLetterView): Promise<void> {
    setPending(true);
    try {
      await http.post(EP.deadLetterReplay(dl.id));
      setList((prev) => prev.filter((x) => x.id !== dl.id));
      onNotice(`死信 ${dl.id} 已提交重放，可在分片热力图观察该任务 FAILED 数下降`, 'ok');
    } catch (err) {
      onNotice(errorText(err), 'danger');
    } finally {
      setPending(false);
    }
  }

  async function discard(dl: DeadLetterView, reason: string): Promise<void> {
    setPending(true);
    try {
      await http.post(EP.deadLetterDiscard(dl.id), { reason });
      setList((prev) => prev.filter((x) => x.id !== dl.id));
      setDiscardTarget(null);
      onNotice(`死信 ${dl.id} 已丢弃并记入审计，相关区间数据需人工补迁`, 'warn');
    } catch (err) {
      onNotice(errorText(err), 'danger');
    } finally {
      setPending(false);
    }
  }

  const columns: Column<DeadLetterView>[] = [
    { key: 'id', title: '死信 ID', render: (d) => <span className="mono">{d.id}</span> },
    { key: 'map', title: '映射', render: (d) => <span className="mono small">{d.tableMappingId}</span> },
    { key: 'pk', title: '主键快照', render: (d) => <span className="mono small">{d.pkJson}</span> },
    {
      key: 'err',
      title: '异常',
      render: (d) => (
        <div style={{ maxWidth: 330, whiteSpace: 'normal' }}>
          <span className="badge danger" style={{ marginRight: 6 }}>
            {d.errorCode}
          </span>
          <span className="small">{d.errorMsg}</span>
        </div>
      ),
    },
    { key: 'retry', title: '重试', align: 'right', render: (d) => `${d.retry}/3` },
    { key: 'status', title: '状态', render: (d) => <span className="badge warn">{d.status}</span> },
    {
      key: 'op',
      title: '操作',
      render: (d) => (
        <div className="row" style={{ gap: 10 }}>
          <button
            className="btn-link"
            disabled={pending || !can('DEAD_LETTER_MANAGE')}
            title={can('DEAD_LETTER_MANAGE') ? undefined : needPermText('DEAD_LETTER_MANAGE')}
            onClick={() => void replay(d)}
          >
            重放
          </button>
          <button
            className="btn-link danger"
            disabled={pending || !can('DEAD_LETTER_MANAGE')}
            title={can('DEAD_LETTER_MANAGE') ? undefined : needPermText('DEAD_LETTER_MANAGE')}
            onClick={() => setDiscardTarget(d)}
          >
            丢弃
          </button>
        </div>
      ),
    },
  ];

  return (
    <>
      <Drawer
        open={open}
        onClose={onClose}
        wide
        title="死信队列"
        subtitle={`GET /api/deadletters?taskId=${taskId}`}
        footer={
          <>
            <span className="small faint" style={{ marginRight: 'auto' }}>
              丢弃需填写 ≥5 字理由并落审计
            </span>
            <button className="btn" onClick={onClose}>
              关闭
            </button>
          </>
        }
      >
        {shard ? (
          <div className="alert info" style={{ marginBottom: 10 }}>
            <span>
              已定位分片 <b className="mono">#{shard.shardNo}</b>（主键区间 ({shard.pkStart ?? '—'},{' '}
              {shard.pkEnd ?? '—'}]）· 匹配 {fmtNum(matched.length)} 条死信
              {fallback ? ' · 未匹配到该区间，以下为任务全部死信' : ''}
            </span>
          </div>
        ) : null}

        {error ? (
          <div className="alert danger" style={{ marginBottom: 10 }}>
            死信接口异常：{error}
          </div>
        ) : null}

        {rows.length === 0 && !loading ? (
          <div className="state">
            <div className="icon">✓</div>
            <div className="title">该任务没有待处理死信</div>
            <div>失败分片已全部重放成功，或尚未产生死信</div>
          </div>
        ) : (
          <DataTable columns={columns} rows={rows} rowKey={(d) => d.id} loading={loading} compact minWidth={900} />
        )}

        <div className="alert info mt12">
          死信处置建议：先确认目标端列长度 / 唯一键冲突已修复，再重放；重放仍失败会再次入队且 retry +1。
        </div>
      </Drawer>

      <ConfirmDialog
        open={discardTarget !== null}
        title="丢弃死信"
        confirmText={discardTarget?.id ?? ''}
        confirmLabel="确认丢弃"
        requireReason
        reasonMinLength={5}
        reasonLabel="丢弃理由（≥ {n} 字，落审计）"
        pending={pending}
        onCancel={() => setDiscardTarget(null)}
        onConfirm={(p) => {
          if (discardTarget) void discard(discardTarget, p.reason);
        }}
      >
        <div className="alert danger">
          死信 <b className="mono">{discardTarget?.id}</b>（映射 {discardTarget?.tableMappingId}，主键快照{' '}
          <span className="mono small">{discardTarget?.pkJson}</span>）将被标记 DISCARDED，该区间数据不会写入目标库，需人工补迁。
        </div>
      </ConfirmDialog>
    </>
  );
}
