package com.knowledge.ai.pipeline.routing;

import com.knowledge.ai.pipeline.ParsedDocument;

/**
 * 一页的处置决定（{@link PageContentRouter#route} 的返回值）。
 *
 * <p>两个字段各自有消费方，**不要合并**：
 * <ul>
 *   <li>{@link #disposition()} 落 {@code sys_parse_issue.disposition} 列，
 *       由前端按它分流"要不要管"；</li>
 *   <li>{@link #note()} 拼进 {@code reason}，是给**人**看的那一半
 *       （本项目对失败态的要求：「不许只说失败两字」，见 `汇总.md` §5）。</li>
 * </ul>
 * 把它们挤成一个字段，等于让"机器可读"和"人可读"互相将就 —— 那正是
 * {@code IssueKind} 的 javadoc 记过的教训。
 *
 * @param disposition 处置结果（v1 恒为 {@link ParsedDocument.Disposition#MISSING}）
 * @param note        原因里补的那一句，**必须同时给出"发生了什么"与"下一步能做什么"**
 *                    （`G4-3` 的文案纪律）
 */
public record PageAction(ParsedDocument.Disposition disposition, String note) {
}