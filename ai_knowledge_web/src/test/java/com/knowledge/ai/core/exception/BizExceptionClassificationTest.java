package com.knowledge.ai.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BizException#isClientError()} 的口径回归测试。
 *
 * <p>这条测试的来历：该判断原本写成 {@code errorCode.getCode() < 9999}，
 * 而业务码是**五位数**（10010~10056），于是它**恒为 false** ——
 * {@code GlobalExceptionHandler} 里那条 {@code warn} 分支成了死代码，
 * 所有业务异常（验证码错、令牌过期、凭证失效）全按 ERROR 级打完整堆栈。
 *
 * <p>之所以能在 S0 之后才被发现：它**不影响任何响应**，
 * 只影响日志级别，所以功能测试全绿也照样漏过去。这类"不影响返回值、
 * 只影响可观测性"的缺陷，只能靠直接断言分类规则本身来防住。
 *
 * <p>不启动 Spring 上下文：本测试只关心枚举与纯函数，毫秒级跑完。
 */
class BizExceptionClassificationTest {

    @Test
    @DisplayName("所有业务错误码都是「客户端过错」——它们都不该打 ERROR 级日志")
    void allBusinessCodesAreClientErrors() {
        for (ErrorCode code : ErrorCode.values()) {
            if (code == ErrorCode.SYSTEM_ERROR) {
                continue;
            }
            assertTrue(new BizException(code, code.getMessage()).isClientError(),
                    () -> code + "（httpStatus=" + code.getHttpStatus() + "）应判为客户端过错，"
                            + "否则可预期的用户错误会以 ERROR 级灌满 -error.log");
        }
    }

    @Test
    @DisplayName("只有 SYSTEM_ERROR 是服务端过错（500 语义）")
    void systemErrorIsNotClientError() {
        assertFalse(new BizException(ErrorCode.SYSTEM_ERROR).isClientError(),
                "系统异常必须打 ERROR 级日志，否则真故障会被淹没");
    }

    @Test
    @DisplayName("按 HTTP 状态码判定：4xx 与业务 200 是客户端过错，5xx 不是")
    void classificationFollowsHttpStatus() {
        // 401：凭证本身有问题（前端据此清 token 跳登录），对用户是可预期结果
        assertTrue(new BizException(ErrorCode.BAD_CREDENTIALS).isClientError());
        assertTrue(new BizException(ErrorCode.TOKEN_EXPIRED).isClientError());
        // 403：权限不足
        assertTrue(new BizException(ErrorCode.FORBIDDEN).isClientError());
        // 200：绝大多数业务错误（含 S1 新增的 1005x 加固段）
        assertTrue(new BizException(ErrorCode.CAPTCHA_INVALID).isClientError());
        assertTrue(new BizException(ErrorCode.LOGIN_LOCKED).isClientError());
        assertTrue(new BizException(ErrorCode.RESET_TOKEN_INVALID).isClientError());
        assertTrue(new BizException(ErrorCode.OLD_PASSWORD_INVALID).isClientError());

        // 判定必须与 ErrorCode 自带的 httpStatus 一致，避免两处口径各说各话
        for (ErrorCode code : ErrorCode.values()) {
            assertEquals(code.getHttpStatus() < 500,
                    new BizException(code, code.getMessage()).isClientError(),
                    () -> code + " 的分类与其 httpStatus=" + code.getHttpStatus() + " 不一致");
        }
    }
}
