package com.dbsync.api.service;

import com.dbsync.api.dto.system.UserCreateRequest;
import com.dbsync.api.dto.system.UserView;
import com.dbsync.api.security.AuthService;
import com.dbsync.api.security.Role;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.metadata.dao.UserDao;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户服务（T46：CRUD + PBKDF2 加盐 + 首登强制改密）。
 *
 * <p><b>首登强制改密</b>（T46 验收 3）：本期不加"是否已改密"列（会改 Flyway 基线），
 * 采用文档给出的等价方案——**用 {@code password_hash} 的初始标记位判定**：
 * 种子管理员的 {@code password_hash} 固定为 {@link #INITIAL_ADMIN_HASH}，
 * 登录后若哈希仍等于它，API 在登录响应里返回 {@code mustChangePassword=true}，
 * 前端强制跳改密页；改密后哈希变化，标记自动消失。
 *
 * <p>口令散列算法与 {@link AuthService} 完全一致（PBKDF2WithHmacSHA256 / 120000 / 256bit），
 * 避免"两套算法导致登录不上"。
 */
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** 种子管理员初始口令哈希（{@code V2__seed.sql}）—— 用于判定"首登未改密"。 */
    public static final String INITIAL_ADMIN_HASH =
            "a6e44388fc76bddee19c2d7004c7f0f88d3f0275a4d70d7833639f9695c99c8f";

    /** 口令最小长度。 */
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final UserDao userDao;

    /**
     * 构造。
     *
     * @param userDao 用户 DAO
     */
    public UserService(UserDao userDao) {
        this.userDao = userDao;
    }

    /**
     * 列表。
     *
     * @return 用户列表（不含口令）
     */
    public List<UserView> list() {
        List<UserView> views = new ArrayList<>();
        for (UserDao.UserRow row : userDao.findAll()) {
            views.add(UserView.of(row));
        }
        return views;
    }

    /**
     * 新建用户。
     *
     * @param req 请求
     * @return 视图
     */
    public UserView create(UserCreateRequest req) {
        if (req == null || req.username() == null || req.username().isBlank()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "用户名不能为空");
        }
        String username = req.username().trim();
        if (userDao.findByUsername(username).isPresent()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "用户名已存在：" + username);
        }
        if (req.password() == null || req.password().length() < MIN_PASSWORD_LENGTH) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "口令长度需 ≥" + MIN_PASSWORD_LENGTH);
        }
        Role role = Role.parse(req.role());
        String salt = AuthService.newSaltHex();
        userDao.insert(username, req.displayName(), AuthService.hashPassword(req.password(), salt),
                salt, role.name());
        log.info("用户已创建 username={} role={}", username, role);
        return userDao.findByUsername(username).map(UserView::of)
                .orElseThrow(() -> new DbSyncException(ErrorCode.INTERNAL_ERROR, "用户创建后读取失败"));
    }

    /**
     * 启用/禁用。
     *
     * @param username 用户名
     * @param enabled  是否启用
     */
    public void setEnabled(String username, boolean enabled) {
        requireUser(username);
        if (!enabled && "admin".equalsIgnoreCase(username)) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "禁止禁用内置 admin 账号");
        }
        userDao.setEnabled(username, enabled);
        log.info("用户状态已更新 username={} enabled={}", username, Boolean.valueOf(enabled));
    }

    /**
     * 管理员重置口令（无需旧口令）。
     *
     * @param username    用户名
     * @param newPassword 新口令
     */
    public void resetPassword(String username, String newPassword) {
        requireUser(username);
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "口令长度需 ≥" + MIN_PASSWORD_LENGTH);
        }
        String salt = AuthService.newSaltHex();
        userDao.updatePassword(username, AuthService.hashPassword(newPassword, salt), salt);
        log.info("口令已重置 username={}", username);
    }

    /**
     * 是否需要强制改密（首登）。
     *
     * @param username 用户名
     * @return 需要改密返回 true
     */
    public boolean mustChangePassword(String username) {
        return userDao.findByUsername(username)
                .map(row -> INITIAL_ADMIN_HASH.equalsIgnoreCase(row.getPasswordHash()))
                .orElse(false);
    }

    /**
     * 校验用户存在。
     *
     * @param username 用户名
     */
    public void requireUser(String username) {
        if (username == null || userDao.findByUsername(username).isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "用户不存在：" + username);
        }
    }
}
