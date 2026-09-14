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
 *   <li>{@code 1005x} —— 认证加固类（S1 新增，见下方说明）</li>
 *   <li>{@code 9999} —— 系统异常兜底</li>
 * </ul>
 *
 * <p><b>关于新增的 {@code 1005x}</b>：{@code 1001x} 的 10 个位置（10010~10019）在 S0
 * 就已排满（未登录 / 过期 / 无效 / 停用 / 待激活 / 密码错 / 验证码 / 发码频繁 /
 * 刷新无效 / 密码弱），而 S1 引入的风控语义（图形验证码、连续失败锁定、重置凭证）
 * 与它们**不是同一层**：前者描述"凭证本身出了什么问题"，后者描述"因为可疑行为被拦下"。
 * 混在一起会让前端无法区分"要重新登录"与"要输验证码"——前者跳登录页，后者留在原页。
 * 因此单开一段，属**纯新增**，不影响任何既有码值。
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
    /**
     * 知识库编码已存在（T-401）。
     *
     * <p>为什么不复用 {@link #PARAM_INVALID}：创建向导的编码是**用户手填的持久标识**
     * （{@code uk_kb_code} 唯一约束，创建后不可改）。与"编码格式不合法"分开报，
     * 前端才能给出可执行的动作 —— 前者提示"换个编码"，后者提示"改成 kb_ 开头的小写"。
     * 混在一起用户只会反复提交同一个重复的编码。
     */
    KB_CODE_EXISTS(10038, 200, "知识库编码已存在，请更换"),

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
    /** 该类型下没有可用的模型配置（未配 key / 未启用 / 配置被删）。 */
    MODEL_NOT_CONFIGURED(10048, 200, "模型未配置或不可用"),

    // ==================== 1005x 认证加固类（S1 新增） ====================

    /** 图形验证码错误或已过期。前端应刷新验证码后重试。 */
    CAPTCHA_INVALID(10050, 200, "图形验证码错误或已过期"),
    /** 该次登录已被要求出示图形验证码，但请求里没带。 */
    CAPTCHA_REQUIRED(10051, 200, "请先填写图形验证码"),
    /** 连续失败达阈值，账号临时锁定（G1-5）。 */
    LOGIN_LOCKED(10052, 200, "连续登录失败次数过多，账号已锁定 10 分钟，请稍后再试"),
    /** 短信登录链路暂未开放（S1 范围决定，接口保留）。 */
    SMS_LOGIN_UNSUPPORTED(10053, 200, "短信登录暂未开放，请使用账号密码登录"),
    /** 重置密码的一次性凭证无效 / 已过期 / 已使用（T-108）。 */
    RESET_TOKEN_INVALID(10054, 200, "重置凭证无效或已过期，请重新验证身份"),
    /** 修改密码时原密码不正确（T-111）。 */
    OLD_PASSWORD_INVALID(10055, 200, "原密码不正确"),
    /** 新旧密码相同，无变更意义（T-111）。 */
    PASSWORD_UNCHANGED(10056, 200, "新密码不能与原密码相同"),

    // ==================== 1006x 用户与组织管理类（S2 新增） ====================

    DEPT_NOT_FOUND(10065, 200, "部门不存在"),
    /** 部门下仍有用户或子部门（知识库校验 S3 起生效），删除被拒。 */
    DEPT_NOT_EMPTY(10066, 200, "部门下仍有成员或子部门，请先转移"),
    /** 对当前登录账号自身的危险操作（停用自己 / 删除自己）。G2-5 自锁防护。 */
    USER_SELF_OPERATION_DENIED(10067, 200, "不能对当前登录账号执行该操作"),
    /** 停用/删除最后一个启用中的超级管理员被拒（G2-5 自锁防护）。 */
    LAST_SUPER_ADMIN(10068, 200, "系统至少保留一个启用的超级管理员"),
    /** 有业务数据的账号禁止删除，只能停用（G2-6）。 */
    USER_HAS_BUSINESS_DATA(10069, 200, "该账号存在知识库或文档业务数据，禁止删除，请改为停用"),
    /** 邀请令牌无效 / 已过期 / 已被使用。 */
    INVITE_INVALID(10070, 200, "邀请链接无效或已过期，请联系管理员重发"),
    /** 批量导入的模板行不合法（列缺失 / 无法解析）。 */
    IMPORT_TEMPLATE_INVALID(10071, 200, "导入文件不符合模板，请下载模板后填写"),

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
