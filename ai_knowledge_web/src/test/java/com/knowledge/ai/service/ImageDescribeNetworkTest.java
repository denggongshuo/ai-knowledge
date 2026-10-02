package com.knowledge.ai.service;

import com.knowledge.ai.core.async.ParseWorker;
import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.pipeline.ImageInfo;
import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.pipeline.PdfDocumentParser;
import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.pipeline.chat.ChatSink;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 **真 VL 跑通「文档图片描述」整条链**（`T-512` 挂起判据 4 / 5 / 8 / 9 / 13 / 14 的兑现）。
 *
 * <h3>为什么这条判据挂了这么久</h3>
 * <p>前面所有图片相关的用例都是**替身级**的（假 sink / 假 caller）：
 * 它们能证"去重位置对""顺序对""真的并行了"，**证不了"真模型跑完整条链会怎样"**。
 * 而测试配置里 {@code m05.image.describe.enabled=false}（真 VL 太慢，见那份 properties），
 * 所以默认跑批**从来不会走这条链**。
 *
 * <h3>⚠️ 它会真花钱 + 真耗时</h3>
 * <p>端点是远程付费 API，单次 **95~115 秒**。本用例用 `1-DeepSeek使用.pdf`（26 张可描述）
 * ⇒ 并发 4 时约 **5~6 分钟**。故：
 * <pre>
 *   mvn -B test -Dtest=ImageDescribeNetworkTest -Dvision.probe.run=true
 * </pre>
 *
 * <h3>判据 9（描述进得了召回）为什么挂在**同一条**用例上</h3>
 * <p>它要的证据 —— "IMAGE 块带着真描述进了 Milvus、拿描述里的词问得回来" ——
 * 正是上面那次真跑攒出来的产物。另起一条用例等于**再花一遍这 5 分钟的真 VL** 去造同一批
 * IMAGE 块，而两次跑出来的描述还是不同的文本。
 *
 * <p>所以本类同时开 {@code m05.vectorize.enabled=true}：测试配置默认关着向量化
 * （同一条"真调外部模型"的理由），关着的话描述只是躺在 {@code sys_chunk.raw_content} 里，
 * **根本没进 Milvus**，"能不能召回"就无从谈起。
 *
 * <p>而且提问走的是**真问答链路**（`ChatStreamService#chat`）而不是直接调
 * `RetrievalService#search`：判据原文要的证据落点是 `sys_retrieval_trace`，
 * 那张表只由 `RetrievalTraceWriter` 在问答收尾时写。直接调检索服务的话那个 trace
 * 对象只存在于返回值里，库里一行都没有 —— 等于**只验了一半**。
 *
 * <p>⚠️ 提问用的词是**从落库的真实描述里现取**的（那条最长行），不硬编码任何词语 ——
 * 描述是模型现生成的，写死一个词会让下一次跑批变成"库里根本没有这个词"。
 *
 * <h3>顺带：它是 `images/` 前缀兜底的**真对象**验证</h3>
 * <p>本用例会往 MinIO 写**真实的图片对象**（不是替身），而收尾走
 * {@code ApiTestSupport} 那套扫描（`ProbeCleanup.MINIO_PREFIXES` 的 `images/` 一行是
 * `S6-M05-C2` 补的 —— 补之前漏加过，一轮跑批留了 180 个孤儿）。
 * 所以跑完它之后核一次桶里干净，是对那行兜底的**端到端证据**。
 */
@Tag("network")
@TestPropertySource(properties = {
        "m05.image.describe.enabled=true",
        // 用生产默认值（4）而不是调大 —— 这条用例要验的是**生产形态**会怎样
        "m05.image.describe.concurrency=4",
        // 判据 9（描述进得了召回）**必须**开它：测试配置默认关着向量化
        // （同一条理由 —— 真调嵌入模型），关着的话 IMAGE 块的描述只是躺在
        // `sys_chunk.raw_content` 里，**根本没进 Milvus**，
        // 于是"能不能被召回"这件事无从谈起（`ChunkVectorizer` 会直接返回 DISABLED）。
        "m05.vectorize.enabled=true",
})
@DisplayName("真 VL：文档图片描述整条链（判据 4/5/8/9/13/14）")
class ImageDescribeNetworkTest extends ApiTestSupport {

