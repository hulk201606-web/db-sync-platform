import { useMemo, useState } from 'react';
import { EP } from '../api/endpoints';
import { http } from '../api/client';
import { useQuery } from '../api/useQuery';
import type { TableProgressItem, TableProgressView } from '../types';
import { DataTable, type Column } from './DataTable';
import { ProgressBar } from './ProgressBar';
import { cls, fmtCount, fmtNum } from '../utils/format';

type SortKey = 'percent' | 'name';

interface TableProgressTableProps {
  taskId: string;
  /** 打开死信抽屉（按任务下钻） */
  onViewDead?: (item: TableProgressItem) => void;
  /** 外部刷新信号，变化即重新拉取 */
  refreshToken?: number;
}

/**
 * 表级进度明细（PRD §9.3）：GET /api/tasks/{taskId}/tables/progress。
 * percent 为 null 时显示「—」而不是 0%，避免运维误以为卡住。
 */
export function TableProgressTable({ taskId, onViewDead, refreshToken = 0 }: TableProgressTableProps) {
  const q = useQuery<TableProgressView>(`tables:${taskId}:${refreshToken}`, () =>
    http.get<TableProgressView>(EP.taskTablesProgress(taskId)),
  );
  const [sortKey, setSortKey] = useState<SortKey>('percent');

  const items = useMemo(() => {
    const list = [...(q.data?.items ?? [])];
    list.sort((a, b) => {
      if (sortKey === 'name') return a.sourceTable.localeCompare(b.sourceTable, 'zh-Hans-CN');
      // 进度升序：null（源端行数未知）排最后
      const av = a.percent === null ? Number.POSITIVE_INFINITY : a.percent;
      const bv = b.percent === null ? Number.POSITIVE_INFINITY : b.percent;
      if (av !== bv) return av - bv;
      return a.sourceTable.localeCompare(b.sourceTable, 'zh-Hans-CN');
    });
    return list;
  }, [q.data, sortKey]);

  const columns: Column<TableProgressItem>[] = [
    {
      key: 'table',
      title: '表名',
      render: (t) => (
        <div>
          <div className="cell-strong mono">{t.sourceTable}</div>
          <div className="small faint mono">
            → {t.targetTable} <span className="faint">· 映射 {t.mappingId}</span>
          </div>
        </div>
      ),
    },
    {
      key: 'shards',
      title: '分片（成功/总数）',
      align: 'right',
      render: (t) => (
        <span className="mono small">
          <span style={{ color: t.successShards === t.totalShards ? 'var(--ok)' : 'var(--text)' }}>
            {fmtNum(t.successShards)}
          </span>
          <span className="faint"> / {fmtNum(t.totalShards)}</span>
        </span>
      ),
    },
    {
      key: 'rows',
      title: '已迁移行数',
      align: 'right',
      render: (t) => (
        <div>
          <div className="mono">
            {fmtCount(t.committedRows)}
            {t.rowsFromEstimate ? (
              <span
                className="badge neutral"
                style={{ marginLeft: 6, fontSize: 10, padding: '0 4px' }}
                title="源端行数取自上报表估算，非实测"
              >
                估算
              </span>
            ) : null}
          </div>
          {t.sourceRows > 0 ? (
            <div className="small faint mono">/ {fmtCount(t.sourceRows)}</div>
          ) : (
            <div className="small faint">源端行数未知</div>
          )}
        </div>
      ),
    },
    {
      key: 'percent',
      title: '进度',
      width: 190,
      render: (t) =>
        t.percent === null ? (
          <span className="faint" title="源端行数未知">
            —
          </span>
        ) : (
          <ProgressBar
            value={t.percent}
            showPct
            tone={t.failedShards + t.deadShards > 0 ? 'danger' : t.percent >= 100 ? 'ok' : 'accent'}
          />
        ),
    },
    {
      key: 'failed',
      title: '失败 / 死信',
      align: 'right',
      render: (t) => (
        <span className="mono small">
          <span style={{ color: t.failedShards > 0 ? 'var(--danger)' : 'var(--faint)' }}>{fmtNum(t.failedShards)}</span>
          <span className="faint"> / </span>
          <span style={{ color: t.deadShards > 0 ? 'var(--danger)' : 'var(--faint)' }}>{fmtNum(t.deadShards)}</span>
        </span>
      ),
    },
    {
      key: 'op',
      title: '操作',
      render: (t) =>
        t.failedShards + t.deadShards > 0 ? (
          <button className="btn-link" onClick={() => onViewDead?.(t)} disabled={!onViewDead}>
            查看死信
          </button>
        ) : (
          <span className="faint small">—</span>
        ),
    },
  ];

  return (
    <div>
      <div className="row wrap" style={{ gap: 8, marginBottom: 8 }}>
        <span className="small faint">排序</span>
        <button
          className={cls('btn btn-sm', sortKey === 'percent' && 'btn-primary')}
          onClick={() => setSortKey('percent')}
        >
          按进度升序
        </button>
        <button className={cls('btn btn-sm', sortKey === 'name' && 'btn-primary')} onClick={() => setSortKey('name')}>
          按表名
        </button>
        <span className="small faint" style={{ marginLeft: 'auto' }}>
          {fmtNum(items.length)} 张表 · 「—」表示源端行数未知，不代表 0%
        </span>
      </div>

      {q.error ? (
        <div className="alert warn" style={{ marginBottom: 8 }}>
          <span>表级进度接口异常：{q.error}</span>
          <button className="btn-link" style={{ marginLeft: 'auto' }} onClick={q.reload}>
            重试
          </button>
        </div>
      ) : null}

      <DataTable
        columns={columns}
        rows={items}
        rowKey={(t) => String(t.mappingId)}
        loading={q.loading}
        compact
        minWidth={880}
        emptyText="该任务暂无表级进度"
        emptyHint="任务启动并生成分片后才会产生表级统计"
      />
    </div>
  );
}
