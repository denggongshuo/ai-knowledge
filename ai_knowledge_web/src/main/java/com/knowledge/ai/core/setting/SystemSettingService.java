package com.knowledge.ai.core.setting;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统参数读取（`sys_setting` · `S5-M07-A` 步 9）。
 *
 * <h3>为什么一次读一批，而不是逐个键查</h3>
 * <p>检索侧一次要拿三个阈值（未命中线 / 降警线 / 默认 rerankTopK）。逐个查就是三次
 * 往返，而这三次的数据在同一个 KV 表里、生命周期完全一致。
 * {@link #snapshot()} 一次取回**本类关心的那几个键**，调用方再按需取值 ——
 * 顺带把"三个值来自同一时刻"这件事变成结构性事实，而不是靠"三次查询之间没人改配置"的运气。
 *
 * <h3>缺行为什么兜底而不是抛</h3>
 * <p>见 {@link SystemSettings} 的类注释。这里补一条：兜底**必须有痕**。
 * 每次兜底都打 {@code warn}，并在返回值里给出"这个值是默认的还是读到的"
 * （{@link Snapshot#sourceOf}），这样取证时能分辨 ——
 * 否则一次"种子没种上"的部署会表现为"阈值就是 0.35 呀，没问题"。
 */
@Slf4j
@Service
public class SystemSettingService {

    /** 本类关心的键 —— 也是 {@link #snapshot()} 的查询范围、{@link #updateBoolean} 的写入范围。 */
    private static final List<String> KEYS = List.of(
            SystemSettings.RETRIEVAL_RECALL_THRESHOLD,
            SystemSettings.RETRIEVAL_MISS_THRESHOLD,
            SystemSettings.RETRIEVAL_LOW_SCORE_WARN,
            SystemSettings.RETRIEVAL_RERANK_TOP_K,
            SystemSettings.RETRIEVAL_DEGRADE_DURING_REINDEX,
            SystemSettings.RETRIEVAL_GRAPH_ENABLED);

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 一批系统参数（同一时刻的快照）。
     *
     * <p>值是**字符串原样**：{@code sys_setting.value_type} 有五种，
     * 在这里统一转型会让"这个键本该是 DECIMAL 却存了 'abc'"从一条可定位的解析日志
     * 变成一次启动期崩溃。转型放在 {@link #decimal} / {@link #integer}，
     * 失败时**回退到默认值并留 warn**（与缺行同一条口径）。
     */
    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select setting_key, setting_value from sys_setting where setting_key in (:keys)")
                .setParameter("keys", KEYS)
                .getResultList();

        Map<String, String> values = new HashMap<>(KEYS.size());
        for (Object[] row : rows) {
            values.put(String.valueOf(row[0]), (String) row[1]);
        }
        for (String key : KEYS) {
            if (!values.containsKey(key)) {
                log.warn("系统参数缺失，本次使用兜底默认值：key={}（请确认 V2 种子是否已执行）", key);
            }
        }
        return new Snapshot(values);
    }

    /**
     * 写一个**布尔型**系统参数（`T-C03` · 本仓第一个 {@code sys_setting} 写入者）。
     *
     * <h3>为什么只开布尔、只开 {@code KEYS} 里的键</h3>
     * <p>这是唯一一个由**页面**驱动的写入口。放开成"任意键任意值"意味着
     * {@code retrieval.embedding-dimension}（{@code editable=0}，改了等于全库重建向量）
     * 也能被一个 PUT 打穿 —— 那是个不可逆动作，需求 `D-4`/`R-8` 明令锁死。
     * 收窄到"本类认识的键 + 布尔类型"之后，能写坏的面只剩一个二值开关，
     * 而它**本来就是要被写的**。参数化写法也顺带堵死了注入。
     *
     * <p>为什么不在这里做 I-11 那类跨行校验：布尔键没有跨行约束。
     * 将来若要开 DECIMAL 键，校验必须加在这里 —— KV 表表达不了跨行约束
     * （见 `V1` 建表注释与 `T-014`），那是唯一能拦的地方。
     *
     * @param key   必须是 {@link #KEYS} 中的键
     * @param value 落库为字面量 {@code "true"} / {@code "false"} —— 与
     *              {@link Snapshot#bool} 的取值域**恰好对齐**（它只认这两个串）
     * @return 受影响行数（0 表示键不存在，调用方应视为失败）
     */
    @Transactional
    public int updateBoolean(String key, boolean value) {
        if (!KEYS.contains(key)) {
            throw new IllegalArgumentException("拒绝写入未登记的系统参数：" + key);
        }
        int affected = entityManager.createNativeQuery(
                        "update sys_setting set setting_value = :value, updated_at = now()"
                                + " where setting_key = :key")
                .setParameter("value", Boolean.toString(value))
                .setParameter("key", key)
                .executeUpdate();
        if (affected == 0) {
            log.warn("系统参数写入影响 0 行（键不存在？）：key={} —— 检查对应迁移的种子是否已执行", key);
        }
        return affected;
    }

    /**
     * 一批系统参数 + 取值口径。
     *
     * @param values 键 → 原样字符串（缺行时该键不出现）
     */
    public record Snapshot(Map<String, String> values) {

        /** 十进制取值；缺行或解析失败 → {@code fallback} 并留 warn。 */
        public double decimal(String key, double fallback) {
            String raw = values.get(key);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException ex) {
                log.warn("系统参数不是合法的十进制数，改用默认值：key={} 实际={} 默认={}", key, raw, fallback);
                return fallback;
            }
        }

        /** 整数取值；缺行或解析失败 → {@code fallback} 并留 warn。 */
        public int integer(String key, int fallback) {
            String raw = values.get(key);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                log.warn("系统参数不是合法的整数，改用默认值：key={} 实际={} 默认={}", key, raw, fallback);
                return fallback;
            }
        }

        /** 供取证：某键的值是**读到的**还是**兜底的**。 */
        public Optional<String> sourceOf(String key) {
            return Optional.ofNullable(values.get(key));
        }

        /**
         * 布尔取值；缺行或认不出来 → {@code fallback} 并留 warn。
         *
         * <p>取值域**刻意只认 {@code "true"} / {@code "false"}**（忽略大小写与首尾空白）：
         * 不认 {@code 1/0}、不认 {@code on/off}。理由是这一列是 {@code VARCHAR}，
         * 宽松解析会让"有人手写了 {@code yes}"变成**看起来生效了但实际走了 fallback** ——
         * 而 fallback 恰好就是 {@code true} 时，这个错永远不会被发现。
         * 认不出来的当场留 warn，是这里唯一能自证的方式。
         */
        public boolean bool(String key, boolean fallback) {
            String raw = values.get(key);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            String trimmed = raw.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return true;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return false;
            }
            log.warn("系统参数不是合法的布尔值（只认 true/false），改用默认值：key={} 实际={} 默认={}",
                    key, raw, fallback);
            return fallback;
        }
    }
}
