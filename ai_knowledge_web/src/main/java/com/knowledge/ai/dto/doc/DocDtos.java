package com.knowledge.ai.dto.doc;

import java.util.List;

/**
 * M05（文档管线）阶段 A 的出入参：`T-500` 上传 · `T-501` 去重 · `T-509` 任务状态 · `T-510` 列表。
 *
 * <p><b>字段名的事实源是前端</b>（与 {@code dto/kb/KbDtos}、{@code dto/admin/AdminDtos} 同一条约定）：
 * 本包字段逐一对齐 {@code ai_knowledge_app/src/types/index.ts} 的 {@code KbDocRow} / {@code DocInfo}，
 * 以及 {@code src/mock/index.ts} 里对应接口的返回体（`:569` 列表 / `:593` 去重 / `:607` 上传 / `:624` 任务）。
 *
 * <p>⚠️ <b>列表行的四个质量字段（{@code tableTotal/tableParsed/imageTotal/imageParsed}）本批恒 0</b>
 * —— 它们由解析产出（`T-502` 起，S4-B）。前端类型里 {@code imageTotal/imageParsed} 是可选的
 * （{@code imageTotal?}），本批**仍然照发**（= 0），因为《06》§3.5 的列表把「图片 N/M」当作常驻列，
 * 不发会让前端在两个布局之间跳变。
 */
public final class DocDtos {

    private DocDtos() {
    }

    // ==================== T-510 · 文档列表 ====================

    /**
     * 列表行。字段与前端 {@code KbDocRow} **逐字对齐**（含可选的两项图片计数）。
     *
     * <p>{@code status} 是**域内真值**（{@code UPLOADED/PARSING/INDEXING/READY/FAILED}），
     * 前端 {@code statusTag()} 按它直接映射文案。这与 {@code sys_kb.status} 的情况不同
     * —— 后者的词表曾与前端分叉（B1 的 W1），故那边额外发了 `statusLabel`；此处词表一致，不需要派生字段。
     */
    public record DocRow(long id, String title, String type, long size, long chunkCount,
                         String status, long tableTotal, long tableParsed,
                         long imageTotal, long imageParsed,
                         String owner, String uploadedAt) {
    }

    /**
     * 列表分页体。
     *
     * <p><b>{@code total} 与该库文档总数同源，{@code displayed} 是本页条数</b>
     * —— 两者都从 {@code sys_document} 实查（判据 13 钉的就是这一条：
     * 不许出现"列表页说 128 篇、上传页说 0 篇"那类两个答案）。
     *
     * <p>前端把 {@code total} 渲染成「共 N 篇」（{@code KbUploadView.vue:540}）。
     */
    public record DocPage(List<DocRow> list, long total, long displayed) {
    }

    // ==================== T-501 · MD5 去重 ====================

    /**
     * 秒传 / 去重判断请求体。
     *
     * <p>由前端算好文件 MD5 后发出 —— **服务端不读文件内容**（这一步在真正上传之前，
     * 目的是让用户在大文件传之前就知道"这不必传"）。
     *
     * @param name 文件名（含扩展名）；命中时用于回显给用户
     * @param md5  32 位小写十六进制
     */
    public record Md5CheckRequest(String name, String md5) {
    }

    /**
     * 去重判断结果。
     *
     * <p>{@code hint} **无论命中与否都非空** —— 前端把它作为一句提示展示
     * （mock 的口径：命中给"将归档为新版本（不覆盖历史问答引用）"，未命中给空串）。
     * 本实现让未命中时也给一句，理由是空串在前端会渲染出一个空的提示框。
     *
     * @param duplicated 是否命中（判定键 = {@code owner_id + md5}，**注意含上传人**）
     * @param docId      命中的文档 id；未命中时 {@code null}
     * @param docTitle   命中的文档标题；未命中时 {@code null}
     */
    public record Md5Check(boolean duplicated, Long docId, String docTitle, String hint) {
    }

    // ==================== T-500 · 上传 ====================

    /**
     * 上传受理结果（**不阻塞**：文件已收下、任务已排队，解析是否成功要轮询 `T-509`）。
     *
     * @param docId      {@code sys_document.id}，本次新建
     * @param taskId     {@code AsyncTaskService} 的任务 id，供 `GET /doc/tasks/{taskId}` 轮询
     * @param name       文件名（回显用）
     * @param kbId       所属知识库
     * @param chunkTotal **本批恒 0** —— 分片总数要解析完才知道，S4-B 起由任务体回填
     *                   （前端在 `chunkTotal = 0` 时显示"处理中"而不是 0%，见 {@code KbUploadView.vue}）
     */
    public record UploadAccepted(long docId, String taskId, String name, long kbId, long chunkTotal) {
    }

    // ==================== T-509 · 任务状态 ====================

    /**
     * 任务进度快照。
     *
     * <p><b>逐字段对齐《10》§6 与前端 {@code docApi.task()}</b>
     * （{@code api/index.ts:202}）—— 与内层的 {@code AsyncTaskInfo} 不同：那个是**内部**完整快照
     * （含 {@code type/status/result/traceId}），本 record 是**对外投影**，
     * 只暴露前端真正消费的六项，避免把内部字段（尤其 {@code result} 里可能带的预签名 URL）
     * 无差别下发。
     *
     * @param errorMsg 仅失败态非空；**必须具体**（{@code G4-3}：不许只说"失败"两字）
     */
    public record TaskView(String taskId, String stage, int percent, int chunkDone,
                           int chunkTotal, String errorMsg) {
    }

    // ==================== T-511 · 未识别表格 / 解析问题清单 ====================

    /**
     * 逐页解析问题行（字段与前端 {@code ParseIssue} 逐字对齐，
     * {@code ai_knowledge_app/src/types/index.ts:555}：{@code docId/docTitle/pageNo/reason}）。
     *
     * <p><b>行粒度 = (文档, 页码)</b> —— 契约 {@code {docId, docTitle, pageNo, reason}}
     * 天然允许一篇文档多行（同一份扫描件 15 页就是 15 行）。
     * 这与 mock 的形状不同（mock 恰好 3 行 3 文档，历史上看不出来），
     * 所以前端告警条的"篇数"必须按 {@code docId} 去重 —— 那半边改动在本批前端步骤里。
     *
     * <p>{@code docTitle} 冗余进每一行而不是让前端 join：告警条一次渲染就要标题，
     * 而文档标题在展示期间不会变（改名走的是另一条接口），冗余是安全的。
     */
    public record ParseIssueRow(long docId, String docTitle, int pageNo, String reason) {
    }

    /**
     * 问题清单分页体（对齐《10》§14：{@code { list: [...], total }}）。
     *
     * <p>{@code total} 与 {@code list.size()} 恒相等 —— 本接口**有意不分页**
     * （后端截断 100 条保护数据库与前端，超出部分属异常情形，靠质量列与逐文档排查兜底）。
     */
    public record ParseIssuePage(List<ParseIssueRow> list, long total) {
    }
}
