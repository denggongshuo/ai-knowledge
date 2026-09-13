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

    /**
     * 是否属于客户端过错（4xx 语义）；用于决定是否打 error 级日志。
     *
     * <p><b>判定为什么按 HTTP 状态码而不是比数值大小</b>：这里原本写的是
     * {@code errorCode.getCode() < ErrorCode.SYSTEM_ERROR.getCode()}，即
     * {@code < 9999}。但业务码是**五位数**（10010~10056），**全部大于 9999** ——
     * 于是该判断恒为 {@code false}，{@link com.knowledge.ai.core.exception.GlobalExceptionHandler}
     * 里那条 {@code warn} 分支成了死代码：用户输错一次验证码、令牌正常过期、
     * 凭证一次性失效，统统按 ERROR 级打**完整堆栈**。
     *
     * <p>后果不是"日志难看"，而是两点实质损害：
     * <ol>
     *   <li>只收 ERROR 的 {@code -error.log} 被可预期的客户端错误灌满，
     *       "ERROR 日志里有东西"不再等于"出故障了"，告警失去意义；</li>
     *   <li>每个被拒的请求都要构造一次堆栈，风控场景下是白白的开销。</li>
     * </ol>
     *
     * <p>改为按 {@code httpStatus} 判定：{@code 4xx} 与业务 200 都属客户端可预期错误，
     * 只有 {@code 5xx}（当前仅 {@code SYSTEM_ERROR}）才是需要人看的服务端故障。
     * 这条口径与 {@code ErrorCode} 类注释里那段 HTTP 状态码说明是同一件事。
     */
    public boolean isClientError() {
        return errorCode.getHttpStatus() < 500;
    }
}
