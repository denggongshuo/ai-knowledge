package com.knowledge.ai.dto.kb;

import com.knowledge.ai.core.enums.KbPresets;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * M04（知识库）的出入参（T-400 / T-401 / T-402 / T-403）。
 *
 * <p><b>字段名的事实源是前端</b>（与 {@code dto/admin/AdminDtos} 同一条约定）：
 * 本包字段逐一对齐 {@code ai_knowledge_app/src/types/index.ts} 的
 * {@code KbConfigForm} / {@code SplitPolicy} / {@code TablePolicy} /
 * {@code RetrievalConfig}，以及 {@code src/mock/index.ts} 里对应接口的返回体。
 *
 * <p><b>{@link KbConfigForm} 既是入参也是出参</b>：创建请求体（{@code POST /kb}）与
 * 配置读取返回的 {@code config} 字段是**同一个结构**（前端的 {@code KbConfigForm}
 * 本来就是一体的）。用一个 record 承担两向，判据 3「创建后回读与提交值深等」
 * 才有结构性保证 —— 拆成两个 DTO 就多了一处会各自演化的映射。
 *
 * <p>⚠️ {@code CUSTOM} 不是合法预设 id 之外的坑：{@code splitPolicy.preset} 允许
 * {@code CUSTOM}（用户在预设基础上手改单项后会变成它），所以**不能**用枚举收窄，
 * 只能留字符串 + 服务侧比对 {@link KbPresets}。
 */
public final class KbDtos {

    private KbDtos() {
    }

    // ==================== T-400 列表 ====================

    /**
     * 列表行。只给卡片要用的四项（《06》§1.1）—— 全字段走
     * {@code GET /kb/{id}/config}，列表页不需要。
     */
    public record KbBrief(long id, String name, long docCount, long chunkCount) {
    }

    public record KbPage(List<KbBrief> list, long total) {
    }

    // ==================== 创建 / 配置读取共用的配置结构 ====================

    /** 分片策略（两级流水线：第一级锁结构边界，第二级仅超长兜底）。 */
    public record SplitPolicy(String preset, int chunkSize, int overlap,
                              boolean recursiveSplit, boolean semanticSplit) {
    }

    /** 表格处理策略（六项 + 两档数值）。 */
    public record TablePolicy(boolean atomicTable, boolean dualRepresentation, boolean repeatHeader,
                              boolean mergeCrossPage, boolean ocrTable, int rowLimit, int rowWindow) {
    }

    /** 检索配置。 */
    public record RetrievalConfig(String mode, double recallThreshold, int rerankTopK,
                                  boolean rerankEnabled, double tableWeight, boolean identifierProtect) {
    }

    /**
     * 知识库创建 / 配置表单（创建请求体 = 配置读取的 {@code config} 字段）。
     *
     * <p>结构校验挂在这里，用 {@code @Valid} 触发；而**业务校验**（部门是否存在、
     * 编码是否重复）在 Service —— 它们要查库，且错误码不同（10065 / 10038）。
     */
    public record KbConfigForm(
            @NotBlank(message = "请填写知识库名称")
            @Size(min = 2, max = 40, message = "知识库名称需 2-40 字")
            String name,

            @NotBlank(message = "请填写知识库编码")
            @Pattern(regexp = "^kb_[a-z0-9_]{2,}$",
                    message = "知识库编码需以 kb_ 开头，仅小写字母、数字与下划线")
            String code,

            @NotNull(message = "请选择归属部门")
            Long deptId,

            @NotEmpty(message = "请至少选择一个可见角色")
            List<String> visibleRoles,

            String secretLevel,

            @NotBlank(message = "请选择业务域")
            String businessDomain,

            String description,

            List<String> customTags,

            SplitPolicy splitPolicy,

            TablePolicy tablePolicy,

            RetrievalConfig retrievalConfig) {
    }

    // ==================== T-401 创建结果 ====================

    /**
     * 创建结果。
     *
     * <p>{@code status} 返域内真值 {@code READY}（新库无文档、无需索引），
     * 前端拿到后直接跳上传页；{@code note} 是给用户看的一句话（与 mock 同文案）。
     */
    public record KbCreated(long id, String name, String status, String note) {
    }

    // ==================== T-402 预设 ====================

    /**
     * 只读嵌入模型投影。
     *
     * <p>{@code locked} 恒为 {@code true} —— 这是 {@code G3-2} 的判据点。
     * {@code ModelConfigSpec} 里**没有** {@code locked} 这个概念（全局单例约束是
     * 在产品口径里，不在配置模型里），所以这里是**响应体层面的投影**：
     * 后端刻意不提供"可选嵌入模型列表"的字段，前端据此把该项置灰
     * （B1 计划 §1.3 W2）。
     *
     * @param dimension 向量维度，恒 1024（{@code MilvusConstants.DIMENSION}）
     * @param norm      归一化方式，由 {@code MilvusConstants.METRIC_TYPE} 派生
     */
    public record EmbeddingInfo(String model, int dimension, String norm, boolean locked) {
    }

    /**
     * VL 视觉模型投影。
     *
     * <p>{@code appliedAt} 目前恒为空串（B1 计划 §1.3 W3）：引导配置
     * （{@code ModelConfigSpec}）没有"生效时间"这个字段，{@code sys_model_config}
     * 要到 S6 才接 DB。前端用 {@code v-if} 守卫 —— 不守卫会渲染出「自  起生效」。
     *
     * <p>{@code enabled=false} 时 {@code name} 为空串（不是"未配置"这类文案）：
     * 文案由前端决定，后端只给数据。
     */
    public record VlModelInfo(String name, boolean enabled, String appliedAt) {
    }

    public record KbPresetsResponse(List<KbPresets.Preset> presets,
                                    KbConfigForm defaults,
                                    EmbeddingInfo embedding,
                                    VlModelInfo vlModel) {
    }

    // ==================== T-403 配置读取 ====================
    /**
     * 设置页的完整回显。
     *
     * <p>{@code status} 是**域内真值**（{@code READY} / {@code INDEXING}），
     * {@code statusLabel} 是它的中文展示（已就绪 / 索引中）。
     * 两者并存是 B1 计划 §1.3 W1 的处置：前端的
     * {@code KnowledgeBase['status']} 历史上是 {@code ACTIVE|BUILDING|PAUSED}，
     * 与库里的词表不同 —— 让枚举迁就展示会反过来污染检索链路的判定，
     * 所以保留真值 + 新增派生 label，前端改用它渲染。
     *
     * @param department 归属部门名（由 {@code deptId} 现拼，不落库 —— 部门改名后不漂移）
     * @param visibleRoles 可见角色 **code**（取自 {@code sys_kb_grant} 的 ROLE 行），
     *                     与创建时提交的 {@code visibleRoles} 同口径
     */
    public record KbConfig(long id, String name, String code, String description,
                           String department, long deptId, long docCount, long chunkCount,
                           String status, String statusLabel,
                           String createdAt, String updatedAt,
                           List<String> visibleRoles,
                           KbConfigForm config,
                           EmbeddingInfo embedding,
                           VlModelInfo vlModel,
                           List<KbPresets.Preset> presetOptions) {
    }
}
