import type {
  CompareReport,
  DataSource,
  DeadLetterView,
  DiffRow,
  MigrationProgressView,
  MonitorOverview,
  PageResult,
  ShardItem,
  TaskRow,
  TaskView,
  TpsSeries,
} from '../types';

/**
 * 后端 DTO → 视图模型 的唯一转换处。
 * 联调时字段若有出入，只改这个文件。
 */

const SERIES_COLORS = ['#22D3EE', '#818CF8', '#34D399', '#FBBF24', '#F87171'];

export const SHARD_STATE_TEXT: Record<string, string> = {
  SUCCESS: '已完成',
  RUNNING: '运行中',
  CLAIMED: '已认领',
  PENDING: '待运行',
  PAUSED: '已暂停',
  FAILED: '失败',
  DEAD: '死信',
};

export const SHARD_STATE_COLOR: Record<string, string> = {
  SUCCESS: 'rgba(52,211,153,0.75)',
  RUNNING: '#FBBF24',
  CLAIMED: '#FBBF24',
  PENDING: '#26313F',
  PAUSED: '#556170',
  FAILED: '#F87171',
  DEAD: '#F87171',
};

/** 分片状态 → 热力格子的语义配色（PRD §9.3：绿/黄/红/灰/浅灰） */
export function shardCellTone(state: string): 'ok' | 'run' | 'fail' | 'idle' | 'pause' {
  switch (state) {
    case 'SUCCESS':
      return 'ok';
    case 'RUNNING':
    case 'CLAIMED':
      return 'run';
    case 'FAILED':
    case 'DEAD':
      return 'fail';
    case 'PAUSED':
      return 'pause';
    default:
      return 'idle';
  }
}

export function toTaskRow(task: TaskView, dsList: DataSource[], progress?: MigrationProgressView | null): TaskRow {
  const src = dsList.find((d) => d.id === task.sourceDsId);
  const tgt = dsList.find((d) => d.id === task.targetDsId);
  return {
    id: task.id,
    name: task.name,
    sourceName: src?.name ?? task.sourceDsId,
    sourceType: src?.type ?? '-',
    targetName: tgt?.name ?? task.targetDsId,
    targetType: tgt?.type ?? '-',
    state: task.state,
    groups: task.groupCount,
    concurrency: task.concurrency,
    batchSize: task.batchSize,
    shardRows: task.shardRows,
    rateLimitRows: task.rateLimitRows,
    incrementalMode: task.incrementalMode,
    lastError: task.lastError,
    progress: progress ?? null,
  };
}

/** 分片状态合计（overview.shardHeat 的每个任务对象） */
export function sumShardStates(rec: Record<string, number> | undefined): {
  total: number;
  success: number;
  running: number;
  pending: number;
  failed: number;
} {
  const r = rec ?? {};
  const success = r.SUCCESS ?? 0;
  const running = r.RUNNING ?? 0;
  const pending = r.PENDING ?? 0;
  const failed = (r.FAILED ?? 0) + (r.DEAD ?? 0);
  return { total: success + running + pending + failed, success, running, pending, failed };
}

/**
 * TPS 序列：真实后端只给"当前值"，没有时间序列，因此前端按轮询累积。
 * 若 mock 提供了 tpsHistory（仅演示数据），则用它作为初始曲线。
 */
export function buildSeries(
  overview: MonitorOverview,
  rows: TaskRow[],
  accumulated: Record<string, number[]>,
): TpsSeries[] {
  const ids = Object.keys(overview.tps);
  if (ids.length === 0) return [];
  return ids.slice(0, 5).map((id, idx) => {
    const name = rows.find((t) => t.id === id)?.name ?? id;
    const seeded = overview.tpsHistory?.[id];
    const values = seeded && seeded.length > 0 ? seeded : accumulated[id] ?? [];
    return {
      name: name.length > 14 ? `${name.slice(0, 13)}…` : name,
      color: SERIES_COLORS[idx % SERIES_COLORS.length] ?? '#22D3EE',
      values,
    };
  });
}

/**
 * 死信 ↔ 分片定位。
 * 后端 DeadLetterView 只有 pkJson（主键快照）与 tableMappingId，没有 shardId，
 * 因此优先按 pkJson 里的 shardId/shardNo 精确匹配，其次按主键区间是否重叠判断。
 */
export function matchShardDeadLetters(list: DeadLetterView[], shard: ShardItem): DeadLetterView[] {
  const ps = shard.pkStart === null ? NaN : Number(shard.pkStart);
  const pe = shard.pkEnd === null ? NaN : Number(shard.pkEnd);
  return list.filter((d) => {
    if (
      d.pkJson.includes(`"shardId":${shard.shardId}`) ||
      d.pkJson.includes(`"shardNo":${shard.shardNo}`)
    ) {
      return true;
    }
    if (!Number.isFinite(ps) || !Number.isFinite(pe)) return false;
    const nums = d.pkJson.match(/\d+/g);
    if (!nums || nums.length < 2) return false;
    const start = Number(nums[0]);
    const end = Number(nums[1]);
    if (!Number.isFinite(start) || !Number.isFinite(end)) return false;
    // 分片区间为左开右闭 (pkStart, pkEnd]
    return start <= pe && end > ps;
  });
}

