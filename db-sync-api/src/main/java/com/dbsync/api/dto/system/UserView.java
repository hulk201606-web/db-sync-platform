package com.dbsync.api.dto.system;

import com.dbsync.metadata.dao.UserDao;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户视图（永不包含口令哈希与盐）。
 *
 * @param id          用户 ID
 * @param username    登录名
 * @param displayName 显示名
 * @param role        角色
 * @param enabled     是否启用
 * @param lastLoginAt 最近登录时间
 */
public record UserView(long id, String username, String displayName, String role, boolean enabled,
                       String lastLoginAt) {

    /**
     * 从 DAO 行转换。
     *
     * @param row 行
     * @return 视图
     */
    public static UserView of(UserDao.UserRow row) {
        return new UserView(row.getId(), row.getUsername(), row.getDisplayName(), row.getRole(),
                row.isEnabled(), row.getLastLoginAt() == null ? null
                        : row.getLastLoginAt().toString());
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("username", username);
        map.put("displayName", displayName);
        map.put("role", role);
        map.put("enabled", enabled);
        map.put("lastLoginAt", lastLoginAt);
        return map;
    }
}
