package com.knowledge.ai.pipeline.routing;

import com.knowledge.ai.pipeline.ParsedDocument;

/**
 * 页内容路由位（`优化/O2` 步 4）—— **接口先留，能力后接**。
 *
 * <h3>为什么现在就抽这个接口，而不是等到接 OCR 时再说</h3>
 * <p>v1 口径明确不做 OCR/VL（`T-0S1` §一-5）。既然不做，一个"只有空实现"的接口看起来是
 * **多余的抽象** —— 直到 `S6` 的 `T-512`（图片理解）与 `T-907`（VL 模型）要接上时才明白
 * 它换来了什么：
 * <ul>
 *   <li><b>没有它</b>：接 OCR 时要改 {@code TextLayerGuard}（加判断）、改落库（加处置列）、
 *       改前端（加"已补全"标记）—— 三处同时动，而这三处当时都已经有别的批次在改。</li>
 *   <li><b>有它</b>：只加一个实现类（{@code OcrPageContentRouter}）并改一处注入。
 *       处置字段（{@code Disposition.OCR_FILLED}）与前端分流**在本批就已就位**。</li>
 * </ul>
 * 这就是 `O2` §4 步 4 那句「路由位先留、能力后接」的具体含义 —— 不是设计洁癖，
 * 是**把将来那次跨三处的改动压成一处**。
 *
 * <h3>为什么是"每个页问一次"而不是"整篇一次性决策"</h3>
 * <p>真实文档里扫描页是**混进来**的（盖章页、附件页、扫描件插页），
 * 按文档类型分流覆盖不到这种最常见的情况 —— 那是 `O2` §4 末句点名的判断。
 * 所以路由的粒度必须是**页**，与 {@link ParsedDocument.PageStat} 同粒度。
 */
public interface PageContentRouter {

    /**
     * 决定一个页面的内容怎么处置。
     *
     * @param page 页级指标（**零字页**才会被问 —— 有文本的页直接走既有文本路径，
     *             不经路由。判据见 {@code TextLayerGuard#pageIssues}）
     * @return 处置结果；**不得返回 {@code null}**（调用方直接读它的两个字段）
     */
    PageAction route(ParsedDocument.PageStat page);
}