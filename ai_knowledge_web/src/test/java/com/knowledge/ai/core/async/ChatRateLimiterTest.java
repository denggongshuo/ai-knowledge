package com.knowledge.ai.core.async;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 问答入口令牌桶（`app.chat.rate-limit.qps`）—— 纯单测，不起 Spring 上下文。
 *
 * <p>这里验的是**桶本身**的两条语义；"桶有没有接到问答入口上"由
 * {@code ChatRateLimitFlowTest} 走真 HTTP 验（那个才是反证"把闸拆了"的锚）。
 *
 * <h3>⚠️ 本类的判据是按 Guava 的**实测行为**写的，不是按"令牌桶"这个名字推的</h3>
 * <p>对当前钉住的版本（`33.4.0-jre`）实测（`RateLimiter.create(qps)` 后连问 5 次）：
 * <pre>
 *   qps=0.001 / 0.2 / 1 / 2 / 5 / 20  —— 结果**完全一样**：
 *       第 1 次 = true（立即，耗时可忽略）；第 2 次起 = false（立即，耗时可忽略）
 *   耗尽后 sleep(1.2s)（qps=1）⇒ 又能取到 1 个
 * </pre>
 * 也就是说：**没有突发**（不是"1 秒 × qps 个许可"），第一个请求立即放行、此后严格按
 * {@code 1/qps} 的间隔补一个。⇒ 断言只能写"恰好 1 个"，写"突发 qps 个"会红
 * （本用例第一版就是这么写错的）。
 *
 * <p>这条差异已写进 {@link ChatRateLimiter} 的类注释 —— 它直接影响运维对"20 QPS"的预期。
 */
class ChatRateLimiterTest {

    @Test
    @DisplayName("恰好放行 1 个，随后立即拒绝（Guava 的桶无突发 —— 实测，非推断）")
    void allowsExactlyOneThenRejects() {
        ChatRateLimiter limiter = new ChatRateLimiter(2);

        assertThat(limiter.enabled()).as("qps>0 ⇒ 真的在限流").isTrue();
        assertThat(limiter.tryAcquire()).as("第 1 个：立即放行").isTrue();
        // ⚠️ 写"突发 2 个"会红：实测 qps=2 时第 2 次就已经是 false
        assertThat(limiter.tryAcquire()).as("第 2 个：已经超了").isFalse();
        assertThat(limiter.tryAcquire()).as("拒绝不消耗许可，再问还是拒绝").isFalse();
    }

    @Test
    @DisplayName("不排队等待：连问 20 次必须秒回（若实现会阻塞，光等待就要十几秒）")
    void doesNotBlockWhenRejected() {
        ChatRateLimiter limiter = new ChatRateLimiter(1);

        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // qps=1 ⇒ 每个许可间隔 1 秒；真排队的话 20 次 ≥ 19 秒。这里给 2 秒的**宽裕**上限，
        // 它只用来区分"立即返回"与"阻塞等待"，不测性能。
        // 🔴 这条不是洁癖：被限流的是 SSE 长连接，让请求线程排队等令牌，等于把过载
        //    从无界执行器挪到容器线程池（见 ChatRateLimiter 类注释第 2 条）。
        assertThat(elapsedMs).as("20 次 tryAcquire 应当立即返回（实测耗时 " + elapsedMs + " ms）")
                .isLessThan(2000L);
    }

    @Test
    @DisplayName("qps ≤ 0：限流关闭，恒放行；且 enabled() 如实为 false")
    void nonPositiveQpsDisablesLimiting() {
        for (double qps : new double[]{0, -1, -0.5}) {
            ChatRateLimiter limiter = new ChatRateLimiter(qps);

            assertThat(limiter.enabled())
                    .as("qps=" + qps + " ⇒ 关闭。⚠️ 只断言 tryAcquire 恒真分不出"
                            + "「关着」与「桶坏了」，所以这条断言是必须的")
                    .isFalse();
            for (int i = 0; i < 50; i++) {
                assertThat(limiter.tryAcquire()).as("关着时第 " + i + " 次也必须放行").isTrue();
            }
        }
    }
}
