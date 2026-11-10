package com.dbsync.api.dto.system;

/**
 * 登录请求。
 *
 * @param username 用户名
 * @param password 口令
 */
public record LoginRequest(String username, String password) {
}
