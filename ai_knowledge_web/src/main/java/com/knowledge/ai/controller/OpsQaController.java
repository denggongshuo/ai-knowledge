package com.knowledge.ai.controller;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.ops.OpsDtos;
import com.knowledge.ai.service.GraphEdgeService;
import com.knowledge.ai.service.OpsExportService;
import com.knowledge.ai.service.QaMissService;
import com.knowledge.ai.service.QaOpsService;
import com.knowledge.ai.service.ReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营分析 —— 问答运营页（需求 `08` §1.8 的端点；`T-A00` 落三条，`T-A01` 补上重放验证）。
 *
 * <p>需求 `08` §1.8 列的**七条端点全部已交付**：{@code GET /misses} · {@code PATCH /misses/{id}} ·
 * {@code GET /overview}（`T-A00`）· {@code POST /misses/{id}/replay}（`T-A01`）·
 * {@code GET /top-questions} · {@code GET /feedback-stats} · {@code POST /export}（`T-A02`）。
 *
 * <h3>🔴 权限：读 `ops:qa:view` · 写 `ops:qa:handle`（本批新增的两个码）</h3>
 * <p>需求 `G8-9` 说这一页「**仅管理员可见**」。落到权限码上就是这两个 ——
 * 授予情况见 `V32` 的头注释：`KB_ADMIN` 两个都有（它是唯一能走完「沉淀 → 补文档 →
 * 重建索引」闭环的角色）、`AUDITOR` 只有读、`USER`/`GUEST` 一个都没有。
 *
 * <p>⚠️ **为什么拆成两个码而不是一个**：审计员"能看不能改"是真实存在的边界，
 * 拆开之后它才是**可证伪**的（同一个 token，`GET` 200 而 `PATCH` 403）。
 * 承 `#168`/`#153` 的教训：**没有角色被授予的权限码 = 没有角色能证伪这条边界**，
 * 那种权限码等于装饰。
 *
 * <h3>为什么读侧也挂码（而不是"只要求登录"）</h3>
 * <p>这一页能看到**全站**用户的提问原文与提问人 —— 与"我自己的通知"是两回事
 * （`NoticeController` 那种才该只要求登录）。`G8-9` 还额外要求
 * 「个人维度**不展示具体提问内容**」，那条是 `T-A05` 使用统计页的事，本批不涉及。
 *
 * <h3>七条端点的审计边界（**只有一条挂审计**）</h3>
 * <p>改状态（`PATCH /misses/{id}`）与重放验证（`POST /misses/{id}/replay`）都**不写审计**：
 * 它们不是《05》§4.3 的高危六类。`ignored_by` 这一列本身就是留痕 —— 需求 §1.3 要它，
 * 要的正是"谁忽略的"，落在那张表里比落进审计表更贴近使用现场。
 * <p>🔴 **只有导出挂审计**（`@AuditLog(module = QA, action = "导出运营报表", risk = HIGH)`），
 * 因为 `AuditRiskPolicy` 的 {@code DATA_EXPORT} 类里**逐字列着「导出报表」** ——
 * 那是口径要求，不是判断题。
 */
@RestController
@RequestMapping("/api/ops/qa")
@RequiredArgsConstructor
public class OpsQaController {

    private final QaMissService qaMissService;

    private final QaOpsService qaOpsService;

    private final OpsExportService opsExportService;

    private final ReplayService replayService;

    private final GraphEdgeService graphEdgeService;

    /**
     * `GET /ops/qa/misses?status=&kbId=&keyword=&page=&size=` —— 未命中问题清单。
     *
     * <p>默认按提问次数降序（需求 §1.2）。
     *
     * <p>⚠️ {@code kbId} 收的是 **String 而不是 Long**：前端「知识库筛选」那一栏的空值是
     * 字面量 {@code all}（`OpsQaView.vue` 的 {@code kbFilter = ref('all')}`，与
     * `mock/index.ts` 的 `kbId !== 'all'` 一致）。声明成 `Long` 的话，Spring 在**参数绑定**
     * 阶段就抛类型转换错误 ⇒ **整个列表接口 400**，而现象是"页面空着、接口没实现"，
     * 与权限/未实现长得一模一样。⇒ 在服务层把它翻成"不筛"。
     */
    @GetMapping("/misses")
    @RequiresPerm("ops:qa:view")
    public ApiResponse<OpsDtos.MissListResponse> misses(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kbId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(qaMissService.list(status, kbId, keyword, page, size));
    }

