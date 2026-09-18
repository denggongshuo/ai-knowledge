package com.knowledge.ai.pipeline.vision;

import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.service.ModelUsageRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VL 描述链路（`T-907`）—— **一次调用两产出 / 缓存 / 降级矩阵**，全替身无网络。
 *
 * <h3>本类验的是需求 `09` FR-SYS-04 的核心，且刻意不联网</h3>
 * <p>判据对应 §4.4（两产出）、§4.8（缓存 key 三段）、§4.10（降级矩阵）。
 * 替身收在 {@link FakeCaller} / {@link FakeCache} / {@link FakeRecorder} 三个手写类里
 * —— **不引 Mockito**：本仓既有用例一律手写替身，理由是替身的行为要能被读出来
 * （"这个用例到底喂进去了什么"），而 mock 的答案在断言里、不在正文里。
 *
 * <h3>为什么"返回空"与"调用失败"要分开测</h3>
 * <p>§4.10 把两者归为同一处置（都降级），但它们走的是**代码里的两条路**
 * （一个是正常返回后判空，一个是异常）。只测一条会漏掉另一条 ——
 * 而漏掉的那条一旦悄悄改成"抛出去"，整条问答就会被一次图片失败炸掉。
 */
class VisionDescriberTest {

    private FakeCaller caller;
    private FakeCache cache;
    private FakeRecorder recorder;
    private VisionDescriber describer;

    @BeforeEach
    void setUp() {
        caller = new FakeCaller();
        cache = new FakeCache();
        recorder = new FakeRecorder();
        // ImageCompressor 是真的：它是纯函数（无依赖、无 IO），用真的比替身更能反映实际
        describer = new VisionDescriber(caller, cache, new ImageCompressor(), recorder);
    }

    /** 构造输入：md5 同时出现在 URL 里，替身据此按图给答案（多图并行时仍是确定的）。 */
    private static VisionDescriber.VisionInput input(String md5) {
        return new VisionDescriber.VisionInput(md5, new byte[]{1, 2, 3},
                "http://minio/" + md5 + ".jpg", "image/jpeg", "这张图说了什么");
    }

    // ==================================================================
    // §4.4 一次调用两产出
    // ==================================================================

