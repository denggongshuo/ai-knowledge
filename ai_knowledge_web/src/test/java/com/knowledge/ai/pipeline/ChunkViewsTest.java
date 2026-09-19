package com.knowledge.ai.pipeline;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.core.enums.ChunkView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `O8`（分片契约：块与视图分离）步 0 取证。
 *
 * <h3>这些判据防的是什么</h3>
 * <p>「块存事实，视图是投影」这句话如果只在文档里，下一个人很容易图省事把标题路径
 * **写回** {@code rawContent} —— 那样一来"改注入策略只需重建索引"就悄悄失效了，
 * 而且失效得没有任何征兆（功能照跑，只是改不动了）。所以这几条用可执行断言钉住：
 * <ul>
 *   <li>投影**不改块**（{@code rawContent} 逐字不变）；</li>
 *   <li>两个视图**确实不同**（否则"切换视图"是个空承诺）；</li>
 *   <li>表格规则与 D-5 §1.4 **逐条等价**（本次是重构不是行为变更 —— 改坏了下游全歪）。</li>
 * </ul>
 *
 * <p>纯单测，不启 Spring：{@link ChunkViews} 全是纯函数，起容器只会让失败现场更难读。
 */
class ChunkViewsTest {

    /** 一个典型的正文块：有标题路径。 */
    private static final Block TEXT_WITH_PATH =
            new Block("接口鉴权走 JWT，有效期 2 小时。", BlockType.PARAGRAPH,
                    "3. 接口设计 > 3.1 鉴权流程", 7, 12, null, null, null);

    /** 封面段那种：标题之前的块，**没有**标题路径。 */
    private static final Block TEXT_WITHOUT_PATH =
            new Block("某公司内部技术规范 v2。", BlockType.PARAGRAPH, null, 1, 1, null, null, null);

    /** 表格父块：rawContent 就是 Markdown 管道表。 */
    private static final Block TABLE_PARENT =
            new Block("| 型号 | 产品名称 | 单价 |\n| A-100 | 智能网关 | ￥1,200 |\n",
                    BlockType.TABLE, "2. 报价", 20, 5, null, null, null);

    /** 表格子块：rawContent 就是行式文本。 */
    private static final Block TABLE_CHILD =
            new Block("型号 A-100，产品名称 智能网关，单价 ￥1,200",
                    BlockType.TABLE, "2. 报价", 21, 5, 20, null, null);

    // ==================================================================
    // O8 判据 2 · 块内容不因注入而改变
    // ==================================================================

    @Test
    @DisplayName("O8 判据 2：投影不写回块 —— rawContent 在任何视图下都逐字不变")
    void rawContentIsNeverRewritten() {
        String before = TEXT_WITH_PATH.rawContent();

        for (ChunkView view : TEXT_WITH_PATH.views()) {
            ChunkViews.project(TEXT_WITH_PATH, view);
        }

        assertThat(TEXT_WITH_PATH.rawContent())
                .as("投影是纯函数，不得就地改造块。"
                        + "一旦写回，'改注入策略只需重建索引'就悄悄失效了 —— 且没有任何征兆")
                .isEqualTo(before)
                .doesNotContain("3. 接口设计");
    }

    // ==================================================================
    // O8 判据 3 · 嵌入文本含标题路径（而块不含）
    // ==================================================================

    @Test
    @DisplayName("O8 判据 3：WITH_TITLE_PATH 含前缀，RAW 不含 —— 两者是同一块的两个投影")
    void viewProjectionDiffersByTitlePath() {
        String raw = ChunkViews.project(TEXT_WITH_PATH, ChunkView.RAW);
        String injected = ChunkViews.project(TEXT_WITH_PATH, ChunkView.WITH_TITLE_PATH);

        assertThat(raw)
                .as("RAW 视图就是原文，不该出现标题路径")
                .isEqualTo("接口鉴权走 JWT，有效期 2 小时。")
                .doesNotContain("接口设计");

        assertThat(injected)
                .as("WITH_TITLE_PATH 要把标题路径拼在原文之前（《07》§附「让这个分片属于哪一节进入向量」）")
                .startsWith("3. 接口设计 > 3.1 鉴权流程")
                .endsWith("接口鉴权走 JWT，有效期 2 小时。")
                .contains(ChunkViews.TITLE_PATH_SEPARATOR);

        assertThat(injected).isNotEqualTo(raw);
    }

    @Test
    @DisplayName("O8：标题路径为空时退化为原文 —— 不留孤零零的分隔符")
    void blankTitlePathFallsBackToRaw() {
        assertThat(ChunkViews.project(TEXT_WITHOUT_PATH, ChunkView.WITH_TITLE_PATH))
                .as("封面段没有标题路径。不退化的话拼接结果是「空 + 分隔符 + 原文」，"
                        + "嵌入文本会以一个孤立的换行开头 —— 那是个无意义的 token，只会稀释语义")
                .isEqualTo(TEXT_WITHOUT_PATH.rawContent())
                .doesNotStartWith(ChunkViews.TITLE_PATH_SEPARATOR);
    }

    // ==================================================================
    // O8 判据 5 · 表格规则与 D-5 §1.4 逐条等价（重构，不是行为变更）
    // ==================================================================

