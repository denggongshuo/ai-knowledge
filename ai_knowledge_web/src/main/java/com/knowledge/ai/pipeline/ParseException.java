package com.knowledge.ai.pipeline;

/**
 * 解析失败（**带可执行原因**）。
 *
 * <h3>为什么用专门的异常而不是直接抛 {@code BizException}</h3>
 * <p>解析发生在**工作线程**上（`ak-task-*`），那里的 {@code BizException} 不会经过
 * {@code GlobalExceptionHandler}（没有请求上下文），它的唯一去处是
 * {@code TaskContext#fail} → {@code AsyncTaskInfo.errorMsg} → 前端轮询展示。
 * 所以这一类异常需要的是"一句话能照着做"，而不是"一个 HTTP 错误码"。
 *
 * <h3>消息口径（本批最重要的一条纪律）</h3>
 * <p>{@code T-0S1} 的结论原话：**真正的风险不在"识别准不准"，而在"识别不到时静默返回空"**。
 * 所以凡是抛本异常的地方，消息里必须同时给出**现象**与**下一步动作**：
 * <ul>
 *   <li>✗ 「解析失败」</li>
 *   <li>✗ 「未提取到文本」</li>
 *   <li>✓ 「未提取到可复制文本（疑似扫描件或纯图片），该文档内容无法被检索命中；
 *          请补录文本，或启用 OCR 后重新上传」</li>
 * </ul>
 * 判据见 `G4-3`：失败态给出**具体原因**（非"失败"两字）。
 */
public class ParseException extends RuntimeException {

    public ParseException(String message) {
        super(message);
    }

    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 包一层底层异常，**保留原始原因**但对外仍是可读的话。
     *
     * <p>底层异常（{@code IOException} / 解析库的 {@code RuntimeException}）的
     * {@code getMessage()} 常是英文堆栈式描述或干脆是 {@code null}，
     * 直接下发到前端等于没说。所以这里显式要求调用方给出可读文案，
     * 原始异常只用于日志与 {@link #getCause()}。
     */
    public static ParseException of(String readable, Throwable cause) {
        return new ParseException(readable, cause);
    }
}
