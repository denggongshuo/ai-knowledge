package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 块的**索引期视图**（`O8` 核心口径，2026-09-16 定）。
 *
 * <h3>块与视图的关系</h3>
 * <p><b>块存事实，视图是投影。</b>块的 {@code rawContent} 是"事实"，永不改写；
 * 「喂给嵌入模型的文本」是索引期由 {@code rawContent} 经**纯函数投影**得到的视图。
 *
 * <p>拆开之前，`titlePath` 被定义成"拼进分片内容"（《07》§附）—— 那是**不可逆**的：
 * 想改注入策略就必须重解析全库（原始块不存在了），回表拿到的也是拼接过的版本，
 * A/B 更是做不了。拆开之后，改策略 = 改投影 = **只重建索引，不重解析**。
 *
 * <h3>为什么这个枚举在 {@code core/enums} 而不在 {@code pipeline}</h3>
 * <p>它是**纯值类型**（视图名 + 是否进检索池 + 展示标签），不含任何投影逻辑。
 * 投影函数在 {@code pipeline/ChunkViews}（`Block` 也在那个包）—— 若把投影写进枚举，
 * {@code core} 就要反向依赖 {@code pipeline}，层次就倒了。
 *
 * <h3>为什么是"一次投影"而不是"一次拼接"</h3>
 * <p>视图是**通用能力**，不是表格专属规则。同一形状的需求有四个：
 * 表格（Markdown 给大模型 / 行式文本给向量）、图片（原图 / VL 描述）、
 * 正文（原文 / 原文 + 标题路径）、HyQ（无 / 假设问句）。
 * 有了视图，"新增块类型"= 声明视图，**不改管线**。
 *
 * @see BlockType
 */
@Getter
public enum ChunkView {

    /** 只把原文送进嵌入模型 —— 用于 A/B 对照"标题路径到底有没有用"。 */
    RAW("原文", true,
            "只投影原文，不拼标题路径"),

    /** **正文块的默认视图** —— 让"这个分片属于哪一节"进入向量（《07》§附的原始意图）。 */
    WITH_TITLE_PATH("标题路径 + 原文", true,
            "原文前拼标题路径；块本身不变，拼接只发生在送嵌入前"),

    /** 表格父块：整表 Markdown 管道表，**只供回表**，不进检索池。 */
    TABLE_MARKDOWN("表格 Markdown", false,
            "给大模型读的那一份（D-5 §1.4 的父块）"),

    /** 表格子块：列名 + 值的行式文本，**进检索池**。 */
    TABLE_LINEAR("表格行式文本", true,
            "给向量检索的那一份（D-5 §1.4 的子块）"),

    /**
     * 图片：VL 描述文本。
     *
     * <p><b>预留视图</b> —— 枚举存在但无实现（`T-512` 属 S6，依赖 `T-907` 的 VL 模型）。
     * 留在这里是为了让"接 VL 时**只加实现、不改管线**"这句话可验证：
     * 到那时本枚举不动，只补 {@code ChunkViews} 的投影分支。
     */
    IMAGE_DESC("图片描述", true,
            "预留：T-512 落地时只加实现，不改管线"),
    ;

    /** 展示名。 */
    private final String label;

    /**
     * 该视图的投影结果**是否写入向量检索池**。
     *
     * <p>这条把 D-5 §1.4「表格父块不参与检索」从**硬编码的分支**变成了**视图的默认配置**
     * （`O8` §4 步 4）。将来若有别的块类型也不该进检索池，声明它就行，不必再改分片管线。
     */
    private final boolean vectorized;

    /** 一句话说明（javadoc 之外的运行时可见版本，供排查时打印）。 */
    private final String description;

    ChunkView(String label, boolean vectorized, String description) {
        this.label = label;
        this.vectorized = vectorized;
        this.description = description;
    }
}
