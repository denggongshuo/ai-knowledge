package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.doc.DocDtos;
import com.knowledge.ai.service.DocDetailService;
import com.knowledge.ai.service.DocService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * M05 文档管线 · 阶段 A 的对外接口（`T-500` / `T-501` / `T-509` / `T-510`）。
 *
 * <p>路径与《10-接口约定汇总》§6 逐条对齐：
 * <ul>
 *   <li>{@code POST /api/kb/{kbId}/doc/upload} —— 上传（`multipart/form-data`）</li>
 *   <li>{@code POST /api/kb/{kbId}/doc/check-md5} —— 秒传判断</li>
 *   <li>{@code GET  /api/kb/{kbId}/docs} —— 文档列表</li>
 *   <li>{@code GET  /api/doc/tasks/{taskId}} —— 任务进度</li>
 * </ul>
 *
 * <p><b>为什么上传与列表在同一个 controller 而路径前缀不同</b>（{@code /kb/...} vs {@code /doc/...}）：
 * 这是《10》§6 的既有约定 —— 前三条挂在知识库下（"这个库的文档"），
 * 任务进度是**全局资源**（任务 id 本身已全局唯一，且提交它的可能是文档解析、打包下载、
 * 审计导出中的任意一种）。所以本类用**类级空前缀 + 方法级全路径**声明，
 * 不写类级路由前缀 —— 否则任务进度会被迫长成 {@code /api/kb/tasks/{id}}，与《10》不符。
 *
 * <p>⚠️ <b>本注释刻意不写那个类级注解的字面形式</b>（连反例也不写）。
 * 理由：{@code ai_knowledge_app/scripts/audit-api.mjs} 用**正则**从本目录的源码里
 * 抓路由，它不区分"注释里的示例"与"真注解" —— 在 javadoc 里写一遍那个注解，
 * 脚本就会把它当成一条真实路由收进清单，产出 {@code GET /kb/api/doc/tasks/{p}}
 * 这种**前缀被拼了两次**的幽灵条目，而 ⑧ 段（真后端有、mock 没实现）会因此变长。
 * 本批施工时实测踩到：⑧ 从 6 条涨到 10 条，其中 4 条全是这段注释造的。
 */
@RestController
@RequiredArgsConstructor
public class DocController {

    private final DocService docService;

    private final DocDetailService docDetailService;

