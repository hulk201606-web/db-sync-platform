-- ============================================================================
-- V2__seed.sql  种子数据
--
-- 默认管理员：admin / Admin@12345
--   ★ 安全要求：**首次登录必须改密**（README 与部署手册均需写明）。
--   password_hash = PBKDF2WithHmacSHA256(password, salt, 120000 次, 256 bit)
--   salt          = 16 字节随机，Hex 存储
--   校验方式见 com.dbsync.api.service.UserService#verifyPassword
--
-- 参数不走元数据库（本期统一走 application.yml 的 db-sync.* 段），因此这里只种一个用户。
-- ============================================================================

INSERT INTO sys_user (id, username, display_name, password_hash, salt, role, enabled, created_at, updated_at)
VALUES (
  1000000000000000001,
  'admin',
  'Administrator',
  'a6e44388fc76bddee19c2d7004c7f0f88d3f0275a4d70d7833639f9695c99c8f',
  '4a7d517be07ab27e1adcbd4d16ab7f85',
  'ADMIN',
  1,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP
);
