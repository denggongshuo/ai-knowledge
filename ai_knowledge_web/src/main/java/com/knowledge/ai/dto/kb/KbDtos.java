package com.knowledge.ai.dto.kb;

import com.knowledge.ai.core.enums.KbPresets;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * M04（知识库）的出入参（T-400 ~ T-405 / T-408 / T-411）。
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
     * @param reindexRequired 配置已改、索引未重建（T-404 置位，S4 的 T-406 清除）。
     *                        <b>不并入 {@code status}</b>：那是"索引过程"，这是"配置条件"，
     *                        两者可同时成立（正在索引时又改了一次配置）。详见 {@code SysKb} 该字段注释
     */
    public record KbConfig(long id, String name, String code, String description,
                           String department, long deptId, long docCount, long chunkCount,
                           String status, String statusLabel,
                           String createdAt, String updatedAt,
                           boolean reindexRequired,
                           List<String> visibleRoles,
                           KbConfigForm config,
                           EmbeddingInfo embedding,
                           VlModelInfo vlModel,
                           List<KbPresets.Preset> presetOptions) {
    }

    // ==================== T-404 保存配置 ====================

    /**
     * 保存 / 预估的请求体：把 {@link KbConfigForm} 包一层 {@code config}。
     *
     * <p><b>为什么要有这个壳，而不是直接收表单</b>：前端就是这么发的 ——
     * {@code api/index.ts} 的 {@code saveConfig} 是 {@code http.put(url, { config })}，
     * mock 也读 {@code ctx.body.config}。按本仓「字段名与形状的事实源是前端」的约定，
     * 后端跟随它、而不是让前端改调用点。多这一层的代价是"少一次前端改动"，
     * 收益是**同一个表单结构在三个接口上完全一致**（创建 / 保存 / 预估）。
     *
     * <p>⚠️ {@code @Valid} **必须挂在组件上**：不挂的话外层 {@code @Valid} 不会级联进
     * {@code config}，于是"名称 1 个字"这类校验会在保存接口上**静默失效** ——
     * 而创建接口拦得住。同一份表单两个接口口径不同，是最难被发现的那种。
     */
    public record KbConfigRequest(@Valid KbConfigForm config) {
    }

    /**
     * 保存结果。
     *
     * <p><b>{@code needReindex} 是服务端算的，不是把请求里的东西回显</b>：
     * 服务端拿"库里的旧值"与"提交的新值"逐字段比对，命中重建字段集才置 true
     * （字段集口径见 {@code KbService} 的说明）。
     *
     * <p>⚠️ <b>前端当前的判定是它自己那份本地 diff</b>（{@code KbDetailView}
     * 的 {@code needReindex} computed，保存后直接丢弃本响应的该字段）。
     * 两者必须同源，否则会出现"页面说不用重建、库里标着脏"这种谁也看不出来的分叉
     * —— 所以本批刻意让服务端字段集**逐字对齐**前端，而不是"按我认为对的来"
     * （B2 计划 §3.1 步 4；其中 4 个检索期参数是否真该触发重建，已登记为遗留）。
     *
     * @param needReindex 本次改动是否使索引失效（同时已落 {@code sys_kb.reindex_required}）
     */
    public record KbConfigSaved(boolean saved, boolean needReindex, String updatedAt) {
    }

    // ==================== T-405 影响预估 ====================

    /**
     * 重建影响预估。
     *
     * <p><b>请求体是完整的 {@code KbConfigForm}，不是 diff 列表</b>：
     * 前端原本按 {@code {changes:[{label,before,after}]}} 发，但那一份只有**展示后的字符串**
     * （"开启"/"通用文档"/"—"），服务端拿它反推 {@code chunkSize} 得先解析中文，
     * 属于注定会漂移的做法。改成发完整表单后，服务端用**与保存完全同一段** diff 代码
     * 算出"哪些字段变了"，估算才和真实保存一致（B2 计划 §3.1 步 5）。
     *
     * @param chunkAfter {@code chunkBefore} 按新 chunkSize/overlap 折算的**估计值**，
     *                   不是精确值 —— G3-4 要求它与重建后的真实分片数同量级（±10%），
     *                   该判据待 S4 有重建能力后合并验收
     */
    public record ReindexImpact(long docCount, long chunkBefore, long chunkAfter,
                                long estimateMinutes, long estimateTokens) {
    }

    // ==================== T-408 存储配额 ====================

    /**
     * 全站配额卡（消费方 {@code DocDownloadView.vue:297}）。
     *
     * <p>{@code used} 是**带单位的字符串**（如 {@code "2.4 GB"}）而不是字节数 ——
     * 前端直接渲染该串，格式化口径归后端，这是既有契约（{@code types/index.ts} 的
     * {@code StorageQuota.used: string}）。
     *
     * @param monthTokens 本月全站 tokens。**当前恒 0**：全项目没有 token 用量表
     *                    （用量统计是 S6 的 {@code T-905}），来源缺失已登记（B2 计划 §1 W-a）
     */
    public record StorageQuota(String used, long kbCount, long docCount, long chunkCount,
                               long monthTokens) {
    }

    // ==================== T-411 只读解析配置 ====================

    /**
     * 解析配置的一行（标签 + 值 + 三个布尔标记）。
     *
     * <p>布尔项用 {@code boolean} 而不是 {@code Boolean}：前端三个标记都是真值判断
     * （{@code v-if="g.locked"} / {@code :class="{ locked: g.locked }"}，
     * {@code KbUploadView.vue:489-511}），{@code false} 与 {@code undefined} 行为一致；
     * 而 {@code Boolean} 序列化出来是 {@code null}，多一个没必要的三态。
     *
     * @param locked  锁定不可改（如整表原子化、嵌入模型）
     * @param warning 需提示风险（如"语义切分仅兜底"）
     * @param enabled 索引增强项是否开启（只有 image / enhance 两栏用）
     */
    public record ParseConfigGroup(String label, String value,
                                   boolean locked, boolean warning, boolean enabled) {
    }

    /**
     * 上传页的只读解析配置（四栏）。
     *
     * <p><b>它是"知识库配置 + 系统常量"的投影，不是独立的配置实体</b>：
     * {@code split} / {@code table} 两栏来自该库的 {@code *_policy_json} 与嵌入模型，
     * {@code image} / {@code enhance} 两栏是全站固定口径（图片处理、标题路径注入、
     * HyQ 等不按库差异化）。所以它**没有写侧**，也不该有 —— 改配置走
     * {@code PUT /kb/{id}/config}。
     */
    public record ParseConfigView(List<ParseConfigGroup> split, List<ParseConfigGroup> table,
                                  List<ParseConfigGroup> image, List<ParseConfigGroup> enhance) {
    }
}
