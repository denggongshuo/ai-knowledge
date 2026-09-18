package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 恶意扫描的**整条链路**（`T-O04` 步 3）—— 真 HTTP 上传 → 异步扫描 → 落库 / 拦下 / 审计。
 *
 * <h3>为什么要起一个假 clamd，而不是连真容器</h3>
 * <p>本机拉不到 `clamav/clamav` 镜像（registry 不可达，见计划 §9）。但判据 4 要验的是
 * **本仓这一侧**：上传 → 扫描 → 命中 → 文档置 FAILED → 审计 HIGH 这条因果链。
 * 用假 clamd 走的是**真协议、真 TCP、真异步任务、真落库**，只有"病毒判定"那一步是替身
 * —— 而那是 ClamAV 自己的事（被全世界验了二十年），不是本仓要证的。
 *
 * <p>⚠️ **如实登记的边界**：本类证的**不是**"真 ClamAV 能认出 EICAR"，
 * 而是「扫描结论为 INFECTED 时，系统的行为正确」。后者才是本仓写的代码。
 *
 * <h3>替身按内容判，不是写死一句话</h3>
 * <p>它读完整条 INSTREAM 请求，**在字节里找 EICAR 测试串** —— 找得到回 `FOUND`、
 * 找不到回 `OK`。写死回一句话的话，"干净的那条路"就没法在同一个类里验了。
 */
class DocScanFlowTest extends ApiTestSupport {

    /** EICAR 测试串（业界标准的"这不是真病毒，但杀毒软件应当报它"样本）。 */
    private static final String EICAR =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private static FakeClamd clamd;

    @DynamicPropertySource
    static void scanProperties(DynamicPropertyRegistry registry) throws Exception {
        clamd = new FakeClamd();
        // 这一批的属性与其它用例类不同 ⇒ Spring 会为它单起一个上下文（可接受）：
        // 不这么做，"扫描器指向假 clamd"就只能靠改全局 yml，那会污染所有用例
        registry.add("app.av.clamav.enabled", () -> true);
        registry.add("app.av.clamav.port", () -> clamd.port());
        registry.add("app.av.clamav.host", () -> "127.0.0.1");
    }

    private String token;
    private long kbId;
    private String kbCode;

    @BeforeEach
    void createProbeKb() {
        token = loginAsAdmin().data("$.accessToken");
        kbCode = "kb_scanprobe" + System.nanoTime();
        RawResponse created = post("/api/kb", Map.of(
                "name", "S6B 扫描探针库",
                "code", kbCode,
                "deptId", 1,
                "visibleRoles", List.of("USER", "KB_ADMIN"),
                "secretLevel", "INTERNAL",
                "businessDomain", "研发 / 平台"), token).assertOk("建探针库");
        kbId = ((Number) created.data("$.id")).longValue();
    }

    @AfterEach
    void dropProbeKb() {
        // 库行 + 文档 + 分片 + 作业按 kb 级联清（`ProbeCleanup` 的登记表认识它们）
        jdbc.update("delete from sys_async_task where kb_id = ?", kbId);
        jdbc.update("delete from sys_chunk where kb_id = ?", kbId);
        jdbc.update("delete from sys_document where kb_id = ?", kbId);
        jdbc.update("delete from sys_kb where id = ?", kbId);
    }

