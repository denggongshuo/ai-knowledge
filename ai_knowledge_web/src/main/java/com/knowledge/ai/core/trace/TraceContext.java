package com.knowledge.ai.core.trace;

import org.slf4j.MDC;

import java.security.SecureRandom;

/**
 * 链路追踪上下文（traceId）。
 *
 * <p>约定（见《02-需求总览与分档》§6.1）：**每一个响应体都带 `traceId`**，
 * 且该 traceId 必须与日志、审计日志、错误页展示的是同一个值。
 * 格式为 16 位小写十六进制（如 {@code a3f8c1e2b47d9e02}）。
 *
 * <p>传播路径：
 * <pre>
 *   TraceIdFilter（读/生成 X-Trace-Id）
 *        └─> TraceContext.set()  →  ThreadLocal（业务代码可取）
 *                                →  MDC（日志 pattern 里 %X{traceId} 可取）
 * </pre>
 *
 * <p><b>线程池注意</b>：ThreadLocal / MDC 不跨线程传播。异步任务在提交时
 * 通过 {@link #current()} 取出 traceId，在工作线程里重新 {@link #set(String)}，
 * 并在 finally 里 {@link #clear()}，避免污染池化线程。
 */
public final class TraceContext {

    /** 请求 / 响应头名称，前后端与网关统一使用。 */
    public static final String HEADER = "X-Trace-Id";

    /** 日志 MDC 的 key，与 logback 配置里的 %X{traceId} 对应。 */
    public static final String MDC_KEY = "traceId";

    /** traceId 长度（字节数与字符数）。 */
    private static final int BYTES = 8;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 绑定 traceId 到当前线程（ThreadLocal + MDC）。 */
    public static void set(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        HOLDER.set(traceId);
        MDC.put(MDC_KEY, traceId);
    }

    /** 取当前线程的 traceId，可能为 {@code null}。 */
    public static String get() {
        return HOLDER.get();
    }

    /**
     * 取当前 traceId；没有则现场生成一个并绑定到当前线程。
     * 供异步任务等「非请求线程」入口使用。
     */
    public static String getOrCreate() {
        String current = HOLDER.get();
        if (current == null) {
            current = newTraceId();
            set(current);
        }
        return current;
    }

    /**
     * 取用于响应体的 traceId：优先复用当前线程已绑定的值；
     * 若当前线程没有（例如非 Web 线程直接构造响应），则生成一个**不写入** ThreadLocal 的值，
     * 避免在池化线程里留下无人清理的残留。
     */
    public static String current() {
        String current = HOLDER.get();
        return current != null ? current : newTraceId();
    }

    /** 生成一个 16 位十六进制 traceId。 */
    public static String newTraceId() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        char[] chars = new char[BYTES * 2];
        for (int i = 0; i < BYTES; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(chars);
    }

    /** 解绑当前线程的 traceId。必须在 finally 中调用。 */
    public static void clear() {
        HOLDER.remove();
        MDC.remove(MDC_KEY);
    }
}
