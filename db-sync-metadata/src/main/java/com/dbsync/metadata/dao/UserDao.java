package com.dbsync.metadata.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 用户 DAO。
 *
 * <p>口令存储为 {@code PBKDF2WithHmacSHA256}（120000 次迭代、256 位）+ 每用户独立随机盐，
 * 数据库里<b>不存明文、不存可逆密文</b>。校验逻辑在 api 模块的 {@code AuthService}。
 */
@Repository
public class UserDao extends AbstractMetaDao {

    /**
     * 用户台账行（ADDED：04 文档列了 UserDao 但 common 未列用户模型；
     * 用户属于 api 层关注点，用 DAO 内嵌套类承载，避免污染 common 的领域模型）。
     */
    public static final class UserRow {

        private final long id;
        private final String username;
        private final String displayName;
        private final String passwordHash;
        private final String salt;
        private final String role;
        private final boolean enabled;
        private final Instant lastLoginAt;

        /**
         * 构造。
         *
         * @param id           用户 ID
         * @param username     登录名
         * @param displayName  显示名
         * @param passwordHash PBKDF2 口令哈希（hex）
         * @param salt         盐（hex）
         * @param role         角色（ADMIN/OPERATOR/VIEWER）
         * @param enabled      是否启用
         * @param lastLoginAt  最近登录时间
         */
        public UserRow(long id, String username, String displayName, String passwordHash,
                       String salt, String role, boolean enabled, Instant lastLoginAt) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
            this.passwordHash = passwordHash;
            this.salt = salt;
            this.role = role;
            this.enabled = enabled;
            this.lastLoginAt = lastLoginAt;
        }

        /** @return 用户 ID */
        public long getId() {
            return id;
        }

        /** @return 登录名 */
        public String getUsername() {
            return username;
        }

        /** @return 显示名 */
        public String getDisplayName() {
            return displayName;
        }

        /** @return 口令哈希 */
        public String getPasswordHash() {
            return passwordHash;
        }

        /** @return 盐 */
        public String getSalt() {
            return salt;
        }

        /** @return 角色 */
        public String getRole() {
            return role;
        }

        /** @return 是否启用 */
        public boolean isEnabled() {
            return enabled;
        }

        /** @return 最近登录时间 */
        public Instant getLastLoginAt() {
            return lastLoginAt;
        }
    }

    private final RowMapper<UserRow> mapper = new RowMapper<UserRow>() {
        @Override
        public UserRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new UserRow(rs.getLong("id"), rs.getString("username"),
                    rs.getString("display_name"), rs.getString("password_hash"),
                    rs.getString("salt"), rs.getString("role"), rs.getInt("enabled") != 0,
                    instantOf(rs, "last_login_at"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public UserDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 按登录名查询。
     *
     * @param username 登录名
     * @return 用户
     */
    public Optional<UserRow> findByUsername(String username) {
        List<UserRow> list = jdbc.query("SELECT * FROM sys_user WHERE username=?", mapper, username);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 按 ID 查询。
     *
     * @param id 用户 ID
     * @return 用户
     */
    public Optional<UserRow> findById(long id) {
        List<UserRow> list = jdbc.query("SELECT * FROM sys_user WHERE id=?", mapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 列出全部用户。
     *
     * @return 用户列表
     */
    public List<UserRow> findAll() {
        return jdbc.query("SELECT * FROM sys_user ORDER BY id", mapper);
    }

    /**
     * 新增用户。
     *
     * @param username     登录名
     * @param displayName  显示名
     * @param passwordHash 口令哈希
     * @param salt         盐
     * @param role         角色
     * @return 影响行数
     */
    public int insert(String username, String displayName, String passwordHash, String salt,
                      String role) {
        Instant now = now();
        return jdbc.update("INSERT INTO sys_user (id,username,display_name,password_hash,salt,"
                        + "role,enabled,created_at,updated_at) VALUES (?,?,?,?,?,?,1,?,?)",
                nextId(), username, displayName, passwordHash, salt, role,
                toTimestamp(now), toTimestamp(now));
    }

    /**
     * 改口令（同时换盐）。
     *
     * @param username     登录名
     * @param passwordHash 新口令哈希
     * @param salt         新盐
     * @return 影响行数
     */
    public int updatePassword(String username, String passwordHash, String salt) {
        return jdbc.update("UPDATE sys_user SET password_hash=?,salt=?,updated_at=? "
                + "WHERE username=?", passwordHash, salt, toTimestamp(now()), username);
    }

    /**
     * 启用/禁用。
     *
     * @param username 登录名
     * @param enabled  是否启用
     * @return 影响行数
     */
    public int setEnabled(String username, boolean enabled) {
        return jdbc.update("UPDATE sys_user SET enabled=?,updated_at=? WHERE username=?",
                enabled ? 1 : 0, toTimestamp(now()), username);
    }

    /**
     * 记录登录时间。
     *
     * @param username 登录名
     * @return 影响行数
     */
    public int updateLastLogin(String username) {
        return jdbc.update("UPDATE sys_user SET last_login_at=? WHERE username=?",
                toTimestamp(now()), username);
    }
}
