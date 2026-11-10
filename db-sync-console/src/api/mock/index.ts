import type {
  ChangeTrace,
  CompareReport,
  DeadLetterView,
  DiffRow,
  DdlDiffView,
  LimitResult,
  MigrationActionView,
  MigrationProgressView,
  MonitorOverview,
  PageResult,
  PrecheckView,
  ShardPage,
  TableDetail,
  TableMeta,
  TableProgressView,
  TaskView,
  AuthSession,
} from '../../types';
import {
  ALERTS_PAYLOAD,
  AUDITS,
  CDC_LATENCY,
  CHANGES,
  COMPARE_REPORT,
  CUTOVER_PLAN,
  DATASOURCES,
  DEAD_LETTERS,
  DEAD_LETTER_VIEWS,
  DDL_DIFF_T_ORDER,
  LOG_LINES,
  ORACLE_TABLES,
  OVERVIEW,
  PROGRESS_MAP,
  ROLES,
  TABLE_DETAILS,
  TABLE_PROGRESS_VIEWS,
  TASKS,
  TASK_VIEWS,
  USERS,
  buildDiffs,
  clone,
  shardPage,
} from './data';
import { MockError } from './error';

/**
 * 仿真数据解析器。
 * 路径与响应结构完全按后端 Controller 的实际契约编写（见 src/api/endpoints.ts）。
 */

const DIFFS: DiffRow[] = buildDiffs();

const DEMO_ADMIN: AuthSession = {
  token: 'mock-token-admin',
  username: 'admin',
  displayName: 'Administrator',
  role: 'ADMIN',
  permissions: [
    'TASK_VIEW',
    'TASK_EDIT',
    'TASK_EXEC',
    'DS_MANAGE',
    'COMPARE_RUN',
    'COMPARE_FIX_SOURCE',
    'CUTOVER_VIEW',
    'CUTOVER_ADVANCE',
    'CUTOVER_ROLLBACK',
    'SKIP_GATE',
    'DEAD_LETTER_MANAGE',
    'USER_MANAGE',
  ],
  expiresAt: '2026-11-10T01:44:00Z',
  mustChangePassword: true,
};

const DEMO_OPERATOR: AuthSession = {
  ...DEMO_ADMIN,
  token: 'mock-token-operator',
  username: 'bob',
  displayName: 'Bob Liu',
  role: 'OPERATOR',
  permissions: [
    'TASK_VIEW',
    'TASK_EDIT',
    'TASK_EXEC',
    'DS_MANAGE',
    'COMPARE_RUN',
    'CUTOVER_VIEW',
    'DEAD_LETTER_MANAGE',
  ],
  mustChangePassword: false,
};

const DEMO_VIEWER: AuthSession = {
  ...DEMO_ADMIN,
  token: 'mock-token-viewer',
  username: 'dave',
  displayName: 'Dave Zhou',
  role: 'VIEWER',
  permissions: ['TASK_VIEW', 'CUTOVER_VIEW'],
  mustChangePassword: false,
};

function qs(path: string, key: string): string {
  const idx = path.indexOf('?');
  if (idx < 0) return '';
  return new URLSearchParams(path.slice(idx + 1)).get(key) ?? '';
}

function segments(path: string): string[] {
  const clean = path.split('?')[0] ?? '';
  return clean.split('/').filter(Boolean);
}