    private static final Path SAMPLE_DIR = Path.of("..", "docs", "测试文件").toAbsolutePath().normalize();
    private static final String SAMPLE = "1-DeepSeek使用.pdf";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ParseWorker parseWorker;

    @Autowired
    private com.knowledge.ai.core.storage.DocStorage docStorage;

    @Autowired
    private ChatStreamService chatStreamService;

    /** 本用例建的探针库 —— 收尾要按它清。 */
    private final List<Long> probeKbIds = new ArrayList<>();

    /**
     * 判据 9 那条提问之前 `sys_chat_session` 的最大 id —— 之后新增的全是本轮造的。
     *
     * <p>⚠️ 会话与打点**没有 `kb_id` / `doc_id` 坐标**，{@code ProbeCleanup} 认不出它们
     * （`#130`：产物认识、归属维度不认识）。所以只能用水位线兜住自己这一批。
     * ⚠️ 收尾必须在**提问之前**就抓到它 —— 提问之后抓就是问完之后的水位，
     * 自己造的行一条也删不掉。
     *
     * <p>🔴 **初值是 {@code -1} 而不是 {@code 0}，而且判空用的是 {@code >= 0}** ——
     * 第一版写成 {@code 0} + {@code > 0}，当场漏了：基线是**空表**时
     * {@code coalesce(max(id), 0)} 本来就返回 0，于是"合法水位"与"还没抓"撞成同一个值，
     * 整段清理被静默跳过。实测留下一整个会话族：`session=1 · message=2 · trace=1 ·
     * trace_chunk=5`。**哨兵值不要用一个合法值去当** —— 这是那条的第二次踩。
     */
    private long chatWatermark = -1;

    /**
     * 收尾：删探针库那一族的行与对象。
     *
     * <h3>🔴 第一版漏了它，当场留了残留</h3>
     * <p>{@code ApiTestSupport} **不负责删探针库**（它只管无主对象的兜底扫描）——
     * 各用例类自己清，{@code DocParseFlowTest} 就是这么写的。
     * 我第一版没写，实测留下：`kb=10`（应为 8）· `doc=2` · `chunk=228` · `issue=128` ·
     * **34 个 MinIO 对象**。
     * <p>这正是 `#186` 那条教训（「**收尾必须是必走路径**」）在写完那条教训的同一个人手上的
     * 第二次重演 —— 所以这里照搬 `DocParseFlowTest` 那份**已经跑过很多轮**的版本，
     * 而不是自己另写一套。
     *
     * <h3>🔴 图片对象为什么必须**显式**删，不能指望兜底扫描</h3>
     * <p>曾经这里是这么写的：「图片对象由 `sys_chunk.object_key` 引用，把行删掉就成了无主对象，
     * `ApiTestSupport` 的收尾扫描（`T-O10`）会扫 `images/` 前缀 —— 只是它有 **10 分钟的年龄下限**
     * （那窗口是为"上传中"留的），所以本轮刚写的图要等下一轮。这是设计如此。」
     *
     * <p>**那句话是错的，而且错得很隐蔽** —— 它把一条**兜底机制**说成了**主路径**。
     * 兜底的那个窗口本来就是给"上传中"留的，它天然管不了"本轮刚写完就删行"这种情形；
     * 而本条用例**每一次都**是这个情形 ⇒ 每一轮都必然留一批，**永远**要等下一轮，
     * 而"下一轮"可能就是别人几小时后的全量跑批。实测：漏加这段的一版留 34 个，
     * 加上收尾但没加这段的一版留 26 个。
     *
     * <p>⇒ **兜底机制不能拿来当主路径的收尾**。判据是：这笔产物**这一轮就该清掉**的话，
     * 收尾就必须直接删它，而不是"它会在某轮之后被扫走"。
     * 复验：本段加上之后跑完，桶里 `images/` 目录**直接不存在**。
     */
    @org.junit.jupiter.api.AfterEach
    void cleanProbeData() {
        for (Long kbId : probeKbIds) {
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select storage_path, parse_path from sys_document where kb_id = ?", kbId)) {
                removeObject(row.get("storage_path"));
                removeObject(row.get("parse_path"));
            }
            // 🔴 **图片对象要显式删，不能靠兜底扫描**。
            //    兜底扫描（`T-O10`）有一条 **10 分钟的年龄下限**（那窗口是给"上传中"留的）
            //    ⇒ 本轮刚写的图**这一轮必然扫不掉**，要靠下一个含图跑批顺手带走。
            //    实测：第一版没写这段，一轮跑批后桶里留 26 个；写之前的那一版留了 34 个。
            //    ⚠️ 顺序不能反 —— 必须**在**删 `sys_chunk` 行之前取 object_key，
            //    行没了就再也查不到这批对象叫什么（`#136` 那个形状）。
            for (String objectKey : jdbcTemplate.queryForList(
                    "select object_key from sys_chunk where kb_id = ? and object_key is not null",
                    String.class, kbId)) {
                removeObject(objectKey);
            }
            // 按 kb_id 直删而不是 join sys_document：文档行可能已经不在了，
            // join 一条也匹配不到、孤儿照样留下
            jdbcTemplate.update("delete from sys_chunk where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_parse_issue where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_document where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb where id = ?", kbId);
        }
        probeKbIds.clear();

