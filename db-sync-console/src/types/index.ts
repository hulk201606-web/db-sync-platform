/**
 * 领域模型（与后端 DTO 对齐的视图模型）。
 * 后端统一响应封装为 { code, message, data }。
 */

export type DbType = 'MySQL' | 'Oracle' | 'PostgreSQL' | 'DM8' | 'DB2';
export type EnvLabel = 'PROD' | 'UAT' | 'DEV';
export type ConnState = 'UP' | 'SLOW' | 'DOWN';

export interface PoolConfig {
  maxConn: number;
  minIdle: number;
  connectTimeoutSec: number;
}

export interface DataSource {
  id: string;
  name: string;
  type: DbType;
  env: EnvLabel;
  host: string;
  port: number;
  database: string;
  username: string;
  schema: string;
  state: ConnState;
  lastProbe: string;
  probeCostMs: number;
  version?: string;
  pool: PoolConfig;
  ssl: boolean;
  driver: string;
  jdbcUrl: string;
  note?: string;
}

export interface TestConnResult {
  ok: boolean;
  version: string;
  costMs: number;
  message: string;
  at: string;
}

export interface TableMeta {
  name: string;
  schema: string;
  rows: number;
  pk: string | null;
  columns: number;
  risks: string[];
  targetTable: string;
  existsOnTarget: boolean;
  targetNote?: string;
  bytes: number;
}

export interface ColumnMeta {
  name: string;
  sourceType: string;
  targetType: string;
  nullable: boolean;
  overridden?: boolean;
  degrading?: boolean;
  defaultExpr?: string;
  comment?: string;
}

export interface IndexMeta {
  name: string;
  kind: 'PK' | 'UK' | 'IDX';
  columns: string;
  targetMissing?: boolean;
  targetName?: string;
}

export interface TableDetail extends TableMeta {
  columnsDetail: ColumnMeta[];
  indexes: IndexMeta[];
  ddl: string;
  risksDetail: string[];
}

export type TaskStatus =
  | 'DRAFT'
  | 'READY'
  | 'RUNNING'
  | 'PAUSED'
  | 'FAILED'
  | 'FINISHED'
  | 'CANCELLED';

export type SyncMode = 'FULL' | 'FULL_INCR';

export interface TaskSummary {
  id: string;
  name: string;
  sourceId: string;
  sourceName: string;
  sourceType: DbType;
  targetId: string;
  targetName: string;
  targetType: DbType;
  mode: SyncMode;
  status: TaskStatus;
  progress: number;
  tableCount: number;
  totalRows: number;
  migratedRows: number;
  updatedAt: string;
  owner: string;
  deadLetters: number;
}

export type ShardState = 'DONE' | 'RUNNING' | 'DEAD' | 'PENDING';

export interface Shard {
  id: number;
  table: string;
  state: ShardState;
  rows: number;
  pkFrom: string;
  pkTo: string;
  instance: string;
  retries: number;
  updatedAt: string;
}

export interface TableProgress {
  table: string;
  totalRows: number;
  migratedRows: number;
  tps: number;
  shards: number;
  doneShards: number;
  failedShards: number;
  status: TaskStatus;
  avgRowBytes: number;
}

export interface TpsSeries {
  name: string;
  color: string;
  values: number[];
}

export interface ProgressSnapshot {
  taskId: string;
  taskName: string;
  status: TaskStatus;
  migratedRows: number;
  totalRows: number;
  tps: number;
  latencySec: number;
  deadShards: number;
  activeInstances: number;
  etaMinutes: number;
  sampledAt: string;
  bucketSeconds: number;
  series: TpsSeries[];
  shards: Shard[];
  tables: TableProgress[];
  limitRowsPerSec: number | null;
}

export interface DeadLetter {
  id: string;
  shardId: number;
  table: string;
  pkRange: string;
  errorCode: string;
  error: string;
  at: string;
  retries: number;
  rows: number;
}

export type CdcMode = 'SDK_DUAL_WRITE' | 'TRIGGER_LOG' | 'TIMESTAMP';

export interface CdcLatency {
  mode: CdcMode;
  p95Sec: number;
  p99Sec: number;
  backlog: number;
  failRate: number;
  applyTps: number;
  triggerInstalled: boolean;
  triggerTables: number;
  triggerTotal: number;
  updatedAt: string;
  trend: number[];
}

export interface ChangeTrace {
  seq: string;
  op: 'INSERT' | 'UPDATE' | 'DELETE';
  table: string;
  pk: string;
  appliedAt: string;
  sourceCommitAt: string;
  result: 'APPLIED' | 'IGNORED' | 'FAILED';
  detail: string;
  latencyMs: number;
}

export type DiffType = 'VALUE_DIFF' | 'ONLY_SOURCE' | 'ONLY_TARGET';
export type Verdict = 'PASS' | 'WARN' | 'FAIL';