function findTable(id: string, name: string): TableDetail {
  const known = TABLE_DETAILS[name];
  if (known) return known;
  const meta = ORACLE_TABLES.find((t) => t.name === name) ?? (ORACLE_TABLES[0] as TableMeta);
  return {
    ...meta,
    columnsDetail: [
      { name: 'ID', sourceType: 'NUMBER(19)', targetType: 'DECIMAL(19,0)', nullable: false },
      { name: 'BIZ_NO', sourceType: 'VARCHAR2(32)', targetType: 'VARCHAR(32)', nullable: true },
      { name: 'AMT', sourceType: 'NUMBER', targetType: 'DECIMAL(38,10)', nullable: true, degrading: true },
      { name: 'C_TIME', sourceType: 'DATE', targetType: 'TIMESTAMP(0)', nullable: false },
      { name: 'MEMO', sourceType: 'VARCHAR2(512)', targetType: 'VARCHAR(1024)', nullable: true },
    ],
    indexes: [
      { name: `PK_${name}`, kind: 'PK', columns: meta.pk ?? '-' },
      { name: `IDX_${name}_C_TIME`, kind: 'IDX', columns: 'C_TIME', targetMissing: true },
    ],
    ddl: `-- 源 ${id} / 表 ${name} → DM8
CREATE TABLE "APP"."${name}" (
  "ID"        DECIMAL(19,0) NOT NULL,
  "BIZ_NO"    VARCHAR(32),
  "AMT"       DECIMAL(38,10),
  "C_TIME"    TIMESTAMP(0) NOT NULL,
  "MEMO"      VARCHAR(1024)
);

ALTER TABLE "APP"."${name}" ADD CONSTRAINT "PK_${name}" PRIMARY KEY ("ID");
CREATE INDEX "IDX_${name}_C_TIME" ON "APP"."${name}" ("C_TIME");`,
    risksDetail: [
      `AMT: 源端无精度 NUMBER，降级 DECIMAL(38,10)`,
      meta.pk ? `主键 ${meta.pk} 为数值型，按区间分片` : `${name} 无主键，按全列哈希分片，断点不可精确定位`,
    ],
  };
}

function diffPage(path: string): PageResult<DiffRow> {
  const page = Number(qs(path, 'page') || '1');
  const size = Number(qs(path, 'size') || '20');
  const type = qs(path, 'type');
  const filtered = type && type !== 'ALL' ? DIFFS.filter((d) => d.type === type) : DIFFS;
  const start = (page - 1) * size;
  return { items: clone(filtered.slice(start, start + size)), total: filtered.length, page, size };
}

const DIFF_TYPE_TEXT: Record<string, string> = {
  VALUE_DIFF: '值不同',
  ONLY_SOURCE: '仅源有',
  ONLY_TARGET: '仅目标有',
};

function diffsCsv(): string {
  const head = ['表名', '主键', '差异类型', '差异列', '源值', '目标值', '差异块', '发现时间'].join(',');
  const lines = DIFFS.slice(0, 200).map((d) =>
    [
      d.table,
      d.pk,
      DIFF_TYPE_TEXT[d.type] ?? d.type,
      d.column ?? '-',
      `"${(d.sourceValue ?? '').replace(/"/g, '""')}"`,
      `"${(d.targetValue ?? '').replace(/"/g, '""')}"`,
      d.blockId,
      d.detectedAt,
    ].join(','),
  );
  return [head, ...lines].join('\r\n');
}

function reportCsv(report: CompareReport): string {
  return [
    '指标,值',
    `比对任务,${report.jobId}`,
    `结论,${report.verdict}`,
    `通过率,${report.passRate}%`,
    `比对行数,${report.comparedRows}`,
    `差异块,${report.diffBlocks}/${report.totalBlocks}`,
    `差异行,${report.diffRows}`,
    `耗时,${report.costText}`,
    `执行人,${report.operator}`,
    `源快照,${report.sourceSnapshotAt}`,
    `目标快照,${report.targetSnapshotAt}`,
  ].join('\r\n');
}

function auditCsv(): string {
  const head = ['时间', '操作人', '动作', '对象', '结果', 'IP', 'TraceId'].join(',');
  const lines = AUDITS.map((a) => [a.at, a.actor, a.action, `"${a.object}"`, a.result, a.ip, a.traceId].join(','));
  return [head, ...lines].join('\r\n');
}

