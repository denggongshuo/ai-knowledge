package com.knowledge.ai.core.security;

import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;

/**
 * 登录用户上下文（ThreadLocal）。
 *
 * <p>写入方：{@link JwtAuthFilter}（在 {@code finally} 里清理）。
 * 读取方：Controller / Service / 审计切面。
 *
 * <p><b>为什么用 ThreadLocal 而不是把 LoginUser 当方法参数传</b>：
 * 审计切面、数据范围拼装、权限校验都要用它，一路透传会让业务方法签名被污染。
 * 代价是**不跨线程** —— 异步任务（解析 / 索引 / 打包）必须自己把
 * 需要的字段（userId、traceId）在提交时取出来带过去，
 * 不能在工作线程里调 {@link #require()}。
 */
public final class LoginUserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private LoginUserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 取当前登录用户，可能为 {@code null}。 */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /**
     * 取当前登录用户；未登录则抛 {@link ErrorCode#UNAUTHORIZED}。
     * 用于「必须登录」的业务方法，省去到处判空。
     */
    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    /** 当前用户 id；未登录抛 10010。 */
    public static Long requireUserId() {
        return require().getUserId();
    }

    /** 当前用户 id，可能在未登录时返回 {@code null}（用于可选登录的场景，如数据范围拼装）。 */
    public static Long userIdOrNull() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getUserId();
    }

    public static boolean isSuperAdmin() {
        LoginUser user = HOLDER.get();
        return user != null && user.isSuperAdmin();
    }

    /** 当前用户可访问的密级上限；未登录时返回最保守的 {@code PUBLIC}。 */
    public static SecretLevel secretLevelUpper() {
        LoginUser user = HOLDER.get();
        return user == null ? SecretLevel.PUBLIC : user.getSecretLevelUpper();
    }

    /** 是否已登录。 */
    public static boolean authenticated() {
        return HOLDER.get() != null;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
