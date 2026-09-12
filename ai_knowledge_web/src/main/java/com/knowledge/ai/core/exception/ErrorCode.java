package com.knowledge.ai.core.exception;

import lombok.Getter;

import java.io.Serializable;

/**
 * 全局错误码（T-002）。
 *
 * <p>分段规则（《02-需求总览与分档》§6.1、《10-接口约定汇总》§0）：
 * <ul>
 *   <li>{@code 0} —— 成功</li>
 *   <li>{@code 1001x} —— 认证类</li>
 *   <li>{@code 1002x} —— 权限类</li>
 *   <li>{@code 1003x} —— 参数类</li>
 *   <li>{@code 1004x} —— 资源类</li>
 *   <li>{@code 9999} —— 系统异常兜底</li>
 * </ul>
 *
 * <p><b>HTTP 状态码口径</b>（这段是刻意设计，勿改）：
 * <blockquote>
 * 业务错误一律 <b>HTTP 200</b> + 非 0 code。<br>
 * 唯二例外是传输层鉴权语义：<b>认证失败 → HTTP 401</b>（前端 {@code request.ts}
 * 依据 {@code resp.status === 401} 清 token 并跳登录，见《10》§13）、
 * <b>权限不足 → HTTP 403</b>。<br>
 * 因此本枚举里只有 {@code 1001x} 中「凭证本身有问题」的项与 {@code 1002x} 的项
 * 是非 200，其余业务错误（含验证码错误、参数非法、资源不存在）都是 200。
 * </blockquote>
 *
 * <p>示例：{@code throw BizException.of(ErrorCode.KB_NOT_FOUND)} →
 * 客户端收到 HTTP 200 + {@code {"code":10043,"message":"知识库不存在",...}}。
 */
@Getter
public enum ErrorCode implements Serializable {

    /** 成功。 */
    SUCCESS(0, 200, "success"),

    // ==================== 1001x 认证类 ====================

    /** 未登录：请求头没有带 Authorization。 */
    UNAUTHORIZED(10010, 401, "未登录或登录凭证缺失"),
    /** 访问令牌已过期。 */
    TOKEN_EXPIRED(10011, 401, "登录已过期，请重新登录"),
    /** 访问令牌格式错误 / 签名不通过 / 已被注销。 */
    TOKEN_INVALID(10012, 401, "登录凭证无效"),
    /** 账号被管理员停用。 */
    ACCOUNT_DISABLED(10013, 401, "账号已停用，请联系管理员"),
    /** 账号待激活（管理员已建号但用户未激活）。 */
    ACCOUNT_PENDING(10014, 401, "账号待激活，请先完成激活"),
    /** 用户名或密码错误。故意不区分"用户不存在"以避免用户名枚举。 */
    BAD_CREDENTIALS(10015, 401, "用户名或密码错误"),
    /** 刷新令牌无效或已过期。 */
    REFRESH_TOKEN_INVALID(10018, 401, "刷新令牌无效或已过期"),

    /** 短信 / 邮箱验证码错误或已过期。 */
    SMS_CODE_INVALID(10016, 200, "验证码错误或已过期"),
    /** 验证码发送过于频繁（60 秒内重复请求）。 */
    SMS_CODE_TOO_FREQUENT(10017, 200, "验证码发送过于频繁，请稍后再试"),
    /** 密码强度不足（长度 / 字符组合不满足策略）。 */
    PASSWORD_WEAK(10019, 200, "密码强度不足"),

    // ==================== 1002x 权限类 ====================

    /** 已登录但缺少该操作所需权限点。 */
    FORBIDDEN(10020, 403, "无操作权限"),
    /** 越出当前角色数据范围（本部门 / 仅本人）。 */
    DATA_SCOPE_DENIED(10021, 403, "超出数据访问范围"),
    /** 无该知识库的访问授权。 */
    KB_ACCESS_DENIED(10022, 403, "无该知识库访问权限"),

    /** 内置角色（SUPER_ADMIN 等）不可编辑或删除。 */
    ROLE_BUILTIN_IMMUTABLE(10023, 200, "内置角色不可修改或删除"),
    /** 角色下仍有成员，拒绝删除。 */
    ROLE_IN_USE(10024, 200, "角色仍被用户引用，无法删除"),

    // ==================== 1003x 参数类 ====================

    /** 参数校验失败（@Valid 不通过 / 必填缺失 / 类型错误 / JSON 解析失败）。 */
    PARAM_INVALID(10030, 200, "参数校验失败"),
    USERNAME_EXISTS(10031, 200, "用户名已存在"),
    PHONE_EXISTS(10032, 200, "手机号已注册"),
    EMAIL_EXISTS(10033, 200, "邮箱已注册"),
    FILE_TYPE_UNSUPPORTED(10034, 200, "文件类型不支持"),
    FILE_TOO_LARGE(10035, 200, "文件超出大小限制"),
    /** 同知识库下已存在相同 MD5 的文档（去重命中）。 */
    DUPLICATE_DOC(10036, 200, "文档内容重复，已存在相同 MD5 的记录"),
    /** 验证码 scene 取值不在 login / register / reset-password 内。 */
    SMS_SCENE_INVALID(10037, 200, "验证码场景不合法"),

    // ==================== 1004x 资源类 ====================

    /** 通用资源不存在（路由不存在时走 HTTP 404，见 GlobalExceptionHandler）。 */
    NOT_FOUND(10040, 200, "资源不存在"),
    USER_NOT_FOUND(10041, 200, "用户不存在"),
    ROLE_NOT_FOUND(10042, 200, "角色不存在"),
    KB_NOT_FOUND(10043, 200, "知识库不存在"),
    DOC_NOT_FOUND(10044, 200, "文档不存在"),
    TASK_NOT_FOUND(10045, 200, "任务不存在"),
    /** 任务状态冲突（如对已完成的下载任务再次取消）。 */
    TASK_CONFLICT(10046, 200, "任务状态冲突"),
    QUOTA_EXCEEDED(10047, 200, "配额不足"),

    // ==================== 兜底 ====================

    /** 未捕获异常。对外只暴露 traceId，细节记日志。 */
    SYSTEM_ERROR(9999, 500, "系统异常，请稍后重试"),
    ;

    private final int code;

    /** 该错误码对应的 HTTP 状态码。 */
    private final int httpStatus;

    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    /** 按 code 反查枚举，查不到返回 {@link #SYSTEM_ERROR}。 */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return SYSTEM_ERROR;
    }

    /** 是否属于 1001x 认证类。 */
    public boolean isAuth() {
        return code >= 10010 && code <= 10019;
    }

    /** 是否属于 1002x 权限类。 */
    public boolean isPermission() {
        return code >= 10020 && code <= 10029;
    }

    /** 是否属于 1003x 参数类。 */
    public boolean isParam() {
        return code >= 10030 && code <= 10039;
    }

    /** 是否属于 1004x 资源类。 */
    public boolean isResource() {
        return code >= 10040 && code <= 10049;
    }
}
