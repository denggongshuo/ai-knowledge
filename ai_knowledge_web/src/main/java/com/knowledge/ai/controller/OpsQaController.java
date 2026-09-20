package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.ops.OpsDtos;
import com.knowledge.ai.service.QaMissService;
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
 * <p>已交付：{@code GET /misses} · {@code PATCH /misses/{id}} · {@code GET /overview}（`T-A00`）·
 * {@code POST /misses/{id}/replay}（`T-A01`）。尚未交付（`T-A02`）：高频问题榜 /
 * 点踩归因 / 导出。
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
 * <h3>不写审计</h3>
 * <p>改一个未命中问题的状态不是《05》§0.6 的高危四类（权限扩大 / 数据导出 /
 * 内容删除 / 模型配置变更）。`ignored_by` 这一列本身就是留痕 —— 需求 §1.3 要它，
 * 要的正是"谁忽略的"，落在这张表里比落进审计表更贴近使用现场。
 */
@RestController
@RequestMapping("/api/ops/qa")
@RequiredArgsConstructor
public class OpsQaController {

    private final QaMissService qaMissService;

    private final ReplayService replayService;

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

    /** `GET /ops/qa/overview?range=7d` —— 四个指标（需求 §1.1）。 */
    @GetMapping("/overview")
    @RequiresPerm("ops:qa:view")
    public ApiResponse<OpsDtos.QaOverview> overview(@RequestParam(required = false) String range) {
        return ApiResponse.ok(qaMissService.overview(range));
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
