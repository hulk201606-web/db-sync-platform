package com.dbsync.api.dto.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一响应体 {@code {code, message, data}}（04 文档 T27 验收 3 要求）。
 *
 * <p>{@code // ADDED:} 04 文档只规定了响应格式，未把响应包装类列进文件清单；
 * 所有 Controller 都返回它，故独立成类放在 {@code dto/common} 下。
 *
 * <p>成功用 {@code code="OK"}（与错误码 {@code E****} 区分），失败由
 * {@code GlobalExceptionHandler} 统一产出，Controller 只负责成功分支。
 */
public final class ApiResponse {

    /** 成功码。 */
    public static final String OK = "OK";

    private final String code;
    private final String message;
    private final Object data;

    /**
     * 构造。
     *
     * @param code    码
     * @param message 消息
     * @param data    数据
     */
    public ApiResponse(String code, String message, Object data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功（无数据）。
     *
     * @return 响应
     */
    public static ApiResponse ok() {
        return new ApiResponse(OK, "success", null);
    }

    /**
     * 成功。
     *
     * @param data 数据
     * @return 响应
     */
    public static ApiResponse ok(Object data) {
        return new ApiResponse(OK, "success", data);
    }

    /**
     * 成功（自定义消息）。
     *
     * @param data    数据
     * @param message 消息
     * @return 响应
     */
    public static ApiResponse ok(Object data, String message) {
        return new ApiResponse(OK, message, data);
    }

    /**
     * 失败。
     *
     * @param code    错误码
     * @param message 消息
     * @param data    数据
     * @return 响应
     */
    public static ApiResponse fail(String code, String message, Object data) {
        return new ApiResponse(code, message, data);
    }

    /**
     * 键值对数据（{@code ok(k,v)} 便捷入口，保持顺序）。
     *
     * @param key   键
     * @param value 值
     * @return 只有一项的有序 Map
     */
    public static Map<String, Object> one(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    /**
     * @return 码
     */
    public String getCode() {
        return code;
    }

    /**
     * @return 消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return 数据
     */
    public Object getData() {
        return data;
    }
}