    @Test
    @DisplayName("O8 判据 5：亲子视图声明 = D-5 §1.4 的既有规则（父块 Markdown 不进检索 · 子块行式文本进）")
    void tableViewsMatchD5Clause14() {
        assertThat(TABLE_PARENT.views())
                .as("表格父块只有 TABLE_MARKDOWN 一个视图")
                .containsExactly(ChunkView.TABLE_MARKDOWN);
        assertThat(TABLE_CHILD.views())
                .as("表格子块只有 TABLE_LINEAR 一个视图")
                .containsExactly(ChunkView.TABLE_LINEAR);

        assertThat(ChunkView.TABLE_MARKDOWN.isVectorized())
                .as("D-5 §1.4：父块不参与检索 —— 这条先是硬编码分支，现在成了视图的默认配置")
                .isFalse();
        assertThat(ChunkView.TABLE_LINEAR.isVectorized())
                .as("D-5 §1.4：子块参与检索")
                .isTrue();

        assertThat(ChunkViews.defaultVectorText(TABLE_PARENT))
                .as("父块默认不进检索池。返回 null 而非空串 —— 调用方要能区分"
                        + "'不该进池'与'进了但文本是空的'")
                .isNull();
        assertThat(ChunkViews.defaultVectorText(TABLE_CHILD))
                .isEqualTo("型号 A-100，产品名称 智能网关，单价 ￥1,200");
    }

    @Test
    @DisplayName("O8 判据 5：回表取父块 Markdown —— 与检索侧取的是同一块的另一份投影")
    void parentBlockReadsAsMarkdownForLlm() {
        assertThat(ChunkViews.displayText(TABLE_PARENT))
                .as("父块给大模型读的是 Markdown（表头 + 竖线）。"
                        + "它同时也是 rawContent —— 说明父块的两条路确实指向同一份事实")
                .isEqualTo(TABLE_PARENT.rawContent())
                .contains("| 型号 |");
    }

    @Test
    @DisplayName("O8：正文块的两个视图都进检索池；表格父块不进")
    void vectorizedFlagSeparatesRetrievalPoolFromDisplay() {
        assertThat(TEXT_WITH_PATH.views())
                .as("正文块声明 RAW 与 WITH_TITLE_PATH 两个视图")
                .containsExactly(ChunkView.RAW, ChunkView.WITH_TITLE_PATH);
        assertThat(TEXT_WITH_PATH.views())
                .allMatch(ChunkView::isVectorized, "正文块的两个视图都进检索池");
    }

    // ==================================================================
    // O8 判据 6 · 图片视图（`T-512` 步 6 起已实装）
    // ==================================================================

    @Test
    @DisplayName("O8 判据 6（T-512 步 6 订正）：IMAGE_DESC 有实现 —— 返回块内容，不再抛")
    void imageViewIsImplemented() {
        Block image = new Block("（图片：架构图.png）", BlockType.IMAGE, "1. 总览", 3, 2, null, null, null);

        assertThat(image.views())
                .as("图片块声明 IMAGE_DESC 一条视图")
                .containsExactly(ChunkView.IMAGE_DESC);

        // ⚠️ 这条断言**前身是它的反面**（`assertThatThrownBy(...UnsupportedOperationException)`，
        //    方法名 `imageViewIsReservedNotImplemented`）。`T-512` 步 6 落地后**改成正向**，
        //    而**不是删掉** —— 删了就没有任何东西守着"落地后确实不抛了"，
        //    而那正是这条断言当初存在的理由（本仓 `O5` 判据 2 吃过一次"基线抓不到自己要抓的退化"）。
        assertThat(ChunkViews.project(image, ChunkView.IMAGE_DESC))
                .as("图片块的 rawContent 就是它的检索文本（题注 + VL 描述在步 5 拼好）")
                .isEqualTo("（图片：架构图.png）");
        assertThat(ChunkViews.defaultVectorText(image))
                .as("图片块**进检索池** —— 需求 06:168「描述文本进文本向量」")
                .isEqualTo("（图片：架构图.png）");
    }

    // ==================================================================
    // 泛化点的自证：新增块类型 = 声明视图，不改管线
    // ==================================================================

    @Test
    @DisplayName("O8 §4 步 3：视图声明覆盖全部 8 种块类型，无遗漏")
    void everyBlockTypeDeclaresViews() {
        for (BlockType type : BlockType.values()) {
            Block top = new Block("x", type, null, 1, 1, null, null, null);
            assertThat(top.views())
                    .as("%s 的顶层块必须声明至少一个视图（漏了会让它在向量化那一层被静默跳过）", type)
                    .isNotEmpty();
        }

        // 表格的父子分叉是这个泛化点最容易被写错的地方：两者 blockType 相同
        assertThat(new Block("x", BlockType.TABLE, null, 1, 1, 20, null, null).views())
                .as("同属 TABLE，靠 parentOrderNo 分叉 —— 这正是原先硬编码在表格分支里的那条规则")
                .containsExactly(ChunkView.TABLE_LINEAR);

        assertThat(List.of(BlockType.values()))
                .as("块类型是 8 种（D-5 §1.3）。数量变了说明枚举动过，本用例的覆盖要跟着更新")
                .hasSize(8);
    }
}