    @Test
    @DisplayName("§4.4 一次调用两产出：最后一行切成 searchSummary，其余是 desc")
    void splitsLastLineIntoSearchSummary() {
        caller.raw = """
                这是一张流程图，描述部署拓扑。
                节点有 A → B → C。
                关键词：部署, 拓扑, 节点, 流程""";

        VisionResult result = describer.describe(input("md5-1"));

        assertThat(result.degraded()).isFalse();
        assertThat(result.desc()).isEqualTo("这是一张流程图，描述部署拓扑。\n节点有 A → B → C。");
        assertThat(result.searchSummary()).isEqualTo("部署, 拓扑, 节点, 流程");
        // 「一次调用」的**负向锚**：整段只调了一次模型，不是"调两次分别取两产出"
        assertThat(caller.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("§4.4 不要求 JSON：输出包在代码块里也原样进 desc，不解析、不失败")
    void doesNotParseJson() {
        caller.raw = """
                ```json
                {"desc": "这是模型自作主张的 JSON"}
                ```
                关键词：json, 代码块""";

        VisionResult result = describer.describe(input("md5-2"));

        assertThat(result.degraded()).isFalse();
        // 代码块标记原样保留 —— 我们**没有**剥围栏，也没有把它当 JSON 解析
        assertThat(result.desc()).contains("```json").contains("模型自作主张");
        assertThat(result.searchSummary()).isEqualTo("json, 代码块");
    }

    @Test
    @DisplayName("§4.10 第 4 行：解析不出关键词行**不失败**，整段当 searchSummary")
    void missingKeywordLineFallsBackToWholeText() {
        caller.raw = "只有一段描述，没有关键词行。";

        VisionResult result = describer.describe(input("md5-3"));

        assertThat(result.degraded()).isFalse();
        assertThat(result.searchSummary()).isEqualTo("只有一段描述，没有关键词行。");
        assertThat(result.desc()).isEqualTo("只有一段描述，没有关键词行。");
    }

    @Test
    @DisplayName("正文里的冒号不会被误当关键词行（判据是'最后一行以关键词开头'）")
    void colonInBodyIsNotAKeywordLine() {
        caller.raw = "版本：v1.4\n错误码：E1001";

        VisionResult result = describer.describe(input("md5-4"));

        assertThat(result.degraded()).isFalse();
        assertThat(result.searchSummary()).isEqualTo("版本：v1.4\n错误码：E1001");
    }

    // ==================================================================
    // §4.8 缓存
    // ==================================================================

    @Test
    @DisplayName("§4.12 同图同模型命中缓存 —— 第二次不再调模型")
    void secondCallHitsCache() {
        caller.raw = "描述\n关键词：a, b";

        describer.describe(input("md5-5"));
        VisionResult second = describer.describe(input("md5-5"));

        assertThat(caller.callCount).isEqualTo(1);
        assertThat(second.degraded()).isFalse();
        assertThat(second.searchSummary()).isEqualTo("a, b");
    }

    @Test
    @DisplayName("§4.10 第 3 行：降级产出**不写缓存** —— 否则重试永远命中那份空描述")
    void degradedResultIsNotCached() {
        caller.failWith = new IllegalStateException("VL 网关 502");

        VisionResult first = describer.describe(input("md5-6"));
        VisionResult second = describer.describe(input("md5-6"));

        assertThat(first.degraded()).isTrue();
        assertThat(second.degraded()).isTrue();
        // **负向锚**：两次都真的调了模型。若降级被缓存，第二次会直接返回空描述
        assertThat(caller.callCount).isEqualTo(2);
    }

    // ==================================================================
    // §4.10 降级矩阵
    // ==================================================================

    @Test
    @DisplayName("§4.10 第 3 行：调用失败 ⇒ degraded=true，**不抛**（照常回答）")
    void callFailureDegradesInsteadOfThrowing() {
        caller.failWith = new RuntimeException("连接超时");

        VisionResult result = describer.describe(input("md5-7"));

        assertThat(result.degraded()).isTrue();
        assertThat(result.desc()).isEmpty();
        assertThat(result.hasDescription()).isFalse();
    }

    @Test
    @DisplayName("§4.10 第 3 行：返回空 ⇒ 同一条降级路径")
    void blankResponseDegrades() {
        caller.raw = "   ";

        VisionResult result = describer.describe(input("md5-8"));

        assertThat(result.degraded()).isTrue();
        assertThat(result.hasDescription()).isFalse();
    }

    @Test
    @DisplayName("VL 未配置 ⇒ 降级，且**一次模型都不调**、不查缓存")
    void unconfiguredVisionDegradesWithoutCalling() {
        caller.model = VisionCaller.VisionModelRef.NONE;

        VisionResult result = describer.describe(input("md5-9"));

        assertThat(result.degraded()).isTrue();
        assertThat(caller.callCount).isZero();
        assertThat(cache.getCount).isZero();
    }

    // ==================================================================
    // 多图并行（§4.3）
    // ==================================================================

    @Test
    @DisplayName("§4.3 多图逐张并行，返回与输入**等长同序**；任一张失败不影响其余")
    void describeAllKeepsOrderAndIsolatesFailures() {
        caller.answers.put("md5-a", "A 的描述\n关键词：甲");
        caller.answers.put("md5-c", "C 的描述\n关键词：丙");
        // md5-b 故意不给答案 ⇒ 替身抛异常 ⇒ 只有它降级

        List<VisionResult> results = describer.describeAll(
                List.of(input("md5-a"), input("md5-b"), input("md5-c")));

        assertThat(results).hasSize(3);
        assertThat(results.get(0).searchSummary()).isEqualTo("甲");
        assertThat(results.get(1).degraded()).isTrue();
        assertThat(results.get(2).searchSummary()).isEqualTo("丙");
    }

    // ==================================================================
    // 用量（T-905）
    // ==================================================================

    @Test
    @DisplayName("T-905 真的调用了就落一条 VL_DESC 用量（失败也记），缓存命中不记")
    void recordsUsageIncludingFailures() {
        caller.raw = "描述\n关键词：x";
        describer.describe(input("md5-d"));
        caller.failWith = new RuntimeException("502");
        describer.describe(input("md5-e"));

        assertThat(recorder.rows).hasSize(2);
        assertThat(recorder.rows).allSatisfy(row -> {
            assertThat(row.type()).isEqualTo(ModelType.VL);
            assertThat(row.purpose()).isEqualTo(ModelUsageRecorder.Purpose.VL_DESC);
        });
        // 缓存命中那次**不记** —— 没有请求发出去
        describer.describe(input("md5-d"));
        assertThat(recorder.rows).hasSize(2);
    }

    // ==================================================================
    // 替身
    // ==================================================================

    /**
     * 假 VL。
     *
     * <p>按**图片 URL 里的 md5** 取答案（多图并行时调用次序不确定，故不能用计数器）；
     * 没配答案的图就抛 —— 那正是"这一张理解失败"的形状。
     * {@link #raw} 是单图场景的默认答案。
     */
    private static final class FakeCaller implements VisionCaller {

        final Map<String, String> answers = new HashMap<>();
        String raw;
        RuntimeException failWith;
        int callCount;
        VisionModelRef model = new VisionModelRef("7", "Qwen/Qwen3-VL-8B-Instruct");

        @Override
        public VisionRaw call(String prompt, byte[] image, String presignedUrl, String mime) {
            callCount++;
            if (failWith != null) {
                throw failWith;
            }
            String key = keyOf(presignedUrl);
            if (key != null && answers.containsKey(key)) {
                return VisionRaw.of(answers.get(key));
            }
            if (!answers.isEmpty()) {
                throw new IllegalStateException("替身没有为这张图准备答案：" + key);
            }
            return VisionRaw.of(raw == null ? "" : raw);
        }

        private static String keyOf(String url) {
            if (url == null) {
                return null;
            }
            int slash = url.lastIndexOf('/');
            int dot = url.lastIndexOf('.');
            return slash < 0 || dot <= slash ? null : url.substring(slash + 1, dot);
        }

        @Override
        public VisionModelRef modelRef() {
            return model;
        }
    }

    /** 假缓存：只认 md5 的 HashMap（真实 key 的拼装由 {@link VisionDescriptionCacheTest} 验）。 */
    private static final class FakeCache extends VisionDescriptionCache {

        private final Map<String, VisionResult> store = new HashMap<>();
        int getCount;

        FakeCache() {
            super(null);
        }

        @Override
        public VisionResult get(String md5, String vlModelId) {
            getCount++;
            return store.get(md5 + ":" + vlModelId);
        }

        @Override
        public void put(String md5, String vlModelId, VisionResult result) {
            if (result != null && result.hasDescription()) {
                store.put(md5 + ":" + vlModelId, result);
            }
        }
    }

    /** 假用量落点：把行收进列表便于断言。 */
    private static final class FakeRecorder extends ModelUsageRecorder {

        final List<Row> rows = new ArrayList<>();

        FakeRecorder() {
            super(null);
        }

        @Override
        public void record(ModelType type, String modelId, String modelName, Purpose purpose,
                           int calls, long promptTokens, long completionTokens, Integer latencyMs) {
            rows.add(new Row(type, purpose, modelName, calls));
        }
    }

    /** 收下来的一行。 */
    private record Row(ModelType type, ModelUsageRecorder.Purpose purpose, String modelName, int calls) {
    }
}
