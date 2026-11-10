-- ============================================================================
-- V1__init.sql  元数据库初始化（18 张表）
-- 目标：一份脚本同时可在 MySQL 8.0 与 H2 2.x(MODE=MySQL) 上执行（架构 §7.3）
--
-- 双方言兼容子集（重要）：
--   * 时间统一 DATETIME(3)（H2 映射为 TIMESTAMP；MySQL 无 2038 问题）
--   * JSON 列一律 TEXT（应用层序列化，避免依赖 JSON 函数）
--   * 布尔用 TINYINT（不带显示宽度，H2 不接受 TINYINT(1)）
--   * MEDIUMTEXT → TEXT
--   * 唯一键用命名约束 CONSTRAINT uk_x UNIQUE (...)
--   * 普通索引用独立 CREATE INDEX 语句（不用内联 KEY）
--   * 不使用表尾 ENGINE=/COMMENT=（MySQL 与 H2 的语法不兼容）；
--     表/列语义说明见文件内注释与架构 §7.2
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. ds_datasource  数据源
-- ---------------------------------------------------------------------------
CREATE TABLE ds_datasource (
  id                BIGINT       NOT NULL,
  name              VARCHAR(64)  NOT NULL,
  db_type           VARCHAR(16)  NOT NULL,
  env               VARCHAR(8)   NOT NULL DEFAULT 'UAT',
  host              VARCHAR(128) NOT NULL,
  port              INT          NOT NULL,
  database_name     VARCHAR(128)          DEFAULT NULL,
  schema_name       VARCHAR(128)          DEFAULT NULL,
  username          VARCHAR(128) NOT NULL,
  password_cipher   VARCHAR(512) NOT NULL,
  key_version       SMALLINT     NOT NULL DEFAULT 1,
  jdbc_params       VARCHAR(1024)         DEFAULT NULL,
  identifier_case   VARCHAR(16)  NOT NULL DEFAULT 'LOWER',
  pool_max_size     INT          NOT NULL DEFAULT 8,
  pool_min_idle     INT          NOT NULL DEFAULT 2,
  conn_timeout_ms   INT          NOT NULL DEFAULT 10000,
  fetch_size        INT          NOT NULL DEFAULT 1000,
  health_status     VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
  last_probe_at     DATETIME(3)           DEFAULT NULL,
  last_probe_error  VARCHAR(512)          DEFAULT NULL,
  version           INT          NOT NULL DEFAULT 0,
  created_by        VARCHAR(64)           DEFAULT NULL,
  created_at        DATETIME(3)  NOT NULL,
  updated_at        DATETIME(3)  NOT NULL,
  deleted_at        DATETIME(3)           DEFAULT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_ds_name UNIQUE (name)
);

CREATE INDEX idx_ds_type_env ON ds_datasource (db_type, env);
CREATE INDEX idx_ds_health ON ds_datasource (health_status, last_probe_at);

