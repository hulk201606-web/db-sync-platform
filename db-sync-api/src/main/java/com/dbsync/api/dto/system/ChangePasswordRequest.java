package com.dbsync.api.dto.system;

/**
 * 改密请求。
 *
 * @param username    用户名（本人改密时忽略，取当前登录用户）
 * @param oldPassword 旧口令
 * @param newPassword 新口令（≥8 位）
 */
public record ChangePasswordRequest(String username, String oldPassword, String newPassword) {
}
