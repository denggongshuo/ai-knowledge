package com.knowledge.ai.core.notice;

/**
 * 通知上的一个**可执行动作**（需求《09》§2.4：「通知要带动作，不能只是通知」）。
 *
 * <p>它对应前端 {@code NoticeItem.actions[]} 的一项：
 * {@code {label, to, primary}} —— 字段名逐字对齐，前端拿到就能直接渲染成按钮。
 *
 * <h3>为什么 {@code to} 是前端路由而不是后端 URL</h3>
 * <p>需求 §2.4 的原话是「给 1~2 个按钮**直接跳到处理现场**」。处理现场是**页面**，
 * 不是接口 —— 后端返回 {@code /kb/1/upload} 这类**前端路由**，由前端 {@code router.push} 消化。
 * 这与"返回一个下载地址"那类动作（`T-B614` 的打包下载）不同，那种返回的是真的 URL。
 * 两者将来会同时在通知里出现，所以这一层**不做格式校验**（`to` 只是个字符串，
 * 是不是路由由消费方决定）。
 *
 * @param label   按钮文案，如「重试解析」
 * @param to      目标（前端路由，或将来某天的资源地址）
 * @param primary 是否主按钮 —— 需求 §2.4 要求 1~2 个按钮，主次决定样式，不影响语义
 */
public record NoticeAction(String label, String to, boolean primary) {

    /** 主按钮（蓝底）。 */
    public static NoticeAction primary(String label, String to) {
        return new NoticeAction(label, to, true);
    }

    /** 次按钮（描边）。 */
    public static NoticeAction secondary(String label, String to) {
        return new NoticeAction(label, to, false);
    }
}
