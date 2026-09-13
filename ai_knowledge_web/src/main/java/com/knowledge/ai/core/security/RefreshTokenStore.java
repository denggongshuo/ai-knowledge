package com.knowledge.ai.core.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * refreshToken 存储与轮换（T-100 / T-101 / T-102）。
 *
 * <p><b>为什么 refreshToken 用「不透明随机串 + Redis」而不是第二个 JWT</b>：
 * <ol>
 *   <li>refreshToken 必须**可撤销**。「改密 / 重置密码后该用户全部 refreshToken
 *       立即失效」（G1-8）这条要求用 JWT 无法实现 —— 只能另建名单，
 *       等于把状态换个地方存，不如一开始就存 Redis。</li>
 *   <li>它只被 {@code /auth/refresh} 消费，不参与任何业务鉴权，
 *       所以「无状态、可离线校验」这个 JWT 的核心优势在这里用不上。</li>
 *   <li>不透明串不携带任何信息，泄露后攻击者也无法从中读出用户 id / 角色。</li>
 * </ol>
 *
 * <p>存储结构（三组键，各自职责单一）：
 * <pre>
 *   auth:rt:{token}          →  "{userId}|{jti}"      TTL = refreshTtlDays
 *   auth:rt:user:{userId}    →  SET of {token}        TTL = refreshTtlDays（每条独立续期）
 *   auth:rt:grace:{token}    →  HASH{userId,refresh,access}  TTL = 30s（见下）
 * </pre>
 *
 * <p><b>{@code auth:rt:user:} 这组集合是 G1-8 的实现基础</b>：
 * "失效该用户全部 refreshToken" 需要反查，只有正向映射的话就得扫全库 KEYS
 * （生产禁用）。用集合维护反查，代价是每次签发/撤销多一次 Redis 往返。
 *
 * <h3>并发去重（T-102 / 门禁 G1-3）</h3>
 * <p>前端在令牌过期时可能**同时**发出多个请求，每个都触发一次刷新。若不处理：
 * <ul>
 *   <li>每个请求都轮换一次 → 前 9 个刚换出的 token 立刻被第 10 个作废；</li>
 *   <li>前 9 个请求拿着"已作废的新 token"→ 全部 401 → 用户被踢回登录页。</li>
 * </ul>
 * <p>做法是「单飞 + 宽限窗口」：
 * <ol>
 *   <li>先查宽限键 {@code auth:rt:grace:{旧token}} —— 命中就直接返回**同一对**新令牌，
 *       完全不触碰轮换逻辑；</li>
 *   <li>未命中则抢锁 {@code auth:rt:lock:{旧token}}（SETNX + 10s）；
 *       抢到的那个执行真正的轮换，并把新旧对应关系写进宽限键（30 秒）；</li>
 *   <li>没抢到的（含锁过期）轮询宽限键最多 {@value #GRACE_WAIT_MILLIS} ms，
 *       读到就返回，读不到才判失败。</li>
 * </ol>
 * <p>于是 10 并发只会产生 **1 次真实轮换**，且 10 个调用方拿到**同一对**新令牌 ——
 * 既满足 G1-3 的判据，也不会互相踢下线。
 *
 * <p><b>已知取舍</b>：宽限窗口内把新 accessToken 也放进了 Redis（30 秒）。
 * 这是"让并发调用方拿到完全一致的结果"必须付的代价；窗口只有 30 秒且键值随旧 token
 * 绑定，泄露面可控。若将来不接受这一条，退路是宽限键只存 refreshToken，
 * 由调用方各自重签 accessToken（多一次 DB 读，结果不完全一致但都可用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String TOKEN_KEY = "auth:rt:";
    private static final String USER_KEY = "auth:rt:user:";
    private static final String LOCK_KEY = "auth:rt:lock:";
    private static final String GRACE_KEY = "auth:rt:grace:";

    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_REFRESH = "refresh";
    private static final String FIELD_ACCESS = "access";

    /** 宽限窗口时长：并发调用方在此期间内能拿到同一对新令牌。 */
    private static final Duration GRACE_TTL = Duration.ofSeconds(30);

    /** 轮换锁持有时长。取 10s —— 远大于一次轮换的真实耗时（几次 Redis 往返）。 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    /** 没抢到锁的调用方等待宽限键的总时长。 */
    private static final long GRACE_WAIT_MILLIS = 2000;

    private static final long GRACE_POLL_MILLIS = 25;

    private final StringRedisTemplate redis;

    /** 一次刷新操作的结果：新令牌对 + 归属用户。 */
    public record Rotation(Long userId, String refreshToken, String accessToken) {
    }

    private final JwtProperties properties;

    // ==================== 签发 / 查询 / 撤销 ====================

    /**
     * 签发一个 refreshToken。
     *
     * @param userId 归属用户
     * @param jti    与本次登录的 accessToken 配对的 jti（用于把两者视作同一次会话）
     */
    public String issue(Long userId, String jti) {
        return issue(userId, jti, properties.getRefreshTtlDays());
    }

    /**
     * 签发 refreshToken，并指定有效期（天）。
     *
     * <p>「记住我」不延长 refreshToken 的有效期上限 —— 它本来就按最长（7 天）签发，
     * 勾与不勾的差别只在 **accessToken** 是否也延长到 7 天（见
     * {@code JwtService.issueAccessToken(user, ttlSeconds)}）。
     * 保留这个方法是为了下一阶段「不记住就只给 1 天」这类策略调整不必改签名。
     */
    public String issue(Long userId, String jti, long ttlDays) {
        String token = randomToken();
        Duration ttl = Duration.ofDays(ttlDays);
        redis.opsForValue().set(TOKEN_KEY + token, userId + "|" + jti, ttl);
        // 反查集合：G1-8「改密后全部失效」靠它，不能省
        String userKey = USER_KEY + userId;
        redis.opsForSet().add(userKey, token);
        redis.expire(userKey, ttl);
        return token;
    }

    /** 解析 refreshToken 的归属用户；无效 / 已过期 / 已撤销返回 {@code null}。 */
    public Long resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String value = redis.opsForValue().get(TOKEN_KEY + token);
        if (value == null) {
            return null;
        }
        int bar = value.indexOf('|');
        String rawUserId = bar > 0 ? value.substring(0, bar) : value;
        try {
            return Long.valueOf(rawUserId);
        } catch (NumberFormatException ex) {
            log.warn("refreshToken 载荷格式异常：{}", value);
            return null;
        }
    }

    /** 撤销单个 refreshToken（登出）。 */
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String value = redis.opsForValue().get(TOKEN_KEY + token);
        redis.delete(TOKEN_KEY + token);
        if (value != null) {
            int bar = value.indexOf('|');
            String userId = bar > 0 ? value.substring(0, bar) : value;
            redis.opsForSet().remove(USER_KEY + userId, token);
        }
        log.debug("refreshToken 已撤销");
    }

    /**
     * 撤销某用户的**全部** refreshToken（G1-8：改密 / 重置密码后连带失效）。
     *
     * @return 实际撤销的条数
     */
    public int revokeAll(Long userId) {
        if (userId == null) {
            return 0;
        }
        String userKey = USER_KEY + userId;
        Set<String> tokens = redis.opsForSet().members(userKey);
        if (tokens == null || tokens.isEmpty()) {
            redis.delete(userKey);
            return 0;
        }
        for (String token : tokens) {
            redis.delete(TOKEN_KEY + token);
        }
        redis.delete(userKey);
        log.info("已撤销用户 {} 的全部 refreshToken，共 {} 条", userId, tokens.size());
        return tokens.size();
    }

    // ==================== 轮换（含并发去重） ====================

    /**
     * 用 refreshToken 换一对新令牌。
     *
     * @param oldToken   客户端提交的 refreshToken
     * @param reissue    真正执行轮换时调用的签发逻辑（由调用方注入，避免本类依赖用户仓储）
     * @return 新的令牌对；refreshToken 无效时返回 {@code null}
     */
    public Rotation rotate(String oldToken, Reissue reissue) {
        if (oldToken == null || oldToken.isBlank()) {
            return null;
        }

        // ① 宽限窗口命中 —— 说明刚刚已经有别的线程刷过了，直接复用，不再轮换
        Rotation grace = readGrace(oldToken);
        if (grace != null) {
            log.debug("命中刷新宽限窗口，复用已有新令牌：userId={}", grace.userId());
            return grace;
        }

        // ② 抢锁，抢到的执行真实轮换
        boolean locked = Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(LOCK_KEY + oldToken, "1", LOCK_TTL));
        if (locked) {
            try {
                // 双重检查：拿到锁的过程中可能已有别的线程写完宽限键
                Rotation again = readGrace(oldToken);
                if (again != null) {
                    return again;
                }
                Long userId = resolveUserId(oldToken);
                if (userId == null) {
                    return null;
                }
                TokenPair pair = reissue.issue(userId);

                // 轮换 = 旧 token 立即作废（refreshToken 一次性使用）
                revoke(oldToken);
                writeGrace(oldToken, userId, pair.refreshToken(), pair.accessToken());
                log.debug("refreshToken 轮换完成：userId={}", userId);
                return new Rotation(userId, pair.refreshToken(), pair.accessToken());
            } finally {
                redis.delete(LOCK_KEY + oldToken);
            }
        }

        // ③ 没抢到锁 —— 轮询宽限键等胜者写完
        long deadline = System.currentTimeMillis() + GRACE_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly();
            Rotation rotation = readGrace(oldToken);
            if (rotation != null) {
                log.debug("等待到并发刷新结果：userId={}", rotation.userId());
                return rotation;
            }
        }
        log.warn("并发刷新等待超时，未取到宽限窗口中的新令牌");
        return null;
    }

    /** 轮换时由调用方提供的签发动作（返回一对新令牌）。 */
    @FunctionalInterface
    public interface Reissue {
        TokenPair issue(Long userId);
    }

    /** 一对新令牌。 */
    public record TokenPair(String refreshToken, String accessToken) {
    }

    // ==================== 内部 ====================

    private void writeGrace(String oldToken, Long userId, String newRefresh, String newAccess) {
        Map<String, String> fields = new HashMap<>(3);
        fields.put(FIELD_USER_ID, String.valueOf(userId));
        fields.put(FIELD_REFRESH, newRefresh);
        fields.put(FIELD_ACCESS, newAccess);
        String key = GRACE_KEY + oldToken;
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, GRACE_TTL);
    }

    private Rotation readGrace(String oldToken) {
        Map<Object, Object> fields = redis.opsForHash().entries(GRACE_KEY + oldToken);
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        Object userId = fields.get(FIELD_USER_ID);
        Object refresh = fields.get(FIELD_REFRESH);
        Object access = fields.get(FIELD_ACCESS);
        if (userId == null || refresh == null || access == null) {
            return null;
        }
        try {
            return new Rotation(Long.valueOf(String.valueOf(userId)),
                    String.valueOf(refresh), String.valueOf(access));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(GRACE_POLL_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String randomToken() {
        return JwtService.newTokenId() + JwtService.newTokenId();
    }
}
