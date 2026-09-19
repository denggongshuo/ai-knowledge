package com.knowledge.ai.controller;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.dto.system.ModelConfigDtos;
import com.knowledge.ai.service.ModelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M09 模型配置（`T-900`~`T-903`）—— `/api/system/model/*`。
 *
 * <h3>为什么另起一个 Controller，而不是并进 {@code SystemController}</h3>
 * <p>{@code SystemController} 的类注释写死了「本类**不预铺**那些映射 —— 空的占位方法会让人以为
 * 接口已经在了」。现在它们真的来了：六个映射、四个 🚩 高危写动作、四类模型的启用切换。
 * 并进那边会让那个只有一条映射的"只读字典"类变成大杂烩。故另起本类，
 * 而 {@code SystemController} 保持它原样的克制。
 *
 * <h3>权限码用的是**早已种子的** {@code system:model}</h3>
 * <p>{@code PermissionCode.SYSTEM_MODEL}（「模型配置」，系统管理）与 `V2` 的种子都在，
 * 但**没有任何角色被授予它** —— 也就是说它事实上是**超管专属**
 * （`SUPER_ADMIN` 的通配 `*` 通吃）。这是本批的**有意选择**而非遗漏：
 * 《09》§1.2 把重排/VL 定为「系统级基础设施」，切换它属于运维动作。
 * ⚠️ 但这条边界**至今没有被任何角色证伪过**（没有角色"有它但应该没有"或反之），
 * 与 `#153` 的 `qa:history:search` 是同一种形态，已登记 `S6-M09-A` §9。
 *
 * <h3>审计点为什么挂在这一层</h3>
 * <p>与 {@code ChatController} 的导出接口同一条理由：审计的 {@code operator} 与 {@code apiPath}
 * 取自**请求上下文**，打在 Service 上会落成空值 —— 而"谁把生产环境的对话模型换掉了"
 * 正是这四条日志唯一的价值。
 *
 * <p>四个动作全部登记为 {@code MODEL_CONFIG_CHANGE} / `HIGH`：《05》§4.3 的第 4 类，
 * 且 {@code AuditRiskPolicy} 的类注释把「四类模型的**新增、编辑、删除、启用或切换**」
 * 一并划入这一类。
 */