        // 判据 9 那条提问造的**问答族四张表** —— `ProbeCleanup` 按 kb_id/doc_id 认产物，
        // 而它们两个坐标都没有，只能按水位线兜。⚠️ 顺序不能反：明细按 trace_id 挂，
        // 主表先删就再也找不到那些明细了（`ChatStreamFlowTest` 记的同一条）
        if (chatWatermark >= 0) {
            jdbcTemplate.update("delete from sys_retrieval_trace_chunk where trace_id in "
                    + "(select trace_id from sys_retrieval_trace where session_id > ?)", chatWatermark);
            jdbcTemplate.update("delete from sys_retrieval_trace where session_id > ?", chatWatermark);
            jdbcTemplate.update("delete from sys_chat_message where session_id > ?", chatWatermark);
            jdbcTemplate.update("delete from sys_chat_session where id > ?", chatWatermark);
        }
    }

    /** 删对象存储里的文件。**静默**：删不掉不该让用例红（`DocStorage#remove` 自己吞并留 warn）。 */
    private void removeObject(Object objectKey) {
        if (objectKey != null && !objectKey.toString().isBlank()) {
            docStorage.remove(objectKey.toString());
        }
    }

    /**
     * 🔴 **读回来的对象必须是一张真图** —— 「写进去了」不等于「写对了」。
     *
     * <p>这条断言是**查一个具体故障时加的**，而且它本身就是本仓那条纪律的落点：
     * 上传接口返回成功、`sys_chunk.object_key` 有值，**都不能证明桶里的字节是图片**。
     * 实测踩过：把对象从 MinIO 的磁盘布局里抠出来时，看到的是一段
     * 「32 字节前缀 + 完整 JPEG」，而带那段前缀打 VL 端点会得到
     * `20015 image_url provided is not a valid image`。
     * ⚠️ 但那次是从**磁盘布局**读的，不是从客户端读的 —— 两者可能不是一回事
     * （MinIO 的 FS 后端有内部包装）。所以这里**用客户端读**，那才是权威口径
     * （同 `T-O10` 那条教训：**用与写入同一个工具去量**）。
     */
    private void assertStoredObjectIsRealJpeg(String objectKey) {
        byte[] bytes = docStorage.open(objectKey);
        String magic = bytes.length >= 3
                ? String.format("%02x%02x%02x", bytes[0], bytes[1], bytes[2]) : "?";
        System.out.println("回读对象 " + objectKey + " · " + bytes.length + " 字节 · magic=" + magic);
        assertThat(magic)
                .as("🔴 从 MinIO 读回的对象必须以 JPEG magic 开头（ffd8ff）—— "
                        + "否则「上传成功」只是接口层的成功，桶里的字节根本不是图片")
                .isEqualTo("ffd8ff");
    }

    @Test
    @DisplayName("上传含图 PDF → 真调 VL → 描述进块、三列元数据落库、image_parsed 有真值")
    void realVisionDescribesImagesAndFillsColumns() throws Exception {
        // 显式 opt-in：真花钱且分钟级，不该在默认跑批里发生
        Assumptions.assumeTrue(Boolean.getBoolean("vision.probe.run"),
                "需要显式开启：-Dvision.probe.run=true（真调付费 VL，耗时数分钟）");
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE_DIR.resolve(SAMPLE)),
                "缺样本 " + SAMPLE + "（docs/测试文件/ 不入库）");

        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] pdf = Files.readAllBytes(SAMPLE_DIR.resolve(SAMPLE));

        RawResponse uploaded = upload(kbId, pdf, "真VL探针.pdf", token).assertOk("上传含图 PDF");
        long docId = ((Number) uploaded.data("$.docId")).longValue();

        // ---------- 判据 13：image_parsed 有真值 ----------
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, image_total, image_parsed from sys_document where id = ?", docId);
        long imageTotal = ((Number) row.get("image_total")).longValue();
        long imageParsed = ((Number) row.get("image_parsed")).longValue();

        System.out.println("======== 真 VL 端到端 ========");
        System.out.println("status=" + row.get("status") + " · image_total=" + imageTotal
                + " · image_parsed=" + imageParsed);

        assertThat(imageTotal).as("该样本实测 75 个图元素").isGreaterThan(0);

        // ---------- 判据 10 的前置：IMAGE 块先查出来 ----------
        // ⚠️ 顺序是刻意的：下面那条**回读校验**要在 `image_parsed` 的断言**之前**跑。
        //    它是用来区分"库里的计数是 0"与"桶里的字节不对"的 —— 若它排在后面，
        //    `image_parsed > 0` 一红就再也走不到，而**恰恰是那种时候最需要它**。
        List<Map<String, Object>> imageChunks = jdbcTemplate.queryForList(
                "select id, order_no, raw_content, object_key, image_md5, vl_model_id "
                        + "from sys_chunk where doc_id = ? and chunk_type = 'IMAGE' order by order_no",
                docId);
        System.out.println("IMAGE 块 = " + imageChunks.size() + " 个");
        assertThat(imageChunks)
                .as("含图文档必须产出 IMAGE 块 —— 且**数量不为 0**")
                .isNotEmpty();
        String sampleKey = String.valueOf(imageChunks.getFirst().get("object_key"));
        assertStoredObjectIsRealJpeg(sampleKey);

        assertThat(imageParsed)
                .as("🔴 判据 13：image_parsed 必须 > 0 —— 此前它恒 0，是「采了不用」的那一列")
                .isGreaterThan(0);
        assertThat(imageParsed)
                .as("⚠️ 必须 ≤ 可提取数（26），**不是** image_total（75）—— "
                        + "75 里有 49 张没有可导出的位图（用户裁定 B：两个数都留）")
                .isLessThanOrEqualTo(26);
        assertThat(imageChunks)
                .as("描述了 %d 张 ⇒ IMAGE 块数应当不少于它（有些块只有题注、没被描述）", imageParsed)
                .hasSizeGreaterThanOrEqualTo((int) imageParsed);

        List<Map<String, Object>> described = imageChunks.stream()
                .filter(c -> c.get("vl_model_id") != null)
                .toList();
        System.out.println("带 vl_model_id 的 = " + described.size());
        assertThat(described)
                .as("判据 10：被描述过的块必须带 object_key / image_md5 / vl_model_id")
                .isNotEmpty()
                .allSatisfy(c -> {
                    assertThat(c.get("object_key")).as("object_key").isNotNull();
                    assertThat(c.get("image_md5")).as("image_md5").isNotNull();
                    assertThat(c.get("vl_model_id")).as("vl_model_id").isNotNull();
                });

        // ---------- 判据 4：题注优先 ----------
        // 题注从**解析产物**里取（那是不花 VL 的一跳），再拿去核对块内容的首行
        Set<String> captions = captionsOf(SAMPLE_DIR.resolve(SAMPLE));
        System.out.println("样本里能配上对的题注 = " + captions.size());
        List<Map<String, Object>> captioned = imageChunks.stream()
                .filter(c -> captions.stream()
                        .anyMatch(cap -> String.valueOf(c.get("raw_content")).startsWith(cap)))
                .toList();
        Assumptions.assumeTrue(!captioned.isEmpty(),
                "本样本里没有「既带题注、又被成功描述」的块，判据 4 这次没验到（下一份样本再试）");
        assertThat(captioned).allSatisfy(c -> {
            String content = String.valueOf(c.get("raw_content"));
            assertThat(content.lines().count())
                    .as("🔴 判据 4：题注块的内容必须**不止题注一行** —— 说明描述接在了它后面；"
                            + "内容=%s", content.substring(0, Math.min(60, content.length())))
                    .isGreaterThan(1L);
        });

        // ---------- 判据 5：图片是原子单元（一个图元素一个块，不被切分）----------
        assertThat(imageChunks.stream()
                .collect(Collectors.groupingBy(c -> c.get("order_no"), Collectors.counting())))
                .as("判据 5：图片**整体**一个原子单元（需求 06:166），order_no 不该重复")
                .allSatisfy((orderNo, count) -> assertThat(count).isEqualTo(1L));

        // ---------- 判据 6.5：抽不到位图的那批仍然可见 ----------
        Long notExtractable = jdbcTemplate.queryForObject(
                "select count(*) from sys_parse_issue where doc_id = ? and kind = 'IMAGE_NOT_EXTRACTABLE'",
                Long.class, docId);
        System.out.println("IMAGE_NOT_EXTRACTABLE issue = " + notExtractable);
        assertThat(notExtractable)
                .as("🔴 那 49 张必须**看得见**（用户裁定 B）—— 否则 image_parsed < image_total "
                        + "在页面上与「还没跑完」长得一模一样")
                .isNotNull().isGreaterThan(0);

        // ---------- 判据 9：图片描述进得了召回 ----------
        // 本批**最后一个未验判据**。它要的证据（IMAGE 块带着真描述进了 Milvus、且被搜得回来）
        // 正是上面那次真跑攒出来的，所以接在这里而不是另起一条用例 —— 另起就等于
        // 再花一遍约 5 分钟的真 VL 去造同一批 IMAGE 块。
        //
        // 走**真问答链路**（`ChatStreamService#chat`）而不是直接调 `RetrievalService#search`：
        // 判据原文要的证据落点是 `sys_retrieval_trace`，而那张表只由 `RetrievalTraceWriter`
        // 在问答收尾时写 —— 直接调检索服务的话那个对象只存在于返回值里，库里一行都没有，
        // 等于**只验了一半**。走问答链路还会顺带真跑一次生成，但那不影响本判据：
        // 打点写在 `finally` 里，模型挂了它也照落。
        //
        // ⚠️ 水位线必须在**提问之前**抓（见字段注释）。
        Long floor = jdbcTemplate.queryForObject(
                "select coalesce(max(id), 0) from sys_chat_session", Long.class);
        chatWatermark = floor == null ? 0L : floor;

        // 提问用**真实描述里的最长那一行**，不硬编码词语 ——
        // 描述是模型现生成的，写死任何一个词都会在下一次跑批里变成"库里没有这个词"。
        // 取"全局最长行"是为了尽量避开与正文重合的短句：命中的必须是描述本身带来的那部分信息
        String searchQuery = imageChunks.stream()
                .filter(c -> c.get("vl_model_id") != null)
                .map(c -> String.valueOf(c.get("raw_content")))
                .flatMap(content -> content.lines())
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .max(Comparator.comparingInt(String::length))
                .orElseThrow(() -> new AssertionError(
                        "一张都没被描述成功，判据 9 无从谈起（先看上面那 4 张为什么失败）"));
        if (searchQuery.length() > 80) {
            searchQuery = searchQuery.substring(0, 80);
        }
        System.out.println("======== 判据 9：拿描述里的词提问 ========");
        System.out.println("query = " + searchQuery);

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), new ChatDtos.ChatRequest(searchQuery, null, null, null, null, null), sink);
        System.out.println("问答事件序列 = " + sink.eventNames + " · 错误 = " + sink.lastError);

        Map<String, Object> trace = jdbcTemplate.queryForMap(
                "select trace_id, chunk_count, vector_hits, bm25_hits, rerank_applied "
                        + "from sys_retrieval_trace where session_id > ? order by id desc limit 1",
                chatWatermark);
        System.out.println("trace = " + trace);

        List<Map<String, Object>> hits = jdbcTemplate.queryForList(
                "select tc.rank_no, tc.chunk_id, c.chunk_type, c.doc_id "
                        + "from sys_retrieval_trace_chunk tc join sys_chunk c on c.id = tc.chunk_id "
                        + "where tc.trace_id = ? order by tc.rank_no", trace.get("trace_id"));
        System.out.println("打点明细 = " + hits);

        assertThat(hits)
                .as("🔴 判据 9：按描述里的词提问，必须命中那个 IMAGE 块 —— "
                        + "一条都是空的说明描述压根没进召回池")
                .isNotEmpty();
        assertThat(hits)
                .as("🔴 判据 9：命中里必须**有 IMAGE 块** —— 描述进了索引但排不上来，"
                        + "等于用户拿图里的内容问不出东西")
                .anySatisfy(h -> assertThat(h.get("chunk_type")).isEqualTo("IMAGE"));
        assertThat(hits)
                .as("命中必须是**本篇**的块（召回到了别人的文档说明可见性范围不对）")
                .allSatisfy(h -> assertThat(((Number) h.get("doc_id")).longValue()).isEqualTo(docId));

        System.out.println("=========================================");
    }

    /**
     * 只收事件、不回话的 {@link ChatSink}。
     *
     * <p>⚠️ 不共用 `ChatStreamFlowTest` 里那个 `RecordingSink`：它在那个用例类内部，
     * 且带着那边特有的断言辅助。本类只要三样东西 —— 事件名、错误文案、以及
     * "链路走到了 `done`"。重复的那点代码比引入一条跨用例类的依赖便宜。
     */
    private static final class RecordingSink implements ChatSink {

        private final List<String> eventNames = new ArrayList<>();
        private String lastError;

        @Override
        public void retrieval(ChatDtos.RetrievalEvent event) {
            eventNames.add("retrieval");
        }

        @Override
        public void delta(String content) {
            eventNames.add("delta");
        }

        @Override
        public void done(ChatDtos.DoneEvent event) {
            eventNames.add("done");
        }

        @Override
        public void error(String message) {
            eventNames.add("error");
            lastError = message;
        }
    }

    /**
     * 一个"看得见全部"的用户 —— 与 `RetrievalFlowSupport#reader` 同形。
     *
     * <p>⚠️ 这里**不能**只传 `LoginUser` 就完事：`DataScopeService#kbVisibility` 认的是
     * `sys_kb_grant` 授权行（它按 `user_id` 现查 `sys_user_role`），**不认 `superAdmin`**
     * 也不看 {@code roleCodes}。所以 userId=1 这个账号在库里真的持有 SUPER_ADMIN 角色，
     * 才是这条提问能看见探针库的原因。
     */
    private static LoginUser reader() {
        return LoginUser.builder()
                .userId(1L)
                .username("admin")
                .realName("探针")
                .deptId(1L)
                .superAdmin(true)
                .dataScope(DataScope.ALL)
                .secretLevelUpper(SecretLevel.SECRET)
                .build();
    }

    /** 从解析产物里取「能配上对的题注」文本集合（不花 VL）。 */
    private static Set<String> captionsOf(Path pdf) throws Exception {
        ParsedDocument doc = PdfDocumentParser.parse(pdf);
        return doc.elements().stream()
                .filter(e -> e.kind() == ParsedDocument.DocElement.Kind.IMAGE)
                .map(ParsedDocument.DocElement::imageInfo)
                .filter(java.util.Objects::nonNull)
                .filter(ImageInfo::hasCaption)
                .map(ImageInfo::caption)
                .collect(Collectors.toSet());
    }

    // ==================================================================
    // 与 DocParseFlowTest 同形的三个小助手
    //
    // ⚠️ `awaitTaskDone` / `createProbeKb` / `formWith` 在本仓已经有**三份**一字不差的拷贝
    //    （`DocParseFlowTest` / `DocUploadFlowTest` / `PageLevelRoutingFlowTest`），
    //    本类是**第四份**。没有顺手收进 `ApiTestSupport` 是**刻意的**：
    //    基类加一个 protected 的同名方法会与子类里的 private 方法**冲突**
    //    （"不能降低可见性"），收口必须**同时**删掉那三份 —— 那是一次独立的重构，
    //    不该塞进本批。已登记为遗留（见计划 §9）。
    // ==================================================================

    private long createProbeKb(String token) {
        // ⚠️ code 的形状有服务端校验：**必须以 `kb_` 开头、只允许小写字母数字下划线**
        //    （第一版写成 `m05c2probe…`，当场被 10030 挡下 —— 那条报错文案是可行动的，
        //     一眼就知道该怎么改）
        String code = "kb_m05c2probe" + Long.toString(System.nanoTime(), 36);
        RawResponse created = post("/api/kb", formWith(code), token).assertOk("建探针库");
        long kbId = ((Number) created.data("$.id")).longValue();
        probeKbIds.add(kbId);   // ← 收尾靠它（第一版漏了这一行）
        return kbId;
    }

    private RawResponse upload(long kbId, byte[] content, String filename, String token) {
        RawResponse accepted = postMultipart("/api/kb/" + kbId + "/doc/upload", content, filename,
                "application/octet-stream", token);
        if (accepted.code() != 0) {
            return accepted;
        }
        awaitTaskDone(accepted.data("$.taskId"), token);
        // 上传与解析是两个作业；测试期后台轮询关掉了，这里显式驱动一轮
        parseWorker.drainOnce();
        return accepted;
    }

    private void awaitTaskDone(String taskId, String token) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            RawResponse t = get("/api/doc/tasks/" + taskId, token).assertOk("轮询任务");
            Integer percent = t.data("$.percent");
            if (percent != null && percent >= 100) {
                return;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待任务完成时被中断：" + taskId, ex);
            }
        }
        throw new AssertionError("任务 120s 内未到终态：" + taskId);
    }

    private static Map<String, Object> formWith(String code) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "S6-C2 真VL探针库");
        form.put("code", code);
        form.put("deptId", 1);
        form.put("visibleRoles", List.of("SUPER_ADMIN", "USER"));
        form.put("secretLevel", "INTERNAL");
        form.put("businessDomain", "研发 / 平台");
        form.put("description", "");
        form.put("customTags", List.of());
        form.put("splitPolicy", Map.of("preset", "GENERAL", "chunkSize", 512, "overlap", 64,
                "recursiveSplit", true, "semanticSplit", false));
        form.put("tablePolicy", Map.of("atomicTable", true, "dualRepresentation", true,
                "repeatHeader", true, "mergeCrossPage", true, "ocrTable", true,
                "rowLimit", 30, "rowWindow", 5));
        form.put("retrievalConfig", Map.of("mode", "HYBRID", "recallThreshold", 0.15,
                "rerankTopK", 5, "rerankEnabled", true, "tableWeight", 1.2, "identifierProtect", true));
        return form;
    }
}