-- ---------------------------------------------------------------------------
-- 2. sync_task  同步任务（聚合根）
-- ---------------------------------------------------------------------------
CREATE TABLE sync_task (
  id                     BIGINT       NOT NULL,
  name                   VARCHAR(128) NOT NULL,
  description            VARCHAR(512)          DEFAULT NULL,
  source_ds_id           BIGINT       NOT NULL,
  target_ds_id           BIGINT       NOT NULL,
  state                  VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
  phase                  VARCHAR(16)  NOT NULL DEFAULT 'MIGRATION',
  concurrency            INT          NOT NULL DEFAULT 4,
  batch_size             INT          NOT NULL DEFAULT 1000,
  shard_rows             INT          NOT NULL DEFAULT 2000000,
  rate_limit_rows        BIGINT       NOT NULL DEFAULT 0,
  write_rate_limit       BIGINT       NOT NULL DEFAULT 0,
  max_retry              INT          NOT NULL DEFAULT 3,
  incremental_mode       VARCHAR(24)  NOT NULL DEFAULT 'TRIGGER_LOG',
  group_count            INT          NOT NULL DEFAULT 16,
  consumer_concurrency   INT          NOT NULL DEFAULT 8,
  poll_interval_ms       INT          NOT NULL DEFAULT 5000,
  conflict_policy        VARCHAR(24)  NOT NULL DEFAULT 'SOURCE_WINS',
  compare_block_rows     INT          NOT NULL DEFAULT 100000,
  compare_parallelism    INT          NOT NULL DEFAULT 8,
  compare_pass_threshold DECIMAL(9,6) NOT NULL DEFAULT 0.999900,
  normalize_json         TEXT                  DEFAULT NULL,
  gate_compare_pass      DECIMAL(9,6) NOT NULL DEFAULT 0.999900,
  gate_lag_p95_ms        BIGINT       NOT NULL DEFAULT 5000,
  gate_fail_rate         DECIMAL(9,6) NOT NULL DEFAULT 0.000100,
  started_at             DATETIME(3)           DEFAULT NULL,
  finished_at            DATETIME(3)           DEFAULT NULL,
  paused_at              DATETIME(3)           DEFAULT NULL,
  last_error             VARCHAR(1000)         DEFAULT NULL,
  version                INT          NOT NULL DEFAULT 0,
  created_by             VARCHAR(64)           DEFAULT NULL,
  created_at             DATETIME(3)  NOT NULL,
  updated_at             DATETIME(3)  NOT NULL,
  deleted_at             DATETIME(3)           DEFAULT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_task_state ON sync_task (state, updated_at);
CREATE INDEX idx_task_ds ON sync_task (source_ds_id, target_ds_id);

-- ---------------------------------------------------------------------------
-- 3. sync_table_mapping  表映射
-- ---------------------------------------------------------------------------
CREATE TABLE sync_table_mapping (
  id                 BIGINT       NOT NULL,
  task_id            BIGINT       NOT NULL,
  source_schema      VARCHAR(128) NOT NULL,
  source_table       VARCHAR(128) NOT NULL,
  target_schema      VARCHAR(128)          DEFAULT NULL,
  target_table       VARCHAR(128) NOT NULL,
  source_pk_json     TEXT                  DEFAULT NULL,
  target_pk_json     TEXT                  DEFAULT NULL,
  row_count_estimate BIGINT       NOT NULL DEFAULT -1,
  has_primary_key    TINYINT      NOT NULL DEFAULT 1,
  range_kind         VARCHAR(8)   NOT NULL DEFAULT 'NUMERIC',
  generated_ddl      TEXT                  DEFAULT NULL,
  risk_tags          TEXT                  DEFAULT NULL,
  ddl_applied        TINYINT      NOT NULL DEFAULT 0,
  enabled            TINYINT      NOT NULL DEFAULT 1,
  version            INT          NOT NULL DEFAULT 0,
  created_at         DATETIME(3)  NOT NULL,
  updated_at         DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_map UNIQUE (task_id, source_schema, source_table)
);

CREATE INDEX idx_map_task ON sync_table_mapping (task_id, enabled);

-- ---------------------------------------------------------------------------
-- 4. sync_column_mapping  列映射（含人工覆盖）
-- ---------------------------------------------------------------------------
CREATE TABLE sync_column_mapping (
  id                BIGINT       NOT NULL,
  table_mapping_id  BIGINT       NOT NULL,
  source_column     VARCHAR(128) NOT NULL,
  target_column     VARCHAR(128) NOT NULL,
  source_type       VARCHAR(64)  NOT NULL,
  target_type       VARCHAR(64)  NOT NULL,
  mapped_type       VARCHAR(64)           DEFAULT NULL,
  override_by       VARCHAR(64)           DEFAULT NULL,
  override_reason   VARCHAR(512)          DEFAULT NULL,
  nullable          TINYINT      NOT NULL DEFAULT 1,
  is_primary_key    TINYINT      NOT NULL DEFAULT 0,
  is_auto_increment TINYINT      NOT NULL DEFAULT 0,
  risk_tag          VARCHAR(64)           DEFAULT NULL,
  ignore_rules      TEXT                  DEFAULT NULL,
  ordinal           INT          NOT NULL DEFAULT 0,
  created_at        DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_colmap UNIQUE (table_mapping_id, source_column)
);

CREATE INDEX idx_colmap_table ON sync_column_mapping (table_mapping_id, ordinal);

-- ---------------------------------------------------------------------------
-- 5. sync_shard  分片（存量迁移最小调度单位）
-- ---------------------------------------------------------------------------
CREATE TABLE sync_shard (
  id                BIGINT       NOT NULL,
  task_id           BIGINT       NOT NULL,
  table_mapping_id  BIGINT       NOT NULL,
  shard_no          INT          NOT NULL,
  range_kind        VARCHAR(8)   NOT NULL DEFAULT 'NUMERIC',
  pk_start          VARCHAR(512)          DEFAULT NULL,
  pk_end            VARCHAR(512)          DEFAULT NULL,
  state             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  lease_owner       VARCHAR(96)           DEFAULT NULL,
  lease_expire_at   DATETIME(3)           DEFAULT NULL,
  heartbeat_at      DATETIME(3)           DEFAULT NULL,
  last_pk           VARCHAR(512)          DEFAULT NULL,
  committed_rows    BIGINT       NOT NULL DEFAULT 0,
  source_rows       BIGINT       NOT NULL DEFAULT -1,
  attempt           INT          NOT NULL DEFAULT 0,
  last_error        VARCHAR(1000)         DEFAULT NULL,
  started_at        DATETIME(3)           DEFAULT NULL,
  finished_at       DATETIME(3)           DEFAULT NULL,
  version           INT          NOT NULL DEFAULT 0,
  created_at        DATETIME(3)  NOT NULL,
  updated_at        DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_shard UNIQUE (task_id, shard_no)
);

CREATE INDEX idx_claim ON sync_shard (task_id, state, shard_no);
CREATE INDEX idx_lease ON sync_shard (state, lease_expire_at);
CREATE INDEX idx_shard_table ON sync_shard (table_mapping_id, state);

-- ---------------------------------------------------------------------------
-- 6. sync_checkpoint  断点 / 水位（SHARD / GROUP / POLLER 统一承载）
-- ---------------------------------------------------------------------------
CREATE TABLE sync_checkpoint (
  id                BIGINT       NOT NULL,
  task_id           BIGINT       NOT NULL,
  table_mapping_id  BIGINT       NOT NULL DEFAULT 0,
  scope_type        VARCHAR(12)  NOT NULL,
  scope_no          INT          NOT NULL,
  last_pk           VARCHAR(512)          DEFAULT NULL,
  last_commit_ts    DATETIME(3)           DEFAULT NULL,
  last_seq          BIGINT                DEFAULT NULL,
  last_log_id       BIGINT                DEFAULT NULL,
  committed_rows    BIGINT       NOT NULL DEFAULT 0,
  committed_batches BIGINT       NOT NULL DEFAULT 0,
  version           INT          NOT NULL DEFAULT 0,
  updated_at        DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_ckpt UNIQUE (task_id, table_mapping_id, scope_type, scope_no)
);

-- ---------------------------------------------------------------------------
-- 7. sync_change_log  变更队列（写放大最严重，索引最讲究）
-- ---------------------------------------------------------------------------
CREATE TABLE sync_change_log (
  id               BIGINT        NOT NULL,
  task_id          BIGINT        NOT NULL,
  table_mapping_id BIGINT        NOT NULL,
  group_no         INT           NOT NULL,
  pk_hash          BIGINT        NOT NULL,
  seq              BIGINT        NOT NULL,
  commit_ts        DATETIME(3)   NOT NULL,
  op               VARCHAR(8)    NOT NULL,
  pk_json          VARCHAR(2048) NOT NULL,
  before_json      TEXT                   DEFAULT NULL,
  after_json       TEXT                   DEFAULT NULL,
  changed_columns  VARCHAR(2048)          DEFAULT NULL,
  status           VARCHAR(12)   NOT NULL DEFAULT 'NEW',
  retry            INT           NOT NULL DEFAULT 0,
  error_msg        VARCHAR(1000)          DEFAULT NULL,
  conflict_action  VARCHAR(24)            DEFAULT NULL,
  source_instance  VARCHAR(96)            DEFAULT NULL,
  trace_id         VARCHAR(64)            DEFAULT NULL,
  applied_at       DATETIME(3)            DEFAULT NULL,
  created_at       DATETIME(3)   NOT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_consume ON sync_change_log (task_id, group_no, status, seq);
CREATE INDEX idx_trace ON sync_change_log (task_id, table_mapping_id, pk_hash, seq);
CREATE INDEX idx_lag ON sync_change_log (task_id, status, commit_ts);
CREATE INDEX idx_reap ON sync_change_log (status, created_at);

-- ---------------------------------------------------------------------------
-- 8. sync_change_watermark  组水位线（组独占 + 连续前缀水位）
-- ---------------------------------------------------------------------------
CREATE TABLE sync_change_watermark (
  id              BIGINT       NOT NULL,
  task_id         BIGINT       NOT NULL,
  group_no        INT          NOT NULL,
  consumer_owner  VARCHAR(96)           DEFAULT NULL,
  owner_expire_at DATETIME(3)           DEFAULT NULL,
  safe_seq        BIGINT       NOT NULL DEFAULT 0,
  safe_commit_ts  DATETIME(3)           DEFAULT NULL,
  lag_ms          BIGINT       NOT NULL DEFAULT 0,
  version         INT          NOT NULL DEFAULT 0,
  updated_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_wm UNIQUE (task_id, group_no)
);

CREATE INDEX idx_wm_owner ON sync_change_watermark (owner_expire_at);

-- ---------------------------------------------------------------------------
-- 9. sync_dead_letter  死信
-- ---------------------------------------------------------------------------
CREATE TABLE sync_dead_letter (
  id               BIGINT        NOT NULL,
  task_id          BIGINT        NOT NULL,
  table_mapping_id BIGINT                 DEFAULT NULL,
  source_type      VARCHAR(16)   NOT NULL,
  ref_id           BIGINT                 DEFAULT NULL,
  pk_json          VARCHAR(2048)          DEFAULT NULL,
  payload_json     TEXT                   DEFAULT NULL,
  error_code       VARCHAR(16)            DEFAULT NULL,
  error_msg        VARCHAR(2000)          DEFAULT NULL,
  stack_digest     VARCHAR(1000)          DEFAULT NULL,
  retry            INT           NOT NULL DEFAULT 0,
  status           VARCHAR(12)   NOT NULL DEFAULT 'OPEN',
  replay_by        VARCHAR(64)            DEFAULT NULL,
  replay_at        DATETIME(3)            DEFAULT NULL,
  discard_reason   VARCHAR(512)           DEFAULT NULL,
  created_at       DATETIME(3)   NOT NULL,
  updated_at       DATETIME(3)   NOT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_dl_task ON sync_dead_letter (task_id, status, created_at);
CREATE INDEX idx_dl_group ON sync_dead_letter (task_id, source_type, error_code);

-- ---------------------------------------------------------------------------
-- 10. sync_compare_job  比对作业
-- ---------------------------------------------------------------------------
CREATE TABLE sync_compare_job (
  id               BIGINT        NOT NULL,
  task_id          BIGINT        NOT NULL,
  table_mapping_id BIGINT                 DEFAULT NULL,
  trigger_type     VARCHAR(16)   NOT NULL DEFAULT 'MANUAL',
  state            VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
  block_rows       INT           NOT NULL DEFAULT 100000,
  parallelism      INT           NOT NULL DEFAULT 8,
  algorithms       VARCHAR(64)   NOT NULL DEFAULT 'COUNT,SUM,HASH',
  normalize_json   TEXT                   DEFAULT NULL,
  total_blocks     INT           NOT NULL DEFAULT 0,
  done_blocks      INT           NOT NULL DEFAULT 0,
  diff_blocks      INT           NOT NULL DEFAULT 0,
  total_rows       BIGINT        NOT NULL DEFAULT 0,
  diff_rows        BIGINT        NOT NULL DEFAULT 0,
  pass_rate        DECIMAL(12,8)          DEFAULT NULL,
  verdict          VARCHAR(8)             DEFAULT NULL,
  snapshot_note    VARCHAR(256)           DEFAULT NULL,
  started_at       DATETIME(3)            DEFAULT NULL,
  finished_at      DATETIME(3)            DEFAULT NULL,
  report_path      VARCHAR(512)           DEFAULT NULL,
  operator         VARCHAR(64)            DEFAULT NULL,
  created_at       DATETIME(3)   NOT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_cmp_task ON sync_compare_job (task_id, created_at);
CREATE INDEX idx_cmp_state ON sync_compare_job (state, started_at);

-- ---------------------------------------------------------------------------
-- 11. sync_compare_block  分块校验和
-- ---------------------------------------------------------------------------
CREATE TABLE sync_compare_block (
  id               BIGINT       NOT NULL,
  job_id           BIGINT       NOT NULL,
  table_mapping_id BIGINT       NOT NULL,
  block_no         INT          NOT NULL,
  pk_start         VARCHAR(512)          DEFAULT NULL,
  pk_end           VARCHAR(512)          DEFAULT NULL,
  src_count        BIGINT                DEFAULT NULL,
  tgt_count        BIGINT                DEFAULT NULL,
  src_sum_hash     VARCHAR(64)           DEFAULT NULL,
  tgt_sum_hash     VARCHAR(64)           DEFAULT NULL,
  src_row_hash     VARCHAR(64)           DEFAULT NULL,
  tgt_row_hash     VARCHAR(64)           DEFAULT NULL,
  matched          TINYINT      NOT NULL DEFAULT 1,
  diff_rows        INT          NOT NULL DEFAULT 0,
  drilled          TINYINT      NOT NULL DEFAULT 0,
  created_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_blk_job ON sync_compare_block (job_id, matched, block_no);
CREATE INDEX idx_blk_table ON sync_compare_block (job_id, table_mapping_id, block_no);

-- ---------------------------------------------------------------------------
-- 12. sync_compare_diff  差异明细
-- ---------------------------------------------------------------------------
CREATE TABLE sync_compare_diff (
  id               BIGINT        NOT NULL,
  job_id           BIGINT        NOT NULL,
  block_id         BIGINT        NOT NULL,
  table_mapping_id BIGINT        NOT NULL,
  pk_json          VARCHAR(2048) NOT NULL,
  pk_hash          BIGINT        NOT NULL,
  diff_type        VARCHAR(24)   NOT NULL,
  diff_columns     TEXT                   DEFAULT NULL,
  src_row_json     TEXT                   DEFAULT NULL,
  tgt_row_json     TEXT                   DEFAULT NULL,
  repair_state     VARCHAR(16)   NOT NULL DEFAULT 'NONE',
  repair_by        VARCHAR(64)            DEFAULT NULL,
  repair_at        DATETIME(3)            DEFAULT NULL,
  backup_table     VARCHAR(256)           DEFAULT NULL,
  created_at       DATETIME(3)   NOT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_diff_job ON sync_compare_diff (job_id, diff_type, table_mapping_id);
CREATE INDEX idx_diff_pk ON sync_compare_diff (job_id, pk_hash);

-- ---------------------------------------------------------------------------
-- 13. cutover_plan  切流计划（两段式状态）
-- ---------------------------------------------------------------------------
CREATE TABLE cutover_plan (
  id                  BIGINT       NOT NULL,
  plan_no             VARCHAR(48)  NOT NULL,
  task_id             BIGINT       NOT NULL,
  name                VARCHAR(128) NOT NULL,
  intent_state        VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
  confirmed_state     VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
  route_version       BIGINT       NOT NULL DEFAULT 0,
  plan_status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  gray_dimensions     TEXT         NOT NULL,
  gray_ratio          INT          NOT NULL DEFAULT 0,
  write_gray_ratio    INT          NOT NULL DEFAULT 0,
  gate_compare_pass   DECIMAL(9,6) NOT NULL DEFAULT 0.999900,
  gate_lag_p95_ms     BIGINT       NOT NULL DEFAULT 5000,
  gate_fail_rate      DECIMAL(9,6) NOT NULL DEFAULT 0.000100,
  gate_dead_letters   BIGINT       NOT NULL DEFAULT 0,
  gate_snapshot_json  TEXT                  DEFAULT NULL,
  observe_window_min  INT          NOT NULL DEFAULT 1440,
  observe_started_at  DATETIME(3)           DEFAULT NULL,
  auto_rollback       TINYINT      NOT NULL DEFAULT 1,
  auto_rb_err_rate    DECIMAL(9,6) NOT NULL DEFAULT 0.010000,
  rolled_back_at      DATETIME(3)           DEFAULT NULL,
  rollback_reason     VARCHAR(512)          DEFAULT NULL,
  created_by          VARCHAR(64)           DEFAULT NULL,
  version             INT          NOT NULL DEFAULT 0,
  created_at          DATETIME(3)  NOT NULL,
  updated_at          DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_plan_no UNIQUE (plan_no)
);

CREATE INDEX idx_plan_task ON cutover_plan (task_id, plan_status);
CREATE INDEX idx_plan_reconcile ON cutover_plan (plan_status, intent_state, confirmed_state);

-- ---------------------------------------------------------------------------
-- 14. cutover_step_log  切流步骤留痕
-- ---------------------------------------------------------------------------
CREATE TABLE cutover_step_log (
  id                   BIGINT       NOT NULL,
  plan_id              BIGINT       NOT NULL,
  step_seq             INT          NOT NULL,
  from_state           VARCHAR(24)  NOT NULL,
  to_state             VARCHAR(24)  NOT NULL,
  action               VARCHAR(64)  NOT NULL,
  gate_result_json     TEXT                  DEFAULT NULL,
  gate_skipped         TINYINT      NOT NULL DEFAULT 0,
  skip_reason          VARCHAR(512)          DEFAULT NULL,
  route_version_before BIGINT       NOT NULL DEFAULT 0,
  route_version_after  BIGINT       NOT NULL DEFAULT 0,
  result               VARCHAR(16)  NOT NULL,
  error_msg            VARCHAR(1000)         DEFAULT NULL,
  duration_ms          BIGINT                DEFAULT NULL,
  operator             VARCHAR(64)  NOT NULL,
  operator_ip          VARCHAR(64)           DEFAULT NULL,
  created_at           DATETIME(3)  NOT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_step_plan ON cutover_step_log (plan_id, step_seq);

-- ---------------------------------------------------------------------------
-- 15. sys_user  用户
-- ---------------------------------------------------------------------------
CREATE TABLE sys_user (
  id           BIGINT       NOT NULL,
  username     VARCHAR(64)  NOT NULL,
  display_name VARCHAR(64)           DEFAULT NULL,
  password_hash VARCHAR(128) NOT NULL,
  salt         VARCHAR(64)  NOT NULL,
  role         VARCHAR(16)  NOT NULL DEFAULT 'VIEWER',
  enabled      TINYINT      NOT NULL DEFAULT 1,
  last_login_at DATETIME(3)          DEFAULT NULL,
  created_at   DATETIME(3)  NOT NULL,
  updated_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_user_name UNIQUE (username)
);

-- ---------------------------------------------------------------------------
-- 16. sys_audit_log  审计日志
-- ---------------------------------------------------------------------------
CREATE TABLE sys_audit_log (
  id            BIGINT       NOT NULL,
  actor         VARCHAR(64)  NOT NULL,
  actor_role    VARCHAR(16)  NOT NULL,
  action        VARCHAR(64)  NOT NULL,
  target_type   VARCHAR(32)           DEFAULT NULL,
  target_id     BIGINT                DEFAULT NULL,
  target_name   VARCHAR(256)          DEFAULT NULL,
  params_digest TEXT                  DEFAULT NULL,
  result        VARCHAR(16)  NOT NULL,
  error_code    VARCHAR(16)           DEFAULT NULL,
  ip            VARCHAR(64)           DEFAULT NULL,
  user_agent    VARCHAR(256)          DEFAULT NULL,
  trace_id      VARCHAR(64)           DEFAULT NULL,
  created_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_audit_time ON sys_audit_log (created_at);
CREATE INDEX idx_audit_actor ON sys_audit_log (actor, created_at);
CREATE INDEX idx_audit_target ON sys_audit_log (target_type, target_id, created_at);
CREATE INDEX idx_audit_action ON sys_audit_log (action, created_at);

-- ---------------------------------------------------------------------------
-- 17. sys_instance  实例心跳
-- ---------------------------------------------------------------------------
CREATE TABLE sys_instance (
  id           VARCHAR(96)  NOT NULL,
  host         VARCHAR(128) NOT NULL,
  port         INT          NOT NULL,
  version      VARCHAR(32)           DEFAULT NULL,
  state        VARCHAR(16)  NOT NULL DEFAULT 'UP',
  started_at   DATETIME(3)  NOT NULL,
  heartbeat_at DATETIME(3)  NOT NULL,
  shard_count  INT          NOT NULL DEFAULT 0,
  task_count   INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

CREATE INDEX idx_inst_hb ON sys_instance (state, heartbeat_at);

-- ---------------------------------------------------------------------------
-- 18. sync_trigger_def  触发器部署台账
-- ---------------------------------------------------------------------------
CREATE TABLE sync_trigger_def (
  id               BIGINT       NOT NULL,
  task_id          BIGINT       NOT NULL,
  table_mapping_id BIGINT       NOT NULL,
  source_ds_id     BIGINT       NOT NULL,
  log_table_name   VARCHAR(128) NOT NULL,
  trigger_names    TEXT         NOT NULL,
  ddl_json         TEXT                  DEFAULT NULL,
  state            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  deployed_at      DATETIME(3)           DEFAULT NULL,
  last_error       VARCHAR(1000)         DEFAULT NULL,
  created_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_trg UNIQUE (task_id, table_mapping_id)
);

CREATE INDEX idx_trg_state ON sync_trigger_def (state, task_id);
