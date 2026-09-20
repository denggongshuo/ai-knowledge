package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 密级。取值与前端 {@code SecretLevel} 逐字一致。
 *
 * <p>用于检索侧的安全过滤：用户的密级上限低于分片密级时，该分片不参与召回。
 * 顺序由低到高：{@code PUBLIC < INTERNAL < CONFIDENTIAL < SECRET}。
 *
 * <p><b>注意</b>：**密级的事实源是 MySQL**（{@code sys_kb.secret_level} 与
 * {@code sys_document.secret_level}），检索时由业务侧算好范围再把 {@code doc_id} 交给
 * Milvus 做标量过滤（《11-非功能需求与技术架构》§2.1 `I-9`）。
 * ⚠️ Milvus 那个同名的标量列**确实存在**（{@code MilvusChunkWriter} 写入，`11` §2.1 也把
 * {@code secret_level} 列为"过滤必需"的低基数字段），但它是**写入时的快照** ⇒
 * **检索时不读它**：按它过滤会让「提高密级」必须重建索引才生效，而需求 `06` §6.7 明写
 * 「属性修改后**立即生效，无需重建索引**」。详见 {@code RetrievalScope} 的类注释。
 */
@Getter
public enum SecretLevel {

    PUBLIC("公开", 1),
    INTERNAL("内部", 2),
    CONFIDENTIAL("机密", 3),
    SECRET("绝密", 4),
    ;

    private final String label;

    /** 密级高度，用于比较。 */
    private final int rank;

    SecretLevel(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    /** 当前密级是否不高于（即允许访问）给定上限。 */
    public boolean within(SecretLevel upperBound) {
        if (upperBound == null) {
            return false;
        }
        return this.rank <= upperBound.rank;
    }

    /**
     * 取两个密级中较高的一个（`I-6`：**生效密级 = max(文档密级, 库密级)** · `S7-M06-B`）。
     *
     * <p>{@code null} 的语义是**未设置 / 未覆盖**：一个为 {@code null} 时返回另一个，
     * 两个都是 {@code null} 时返回 {@code null}（调用方自己决定怎么报"两边都没有"）。
     *
     * <p>⚠️ 这是**唯一**一处比密级高低的 Java 落点，**不要**把它挪进 SQL ——
     * 密级名按字母序与密级序是**错位**的（{@code greatest('INTERNAL','CONFIDENTIAL')}
     * 返回 {@code 'INTERNAL'}），而那种 bug 的现象是"**有人看得见绝密**"而不是报错。
     */
    public static SecretLevel max(SecretLevel a, SecretLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.rank >= b.rank ? a : b;
    }
}