export const TASK_STATE_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  READY: '待启动',
  RUNNING: '运行中',
  PAUSED: '已暂停',
  FAILED: '失败',
  FINISHED: '已完成',
  CANCELLED: '已取消',
  ARCHIVED: '已归档',
};

export function stateTone(state: string): 'ok' | 'warn' | 'danger' | 'neutral' | 'accent' {
  switch (state) {
    case 'RUNNING':
      return 'ok';
    case 'PAUSED':
      return 'warn';
    case 'FAILED':
      return 'danger';
    case 'FINISHED':
      return 'accent';
    default:
      return 'neutral';
  }
}

/* ---------------- 比对报告：后端 DTO 未完全定型，做容错归一 ---------------- */

function num(v: unknown, fallback = 0): number {
  const n = typeof v === 'string' ? Number(v) : typeof v === 'number' ? v : NaN;
  return Number.isFinite(n) ? n : fallback;
}

function str(v: unknown, fallback = '—'): string {
  return typeof v === 'string' && v.length > 0 ? v : fallback;
}

function arr<T>(v: unknown): T[] {
  return Array.isArray(v) ? (v as T[]) : [];
}

/** 把后端 report / summary 响应归一成页面需要的形状（缺字段一律降级为 0 / —，不抛错） */
export function adaptReport(raw: unknown): CompareReport & { _raw?: unknown } {
  const r = (raw ?? {}) as Record<string, unknown>;
  const passRate = num(r.passRate ?? r.pass_rate, 0);
  const diffRows = num(r.diffRows ?? r.diff_rows, 0);
  const threshold = num(r.threshold, 0.001);
  const verdictRaw = String(r.verdict ?? '').toUpperCase();
  const verdict: 'PASS' | 'WARN' | 'FAIL' =
    verdictRaw === 'PASS' || verdictRaw === 'WARN' || verdictRaw === 'FAIL'
      ? verdictRaw
      : passRate >= 100 - threshold
        ? 'PASS'
        : diffRows > 0
          ? 'WARN'
          : 'FAIL';

  const distribution = arr<Record<string, unknown>>(r.distribution ?? r.tableDistribution).map((d) => ({
    table: str(d.table ?? d.tableName, '-'),
    rows: num(d.rows ?? d.diffRows ?? d.count),
    blockRate: num(d.blockRate, 0),
  }));

  const byType = (r.diffByType ?? {}) as Record<string, unknown>;
  const ignoreRules = arr<Record<string, unknown>>(r.ignoreRules).map((x) => ({
    name: str(x.name, '-'),
    detail: str(x.detail, ''),
    enabled: Boolean(x.enabled),
  }));

  return {
    jobId: str(r.jobId ?? r.id, '-'),
    taskId: str(r.taskId, '-'),
    taskName: str(r.taskName, '-'),
    verdict,
    passRate,
    comparedRows: num(r.comparedRows ?? r.totalRows),
    totalBlocks: num(r.totalBlocks ?? r.blocks),
    diffBlocks: num(r.diffBlocks),
    diffRows,
    costText: str(r.costText ?? r.cost, '—'),
    operator: str(r.operator ?? r.executor, '—'),
    sourceSnapshotAt: str(r.sourceSnapshotAt ?? r.sourceSnapshot, '—'),
    targetSnapshotAt: str(r.targetSnapshotAt ?? r.targetSnapshot, '—'),
    startedAt: str(r.startedAt, '—'),
    algorithm: str(r.algorithm, '—'),
    threshold,
    ignoreRules,
    distribution,
    diffByType: {
      VALUE_DIFF: num(byType.VALUE_DIFF ?? byType.valueDiff),
      ONLY_SOURCE: num(byType.ONLY_SOURCE ?? byType.onlySource),
      ONLY_TARGET: num(byType.ONLY_TARGET ?? byType.onlyTarget),
    },
  };
}

/** 差异分页：兼容 items / list / records 三种命名 */
export function adaptDiffPage(raw: unknown, page: number, size: number): PageResult<DiffRow> {
  const r = (raw ?? {}) as Record<string, unknown>;
  const items = arr<Record<string, unknown>>(r.items ?? r.list ?? r.records ?? r.rows).map((d, i) => ({
    id: num(d.id, i + 1),
    table: str(d.table ?? d.tableName, '-'),
    pk: str(d.pk ?? d.primaryKey, '-'),
    type: (['VALUE_DIFF', 'ONLY_SOURCE', 'ONLY_TARGET'].includes(String(d.type))
      ? String(d.type)
      : 'VALUE_DIFF') as DiffRow['type'],
    column: d.column === null || d.column === undefined ? null : String(d.column),
    sourceValue: d.sourceValue === null || d.sourceValue === undefined ? null : String(d.sourceValue),
    targetValue: d.targetValue === null || d.targetValue === undefined ? null : String(d.targetValue),
    blockId: str(d.blockId ?? d.block, '-'),
    detectedAt: str(d.detectedAt, '-'),
  }));
  return {
    items,
    total: num(r.total ?? r.totalElements, items.length),
    page: num(r.page, page),
    size: num(r.size, size),
  };
}
