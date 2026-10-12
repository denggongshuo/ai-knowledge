package com.knowledge.ai.service;

import com.knowledge.ai.core.async.ChatRateLimiter;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 问答入口限流**接没接上** —— 真 HTTP 取证（2026-10-12）。
 *
 * <h3>它是"把闸拆了"这件事的反证锚</h3>
 * <p>{@code ChatRateLimiterTest} 只验了桶本身；桶能被正常创建却**从来没被调用**，
 * 表现是"限流配了、代码也在、但过载照样打穿" —— 那正是本仓点名过的那类
 * 「看不见的失效」。所以本用例从**真入口**打一次超限请求，断言它被 **HTTP 429** 挡下。
 * 谁把 {@code ChatController#completions} 里那两行删掉，本用例立刻红
 * （请求会一路跑进真实编排，拿到 200/SSE 而不是 429）。
 *
 * <h3>为什么把 qps 配成 1，而不是"配得极小让第一个请求就超限"</h3>
 * <p>Guava 的桶**总有一个初始突发**（{@code 1 秒 × qps} 个许可），所以"第一个请求就超限"
 * 是做不到的。而把 qps 配到 1 以下更糟：那时初始突发 < 1 个许可，
 * {@code tryAcquire()} 会为了"攒够一个许可"**真的睡**近 1/qps 秒 —— 用例会挂住。
 * 于是取 {@code qps = 1}（突发恰好 1 个、且拿到它不耗时），在
 * {@link #drainBurst()} 里先把这个突发用掉，再让 HTTP 请求成为**必然超限**的那一次。
 *
 * <h3>⚠️ 它不产生任何真实问答开销</h3>
 * <p>请求在**提交到执行器之前**就被拒了，所以既不检索也不调模型 ——
 * 这也是"闸必须在提交前"这条设计的一个副产品：超限请求的成本是零。
 */
@TestPropertySource(properties = "app.chat.rate-limit.qps=1")
class ChatRateLimitFlowTest extends ApiTestSupport {

    @Autowired
    private ChatRateLimiter chatRateLimiter;

    @Test
    @DisplayName("超限：问答入口返回 HTTP 429 + 10082（在提交到执行器之前就被挡下）")
    void overLimitRequestIsRejectedWith429() {
        String token = loginAsAdmin().data("$.accessToken");
        drainBurst();

        RawResponse response = post("/api/chat/completions", Map.of("message", "限流探针"), token);

        assertThat(response.status())
                .as("入口限流是**入口层**语义（请求根本没进业务）⇒ HTTP 429；"
                        + "发 200 带非 0 code 会被前端当成普通业务失败，而重发正是限流最不想要的动作")
                .isEqualTo(429);
        assertThat(response.code()).isEqualTo(ErrorCode.CHAT_RATE_LIMITED.getCode());
        assertThat(response.message())
                .as("G4-3：文案要给「现象 + 下一步动作」")
                .isNotBlank().contains("稍后");
        // 反过来说：如果这里拿到的是 SSE 帧或 200，说明闸没生效 —— 下面这条把"静默放行"也钉住
        assertThat(response.body()).as("超限响应必须是统一错误体（JSON），不是 SSE 帧")
                .contains("\"code\"").doesNotContain("data: ");
    }

    /**
     * 把令牌桶的初始突发耗光，让随后的 HTTP 请求必然超限。
     *
     * <p>⚠️ 有界循环而不是 {@code while}：万一 qps 被配大了，"耗光"这件事就不再是一瞬间的
     * —— 那时无界循环会一直转下去。这里转到第 3 次还不肯给 false，就是配置与本用例的前提
     * 不符，直接失败比挂住好。
     */
    private void drainBurst() {
        for (int i = 0; i < 3; i++) {
            if (!chatRateLimiter.tryAcquire()) {
                return;
            }
        }
        fail("令牌桶在 3 次内没被耗光 —— 本用例前提是 qps=1（初始突发恰好 1 个许可）");
    }
}
