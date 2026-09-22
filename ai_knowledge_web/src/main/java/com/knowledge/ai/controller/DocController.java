package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.model.PageQuery;
import com.knowledge.ai.core.model.PageResult;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.core.web.WebUtils;
import com.knowledge.ai.dto.doc.DocDtos;
import com.knowledge.ai.service.DocAttributeService;
import com.knowledge.ai.service.DocDetailService;
import com.knowledge.ai.service.DocLifecycleService;
import com.knowledge.ai.service.DocReindexService;
import com.knowledge.ai.service.DocService;
import com.knowledge.ai.service.DocVersionService;
import com.knowledge.ai.service.DownloadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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

    private final DocAttributeService docAttributeService;

    private final DocReindexService docReindexService;

    private final DocVersionService docVersionService;

    /** `T-613`/`T-614`/`T-615`：预览地址 · 单篇下载 · 打包下载 · 任务 · 记录 · 导出清单。 */
    private final DownloadService downloadService;

    /** `T-611`：软删（移入回收站）。彻底删除与恢复在 `RecycleController`。 */
    private final DocLifecycleService docLifecycleService;

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

    // ==================================================================
    // S7 · M06 · 阶段 D：版本历史（`T-606`/`T-607`/`T-608`/`T-609`）
    // ==================================================================

    /**
     * {@code GET /api/doc/{id}/versions} —— 版本列表（版本 Tab 的表格）。
     *
     * <p>权限码 {@code doc:detail}（与元信息同一个）：版本列表是"管"侧的一栏，
     * 与预览页的 {@code doc:preview} 分开 —— 与 {@link #meta(long)} 同一条理由。
     *
     * <p>⚠️ 入参是**组内任意一版**的 doc id：页面按 id 打开某一版时也要看得到它的兄弟版本。
     */
    @GetMapping("/api/doc/{id}/versions")
    @RequiresPerm("doc:detail")
    public ApiResponse<DocDtos.DocVersionList> versions(@PathVariable("id") long docId) {
        return ApiResponse.ok(docVersionService.list(docId));
    }

    /**
     * {@code GET /api/doc/{id}/versions/{v}/diff?against={v2}} —— 版本对比（**仅元数据**）。
     *
     * <p>🔴 {@code textDiff} 恒 {@code null}：需求 §6.8 逐字写「v1 只做元数据对比 +
     * 文本 diff 接口预留」。{@code against} 留空 = 与当前版本比。
     */
    @GetMapping("/api/doc/{id}/versions/{v}/diff")
    @RequiresPerm("doc:detail")
    public ApiResponse<DocDtos.VersionDiff> versionDiff(@PathVariable("id") long docId,
                                                        @PathVariable("v") String version,
                                                        @RequestParam(required = false) String against) {
        return ApiResponse.ok(docVersionService.diff(docId, version, against));
    }

    /**
     * {@code POST /api/doc/{id}/rollback} —— 回滚到某一版（`G7-6`）。
     *
     * <p>⚠️ 版本号在**请求体**里（{@code {version}}），不是路径参数 —— 前端的
     * {@code docApi.rollback} 就是这么发的（见 {@code DocDtos.RollbackRequest} 的注释）。
     *
     * <p>权限码 {@code doc:update}：回滚是"管理这篇文档"的动作，与属性修改同属一类
     * （计划 §3.4 假设 6）。审计由服务层的 {@code @AuditLog} 落「文档 / 版本回滚」。
     */
    @PostMapping("/api/doc/{id}/rollback")
    @RequiresPerm("doc:update")
    public ApiResponse<DocDtos.RollbackResult> rollback(@PathVariable("id") long docId,
                                                        @RequestBody DocDtos.RollbackRequest request) {
        return ApiResponse.ok(docVersionService.rollback(docId, request == null ? null : request.version()));
    }

    /**
     * {@code GET /api/doc/{id}/tags} —— 文档级内容标签（`T-609`，**契约保留、最小实现**）。
     *
     * <p>⚠️ 恒回空数组，且这是**如实**而不是缺陷：文档级标签至今没有归属表（计划 §3.4 假设 4）。
     * 可见性判定照旧先走一遍 —— 空数组不该成为"这个 id 存不存在"的探测器。
     */
    @GetMapping("/api/doc/{id}/tags")
    @RequiresPerm("doc:detail")
    public ApiResponse<List<String>> tags(@PathVariable("id") long docId) {
        return ApiResponse.ok(docVersionService.tags(docId));
    }

    /**
     * {@code GET /api/doc/{id}/index-tasks} —— 该文档的解析/索引任务记录（详情页「版本历史」Tab 的上半）。
     *
     * <p>权限码取 {@code doc:detail} 而不是 {@code doc:reindex}：这一条是**看历史**，
     * 不是"重建"。重建权限（{@code doc:reindex}）守的是下面那条写侧端点（`T-610`）。
     */
    @GetMapping("/api/doc/{id}/index-tasks")
    @RequiresPerm("doc:detail")
    public ApiResponse<DocDtos.IndexTaskPage> indexTasks(@PathVariable("id") long docId) {
        return ApiResponse.ok(docDetailService.indexTasks(docId));
    }

    /**
     * {@code PATCH /api/doc/{id}/attributes} —— 修改文档属性（`T-601` · 需求 `06` §6.7）。
     *
     * <p>body `{ secretLevel?, kbId?, tags? }`（那三个就是面板上的**全部可编辑项**）。
     *
     * <p>权限 {@code doc:update}（第 44 个码）—— ⚠️ 它**只表达"能改属性"**；
     * "能不能改**这一篇**"由服务层按库判（移动要源库 `MANAGE` + 目标库 `doc:upload`）。
     * 码是角色级的、授权是逐库的，两者不能互相顶替（与 {@code doc:delete} 同一条分工）。
     *
     * <p>两条门禁的落点都在这条路径后面：
     * <ul>
     *   <li>**`G7-3`** —— 密级只能提高，降到低于知识库密级会被拒（`10030`）；</li>
     *   <li>**`G7-4`** —— 移动知识库触发重建（响应 {@code needReindex: true}）且**审计留痕**
     *       （「文档 / 移动知识库」）。</li>
     * </ul>
     */
    @PatchMapping("/api/doc/{id}/attributes")
    @RequiresPerm("doc:update")
    public ApiResponse<DocDtos.AttributeSaved> saveAttributes(@PathVariable("id") long docId,
                                                             @RequestBody DocDtos.AttributesRequest request) {
        return ApiResponse.ok(docAttributeService.save(docId, request));
    }

    /**
     * {@code POST /api/doc/{id}/reindex} —— 单篇重建（`T-610` · 需求 `06` §5「重新索引」）。
     *
     * <p>**只负责受理**：立刻返回 {@code taskId}，进度走 {@code GET /doc/tasks/{taskId}}
     * （与库级 `POST /kb/{id}/reindex` 同一条口径）。
     *
     * <p>🔴 它是**重新解析**（需求 §4：「会重新执行解析与表格识别，是修复未识别表格的主要入口」；
     * §5：「用于修复**解析失败**的个别文档」）—— 走整条流水线，不是"拿现有分片重新嵌入"。
     *
     * <p>权限 {@code doc:reindex}（S0 就有的码，本批**不新增**）。走 `@AuditLog`「文档 / 重建索引」：
     * 需求 `05` §6 的文档审计动作表**逐字列着**它。⚠️ 与**库级**重建刻意不审计形成对照 ——
     * 那条的理由是"内容一样，只是重算索引"（`KbController#reindex` 的注释），
     * 而**这条会重新解析**，表格识别与摘要都会重算 ⇒ **内容语义真的会变**。
     */
    @PostMapping("/api/doc/{id}/reindex")
    @RequiresPerm("doc:reindex")
    public ApiResponse<DocDtos.DocReindexAccepted> reindex(@PathVariable("id") long docId) {
        return ApiResponse.ok(docReindexService.submit(docId));
    }

    // ==================================================================
    // S7 · M06 · 阶段 E：预览地址 / 下载 / 打包 / 任务 / 记录 / 导出
    // （`T-613` / `T-614` / `T-615` · `G7-7` / `G7-8` / `G7-9`）
    // ==================================================================

    /**
     * {@code GET /api/doc/{id}/preview-url} —— **预览**用的预签名地址（{@code inline}，10 分钟）。
     *
     * <p>权限 {@code doc:preview}（与预览页其余五条同码）。它与下面那条下载地址的区别
     * **不在权限，在响应头**：这边让对象存储回 {@code Content-Disposition: inline}，
     * 浏览器直接开；那边回 {@code attachment}，浏览器另存为。
     * 需求 `10` §14 把两条并排列出正是这个意思（同一个对象、两种取法）。
     *
     * <p>⚠️ 前端预览页目前用元信息**本地渲染正文**（需求 §14 line 365 的「前端改用」），
     * 这条端点保留给大文件与"直接看原文件"的场景 —— 后端有、前端未接入。
     */
    @GetMapping("/api/doc/{id}/preview-url")
    @RequiresPerm("doc:preview")
    public ApiResponse<DocDtos.DownloadUrl> previewUrl(@PathVariable("id") long docId) {
        return ApiResponse.ok(downloadService.previewUrl(docId));
    }

    /**
     * {@code GET /api/doc/download-url/{docId}} —— **单篇下载**的预签名地址（{@code attachment}，10 分钟）。
     *
     * <p>🔴 每次调用都会写一条下载记录（`G7-9`）—— 它是"我下过什么"的证据链起点，
     * 不是一条可以随便重放的查询接口。
     *
     * <p>⚠️ 路径段是 {@code download-url}（字面量）而不是 {@code {id}} 的某个动作：
     * 《10》§7 就是这么定的。与 {@code /doc/{id}/preview-url} 的形状不同是**故意的**
     * —— 前者以动作开头（下载是全局动作），后者以资源开头（预览是文档的动作）。
     */
    @GetMapping("/api/doc/download-url/{docId}")
    @RequiresPerm("doc:download")
    public ApiResponse<DocDtos.DownloadUrl> downloadUrl(@PathVariable long docId,
                                                       HttpServletRequest request) {
        return ApiResponse.ok(downloadService.downloadUrl(docId, WebUtils.clientIp(request)));
    }

    /**
     * {@code POST /api/doc/batch-download} —— 批量打包下载（异步，`G7-8`）。
     *
     * <p>body = {@code {docIds: [..]}}。**受理时同步**校验：非空 · ≤ 50 篇 · 合计 ≤ 2GB ·
     * 每一篇都可见（任一失败 ⇒ 整批拒绝，不静默过滤）。
     * 通过后立刻返回 {@code taskId}，打包在异步任务里做，完成后经**通知中心**交付地址。
     */
    @PostMapping("/api/doc/batch-download")
    @RequiresPerm("doc:download")
    public ApiResponse<DocDtos.BatchDownloadAccepted> batchDownload(
            @RequestBody DocDtos.BatchDownloadRequest request,
            HttpServletRequest httpRequest) {
        // body 整个缺省时 request 为 null（前端 mock 之外没有这种调用，但不该 500）
        List<Long> docIds = request == null ? null : request.docIds();
        return ApiResponse.ok(downloadService.batchDownload(docIds, WebUtils.clientIp(httpRequest)));
    }

    /**
     * {@code GET /api/doc/download-tasks} —— **我的**打包任务列表。
     *
     * <p>🔴 归属过滤在服务端 SQL 里（{@code owner_id}）——
     * 不是"查全部再让前端只显示自己的"。
     */
    @GetMapping("/api/doc/download-tasks")
    @RequiresPerm("doc:download")
    public ApiResponse<DocDtos.DownloadTaskList> downloadTasks() {
        return ApiResponse.ok(downloadService.taskList());
    }

    /**
     * {@code GET /api/doc/download-tasks/{taskId}} —— 单条打包任务（前端轮询它推进度）。
     *
     * <p>⚠️ 与上面那条泛用的 {@code GET /api/doc/tasks/{taskId}} **不是一回事**：
     * 那条是文档解析等任务的投影（{@code DocDtos.TaskView}，六字段），
     * 这条是**打包下载**的投影（{@code DownloadTaskView}，带文件名 / 大小 / 下载地址），
     * 且**带归属校验** —— 泛用那条只要求登录（理由见它的 javadoc：taskId 本身就是能力凭证），
     * 而这条的响应里有**能把文件取走的预签名地址**，必须有归属。
     */
    @GetMapping("/api/doc/download-tasks/{taskId}")
    @RequiresPerm("doc:download")
    public ApiResponse<DocDtos.DownloadTaskView> downloadTask(@PathVariable String taskId) {
        return ApiResponse.ok(downloadService.taskDetail(taskId));
    }

    /**
     * {@code GET /api/doc/download-records?page=&size=} —— 我的下载历史（分页）。
     *
     * <p>⚠️ {@code size} 与 {@code pageSize} **两个名字都收，{@code size} 优先**：
     * 需求 `06` §5.7 的请求参数写的是 {@code size}，而前端历史代码发的是 {@code pageSize}
     * （{@code DocDownloadView.vue} 的 {@code PAGE_SIZE}）。只认一个的话，
     * 另一种调用会**静默退回默认 20 条** —— 现象是"每页条数设了没用"，最难查的一类。
     * 只做请求侧的兼容；**响应**统一走全站标准的 {@code { list, total, page, size }}。
     */
    @GetMapping("/api/doc/download-records")
    @RequiresPerm("doc:download")
    public ApiResponse<PageResult<DocDtos.DownloadRecordRow>> downloadRecords(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer pageSize) {
        int safePage = page == null ? PageQuery.DEFAULT_PAGE : page;
        int safeSize = size != null ? size
                : (pageSize == null ? PageQuery.DEFAULT_SIZE : pageSize);
        return ApiResponse.ok(downloadService.records(PageQuery.of(safePage, safeSize)));
    }

    /**
     * {@code POST /api/doc/export-list} —— 导出**下载记录清单**（异步 CSV，走通知中心）。
     *
     * <p>无请求体：导出范围就是"当前用户的全部下载记录"（记录页没有筛选器）。
     * 🚩 数据导出，审计标 HIGH。
     */
    @PostMapping("/api/doc/export-list")
    @RequiresPerm("doc:download")
    public ApiResponse<DocDtos.ExportListAccepted> exportList() {
        return ApiResponse.ok(downloadService.exportList());
    }

    // ==================================================================
    // S7 · M06 · 阶段 F：删除（`T-611`）与跨库列表（`T-612`）
    // ==================================================================

    /**
     * {@code GET /api/doc/all?keyword=&kbId=&type=&page=&size=} —— **跨库**文档列表（`T-612`）。
     *
     * <p>权限 {@code kb:view}：它是**读**侧，且**可见范围已经在 SQL 里收窄**
     * （服务端拼 {@code kbVisibility}）—— 多叠一道权限码不会让它更安全，
     * 只会让"能看列表的人看不了这个列表"。
     *
     * <p>⚠️ {@code kbId} 是**字符串**（前端下拉框把"全部"发成字面量 {@code all}）；
     * 非法值报 {@code 10030} 而不是忽略 —— 静默忽略会把"按库筛"变成"看全部"。
     */
    @GetMapping("/api/doc/all")
    @RequiresPerm("kb:view")
    public ApiResponse<DocDtos.AllDocPage> all(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String kbId,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(docService.allDocs(keyword, kbId, type, page, size));
    }

    /**
     * {@code DELETE /api/doc/{id}} —— 删除文档（**软删进回收站**，`T-611` · 🚩 内容删除）。
     *
     * <p>🔴 <b>它不是"删掉"，是"移入回收站"</b>：对象与向量**一个都不动**（`G7-14`），
     * 30 天内可恢复。真正抹掉内容的是 `/api/recycle/items`（标「彻底删除」，两条不同的审计）。
     *
     * <p>⚠️ 这条路径与 {@code GET /api/doc/{id}}（文档元信息）**同形不同方法** ——
     * 不是笔误：REST 语义里"取"与"删"本来就该是同一个资源的两条动词。
     */
    @DeleteMapping("/api/doc/{id}")
    @RequiresPerm("doc:delete")
    public ApiResponse<DocDtos.DocDeleted> remove(@PathVariable("id") long docId) {
        return ApiResponse.ok(docLifecycleService.softDelete(docId));
    }

    /**
     * {@code DELETE /api/doc/batch} —— 批量删除（软删，body {@code {ids}}，`T-611`）。
     *
     * <p>🔴 与 {@code /api/doc/{id}} 的**同一段**业务（`batchSoftDelete` → 同一套 `markDeleted`），
     * 只是多了"先全验后全删"那一轮 —— 批里任一文档不存在/不可见就**整批拒绝**，不静默过滤。
     *
     * <p>⚠️ 路径段是字面量 {@code batch}：Spring 的字面量优先规则会让它**先于**
     * {@code /api/doc/{id}} 命中（同 {@code /doc/download-tasks} 那条的说明）。
     */
    @DeleteMapping("/api/doc/batch")
    @RequiresPerm("doc:delete")
    public ApiResponse<DocDtos.DocBatchDeleted> batchRemove(
            @RequestBody DocDtos.BatchRemoveRequest request) {
        return ApiResponse.ok(docLifecycleService.batchSoftDelete(
                request == null ? null : request.ids()));
    }
}