@Slf4j
@RestController
@RequestMapping("/api/system/model")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    /**
     * 四类模型列表（`T-900`）。
     *
     * <p>返回 {@code {stats, chat[], embedding, rerank[], vl[]}} —— 形状见
     * {@link ModelConfigDtos.ConfigResponse}。⚠️ {@code embedding} 是**单条对象**。
     */
    @GetMapping("/config")
    @RequiresPerm("system:model")
    public ApiResponse<ModelConfigDtos.ConfigResponse> config() {
        return ApiResponse.ok(modelConfigService.list());
    }

    /**
     * 问答页模型选项（`T-904`）。
     *
     * <p>🔴 **权限码是 {@code qa:ask}，不是 {@code system:model}** —— 本类其余六个端点
     * 都是后者，唯独这个不是，而且这个差别是**有意的、必须的**：
     * 消费方是问答页的切换器（`ChatView.vue:29` 已在调），而 {@code system:model}
     * 在 `V2` 的授权矩阵里只有 {@code SUPER_ADMIN} 有（本批开工前逐行查过
     * {@code sys_role_permission}）。挂错的表现是**普通用户在问答页根本开不了切换器**
     * —— 而它在用例层不会红（用例拿的是超管 token）。
     * 与 `S5-M08-C` 的 {@code qa:feedback} 同族：权限码名字像、语义不同。
     *
     * <p>⚠️ 不写审计点：读接口，且 `AuditRiskPolicy` 里「模型配置」那一行的落点是
     * **四个写动作**（新增/编辑/删除/启用）。用户看了一眼下拉框不需要进审计页。
     */
    @GetMapping("/options")
    @RequiresPerm("qa:ask")
    public ApiResponse<ModelConfigDtos.OptionList> options(
            @RequestParam(required = false) ModelType type) {
        return ApiResponse.ok(modelConfigService.options(type));
    }

    /**
     * 用量统计（`T-905`），默认本月。
     *
     * <p>⚠️ 页面上展示的是**费用估算**，不是账单（`09` §1.13.1 的「诚实口径」）。
     * 本接口返回的 {@code cost} 有**三态**（可估算 / 0 = 不计费 / null = 未配单价），
     * 前端必须区分 —— 见 {@link ModelConfigDtos.ModelUsageItem#cost}。
     *
     * <p>权限 {@code system:model}：这确实是模型配置页的读接口，超管专属是对的
     * （与上面的 {@code options} 恰好相反 —— 那条要给普通用户用）。
     */
    @GetMapping("/usage")
    @RequiresPerm("system:model")
    public ApiResponse<ModelConfigDtos.UsageResponse> usage() {
        return ApiResponse.ok(modelConfigService.usage());
    }

    /**
     * 新增一条（`T-901`）—— 🚩 高危。
     *
     * <p>嵌入不可新增（全局单例，`G6-2`）。新增的行**一律未启用且未测试**：
     * 启用要单独走 {@code activate}，那里有「先测通过」的门槛（`G6-3`）。
     * 若允许在请求体里带 `isActive`，就等于绕过了那道门槛。
     */
    @PostMapping("/config")
    @RequiresPerm("system:model")
    @AuditLog(module = AuditModule.SYSTEM, action = "新增模型配置", risk = RiskLevel.HIGH,
            target = "'模型配置#' + #result.data.id")
    public ApiResponse<ModelConfigDtos.SavedResult> create(@RequestBody ModelConfigDtos.SaveRequest request) {
        Long userId = LoginUserContext.requireUserId();
        return ApiResponse.ok(modelConfigService.create(request, userId));
    }

    /**
     * 编辑一条（`T-901`）—— 🚩 高危。
     *
     * <p>改了 Base URL / 模型名 → **原测试结论失效**，返回 `needRetest = true`，
     * 前端据此把该行的「启用」按钮置灰（《09》§1.6）。
     */
    @PutMapping("/config/{id}")
    @RequiresPerm("system:model")
    @AuditLog(module = AuditModule.SYSTEM, action = "编辑模型配置", risk = RiskLevel.HIGH, target = "#id")
    public ApiResponse<ModelConfigDtos.UpdatedResult> update(@PathVariable("id") Long id,
                                                             @RequestBody ModelConfigDtos.SaveRequest request) {
        return ApiResponse.ok(modelConfigService.update(id, request));
    }

    /**
     * 删除一条（`T-901`）—— 🚩 高危。
     *
     * <p>**当前启用的不可删**：删掉它会让该类当场没有可用模型（问答/检索/解析全线报
     * "模型未配置"），而这与"删一条配置"在用户眼里是同一件事。
     */
    @DeleteMapping("/config/{id}")
    @RequiresPerm("system:model")
    @AuditLog(module = AuditModule.SYSTEM, action = "删除模型配置", risk = RiskLevel.HIGH, target = "#id")
    public ApiResponse<ModelConfigDtos.DeletedResult> delete(@PathVariable("id") Long id) {
        return ApiResponse.ok(modelConfigService.delete(id));
    }

    /**
     * 切换启用（`T-902`）—— 🚩 高危。
     *
     * <p>约束四道：嵌入直接拒 · 必须已通过连接测试 · 同类型其余自动停用 · 失效客户端缓存。
     * 响应里的 {@code needRebuildImageDocs} 告诉前端"换 VL 后需要重建含图文档"（`T-908`）。
     */
    @PostMapping("/config/{id}/activate")
    @RequiresPerm("system:model")
    @AuditLog(module = AuditModule.SYSTEM, action = "启用模型配置", risk = RiskLevel.HIGH, target = "#id")
    public ApiResponse<ModelConfigDtos.ActivateResult> activate(@PathVariable("id") Long id) {
        return ApiResponse.ok(modelConfigService.activate(id));
    }

    /**
     * 连接测试（`T-903`）—— **按待测的那一行真发一次请求**。
     *
     * <p>⚠️ 它**不记审计**：测试是只读探测，不改任何配置、不影响任何行为
     * （它只写 {@code last_test_*} 这三个结论列）。把只读动作记成 🚩 高危会让那几条
     * 真正的变更日志被淹没 —— 与"单文件浏览式下载不计入高危"是同一条取舍。
     */
    @PostMapping("/config/{id}/test")
    @RequiresPerm("system:model")
    public ApiResponse<ModelConfigDtos.TestResult> test(@PathVariable("id") Long id) {
        return ApiResponse.ok(modelConfigService.test(id));
    }

    // ==================================================================
    // T-906 / T-908 —— 「换模型」线：本版本**只保留切换**，换 + 重建如实回「不支持」
    //
    // 这四个端点在本版本**只为了说一句话而存在**。它们不是占位、不是待开发 ——
    // 而是**产品口径的落地点**（用户 2026-09-19 裁定，见 `plan/2026-09-19-S6-M09-C.md`）。
    //
    // 🔴 为什么四个一起拒（而不是"反正 GET 是只读的，就把真数字算给它"）：
    //   两个 `*-impact` 唯一的消费方是「换 + 重建」的确认弹窗。**返回真数字会让
    //   「不支持」看起来像「能换」** —— 用户会一路填到勾选「我已知晓影响范围」
    //   才撞墙，而那时他已经认真读过一遍假的耗时与 token 估算了。
    //   「不给一条走不通的表单」比「表单填得动但提交必失败」诚实。
    //
    // 🔴 为什么不做（免得下一个人重新挖一遍，本仓已因此返工 6 次）：
    //   ① **换嵌入**：跨维度换模型在当前实现下**写进去就抛** ——
    //      `MilvusChunkWriter` 把维度写成了编译期常量（`private static final int DIMENSION
    //      = MilvusConstants.DIMENSION`），而维度漂移的处置在
    //      `MilvusCollectionInitializer#verifyDimension` 里写死为**人工**
    //      `DROP COLLECTION` + 重启 + 全量重建。做通要连带 维度去常量 + 库内维度持久化列
    //      + collection 整体重建 + **全站检索中断**（需求自己写的「旧数据不会保留」）。
    //   ② **换 VL + 重建**：成本结构不对 —— 重建要逐张真调 VL（实测 40~60 秒/张），
    //      而本仓**没有多篇含图文档的真实语料** ⇒ 交付了也**没人能证伪**"重建后确实更好"。
    //   ③ 🔴 **这个入口现在没有要处理的对象** —— 《09》§1.12 把换 VL 的价值定在
    //      「已入库文档的图片描述不会自动更新」，而库里 `sys_chunk` 全 0、
    //      Milvus 向量数 0 ⇒ 没有一份"旧模型产的描述"存在。
    //
    // ⚠️ 「启用切换」（`activate`）**不在这里面** —— 它不改已入库数据，
    //    重排切换按《09》§1.11 本就不需要重建索引，且它是已过 `G6-4` 的能力。
    //    收紧它等于**回退一个已验收的功能**。
    //
    // ⚠️ 四个都**不写 `@AuditLog`**：拒绝不是"谁改了什么"，记进审计只会把真正的高危日志淹掉
    //    （与 `test` 那条「只读探测不记审计」同一条取舍）。
    // ==================================================================

    /**
     * {@code GET /system/model/embedding-impact} —— **本版本不支持**换嵌入模型。
     *
     * <p>契约形状见 `10-接口约定汇总.md` §14（`{kbCount, docCount, chunkCount, estimateMinutes,
     * extraTokens}`）与 mock（`ai_knowledge_app/src/mock/index.ts:1428`）。本版本**刻意不返回
     * 那五个数**，理由见本段上方注释。
     */
    @GetMapping("/embedding-impact")
    @RequiresPerm("system:model")
    public ApiResponse<Void> embeddingImpact() {
        throw unsupportedEmbeddingSwitch();
    }

    /**
     * {@code POST /system/model/change-embedding} —— **本版本不支持**换嵌入模型。
     *
     * <p>⚠️ **刻意不声明 `@RequestBody`**：请求体（`{model, dimension, rebuildMode}`）本版本
     * 一律不看。若声明成具体 DTO，一个字段类型不对的请求会先被 Bean Validation 拦成
     * 「参数校验失败」—— 而真正该告诉用户的是「本版本不支持」，与请求体写得对不对无关。
     *
     * <p>🔴 **不落 `sys_async_task`、不返回 `taskId`**：返回一个假的任务号等于
     * 「假启动」—— 用户会去通知中心等一个永远不会来的结果。
     */
    @PostMapping("/change-embedding")
    @RequiresPerm("system:model")
    public ApiResponse<Void> changeEmbedding() {
        throw unsupportedEmbeddingSwitch();
    }

    /**
     * {@code GET /system/model/vl-impact} —— **本版本不支持**重建含图文档。
     *
     * <p>契约形状见 `10-接口约定汇总.md` §14（`{kbCount, imageDocCount, imageCount,
     * estimateMinutes, extraTokens}`）。与上面那个同理：**返回真数字会让「不支持」看着像「能换」**。
     */
    @GetMapping("/vl-impact")
    @RequiresPerm("system:model")
    public ApiResponse<Void> vlImpact() {
        throw unsupportedVlRebuild();
    }

    /**
     * {@code POST /system/model/rebuild-image-docs} —— **本版本不支持**重建含图文档。
     *
     * <p>⚠️ 同 {@link #changeEmbedding()}：不声明 `@RequestBody`、不落作业、不返回 `taskId`。
     */
    @PostMapping("/rebuild-image-docs")
    @RequiresPerm("system:model")
    public ApiResponse<Void> rebuildImageDocs() {
        throw unsupportedVlRebuild();
    }

    /** 换嵌入：现象（跨维度要重建整个集合、旧数据不保留）+ 下一步动作（人工流程）。 */
    private static BizException unsupportedEmbeddingSwitch() {
        return BizException.of(ErrorCode.MODEL_SWITCH_UNSUPPORTED,
                "本版本不支持更换嵌入模型：更换它需要重建整个向量集合，跨维度时旧数据不会保留，"
                        + "风险尚未评估。当前只开放对话模型的切换；"
                        + "如确需更换，请联系管理员按《11-M05-存储生命周期规则（O7）》走人工流程。");
    }

    /** 换 VL：现象（不会自动更新）+ 下一步动作（重新上传，当前唯一可行的那条路）。 */
    private static BizException unsupportedVlRebuild() {
        return BizException.of(ErrorCode.MODEL_SWITCH_UNSUPPORTED,
                "本版本不支持自动重建含图文档：重建需要逐张重新调用 VL 模型（实测 40~60 秒/张），"
                        + "本版本未开放该入口 —— 已入库文档的图片描述仍由原模型产出，不会自动更新。"
                        + "如需让它们跟上新模型，当前只能重新上传该文档（会重新走一遍解析与图片理解）。");
    }
}