export interface CompareReport {
  jobId: string;
  taskId: string;
  taskName: string;
  verdict: Verdict;
  passRate: number;
  comparedRows: number;
  totalBlocks: number;
  diffBlocks: number;
  diffRows: number;
  costText: string;
  operator: string;
  sourceSnapshotAt: string;
  targetSnapshotAt: string;
  startedAt: string;
  algorithm: string;
  threshold: number;
  ignoreRules: Array<{ name: string; detail: string; enabled: boolean }>;
  distribution: Array<{ table: string; rows: number; blockRate: number }>;
  diffByType: Record<DiffType, number>;
}

export interface DiffRow {
  id: number;
  table: string;
  pk: string;
  type: DiffType;
  column: string | null;
  sourceValue: string | null;
  targetValue: string | null;
  blockId: string;
  detectedAt: string;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

/** 切流状态机：与后端 CutoverState.java 的 9 个枚举值一一对应（唯一真源） */
export type CutoverState =
  | 'DRAFT'
  | 'PRE_CHECK'
  | 'PRE_CHECK_FAILED'
  | 'GRAY_READ'
  | 'GRAY_WRITE'
  | 'FULL_TARGET'
  | 'OBSERVE'
  | 'DONE'
  | 'ROLLED_BACK';

export interface GateItem {
  key: string;
  name: string;
  actual: number;
  threshold: number;
  unit: string;
  pass: boolean;
  compare: '>=' | '<=' | '=';
  evidence: string;
  at: string;
}

export interface RwMatrix {
  readSourcePct: number;
  readTargetPct: number;
  writeSource: boolean;
  writeTarget: boolean;
  writeDual: boolean;
}

export interface CutoverPlan {
  id: string;
  planName: string;
  taskId: string;
  taskName: string;
  sourceName: string;
  sourceType: DbType;
  targetName: string;
  targetType: DbType;
  state: CutoverState;
  stateSince: string;
  stateElapsed: string;
  adminUser: string;
  gates: GateItem[];
  matrix: RwMatrix;
  grayPct: number;
  grayDimensions: string[];
  grayTables: string[];
  gateSkipped: string[];
  observeWindowH: number;
  autoRollbackErrorPct: number;
  timeline: Array<{
    at: string;
    actor: string;
    action: string;
    detail: string;
    level: 'info' | 'warn' | 'danger';
  }>;
}

export interface AlertChannel {
  id: string;
  name: string;
  kind: 'WEBHOOK' | 'EMAIL' | 'WECOM';
  target: string;
  enabled: boolean;
  severities: string[];
  lastSentAt: string | null;
  lastResult: string;
}

export interface AuditLog {
  id: string;
  at: string;
  actor: string;
  action: string;
  object: string;
  result: 'SUCCESS' | 'FAILED' | 'DENIED';
  ip: string;
  traceId: string;
}

export interface RoleMatrix {
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER';
  desc: string;
  perms: Record<string, boolean>;
}

export interface SysUser {
  id: string;
  account: string;
  name: string;
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER';
  source: string;
  lastLogin: string;
  enabled: boolean;
}

/* ============================================================
   以下为后端真实 DTO（字段名以后端为准，勿改）
   ============================================================ */

export interface AuthSession {
  token: string;
  username: string;
  displayName: string;
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER' | string;
  permissions: string[];
  expiresAt: string;
  mustChangePassword: boolean;
}

/** TaskView */
export interface TaskView {
  id: string;
  name: string;
  sourceDsId: string;
  targetDsId: string;
  state: string;
  groupCount: number;
  concurrency: number;
  batchSize: number;
  shardRows: number;
  rateLimitRows: number | null;
  maxRetry: number;
  incrementalMode: string | null;
  consumerConcurrency: number | null;
  lastError: string | null;
}

/** MigrationActionView：applied=false 表示已在目标态（幂等未重复执行） */
export interface MigrationActionView {
  taskId: string;
  action: string;
  taskState: string;
  applied: boolean;
}

/** GET /api/tasks/{id}/migration/progress（计数，不是分片清单） */
export interface MigrationProgressView {
  taskId: string;
  taskState: string;
  totalShards: number;
  shardCounts: Record<string, number>;
  committedRows: number;
  estimatedRows: number;
  percent: number;
  successShards: number;
  failedShards: number;
  finished: boolean;
}

/* ---------------- 逐分片进度（GET /api/tasks/{taskId}/shards） ---------------- */

export type ShardRunState =
  | 'PENDING'
  | 'CLAIMED'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'DEAD'
  | 'PAUSED';

export interface ShardItem {
  shardId: number;
  mappingId: number;
  shardNo: number;
  rangeKind: string;
  pkStart: string | null;
  pkEnd: string | null;
  state: ShardRunState | string;
  leaseOwner: string | null;
  leaseExpireAt: string | null;
  lastPk: string | null;
  committedRows: number;
  sourceRows: number;
  attempt: number;
  lastError: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface ShardPage {
  total: number;
  page: number;
  size: number;
  items: ShardItem[];
}

/* ---------------- 表级进度（GET /api/tasks/{taskId}/tables/progress） ---------------- */

export interface TableProgressItem {
  mappingId: number;
  sourceTable: string;
  targetTable: string;
  totalShards: number;
  successShards: number;
  failedShards: number;
  deadShards: number;
  committedRows: number;
  sourceRows: number;
  /** 源端行数未知时为 null，UI 必须显示「—」而不是 0% */
  percent: number | null;
  /** true 表示 sourceRows 来自上报表估算，UI 加「估算」徽章 */
  rowsFromEstimate: boolean;
}

export interface TableProgressView {
  taskId: string | number;
  items: TableProgressItem[];
}

/* ---------------- 运行期限速（POST /api/tasks/{taskId}/migration/limit） ---------------- */

export interface LimitRequest {
  rowsPerSec?: number;
  readRowsPerSec?: number;
  writeRowsPerSec?: number;
}

export interface LimitResult {
  taskId: string | number;
  applied: boolean;
  readLimit: number | null;
  writeLimit: number | null;
  /** IMMEDIATE=已热生效；NEXT_START=任务未运行，下次启动生效 */
  effective: 'IMMEDIATE' | 'NEXT_START';
}

/* ---------------- DDL 差异（GET /api/tasks/{taskId}/mappings/{mid}/ddl-diff） ---------------- */

export type DdlColumnStatus = 'NEW' | 'TYPE_MISMATCH' | 'EXTRA' | 'OK';

export interface DdlDiffColumn {
  name: string;
  sourceType: string;
  generatedType: string;
  targetType: string | null;
  status: DdlColumnStatus | string;
  note?: string | null;
}

export interface DdlDiffIndex {
  name: string;
  status: 'MISSING' | 'OK' | 'DIFFERENT' | string;
}

export interface DdlDiffSummary {
  newColumns: number;
  typeMismatch: number;
  missingIndexes: number;
  extraColumns: number;
}

export interface DdlDiffView {
  mappingId: number;
  sourceTable: string;
  targetTable: string;
  targetExists: boolean;
  generatedDdl: string[];
  columns: DdlDiffColumn[];
  indexes: DdlDiffIndex[];
  summary: DdlDiffSummary;
}

/** DeadLetterView */
export interface DeadLetterView {
  id: string;
  taskId: string;
  tableMappingId: string;
  sourceType: string;
  errorCode: string;
  errorMsg: string;
  status: string;
  retry: number;
  pkJson: string;
  replayBy: string | null;
}

export interface PrecheckView {
  taskId: string;
  pass: boolean;
  blocking: string[];
  warnings: string[];
}

export interface RecentAlert {
  rule: string;
  title: string;
  at: string;
}

/** GET /api/monitor/overview（全局聚合，无逐分片明细） */
export interface MonitorOverview {
  taskCounts: Record<string, number>;
  totalTasks: number;
  shardCounts: Record<string, number>;
  shardHeat: Record<string, Record<string, number>>;
  committedRows: number;
  tps: Record<string, number>;
  totalTps: number;
  deadLetterOpen: number;
  aliveInstances: number;
  cutoverPlans: Record<string, number>;
  recentAlerts: RecentAlert[];
  /** 仅 mock 提供：给演示用的历史 TPS 种子，真实后端不返回该字段 */
  tpsHistory?: Record<string, number[]>;
}

/** 表格行：TaskView + 数据源信息 + 该任务的迁移进度（按需拉取） */
export interface TaskRow {
  id: string;
  name: string;
  sourceName: string;
  sourceType: string;
  targetName: string;
  targetType: string;
  state: string;
  groups: number;
  concurrency: number;
  batchSize: number;
  shardRows: number;
  rateLimitRows: number | null;
  incrementalMode: string | null;
  lastError: string | null;
  progress: MigrationProgressView | null;
}

/** 大盘视图模型（overview + 任务列表 + 前端累积的 TPS 序列） */
export interface MonitorView {
  overview: MonitorOverview;
  tasks: TaskRow[];
  series: TpsSeries[];
}

/* ---------------- 告警（GET /api/system/alerts 真实结构） ---------------- */
export interface AlertRuleView {
  code: string;
  description: string;
  editable: boolean;
}

export interface RecentAlertItem {
  rule: string;
  title: string;
  detail?: string;
  firedAt?: string;
  channels?: string[];
}

export interface AlertsPayload {
  /** 已启用通道；后端为 ["LOG"] 形式，兼容对象数组 */
  channels: Array<string | AlertChannel>;
  rules: AlertRuleView[];
  recent: RecentAlertItem[];
}
