package com.knowledge.ai.pipeline.enhance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.core.text.IdentifierTokenizer;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 索引增强（`T-507` · `D-5` §1.6 的**文档级**那一次调用）。
 *
 * <h3>位置：分片之后、向量化之前</h3>
 * <pre>
 *   解析 → 清洗（O3） → 分片（T-503/504/505） → 【增强】 → 落 sys_chunk → 向量化（T-508）
 * </pre>
 * <p>它**吃分片后的块集、吐加了摘要块的块集**。之所以不塞进
 * {@link com.knowledge.ai.pipeline.ChunkingService}：那位是**纯函数**，
 * 有 18 条不联网的用例钉着（`chunkSize` 边界、父子块、无内容丢失）。
 * 把一次网络调用放进去，那些用例要么变慢要么变不稳 —— 而它们保的是分片的正确性，
 * 与"能不能调到模型"无关。**纯函数保持纯**，编排留在外面。
 *
 * <h3>`I-10`：文档级一次调用产出三样东西</h3>
 * <p>摘要 + 内容标签 + 实体关系**共享同一上下文**（`D-5` §1.6 的原话：
 * "三者共享同一上下文，边际成本 ≈ 0"）。分三次调用是三重成本与三重失败点，
 * 而且三份"对文档的理解"还会互相不一致。
 * <p>本批只有**摘要**有落点（{@code SUMMARY} 块）；标签与关系**没有列可存** ——
 * 关系的去向是 Neo4j（`T-C00`），标签至今没有归属表。它们仍然被解析出来并随结果返回，
 * 因为这正是 `I-10` 要证明的事，而且 `T-C00` 落地时不必再加一次调用。
 *
 * <h3>⚠️ 本批**不做** HyQ（分片级那 n 次调用）—— 2026-09-16 用户裁定推到 S5</h3>
 * <p>理由与装饰图判定同构：它的全部价值在**检索侧**，而检索侧不存在（`T-700` 属 S5）。
 * 更具体的一层：`D-5` **没有给 HyQ 块任何回指源块的字段**，而 `G4-6` 的 SQL 断言
 * （已收口）要求"凡有 `parent_id` 的块，父块必须是 `TABLE`" ——
 * 于是"把 HyQ 块挂到它的源块下面"这条最自然的路是封死的。
 * 命中一个 HyQ 块之后拿什么给大模型，这个问题只能在有检索实现的时候回答。
 *
 * <h3>失败不牵连文档</h3>
 * <p>模型不可用 / 输出不成 JSON → **文档照样成功**，只是没有摘要块。
 * 正文已经切好、清洗过、能检索了；因为"摘要没生成"把整篇判失败，
 * 等于用一件增强功能的故障去否掉一篇可用的文档。
 * <p>但**不静默**：失败原因随 {@link Result} 返回、由调用方记日志
 * （`G4-3` 的口径：不许只说"失败"两字）。⚠️ 目前**没有面向用户的可见信号**
 * —— 已登记为遗留，见计划 §9。
 */
