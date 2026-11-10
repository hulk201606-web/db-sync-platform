package com.dbsync.api.dto.system;

/**
 * 新建用户请求（T46，ADMIN only）。
 *
 * @param username    登录名
 * @param displayName 显示名
 * @param password    初始口令（≥8 位；首登强制改密）
 * @param role        角色（VIEWER/OPERATOR/ADMIN）
 */
public record UserCreateRequest(String username, String displayName, String password, String role) {
}