    /**
     * `PATCH /ops/qa/misses/{id}` —— 改状态（body `{status}`）。
     *
     * <p>回话里带 `pending`：前端改完直接用新待办数刷新统计卡与角标，不再多发一次请求。
     */
    @PatchMapping("/misses/{id}")
    @RequiresPerm("ops:qa:handle")
    public ApiResponse<OpsDtos.PatchMissResponse> patchMiss(@PathVariable long id,
                                                            @RequestBody OpsDtos.PatchMissRequest body) {
        String status = body == null ? null : body.status();
        return ApiResponse.ok(qaMissService.patch(id, status, LoginUserContext.requireUserId()));
    }

    /**
     * `POST /ops/qa/misses/{id}/graph-edge` —— 图谱补边（`T-C04` · 需求 `07:544`）。
     *
     * <p>针对一条未命中问题，人工补一条实体关系边（`confidence` 一律 `1.0`，需求 `03:102`）。
     * 它是"用文档补"（`T-A01` 的补文档 → 重放验证）之外的第二条闭环路：
     * 有些问题是**文档本来就有、只是模型没抽出那条关系**，重传文档解决不了。
     *
     * <p>权限 `ops:qa:handle`：与 `PATCH /misses/{id}` 同码 —— 两者都是"处理未命中"这个动作的
     * 不同侧面，能改状态就能补边，不该因为多一个动词就多要一个权限。
     *
     * <p>⚠️ 风险等级 **NORMAL**：它只**新增**一条边、影响面限于命中该实体对的提问，
     * 且可由"删掉这条边"完全回退 —— 与 `PUT /system/feature/graph`（一按下去**所有用户**
     * 的问答结果都变）不是同一量级。按《05》§4.3 的纪律，**不进高危类的动作要在
     * `AuditRiskPolicy.IMPLEMENTED` 里留下"为什么不标"的证据**。
     */
    @PostMapping("/misses/{id}/graph-edge")
    @RequiresPerm("ops:qa:handle")
    @AuditLog(module = AuditModule.QA, action = "图谱补边", risk = RiskLevel.NORMAL)
    public ApiResponse<OpsDtos.ManualEdgeResult> graphEdge(@PathVariable long id,
                                                           @RequestBody OpsDtos.ManualEdgeRequest body) {
        if (body == null || body.docId() == null || body.chunkId() == null) {
            // 缺参数在这里就拒：让它落到服务层会变成一次"文档不存在"的误报
            throw new BizException(ErrorCode.PARAM_INVALID, "docId 与 chunkId 必填（回源锚不能缺）");
        }
        return ApiResponse.ok(graphEdgeService.addEdge(id, body.subject(), body.predicate(),
                body.object(), body.docId(), body.chunkId()));
    }

    /** `GET /ops/qa/overview?range=7d` —— 四个指标（需求 §1.1）。 */
    @GetMapping("/overview")
    @RequiresPerm("ops:qa:view")
    public ApiResponse<OpsDtos.QaOverview> overview(@RequestParam(required = false) String range) {
        return ApiResponse.ok(qaMissService.overview(range));
    }

    /**
     * `GET /ops/qa/top-questions?range=7d&limit=20` —— 高频问题榜（需求 §1.5）。
     *
     * <p>⚠️ {@code limit} 收 {@code Integer} 而不是 {@code int}：前端可以不传（默认 20）。
     * 越界是**夹取**不是拒绝 —— 与 {@code overview} 的 {@code range} 同一条口径。
     */
    @GetMapping("/top-questions")
    @RequiresPerm("ops:qa:view")
    public ApiResponse<OpsDtos.TopQuestionsResponse> topQuestions(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(qaOpsService.topQuestions(range, limit));
    }

