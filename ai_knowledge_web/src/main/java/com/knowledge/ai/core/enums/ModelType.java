package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 模型类型（四类）。取值与前端 {@code ModelType} 逐字一致。
 *
 * <p>约束（《11-非功能需求与技术架构》§1.4）：
 * <ul>
 *   <li>{@link #EMBEDDING} —— **全局单例**，BAAI/bge-m3，1024 维，L2 归一化。
 *       不可多实例、不可逐库切换；更换 = 全库重建。列表接口里**不可新增**。</li>
 *   <li>{@link #CHAT} —— 可配置多条，运行时按 id 选用。</li>
 *   <li>{@link #RERANK} —— **可配多条，但全局唯一启用**（所有知识库共用同一条）；
 *       切换即时生效、**不需要重建索引**，只在模型配置页可切。</li>
 *   <li>{@link #VL} —— 图片理解（视觉）模型，配合独立 OCR 链路。
 *       **可配多条，但全局唯一启用**；切换**只影响含图文档**（与嵌入的全库重建区分开）。</li>
 * </ul>
 */
@Getter
public enum ModelType {

    CHAT("对话模型"),
    EMBEDDING("嵌入模型"),
    RERANK("重排模型"),
    VL("视觉模型"),
    ;

    private final String label;

    ModelType(String label) {
        this.label = label;
    }

    /**
     * 是否**只允许存在一条**（"全局单例"）。
     *
     * <p>现只有 {@link #EMBEDDING}：索引期与查询期必须同一个模型，且 Milvus collection
     * 的维度固定 —— 混用会让检索完全失效（《09》§1.2 的硬约束一）。
     * 所以它在配置页**不可新增**、也**不可切换**（切换 = 换模型 = 全库重建，那是 `T-906`）。
     */
    public boolean isSingleton() {
        return this == EMBEDDING;
    }

    /**
     * 是否允许在「模型配置」页新增多条。
     *
     * <p>与 {@link #isSingleton()} 是互补的两个问法，但**刻意都留着**：
     * 一个问的是"能不能多配几条"，一个问的是"它是不是全系统唯一的那一个"。
     * 未来若出现"可多条但全系统只生效一条"以外的形态，两者就会分家。
     *
     * <p>🔴 **2026-09-18 订正（`S6-M09-A`）**：本方法原为 {@code CHAT || VL}，**漏了 {@code RERANK}**；
     * 而 {@link #isSingleton()} 原为 {@code EMBEDDING || RERANK}，等于把「只允许一条行」
     * 与「只允许一条启用」压成了一对互补的真假值 —— 于是「重排可配多条、但同时只启用一条」
     * 这个真实形态**两边都表达不了**。
     *
     * <p>口径以**需求**为准（`09` §1.2 的定位表与 `12` §1 两处一致）：
     * **对话 / 重排 / VL 都可配多条，只有嵌入写死单例**。
     * ⚠️ 这与 `plan/汇总-归档.md` §C「接口裁剪」那一节说的「三类都不可新增」**矛盾** ——
     * 那节是 2026-09-15 的推断，用户 2026-09-18 裁定以需求为准，该节已回填订正。
     *
     * <p>⚠️ 「同类型**至多一条启用**」（《09》§1.6）是**四类通用**的，不由本方法表达 ——
     * 它由 `sys_model_config` 的 `uk_model_active` 唯一索引在库层保证。
     */
    public boolean allowsMultiple() {
        return !isSingleton();
    }
}
