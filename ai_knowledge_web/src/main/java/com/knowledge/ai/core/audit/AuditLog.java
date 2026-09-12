package com.knowledge.ai.core.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志埋点（M00 公共基座 · D-1）。
 *
 * <p>用法：
 * <pre>
 *   &#64;AuditLog(module = AuditModule.USER, action = "新建用户", risk = RiskLevel.HIGH,
 *             target = "#body.username", recordParams = false)
 *   &#64;PostMapping("/users")
 *   public ApiResponse&lt;Void&gt; create(&#64;RequestBody UserCreate body) { ... }
 * </pre>
 *
 * <p>切面会自动补齐：操作人（从 {@code LoginUserContext}）、IP、User-Agent、
 * 接口路径、耗时、traceId、成功/失败与失败原因。
 *
 * <p><b>不变量 I-12</b>：落库走 {@code REQUIRES_NEW} 独立事务，
 * 业务回滚不会把日志一起回滚 —— 否则会出现"操作发生过但查不到"。
 *
 * <p><b>不变量 I-15</b>：API Key 一类敏感字段**只记"已变更"，不记具体值**。
 * 所以 {@link #recordParams()} 默认 {@code false}；
 * 即使开启，也请确认参数里没有密钥（或先把密钥脱敏）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /** 业务模块，取值见 {@link AuditModule}。 */
    String module();

    /** 动作描述，如"新建用户""调整知识库授权"。会直接展示在日志列表的「操作」列。 */
    String action();

    /** 风险等级，默认普通。高危动作见 {@link RiskLevel}。 */
    RiskLevel risk() default RiskLevel.NORMAL;

    /**
     * 操作对象。支持 SpEL，可用方法参数名引用（如 {@code "#id"}、{@code "#body.username"}）。
     * 留空则回退为 {@code 类名#方法名}。
     */
    String target() default "";

    /**
     * 是否把方法入参序列化后存进备注。
     * 默认 {@code false} —— 入参里可能含密码 / Token / API Key（不变量 I-15）。
     */
    boolean recordParams() default false;
}
