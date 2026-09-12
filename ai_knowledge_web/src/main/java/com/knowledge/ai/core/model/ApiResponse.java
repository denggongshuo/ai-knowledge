package com.knowledge.ai.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.trace.TraceContext;
import lombok.Data;

/**
 * 统一响应体（T-001）。
 *
 * <p>契约（《02-需求总览与分档》§6.1、《10-接口约定汇总》§0）：
 * <pre>
 *   { "code": 0, "message": "success", "data": {}, "traceId": "a3f8c1e2b47d9e02" }
 * </pre>
 *
 * <ul>
 *   <li>{@code code == 0} 表示成功，非 0 表示业务失败；</li>
 *   <li><b>HTTP 状态码只表达传输层语义</b> —— 业务错误一律 HTTP 200 + 非 0 code；</li>
 *   <li>唯二的例外是传输层鉴权语义：认证失败 HTTP 401、权限不足 HTTP 403
 *       （前端 {@code request.ts} 依赖 401 跳登录，见《10》§13）；</li>
 *   <li>所有响应都带 {@code traceId}，与日志、审计、错误页一致。</li>
 * </ul>
 *
 * <p>{@code traceId} 由 {@link TraceContext#current()} 自动填充，调用方无需关心。
 *
 * @param <T> 业务数据类型
 */
@Data
public class ApiResponse<T> {

    /** 成功码。全项目唯一，勿改。 */
    public static final int SUCCESS_CODE = 0;

    private int code;

    private String message;

    private T data;

    /** 链路追踪 ID，16 位十六进制。 */
    private String traceId;

    public ApiResponse() {
        this.traceId = TraceContext.current();
    }

    public ApiResponse(int code, String message, T data) {
        this();
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ==================== 成功 ====================

    /** 成功（带数据）。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "success", data);
    }

    /** 成功（无数据）。 */
    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    /**
     * 成功但需要自定义提示语。
     * 例如删除接口返回 {@code { saved: true }} 之类。
     */
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(SUCCESS_CODE, message, data);
    }

    // ==================== 失败 ====================

    /** 失败（用错误码自带的提示语）。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 失败（覆盖提示语，错误码不变）。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        String finalMessage = (message == null || message.isBlank()) ? errorCode.getMessage() : message;
        return new ApiResponse<>(errorCode.getCode(), finalMessage, null);
    }

    /** 失败（裸错误码，供无法用枚举表达的场景兜底）。 */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 是否成功。
     *
     * <p><b>不参与序列化</b>（G0-2）：Jackson 把 {@code isXxx()} 视为名为 {@code xxx} 的
     * 布尔属性，若不加此注解，线上响应体会多出一个 {@code "success": true}，
     * 破坏"恰好四字段"的契约，前端也据此判读失败语义。
     * 这是给 Java 侧用的便捷方法，传输层一律看 {@code code}。
     */
    @JsonIgnore
    public boolean isSuccess() {
        return this.code == SUCCESS_CODE;
    }
}
