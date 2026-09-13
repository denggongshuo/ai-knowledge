package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.admin.AdminDtos;
import com.knowledge.ai.service.KbGrantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库授权（M02 · 《10》§4，T-211）。
 *
 * <p>路径注意：{@code GET /kb/grants}（字面路径，授权列表）与
 * {@code GET /kb/{kbId}/grants}（单库详情）并存 —— mock 注册顺序上前者必须在前
 * （否则 {@code grants} 被当 {@code :id} 吃掉）；Spring 的字面路径优先级天然正确，无此问题。
 *
 * <p>读侧只要求登录（授权管理页与知识库可见性判断共用）；写侧挂 {@code kb:grant}
 * （知识库管理员与超管持有）—— 改授权直接改变所有人的可见范围，🚩 高危。
 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KbGrantController {

    private final KbGrantService kbGrantService;

    /** {@code GET /kb/grants?page=&pageSize=} —— ⚠️ 这里的分页参数是 {@code pageSize}。 */
    @GetMapping("/grants")
    public ApiResponse<AdminDtos.KbGrantPage> list(@RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(name = "pageSize", defaultValue = "8") long pageSize) {
        return ApiResponse.ok(kbGrantService.list(page, pageSize));
    }

    /** {@code GET /kb/{kbId}/grants} —— 全量角色 + granted 标记 + 额外用户（勾选回显）。 */
    @GetMapping("/{kbId}/grants")
    public ApiResponse<AdminDtos.KbGrantDetail> detail(@PathVariable long kbId) {
        return ApiResponse.ok(kbGrantService.detail(kbId));
    }

    /** {@code PUT /kb/{kbId}/grants} —— 🚩 全量替换：{@code { roleIds, level, extraUserIds }}。 */
    @PutMapping("/{kbId}/grants")
    @RequiresPerm("kb:grant")
    public ApiResponse<AdminDtos.KbGrantSaved> save(@PathVariable long kbId,
                                                    @Valid @RequestBody AdminDtos.KbGrantSaveRequest request) {
        return ApiResponse.ok(kbGrantService.save(kbId, request));
    }
}
