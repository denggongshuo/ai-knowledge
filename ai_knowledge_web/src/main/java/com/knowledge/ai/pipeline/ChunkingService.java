package com.knowledge.ai.pipeline;

import com.knowledge.ai.core.enums.BlockType;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 两级分片（`T-503`）—— 解析产物 → 块列表。
 *
 * <h3>两级的分工（《06》§1.4.2）</h3>
 * <table border="1">
 *   <tr><th>级</th><th>触发</th><th>边界</th><th>可关?</th></tr>
 *   <tr><td><b>第一级 · 结构边界</b></td><td>总是</td>
 *       <td>标题层级 H1→H3（<b>叶子节点即一个候选块</b>）· 表格整表封存 · 代码块 · 完整列表</td>
 *       <td>❌ <b>锁定，任何情况下不可关</b></td></tr>
 *   <tr><td><b>第二级 · 超长兜底</b></td><td>仅当结构单元 &gt; {@code chunkSize}</td>
 *       <td>递归分隔符（段落 → 换行 → 句号 → 分号 → 逗号 → 空格）</td><td>✅（默认开）</td></tr>
 * </table>
 *
 * <h3>「叶子节点即一个候选块」怎么落</h3>
 * <p>不是"每个标题出一个块"，而是"**每一节的正文合成一个块**"：
 * 连续正文段落在遇到下一个结构边界（标题 / 表格）之前一直累积，
 * 到边界处**合并着冲刷**成尽可能少的块。所以一节里的 10 个短段落
 * 若合计不到 {@code chunkSize}，产出的是**一个**块 —— 这正是"叶子节点即候选块"。
 *
 * <h3>语义切分为什么不在这里</h3>
 * <p>{@link ChunkingOptions#semanticSplit()} 开启时本类**显式失败**，不静默降级。
 * 理由：语义切分要句子级 embedding（`T-508` 的能力）且断点依赖相似度阈值
 * （《06》§1.4.2：「阈值动一点切出来的块全变，同一份文档两次索引结果不一致」）。
 * 静默用递归分隔符替代，等于让用户以为开了 —— 而本项目把「**静默降级视为缺陷**」
 * 写成了验收口径第一条（《02》）。所以宁可当场说"没实现"，也不假装。
 */
@Service
public class ChunkingService {

    /**
     * 第二级递归分隔符，**按优先级从粗到细**。
     *
     * <p>先按段落断（最自然），段内再按句号断，句子还长才按分号 / 逗号 / 空格断。
     * 这个顺序与《06》§1.4.2 的"段落 → 句号 → 分号 → 空格"一致，中间多一档换行与逗号
     * 是因为解析产物里硬换行很常见（PDF 的视觉断行），不先按它切会把一句话里的换行
     * 当成一个断点候选，切出来的块读起来是断的。
     */
    private static final List<String> SEPARATORS = List.of("\n\n", "\n", "。", "；", "，", " ");

    /**
     * 标题路径的层级连接符（D-5 §1.1 的示例 {@code 3. 接口设计 > 3.1 鉴权流程}）。
     *
     * <p>⚠️ 它是 {@code public} 的，因为**读侧也要用同一个字面量**：`T-602` 的目录树
     * 要从 {@code title_path} 的分段数推出标题层级（{@code DocDetailService#toc}）。
     * 那边若自己再写一次 {@code " > "}，两个常量就有分叉的一天 ——
     * 而分叉的表现是"目录树层级与检索侧过滤不一致"，很难查。
     */
    public static final String TITLE_PATH_SEPARATOR = " > ";

    /**
     * 把解析产物切成块。
     *
     * @param doc     解析产物（`T-502` 的输出，`elements` 是切分的唯一依据）
     * @param options 该知识库的分片参数
     * @throws ParseException 配置了尚未实现的语义切分（不静默降级）
     */
    public List<Block> chunk(ParsedDocument doc, ChunkingOptions options) {
        if (options.semanticSplit()) {
            throw new ParseException("该知识库启用了「语义切分」，但本版本尚未实现它"
                    + "（需要句子级嵌入能力）。请先在知识库的分片策略里关闭语义切分后重新上传 —— "
                    + "继续用「递归分隔符」兜底会让您以为开着的功能其实没生效。");
        }
        if (doc.elements().isEmpty() && doc.chars() > 0) {
            // `elements` 是 S4-M05-C1 步 0.6 新增的字段。在它之前落库的 parse.json 没有这一项，
            // Jackson 会给出空列表 —— 那种产物分片的结果是**0 个块**，而文档会显示「解析完成」。
            // 「跑了但什么都没产出」是本项目最不能接受的形态（《02》静默降级视为缺陷），
            // 所以这里当场失败并要求重解析，不静默产出空块集。
            throw new ParseException("解析产物缺少元素序列（可能是旧版本解析器生成的），无法分片；"
                    + "请重新解析该文档。若重解析后仍然如此，说明解析器没有产出结构信息，"
                    + "那是解析侧的缺陷，请反馈。");
        }

        List<Block> blocks = new ArrayList<>();
        // 结构单元：连续正文段落攒在这里，遇到下一个结构边界时冲刷
        List<PendingText> pending = new ArrayList<>();
        // TOC 栈：给出"当前这块属于哪一节"的 titlePath（标准栈操作，与 ParsedDocument#titlePathAt 同源）
        Deque<TocNode> toc = new ArrayDeque<>();

        for (ParsedDocument.DocElement el : doc.elements()) {
            switch (el.kind()) {
                case HEADING -> {
                    flushParagraphs(blocks, pending, titlePath(toc), options);
                    pushToc(toc, el.level(), el.text());
                    // 标题自身也是原子块（《06》§1.5：「标题、代码块、完整列表同为结构边界切出的原子块」）
                    blocks.add(new Block(el.text(), BlockType.HEADING, titlePath(toc),
                            nextOrderNo(blocks), el.pageNo(), null, null, null));
                }
                case PARAGRAPH -> pending.add(new PendingText(el.text(), el.pageNo()));
                case TABLE -> {
                    flushParagraphs(blocks, pending, titlePath(toc), options);
                    appendTable(blocks, doc, el, titlePath(toc));
                }
                // `T-512` 步 4：图片开始产出块。
                // ⚠️ **先冲刷**攒下的正文 —— 与 TABLE 同一个理由：`orderNo` 是按**产出顺序**
                // 递增的（`nextOrderNo`），不先冲刷的话，图前面那段的 orderNo 会排到图后面，
                // 回表与详情页的阅读顺序就错位了。
                case IMAGE -> {
                    flushParagraphs(blocks, pending, titlePath(toc), options);
                    appendImage(blocks, el, titlePath(toc));
                }
            }
        }
        flushParagraphs(blocks, pending, titlePath(toc), options);
        return List.copyOf(blocks);
    }

    // ==================================================================
    // 第一级：结构单元 → 块
    // ==================================================================

    /**
     * 把攒下的连续正文段落冲刷成块。
     *
     * <p>合并到 {@code chunkSize} 以内为止 —— 这就是"叶子节点即一个候选块"：
     * 一节里的短段落不各自成块，而是合成尽可能少的块。
     */
    private void flushParagraphs(List<Block> blocks, List<PendingText> pending,
                                 String titlePath, ChunkingOptions options) {
        if (pending.isEmpty()) {
            return;
        }
        String text = String.join("\n\n", pending.stream().map(PendingText::text).toList());
        Integer pageNo = pending.getFirst().pageNo();
        pending.clear();

        for (String piece : splitForFallback(text, options)) {
            blocks.add(new Block(piece, BlockType.PARAGRAPH, titlePath,
                    nextOrderNo(blocks), pageNo, null, null, null));
        }
    }

    /**
     * 一张图 → **恰好一个** `IMAGE` 块（`T-512` 步 4）。
     *
     * <h3>需求 `06:166` 的「原子单元」怎么落</h3>
     * <p>「图片**整体**作为一个原子单元（**不做图像切分**）」⇒ 这里**不调用**
     * {@link #splitForFallback}，块数恒为 1，**不随 {@code chunkSize} 变**。
     * 一张图的题注 + 描述远够不上一条分片，为它跑切分是纯粹的机器噪音。
     *
     * <h3>`rawContent` 此刻只有题注</h3>
     * <p>VL 描述要等**锁外**那一步（步 5）才有 —— 这里若先塞个占位，
     * 步 5 就得区分"占位"与"真描述"，而那个区分迟早会错。空着更诚实。
     *
     * <h3>没字节又没题注的图**不产出块**</h3>
     * <p>它们已经在解析期登记为 {@code IMAGE_NOT_EXTRACTABLE}（步 6.5），**已经可见**。
     * 再给它们各发一个空块，等于往索引里灌一批嵌入为空的向量 ——
     * 检索命中它只会浪费一次召回（`rawContent` 是空串，既是"块"又没有任何东西可检索）。
     * 「看得见」与「进索引」是两件事，这里刻意只做前者。
     */
    private void appendImage(List<Block> blocks, ParsedDocument.DocElement el, String titlePath) {
        ImageInfo info = el.imageInfo();
        String caption = info == null || !info.hasCaption() ? "" : info.caption();
        boolean hasBytes = info != null && info.hasBytes();
        if (!hasBytes && caption.isEmpty()) {
            return;
        }
        blocks.add(new Block(caption, BlockType.IMAGE, titlePath,
                nextOrderNo(blocks), el.pageNo(), null, null, info));
    }

    /** 一张表 → 父块（Markdown，不进检索）+ N 个子块（行式文本，进检索）。 */
    private void appendTable(List<Block> blocks, ParsedDocument doc,
                             ParsedDocument.DocElement el, String titlePath) {
        if (el.tableIndex() < 0 || el.tableIndex() >= doc.tables().size()) {
            // 解析产物自相矛盾（元素指向不存在的表）—— 不静默跳过，
            // 否则"少了一张表"会没有任何痕迹
            throw new ParseException("解析产物损坏：第 " + el.pageNo() + " 页的表格元素指向不存在的表 #"
                    + el.tableIndex() + "，请重新解析该文档");
        }
        ParsedDocument.ParsedTable table = doc.tables().get(el.tableIndex());
        if (table.layoutArtifact()) {
            // 与 ParsedDocument#tableTotal() 同一口径：1×1 空表是版式分隔线被误判，不是表格。
            // 在这里滤掉而不是在渲染器里，是为了让"什么算一张表"只有一处口径。
            return;
        }

        TableRenderer.RenderedTable rendered = TableRenderer.render(table);
        if (rendered.isEmpty()) {
            return;
        }

        int parentOrder = nextOrderNo(blocks);
        blocks.add(new Block(rendered.markdown(), BlockType.TABLE, titlePath,
                parentOrder, el.pageNo(), null, null, null));
        // I-4 的前提：父块整表封存、**不被切分**（所以这里不调用 splitForFallback）。
        // 子块逐行产出，每行 = 一条可独立检索的自然语言。
        for (String row : rendered.linearRows()) {
            blocks.add(new Block(row, BlockType.TABLE, titlePath,
                    nextOrderNo(blocks), el.pageNo(), parentOrder, null, null));
        }
    }

    // ==================================================================
    // 第二级：超长兜底（递归分隔符）
    // ==================================================================

    /**
     * 结构单元超过 {@code chunkSize} 时按递归分隔符切分；否则原样返回一个元素。
     *
     * <p>overlap 只在**这一级**切出来的相邻片之间生效 —— 结构边界切出来的块之间不重叠，
     * 那是两节内容，重叠会让检索命中串节。
     */
    private static List<String> splitForFallback(String text, ChunkingOptions options) {
        if (ChunkingOptions.estimateTokens(text) <= options.chunkSize()) {
            return List.of(text);
        }
        List<String> units = splitUnits(text, 0, options);
        // 合并目标取 chunkSize - overlap：叠加重叠部分之后仍不超过 chunkSize，
        // 否则"窗口 512"的库实际会切出 576 的块，与配置对不上。
        int target = options.chunkSize() - options.overlap();
        List<String> merged = mergeUnits(units, target, options.chunkSize());
        return applyOverlap(merged, options);
    }

    /**
     * 递归拆成"原子片"：在某一档分隔符上切开，切不动就换更细的一档。
     *
     * <p>返回的片**可能仍超长**（走到最细的分隔符也没切开，如一段没有标点的长串）：
     * 那属于"切不动"，交给 {@link #mergeUnits} 里的硬切兜底 ——
     * 硬切是最后手段，因为它在语义上一定切坏了。
     */
    private static List<String> splitUnits(String text, int sepIndex, ChunkingOptions options) {
        if (ChunkingOptions.estimateTokens(text) <= options.chunkSize()
                || sepIndex >= SEPARATORS.size()) {
            return List.of(text);
        }
        List<String> parts = splitKeepingSeparator(text, SEPARATORS.get(sepIndex));
        if (parts.size() <= 1) {
            return splitUnits(text, sepIndex + 1, options);
        }
        List<String> units = new ArrayList<>();
        for (String p : parts) {
            units.addAll(splitUnits(p, sepIndex + 1, options));
        }
        return units;
    }

    /**
     * 把原子片按顺序合并到 {@code target} 以内。
     *
     * <p>单个片本身就超 {@code hardLimit} 时**先冲刷缓冲再硬切** —— 不硬切的话
     * 那个片会原样变成一个超长块，`chunkSize` 就形同虚设。
     */
    private static List<String> mergeUnits(List<String> units, int target, int hardLimit) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String unit : units) {
            if (ChunkingOptions.estimateTokens(unit) > hardLimit) {
                if (!buf.isEmpty()) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                out.addAll(hardSplit(unit, hardLimit));
                continue;
            }
            if (!buf.isEmpty()
                    && ChunkingOptions.estimateTokens(buf.toString())
                    + ChunkingOptions.estimateTokens(unit) > target) {
                out.add(buf.toString());
                buf.setLength(0);
            }
            buf.append(unit);
        }
        if (!buf.isEmpty()) {
            out.add(buf.toString());
        }
        return out;
    }

    /** 连最细的分隔符都切不开时的最后手段：按字符硬切。返回的片保证不超 `limit`。 */
    private static List<String> hardSplit(String text, int limit) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            double t = ChunkingOptions.estimateTokens(String.valueOf(c));
            if (tokens + t > limit && !buf.isEmpty()) {
                out.add(buf.toString());
                buf.setLength(0);
                tokens = 0;
            }
            buf.append(c);
            tokens += t;
        }
        if (!buf.isEmpty()) {
            out.add(buf.toString());
        }
        return out;
    }

    /**
     * 让每个片"复述"前一片的尾部 {@code overlap} 个 token。
     *
     * <p>这是重叠的**唯一作用**：让跨越切点的句子在后一片里也出现一次，
     * 检索命中后半句时仍能取到完整语义。
     */
    private static List<String> applyOverlap(List<String> chunks, ChunkingOptions options) {
        if (options.overlap() <= 0 || chunks.size() <= 1) {
            return chunks;
        }
        List<String> out = new ArrayList<>();
        out.add(chunks.getFirst());
        for (int i = 1; i < chunks.size(); i++) {
            String tail = tailByTokens(chunks.get(i - 1), options.overlap());
            out.add(tail.isEmpty() ? chunks.get(i) : tail + chunks.get(i));
        }
        return List.copyOf(out);
    }

    /** 取尾部约 {@code tokens} 个 token 的文本（与 {@link ChunkingOptions#estimateTokens} 同一口径）。 */
    private static String tailByTokens(String text, int tokens) {
        int cut = text.length();
        double acc = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            acc += ChunkingOptions.estimateTokens(String.valueOf(text.charAt(i)));
            if (acc > tokens) {
                break;
            }
            cut = i;
        }
        return text.substring(cut);
    }

    /**
     * 在分隔符处切开，**分隔符保留在前一片末尾**。
     *
     * <p>丢掉分隔符会让「第一句。」变成「第一句」—— 句号是语义的一部分，
     * 也是下一级递归的起点；而且 markdown 的换行丢掉后列表会粘成一团。
     */
    private static List<String> splitKeepingSeparator(String text, String separator) {
        if (separator.isEmpty()) {
            return List.of(text);
        }
        List<String> out = new ArrayList<>();
        int from = 0;
        int at;
        while ((at = text.indexOf(separator, from)) >= 0) {
            out.add(text.substring(from, at + separator.length()));
            from = at + separator.length();
        }
        if (from < text.length()) {
            out.add(text.substring(from));
        }
        return out.isEmpty() ? List.of(text) : out;
    }

    // ==================================================================
    // TOC 栈
    // ==================================================================

    /** 更新标题栈：弹掉层级 >= 新标题的那些（标准 TOC 栈操作）。 */
    private static void pushToc(Deque<TocNode> toc, int level, String text) {
        while (!toc.isEmpty() && toc.peek().level() >= level) {
            toc.pop();
        }
        toc.push(new TocNode(level, text));
    }

    /** 当前标题路径，形如 {@code 3. 接口设计 > 3.1 鉴权流程}；栈空时是空串。 */
    private static String titlePath(Deque<TocNode> toc) {
        if (toc.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        // Deque 的迭代顺序是"栈顶→栈底"，路径要反过来（从最外层标题开始）
        for (TocNode node : toc) {
            parts.add(node.text());
        }
        return String.join(TITLE_PATH_SEPARATOR, parts.reversed());
    }

    /**
     * 序号 = 当前块数 + 1（**1-based**）。
     *
     * <p>序号由 {@link Block#orderNo} 承载，子块的 {@code parentOrderNo} 直接指向父块的序号 ——
     * 落库时再换成父块的真实自增 id（见 {@code T-505} 的写入层）。
     */
    private static int nextOrderNo(List<Block> blocks) {
        return blocks.size() + 1;
    }

    private record TocNode(int level, String text) {
    }

    private record PendingText(String text, Integer pageNo) {
    }
}
