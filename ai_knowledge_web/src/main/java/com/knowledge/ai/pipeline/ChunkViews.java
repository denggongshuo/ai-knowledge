package com.knowledge.ai.pipeline;

import com.knowledge.ai.core.enums.ChunkView;

/**
 * 块的**视图投影**（`O8` 步 0）—— 全是纯函数，无状态、无 IO。
 *
 * <h3>为什么投影不写在 {@link ChunkView} 里</h3>
 * <p>{@code ChunkView} 在 {@code core/enums}，是纯值类型。投影要知道 {@link Block} 的形状，
 * 写在枚举里会让 {@code core} 反向依赖 {@code pipeline}，层次就倒了。
 *
 * <h3>本类是"只重建索引、不重解析"这句话的落点</h3>
 * <p>块落库后 {@code rawContent} 不再变。用户改「标题路径注入」开关时，变的只是
 * 这里被调用的次数与参数 —— **不触发 `parseDocument`**（`O8` 判据 4）。
 *
 * @see ChunkView
 * @see Block#views()
 */
public final class ChunkViews {

    /**
     * 标题路径与原文之间的分隔符。
     *
     * <p><b>冻结在这里</b>：改它 = 改所有嵌入文本 = 必须重建索引（所以它属于"索引期配置"，
     * 不属于"块"）。取换行而不是 {@code " > "} 之类的行内分隔符，是为了让标题路径
     * 在嵌入模型眼里**独占一行**，不与正文首句混成一个 n-gram。
     */
    public static final String TITLE_PATH_SEPARATOR = "\n";

    private ChunkViews() {
    }

    /**
     * 把块投影成某个视图的文本。
     *
     * <p>**这是 `O8` 的核心一跳**：块的 {@code rawContent} 永不改写，投影结果只送给嵌入模型。
     *
     * <p>⚠️ 调用方要保证 {@code view} 确实适用于该块（用 {@link Block#views()} 判断）——
     * 本方法不做适用性校验，因为"给正文块要表格视图"是**调用方的逻辑错误**，
     * 不该被静默兜底成一个看似合理的值。
     *
     * @throws UnsupportedOperationException 视图是预留的（**当前已无** —— 每个枚举值都有实现）
     */
    public static String project(Block block, ChunkView view) {
        return switch (view) {
            case RAW -> block.rawContent();
            case WITH_TITLE_PATH -> withTitlePath(block);
            // 表格的两份内容在分片阶段就已各自算好（TableRenderer 产出），
            // 这里"投影"就是取它本身 —— 之所以仍走投影，是为了让它与正文块**同一条路**，
            // 而不是在向量化那一层再长出一个 `if (blockType == TABLE)` 的分支。
            case TABLE_MARKDOWN, TABLE_LINEAR -> block.rawContent();
            // `T-512` 步 6：图片块的 rawContent 在**锁外描述**那一步（步 5）就已经拼好
            // （题注 + 换行 + VL 描述），所以这里与表格同形 —— **取它本身**。
            //
            // 🔑 这一步兑现的是 `ChunkView` 类注释里那句承诺：「接 VL 时**只加实现、不改管线**」。
            //    此前它只是一句**承诺**（本分支抛异常）；现在它是**事实** ——
            //    `ChunkingService`、`ChunkVectorizer`、落库、前端一行都没为它改过。
            case IMAGE_DESC -> block.rawContent();
        };
    }

    /**
     * 正文块的默认视图文本 = {@code titlePath + 分隔符 + rawContent}。
     *
     * <p>标题路径为空（标题之前的块，如封面段）时**退化为原文** —— 不拼一个空前缀
     * 再补个分隔符，那会在嵌入文本开头留一个孤零零的换行。
     */
    public static String withTitlePath(Block block) {
        String path = block.titlePath();
        if (path == null || path.isBlank()) {
            return block.rawContent();
        }
        return path + TITLE_PATH_SEPARATOR + block.rawContent();
    }

    /**
     * 该块**默认**进向量检索池的文本。
     *
     * <p>默认口径（= `O8` 落地前的既有行为，逐条等价）：
     * <ul>
     *   <li>正文块 → {@link ChunkView#WITH_TITLE_PATH}（《07》§附「标题路径注入」的意图）；</li>
     *   <li>表格子块 → {@link ChunkView#TABLE_LINEAR}；</li>
     *   <li>表格父块 → **{@code null}**（D-5 §1.4：父块不参与检索）。</li>
     * </ul>
     *
     * <p>返回 {@code null} 而不是空串：调用方要能区分"这块不该进检索池"与"这块进了但文本是空的"。
     *
     * @throws UnsupportedOperationException 已无（`T-512` 步 6 补齐了最后一个预留视图）
     */
    public static String defaultVectorText(Block block) {
        return switch (block.blockType()) {
            case TABLE -> block.isTopLevel() ? null : project(block, ChunkView.TABLE_LINEAR);
            case IMAGE -> project(block, ChunkView.IMAGE_DESC);
            default -> project(block, ChunkView.WITH_TITLE_PATH);
        };
    }

    /**
     * 该块**给大模型读**的文本（回表 / 详情页展示）。
     *
     * <p>与 {@link #defaultVectorText} 的分工：正文块两者相同；**表格父块**在这条路上
     * 返回 Markdown（要给大模型读），在检索那条路上返回 {@code null}（不进池）。
     * 这正是"同一形状、两份内容"的通用形态。
     */
    public static String displayText(Block block) {
        return switch (block.blockType()) {
            case TABLE -> block.isTopLevel()
                    ? project(block, ChunkView.TABLE_MARKDOWN)
                    : project(block, ChunkView.TABLE_LINEAR);
            case IMAGE -> project(block, ChunkView.IMAGE_DESC);
            default -> project(block, ChunkView.RAW);
        };
    }
}
