package com.knowledge.ai.core.exception;

import lombok.Getter;

/**
 * 业务异常（M00 公共基座的唯一业务异常出口）。
 *
 * <p>用法：
 * <pre>
 *   // 用错误码自带的提示语
 *   throw BizException.of(ErrorCode.KB_NOT_FOUND);
 *
 *   // 覆盖提示语（错误码不变，前端仍按 code 分支）
 *   throw BizException.of(ErrorCode.PARAM_INVALID, "切分窗口必须在 128~2048 之间");
 *
 *   // 带占位符
 *   throw BizException.ofFormat(ErrorCode.FILE_TOO_LARGE, "文件 %s 超过 100MB", name);
 * </pre>
 *
 * <p>抛出后由 {@code GlobalExceptionHandler} 统一转成
 * {@code ApiResponse}，HTTP 状态码取自 {@link ErrorCode#getHttpStatus()}。
 *
 * <p><b>不要用它表达参数校验</b>：能用 {@code @Valid} 的走 Bean Validation，
 * 异常处理器会统一映射为 {@link ErrorCode#PARAM_INVALID}。
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /** 排查用的附加细节。**不返回给前端**，只进日志。 */
    private final transient Object detail;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null, null);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, cause, null);
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause, Object detail) {
        super(message == null ? errorCode.getMessage() : message, cause);
        this.errorCode = errorCode == null ? ErrorCode.SYSTEM_ERROR : errorCode;
        this.detail = detail;
    }

    // ==================== 静态工厂 ====================

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }

    /** 带占位符的提示语写法，避免到处拼字符串。 */
    public static BizException ofFormat(ErrorCode errorCode, String format, Object... args) {
        String message = (args == null || args.length == 0) ? format : String.format(format, args);
        return new BizException(errorCode, message);
    }

    public static BizException of(ErrorCode errorCode, Throwable cause) {
        return new BizException(errorCode, errorCode.getMessage(), cause);
    }

    /** 是否属于客户端过错（4xx 语义）；用于决定是否打 error 级日志。 */
    public boolean isClientError() {
        return errorCode.getCode() < ErrorCode.SYSTEM_ERROR.getCode();
    }
}
