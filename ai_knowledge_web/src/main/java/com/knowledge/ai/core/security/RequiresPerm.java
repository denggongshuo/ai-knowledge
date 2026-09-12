package com.knowledge.ai.core.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 功能权限校验（M00 公共基座）。
 *
 * <p>用法（取值必须是 {@code PermissionCode} 里的原子权限码，见《05-需求-权限与审计》§0.3）：
 * <pre>
 *   &#64;RequiresPerm(PermissionCode.DOC_DOWNLOAD)              // 常量，推荐
 *   &#64;RequiresPerm("doc:download")                            // 字面量，等价
 *   &#64;RequiresPerm(value = {"user:create", "user:update"}, logic = OR)   // 任一命中
 *   &#64;RequiresPerm(value = {"kb:create", "kb:config"}, logic = AND)      // 全部命中
 * </pre>
 *
 * <p>校验失败抛 {@code BizException(FORBIDDEN)} → 由全局异常处理器转成
 * HTTP 403 + {@code {"code":10020}}。
 *
 * <p><b>它只管「功能权限」</b>。另外两个维度不在这里：
 * <ul>
 *   <li><b>数据范围</b>（ALL / DEPT / SELF）—— 拼进 SQL 的 where 条件，
 *       见数据范围工具，不是注解能表达的；</li>
 *   <li><b>知识库授权</b>（逐库 READ/UPLOAD/MANAGE）—— 在业务方法里按 kbId 校验。</li>
 * </ul>
 * 三者是**求交**关系（《05》§0.2）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPerm {

    /** 权限码。空数组表示只校验「已登录」。 */
    String[] value() default {};

    /** 多个权限码之间的关系。默认任一命中（更符合"能进这个页面"的直觉）。 */
    PermissionLogic logic() default PermissionLogic.OR;

    /** 校验失败时的提示语。留空则用 {@code ErrorCode.FORBIDDEN} 的默认文案。 */
    String message() default "";
}
