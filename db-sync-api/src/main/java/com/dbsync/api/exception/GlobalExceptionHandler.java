package com.dbsync.api.exception;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器（T42 验收 4）。
 *
 * <p>统一响应体：{@code {"code":"E1001","message":"...","data":{...}}}。
 * <ul>
 *   <li>{@link DbSyncException} → 按 {@link ErrorCode#httpStatus()} 返回，{@code data} 为
 *       {@link DbSyncException#context()}（结构化上下文，前端可直接定位字段）；</li>
 *   <li>参数校验失败 → {@code E1001} + 400，{@code data.fieldErrors} 列出字段级原因；</li>
 *   <li>未捕获异常 → {@code E1099} + 500（不把堆栈返回给调用方）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 日志追踪键（与 server 的 TraceIdFilter 约定一致）。 */
    private static final String TRACE_ID_KEY = "traceId";

    /**
     * 业务异常。
     *
     * @param ex 业务异常
     * @return 统一响应
     */
    @ExceptionHandler(DbSyncException.class)
    public ResponseEntity<Map<String, Object>> handleDbSync(DbSyncException ex) {
        ErrorCode code = ex.errorCode();
        Map<String, Object> body = body(code.code(), ex.getMessage(), ex.context());
        HttpStatus status = HttpStatus.resolve(code.httpStatus());
        return ResponseEntity.status(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status)
                .body(body);
    }

    /**
     * Bean Validation 失败（{@code @Valid}）。
     *
     * @param ex 校验异常
     * @return 统一响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest()
                .body(body(ErrorCode.PARAM_INVALID.code(), "参数校验失败", data));
    }

    /**
     * 请求体不可解析。
     *
     * @param ex 异常
     * @return 统一响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(
            HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(body(ErrorCode.PARAM_INVALID.code(), "请求体格式错误", null));
    }

    /**
     * 缺少必填查询参数。
     *
     * @param ex 异常
     * @return 统一响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            MissingServletRequestParameterException ex) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parameter", ex.getParameterName());
        return ResponseEntity.badRequest()
                .body(body(ErrorCode.PARAM_INVALID.code(), "缺少必填参数", data));
    }

    /**
     * 路径参数类型不匹配。
     *
     * @param ex 异常
     * @return 统一响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parameter", ex.getName());
        data.put("value", ex.getValue() == null ? null : String.valueOf(ex.getValue()));
        return ResponseEntity.badRequest()
                .body(body(ErrorCode.PARAM_INVALID.code(), "参数类型错误", data));
    }

    /**
     * 未捕获异常兜底。
     *
     * @param ex 异常
     * @return 统一响应（500 + E1099，不泄露堆栈）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("未捕获异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(ErrorCode.INTERNAL_ERROR.code(),
                        ErrorCode.INTERNAL_ERROR.format(ex.getClass().getSimpleName()), null));
    }

    /**
     * 路径不存在（Spring 6.1 的 {@code NoResourceFoundException} 与老的 {@code NoHandlerFoundException}）。
     *
     * <p>必须单独处理：{@link Exception} 兜底会把"接口写错"变成 500，
     * 前端就无法区分"我调错路径"和"服务挂了"，也没法做 404 提示。
     * <b>状态码用 404</b>；错误码沿用 E1001（错误码表里没有通用 404 段位，
     * 新增枚举值会破坏"错误码唯一数据源"，因此用明确的 message 区分）。
     *
     * @param ex 异常
     * @return 统一响应（404）
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNoResource(Exception ex) {
        String path = ex instanceof NoResourceFoundException
                ? ((NoResourceFoundException) ex).getResourcePath() : null;
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("path", path);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body(ErrorCode.PARAM_INVALID.code(),
                        "接口不存在：" + (path == null ? "" : path), data));
    }

    /**
     * 组装统一响应体。
     *
     * <p><b>错误体必须带 {@code traceId}</b>（前端约定）：用户报障时只需给这个值，
     * 服务端就能在日志里按 traceId 捞出整条链路（{@code TraceIdFilter} 写入 MDC，
     * 同时回写响应头 {@code X-Trace-Id}，body 里也给一份方便前端直接展示）。
     *
     * @param code    错误码
     * @param message 消息
     * @param data    数据（可 null）
     * @return 有序 Map
     */
    public static Map<String, Object> body(String code, String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId != null && !traceId.isBlank()) {
            body.put("traceId", traceId);
        }
        return body;
    }
}
