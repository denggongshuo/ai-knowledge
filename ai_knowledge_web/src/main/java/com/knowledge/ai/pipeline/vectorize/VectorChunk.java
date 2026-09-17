package com.knowledge.ai.pipeline.vectorize;

import java.util.List;

/**
 * 一个待向量化的块（`T-508`）—— 从 {@code sys_chunk} 读出来的**只读快照**。
 *
 * <h3>为什么不用 {@code SysChunk} 实体</h3>
 * <p>向量化只关心「把哪段文本编成向量、带上哪些过滤标量」，与 JPA 的托管状态、
 * 脏检查、懒加载无关。用一个 record 承接，{@link ChunkVectorizer} 就能完全脱离
 * 数据库单测（这是"分片是纯函数"那条设计在向量化这一层的延续）。
 *
 * @param chunkId      MySQL {@code chunk.id}，**同时用作 Milvus 主键**（建表注释：
 *                     「分片 id，与 MySQL chunk.id 同值」）—— 两侧同值才能互相溯源与删除
 * @param blockType    块类型，写进 Milvus 标量 {@code chunk_type}
 * @param rawContent   块的原始内容（事实，永不改写）
 * @param titlePath    标题路径，可空
 * @param secretLevel  密级，取自**所属知识库**（块自身没有密级列）
 * @param parentId     父块 id；**{@code null} = 顶层块**
 * @param isBoilerplate 是否被判为**跨文档样板**（`O9` 步 3/4）。为 {@code true} 时
 *                      {@link #vectorText()} 照样有值（块本身是完整的），但它
 *                      **不进向量召回池** —— 见 {@link #inRecallPool()}
 */
public record VectorChunk(long chunkId,
                          String blockType,
                          String rawContent,
                          String titlePath,
                          String secretLevel,
                          Long parentId,
                          boolean isBoilerplate) {

    /** 旧形态（无样板标记）—— 样板判定是 `S5-M07-A` 才有的，本批之前的调用点与测试用它。 */
    public VectorChunk(long chunkId, String blockType, String rawContent, String titlePath,
                       String secretLevel, Long parentId) {
        this(chunkId, blockType, rawContent, titlePath, secretLevel, parentId, false);
    }

    /**
     * 是否**进向量召回池**。
     *
     * <p>两道门槛，缺一不可：
     * <ol>
     *   <li>{@link #vectorText()} 非 {@code null}（表格父块不进池，`D-5` §1.4）；</li>
     *   <li>不是样板（`O9` 步 4：跨文档样板占着 top-20 的名额却不含信息）。</li>
     * </ol>
     *
     * <p>⚠️ 为什么不把样板判定并进 {@link #vectorText()}：那个方法的返回值是
     * **文本投影**（`O8` 的"视图"），而样板是**语料统计结论**。把统计塞进投影函数
     * 会让"这段文本是什么"依赖"库里有多少篇文档" —— 同一个块在不同时刻投影出不同结果，
     * 是那种"跑两次结果不一样"的最坏形态。
     */
    public boolean inRecallPool() {
        return vectorText() != null && !isBoilerplate;
    }

    /**
     * 是否为**表格父块**（D-5 §1.4：父块只供回表，**不进检索池**）。
     *
     * <p>判据是「类型是 TABLE 且没有父块」—— 表格父块与子块同属 {@code TABLE}，
     * 靠 {@code parentId} 区分（与 {@code Block#isTopLevel()} 同一口径）。
     */
    public boolean isTableParent() {
        return "TABLE".equals(blockType) && parentId == null;
    }

    /**
     * 该块进向量检索池的文本；{@code null} = **不该进**。
     *
     * <p>这是 `O8`「块存事实、视图是投影」在向量化这一跳的落点：
     * {@link #rawContent} 不动，投影只发生在送嵌入之前。
     * 与 {@code ChunkViews#defaultVectorText} 的口径**逐条等价**（那是对 {@code Block} 的版本，
     * 这里是对"已落库的块"的版本 —— 两者必须一致，由 {@code ChunkVectorizerTest} 钉住）：
     * <ul>
     *   <li>表格父块 → {@code null}（不进池）；</li>
     *   <li>表格子块 → 行式文本（分片阶段已算好，存在 {@code raw_content} 里）；</li>
     *   <li>正文块 → 标题路径 + 换行 + 原文（标题路径为空时退化为原文）。</li>
     * </ul>
     */
    public String vectorText() {
        if (isTableParent()) {
            return null;
        }
        if (titlePath == null || titlePath.isBlank()) {
            return rawContent;
        }
        // 分隔符与 ChunkViews.TITLE_PATH_SEPARATOR 一致（单个换行）。
        // 两处都写死字面量而不是互相引用：ChunkViews 在 pipeline 层、这里在同层的 vectorize 子包，
        // 引用会造出一个不必要的编译依赖；一致性由用例钉住（见类 javadoc）。
        return titlePath + "\n" + rawContent;
    }

    /** 供日志与断言：投影是否真的产生了变化。 */
    public boolean projectionChangesText() {
        String text = vectorText();
        return text != null && !text.equals(rawContent);
    }

    /** 便于批量读取时做「只取进池的块」过滤。 */
    public static List<VectorChunk> onlyVectorized(List<VectorChunk> chunks) {
        return chunks.stream().filter(c -> c.vectorText() != null).toList();
    }

    /**
     * 批量取「真的该进召回池」的块（= {@link #inRecallPool()}）。
     *
     * <p>与 {@link #onlyVectorized} 的关系：后者是**投影维度**的过滤（表格父块），
     * 它有一条 `ChunkViewsTest` 钉着的等价性契约（与 {@code ChunkViews#defaultVectorText} 逐条对齐），
     * **不能改**。本方法在它之上再加样板这一维。分开两个方法是为了让
     * "表格父块被跳过"和"样板被跳过"这两件事**分别可计数、可解释** ——
     * 合成一个过滤器的后果是日志里只剩一个"跳过 N 条"，而 N 里混了两种完全不同的原因。
     */
    public static List<VectorChunk> onlyRecallable(List<VectorChunk> chunks) {
        return chunks.stream().filter(VectorChunk::inRecallPool).toList();
    }
}
