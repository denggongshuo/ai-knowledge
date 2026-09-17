package com.knowledge.ai.pipeline.routing;

import com.knowledge.ai.pipeline.ParsedDocument;
import org.springframework.stereotype.Component;

/**
 * v1 的路由实现：**只登记、不处理**（`优化/O2` §4 步 4）。
 *
 * <h3>它为什么不是"空实现"，而是一个诚实的实现</h3>
 * <p>v1 口径下 OCR/VL**确实不做**（`T-0S1` §一-5）。所以这一页的内容**确实缺失**、
 * 用户**确实要自己补** —— {@code MISSING} 不是占位符，它是这一页在 v1 里的**真实处置结果**。
 * 返回它同时满足本项目的两条硬口径：
 * <ul>
 *   <li>「静默降级视为缺陷」—— content 丢了这件事**必须可见**（进 {@code sys_parse_issue}）；</li>
 *   <li>「失败必须给现象 + 下一步动作」—— {@link #NOTE} 里两句都写了。</li>
 * </ul>
 *
 * <h3>接能力时改哪里</h3>
 * <p>`S6` 的 `T-512`/`T-907` 接上 VL 模型后，新增一个
 * {@code OcrPageContentRouter} 并让它成为注入的那个 —— 届时 {@code route} 会返回
 * {@link ParsedDocument.Disposition#OCR_FILLED} 与"已补全"的说明。
 * **本类不要改**（它是 v1 口径的记录），改注入点即可。
 *
 * <p>⚠️ 现在那个"改注入点"的动作**没有留下的痕迹** —— 唯一的注入方是
 * {@code DocParseService}，那里的字段声明上有注释指向本类。
 */
@Component
public class NoOpPageContentRouter implements PageContentRouter {

    /**
     * 补进 issue 原因的那一句。
     *
     * <p>两句不能省任何一半：**现象**（该页内容不在索引里）让人判断严重性，
     * **动作**（补录文本版本 / 启用 OCR 后重建索引）让人知道怎么办。
     * 只说前一半，用户看到的是一个自己无能为力的红条。
     */
    private static final String NOTE =
            "该页内容无法参与检索；请补录该页的文本版本，或启用 OCR 后重建索引";

    @Override
    public PageAction route(ParsedDocument.PageStat page) {
        // v1：不做任何补全。返回"未处理" —— 这是**事实**，不是占位。
        return new PageAction(ParsedDocument.Disposition.MISSING, NOTE);
    }
}