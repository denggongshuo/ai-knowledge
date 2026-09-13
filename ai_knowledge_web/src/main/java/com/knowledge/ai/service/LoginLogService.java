package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.LoginMethod;
import com.knowledge.ai.dto.auth.AuthDtos;
import com.knowledge.ai.entity.SysLoginLog;
import com.knowledge.ai.repository.SysLoginLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 登录日志写入与查询（T-112 · 门禁 G1-4）。
 *
 * <p><b>写入为什么用 {@code REQUIRES_NEW}</b>：登录**失败**时业务方法会抛异常
 * （{@code BAD_CREDENTIALS}），若日志与业务同事务，这条最需要留下的记录
 * 会随回滚一起消失，风控就成了摆设。独立事务保证"业务怎么失败，日志都留下"。
 * 与审计 AOP（I-12）同一理由。
 *
 * <p><b>写入失败绝不影响登录结果</b>：日志是旁路证据，不是业务前置条件。
 * 因此所有写入都包在 try/catch 里，失败只记 warn。
 *
 * <p>设备与归属地都是**由请求头推导的展示值**（不做 IP 库解析，《04》§4.5 明确 v1 不做）。
 * 推导规则集中在本类，避免散落到 Controller。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginLogService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysLoginLogRepository repository;

    // ==================== 写入 ====================

    /**
     * 记一条登录日志（成功或失败）。
     *
     * @param userId     用户 id；账号不存在时为 {@code null}
     * @param username   提交的登录账号
     * @param method     登录方式
     * @param result     {@code SUCCESS} / {@code FAILED}
     * @param failReason 失败原因（成功传 {@code null}）
     * @param ip         客户端 IP
     * @param userAgent  原始 User-Agent
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String username, LoginMethod method, boolean result,
                       String failReason, String ip, String userAgent) {
        try {
            SysLoginLog entry = new SysLoginLog();
            entry.setUserId(userId);
            entry.setUsername(truncate(username, 64));
            entry.setMethod(method.name());
            entry.setIp(truncate(ip, 64));
            entry.setUserAgent(truncate(userAgent, 255));
            entry.setDevice(parseDevice(userAgent));
            entry.setLocation(parseLocation(ip));
            entry.setResult(result ? RESULT_SUCCESS : RESULT_FAILED);
            entry.setFailReason(truncate(failReason, 255));
            LocalDateTime now = LocalDateTime.now();
            entry.setCreatedAt(now);
            entry.setCreatedDate(now.toLocalDate());
            repository.save(entry);
        } catch (RuntimeException ex) {
            // 绝不因为日志写不进去而让用户登录失败
            log.warn("登录日志写入失败：username={} err={}", username, ex.getMessage());
        }
    }

    // ==================== 查询 ====================

    /**
     * 分页查某用户的登录日志（按时间倒序）。
     *
     * <p><b>请求参数名与响应字段名刻意不同源</b>，这是本接口唯一容易踩的地方：
     * <ul>
     *   <li><b>请求</b>：前端 `userApi.loginLogs` 传 {@code pageSize}，《10》§58 写 {@code size}
     *       —— 两个都得收（见 {@code UserController#loginLogs}，{@code pageSize} 优先）；</li>
     *   <li><b>响应</b>：按《10》§368 只给 {@code pageSize}。**不做双写** ——
     *       前端当前只读 {@code list}，多加一个字段是无人消费的载荷。</li>
     * </ul>
     * 这是唯一一个不遵循 §0「{@code page/size}」通用约定的接口。
     */
    @Transactional(readOnly = true)
    public PageResult page(Long userId, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize);
        Page<SysLoginLog> result = repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<AuthDtos.LoginLogItem> list = result.getContent().stream()
                .map(this::toItem)
                .toList();
        return new PageResult(list, result.getTotalElements(), safePage, safeSize);
    }

    /** 最近成功的登录记录，供「安全概览」的 {@code recentDevices}。 */
    @Transactional(readOnly = true)
    public List<SysLoginLog> recentSuccess(Long userId, int limit) {
        List<SysLoginLog> all = repository.findTop10ByUserIdAndResultOrderByCreatedAtDesc(userId, RESULT_SUCCESS);
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    // ==================== 映射与推导 ====================

    private AuthDtos.LoginLogItem toItem(SysLoginLog log) {
        return new AuthDtos.LoginLogItem(
                log.getId(),
                LoginMethod.labelOf(log.getMethod()),
                log.getIp(),
                // 前端列表的"设备"列要的是「浏览器 · 操作系统」这种短语；
                // 历史数据可能没有 device（早期记录），退回展示原始 UA 的前 40 字符
                log.getDevice() != null ? log.getDevice() : truncate(log.getUserAgent(), 40),
                log.getResult(),
                log.getCreatedAt() == null ? null : TIME_FORMAT.format(log.getCreatedAt()));
    }

    /**
     * 由 User-Agent 推导「浏览器 · 操作系统」。
     *
     * <p>刻意**不引 UA 解析库**：本项目的设备列只有一个展示用途（个人信息页列表 +
     * 安全概览的"当前设备"），而 UA 解析库普遍体积大、规则常过期。
     * 这里按主流浏览器的 UA 特征串做最小匹配，识别不出就返回 {@code null}
     * 由调用方退回原始 UA —— 不装懂比猜错强。
     */
    static String parseDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);

        String browser = null;
        if (ua.contains("edg/")) {
            browser = "Edge";
        } else if (ua.contains("chrome/") || ua.contains("crios/")) {
            browser = "Chrome";
        } else if (ua.contains("firefox/")) {
            browser = "Firefox";
        } else if (ua.contains("safari/")) {
            browser = "Safari";
        } else if (ua.contains("curl/")) {
            browser = "curl";
        }

        String os = null;
        if (ua.contains("windows")) {
            os = "Windows";
        } else if (ua.contains("mac os x") || ua.contains("macintosh")) {
            os = "macOS";
        } else if (ua.contains("android")) {
            os = "Android";
        } else if (ua.contains("iphone") || ua.contains("ipad")) {
            os = "iOS";
        } else if (ua.contains("linux")) {
            os = "Linux";
        }

        if (browser == null && os == null) {
            return null;
        }
        if (browser == null) {
            return os;
        }
        if (os == null) {
            return browser;
        }
        return browser + " · " + os;
    }

    /**
     * 由 IP 推导归属地。
     *
     * <p>v1 只区分**内网 / 外网**，不做 IP 库解析（《04》§4.5 明确不做
     * "上次登录地点"之类的地理风控提示）。内网段按 RFC1918 判定。
     */
    static String parseLocation(String ip) {
        if (ip == null || ip.isBlank()) {
            return "未知";
        }
        String value = ip.trim();
        if (value.startsWith("10.")
                || value.startsWith("192.168.")
                || value.startsWith("127.")
                || value.startsWith("169.254.")
                || isPrivate172(value)
                || "::1".equals(value)
                || "localhost".equalsIgnoreCase(value)) {
            return "内网";
        }
        return "外网";
    }

    private static boolean isPrivate172(String ip) {
        if (!ip.startsWith("172.")) {
            return false;
        }
        int firstDot = ip.indexOf('.');
        int secondDot = ip.indexOf('.', firstDot + 1);
        if (secondDot < 0) {
            return false;
        }
        try {
            int second = Integer.parseInt(ip.substring(firstDot + 1, secondDot));
            return second >= 16 && second <= 31;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** 成功标识。 */
    public static final String RESULT_SUCCESS = "SUCCESS";

    /** 失败标识。 */
    public static final String RESULT_FAILED = "FAILED";

    /** 分页结果。字段名按《10》§368 与前端消费口径（{@code { list, total, page, pageSize }}）。 */
    public record PageResult(List<AuthDtos.LoginLogItem> list, long total, int page, int pageSize) {
    }
}
