package com.knowledge.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计单号生成器：{@code LOG-20260911-0008821}。
 *
 * <p>用 Redis {@code INCR} 按天取序号，保证**多实例部署下也不重号**；
 * Redis 不可用时降级为进程内的 {@link AtomicLong}
 * （可能重号 → 落库时唯一索引会挡住，切面已保证写日志失败不影响业务）。
 *
 * <p>为什么不用数据库自增 id 拼单号：单号要在**落库前**确定，
 * 而且对外可读（用户报障时会念这个号），不该依赖数据库行 id。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogNoGenerator {

    private static final String PREFIX = "LOG-";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 序号宽度，不足左侧补 0。 */
    private static final int SEQ_WIDTH = 7;

    private static final String REDIS_KEY_PREFIX = "ak:audit:seq:";

    private final StringRedisTemplate redisTemplate;

    /** Redis 不可用时的降级计数器。 */
    private final AtomicLong fallback = new AtomicLong(0);

    public String next(LocalDate date) {
        long seq = nextSequence(date);
        return PREFIX + date.format(DATE) + "-" + pad(seq);
    }

    private long nextSequence(LocalDate date) {
        try {
            Long value = redisTemplate.opsForValue().increment(REDIS_KEY_PREFIX + date.format(DATE));
            if (value != null) {
                return value;
            }
        } catch (Exception ex) {
            log.warn("审计序号取用 Redis 失败，降级为进程内计数：{}", ex.getMessage());
        }
        return fallback.incrementAndGet();
    }

    private String pad(long value) {
        String text = Long.toString(value);
        if (text.length() >= SEQ_WIDTH) {
            return text;
        }
        return "0".repeat(SEQ_WIDTH - text.length()) + text;
    }
}