@Slf4j
@Service
public class IndexEnhancer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 提示词里正文的 token 预算（`windowTokens` 口径）。 */
    private static final int INPUT_TOKEN_BUDGET = 3000;

    private final ChatCaller chatCaller;

    /**
     * 增强总开关。
     *
     * <p><b>生产默认开</b>（`G4-9` 要求"存在 `SUMMARY` 类型分片"，默认关等于这条永远不成立），
     * 但**测试期默认关**（`src/test/resources/application.properties`）——
     * 与本仓既有的 `app.audit.retention-cron=-` 同一条理由：一处真实副作用会让
     * 「跑批结果取决于外部状态」。开着的话，`DocParseFlowTest` 里那二十来次上传
     * 每次都要真打一次模型，而本仓 surefire 没有 `excludedGroups`。
     *
     * <p>⚠️ 做成**构造器参数**而不是 {@code @Value} 字段：字段注入的话，
     * 单元测试要验"关掉之后一次都不打模型"就得靠反射塞值 ——
     * 而那正是最该被正常构造路径覆盖的一条分支。
     */
    private final boolean enabled;

    public IndexEnhancer(ChatCaller chatCaller,
                         @Value("${m05.enhance.enabled:true}") boolean enabled) {
        this.chatCaller = chatCaller;
        this.enabled = enabled;
    }

    /** 调用结果的性质 —— 调用方据此决定日志，测试据此断言"哪一种失败"。 */
    public enum Status {
        /** 开关关掉了（测试期 / 运维临时关）。 */
        DISABLED,
        /** 文档没有可送的内容（清洗后一篇空白）—— 不是失败。 */
        EMPTY_INPUT,
        /** 拿到了可用的摘要。 */
        OK,
        /** 模型调用本身炸了（网络 / 密钥 / 限流）。 */
        MODEL_FAILED,
        /** 拿到文本但解析不出约定的 JSON 结构。 */
        BAD_OUTPUT,
    }

    /**
     * 增强产物。
     *
     * @param blocks      加了 `SUMMARY` 块之后的块集；失败/关闭时**原样返回入参的块集**
     * @param status      这次尝试的性质
     * @param detail      失败原因（可读，进日志）；成功时是摘要的一句话概述
     * @param enhancement 解析出的三样东西；未成功时为 {@code null}
     */
    public record Result(List<Block> blocks, Status status, String detail, Enhancement enhancement) {

        /** 摘要块是否真的产出并进了块集。 */
        public boolean summaryProduced() {
            return status == Status.OK;
        }
    }

    /**
     * 文档级增强产物（`I-10` 的三样）。
     *
     * @param summary   文档摘要（非空）
     * @param labels    内容标签；模型没给就是空列表（**不是 null**）
     * @param relations 实体关系；模型没给就是空列表。落点见类 javadoc（`T-C00`）
     */
    public record Enhancement(String summary, List<String> labels, List<Relation> relations) {

        public Enhancement {
            labels = labels == null ? List.of() : List.copyOf(labels);
            relations = relations == null ? List.of() : List.copyOf(relations);
        }

        public record Relation(String subject, String predicate, String object) {
        }
    }

    // ==================================================================

    /**
     * 给块集加上文档级摘要块。
     *
     * @param doc    解析产物（**清洗后**的 —— 摘要必须基于进了索引的那份正文，
     *               否则摘要里会混进页眉页脚）
     * @param blocks 分片产出的块集
     * @return 见 {@link Result}
     */
    public Result enhance(ParsedDocument doc, List<Block> blocks) {
        if (!enabled) {
            return new Result(blocks, Status.DISABLED, "增强开关未开启（m05.enhance.enabled=false）", null);
        }

        Content content = documentContent(doc, INPUT_TOKEN_BUDGET);
        if (content.text().isBlank()) {
            // 清洗把整篇剥空了（或本来就是空文档）—— 没有可摘的内容，不是失败
            return new Result(blocks, Status.EMPTY_INPUT, "清洗后的正文为空，没有可摘要的内容", null);
        }

        String prompt = EnhancePrompts.documentLevel(content.text(), content.truncated());
        String raw;
        try {
            raw = chatCaller.call(prompt);
        } catch (Exception ex) {
            // 模型侧的异常一律不往上抛：摘要失败不该让一篇可用的文档失败
            String reason = "模型调用失败：" + readable(ex);
            log.warn("文档级增强失败（文档仍将正常入库）：{}", reason);
            return new Result(blocks, Status.MODEL_FAILED, reason, null);
        }

        Enhancement enhancement;
        try {
            enhancement = parse(raw);
        } catch (Exception ex) {
            String reason = "模型输出不是约定的 JSON 结构：" + readable(ex)
                    + "；原始输出前 200 字：" + clip(raw, 200);
            log.warn("文档级增强输出无法解析（文档仍将正常入库）：{}", reason);
            return new Result(blocks, Status.BAD_OUTPUT, reason, null);
        }

        List<Block> withSummary = new ArrayList<>(blocks);
        withSummary.add(new Block(enhancement.summary(), BlockType.SUMMARY, "",
                withSummary.size() + 1, null, null));

        return new Result(List.copyOf(withSummary), Status.OK,
                "摘要 " + enhancement.summary().length() + " 字 · 标签 " + enhancement.labels().size()
                        + " 个 · 关系 " + enhancement.relations().size() + " 条（提示词 "
                        + EnhancePrompts.VERSION + "）",
                enhancement);
    }

    // ==================================================================
    // 组装：正文 → 提示词输入
    // ==================================================================

    /**
     * 文档正文（**清洗后**）→ 送进提示词的文本，按 token 预算截断。
     *
     * <h3>为什么按预算截断而不是"全送"</h3>
     * <p>一篇 20 页的文档正文轻松上万 token，全送会撞模型窗口（直接调用失败）
     * 或者贵得没必要。摘要是**文档级的一句话**，它不需要逐字读完每个表格单元格。
     *
     * <h3>为什么截断了要告诉模型</h3>
     * <p>见 {@link EnhancePrompts#documentLevel} —— 不说的话模型会照常写出一份
     * 看起来很完整的摘要，而它其实只看到前半篇。**这是"少了一点东西而没人发现"的典型**。
     *
     * <p>⚠️ 本期是**从头截断**（保开头），因为开头通常含标题与范围说明。
     * 更好的做法是"抽头 + 抽尾 + 各节首段"这类分层采样，但那是 `O5`（质量基线）
     * 拿实测说话的地方 —— 在没有基线之前调采样策略等于瞎调（与"表格不单独开召回通道"
     * 同一条判断）。
     */
    private static Content documentContent(ParsedDocument doc, int budget) {
        StringBuilder sb = new StringBuilder();
        double used = 0;
        boolean truncated = false;

        for (ParsedDocument.DocElement el : doc.elements()) {
            String piece = pieceOf(doc, el);
            if (piece == null || piece.isBlank()) {
                continue;
            }
            double cost = IdentifierTokenizer.windowTokens(piece);
            if (used + cost > budget) {
                truncated = true;
                // ⚠️ 一个字都还没装下就超预算时，**必须砍一段进来**，不能就这么 break。
                // 否则"第一个元素本身就超预算"的文档会得到空文本 → 判 EMPTY_INPUT →
                // **永远没有摘要**。而"一个超长段落"恰恰是最需要摘要的那种文档。
                // （这条是被 truncationIsDisclosedToTheModel 抓出来的：
                //   当时的实现直接 break，于是替身模型一次都没被调用。）
                if (sb.isEmpty()) {
                    sb.append(prefixWithinBudget(piece, budget));
                }
                break;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(piece);
            used += cost;
        }
        return new Content(sb.toString(), truncated);
    }

    /**
     * 取不超过预算的最长前缀（二分）。
     *
     * <p>{@code windowTokens} 对长度单调不减（截短一个 token 只会让它更便宜或不变），
     * 所以二分成立。返回值可能为空串 —— 那是"预算小到连一个字符都装不下"，
     * 属于配置错误而非本方法的责任。
     */
    private static String prefixWithinBudget(String piece, int budget) {
        int lo = 0;
        int hi = piece.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (IdentifierTokenizer.windowTokens(piece.substring(0, mid)) <= budget) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return piece.substring(0, lo);
    }

    /** 元素 → 文本。表格用它的 Markdown 表示（**给大模型读的那一份**，`T-504` 的口径）。 */
    private static String pieceOf(ParsedDocument doc, ParsedDocument.DocElement el) {
        return switch (el.kind()) {
            case HEADING -> "#".repeat(Math.max(1, Math.min(6, el.level()))) + " " + el.text();
            case PARAGRAPH -> el.text();
            case TABLE -> el.tableIndex() >= 0 && el.tableIndex() < doc.tables().size()
                    ? doc.tables().get(el.tableIndex()).toMarkdown() : null;
            // 图片没有可送的文本（VL 描述属 T-512/S6）—— 不占预算，也不产出空行
            case IMAGE -> null;
        };
    }

    private record Content(String text, boolean truncated) {
    }

    // ==================================================================
    // 解析：模型输出 → Enhancement
    // ==================================================================

    /**
     * 解析模型输出。
     *
     * <h3>为什么解析要"宽松"</h3>
     * <p>提示词已经要求"只输出 JSON"，但**要求不等于保证**：模型经常还是会包一层
     * ``` 围栏，或者前面加一句"好的，以下是分析结果："。这些都不影响内容本身可用，
     * 为此判失败是把提示词工程的问题升级成功能故障。
     *
     * <p>宽松的边界划在**结构**上：剥掉围栏、取第一个 `{` 到最后一个 `}`，
     * 剩下的必须能被 Jackson 解析成对象且 `summary` 非空 —— 那之后就不猜了。
     *
     * @throws IllegalArgumentException 结构不对（拿不到 JSON 对象 / summary 为空）
     */
    static Enhancement parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("模型返回空文本");
        }
        String json = stripFences(raw.strip());
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("输出里找不到 JSON 对象");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(json.substring(start, end + 1));
        } catch (Exception ex) {
            throw new IllegalArgumentException("JSON 解析失败：" + readable(ex), ex);
        }

        String summary = clip(root.path("summary").asText("").strip(), EnhancePrompts.MAX_SUMMARY_CHARS);
        if (summary.isEmpty()) {
            // summary 是这三样里唯一的必需项：另两样给空数组是可接受的，摘要空则整次调用白费
            throw new IllegalArgumentException("JSON 里没有非空的 summary");
        }

        List<String> labels = new ArrayList<>();
        for (JsonNode label : root.path("labels")) {
            String v = label.asText("").strip();
            if (!v.isEmpty() && labels.size() < EnhancePrompts.MAX_LABELS) {
                labels.add(v);
            }
        }

        List<Enhancement.Relation> relations = new ArrayList<>();
        for (JsonNode rel : root.path("relations")) {
            String s = rel.path("subject").asText("").strip();
            String p = rel.path("predicate").asText("").strip();
            String o = rel.path("object").asText("").strip();
            // 三要素缺一不可：缺了的关系进图库就是一条断边
            if (!s.isEmpty() && !p.isEmpty() && !o.isEmpty()
                    && relations.size() < EnhancePrompts.MAX_RELATIONS) {
                relations.add(new Enhancement.Relation(s, p, o));
            }
        }

        return new Enhancement(summary, labels, relations);
    }

    /** 剥掉 ```json ... ``` 围栏（模型最常见的"不听话"形态，剥掉即可，不值得判失败）。 */
    private static String stripFences(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String body = text.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        return closing < 0 ? body : body.substring(0, closing);
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String v = text.strip();
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private static String readable(Exception ex) {
        String m = ex.getMessage();
        return m == null || m.isBlank() ? ex.getClass().getSimpleName() : clip(m, 300);
    }
}