    /**
     * {@code POST /api/kb/{kbId}/doc/upload} —— 上传文档。
     *
     * <p>字段名固定 {@code file}（同 {@code POST /user/avatar} 的口径）：前端
     * {@code FormData.append('file', f)}。**用 {@code @RequestParam} 而不是 {@code @RequestPart}** ——
     * 后者要求部件带 {@code Content-Type}，而浏览器在 {@code FormData.append(name, File)}
     * 时给的部件类型是我们不校验的（见 {@code DocStorage#validate} 的取舍：类型按扩展名判）。
     * 收 {@code @RequestPart} 反而会在某些客户端下报"不支持的内容类型"。
     *
     * <p>权限：{@code doc:upload}（38 个权限点之一，S0 已建）。
     *
     * <p>可选字段 {@code remark}（`V34` · `T-A01`）：从问答运营页点「补文档」跳来时，
     * 前端把那条未命中问题的原文带进来（需求 §1.4 步 2「问题文本作为补录备注带入」）。
     * ⚠️ 它**不是必填** —— 普通上传照样只发 {@code file}，行为与以前完全一致。
     */
    @PostMapping("/api/kb/{kbId}/doc/upload")
    @RequiresPerm("doc:upload")
    public ApiResponse<DocDtos.UploadAccepted> upload(@PathVariable long kbId,
                                                      @RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "remark", required = false)
                                                      String remark) {
        return ApiResponse.ok(docService.upload(kbId, file, remark));
    }

    /**
     * {@code POST /api/kb/{kbId}/doc/check-md5} —— 秒传 / 去重判断。
     *
     * <p>只要求登录，**不要求 {@code doc:upload}**：本接口读的是"这个库里有没有同名同内容"
     * 这一条信息，而库本身的可达性已由求交放行（{@code 10022}）守住。
     * 多叠一道上传权限会让"只看得到但传不了"的角色（如审计员）连"这文件是否已存在"
     * 都问不出来，而那个答案对他并不敏感。
     */
    @PostMapping("/api/kb/{kbId}/doc/check-md5")
    @RequiresPerm("kb:list")
    public ApiResponse<DocDtos.Md5Check> checkMd5(@PathVariable long kbId,
                                                  @Valid @RequestBody DocDtos.Md5CheckRequest request) {
        return ApiResponse.ok(docService.checkMd5(kbId, request));
    }

    /**
     * {@code GET /api/kb/{kbId}/docs} —— 库内文档列表（含解析质量）。
     *
     * <p>筛选参数：{@code keyword}（标题/文件名模糊）· {@code status}（域内真值，
     * {@code all} 等同不传）· {@code quality=issue}（只看有未识别表格/图片的）。
     */
    @GetMapping("/api/kb/{kbId}/docs")
    @RequiresPerm("kb:list")
    public ApiResponse<DocDtos.DocPage> page(@PathVariable long kbId,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String quality,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(docService.page(kbId, keyword, status, quality, page, size));
    }

    /**
     * {@code GET /api/doc/tasks/{taskId}} —— 异步任务进度。
     *
     * <p>只要求登录：任务 id 是**不可猜的随机 traceId**（{@code TraceContext.newTraceId()}），
     * 本身就是能力凭证；再叠一层权限只会让"打包下载完成后回来看进度"这类场景
     * 因为权限码不同而莫名失败。任务查不到 → {@code 10045}。
     */
    @GetMapping("/api/doc/tasks/{taskId}")
    public ApiResponse<DocDtos.TaskView> task(@PathVariable String taskId) {
        return ApiResponse.ok(docService.taskView(taskId));
    }

    /**
     * {@code GET /api/kb/{kbId}/parse-issues} —— 逐页解析问题清单（`T-511`，`G4-11`）。
     *
     * <p>权限 {@code kb:list}：与文档列表同码 —— 清单是列表页告警条的数据源，
     * "能看列表却看不到告警"只会造成"列表里有红标、顶部却安静"的割裂。
     * 行粒度 = (文档, 页码)；前端"篇数"按 {@code docId} 去重。
     */
    @GetMapping("/api/kb/{kbId}/parse-issues")
    @RequiresPerm("kb:list")
    public ApiResponse<DocDtos.ParseIssuePage> parseIssues(@PathVariable long kbId) {
        return ApiResponse.ok(docService.parseIssues(kbId));
    }

    // ==================================================================
    // S7 · M06 · 阶段 A：文档详情 / 预览页的**只读**读侧（`T-600`/`T-602`/`T-603`/`T-604`/`T-605`）
    // ==================================================================

    /**
     * {@code GET /api/doc/{id}} —— 文档元信息（详情页与预览页共用）。
     *
     * <p>权限码 {@code doc:detail}：这一页是"管"侧（可改属性、可回滚版本），
     * 与预览页的 {@code doc:preview} 分开 —— 两者的可见范围本来就不同。
     */
    @GetMapping("/api/doc/{id}")
    @RequiresPerm("doc:detail")
    public ApiResponse<DocDtos.DocMetaView> meta(@PathVariable("id") long docId) {
        return ApiResponse.ok(docDetailService.meta(docId));
    }

    /**
     * {@code GET /api/doc/{id}/toc} —— 目录树（左栏，需求 §4.2）。
     *
     * <p>参数一律写成 {@code @PathVariable("id") long docId}（六条同一个写法）：
     * 路径变量的名字是 {@code id}、而局部变量叫 {@code docId}，显式写出来就不会有人
     * 以为这两条挂的不是同一个东西。
     */
    @GetMapping("/api/doc/{id}/toc")
    @RequiresPerm("doc:preview")
    public ApiResponse<DocDtos.TocView> toc(@PathVariable("id") long docId) {
        return ApiResponse.ok(docDetailService.toc(docId));
    }

    /**
     * {@code GET /api/doc/{id}/chunks?type=&page=&size=} —— 分片列表（右栏卡片流）。
     *
     * <p>{@code type} 的闭集是 {@code all/text/table/image/summary}（需求 §4.4 的四个展示分组 + 全部）；
     * 闭集之外显式报 {@code 10030}，**不静默当成 all**。
     */
    @GetMapping("/api/doc/{id}/chunks")
    @RequiresPerm("doc:preview")
    public ApiResponse<DocDtos.ChunkPage> chunks(@PathVariable("id") long docId,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(docDetailService.chunks(docId, type, page, size));
    }

    /**
     * {@code GET /api/doc/{id}/chunk-stat} —— 分片统计（详情页的三张统计卡 + `G7-2`）。
     *
     * <p>与分片列表**分开两个端点**（《10》§6 的既有约定）：列表带过滤、统计不带 ——
     * 详情页要的是"这篇文档总共有多少个什么块"，与用户此刻筛了哪一档无关。
     */
    @GetMapping("/api/doc/{id}/chunk-stat")
    @RequiresPerm("doc:preview")
    public ApiResponse<DocDtos.ChunkStat> chunkStat(@PathVariable("id") long docId) {
        return ApiResponse.ok(docDetailService.statOf(docId));
    }

    /**
     * {@code GET /api/doc/{id}/chunks/{parentId}} —— 父块完整内容 + **全部**子块（`G7-1`）。
     *
     * <p>⚠️ 这条路径与 {@code chunks} 是**两层**关系：{@code parentId} 必须是**父块**
     * （{@code chunk_type = 'TABLE' 且 parent_id is null}）的真实 id；传子块 id、
     * 传非表格块、传 {@code 0} 都会得到明确的业务错（理由见 {@code DocDetailService#parentChunk}）。
     */
    @GetMapping("/api/doc/{id}/chunks/{parentId}")
    @RequiresPerm("doc:preview")
    public ApiResponse<DocDtos.ParentChunkView> parentChunk(@PathVariable("id") long docId,
                                                           @PathVariable long parentId) {
        return ApiResponse.ok(docDetailService.parentChunk(docId, parentId));
    }

    /**
     * {@code GET /api/doc/{id}/index-tasks} —— 该文档的解析/索引任务记录（详情页「版本历史」Tab 的上半）。
     *
     * <p>权限码取 {@code doc:detail} 而不是 {@code doc:reindex}：这一条是**看历史**，
     * 不是"重建"。重建权限（{@code doc:reindex}）守的是写侧那条端点（`T-610`，本批不做）。
     */
    @GetMapping("/api/doc/{id}/index-tasks")
    @RequiresPerm("doc:detail")
    public ApiResponse<DocDtos.IndexTaskPage> indexTasks(@PathVariable("id") long docId) {
        return ApiResponse.ok(docDetailService.indexTasks(docId));
    }
}