    @Test
    @DisplayName("判据 4：命中恶意内容 ⇒ 文档置 FAILED + scan_status=INFECTED + 审计 HIGH")
    void maliciousFileIsBlockedAndAudited() throws Exception {
        byte[] body = ("这是 EICAR 测试串：" + EICAR + "\n").getBytes(StandardCharsets.UTF_8);

        long docId = uploadAndWaitForScan("eicar.txt", body);
        // 上传后扫描是异步的 —— 等到它落库；替身命中时结论是 INFECTED
        awaitScanVerdict(docId, "INFECTED");
        // ⚠️ **还要等 `status` 落定**：`scan_status` 先写、`markFailed` 紧跟其后，
        // 只等前者会读到上一刻的 `UPLOADED` —— 那是一次纯粹的时序假红
        //（实测踩到：断言报"必须置 FAILED"，而库里的 FAILED 在几十毫秒后就有了）
        awaitDocStatus(docId, "FAILED");

        Map<String, Object> row = scanRow(docId);
        assertThat(row.get("scan_status")).isEqualTo("INFECTED");
        // 签名是 clamd 给的原文（`Eicar-Test-Signature`，大小写混合）—— 按忽略大小写比
        assertThat((String) row.get("scan_note")).as("扫描说明应带上病毒签名")
                .containsIgnoringCase("eicar");
        assertThat(row.get("status"))
                .as("被判恶意的文档必须置 FAILED —— 它不该被解析、更不该进检索")
                .isEqualTo("FAILED");
        assertThat((String) row.get("error_msg")).as("失败原因必须可执行（G4-3）").contains("隔离");

        // 审计：动作名「恶意内容拦截」、档次 HIGH、target 到文档级。
        // ⚠️ **要轮询**：`scanAndRecord` 里 `markFailed` 在前、`auditTrail.maliciousBlocked` 在后，
        // 而上面刚等到 status 落定就查审计 ⇒ 会读到"日志还没写"。
        // 单独跑这个类时它一直是绿的（时间差刚好被掩盖），**全量跑才暴露** ——
        // 与 `#129`「每批绿不绿是软的」同一族：只在小样本下成立的时序假设。
        Integer audits = awaitAudit(docId);
        assertThat(audits)
                .as("O4 判据 4 的后半句就是「审计 HIGH」—— 没有这条日志，安全事件就只剩一个状态位")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("干净文件 ⇒ scan_status=CLEAN，文档照常走下去（扫描不阻断）")
    void cleanFileProceeds() throws Exception {
        byte[] body = "这是一份正常的会议纪要，没有任何恶意内容。\n".getBytes(StandardCharsets.UTF_8);

        long docId = uploadAndWaitForScan("normal.txt", body);
        awaitScanVerdict(docId, "CLEAN");

        Map<String, Object> row = scanRow(docId);
        assertThat(row.get("scan_status")).isEqualTo("CLEAN");
        assertThat(row.get("status"))
                .as("扫描通过不该改动文档状态机 —— 它只回答一个事实")
                .isNotEqualTo("FAILED");
    }

    @Test
    @DisplayName("判据 6：文档列表回带 scanStatus，前端才有「已扫描 / 已拦截」可显示")
    void listCarriesScanStatus() throws Exception {
        long docId = uploadAndWaitForScan("list.txt",
                "普通内容。\n".getBytes(StandardCharsets.UTF_8));
        awaitScanVerdict(docId, "CLEAN");

        RawResponse page = get("/api/kb/" + kbId + "/docs", token).assertOk("文档列表");
        List<String> statuses = page.data("$.list[*].scanStatus");

        assertThat(statuses)
                .as("列表不回带 scanStatus，前端那一列就永远是「—」——接口通了但页面是空的")
                .contains("CLEAN");
    }

    @Test
    @DisplayName("宏检测：.md 结构上不可能带宏 ⇒ has_macro=0（**不是** NULL）")
    void plainTextIsRecordedAsNoMacro() throws Exception {
        long docId = uploadAndWaitForScan("macro.md",
                "# 标题\n\n正文。\n".getBytes(StandardCharsets.UTF_8));

        // ⚠️ 必须先等到**扫描任务**跑完再查：`has_macro` 由转存任务写、`scan_status` 由扫描任务写，
        // 而扫描任务排在它后面 —— 等扫描落库即等于两个都写完了。
        // 第一版直接查，读到的是 `NULL`（还没写），表现为"宏检测没生效"的假红。
        awaitScanVerdict(docId, "CLEAN");

        Object hasMacro = jdbc.queryForObject(
                "select has_macro from sys_document where id = ?", Object.class, docId);
        assertThat(hasMacro)
                .as("NULL 会被读成「没检测过」，而 txt/md 是**确定没有宏** —— 两者必须能区分")
                .isNotNull();
        // ⚠️ 同样是 `TINYINT(1)`：驱动回的是 `Boolean`，硬转 `Number` 会抛
        //（主代码那边的 `boolOf` 就是为这条踩出来的）
        Boolean noMacro = hasMacro instanceof Boolean b ? b : ((Number) hasMacro).intValue() != 0;
        assertThat(noMacro)
                .as("md 结构上不可能带宏 ⇒ 必须是「确认没有」")
                .isFalse();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 上传并等文档行出现（返回 docId）。扫描结论由 {@link #awaitScanVerdict} 单独等。 */
    private long uploadAndWaitForScan(String name, byte[] content) {
        RawResponse accepted = postMultipart("/api/kb/" + kbId + "/doc/upload", content, name,
                name.endsWith(".md") ? "text/markdown" : "text/plain", token).assertOk("上传 " + name);
        long docId = ((Number) accepted.data("$.docId")).longValue();
        // 上传任务本身会转存 + 投扫描；扫描是独立任务。等文档行出现即可
        // （`scan_status` 由扫描任务回填，见 awaitScanVerdict）
        for (int i = 0; i < 100; i++) {
            Integer n = jdbc.queryForObject(
                    "select count(*) from sys_document where id = ?", Integer.class, docId);
            if (n != null && n > 0) {
                break;
            }
            sleep(100);
        }
        return docId;
    }

    /** 轮询到扫描结论落库为止（异步任务在别的线程上跑）。 */
    private void awaitScanVerdict(long docId, String expected) {
        for (int i = 0; i < 150; i++) {
            Object verdict = jdbc.queryForObject(
                    "select scan_status from sys_document where id = ?", Object.class, docId);
            if (verdict != null) {
                assertThat(verdict).as("扫描结论与预期不符").isEqualTo(expected);
                return;
            }
            sleep(100);
        }
        throw new AssertionError("等待扫描结论超时（15s）—— 扫描任务没有落库：docId=" + docId);
    }

    /** 轮询「恶意内容拦截」那条审计日志（它写在 `markFailed` 之后）。 */
    private Integer awaitAudit(long docId) {
        for (int i = 0; i < 100; i++) {
            Integer n = jdbc.queryForObject(
                    "select count(*) from sys_audit_log where action = ? and risk_level = 'HIGH' and target = ?",
                    Integer.class, "恶意内容拦截", "文档#" + docId);
            if (n != null && n > 0) {
                return n;
            }
            sleep(100);
        }
        return 0;
    }

    /** 轮询到文档状态落定（`markScan` 与 `markFailed` 是两次独立的 UPDATE）。 */
    private void awaitDocStatus(long docId, String expected) {
        for (int i = 0; i < 100; i++) {
            Object status = jdbc.queryForObject(
                    "select status from sys_document where id = ?", Object.class, docId);
            if (expected.equals(status)) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("等待文档状态 " + expected + " 超时（10s）：docId=" + docId);
    }

    private Map<String, Object> scanRow(long docId) {
        return jdbc.queryForMap(
                "select scan_status, scan_note, status, error_msg, has_macro from sys_document where id = ?",
                docId);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================================================================
    // 假 clamd（按内容判：含 EICAR 就 FOUND）
    // ==================================================================

    /**
     * 协议与真 clamd 一致（`zINSTREAM\0` + 大端定长块 + 零长结束），
     * **判定按内容** —— 字节里找得到 EICAR 串就回 FOUND，否则 OK。
     */
    private static final class FakeClamd implements AutoCloseable {

        private final ServerSocket server;
        private final List<byte[]> seen = new CopyOnWriteArrayList<>();

        private FakeClamd() throws Exception {
            this.server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            Thread worker = new Thread(this::loop, "fake-clamd-scan");
            worker.setDaemon(true);
            worker.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void loop() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    byte[] payload = readInstream(socket.getInputStream());
                    seen.add(payload);
                    boolean infected = new String(payload, StandardCharsets.ISO_8859_1).contains("EICAR");
                    String reply = infected
                            ? "stream: Eicar-Test-Signature FOUND"
                            : "stream: OK";
                    OutputStream out = socket.getOutputStream();
                    out.write((reply + "\0").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (Exception ex) {
                    // 关服务时的 SocketException 是正常路径，不记
                }
            }
        }

        private static byte[] readInstream(InputStream in) throws Exception {
            in.readNBytes("zINSTREAM\0".length());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                byte[] lengthBytes = in.readNBytes(4);
                if (lengthBytes.length < 4) {
                    break;
                }
                int size = ((lengthBytes[0] & 0xFF) << 24) | ((lengthBytes[1] & 0xFF) << 16)
                        | ((lengthBytes[2] & 0xFF) << 8) | (lengthBytes[3] & 0xFF);
                if (size == 0) {
                    break;
                }
                out.write(in.readNBytes(size));
            }
            return out.toByteArray();
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (Exception ignored) {
                // 关闭失败不影响断言
            }
        }
    }
}