function taskView(id: string): TaskView {
  return TASK_VIEWS.find((t) => t.id === id) ?? (TASK_VIEWS[0] as TaskView);
}

function progressOf(id: string): MigrationProgressView {
  return PROGRESS_MAP[id] ?? (PROGRESS_MAP['395179382747508736'] as MigrationProgressView);
}

function actionView(taskId: string, action: string, state: string, applied: boolean): MigrationActionView {
  return { taskId, action, taskState: state, applied };
}

export function mockResolve<T>(path: string, method: string, body?: unknown): T {
  const seg = segments(path);
  const m = method.toUpperCase();
  return clone(route(seg, path, m, body)) as T;
}

function route(seg: string[], path: string, method: string, body?: unknown): unknown {
  const [root, a, b, c, d] = seg;
  const payload = (body ?? {}) as Record<string, unknown>;

  /* ---------------- 鉴权 ---------------- */
  if (root === 'api' && a === 'auth') {
    if (b === 'login' && method === 'POST') {
      const username = String(payload.username ?? '').trim();
      const password = String(payload.password ?? '');
      if (!username || !password) throw new MockError('E1001', '用户名和口令不能为空', 400);
      if (username === 'admin' && password === 'Admin@12345') return DEMO_ADMIN;
      if (username === 'bob' && password === 'Bob@12345') return DEMO_OPERATOR;
      if (username === 'dave' && password === 'Dave@12345') return DEMO_VIEWER;
      throw new MockError('E8001', '用户名或口令错误', 401, { required: null });
    }
    if (b === 'logout') return { ok: true };
    if (b === 'password' && method === 'POST') {
      if (String(payload.oldPassword ?? '') !== 'Admin@12345') {
        throw new MockError('E1001', '原口令不正确', 400);
      }
      if (String(payload.newPassword ?? '').length < 8) {
        throw new MockError('E1001', '新口令长度需 ≥ 8 位', 400);
      }
      return { ok: true, relogin: true };
    }
  }

  /* ---------------- 大盘（唯一聚合接口） ---------------- */
  if (root === 'api' && a === 'monitor' && b === 'overview') {
    const ov: MonitorOverview = clone(OVERVIEW);
    return ov;
  }

  /* ---------------- 死信 ---------------- */
  if (root === 'api' && a === 'deadletters') {
    if (!b) {
      const taskId = qs(path, 'taskId');
      return taskId ? DEAD_LETTER_VIEWS.filter((x) => x.taskId === taskId) : DEAD_LETTER_VIEWS;
    }
    if (b === 'groups') {
      const taskId = qs(path, 'taskId');
      const list = taskId ? DEAD_LETTER_VIEWS.filter((x) => x.taskId === taskId) : DEAD_LETTER_VIEWS;
      const map = new Map<string, DeadLetterView[]>();
      for (const item of list) {
        const arr = map.get(item.tableMappingId) ?? [];
        arr.push(item);
        map.set(item.tableMappingId, arr);
      }
      return Array.from(map.entries()).map(([tableMappingId, items]) => ({
        tableMappingId,
        count: items.length,
        lastError: items[0]?.errorMsg ?? '',
        retryMax: Math.max(...items.map((i) => i.retry)),
      }));
    }
    if (b === 'replay' && method === 'POST' && !c) return { replayed: DEAD_LETTER_VIEWS.length, taskId: payload.taskId };
    if (c === 'replay') return { id: b, status: 'REPLAYED', applied: true };
    if (c === 'discard' && method === 'POST') {
      const reason = String(payload.reason ?? '').trim();
      if (reason.length < 5) throw new MockError('E1001', '丢弃理由需 ≥ 5 字', 400);
      return { id: b, status: 'DISCARDED', reason };
    }
  }

  /* ---------------- 系统 ---------------- */
  if (root === 'api' && a === 'system') {
    if (b === 'audit') {
      if (c === 'export') return auditCsv();
      const user = qs(path, 'user');
      const action = qs(path, 'action');
      return AUDITS.filter((x) => (!user || x.actor === user) && (!action || x.action.includes(action)));
    }
    if (b === 'users') {
      if (method === 'GET') return USERS;
      if (method === 'POST') return { ...payload, id: `U-${Date.now()}` };
      const username = c ?? '';
      if (d === 'enabled') return { username, enabled: payload.enabled };
      if (d === 'password') {
        if (String(payload.password ?? '').length < 8) throw new MockError('E1001', '口令长度需 ≥ 8 位', 400);
        return { username, reset: true };
      }
    }
    if (b === 'alerts') {
      // 真实结构：{ channels, rules, recent }；规则只读，不支持在线改阈值
      if (!c && method === 'GET') return ALERTS_PAYLOAD;
      if (c === 'test' && method === 'POST') {
        return { ok: true, message: '测试告警已发送（通道 LOG + WECOM），耗时 118ms' };
      }
      if (d === 'test') return { ok: true, rule: c, message: `规则 ${c} 测试告警已发送` };
    }
    if (b === 'roles') return ROLES;
  }

  /* ---------------- 数据源 ---------------- */
  if (root === 'api' && a === 'datasources') {
    if (!b) {
      if (method === 'GET') return DATASOURCES;
      if (method === 'POST') return { ...(body as object), id: `ds-new-${Date.now()}` };
    }
    if (b === 'test') {
      return {
        ok: true,
        version: 'DM8 V8.1.2.100',
        costMs: 210,
        message: '连接成功，已读取 1,284 张表元数据',
        at: '2026-11-09 15:26:41',
      };
    }
    if (b && c === 'test') {
      const ds = DATASOURCES.find((x) => x.id === b);
      const up = ds?.state === 'UP';
      return {
        ok: up,
        version: ds?.version || '未知',
        costMs: ds?.probeCostMs || 0,
        message: up ? '连接成功，已读取 1,284 张表元数据' : '连接失败：网络不可达，请检查防火墙与实例状态',
        at: '2026-11-09 15:26:41',
      };
    }
    if (b && c === 'tables' && !d) return ORACLE_TABLES;
    if (b && c === 'tables' && d) return findTable(b, decodeURIComponent(d));
    if (b && method === 'DELETE') return { deleted: true };
    if (b && (method === 'PUT' || method === 'GET'))
      return { ...(DATASOURCES.find((x) => x.id === b) ?? DATASOURCES[0]), ...(body as object) };
  }

  /* ---------------- 任务 ---------------- */
  if (root === 'api' && a === 'tasks') {
    if (!b) {
      if (method === 'GET') return TASK_VIEWS;
      if (method === 'POST') return { ...taskView(''), id: '395179382747508799', state: 'DRAFT', ...(body as object) };
    }
    if (b && !c) {
      if (method === 'GET') return taskView(b);
      if (method === 'DELETE') return { deleted: true };
      return { ...taskView(b), ...(body as object) };
    }
    if (b && c === 'state') {
      const action = String(payload.action ?? '');
      const target: Record<string, string> = {
        START: 'RUNNING',
        PAUSE: 'PAUSED',
        RESUME: 'RUNNING',
        CANCEL: 'CANCELLED',
        ARCHIVE: 'ARCHIVED',
      };
      const state = target[action] ?? 'READY';
      return actionView(b, action, state, true);
    }
    if (b && c === 'shards') {
      const page = Number(qs(path, 'page') || '1');
      const size = Number(qs(path, 'size') || '200');
      const result: ShardPage = shardPage(b, qs(path, 'state'), page, size);
      return result;
    }
    if (b && c === 'tables' && d === 'progress') {
      const view: TableProgressView = TABLE_PROGRESS_VIEWS[b] ?? { taskId: b, items: [] };
      return view;
    }
    if (b && c === 'precheck') {
      const view: PrecheckView = {
        taskId: b,
        pass: true,
        blocking: [],
        warnings: [
          'T_ACCT 无主键：断点不可精确定位，将按全列哈希分片',
          'T_LOG 41 亿行，预估耗时 9 小时 20 分，建议错峰',
          '目标库 T_SETTLE 存在 1 列类型差异（VARCHAR(32)→VARCHAR(64)）',
        ],
      };
      return view;
    }
    if (b && c === 'migration') {
      if (d === 'progress') return progressOf(b);
      if (d === 'start') return actionView(b, 'START', 'RUNNING', true);
      if (d === 'pause') return actionView(b, 'PAUSE', 'PAUSED', true);
      if (d === 'resume') return actionView(b, 'RESUME', 'RUNNING', true);
      if (d === 'cancel') return actionView(b, 'CANCEL', 'CANCELLED', true);
      if (d === 'verify') return { taskId: b, jobId: 'CMP-20261109-0007' };
      if (d === 'limit') {
        const rows = Number(payload.rowsPerSec ?? payload.readRowsPerSec ?? NaN);
        const read = payload.readRowsPerSec === undefined ? rows : Number(payload.readRowsPerSec);
        const write = payload.writeRowsPerSec === undefined ? rows : Number(payload.writeRowsPerSec);
        for (const v of [read, write]) {
          if (!Number.isFinite(v) || v < 0) throw new MockError('E1001', '限速值必须 ≥ 0（0 表示不限速）', 400);
          if (v > 1e8) throw new MockError('E1001', '限速值不能超过 1 亿行/秒', 400);
        }
        const taskState = TASK_VIEWS.find((t) => t.id === b)?.state ?? 'READY';
        const result: LimitResult = {
          taskId: b,
          applied: true,
          readLimit: read,
          writeLimit: write,
          effective: taskState === 'RUNNING' ? 'IMMEDIATE' : 'NEXT_START',
        };
        return result;
      }
    }
    if (b && c === 'mappings') {
      if (d === 'generate')
        return {
          mid: '10',
          tables: Array.isArray(payload.tables) ? payload.tables.length : 87,
          detail: TABLE_DETAILS.T_ORDER,
        };
      if (!d) return [{ mid: 'MAP-T_ORDER-01', tableName: 'T_ORDER', columns: 8, riskCount: 3 }];
      if (seg[5] === 'ddl-diff') {
        const diff: DdlDiffView = { ...DDL_DIFF_T_ORDER, mappingId: Number(d) || DDL_DIFF_T_ORDER.mappingId };
        return diff;
      }
      if (seg[5] === 'ddl') return { mid: d, ddl: TABLE_DETAILS.T_ORDER?.ddl ?? '' };
      if (!seg[5]) return { mid: d, detail: TABLE_DETAILS.T_ORDER };
      return { mid: d, overridden: true, overrideBy: 'alice' };
    }
    if (b && c === 'cdc') {
      if (d === 'latency') return CDC_LATENCY;
      if (d === 'mode') return { taskId: b, mode: payload.mode ?? 'TRIGGER_LOG' };
      if (d === 'changes') return CHANGES as ChangeTrace[];
      if (d === 'triggers') return { installed: method === 'POST', tables: 87 };
    }
    if (b && c === 'compare') return { jobId: 'CMP-20261109-0007', status: 'RUNNING' };
    if (b && c === 'logs') return LOG_LINES;
  }

  /* ---------------- 比对 ---------------- */
  if (root === 'api' && a === 'compare' && b) {
    if (!c) return COMPARE_REPORT;
    if (c === 'report') {
      if (d === 'export') return qs(path, 'target') === 'report' ? reportCsv(COMPARE_REPORT) : diffsCsv();
      return COMPARE_REPORT;
    }
    if (c === 'diffs') return diffPage(path);
    if (c === 'summary')
      return {
        jobId: b,
        verdict: COMPARE_REPORT.verdict,
        passRate: COMPARE_REPORT.passRate,
        diffRows: COMPARE_REPORT.diffRows,
        diffBlocks: COMPARE_REPORT.diffBlocks,
      };
    if (c === 'cancel') return { jobId: b, status: 'CANCELLED' };
    if (c === 'repair') {
      if (d === 'fix-source' && String(payload.reason ?? '').trim().length < 10) {
        throw new MockError('E1001', '反向修复理由需 ≥ 10 字', 400);
      }
      return { ok: true, repaired: 5846, jobId: b, at: '2026-11-09 15:26:41' };
    }
  }

  /* ---------------- 切流 ---------------- */
  if (root === 'api' && a === 'cutover' && b === 'plans') {
    if (!c) return CUTOVER_PLAN;
    if (c === 'by-task') return CUTOVER_PLAN;
    if (c && !d) return CUTOVER_PLAN;
    if (d === 'gate') {
      return { ...CUTOVER_PLAN, gates: CUTOVER_PLAN.gates.map((g) => ({ ...g, at: '2026-11-09 15:27:02' })) };
    }
    if (d === 'advance') {
      // 与后端 CutoverState.forwardTargets() 的主链一致
      const next: Record<string, string> = {
        DRAFT: 'PRE_CHECK',
        PRE_CHECK: 'GRAY_READ',
        PRE_CHECK_FAILED: 'PRE_CHECK',
        GRAY_READ: 'GRAY_WRITE',
        GRAY_WRITE: 'FULL_TARGET',
        FULL_TARGET: 'OBSERVE',
        OBSERVE: 'DONE',
      };
      return { ...CUTOVER_PLAN, state: next[CUTOVER_PLAN.state] ?? 'DONE' };
    }
    if (d === 'skip-gate') {
      const reason = String(payload.reason ?? '').trim();
      const confirmText = String(payload.confirmText ?? '');
      if (reason.length < 10) throw new MockError('E1001', '理由需 ≥10 字', 400);
      if (confirmText !== 'SKIP-GATE') throw new MockError('E1001', '确认文本错误，需输入 SKIP-GATE', 400);
      if (!payload.secondConfirmUser) throw new MockError('E8002', 'PROD 环境需双人复核', 400);
      return { ...CUTOVER_PLAN, gateSkipped: ['比对通过率'] };
    }
    if (d === 'rollback') {
      if (String(payload.reason ?? '').trim().length < 5) {
        throw new MockError('E1001', '回滚原因需 ≥ 5 字', 400);
      }
      return { ...CUTOVER_PLAN, state: 'ROLLED_BACK' };
    }
    if (d === 'dry-run') return { steps: dryRunSteps(), estimatedMinutes: 96, at: '2026-11-09 15:26:41' };
    if (d === 'gray') return { ...CUTOVER_PLAN, grayPct: payload.grayPct ?? 50 };
  }

  /* ---------------- 兜底：拒绝用假数据冒充未登记接口 ---------------- */
  throw new MockError('E404', `仿真层未登记该接口：${method} ${path}`, 404);
}

function dryRunSteps(): Array<{ step: string; gate: string; expected: string }> {
  // 与后端 CutoverState.java 的 9 状态主链一致
  return [
    { step: 'PRE_CHECK', gate: '4 项卡点校验', expected: '≤ 2 分钟' },
    { step: 'GRAY_READ', gate: '读流量灰度切入目标', expected: '观察 30 分钟' },
    { step: 'GRAY_WRITE', gate: '开启目标端双写', expected: '观察 30 分钟' },
    { step: 'FULL_TARGET', gate: '读写全部切目标', expected: '观察 15 分钟' },
    { step: 'OBSERVE', gate: '观察期监控', expected: '≤ 24h' },
    { step: 'DONE', gate: '归档计划', expected: '即时' },
  ];
}

export { DEAD_LETTERS, DIFFS, ORACLE_TABLES, TASKS, TASK_VIEWS };
