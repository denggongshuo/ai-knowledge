package com.knowledge.ai.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClamAV 的 `INSTREAM` 客户端（`T-O04` 步 3）—— **协议级取证，不依赖真容器**。
 *
 * <h3>为什么用一个假 clamd 而不是真容器</h3>
 * <p>两条理由，第二条更重要：
 * <ol>
 *   <li>本机**拉不到 `clamav/clamav` 镜像**（registry 不可达，见计划 §9）——
 *       而那不该让这一批什么都验不了；</li>
 *   <li>真容器验的是「ClamAV 能认出病毒」—— 那是 ClamAV 自己的、被全世界验了二十年的事；
 *       而**本仓要验的是我方这一半**：协议拼对了没有、三种回复解析对了没有、
 *       认不出的回复会不会被误判成"干净"。这三件在假 clamd 上验得**更准**
 *       （真容器给不出"返回一句废话"这种输入）。</li>
 * </ol>
 *
 * <h3>🔴 本类最要紧的两条是**负向锚**</h3>
 * <p>{@link #unreachableIsSkippedNeverClean} 与 {@link #unknownReplyIsSkippedNeverClean}：
 * 「扫不了」被当成「干净」是本模块唯一不可接受的错 —— 那等于**假装扫过了**，
 * 而 `O4` 判据 6 的原话是「上传成功但列表显示"未扫描"标记（**不是**静默当作已扫描）」。
 * 这两条保证：只有确切的 `stream: OK` 才是 CLEAN，其余一律 SKIPPED。
 */
class ClamAvScannerTest {

    private static final byte[] SAMPLE = "hello".getBytes(StandardCharsets.UTF_8);

    // ==================================================================
    // 三态判定的正向与负向锚
    // ==================================================================

    @Test
    @DisplayName("未启用（enabled=false）⇒ SKIPPED，**一次连接都不建**")
    void disabledIsSkippedWithoutConnecting() {
        // 端口指向一个必然没人监听的地方：真去连的话会走 connect 超时（2s），
        // 而这里要在毫秒级返回 —— 那本身就是"没连"的证据
        ClamAvScanner scanner = new ClamAvScanner("127.0.0.1", 1, false, 5_000);
        ClamAvScanner.ScanResult result = scanner.scan(SAMPLE, "a.pdf");

        assertThat(result.verdict()).isEqualTo(ClamAvScanner.Verdict.SKIPPED);
        assertThat(result.detail()).contains("未启用");
    }

    @Test
    @DisplayName("🔴 负向锚：服务不可达 ⇒ SKIPPED，**绝不是 CLEAN**")
    void unreachableIsSkippedNeverClean() {
        ClamAvScanner scanner = new ClamAvScanner("127.0.0.1", freePort(), true, 2_000);
        ClamAvScanner.ScanResult result = scanner.scan(SAMPLE, "a.pdf");

        assertThat(result.verdict())
                .as("连不上扫描服务必须是「没扫成」，把它当成干净就是假装扫过了（判据 6 的原话）")
                .isEqualTo(ClamAvScanner.Verdict.SKIPPED);
        assertThat(result.detail()).contains("不可达");
    }

    @Test
    @DisplayName("stream: OK ⇒ CLEAN")
    void okIsClean() throws Exception {
        try (FakeClamd clamd = FakeClamd.replying("stream: OK")) {
            ClamAvScanner.ScanResult result = scannerOn(clamd).scan(SAMPLE, "a.pdf");

            assertThat(result.verdict()).isEqualTo(ClamAvScanner.Verdict.CLEAN);
            assertThat(result.detail()).isEqualTo("安全扫描通过");
        }
    }

    @Test
    @DisplayName("stream: <签名> FOUND ⇒ INFECTED，且签名要原样带进 detail")
    void foundIsInfected() throws Exception {
        try (FakeClamd clamd = FakeClamd.replying("stream: Eicar-Test-Signature FOUND")) {
            ClamAvScanner.ScanResult result = scannerOn(clamd).scan(SAMPLE, "a.pdf");

            assertThat(result.verdict()).isEqualTo(ClamAvScanner.Verdict.INFECTED);
            assertThat(result.detail()).contains("Eicar-Test-Signature");
        }
    }

    @Test
    @DisplayName("🔴 负向锚：病毒名里含 OK 也不能判成干净（只认确切的 `stream: OK`）")
    void signatureContainingOkIsStillInfected() throws Exception {
        try (FakeClamd clamd = FakeClamd.replying("stream: Trojan.OK.1234 FOUND")) {
            ClamAvScanner.ScanResult result = scannerOn(clamd).scan(SAMPLE, "a.pdf");

            assertThat(result.verdict())
                    .as("宽松判据（contains(\"OK\")）会把这条判成干净 —— 那是本模块最坏的错")
                    .isEqualTo(ClamAvScanner.Verdict.INFECTED);
        }
    }

    @Test
    @DisplayName("🔴 负向锚：认不出的回复 ⇒ SKIPPED（不是 CLEAN）")
    void unknownReplyIsSkippedNeverClean() throws Exception {
        try (FakeClamd clamd = FakeClamd.replying("INSTREAM size limit exceeded. ERROR")) {
            ClamAvScanner.ScanResult result = scannerOn(clamd).scan(SAMPLE, "a.pdf");

            assertThat(result.verdict())
                    .as("只认确切的两种形态；其余一律「没扫成」")
                    .isEqualTo(ClamAvScanner.Verdict.SKIPPED);
            assertThat(result.detail()).contains("无法识别");
        }
    }

    @Test
    @DisplayName("负向锚：对端不发 NUL 直接断开 ⇒ SKIPPED，不抛")
    void connectionClosedWithoutReplyIsSkipped() throws Exception {
        try (FakeClamd clamd = FakeClamd.replying("stream: OK")) {
            clamd.setDropConnection(true);
            ClamAvScanner.ScanResult result = scannerOn(clamd).scan(SAMPLE, "a.pdf");

            assertThat(result.verdict()).isEqualTo(ClamAvScanner.Verdict.SKIPPED);
        }
    }

    // ==================================================================
    // 协议本身
    // ==================================================================

    @Test
    @DisplayName("🔴 协议：发出的字节是 `zINSTREAM\\0` + 大端定长块 + 零长结束标记")
    void wireFormatIsInstreamProtocol() throws Exception {
        byte[] payload = new byte[10_000]; // 跨多个 4096 的块
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        try (FakeClamd clamd = FakeClamd.replying("stream: OK")) {
            scannerOn(clamd).scan(payload, "big.bin");

            byte[] received = clamd.awaitRequest();
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < payload.length; offset += 4096) {
                int size = Math.min(4096, payload.length - offset);
                // 大端（network byte order）—— 写反了会表现为超时而不是报错，
                // 所以这条断言是唯一能提前发现它的地方
                expected.write(new byte[]{
                        (byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
                expected.write(payload, offset, size);
            }
            expected.write(new byte[]{0, 0, 0, 0});

            assertThat(received)
                    .as("INSTREAM 的字节序或分块写错了 —— 真 clamd 上的表现是**连接超时**"
                            + "（对端读到一个天文数字的长度然后一直等），而不是报错，极难排查")
                    .isEqualTo(expected.toByteArray());
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static ClamAvScanner scannerOn(FakeClamd clamd) {
        return new ClamAvScanner("127.0.0.1", clamd.port(), true, 5_000);
    }

    /** 找一个当前空闲的端口（给"必然连不上"的用例用）。 */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 一个只做一件事的假 clamd：收完一整条 INSTREAM 请求，回一句预设的话。
     *
     * <p>它与真 clamd 的差别只在"不做病毒判定"—— 协议那一段是照文档实现的，
     * 而下面 {@link ClamAvScannerTest#wireFormatIsInstreamProtocol} 正是拿它当尺子量我们的编码。
     */
    private static final class FakeClamd implements AutoCloseable {

        private final ServerSocket server;
        private final String reply;
        private final AtomicReference<byte[]> request = new AtomicReference<>();
        private final CountDownLatch served = new CountDownLatch(1);
        private volatile boolean dropConnection;

        private FakeClamd(String reply) throws Exception {
            this.reply = reply;
            this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Thread worker = new Thread(this::serve, "fake-clamd");
            worker.setDaemon(true);
            worker.start();
        }

        static FakeClamd replying(String reply) throws Exception {
            return new FakeClamd(reply);
        }

        int port() {
            return server.getLocalPort();
        }

        void setDropConnection(boolean drop) {
            this.dropConnection = drop;
        }

        byte[] awaitRequest() throws InterruptedException {
            served.await(5, TimeUnit.SECONDS);
            return request.get();
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                ByteArrayOutputStream captured = new ByteArrayOutputStream();

                // 命令头：`zINSTREAM\0`
                byte[] head = in.readNBytes("zINSTREAM\0".length());
                captured.write(head);
                if (dropConnection) {
                    socket.close();
                    return;
                }
                // 定长块直到长度为 0
                while (true) {
                    byte[] lengthBytes = in.readNBytes(4);
                    if (lengthBytes.length < 4) {
                        break;
                    }
                    captured.write(lengthBytes);
                    int size = ((lengthBytes[0] & 0xFF) << 24) | ((lengthBytes[1] & 0xFF) << 16)
                            | ((lengthBytes[2] & 0xFF) << 8) | (lengthBytes[3] & 0xFF);
                    if (size == 0) {
                        break;
                    }
                    byte[] chunk = in.readNBytes(size);
                    captured.write(chunk);
                }
                request.set(captured.toByteArray());
                out.write((reply + "\0").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (Exception ex) {
                // 用例失败时这里吞掉即可 —— 断言拿不到 request 会报超时，那已经够了
            } finally {
                served.countDown();
            }
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (Exception ignored) {
                // 关不掉不影响断言
            }
        }
    }
}
