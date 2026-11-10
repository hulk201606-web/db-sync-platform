import type {
  AlertChannel,
  AuditLog,
  ChangeTrace,
  CompareReport,
  CutoverPlan,
  DataSource,
  DeadLetter,
  DiffRow,
  ProgressSnapshot,
  Shard,
  SysUser,
  TableDetail,
  TableMeta,
  TableProgress,
  TaskSummary,
  TpsSeries,
  RoleMatrix,
} from '../../types';

export function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function seeded(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

export const NOW = '2026-11-09 15:26:41';

/* ============================================================
   数据源
   ============================================================ */
export const DATASOURCES: DataSource[] = [
  {
    id: 'ds-ora-01',
    name: 'ora-core-prod',
    type: 'Oracle',
    env: 'PROD',
    host: '10.1.2.3',
    port: 1521,
    database: 'ORCL',
    username: 'sync_ro',
    schema: 'APP',
    state: 'UP',
    lastProbe: '12s 前',
    probeCostMs: 86,
    version: 'Oracle 19.19.0.0.0',
    pool: { maxConn: 8, minIdle: 2, connectTimeoutSec: 10 },
    ssl: false,
    driver: 'oracle.jdbc.OracleDriver',
    jdbcUrl: 'jdbc:oracle:thin:@10.1.2.3:1521/ORCL',
    note: '核心订单库，只读账号 sync_ro',
  },
  {
    id: 'ds-dm-01',
    name: 'dm8-core-prod',
    type: 'DM8',
    env: 'PROD',
    host: '10.1.9.7',
    port: 5236,
    database: 'DMSYNC',
    username: 'SYNC_USER',
    schema: 'APP',
    state: 'UP',
    lastProbe: '12s 前',
    probeCostMs: 210,
    version: 'DM8 V8.1.2.100',
    pool: { maxConn: 12, minIdle: 4, connectTimeoutSec: 10 },
    ssl: false,
    driver: 'dm.jdbc.driver.DmDriver',
    jdbcUrl: 'jdbc:dm://10.1.9.7:5236?schema=APP',
    note: '国产化目标库（达梦）',
  },
  {
    id: 'ds-db2-01',
    name: 'db2-legacy',
    type: 'DB2',
    env: 'PROD',
    host: '10.1.5.9',
    port: 50000,
    database: 'LEGDB',
    username: 'db2inst1',
    schema: 'LEG',
    state: 'SLOW',
    lastProbe: '30s 前',
    probeCostMs: 2860,
    version: 'DB2 LUW 11.5.9',
    pool: { maxConn: 6, minIdle: 2, connectTimeoutSec: 15 },
    ssl: true,
    driver: 'com.ibm.db2.jcc.DB2Driver',
    jdbcUrl: 'jdbc:db2://10.1.5.9:50000/LEGDB:sslConnection=true;',
    note: '历史库，探活耗时偏高（2.8s），巡检偶发超时',
  },
  {
    id: 'ds-pg-01',
    name: 'pg-uat-01',
    type: 'PostgreSQL',
    env: 'UAT',
    host: '10.2.0.11',
    port: 5432,
    database: 'appdb',
    username: 'sync_user',
    schema: 'public',
    state: 'DOWN',
    lastProbe: '连接失败',
    probeCostMs: 0,
    version: '',
    pool: { maxConn: 8, minIdle: 2, connectTimeoutSec: 10 },
    ssl: false,
    driver: 'org.postgresql.Driver',
    jdbcUrl: 'jdbc:postgresql://10.2.0.11:5432/appdb',
    note: 'UAT 环境停机维护中（2026-11-09 14:00–18:00）',
  },
  {
    id: 'ds-my-01',
    name: 'mysql-uat-02',
    type: 'MySQL',
    env: 'UAT',
    host: '10.2.0.24',
    port: 3306,
    database: 'orders',
    username: 'sync_rw',
    schema: 'orders',
    state: 'UP',
    lastProbe: '5s 前',
    probeCostMs: 34,
    version: 'MySQL 8.0.36',
    pool: { maxConn: 16, minIdle: 4, connectTimeoutSec: 8 },
    ssl: true,
    driver: 'com.mysql.cj.jdbc.Driver',
    jdbcUrl: 'jdbc:mysql://10.2.0.24:3306/orders?useSSL=true&serverTimezone=Asia/Shanghai',
    note: 'UAT 双写验证库',
  },
  {
    id: 'ds-pg-02',
    name: 'pg-report-dev',
    type: 'PostgreSQL',
    env: 'DEV',
    host: '10.3.1.8',
    port: 5432,
    database: 'report',
    username: 'dev',
    schema: 'public',
    state: 'UP',
    lastProbe: '8s 前',
    probeCostMs: 41,
    version: 'PostgreSQL 15.6',
    pool: { maxConn: 4, minIdle: 1, connectTimeoutSec: 10 },
    ssl: false,
    driver: 'org.postgresql.Driver',
    jdbcUrl: 'jdbc:postgresql://10.3.1.8:5432/report',
    note: '报表库，仅用于结构验证',
  },
];

/** 数据源类型 → 驱动 / URL 模板（抽屉里切换类型时动态替换） */
export const DB_TEMPLATES: Record<
  string,
  { driver: string; port: number; urlTemplate: string; dbLabel: string; defaultSchema: string }
> = {
  Oracle: {
    driver: 'oracle.jdbc.OracleDriver',
    port: 1521,
    urlTemplate: 'jdbc:oracle:thin:@{host}:{port}/{database}',
    dbLabel: '库 / SID / 服务名',
    defaultSchema: 'APP',
  },
  DM8: {
    driver: 'dm.jdbc.driver.DmDriver',
    port: 5236,
    urlTemplate: 'jdbc:dm://{host}:{port}?schema={schema}',
    dbLabel: '数据库名',
    defaultSchema: 'APP',
  },
  PostgreSQL: {
    driver: 'org.postgresql.Driver',
    port: 5432,
    urlTemplate: 'jdbc:postgresql://{host}:{port}/{database}',
    dbLabel: '数据库名',
    defaultSchema: 'public',
  },
  MySQL: {
    driver: 'com.mysql.cj.jdbc.Driver',
    port: 3306,
    urlTemplate: 'jdbc:mysql://{host}:{port}/{database}?useSSL={ssl}&serverTimezone=Asia/Shanghai',
    dbLabel: '数据库名',
    defaultSchema: 'orders',
  },
  DB2: {
    driver: 'com.ibm.db2.jcc.DB2Driver',
    port: 50000,
    urlTemplate: 'jdbc:db2://{host}:{port}/{database}',
    dbLabel: '数据库名',
    defaultSchema: 'LEG',
  },
};

/* ============================================================
   表元数据（源库 APP schema）
   ============================================================ */
export const ORACLE_TABLES: TableMeta[] = [
  {
    name: 'T_ORDER',
    schema: 'APP',
    rows: 820_000_000,
    pk: 'ID',
    columns: 32,
    risks: [],
    targetTable: 'T_ORDER',
    existsOnTarget: false,
    bytes: 486_000_000_000,
  },
  {
    name: 'T_ACCT',
    schema: 'APP',
    rows: 32_000_000,
    pk: null,
    columns: 18,
    risks: ['无主键'],
    targetTable: 'T_ACCT',
    existsOnTarget: true,
    targetNote: '已存在，缺 IDX_UPD_TIME',
    bytes: 8_400_000_000,
  },
  {
    name: 'T_LOG',
    schema: 'APP',
    rows: 4_100_000_000,
    pk: null,
    columns: 14,
    risks: ['无主键', '降级'],
    targetTable: 'T_LOG',
    existsOnTarget: false,
    bytes: 1_640_000_000_000,
  },
  {
    name: 'T_PAY',
    schema: 'APP',
    rows: 12_400_000,
    pk: 'PAY_ID',
    columns: 24,
    risks: [],
    targetTable: 'T_PAY',
    existsOnTarget: false,
    bytes: 5_200_000_000,
  },
  {
    name: 'T_ITEM',
    schema: 'APP',
    rows: 890_000_000,
    pk: 'ITEM_ID',
    columns: 21,
    risks: [],
    targetTable: 'T_ITEM',
    existsOnTarget: false,
    bytes: 210_000_000_000,
  },
  {
    name: 'T_SETTLE',
    schema: 'APP',
    rows: 6_400_000,
    pk: 'ID',
    columns: 26,
    risks: [],
    targetTable: 'T_SETTLE',
    existsOnTarget: true,
    targetNote: '已存在，列类型 VARCHAR(32)→VARCHAR(64)',
    bytes: 3_100_000_000,
  },
  {
    name: 'T_REFUND',
    schema: 'APP',
    rows: 1_240_000,
    pk: 'ID',
    columns: 19,
    risks: [],
    targetTable: 'T_REFUND',
    existsOnTarget: false,
    bytes: 720_000_000,
  },
  {
    name: 'T_CUST',
    schema: 'APP',
    rows: 45_000_000,
    pk: 'CUST_ID',
    columns: 29,
    risks: ['含 CLOB 大字段'],
    targetTable: 'T_CUST',
    existsOnTarget: false,
    bytes: 42_000_000_000,
  },
  {
    name: 'T_ADDR',
    schema: 'APP',
    rows: 120_000_000,
    pk: 'ID',
    columns: 12,
    risks: [],
    targetTable: 'T_ADDR',
    existsOnTarget: false,
    bytes: 19_000_000_000,
  },
  {
    name: 'T_COUPON',
    schema: 'APP',
    rows: 8_000_000,
    pk: 'ID',
    columns: 16,
    risks: [],
    targetTable: 'T_COUPON',
    existsOnTarget: false,
    bytes: 2_400_000_000,
  },
  {
    name: 'T_INVOICE',
    schema: 'APP',
    rows: 3_200_000,
    pk: 'ID',
    columns: 22,
    risks: ['VARCHAR2 BYTE 语义'],
    targetTable: 'T_INVOICE',
    existsOnTarget: false,
    bytes: 1_800_000_000,
  },
  {
    name: 'T_AUDIT_TRAIL',
    schema: 'APP',
    rows: 210_000_000,
    pk: null,
    columns: 9,
    risks: ['无主键', '不可断点精确定位'],
    targetTable: 'T_AUDIT_TRAIL',
    existsOnTarget: false,
    bytes: 88_000_000_000,
  },
];

const T_ORDER_DDL = `-- 生成时间 2026-11-09 15:26:41 / 目标方言 DM8
CREATE TABLE "APP"."T_ORDER" (
  "ID"        DECIMAL(19,0)  NOT NULL,
  "ORDER_NO"  VARCHAR(32)    NOT NULL,
  "ACCT_ID"   DECIMAL(19,0)  NOT NULL,
  "AMT"       DECIMAL(38,10) DEFAULT 0,
  "STAT"      CHAR(1)        DEFAULT '0',
  "C_TIME"    TIMESTAMP(0)   NOT NULL,
  "UPD_TIME"  TIMESTAMP(0),
  "MEMO"      CLOB
);

ALTER TABLE "APP"."T_ORDER" ADD CONSTRAINT "PK_T_ORDER" PRIMARY KEY ("ID");
ALTER TABLE "APP"."T_ORDER" ADD CONSTRAINT "UK_T_ORDER_NO" UNIQUE ("ORDER_NO");
CREATE INDEX "IDX_T_ORDER_C_TIME" ON "APP"."T_ORDER" ("C_TIME");
COMMENT ON TABLE "APP"."T_ORDER" IS '订单主表';
COMMENT ON COLUMN "APP"."T_ORDER"."AMT" IS '订单金额（源 NUMBER 无精度，降级 DECIMAL(38,10)）';`;

export const TABLE_DETAILS: Record<string, TableDetail> = {
  T_ORDER: {
    ...(ORACLE_TABLES[0] as TableMeta),
    columnsDetail: [
      {
        name: 'ID',
        sourceType: 'NUMBER(19)',
        targetType: 'DECIMAL(19,0)',
        nullable: false,
        overridden: true,
      },
      {
        name: 'ORDER_NO',
        sourceType: 'VARCHAR2(32 BYTE)',
        targetType: 'VARCHAR(32)',
        nullable: false,
      },
      {
        name: 'ACCT_ID',
        sourceType: 'NUMBER(19)',
        targetType: 'DECIMAL(19,0)',
        nullable: false,
      },
      {
        name: 'AMT',
        sourceType: 'NUMBER',
        targetType: 'DECIMAL(38,10)',
        nullable: true,
        degrading: true,
        defaultExpr: '0',
      },
      {
        name: 'STAT',
        sourceType: 'CHAR(1)',
        targetType: 'CHAR(1)',
        nullable: true,
        defaultExpr: "'0'",
      },
      {
        name: 'C_TIME',
        sourceType: 'DATE',
        targetType: 'TIMESTAMP(0)',
        nullable: false,
        overridden: true,
      },
      { name: 'UPD_TIME', sourceType: 'DATE', targetType: 'TIMESTAMP(0)', nullable: true },
      { name: 'MEMO', sourceType: 'CLOB', targetType: 'CLOB', nullable: true },
    ],
    indexes: [
      { name: 'PK_T_ORDER', kind: 'PK', columns: 'ID' },
      { name: 'UK_T_ORDER_NO', kind: 'UK', columns: 'ORDER_NO' },
      { name: 'IDX_T_ORDER_C_TIME', kind: 'IDX', columns: 'C_TIME', targetMissing: true, targetName: 'IDX_C_TIME' },
    ],
    ddl: T_ORDER_DDL,
    risksDetail: [
      'AMT: 源端 Oracle 无精度 NUMBER，默认降级 DECIMAL(38,10)，超过 10 位小数将截断',
      'C_TIME: 源 DATE 含时分秒，已强制映射 TIMESTAMP(0)，禁止降为 DATE',
      'T_ACCT 无主键，断点不可精确定位，需全列哈希分片',
      'T_LOG 为 41 亿行大表，降级迁移（无主键 + 大字段），建议错峰执行',
    ],
  },
};

/* ============================================================
   任务列表
   ============================================================ */
export const TASKS: TaskSummary[] = [
  {
    id: 'T-20261109-01',
    name: 'T_ORDER 全量迁移',
    sourceId: 'ds-ora-01',
    sourceName: 'ora-core-prod',
    sourceType: 'Oracle',
    targetId: 'ds-dm-01',
    targetName: 'dm8-core-prod',
    targetType: 'DM8',
    mode: 'FULL_INCR',
    status: 'RUNNING',
    progress: 50.2,
    tableCount: 87,
    totalRows: 5_214_000_000,
    migratedRows: 2_617_000_000,
    updatedAt: '2026-11-09 15:26:38',
    owner: 'alice',
    deadLetters: 2,
  },
  {
    id: 'T-20261108-04',
    name: '账户中心存量迁移',
    sourceId: 'ds-ora-01',
    sourceName: 'ora-core-prod',
    sourceType: 'Oracle',
    targetId: 'ds-dm-01',
    targetName: 'dm8-core-prod',
    targetType: 'DM8',
    mode: 'FULL',
    status: 'FINISHED',
    progress: 100,
    tableCount: 23,
    totalRows: 32_000_000,
    migratedRows: 32_000_000,
    updatedAt: '2026-11-08 22:14:07',
    owner: 'bob',
    deadLetters: 0,
  },
  {
    id: 'T-20261107-02',
    name: '日志归档降级迁移',
    sourceId: 'ds-ora-01',
    sourceName: 'ora-core-prod',
    sourceType: 'Oracle',
    targetId: 'ds-dm-01',
    targetName: 'dm8-core-prod',
    targetType: 'DM8',
    mode: 'FULL',
    status: 'PAUSED',
    progress: 0.3,
    tableCount: 4,
    totalRows: 4_100_000_000,
    migratedRows: 12_300_000,
    updatedAt: '2026-11-09 12:02:55',
    owner: 'alice',
    deadLetters: 2,
  },
  {
    id: 'T-20261109-05',
    name: '支付库灰度双写',
    sourceId: 'ds-my-01',
    sourceName: 'mysql-uat-02',
    sourceType: 'MySQL',
    targetId: 'ds-pg-01',
    targetName: 'pg-uat-01',
    targetType: 'PostgreSQL',
    mode: 'FULL_INCR',
    status: 'FAILED',
    progress: 78.4,
    tableCount: 12,
    totalRows: 18_600_000,
    migratedRows: 14_582_000,
    updatedAt: '2026-11-09 09:41:12',
    owner: 'carol',
    deadLetters: 17,
  },
  {
    id: 'T-20261109-06',
    name: '报表库结构同步（草稿）',
    sourceId: 'ds-my-01',
    sourceName: 'mysql-uat-02',
    sourceType: 'MySQL',
    targetId: 'ds-pg-02',
    targetName: 'pg-report-dev',
    targetType: 'PostgreSQL',
    mode: 'FULL',
    status: 'DRAFT',
    progress: 0,
    tableCount: 5,
    totalRows: 2_100_000,
    migratedRows: 0,
    updatedAt: '2026-11-09 15:10:03',
    owner: 'dave',
    deadLetters: 0,
  },
];

/* ============================================================
   迁移进度快照
   ============================================================ */
function buildSeries(): TpsSeries[] {
  const rand = seeded(20261109);
  const a: number[] = [];
  const b: number[] = [];
  const c: number[] = [];
  for (let i = 0; i < 60; i++) {
    const wave = Math.sin((i / 60) * Math.PI * 3.2) * 900;
    const ramp = i < 8 ? (i + 1) * 420 : 0;
    a.push(Math.round(15200 + wave + rand() * 1500 + ramp));
    b.push(Math.round(18600 + Math.cos((i / 60) * Math.PI * 2.4) * 1100 + rand() * 1700));
    c.push(Math.round(13900 + Math.sin((i / 60) * Math.PI * 4.1) * 700 + rand() * 1300));
  }
  // 末点与 KPI 一致：16,980 + 19,540 + 15,820 = 52,340
  a[59] = 16_980;
  b[59] = 19_540;
  c[59] = 15_820;
  return [
    { name: '实例 A', color: '#22D3EE', values: a },
    { name: '实例 B', color: '#818CF8', values: b },
    { name: '实例 C', color: '#34D399', values: c },
  ];
}

function buildShards(): Shard[] {
  const total = 300;
  const list: Shard[] = [];
  for (let i = 0; i < total; i++) {
    const id = i + 1;
    let state: Shard['state'] = 'PENDING';
    if (i < 206) state = 'DONE';
    else if (i < 218) state = 'RUNNING';
    else if (i === 218 || i === 219) state = 'DEAD';
    const from = i * 2_000_000 + 1;
    const to = (i + 1) * 2_000_000;
    list.push({
      id,
      table: 'T_ORDER',
      state,
      rows: 2_000_000,
      pkFrom: String(from),
      pkTo: String(to),
      instance: state === 'RUNNING' ? ['实例 A', '实例 B', '实例 C'][i % 3] ?? '实例 A' : state === 'DONE' ? `实例 ${['A', 'B', 'C'][i % 3] ?? 'A'}` : '-',
      retries: state === 'DEAD' ? 3 : 0,
      updatedAt: state === 'PENDING' ? '-' : '15:2' + (i % 10) + ':0' + (i % 9),
    });
  }
  return list;
}

const TABLE_PROGRESS: TableProgress[] = [
  {
    table: 'T_ORDER',
    totalRows: 820_000_000,
    migratedRows: 412_000_000,
    tps: 32_110,
    shards: 410,
    doneShards: 206,
    failedShards: 0,
    status: 'RUNNING',
    avgRowBytes: 592,
  },
  {
    table: 'T_ACCT',
    totalRows: 32_000_000,
    migratedRows: 32_000_000,
    tps: 0,
    shards: 16,
    doneShards: 16,
    failedShards: 0,
    status: 'FINISHED',
    avgRowBytes: 262,
  },
  {
    table: 'T_LOG',
    totalRows: 4_100_000_000,
    migratedRows: 12_300_000,
    tps: 18_220,
    shards: 2050,
    doneShards: 7,
    failedShards: 2,
    status: 'RUNNING',
    avgRowBytes: 400,
  },
  {
    table: 'T_PAY',
    totalRows: 12_400_000,
    migratedRows: 12_100_000,
    tps: 2_010,
    shards: 7,
    doneShards: 6,
    failedShards: 0,
    status: 'RUNNING',
    avgRowBytes: 418,
  },
];

export function getProgress(taskId: string): ProgressSnapshot {
  const running = TASKS.find((t) => t.id === taskId && t.status === 'RUNNING') ?? TASKS[0];
  const task = running as TaskSummary;
  return {
    taskId: task.id,
    taskName: task.name,
    status: task.status,
    migratedRows: 412_000_000,
    totalRows: 820_000_000,
    tps: 52_340,
    latencySec: 1.2,
    deadShards: 2,
    activeInstances: 3,
    etaMinutes: 22,
    sampledAt: '15:26:40',
    bucketSeconds: 60,
    series: buildSeries(),
    shards: buildShards(),
    tables: TABLE_PROGRESS,
    limitRowsPerSec: null,
  };
}

export const DEAD_LETTERS: DeadLetter[] = [
  {
    id: 'DL-8841',
    shardId: 219,
    table: 'T_ORDER',
    pkRange: '437,000,001 – 439,000,000',
    errorCode: 'E5001',
    error: 'ORA-12899: value too large for column "ORDER_NO" (actual: 41, maximum: 32)',
    at: '2026-11-09 15:14:02',
    retries: 3,
    rows: 2_000_000,
  },
  {
    id: 'DL-8842',
    shardId: 220,
    table: 'T_ORDER',
    pkRange: '439,000,001 – 441,000,000',
    errorCode: 'E5001',
    error: '目标库唯一键冲突：UK_T_ORDER_NO 重复值（ORDER_NO=SO2026110900031）',
    at: '2026-11-09 15:15:37',
    retries: 3,
    rows: 2_000_000,
  },
];

export const CDC_LATENCY = {
  mode: 'TRIGGER_LOG' as const,
  p95Sec: 1.2,
  p99Sec: 2.8,
  backlog: 1_842,
  failRate: 0.004,
  applyTps: 4_120,
  triggerInstalled: true,
  triggerTables: 87,
  triggerTotal: 87,
  updatedAt: '2026-11-09 15:26:35',
  trend: [1.6, 1.4, 1.5, 1.3, 1.2, 1.1, 1.3, 1.2, 1.0, 1.2, 1.3, 1.2],
};

export const CHANGES: ChangeTrace[] = [
  {
    seq: '0000000451',
    op: 'UPDATE',
    table: 'T_ORDER',
    pk: '88122301',
    appliedAt: '2026-11-09 15:26:33.221',
    sourceCommitAt: '2026-11-09 15:26:32.004',
    result: 'APPLIED',
    detail: 'STAT: "1" → "2", AMT: 12800.00 → 12900.00',
    latencyMs: 1217,
  },
  {
    seq: '0000000450',
    op: 'INSERT',
    table: 'T_ORDER',
    pk: '88122300',
    appliedAt: '2026-11-09 15:26:31.884',
    sourceCommitAt: '2026-11-09 15:26:30.771',
    result: 'APPLIED',
    detail: '整行插入，AMT=3680.50',
    latencyMs: 1113,
  },
  {
    seq: '0000000449',
    op: 'UPDATE',
    table: 'T_ORDER',
    pk: '88122298',
    appliedAt: '2026-11-09 15:26:30.512',
    sourceCommitAt: '2026-11-09 15:26:29.900',
    result: 'IGNORED',
    detail: 'TIMESTAMP_NEWER 策略：源端 C_TIME 早于目标端，已忽略并计数',
    latencyMs: 612,
  },
  {
    seq: '0000000448',
    op: 'DELETE',
    table: 'T_ORDER',
    pk: '88122297',
    appliedAt: '2026-11-09 15:26:29.033',
    sourceCommitAt: '2026-11-09 15:26:27.881',
    result: 'APPLIED',
    detail: '幂等删除，目标端影响行数 1',
    latencyMs: 1152,
  },
];

/* ============================================================
   比对报告
   ============================================================ */
export const COMPARE_REPORT: CompareReport = {
  jobId: 'CMP-20261109-0007',
  taskId: 'T-20261109-01',
  taskName: 'T_ORDER 全量迁移',
  verdict: 'WARN',
  passRate: 99.993,
  comparedRows: 820_000_000,
  totalBlocks: 8_200,
  diffBlocks: 12,
  diffRows: 5_846,
  costText: '11 分 32 秒',
  operator: 'alice',
  sourceSnapshotAt: '2026-11-09 14:02:11',
  targetSnapshotAt: '2026-11-09 14:02:13',
  startedAt: '2026-11-09 14:02:11',
  algorithm: 'COUNT + SUM + HASH(CRC32)',
  threshold: 0.001,
  ignoreRules: [
    { name: '大小写', detail: '忽略 VARCHAR 大小写差异', enabled: false },
    { name: '浮点容差', detail: 'ABS(a-b) <= 0.001', enabled: true },
    { name: '金额尾零', detail: 'DECIMAL 尾随 0 归一化', enabled: true },
    { name: '时区换算', detail: 'TIMESTAMP 按 Asia/Shanghai 对齐', enabled: true },
  ],
  distribution: [
    { table: 'T_LOG', rows: 5_102, blockRate: 8.4 },
    { table: 'T_ACCT', rows: 744, blockRate: 3.6 },
  ],
  diffByType: { VALUE_DIFF: 5_820, ONLY_SOURCE: 24, ONLY_TARGET: 2 },
};

export function buildDiffs(): DiffRow[] {
  const rand = seeded(8891);
  const rows: DiffRow[] = [];
  const cols = ['MEMO', 'AMT', 'STAT', 'UPD_TIME', 'ORDER_NO'];
  for (let i = 0; i < 180; i++) {
    const isLog = i < 156;
    const table = isLog ? 'T_LOG' : 'T_ACCT';
    const r = rand();
    const type: DiffRow['type'] = r < 0.93 ? 'VALUE_DIFF' : r < 0.975 ? 'ONLY_SOURCE' : 'ONLY_TARGET';
    const pk = String(9_920_000 + i * 7 + (isLog ? 0 : 100_000));
    const col = type === 'VALUE_DIFF' ? (cols[i % cols.length] ?? 'MEMO') : null;
    rows.push({
      id: i + 1,
      table,
      pk,
      type,
      column: col,
      sourceValue:
        type === 'ONLY_TARGET'
          ? null
          : col === 'AMT'
            ? '12800.00'
            : col === 'MEMO'
              ? "'abc订单备注-原始值'"
              : col === 'STAT'
                ? "'1'"
                : col === 'UPD_TIME'
                  ? '2026-11-08 22:41:07'
                  : "'SO2026110800193'",
      targetValue:
        type === 'ONLY_SOURCE'
          ? null
          : col === 'AMT'
            ? '12900.00'
            : col === 'MEMO'
              ? "'abd订单备注-目标值'"
              : col === 'STAT'
                ? "'2'"
                : col === 'UPD_TIME'
                  ? '2026-11-08 22:41:09'
                  : "'SO2026110800195'",
      blockId: `BLK-${String(1200 + (i % 12) * 41).padStart(5, '0')}`,
      detectedAt: '2026-11-09 14:0' + (i % 9) + ':' + String(10 + (i % 49)).padStart(2, '0'),
    });
  }
  return rows;
}

/* ============================================================
   切流计划
   ============================================================ */
export const CUTOVER_PLAN: CutoverPlan = {
  id: 'CUT-20261109-01',
  planName: 'APP 核心库 Oracle→DM8 切流',
  taskId: 'T-20261109-01',
  taskName: 'T_ORDER 全量迁移',
  sourceName: 'ora-core-prod',
  sourceType: 'Oracle',
  targetName: 'dm8-core-prod',
  targetType: 'DM8',
  state: 'GRAY_WRITE',
  stateSince: '2026-11-09 14:52:10',
  stateElapsed: '34 分 31 秒',
  adminUser: 'alice',
  gates: [
    {
      key: 'passRate',
      name: '比对通过率',
      actual: 99.993,
      threshold: 99.99,
      unit: '%',
      pass: true,
      compare: '>=',
      evidence: '14:02 全量比对 CMP-20261109-0007',
      at: '2026-11-09 14:02',
    },
    {
      key: 'latencyP95',
      name: '增量延迟 P95',
      actual: 1.8,
      threshold: 5,
      unit: 's',
      pass: true,
      compare: '<=',
      evidence: '近 15 分钟滑动窗口',
      at: '实时',
    },
    {
      key: 'failRate',
      name: '双写失败率',
      actual: 0.004,
      threshold: 0.01,
      unit: '%',
      pass: true,
      compare: '<=',
      evidence: '近 15 分钟 1,842 次写入',
      at: '2026-11-09 15:20',
    },
    {
      key: 'deadLetters',
      name: '未处理死信',
      actual: 0,
      threshold: 0,
      unit: '条',
      pass: true,
      compare: '=',
      evidence: '死信队列已清空（2 条已重放成功）',
      at: '2026-11-09 15:18',
    },
  ],
  matrix: {
    readSourcePct: 40,
    readTargetPct: 60,
    writeSource: true,
    writeTarget: true,
    writeDual: true,
  },
  grayPct: 50,
  grayDimensions: ['比例', '主键哈希'],
  grayTables: ['T_ORDER', 'T_PAY'],
  gateSkipped: [],
  observeWindowH: 24,
  autoRollbackErrorPct: 1,
  timeline: [
    {
      at: '2026-11-09 15:12:41',
      actor: 'alice',
      action: '灰度放量',
      detail: '读流量灰度 10% → 50%（按比例 + 主键哈希组合维度）',
      level: 'info',
    },
    {
      at: '2026-11-09 14:58:07',
      actor: 'alice',
      action: '卡点校验',
      detail: '4 项卡点全部通过：通过率 99.993%、延迟 P95 1.8s、失败率 0.004%、死信 0',
      level: 'info',
    },
    {
      at: '2026-11-09 14:52:10',
      actor: 'alice',
      action: '推进阶段',
      detail: 'GRAY_READ → GRAY_WRITE，开启目标端双写（写源 + 写目标）',
      level: 'warn',
    },
    {
      at: '2026-11-09 14:20:33',
      actor: 'bob',
      action: '灰度放量',
      detail: '读流量灰度 5% → 10%',
      level: 'info',
    },
    {
      at: '2026-11-09 14:05:12',
      actor: 'bob',
      action: '推进阶段',
      detail: 'PRE_CHECK → GRAY_READ，读流量 5% 切入目标库',
      level: 'info',
    },
    {
      at: '2026-11-09 13:58:20',
      actor: 'alice',
      action: '创建计划',
      detail: '计划 CUT-20261109-01 创建，观察期 24h，自动回滚阈值 错误率>1%',
      level: 'info',
    },
  ],
};

/** 与后端 CutoverState.java 一致的 9 状态 */
export const CUTOVER_STATES = [
  'DRAFT',
  'PRE_CHECK',
  'PRE_CHECK_FAILED',
  'GRAY_READ',
  'GRAY_WRITE',
  'FULL_TARGET',
  'OBSERVE',
  'DONE',
  'ROLLED_BACK',
] as const;

/* ============================================================
   系统：告警 / 审计 / 用户
   ============================================================ */
export const ALERT_CHANNELS: AlertChannel[] = [
  {
    id: 'AL-01',
    name: 'DBA 值班群（企业微信）',
    kind: 'WECOM',
    target: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=9f3a****',
    enabled: true,
    severities: ['P0', 'P1'],
    lastSentAt: '2026-11-09 15:14:05',
    lastResult: '成功（死信告警 P1）',
  },
  {
    id: 'AL-02',
    name: '运维邮件组',
    kind: 'EMAIL',
    target: 'dba-oncall@example.com',
    enabled: true,
    severities: ['P0', 'P1', 'P2'],
    lastSentAt: '2026-11-09 09:41:18',
    lastResult: '成功（任务失败 P0）',
  },
  {
    id: 'AL-03',
    name: '通用 Webhook（内部告警平台）',
    kind: 'WEBHOOK',
    target: 'https://alert.internal.example.com/hook/dbsync',
    enabled: false,
    severities: ['P0'],
    lastSentAt: '2026-11-07 18:20:44',
    lastResult: '失败（HTTP 502，重试 3 次后放弃）',
  },
];

const AUDIT_SEED: Array<[string, string, string, string, AuditLog['result'], string]> = [
  ['15:26:11', 'alice', 'CDC_MODE_CHANGE', '任务 T-20261109-01 → TRIGGER_LOG', 'SUCCESS', '10.8.12.31'],
  ['15:22:04', 'alice', 'GRAY_SCALE', '计划 CUT-20261109-01 灰度 10% → 50%', 'SUCCESS', '10.8.12.31'],
  ['15:18:52', 'bob', 'DEAD_LETTER_REPLAY', '死信 DL-8841 / 分片 #219', 'SUCCESS', '10.8.12.77'],
  ['15:15:37', 'system', 'DEAD_LETTER_CREATE', '分片 #220 重试耗尽进入死信', 'FAILED', '127.0.0.1'],
  ['15:02:19', 'carol', 'TASK_PAUSE', '任务 T-20261109-05', 'DENIED', '10.8.14.9'],
  ['14:58:07', 'alice', 'GATE_CHECK', '计划 CUT-20261109-01 卡点校验', 'SUCCESS', '10.8.12.31'],
  ['14:52:10', 'alice', 'CUTOVER_ADVANCE', 'GRAY_READ → GRAY_WRITE', 'SUCCESS', '10.8.12.31'],
  ['14:41:33', 'bob', 'DS_UPDATE', '数据源 ds-db2-01 连接池 4→6', 'SUCCESS', '10.8.12.77'],
  ['14:20:33', 'bob', 'GRAY_SCALE', '计划 CUT-20261109-01 灰度 5% → 10%', 'SUCCESS', '10.8.12.77'],
  ['14:02:11', 'alice', 'COMPARE_START', '任务 T-20261109-01 全量比对', 'SUCCESS', '10.8.12.31'],
  ['13:58:20', 'alice', 'CUTOVER_CREATE', '计划 CUT-20261109-01', 'SUCCESS', '10.8.12.31'],
  ['13:40:02', 'dave', 'DS_DELETE', '数据源 ds-old-test', 'FAILED', '10.8.15.2'],
];

export const AUDITS: AuditLog[] = AUDIT_SEED.map(([at, actor, action, object, result, ip], i) => ({
  id: `AU-${9000 + i}`,
  at: `2026-11-09 ${at}`,
  actor,
  action,
  object,
  result,
  ip,
  traceId: `TR-2026110915${String(20 + i).padStart(2, '0')}-${String(1000 + i)}`,
}));

export const USERS: SysUser[] = [
  {
    id: 'U-01',
    account: 'alice',
    name: 'Alice Chen',
    role: 'ADMIN',
    source: 'LDAP',
    lastLogin: '2026-11-09 15:22:04',
    enabled: true,
  },
  {
    id: 'U-02',
    account: 'bob',
    name: 'Bob Liu',
    role: 'OPERATOR',
    source: 'LDAP',
    lastLogin: '2026-11-09 15:18:52',
    enabled: true,
  },
  {
    id: 'U-03',
    account: 'carol',
    name: 'Carol Wang',
    role: 'OPERATOR',
    source: 'LDAP',
    lastLogin: '2026-11-09 14:41:10',
    enabled: true,
  },
  {
    id: 'U-04',
    account: 'dave',
    name: 'Dave Zhou',
    role: 'VIEWER',
    source: '本地账号',
    lastLogin: '2026-11-09 09:12:44',
    enabled: true,
  },
  {
    id: 'U-05',
    account: 'audit01',
    name: '合规审计（只读）',
    role: 'VIEWER',
    source: '本地账号',
    lastLogin: '2026-11-05 10:02:31',
    enabled: false,
  },
];

export const PERM_KEYS = [
  '查看大盘与报告',
  '数据源增删改',
  '任务创建与编辑',
  '启动/暂停/取消任务',
  '比对与重放修复',
  '反向修复源端',
  '切流推进与回滚',
  '跳过卡点',
  '用户与角色管理',
] as const;

export const ROLES: RoleMatrix[] = [
  {
    role: 'ADMIN',
    desc: '平台管理员：全部权限，含切流与跳过卡点',
    perms: {
      查看大盘与报告: true,
      数据源增删改: true,
      任务创建与编辑: true,
      '启动/暂停/取消任务': true,
      比对与重放修复: true,
      反向修复源端: true,
      切流推进与回滚: true,
      跳过卡点: true,
      用户与角色管理: true,
    },
  },
  {
    role: 'OPERATOR',
    desc: '运维工程师：可执行迁移、比对、重放，不可切流/改权限',
    perms: {
      查看大盘与报告: true,
      数据源增删改: true,
      任务创建与编辑: true,
      '启动/暂停/取消任务': true,
      比对与重放修复: true,
      反向修复源端: false,
      切流推进与回滚: false,
      跳过卡点: false,
      用户与角色管理: false,
    },
  },
  {
    role: 'VIEWER',
    desc: '只读审计：仅查看大盘、报告与审计日志',
    perms: {
      查看大盘与报告: true,
      数据源增删改: false,
      任务创建与编辑: false,
      '启动/暂停/取消任务': false,
      比对与重放修复: false,
      反向修复源端: false,
      切流推进与回滚: false,
      跳过卡点: false,
      用户与角色管理: false,
    },
  },
];

export const LOG_LINES = [
  { ts: '15:26:38.412', level: 'INFO', text: '[shard-207] 提交 checkpoint 1,999,842 行 / 耗时 2.5s' },
  { ts: '15:26:38.009', level: 'INFO', text: '[writer-3] batch insert 1000 rows → dm8-core-prod 完成 312ms' },
  { ts: '15:26:37.771', level: 'WARN', text: '[reader-1] 分片 #219 重试 3/3 仍失败：ORA-12899 ORDER_NO 超长，转入死信队列' },
  { ts: '15:26:37.204', level: 'INFO', text: '[shard-218] 开始区间 (435000001, 437000000] 读取' },
  { ts: '15:26:36.889', level: 'INFO', text: '[lease] 实例 A 续约成功，租约到期 15:27:06' },
  { ts: '15:26:36.112', level: 'ERROR', text: '[trigger-consumer] SYNC_LOG_11 消费异常：唯一键冲突，已记录并继续' },
  { ts: '15:26:35.503', level: 'INFO', text: '[tps] 窗口 1min：52,340 行/秒（A 16,980 / B 19,540 / C 15,820）' },
  { ts: '15:26:34.921', level: 'INFO', text: '[checkpoint] 已持久化 218 个分片进度，落后写入 42ms' },
  { ts: '15:26:34.220', level: 'WARN', text: '[throttle] 全局限速 60,000 行/秒，当前未触发' },
  { ts: '15:26:33.010', level: 'INFO', text: '[precheck] 目标表 T_ORDER 空间剩余 1.8TB，满足预估 486GB' },
];

/* ============================================================
   后端真实 DTO 形状的仿真数据（字段名与 Controller 一致）
   ============================================================ */
import type {
  AlertsPayload,
  DeadLetterView,
  DdlDiffView,
  MigrationProgressView,
  MonitorOverview,
  ShardItem,
  ShardPage,
  TableProgressView,
  TaskView,
} from '../../types';

export const T1 = '395179382747508736';
export const T2 = '395179382747508737';
export const T3 = '395179382747508738';
export const T4 = '395179382747508739';
export const T5 = '395179382747508740';

export const TASK_VIEWS: TaskView[] = [
  {
    id: T1,
    name: 'T_ORDER 全量迁移',
    sourceDsId: 'ds-ora-01',
    targetDsId: 'ds-dm-01',
    state: 'RUNNING',
    groupCount: 87,
    concurrency: 4,
    batchSize: 1000,
    shardRows: 2_000_000,
    rateLimitRows: null,
    maxRetry: 3,
    incrementalMode: 'TRIGGER_LOG',
    consumerConcurrency: 8,
    lastError: null,
  },
  {
    id: T2,
    name: '账户中心存量迁移',
    sourceDsId: 'ds-ora-01',
    targetDsId: 'ds-dm-01',
    state: 'FINISHED',
    groupCount: 23,
    concurrency: 8,
    batchSize: 2000,
    shardRows: 2_000_000,
    rateLimitRows: null,
    maxRetry: 3,
    incrementalMode: null,
    consumerConcurrency: null,
    lastError: null,
  },
  {
    id: T3,
    name: '日志归档降级迁移',
    sourceDsId: 'ds-ora-01',
    targetDsId: 'ds-dm-01',
    state: 'PAUSED',
    groupCount: 4,
    concurrency: 2,
    batchSize: 500,
    shardRows: 2_000_000,
    rateLimitRows: 20_000,
    maxRetry: 3,
    incrementalMode: null,
    consumerConcurrency: null,
    lastError: '限速触发：全局限速 20,000 行/秒',
  },
  {
    id: T4,
    name: '支付库灰度双写',
    sourceDsId: 'ds-my-01',
    targetDsId: 'ds-pg-01',
    state: 'FAILED',
    groupCount: 12,
    concurrency: 4,
    batchSize: 1000,
    shardRows: 500_000,
    rateLimitRows: null,
    maxRetry: 3,
    incrementalMode: 'SDK_DUAL_WRITE',
    consumerConcurrency: 4,
    lastError: '目标端连接失败：pg-uat-01 不可达（E2004）',
  },
  {
    id: T5,
    name: '报表库结构同步',
    sourceDsId: 'ds-my-01',
    targetDsId: 'ds-pg-02',
    state: 'READY',
    groupCount: 5,
    concurrency: 2,
    batchSize: 1000,
    shardRows: 100_000,
    rateLimitRows: null,
    maxRetry: 1,
    incrementalMode: null,
    consumerConcurrency: null,
    lastError: null,
  },
];

export const PROGRESS_MAP: Record<string, MigrationProgressView> = {
  [T1]: {
    taskId: T1,
    taskState: 'RUNNING',
    totalShards: 410,
    shardCounts: { SUCCESS: 206, RUNNING: 12, PENDING: 190, FAILED: 2 },
    committedRows: 412_000_000,
    estimatedRows: 820_000_000,
    percent: 50.2,
    successShards: 206,
    failedShards: 2,
    finished: false,
  },
  [T2]: {
    taskId: T2,
    taskState: 'FINISHED',
    totalShards: 16,
    shardCounts: { SUCCESS: 16 },
    committedRows: 32_000_000,
    estimatedRows: 32_000_000,
    percent: 100,
    successShards: 16,
    failedShards: 0,
    finished: true,
  },
  [T3]: {
    taskId: T3,
    taskState: 'PAUSED',
    totalShards: 2050,
    shardCounts: { SUCCESS: 7, RUNNING: 4, PENDING: 2039 },
    committedRows: 12_300_000,
    estimatedRows: 4_100_000_000,
    percent: 0.3,
    successShards: 7,
    failedShards: 0,
    finished: false,
  },
  [T4]: {
    taskId: T4,
    taskState: 'FAILED',
    totalShards: 93,
    shardCounts: { SUCCESS: 73, PENDING: 3, FAILED: 17 },
    committedRows: 14_582_000,
    estimatedRows: 18_600_000,
    percent: 78.4,
    successShards: 73,
    failedShards: 17,
    finished: false,
  },
  [T5]: {
    taskId: T5,
    taskState: 'READY',
    totalShards: 11,
    shardCounts: { PENDING: 11 },
    committedRows: 0,
    estimatedRows: 2_100_000,
    percent: 0,
    successShards: 0,
    failedShards: 0,
    finished: false,
  },
};

/* ---------------- 逐分片进度（GET /api/tasks/{id}/shards 的真实结构） ---------------- */

const SHARD_ROWS = 2_000_000;

interface ShardSpec {
  total: number;
  success: number;
  running: number;
  failed: number;
  /** 任务处于 PAUSED 时，未完成分片标 PAUSED（浅灰） */
  paused?: boolean;
}

const SHARD_SPEC: Record<string, ShardSpec> = {
  [T1]: { total: 410, success: 206, running: 12, failed: 2 },
  [T2]: { total: 16, success: 16, running: 0, failed: 0 },
  [T3]: { total: 2050, success: 7, running: 4, failed: 0, paused: true },
  [T4]: { total: 93, success: 73, running: 0, failed: 17 },
  [T5]: { total: 11, success: 0, running: 0, failed: 0 },
};

const SHARD_CACHE = new Map<string, ShardItem[]>();

/** 按任务生成一份确定性的分片数组（不随机，截图与联调可复现） */
export function shardsOf(taskId: string): ShardItem[] {
  const cached = SHARD_CACHE.get(taskId);
  if (cached) return cached;
  const spec = SHARD_SPEC[taskId];
  if (!spec) return [];
  const list: ShardItem[] = [];
  for (let n = 1; n <= spec.total; n += 1) {
    const start = (n - 1) * SHARD_ROWS + 1;
    const end = n * SHARD_ROWS;
    let state = 'PENDING';
    if (n <= spec.success) state = 'SUCCESS';
    else if (n <= spec.success + spec.running) state = 'RUNNING';
    else if (n > spec.total - spec.failed) state = 'FAILED';
    else if (spec.paused) state = 'PAUSED';
    const committed =
      state === 'SUCCESS'
        ? SHARD_ROWS
        : state === 'RUNNING'
          ? Math.floor(SHARD_ROWS * 0.62)
          : state === 'FAILED'
            ? Math.floor(SHARD_ROWS * 0.42)
            : 0;
    list.push({
      shardId: 100_000 + n,
      mappingId: 10,
      shardNo: n,
      rangeKind: 'NUMERIC',
      pkStart: String(start),
      pkEnd: String(end),
      state,
      leaseOwner: state === 'RUNNING' ? 'host:8080#a1b2c3' : null,
      leaseExpireAt: state === 'RUNNING' ? '2026-11-09T15:27:06Z' : null,
      lastPk: state === 'SUCCESS' ? String(end) : state === 'RUNNING' ? String(start + committed) : null,
      committedRows: committed,
      sourceRows: SHARD_ROWS,
      attempt: state === 'FAILED' ? 3 : 0,
      lastError:
        state === 'FAILED'
          ? 'ORA-12899: value too large for column "ORDER_NO" (actual: 41, maximum: 32)'
          : null,
      startedAt: state === 'PENDING' || state === 'PAUSED' ? null : '2026-11-09T15:02:11Z',
      finishedAt: state === 'SUCCESS' ? '2026-11-09T15:04:33Z' : null,
    });
  }
  SHARD_CACHE.set(taskId, list);
  return list;
}

export function shardPage(taskId: string, state: string, page: number, size: number): ShardPage {
  const all = shardsOf(taskId);
  const filtered = state ? all.filter((s) => s.state === state) : all;
  const p = Math.max(1, page);
  const s = Math.min(1000, Math.max(1, size));
  return {
    total: filtered.length,
    page: p,
    size: s,
    items: filtered.slice((p - 1) * s, p * s),
  };
}

/* ---------------- 表级进度（GET /api/tasks/{id}/tables/progress 的真实结构） ---------------- */

export const TABLE_PROGRESS_VIEWS: Record<string, TableProgressView> = {
  [T1]: {
    taskId: T1,
    items: [
      {
        mappingId: 10,
        sourceTable: 'T_ORDER',
        targetTable: 'T_ORDER',
        totalShards: 340,
        successShards: 200,
        failedShards: 2,
        deadShards: 1,
        committedRows: 380_000_000,
        sourceRows: 640_000_000,
        percent: 59.38,
        rowsFromEstimate: false,
      },
      {
        mappingId: 11,
        sourceTable: 'T_ACCT',
        targetTable: 'T_ACCT',
        totalShards: 60,
        successShards: 6,
        failedShards: 0,
        deadShards: 0,
        committedRows: 30_000_000,
        sourceRows: 0,
        percent: null,
        rowsFromEstimate: false,
      },
      {
        mappingId: 12,
        sourceTable: 'T_SETTLE',
        targetTable: 'T_SETTLE_BAK',
        totalShards: 10,
        successShards: 0,
        failedShards: 0,
        deadShards: 0,
        committedRows: 2_000_000,
        sourceRows: 150_000_000,
        percent: 1.33,
        rowsFromEstimate: true,
      },
    ],
  },
  [T2]: {
    taskId: T2,
    items: [
      {
        mappingId: 20,
        sourceTable: 'T_ACCT',
        targetTable: 'T_ACCT',
        totalShards: 16,
        successShards: 16,
        failedShards: 0,
        deadShards: 0,
        committedRows: 32_000_000,
        sourceRows: 32_000_000,
        percent: 100,
        rowsFromEstimate: false,
      },
    ],
  },
  [T3]: {
    taskId: T3,
    items: [
      {
        mappingId: 30,
        sourceTable: 'T_LOG',
        targetTable: 'T_LOG',
        totalShards: 2050,
        successShards: 7,
        failedShards: 0,
        deadShards: 0,
        committedRows: 12_300_000,
        sourceRows: 4_100_000_000,
        percent: 0.3,
        rowsFromEstimate: false,
      },
    ],
  },
  [T4]: {
    taskId: T4,
    items: [
      {
        mappingId: 40,
        sourceTable: 'T_PAY',
        targetTable: 't_pay',
        totalShards: 50,
        successShards: 34,
        failedShards: 12,
        deadShards: 4,
        committedRows: 9_120_000,
        sourceRows: 10_000_000,
        percent: 91.2,
        rowsFromEstimate: false,
      },
      {
        mappingId: 41,
        sourceTable: 'T_PAY_ITEM',
        targetTable: 't_pay_item',
        totalShards: 33,
        successShards: 30,
        failedShards: 4,
        deadShards: 0,
        committedRows: 5_262_000,
        sourceRows: 6_600_000,
        percent: 79.73,
        rowsFromEstimate: true,
      },
      {
        mappingId: 42,
        sourceTable: 'T_CFG',
        targetTable: 't_cfg',
        totalShards: 10,
        successShards: 9,
        failedShards: 1,
        deadShards: 0,
        committedRows: 200_000,
        sourceRows: 200_000,
        percent: 100,
        rowsFromEstimate: false,
      },
    ],
  },
  [T5]: {
    taskId: T5,
    items: [
      {
        mappingId: 50,
        sourceTable: 'T_REPORT',
        targetTable: 't_report',
        totalShards: 11,
        successShards: 0,
        failedShards: 0,
        deadShards: 0,
        committedRows: 0,
        sourceRows: 2_100_000,
        percent: 0,
        rowsFromEstimate: false,
      },
    ],
  },
};

/* ---------------- DDL 差异（GET /api/tasks/{id}/mappings/{mid}/ddl-diff） ---------------- */

export const DDL_DIFF_T_ORDER: DdlDiffView = {
  mappingId: 10,
  sourceTable: 'T_ORDER',
  targetTable: 'T_ORDER',
  targetExists: true,
  generatedDdl: [
    'CREATE TABLE "APP"."T_ORDER" (',
    '  "ID"        DECIMAL(19,0) NOT NULL,',
    '  "BIZ_NO"    VARCHAR(32),',
    '  "AMT"       DECIMAL(38,10),',
    '  "C_TIME"    TIMESTAMP(0) NOT NULL,',
    '  "MEMO"      VARCHAR(1024),',
    '  "SRC_EXT"   VARCHAR(64)',
    ');',
    'ALTER TABLE "APP"."T_ORDER" ADD CONSTRAINT "PK_T_ORDER" PRIMARY KEY ("ID");',
  ],
  columns: [
    { name: 'ID', sourceType: 'NUMBER(19)', generatedType: 'DECIMAL(19,0)', targetType: 'DECIMAL(19,0)', status: 'OK' },
    { name: 'BIZ_NO', sourceType: 'VARCHAR2(32)', generatedType: 'VARCHAR(32)', targetType: 'VARCHAR(32)', status: 'OK' },
    {
      name: 'AMT',
      sourceType: 'NUMBER',
      generatedType: 'DECIMAL(38,10)',
      targetType: 'DECIMAL(30,6)',
      status: 'TYPE_MISMATCH',
      note: '目标精度更小，可能截断',
    },
    { name: 'C_TIME', sourceType: 'DATE', generatedType: 'TIMESTAMP(0)', targetType: 'TIMESTAMP(0)', status: 'OK' },
    { name: 'MEMO', sourceType: 'VARCHAR2(512)', generatedType: 'VARCHAR(1024)', targetType: null, status: 'NEW' },
    { name: 'SRC_EXT', sourceType: 'VARCHAR2(64)', generatedType: 'VARCHAR(64)', targetType: null, status: 'NEW' },
    {
      name: 'LEGACY_FLAG',
      sourceType: '—',
      generatedType: '—',
      targetType: 'CHAR(5)',
      status: 'EXTRA',
      note: '目标表存在源端没有的列',
    },
  ],
  indexes: [
    { name: 'PK_T_ORDER', status: 'OK' },
    { name: 'IDX_T_ORDER_C_TIME', status: 'MISSING' },
  ],
  summary: { newColumns: 2, typeMismatch: 1, missingIndexes: 1, extraColumns: 1 },
};

const HIST = buildSeries();const hist0 = HIST[0]?.values ?? [];
const hist1 = HIST[1]?.values ?? [];
const hist2 = HIST[2]?.values ?? [];

export const TPS_HISTORY: Record<string, number[]> = {
  [T1]: hist0,
  [T3]: hist2,
  [T4]: hist1,
};

export const OVERVIEW: MonitorOverview = {
  taskCounts: { RUNNING: 1, PAUSED: 1, FINISHED: 1, FAILED: 1, READY: 1 },
  totalTasks: TASK_VIEWS.length,
  shardCounts: { SUCCESS: 302, RUNNING: 16, PENDING: 2243, FAILED: 19 },
  shardHeat: {
    [T1]: { SUCCESS: 206, RUNNING: 12, PENDING: 190, FAILED: 2 },
    [T2]: { SUCCESS: 16 },
    [T3]: { SUCCESS: 7, RUNNING: 4, PENDING: 2039 },
    [T4]: { SUCCESS: 73, PENDING: 3, FAILED: 17 },
    [T5]: { PENDING: 11 },
  },
  committedRows: 470_582_000,
  tps: { [T1]: 32_110, [T3]: 18_220, [T4]: 2_010 },
  totalTps: 52_340,
  deadLetterOpen: 2,
  aliveInstances: 3,
  cutoverPlans: { GRAY_WRITE: 1 },
  recentAlerts: [
    { rule: 'DEAD_LETTER', title: '任务 T_ORDER 全量迁移 新增 2 条死信', at: '15:14:05' },
    { rule: 'TASK_FAILED', title: '任务 支付库灰度双写 进入 FAILED（目标库不可达）', at: '09:41:18' },
    { rule: 'CDC_LAG', title: '任务 日志归档降级迁移 增量积压 1.8 万条', at: '08:22:41' },
  ],
  tpsHistory: TPS_HISTORY,
};

export const DEAD_LETTER_VIEWS: DeadLetterView[] = [
  {
    id: '8841001',
    taskId: T1,
    tableMappingId: 'MAP-T_ORDER-01',
    sourceType: 'Oracle',
    errorCode: 'E5001',
    errorMsg: 'ORA-12899: value too large for column "ORDER_NO" (actual: 41, maximum: 32)',
    status: 'OPEN',
    retry: 3,
    pkJson: '{"ID":[437000001,439000000]}',
    replayBy: null,
  },
  {
    id: '8841002',
    taskId: T1,
    tableMappingId: 'MAP-T_ORDER-01',
    sourceType: 'Oracle',
    errorCode: 'E5001',
    errorMsg: '目标库唯一键冲突：UK_T_ORDER_NO 重复值（ORDER_NO=SO2026110900031）',
    status: 'OPEN',
    retry: 3,
    pkJson: '{"ID":[439000001,441000000]}',
    replayBy: null,
  },
];

/** 告警（GET /api/system/alerts 真实结构：channels/rules/recent） */
export const ALERTS_PAYLOAD: AlertsPayload = {
  channels: ['LOG', 'WECOM', 'EMAIL'],
  rules: [
    { code: 'LAG_HIGH', description: '增量延迟 P95 > 5s 持续 3 分钟', editable: false },
    { code: 'FAIL_RATE_HIGH', description: '双写失败率 > 0.01% 持续 5 分钟', editable: false },
    { code: 'TASK_FAILED', description: '任务进入 FAILED 状态', editable: false },
    { code: 'DEADLETTER_NEW', description: '新增死信（分片重试耗尽）', editable: false },
    { code: 'DATASOURCE_DOWN', description: '数据源探活连续 3 次失败', editable: false },
    { code: 'LEASE_TAKEOVER', description: '实例租约被其他实例接管', editable: false },
  ],
  recent: [
    {
      rule: 'DEADLETTER_NEW',
      title: 'T_ORDER 全量迁移 新增 2 条死信',
      detail: '分片 #219 / #220 重试 3 次耗尽，已进入死信队列',
      firedAt: '2026-11-09 15:14:05',
      channels: ['WECOM', 'LOG'],
    },
    {
      rule: 'TASK_FAILED',
      title: '支付库灰度双写 进入 FAILED',
      detail: '目标库 pg-uat-01 不可达（E2004），SDK 侧已停止双写',
      firedAt: '2026-11-09 09:41:18',
      channels: ['WECOM', 'EMAIL', 'LOG'],
    },
    {
      rule: 'LAG_HIGH',
      title: '日志归档降级迁移 增量积压 1.8 万条',
      detail: '延迟 P95 12.4s（阈值 5s），已自动降速生产端',
      firedAt: '2026-11-09 08:22:41',
      channels: ['LOG'],
    },
  ],
};
