package com.knowledge.ai.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-O28`：**同库只能有一个异步消费者** —— 起跑前的机器检查。
 *
 * <h3>为什么这是一条判据，而不是一句提醒</h3>
 * <p>异步作业是**队列**（`sys_async_task` + `FOR UPDATE SKIP LOCKED`，见
 * {@code SysAsyncTaskRepository} 的类注释）。本机的开发后端只要在跑，它就是一个
 * 与用例**平权**的消费者：谁先 `SKIP LOCKED` 领到就是谁的。
 * 于是用例里那句 `parseWorker.drainOnce()` 会领到 **0 条**（作业被另一个进程拿走了），
 * 而现象是**每次都不同的假红** —— 文档停在 `UPLOADED`、任务查不到、断言散在各处，
 * 错误的信号指向错误的地方。本仓已经为此浪费过整轮排查（`#187` 那一族）。
 *
 * <p>所以这条检查的口径是：**发现第二个消费者 ⇒ 红**，而不是记一条 warn 让它过去。
 * 警告在这里等于没有 —— 假红最难的地方就是"看起来像功能坏了"。
 *
 * <h3>它检查的是什么</h3>
 * <p>本机开发后端的监听端口（默认 `8080`，可用 {@code -Dak.backend.port=xxxx} 覆盖）。
 * 端口在听 ⇒ 那个进程极可能连着同一个库、跑着同一个 worker。
 * <p>⚠️ 它**不是**完备判据：把后端起到别的端口上它就看不见。
 * 但它是**可执行**的那一条 —— 完备的判据需要"另一个进程的 classpath / 连接串"，
 * 那是运维面的信息，测试进程拿不到。宁可给出一个能立刻照做的动作，也不要一个
 * 谁也执行不了的"完备"检查（这条取舍与 `ProbeArtifacts` 的盲区台账同源：
 * **判不了的部分要写下来，而不是假装判过了**）。
 */
class SingleAsyncConsumerGuardTest {

    /** 本机开发后端的默认端口（`application.yml` 的 `server.port`）。 */
    private static final int DEV_BACKEND_PORT = 8080;

    /** 连接探活超时：本机回环，超过这个数基本就是"没在听"。 */
    private static final int CONNECT_TIMEOUT_MS = 300;

    @Test
    @DisplayName("T-O28：8080 没有第二个后端在跑（否则它会偷走用例的异步作业 ⇒ 假红）")
    void noSecondAsyncConsumerIsListening() {
        int port = Integer.getInteger("ak.backend.port", DEV_BACKEND_PORT);

        assertThat(isListening(port))
                .as("""

                        🔴 检测到 %d 端口上有服务在监听 —— 那极可能是本机的开发后端。
                        它会与用例**平权**地抢走 sys_async_task 里的作业（for update skip locked），
                        于是用例里的 drainOnce() 领到 0 条，表现为"每次都不同的假红"。

                        处置（按 PID 杀**真正的 JVM**，不是只关掉那个 shell）：
                          1) netstat -ano | findstr :%d        ← 取最后一列的 PID
                          2) taskkill /PID <pid> /F
                          3) 重新跑一次本检查，确认端口已释放

                        若后端确实要起到别的端口上，用 -Dak.backend.port=<那个端口> 重跑
                        （但那时本检查就看不见它了 —— 见类注释里"它不完备"那一段）。

                        """.formatted(port, port))
                .isFalse();
    }

    /** 回环上能不能连上。连不上 = 没有监听者。 */
    private static boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
