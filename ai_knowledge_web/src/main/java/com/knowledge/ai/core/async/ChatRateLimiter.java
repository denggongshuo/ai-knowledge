package com.knowledge.ai.core.async;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 问答入口的**全局令牌桶**（Guava {@link RateLimiter}，配置项 {@code app.chat.rate-limit.qps}）。
 *
 * <h3>它补的是哪个洞</h3>
 * <p>问答跑在 {@code AsyncConfig#chatVirtualExecutor} 上 —— 那是**无界虚拟线程执行器**：
 * 没有队列上限，也就**不存在"拒绝"这条路径**（`T-D07` 换执行器时就写明了这个代价）。
 * 于是过载不再被挡在入口，而是一路传导到模型供应商：那里才报 429/超时，
 * 而那时用户已经盯着"生成中"等了几十秒，并且**已经占着一个连接与一次检索**。
 * 本类把过载挡在**提交之前**。
 *
 * <h3>四个刻意的取舍（都是计划里挂着的问号，这里定案）</h3>
 * <ol>
 *   <li><b>全局一个桶</b>，不按用户分桶（用户 2026-10-11 裁定）。保护的是**模型侧的总吞吐**，
 *       而它是全站共享的 —— 按用户分桶会让"一个用户狂发"照样打满供应商。</li>
 *   <li><b>放在入口、不排队</b>：{@link #tryAcquire()} **立即**返回，不阻塞等待。
 *       ⚠️ 这条不是性能洁癖：被限流的是 **SSE 长连接**，让请求线程排队等令牌，
 *       等于把"被限的请求"从执行器挪到容器线程池 —— 过载只是换了个地方堆。</li>
 *   <li><b>不做成 {@code @Bean} 放在 {@code AsyncConfig} 里</b>：那里放的是"怎么跑"，
 *       而"要不要拦"是一个有自己状态（关/开）与自己的语义（{@code qps <= 0} 的含义）的组件。
 *       包一层之后，调用方只有一句 {@link #tryAcquire()}，
 *       不必为"关着"再写一条分支 —— 分支少一条就少一条能写错的地方。</li>
 *   <li><b>{@code qps <= 0} = 关闭限流</b>（放行全部）。压测/联调要的是"别拦我"，
 *       而不是"全都拒掉" —— 后者直接表现为"问答挂了"，是个会让人白查半天的现象。</li>
 * </ol>
 *
 * <h3>⚠️ 进程内桶 —— 多实例部署时每个实例各一份</h3>
 * <p>Guava 的桶在**本进程内存**里。单实例部署（当前形态）它就是全站配额；
 * 一旦扩到 N 个实例，实际全局配额 ≈ {@code N × qps}。要真正的全局配额得换
 * 分布式桶（Redis），那不在本批范围内 —— 但**部署形态一变，这里必须回来重看**。
 *
 * <h3>🔴 「20 QPS」到底是什么意思（实测口径，不是按名字推的）</h3>
 * <p>对当前钉住的 `guava:33.4.0-jre` 实测（`RateLimiter.create(qps)` 后连问若干次）：
 * <pre>
 *   qps = 0.001 / 0.2 / 1 / 2 / 5 / 20 —— 结果一致：
 *       第 1 次 = 放行（立即）· 第 2 次起 = 拒绝（立即）· 耗尽后每 1/qps 秒补一个
 * </pre>
 * 两条要记住的推论：
 * <ul>
 *   <li><b>没有突发</b>：不是"1 秒内可以连放 qps 个"，而是"先放 1 个，此后每 {@code 1/qps}
 *       秒放 1 个"。⇒ 20 QPS 的观感是**平均每秒 20 次**，不是"同时能开 20 个"。
 *       所以紧接着的第二个提问（哪怕隔 5 毫秒）也会被拒 —— 这是**预期行为**，不是 bug；</li>
 *   <li><b>{@link #tryAcquire()} 从不阻塞</b>：拿不到就当场 {@code false}，
 *       不会为了攒许可睡过去（那是普通 {@code acquire()} 的行为，本类刻意不用）。</li>
 * </ul>
 * <p>这两点都有用例钉着（{@code ChatRateLimiterTest}），换 Guava 版本时若行为变了会当场红。
 *
 * <h3>为什么是启动期定值</h3>
 * <p>{@code qps} 只在构造时读一次。运行期改配置不生效（要 {@code @RefreshScope} + 配置中心），
 * 特意不做：一个能热改的限流阈值，配错时的表现是"流量忽大忽小地抖动"，很难归因。
 * 真要调就重启 —— 限流阈值不是需要秒级生效的东西。
 */
@Slf4j
@Component
public class ChatRateLimiter {

    /** {@code null} = 限流**关闭**（{@code qps <= 0}）。见类注释第 4 条取舍。 */
    private final RateLimiter limiter;

    public ChatRateLimiter(@Value("${app.chat.rate-limit.qps:20}") double qps) {
        if (qps <= 0) {
            this.limiter = null;
            log.warn("问答入口限流**已关闭**（app.chat.rate-limit.qps={} ≤ 0）——"
                    + "所有提问都会被放行，过载将直接打到模型供应商。压测时可如此，生产环境不要这样配", qps);
        } else {
            this.limiter = RateLimiter.create(qps);
            log.info("问答入口令牌桶已就绪：全局 {} QPS（**进程内**桶 ⇒ 多实例部署时每个实例各一份）", qps);
        }
    }

    /**
     * 取一个令牌。**立即返回**，不等待 —— 见到 {@code false} 的调用方应当直接拒绝该次请求
     * （抛 {@code ErrorCode#CHAT_RATE_LIMITED} → HTTP 429），而不是重试或排队。
     *
     * <p>限流关闭时恒返回 {@code true}。
     */
    public boolean tryAcquire() {
        return limiter == null || limiter.tryAcquire();
    }

    /**
     * 是否**真的在限流**（{@code qps > 0}）。
     *
     * <p>存在的意义是让用例能把"关着所以全放行"与"桶坏了所以全放行"分开 ——
     * 只看 {@link #tryAcquire()} 的返回值，两者长得一模一样。
     */
    public boolean enabled() {
        return limiter != null;
    }
}
