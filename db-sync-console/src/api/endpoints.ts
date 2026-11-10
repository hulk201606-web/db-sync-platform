/**
 * 接口路径常量 —— 一个接口一行，路径以后端 Controller 为准。
 * 仅 [MOCK] 标注的 2 个接口为后端本期未提供、走前端仿真数据；其余均为后端已交付的真实接口。
 */
export const EP = {
  /* ---------------- 接口目录 & 鉴权 ---------------- */
  openapi: () => '/api/openapi.json',
  login: () => '/api/auth/login',
  logout: () => '/api/auth/logout',
  changePassword: () => '/api/auth/password',

  /* ---------------- 监控大盘（唯一的聚合接口） ---------------- */
  monitorOverview: () => '/api/monitor/overview',

  /* ---------------- 数据源 ---------------- */
  dsList: () => '/api/datasources',
  dsDetail: (id: string) => `/api/datasources/${encodeURIComponent(id)}`,
  dsCreate: () => '/api/datasources',
  dsUpdate: (id: string) => `/api/datasources/${encodeURIComponent(id)}`,
  dsDelete: (id: string) => `/api/datasources/${encodeURIComponent(id)}`,
  dsTest: (id: string) => `/api/datasources/${encodeURIComponent(id)}/test`,
  dsTestDraft: () => '/api/datasources/test',
  dsTables: (id: string) => `/api/datasources/${encodeURIComponent(id)}/tables`,
  dsTable: (id: string, table: string) =>
    `/api/datasources/${encodeURIComponent(id)}/tables/${encodeURIComponent(table)}`,

  /* ---------------- 任务 ---------------- */
  taskList: () => '/api/tasks',
  taskDetail: (id: string) => `/api/tasks/${encodeURIComponent(id)}`,
  taskCreate: () => '/api/tasks',
  taskUpdate: (id: string) => `/api/tasks/${encodeURIComponent(id)}`,
  taskDelete: (id: string) => `/api/tasks/${encodeURIComponent(id)}`,
  /** 状态跃迁统一入口，body: { action, reason? } */
  taskState: (id: string) => `/api/tasks/${encodeURIComponent(id)}/state`,
  /** 逐分片进度（权限 TASK_VIEW），state 为空表示不过滤；size 默认 200、上限 1000 */
  taskShards: (id: string, state: string, page: number, size: number) => {
    const q = new URLSearchParams({ page: String(page), size: String(size) });
    if (state) q.set('state', state);
    return `/api/tasks/${encodeURIComponent(id)}/shards?${q.toString()}`;
  },
  /** 表级进度（权限 TASK_VIEW）：每张表的成功率、行数、percent 可能为 null */
  taskTablesProgress: (id: string) => `/api/tasks/${encodeURIComponent(id)}/tables/progress`,

  /* ---------------- 结构映射 ---------------- */
  mappings: (taskId: string) => `/api/tasks/${encodeURIComponent(taskId)}/mappings`,
  mappingGenerate: (taskId: string) => `/api/tasks/${encodeURIComponent(taskId)}/mappings/generate`,
  mappingDetail: (taskId: string, mid: string) =>
    `/api/tasks/${encodeURIComponent(taskId)}/mappings/${encodeURIComponent(mid)}`,
  mappingDdl: (taskId: string, mid: string) =>
    `/api/tasks/${encodeURIComponent(taskId)}/mappings/${encodeURIComponent(mid)}/ddl`,
  /** 目标库现状与生成 DDL 的差异（权限 TASK_VIEW） */
  mappingDdlDiff: (taskId: string, mid: string) =>
    `/api/tasks/${encodeURIComponent(taskId)}/mappings/${encodeURIComponent(mid)}/ddl-diff`,
  mappingColumn: (taskId: string, mid: string, col: string) =>
    `/api/tasks/${encodeURIComponent(taskId)}/mappings/${encodeURIComponent(mid)}/columns/${encodeURIComponent(col)}`,
  /** 表级冲突策略（SOURCE_WINS/TARGET_WINS/TIMESTAMP_NEWER，权限 TASK_EDIT） */
  mappingConflictPolicy: (taskId: string, mid: string) =>
    `/api/tasks/${encodeURIComponent(taskId)}/mappings/${encodeURIComponent(mid)}/conflict-policy`,

  /* ---------------- 存量迁移 ---------------- */
  taskPrecheck: (id: string) => `/api/tasks/${encodeURIComponent(id)}/precheck`,
  migrationStart: (id: string) => `/api/tasks/${encodeURIComponent(id)}/migration/start`,
  migrationPause: (id: string) => `/api/tasks/${encodeURIComponent(id)}/migration/pause`,
  migrationResume: (id: string) => `/api/tasks/${encodeURIComponent(id)}/migration/resume`,
  migrationCancel: (id: string) => `/api/tasks/${encodeURIComponent(id)}/migration/cancel`,
  migrationProgress: (id: string) => `/api/tasks/${encodeURIComponent(id)}/migration/progress`,
  /** 运行期调限速（权限 TASK_EXEC），body: { rowsPerSec } 或 { readRowsPerSec, writeRowsPerSec }；0=不限速 */
  migrationLimit: (id: string) => `/api/tasks/${encodeURIComponent(id)}/migration/limit`,
  migrationVerify: (id: string) => `/api/tasks/${encodeURIComponent(id)}/migration/verify`,

  /* ---------------- 死信 ---------------- */
  deadLetters: (taskId: string) => `/api/deadletters?taskId=${encodeURIComponent(taskId)}`,
  deadLetterGroups: (taskId: string) => `/api/deadletters/groups?taskId=${encodeURIComponent(taskId)}`,
  deadLetterReplay: (id: string) => `/api/deadletters/${encodeURIComponent(id)}/replay`,
  deadLetterBatchReplay: () => '/api/deadletters/replay',
  deadLetterDiscard: (id: string) => `/api/deadletters/${encodeURIComponent(id)}/discard`,

  /* ---------------- 增量 / CDC ---------------- */
  cdcMode: (id: string) => `/api/tasks/${encodeURIComponent(id)}/cdc/mode`,
  cdcLatency: (id: string) => `/api/tasks/${encodeURIComponent(id)}/cdc/latency`,
  /** 增量消费运行态（权限 TASK_VIEW） */
  cdcRuntime: (id: string) => `/api/tasks/${encodeURIComponent(id)}/cdc/runtime`,
  cdcTriggersInstall: (id: string) => `/api/tasks/${encodeURIComponent(id)}/cdc/triggers/install`,
  cdcTriggersDelete: (id: string) => `/api/tasks/${encodeURIComponent(id)}/cdc/triggers`,
  cdcChanges: (id: string, table: string, pk: string) =>
    `/api/tasks/${encodeURIComponent(id)}/cdc/changes?table=${encodeURIComponent(table)}&pk=${encodeURIComponent(pk)}`,

  /* ---------------- 比对 ---------------- */
  compareCreate: (taskId: string) => `/api/tasks/${encodeURIComponent(taskId)}/compare`,
  compareJob: (jobId: string) => `/api/compare/${encodeURIComponent(jobId)}`,
  compareReport: (jobId: string) => `/api/compare/${encodeURIComponent(jobId)}/report`,
  compareExport: (jobId: string) => `/api/compare/${encodeURIComponent(jobId)}/report/export`,
  compareDiffs: (jobId: string, page: number, size: number, type: string) =>
    `/api/compare/${encodeURIComponent(jobId)}/diffs?page=${page}&size=${size}&type=${encodeURIComponent(type)}`,
  compareSummary: (jobId: string) => `/api/compare/${encodeURIComponent(jobId)}/summary`,
  compareCancel: (jobId: string) => `/api/compare/${encodeURIComponent(jobId)}/cancel`,
  compareReplay: (jobId: string) => `/api/compare/${encodeURIComponent(jobId)}/repair/replay`,
  compareFixSource: (jobId: string) => `/api/compare/${encodeURIComponent(jobId)}/repair/fix-source`,

  /* ---------------- 切流 ---------------- */
  cutoverCreate: () => '/api/cutover/plans',
  cutoverPlan: (id: string) => `/api/cutover/plans/${encodeURIComponent(id)}`,
  cutoverByTask: (taskId: string) => `/api/cutover/plans/by-task/${encodeURIComponent(taskId)}`,
  cutoverGate: (id: string) => `/api/cutover/plans/${encodeURIComponent(id)}/gate`,
  cutoverAdvance: (id: string) => `/api/cutover/plans/${encodeURIComponent(id)}/advance`,
  cutoverSkipGate: (id: string) => `/api/cutover/plans/${encodeURIComponent(id)}/skip-gate`,
  cutoverRollback: (id: string) => `/api/cutover/plans/${encodeURIComponent(id)}/rollback`,
  cutoverDryRun: (id: string) => `/api/cutover/plans/${encodeURIComponent(id)}/dry-run`,

  /* ---------------- 系统 ---------------- */
  sysAudit: (q: { from?: string; to?: string; user?: string; action?: string }) => {
    const params = new URLSearchParams();
    if (q.from) params.set('from', q.from);
    if (q.to) params.set('to', q.to);
    if (q.user) params.set('user', q.user);
    if (q.action) params.set('action', q.action);
    const s = params.toString();
    return `/api/system/audit${s ? `?${s}` : ''}`;
  },
  sysAuditExport: () => '/api/system/audit/export',
  sysUsers: () => '/api/system/users',
  sysUserCreate: () => '/api/system/users',
  sysUserEnabled: (username: string) => `/api/system/users/${encodeURIComponent(username)}/enabled`,
  sysUserPassword: (username: string) => `/api/system/users/${encodeURIComponent(username)}/password`,

  /* ---------------- 告警（真实存在，规则只读） ---------------- */
  sysAlerts: () => '/api/system/alerts',
  sysAlertTest: () => '/api/system/alerts/test',
  sysAlertTestRule: (rule: string) => `/api/system/alerts/${encodeURIComponent(rule)}/test`,

  /* ---------------- 业务侧 SDK 路由（后端已交付） ---------------- */
  sdkRouteSnapshot: (taskId: string) => `/api/sdk/route-snapshot?taskId=${encodeURIComponent(taskId)}`,
  sdkRouteAck: () => '/api/sdk/route-ack',

  /* ---------------- 仅前端仿真（后端本期无对应接口，共 2 处） ----------------
     告警规则/阈值在线改写并无独立端点（GET /api/system/alerts 规则 editable:false 只读），
     因此它不是第三处 [MOCK] 接口，不在此登记。 */
  // [MOCK] 后端未提供：任务日志 tail（无日志查询接口）
  taskLogs: (id: string) => `/api/tasks/${encodeURIComponent(id)}/logs`,
  // [MOCK] 后端未提供：灰度比例（只能 advance 整体推进，无单独调比例接口）
  cutoverGray: (id: string) => `/api/cutover/plans/${encodeURIComponent(id)}/gray`,
} as const;
