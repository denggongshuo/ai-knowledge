package com.knowledge.ai.core.exception;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器（T-001 / T-002）。
 *
 * <p>把任意异常收敛成 {@link ApiResponse}，保证：
 * <ol>
 *   <li>响应体结构与成功路径完全一致（含 traceId）；</li>
 *   <li>错误码严格落在 1001x / 1002x / 1003x / 1004x / 9999 分段里；</li>
 *   <li>堆栈只进日志，不回前端；前端拿到的 message 是给人看的。</li>
 * </ol>
 *
 * <p><b>HTTP 状态码策略</b>：业务错误一律 200；只有
 * 认证失败（401）、权限不足（403）、路由不存在（404）、未捕获异常（500）
 * 使用非 200 —— 因为前端要靠 401 跳登录、靠 404 走错误页。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 业务异常 ====================

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        if (ex.isClientError()) {
            log.warn("业务异常 code={} uri={} traceId={} message={}",
                    code.getCode(), request.getRequestURI(), TraceContext.get(), ex.getMessage());
        } else {
            log.error("业务异常 code={} uri={} traceId={}",
                    code.getCode(), request.getRequestURI(), TraceContext.get(), ex);
        }
        return build(code.getHttpStatus(), ApiResponse.fail(code, ex.getMessage()));
    }

    // ==================== 1001x 认证类 ====================

    /**
     * Spring Security 在「匿名访问受保护资源」时可能抛出它。
     * 统一按未登录处理 → HTTP 401，前端据此跳登录页。
     */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(Exception ex, HttpServletRequest request) {
        log.warn("认证失败 uri={} traceId={} message={}",
                request.getRequestURI(), TraceContext.get(), ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED.value(), ApiResponse.fail(ErrorCode.UNAUTHORIZED));
    }

    // ==================== 1002x 权限类 ====================

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("权限不足 uri={} traceId={} message={}",
                request.getRequestURI(), TraceContext.get(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN.value(), ApiResponse.fail(ErrorCode.FORBIDDEN));
    }

    // ==================== 1003x 参数类 ====================

    /** {@code @RequestBody @Valid} 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        return build(HttpStatus.OK.value(), ApiResponse.fail(ErrorCode.PARAM_INVALID, describe(ex.getBindingResult().getFieldErrors())));
    }

    /** 表单 / 查询参数绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException ex) {
        return build(HttpStatus.OK.value(), ApiResponse.fail(ErrorCode.PARAM_INVALID, describe(ex.getBindingResult().getFieldErrors())));
    }

    /** 方法级 {@code @Validated} 校验失败（如 @PathVariable @Min(1)）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations() == null
                ? ErrorCode.PARAM_INVALID.getMessage()
                : ex.getConstraintViolations().stream()
                        .map(ConstraintViolation::getMessage)
                        .collect(Collectors.joining("；"));
        return build(HttpStatus.OK.value(), ApiResponse.fail(ErrorCode.PARAM_INVALID, message));
    }

    /** 必填查询参数缺失。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(HttpStatus.OK.value(),
                ApiResponse.fail(ErrorCode.PARAM_INVALID, "缺少必填参数：" + ex.getParameterName()));
    }

    /** 参数类型不匹配（如 id 传了非数字）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.OK.value(),
                ApiResponse.fail(ErrorCode.PARAM_INVALID, "参数类型不正确：" + ex.getName()));
    }

    /** 请求体不是合法 JSON / 无法反序列化。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败 traceId={} message={}", TraceContext.get(), ex.getMessage());
        return build(HttpStatus.OK.value(), ApiResponse.fail(ErrorCode.PARAM_INVALID, "请求体格式不正确"));
    }

    /** 请求方法不支持。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.OK.value(),
                ApiResponse.fail(ErrorCode.PARAM_INVALID, "不支持的请求方法：" + ex.getMethod()));
    }

    // ==================== 1004x 资源类 ====================

    /** 路由 / 静态资源不存在 → HTTP 404，与前端错误页语义对齐。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        log.warn("资源不存在 uri={} traceId={}", ex.getResourcePath(), TraceContext.get());
        return build(HttpStatus.NOT_FOUND.value(), ApiResponse.fail(ErrorCode.NOT_FOUND));
    }

    // ==================== 9999 兜底 ====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未捕获异常 uri={} traceId={}", request.getRequestURI(), TraceContext.get(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR.value(), ApiResponse.fail(ErrorCode.SYSTEM_ERROR));
    }

    // ==================== 内部工具 ====================

    private ResponseEntity<ApiResponse<Void>> build(int httpStatus, ApiResponse<Void> body) {
        return ResponseEntity.status(httpStatus).body(body);
    }

    /** 最多回溯 3 条字段错误，避免把整张表单的错误一次性糊到提示里。 */
    private String describe(List<FieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            return ErrorCode.PARAM_INVALID.getMessage();
        }
        List<String> parts = new ArrayList<>(3);
        for (FieldError error : errors) {
            if (parts.size() >= 3) {
                break;
            }
            parts.add(error.getField() + " " + error.getDefaultMessage());
        }
        return String.join("；", parts);
    }
}
