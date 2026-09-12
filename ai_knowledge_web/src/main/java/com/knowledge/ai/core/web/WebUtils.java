package com.knowledge.ai.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * Web 层小工具。
 */
public final class WebUtils {

    private static final String UNKNOWN = "unknown";

    private static final String[] IP_HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
    };

    private WebUtils() {
    }

    /**
     * 取客户端真实 IP。
     *
     * <p>顺序：{@code X-Forwarded-For}（取第一段，即最初的客户端）→ {@code X-Real-IP}
     * → 其它代理头 → {@code remoteAddr}。
     *
     * <p><b>安全提醒</b>：这些头是客户端可伪造的，只有在**可信代理**（Nginx）之后
     * 才有意义。因此它只用于审计展示与风控参考，**不用于任何权限判断**。
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        for (String header : IP_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                String ip = comma > 0 ? value.substring(0, comma) : value;
                return ip.trim();
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? UNKNOWN : remote;
    }

    /** 取 User-Agent，截断到 255 字符（列宽上限）。 */
    public static String userAgent(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String ua = request.getHeader(HttpHeaders.USER_AGENT);
        if (ua == null) {
            return "";
        }
        return ua.length() > 255 ? ua.substring(0, 255) : ua;
    }

    /** 接口路径，含查询串，截断到 512 字符。 */
    public static String apiPath(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String path = request.getRequestURI();
        String query = request.getQueryString();
        String full = (query == null || query.isBlank()) ? path : path + "?" + query;
        return full.length() > 512 ? full.substring(0, 512) : full;
    }
}