    /**
     * `GET /ops/qa/feedback-stats?range=7d` —— 点踩原因分布（需求 §1.6）。
     *
     * <p>🔴 回话里有三个数合起来才对得上（`reasons` 合计 + `unreasoned` = `negativeTotal`），
     * 因为**点踩时原因是可以不填的** —— 见 {@link OpsDtos.FeedbackStats} 的类注释。
     */
    @GetMapping("/feedback-stats")
    @RequiresPerm("ops:qa:view")
    public ApiResponse<OpsDtos.FeedbackStats> feedbackStats(
            @RequestParam(required = false) String range) {
        return ApiResponse.ok(qaOpsService.feedbackStats(range));
    }

    /**
     * `POST /ops/qa/export` —— 导出未命中清单（需求 §1.7 / §1.8），异步，走通知中心。
     *
     * <h4>🔴 它是本页唯一挂审计的端点，也是唯一挂 `ops:qa:handle` 的读侧动作</h4>
     * <p>两个选择都不是本批发明的：
     * <ul>
     *   <li>**审计**：`AuditRiskPolicy` 的 {@code DATA_EXPORT} 类里**逐字列着「导出报表」**
     *       （`AuditRiskPolicy:18`），与「导出审计日志」「导出会话」并列 ⇒ 这是**口径要求**，
     *       不是"顺手加一个"。`risk = HIGH`。</li>
     *   <li>**权限用 `ops:qa:handle` 而不是 `ops:qa:view`**：导出是**数据外带**
     *       （含提问原文 + 提问人姓名），按 `AuditController#export` 的先例
     *       （它用单独的 `audit:export` 而不复用 `audit:view`）该比"看"更严。
     *       ⚠️ 但本批**刻意不新增第 41 个权限码**：新码落不到角色上就会变成
     *       `#153`/`#168` 那种"没有角色能证伪"的装饰码（那两个号的教训写在
     *       `OpsQaController` 的类注释里）。用既有的处置侧码，边界**可证伪**：
     *       `AUDITOR` 能 `GET` 两个统计端点、`POST /export` 被 403。</li>
     * </ul>
     */
    @PostMapping("/export")
    @RequiresPerm("ops:qa:handle")
    @AuditLog(module = AuditModule.QA, action = "导出运营报表", risk = RiskLevel.HIGH)
    public ApiResponse<OpsDtos.ExportAccepted> export(
            @RequestBody(required = false) OpsDtos.ExportRequest body) {
        OpsDtos.ExportRequest q = body == null
                ? new OpsDtos.ExportRequest(null, null, null)
                : body;
        return ApiResponse.ok(opsExportService.submit(q.status(), q.kbId(), q.keyword()));
    }

    /**
     * `POST /ops/qa/misses/{id}/replay` —— 重放验证（`T-A01` · 需求 §1.8 与 §1.4 步 4）。
     *
     * <p>权限用 {@code ops:qa:handle}（不是 {@code view}）：它会**改状态**。
     * 于是审计员那半边边界继续成立 —— 同一个 AUDITOR token，
     * `GET /ops/qa/misses` 200 而本接口 403（`G8-9`）。
     *
     * <p>⚠️ 用 {@link LoginUserContext#require()} 而不是 {@code requireUserId()}：
     * 重放要**以原提问人的身份构造一个登录用户**去跑检索，而"操作人"是提问人已注销时的
     * 兜底身份 —— 那需要一个完整的 {@code LoginUser}，不只是一个 id。
     */
    @PostMapping("/misses/{id}/replay")
    @RequiresPerm("ops:qa:handle")
    public ApiResponse<OpsDtos.ReplayResult> replay(@PathVariable long id) {
        return ApiResponse.ok(replayService.replay(id, LoginUserContext.require()));
    }
}
