package com.knowledge.ai.pipeline;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.core.enums.ChunkView;

import java.util.List;

/**
 * 一个**块**（`T-503`）—— 分片的产出单元，也是 D-5 契约的领域模型。
 *
 * <h3>块存事实，视图是投影</h3>
 * <p>{@link #rawContent} 是**事实，永不改写**；「喂给嵌入模型的文本」由
 * {@link ChunkViews#project} 在索引期投影得到。这是 `O8` 的全部内容 ——
 * 把一次字符串拼接从"写库时"挪到"送嵌入前"。
 *
 * <h3>为什么 {@code parentOrderNo} 是"序号"而不是 id</h3>
 * <p>分片阶段还没有数据库，父块的 {@code id} 尚不存在（自增靠插入才拿得到）。
 * 所以子块先指向**父块在本篇文档内的序号**，由落库那一层（`T-505` 的
 * {@code ChunkRecordWriter}）先插父块拿 id、再回填成 {@code parentId}。
 * 这样分片算法是**纯函数**，可以脱离数据库单测。
 *
 * @param rawContent     块的原始内容（表格父块 = Markdown 管道表；表格子块 = 行式文本；
 *                       正文块 = 原文）。**任何视图都不会改写它**
 * @param blockType      块类型（D-5 §1.3：入库列名 / Milvus 标量名是 {@code chunk_type}）
 * @param titlePath      标题路径（如 {@code 3. 接口设计 > 3.1 鉴权流程}），可空 ——
 *                       标题之前的块（封面段）没有路径
 * @param orderNo        文档内顺序，**1-based**（回表与详情页按它还原阅读顺序）
 * @param pageNo         所在页码，1-based；**非分页格式（TXT/MD）为 {@code null}**
 * @param parentOrderNo  **表格子块**指向其父块的 {@link #orderNo}；**顶层块为 {@code null}**。
 *                       ⚠️ 禁用 {@code 0} 当哨兵（D-5 §1.2 硬口径：{@code 0} 会与真实 id 混淆）
 * @param sourceOrderNo  **HyQ 块**（`HYPOTHESIS_QA`）指向它由哪个块生成 —— 即"这条假设问句
 *                       是从哪段正文推出来的"。其余块为 {@code null}。
 *                       <p>⚠️ 它**不是** {@link #parentOrderNo}，两者不能合并：
 *                       `G4-6` 的 SQL 断言（已收口）要求「凡有 {@code parent_id} 的块，
 *                       父块必须是 {@code TABLE}`」—— 把 HyQ 挂到源块下面会让那条断言当场转红。
 *                       而 `D-5` 原本**没给 HyQ 块任何回指源块的字段**，于是检索命中一个 HyQ 块时
 *                       "拿什么给大模型"没有答案（`汇总.md` §4-271 挂了很久的判据）。
 *                       本字段就是那个答案：命中后按它取**源块正文**喂给模型
 *                       —— 问句只用于召回，不用于回答。
 *                       <p>落库时它被解析成 `sys_chunk.source_chunk_id`（与 `parentOrderNo`
 *                       解析成 `parent_id` 是同一跳：先插全部块拿到 id，再回填）。
 */
public record Block(String rawContent,
                    BlockType blockType,
                    String titlePath,
                    int orderNo,
                    Integer pageNo,
                    Integer parentOrderNo,
                    Integer sourceOrderNo) {

    /** 紧凑构造器：内容为 null 与空串等价，下游不必两处判空。 */
    public Block {
        rawContent = rawContent == null ? "" : rawContent;
    }

    /**
     * 是否是**顶层块**（D-5 §1.2）。
     *
     * <p>口径是"没有父块"，**不是**"某种类型" —— 表格父块与表格子块同属
     * {@link BlockType#TABLE}，两者的区别只在这里。
     * `chunkCount` 也只计顶层块（D-5 §1.5）。
     */
    public boolean isTopLevel() {
        return parentOrderNo == null;
    }

    /**
     * 本块**声明自己有哪些视图**（`O8` §4 步 3 的"泛化点"）。
     *
     * <p>判别表格父子靠 {@link #isTopLevel()} 而不是类型 —— 两者同属
     * {@link BlockType#TABLE}。这条规则原先硬编码在分片管线的表格分支里，
     * 现在它是**声明**：新增块类型 = 在这儿加一行，不改管线。
     */
    public List<ChunkView> views() {
        return switch (blockType) {
            // 父块只供回表，子块只供检索 —— 两者都是 TABLE，靠父/子区分
            case TABLE -> isTopLevel()
                    ? List.of(ChunkView.TABLE_MARKDOWN)
                    : List.of(ChunkView.TABLE_LINEAR);
            case IMAGE -> List.of(ChunkView.IMAGE_DESC);
            default -> List.of(ChunkView.RAW, ChunkView.WITH_TITLE_PATH);
        };
    }
}
