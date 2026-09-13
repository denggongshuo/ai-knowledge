package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 登录方式（登录日志的 {@code method} 列）。
 *
 * <p>库与实体里存**枚举名**，接口出参映射成**中文标签**。
 * 这个分工是刻意的：前端 {@code login-logs} 表格直接渲染 {@code method} 字段，
 * 要的是「密码登录」这样的文案；而库若存文案，改文案就得刷历史数据。
 * 映射只能放在出参侧，这是唯一能同时满足两者的位置。
 *
 * <p>{@code SSO} 目前没有实现（统一身份认证是原型上的演示项），
 * 保留枚举值是为了让前端既有的「统一身份认证」筛选/展示逻辑不改。
 */
@Getter
public enum LoginMethod {

    /** 账号密码登录（{@code POST /auth/login}）。 */
    PASSWORD("密码登录"),

    /** 短信验证码登录（{@code POST /auth/login-by-sms}，S1 暂未开放）。 */
    SMS("验证码登录"),

    /** 统一身份认证（原型演示项，未实现）。 */
    SSO("统一身份认证"),
    ;

    private final String label;

    LoginMethod(String label) {
        this.label = label;
    }

    /** 由枚举名取中文标签；未知值原样返回（历史数据向前兼容，不抛异常）。 */
    public static String labelOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (LoginMethod method : values()) {
            if (method.name().equalsIgnoreCase(name)) {
                return method.label;
            }
        }
        return name;
    }
}
