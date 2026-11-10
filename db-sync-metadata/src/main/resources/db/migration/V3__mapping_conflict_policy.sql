-- ---------------------------------------------------------------------------
-- V3：表级冲突策略（CDC-06「三种策略按表可配」）
--
-- 背景：V1 建表时只有 sync_task.conflict_policy（任务级），代码里又被
--       CdcContextFactory 写死成 SOURCE_WINS；PRD CDC-06 要求「按表可配」，
--       同一任务下不同表可以取不同策略（例如字典表用 TARGET_WINS 保护人工订正）。
--
-- 语法约束（硬规则 3）：
--   * 只用标准 DDL：ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT '...'
--   * 不写 ENGINE/CHARSET/AFTER 等 MySQL 专有子句（H2 MODE=MySQL 不支持 AFTER）
--   * 默认值必须给：表上已有行（存量任务）要能补出确定值，不能留 NULL
--
-- 兼容性验证（详见 CdcConflictPolicyMigrationTest）：
--   * H2 1.4.x / 2.x，MODE=MySQL：ADD COLUMN + NOT NULL + DEFAULT 常量均支持；
--   * MySQL 8.0：ALTER TABLE ... ADD COLUMN ... VARCHAR(20) NOT NULL DEFAULT 'x'
--     在 sql_mode=STRICT_* 下同样成立（常量默认值不需要表达式默认值语法）。
-- ---------------------------------------------------------------------------
ALTER TABLE sync_table_mapping
  ADD COLUMN conflict_policy VARCHAR(20) NOT NULL DEFAULT 'SOURCE_WINS';
