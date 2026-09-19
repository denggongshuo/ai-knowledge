package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.dto.notice.NoticeDtos;
import com.knowledge.ai.service.NoticeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知中心 —— `M11`，需求《09》§2.8 的七条端点（`T-B00`/`T-B01`/`T-B02`）。
 *
 * <h3>🔴 权限：只要求登录，**不挂任何权限码**</h3>
 * <p>这七条端点操作的全是**「我自己的通知」** —— 别人有没有权限跟"我能不能看自己的未读"
 * 没有关系。挂一个 `qa:*` 或 `system:*` 上去，结果是**把普通用户挡在自己的通知外面**
 * —— 那正是 `S6-M09-B` 踩过的坑（`T-904` 的模型选项一度挂 `system:model`，
 * 而那个码只有超管有 ⇒ 普通用户在问答页拿不到模型列表）。
 *
 * <p>越权靠的是**查询条件**而不是权限码：每条 SQL 都带可见性条件
 * （{@code n.user_id is null or n.user_id = :uid}，见 {@code NoticeRepository.VISIBLE}），
 * 标已读的写操作也带 —— 拿别人的 id 只会得到 404。
 *
 * <h3>不写审计</h3>
 * <p>标已读 / 存设置都不是《05》§0.6 的高危四类（权限扩大 / 数据导出 / 内容删除 /
 * 模型配置变更），且**用户读自己的通知**这件事按 180 天记审计没有价值。
 * 承 {@code T-902} 的 `activate` 那条口径：**别为了"多记一点"把审计表淹掉**。
 */
@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    /** `GET /notices?type=&read=&page=&size=` —— 列表 + 六个计数。 */
    @GetMapping
    public ApiResponse<NoticeDtos.NoticeListResponse> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String read,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(noticeService.list(LoginUserContext.requireUserId(), type, read, page, size));
    }

    /** `GET /notices/unread-count` —— 顶栏铃铛角标（需求 §2.6）。 */
    @GetMapping("/unread-count")
    public ApiResponse<NoticeDtos.UnreadCountResponse> unreadCount() {
        return ApiResponse.ok(noticeService.unreadCount(LoginUserContext.requireUserId()));
    }

    /**
     * `GET /notices/pending` —— 「待我处理」聚合。
     *
     * <p>⚠️ 本批**只出有源的那一半**（解析问题）；未命中问题那一半的源属 `T-A00`（S8），
     * 见 {@code NoticeService} 的类注释与计划 §1.2。
     */
    @GetMapping("/pending")
    public ApiResponse<NoticeDtos.PendingResponse> pending() {
        // 这个聚合是全局口径（不按用户分），但仍要求登录 —— 它是"系统里还有多少待处理"，
        // 不是"我的待办"。未登录能拿到它会是一个不大不小的信息泄露。
        LoginUserContext.requireUserId();
        return ApiResponse.ok(noticeService.pending());
    }

    /** `PATCH /notices/{id}/read` —— 标已读 / 标回未读（已读可逆）。 */
    @PatchMapping("/{id}/read")
    public ApiResponse<NoticeDtos.ReadResponse> setRead(@PathVariable long id,
                                                       @RequestBody(required = false) ReadRequest request) {
        boolean read = request == null || request.read() == null || request.read();
        return ApiResponse.ok(noticeService.setRead(LoginUserContext.requireUserId(), id, read));
    }

    /** `POST /notices/read-all` —— 全部标为已读（无需二次确认）。 */
    @PostMapping("/read-all")
    public ApiResponse<NoticeDtos.ReadAllResponse> readAll() {
        return ApiResponse.ok(noticeService.readAll(LoginUserContext.requireUserId()));
    }

    /** `GET /notices/settings` —— 四类开关。 */
    @GetMapping("/settings")
    public ApiResponse<NoticeDtos.SettingsResponse> settings() {
        return ApiResponse.ok(noticeService.settings(LoginUserContext.requireUserId()));
    }

    /** `PUT /notices/settings` —— 保存；**系统告警的关闭请求会被剔除并在 `note` 里说明**。 */
    @PutMapping("/settings")
    public ApiResponse<NoticeDtos.SettingsSaveResponse> saveSettings(
            @Valid @RequestBody NoticeDtos.SettingsSaveRequest request) {
        return ApiResponse.ok(noticeService.saveSettings(LoginUserContext.requireUserId(), request));
    }

    /** 请求体极小且字段可选，故不单开 DTO 文件（与前端 `{read}` 一字不差）。 */
    public record ReadRequest(Boolean read) {
    }
}
