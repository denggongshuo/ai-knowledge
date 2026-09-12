package com.knowledge.ai.core.security;

import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JWT 签发与解析（T-007）。
 *
 * <p>算法固定 HS256（对称密钥）。claims 布局：
 * <pre>
 *   sub      用户 id
 *   jti      令牌唯一 id（注销时进 Redis 黑名单）
 *   username 登录账号
 *   name     真实姓名
 *   dept     部门 id
 *   roles    角色编码数组
 *   perms    原子权限码数组（含 "*" 表示超管）
 *   scope    数据范围枚举名
 *   secret   密级上限枚举名
 * </pre>
 *
 * <p>异常语义：过期 → {@link ErrorCode#TOKEN_EXPIRED}（10011）；
 * 其它（签名不对 / 格式错 / 篡改）→ {@link ErrorCode#TOKEN_INVALID}（10012）。
 * 两者都对应 **HTTP 401**，前端会清 token 并跳登录（《10》§13）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    static final String CLAIM_USERNAME = "username";
    static final String CLAIM_REAL_NAME = "name";
    static final String CLAIM_DEPT = "dept";
    static final String CLAIM_ROLES = "roles";
    static final String CLAIM_PERMS = "perms";
    static final String CLAIM_SCOPE = "scope";
    static final String CLAIM_SECRET = "secret";

    /** HS256 要求的最小密钥长度（字节）。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 JWT 密钥：请在 ai_knowledge_web/.env 中配置 JWT_SECRET（至少 32 字符随机串）");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET 过短（" + bytes.length + " 字节），HS256 要求至少 " + MIN_SECRET_BYTES + " 字节");
        }
        this.signingKey = Keys.hmacShaKeyFor(bytes);
    }

    // ==================== 签发 ====================

    /** 签发 accessToken。 */
    public String issueAccessToken(LoginUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(properties.getAccessTtlMinutes() * 60);
        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .id(user.getTokenId() != null ? user.getTokenId() : newTokenId())
                .issuer(properties.getIssuer())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_REAL_NAME, user.getRealName())
                .claim(CLAIM_DEPT, user.getDeptId())
                .claim(CLAIM_ROLES, List.copyOf(user.getRoleCodes()))
                .claim(CLAIM_PERMS, List.copyOf(user.getPermissions()))
                .claim(CLAIM_SCOPE, user.getDataScope().name())
                .claim(CLAIM_SECRET, user.getSecretLevelUpper().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /** accessToken 剩余有效秒数，用于响应体的 {@code expiresIn}。 */
    public long accessTtlSeconds() {
        return properties.getAccessTtlMinutes() * 60;
    }

    public static String newTokenId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ==================== 解析 ====================

    /**
     * 解析并校验 accessToken。
     *
     * @throws BizException 10011 已过期 / 10012 无效
     */
    public LoginUser parseAccessToken(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw BizException.of(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT 解析失败：{}", ex.getMessage());
            throw BizException.of(ErrorCode.TOKEN_INVALID);
        }

        Set<String> roles = toStringSet(claims.get(CLAIM_ROLES));
        Set<String> perms = toStringSet(claims.get(CLAIM_PERMS));
        boolean superAdmin = perms.contains(PermissionCode.WILDCARD);

        return LoginUser.builder()
                .userId(toLong(claims.getSubject()))
                .tokenId(claims.getId())
                .username(claims.get(CLAIM_USERNAME, String.class))
                .realName(claims.get(CLAIM_REAL_NAME, String.class))
                .deptId(toLong(claims.get(CLAIM_DEPT)))
                .roleCodes(roles)
                .permissions(perms)
                .superAdmin(superAdmin)
                .dataScope(parseEnum(claims.get(CLAIM_SCOPE, String.class), DataScope.class, DataScope.SELF))
                .secretLevelUpper(parseEnum(claims.get(CLAIM_SECRET, String.class), SecretLevel.class, SecretLevel.PUBLIC))
                .build();
    }

    // ==================== 内部工具 ====================

    @SuppressWarnings("unchecked")
    private Set<String> toStringSet(Object raw) {
        if (raw instanceof List<?> list) {
            Set<String> result = new HashSet<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return Set.of();
    }

    private Long toLong(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private <E extends Enum<E>> E parseEnum(String raw, Class<E> type, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
