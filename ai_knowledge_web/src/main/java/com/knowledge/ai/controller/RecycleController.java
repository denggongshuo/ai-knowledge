package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.model.PageQuery;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.recycle.RecycleDtos;
import com.knowledge.ai.service.RecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回收站（`T-616` · 需求 `06` §7 · 《10》§8）。
 *
 * <p>五条端点与《10》§8 的表逐条对齐，路径全部是 {@code /api/recycle/*}。
 *
 * <h3>🔴 权限码是 {@code doc:recycle}，而它今天**只有超管有**</h3>
 * <p>`V2` 的种子：超管走 `CROSS JOIN sys_permission`（拥有全部权限点），
 * 其余四个角色是**逐个列举**的固定清单，**都不含** `doc:recycle`。
 * ⇒ 回收站事实上是**超管专属**页面。
 *
 * <p>由此推出一件必须写下来的事：**本控制器是回收站的唯一入口，也是唯一的授权边界**。
 * `RecycleService` 里的列表**刻意不过库级可见性**（那会把"库已删"的条目滤掉，
 * 而它们正是回收站最该显示的东西）—— 这条取舍成立的前提就是"能进来的只有超管"。
 * <p>⚠️ **哪天把这个码授给别的角色，`RecycleService` 必须补库级可见性**，
 * 且补的时候不能直接用 {@code kbVisibility}（它自带 `deleted_at IS NULL`）。
 */
@RestController
@RequiredArgsConstructor
public class RecycleController {

    private final RecycleService recycleService;

    /**
     * {@code GET /api/recycle/items?type=DOC|KB&keyword=&page=&size=} —— 回收站列表。
     *
     * <p>{@code type} **必填**（两个 Tab 的数据源不同：文档 join 原知识库、知识库 join 所属部门）。
     * 非法值显式报 {@code 10030}，**不默认成 DOC** —— 那样知识库 Tab 会静默显示成文档列表。
     */
    @GetMapping("/api/recycle/items")
    @RequiresPerm("doc:recycle")
    public ApiResponse<RecycleDtos.RecyclePage> items(@RequestParam String type,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(recycleService.items(type, keyword,
                PageQuery.of(page == null ? PageQuery.DEFAULT_PAGE : page,
                        size == null ? PageQuery.DEFAULT_SIZE : size)));
    }

    /**
     * {@code POST /api/recycle/restore} —— 恢复（🚩 见 `RecycleService#restore` 的说明）。
     *
     * <p>body = {@code {type, ids, targetKbId?}}。⚠️ 请求体里**没有** {@code page} 之类，
     * 走 {@code @RequestBody} 而不是表单（与前端 `http.post(path, body)` 一致）。
     */
    @PostMapping("/api/recycle/restore")
    @RequiresPerm("doc:recycle")
    public ApiResponse<RecycleDtos.RestoreResult> restore(
            @RequestBody RecycleDtos.RestoreRequest request) {
        return ApiResponse.ok(recycleService.restore(request));
    }

    /**
     * {@code DELETE /api/recycle/items} —— 彻底删除（🚩 **不可逆**）。
     *
     * <p>⚠️ `DELETE` 带请求体（{@code {type, ids}}）—— 前端 `http.delete(path, body)` 就是这么发的，
     * 且本仓已有同形先例（{@code DELETE /api/doc/batch}）。
     */
    @DeleteMapping("/api/recycle/items")
    @RequiresPerm("doc:recycle")
    public ApiResponse<RecycleDtos.PurgeResult> purge(@RequestBody RecycleDtos.PurgeRequest request) {
        return ApiResponse.ok(recycleService.purge(request));
    }

    /**
     * {@code DELETE /api/recycle/all} —— 清空回收站（🚩 **最高危**，需求 §7.6）。
     *
     * <p>⚠️ **需求 §7.6 的"输入确认词"在客户端**（`RecycleView` 输 `DELETE`）。
     * 服务端收不到那个词 —— 这是已知偏差 ②（`plan/2026-09-22-S7-M06-F.md` §1），
     * 权限侧由 {@code doc:recycle} 把关。
     */
    @DeleteMapping("/api/recycle/all")
    @RequiresPerm("doc:recycle")
    public ApiResponse<RecycleDtos.ClearResult> clearAll() {
        return ApiResponse.ok(recycleService.clearAll());
    }

    /**
     * {@code POST /api/recycle/cleanup} —— 清理超期项（《10》§8 注「**定时任务**调用」）。
     *
     * <p>⚠️ <b>这条是同步的</b>（前端 `recycleApi.cleanup()` 直接等 `{cleaned, note}`），
     * 而定时那条走**异步任务**（`RecycleCleanupJob` → `submitCleanup()`）——
     * 差别是"谁触发、失败要不要发通知"：手工调的人当场看得到错误，凌晨跑的没人看，
     * 所以它必须产广播告警（需求 §7.9）。两条最终调的是**同一个** {@code cleanup()}。
     */
    @PostMapping("/api/recycle/cleanup")
    @RequiresPerm("doc:recycle")
    public ApiResponse<RecycleDtos.CleanupResult> cleanup() {
        return ApiResponse.ok(recycleService.cleanup());
    }
}
