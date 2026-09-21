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
                         long pagesTotal, long pagesWithText,
                         String owner, String uploadedAt,
                         String scanStatus, Boolean hasMacro) {
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
     *
     * <p>⚠️ <b>`S4-E2` 起多两个字段</b>（{@code V18} 的 `kind`/`disposition` 列）——
     * 在此之前"种类"是**拼在 {@code reason} 前面的文字前缀**，前端只能靠关键词猜。
     * 现在它是可查、可筛选的结构化字段，前端据此把告警条分成
     * 「内容缺失（未处理）」（{@code MISSING}）与「空白页」（{@code EMPTY_PAGE}）两类。
     *
     * <p>两字段**可空**：本批之前落的旧行没有这两列的值（`V18` 的列注释写了为什么
     * 不做回填 —— 回填出来的是猜的）。前端按"未知"展示，**不伪造**。
     *
     * @param kind        问题种类（{@code ParsedDocument.IssueKind} 名；旧行为 {@code null}）
     * @param disposition 处置结果（{@code MISSING}/{@code OCR_FILLED}/{@code EMPTY_PAGE}；旧行为 {@code null}）
     */
    public record ParseIssueRow(long docId, String docTitle, int pageNo, String reason,
                                String kind, String disposition) {
    }

    /**
     * 问题清单分页体（对齐《10》§14：{@code { list: [...], total }}）。
     *
     * <p>{@code total} 与 {@code list.size()} 恒相等 —— 本接口**有意不分页**
     * （后端截断 100 条保护数据库与前端，超出部分属异常情形，靠质量列与逐文档排查兜底）。
     */
    public record ParseIssuePage(List<ParseIssueRow> list, long total) {
    }

    // ==================== T-600 · 文档元信息（详情页 / 预览页共用） ====================

    /**
     * 文档元信息（对齐前端 {@code DocMeta}，{@code types/index.ts:693}）= {@code DocInfo} + 治理与统计字段。
     *
     * <h3>🔴 原始四个「未采集」字段，如今只剩两个仍故意为 {@code null}</h3>
     * <p>`S7-M06-A` 时这四个字段的**采集方/落库方在本仓都还不存在**。返回 {@code 0}
     * 或空数组会把"从没采过"说成"确实是 0" —— 而那是**一句关于内容的断言**（`D-2`：
     * 打点后补 = 数据永久缺失）。故当时一律 {@code null}，前端渲染成「未采集」而不是「0 次」。
     * 其后逐批转真（`S7-M06-C` 转 {@code citations}、`S7-M06-D` 转 {@code version}）——
     * 每一条"为什么还是 {@code null}"都**必须各自成立**，不能因为别人转真就顺手一起填：
     * <ul>
     *   <li>🆕 {@code citations} —— **`S7-M06-C` 起转真**：`T-804` 让
     *       {@code sys_retrieval_trace_chunk.cited} 由答案角标解析而来，本列 = 该文档的
     *       分片被**实际引用**过的次数。⚠️ 只数 {@code cited = 1} ——
     *       {@code cited is null}（本批之前的历史行）**不算**，那是"没采集"不是"没被引用"。
     *       ⇒ 所以本列的语义是「**本批之后**的引用次数」，历史为 0 不代表当时没被引；</li>
     *   <li>⚠️ {@code downVotes} —— **仍为 {@code null}**，且**根因与 `citations` 不同**：
     *       它要的是"这篇文档的答案被点踩几次"，是 {@code sys_chat_message.feedback}
     *       × {@code trace_id} × {@code doc_id} 的**跨表口径**。`T-804` 只解决了"被引用"，
     *       没解决"被点踩" ⇒ 两个字段**没有同一根因**，一起转真会让两条判据看起来像一条；</li>
     *   <li>🆕 {@code version} —— **`S7-M06-D` 起转真**：`V39` 给 {@code sys_document} 加了
     *       {@code version_no}（+ {@code doc_group_id} / {@code is_current}），本字段
     *       = {@code "v" + version_no}，与版本列表 / 回滚接口**同源同格式**。
     *       ⚠️ 仍**不要**拿 {@code sys_document.parse_version} 顶替：那是**解析器指纹**
     *       （`V20` 的注释明写"≠ 文档版本"），语义不同；</li>
     *   <li>{@code tags} —— 文档级内容标签**没有归属表**（{@code IndexEnhancer} 的类 javadoc
     *       自己写着「标签至今没有归属表」）。归属「标签归属」那次裁定 + `T-609`。</li>
     * </ul>
     *
     * @param fileDesc       如「PDF（原生文本，非扫描件）」—— 由 {@code type} + {@code pagesWithText} 派生，
     *                       不是数据库里的列
     * @param lastIndexAt    最近一次解析任务完成时刻；**没有索引任务过时为 {@code null}**
     *                       （不是空串：空串在前端会被渲染成一个空白的"最近索引"）
     * @param securityLevel     **当前生效**的密级 = `max(文档级, 库级)`（`I-6` · `S7-M06-B`）。
     *                          由 {@link com.knowledge.ai.core.enums.SecretLevel#max} 在 Java 侧算 ——
     *                          **不在 SQL 里取上界**（密级名按字母序与密级序错位，那样会做反）。
     *                          因 {@code sys_kb.secret_level} 是 {@code NOT NULL} ⇒ 本字段**恒非 null**
     * @param kbSecurityLevel   所属知识库的密级（原样回显，供页面做下拉的**下限**与对照）
     * @param ownSecurityLevel  文档**自己**的密级；**{@code null} = 未覆盖（继承知识库）** ——
     *                          需求 `06` §6.7 要的「内部（**继承知识库**）」标注靠它才渲染得出来。
     *                          ⚠️ 它**不是**"没有密级"（生效值见 {@code securityLevel}），
     *                          也不要拿它跟 {@code kbSecurityLevel} 比相等来判断"是否继承" ——
     *                          用户主动把文档设成与库相同的值，在数据上与"继承"是两回事
     * @param citations         该文档的分片被答案**实际引用**过的次数（`T-804` 起为真值）。
     *                          ⚠️ 只数 `cited = 1`，所以它**不覆盖**本批之前的历史提问
     * @param downVotes         见上，恒 {@code null}（**与 `citations` 不是同一根因**）
     * @param version           {@code "v" + version_no}（`S7-M06-D` 起为真值，见上）
     * @param tags              见上，恒 {@code null}
     */
    public record DocMetaView(long id, long kbId, String kbName, String title, String path, String type,
                             long size, int pageCount, long chunkCount, String status,
                             String securityLevel, String owner, String uploadedAt, String version,
                             String fileDesc, String department, String md5, String storagePath,
                             long tableTotal, long tableParsed, Long imageTotal, Long imageParsed,
                             Long citations, Long downVotes, String lastIndexAt,
                             String kbSecurityLevel, String ownSecurityLevel, List<String> tags) {
    }

    // ==================== T-602 · 目录树 ====================

    /**
     * 目录项（对齐前端 {@code TocItem}，{@code types/index.ts:762}）。
     *
     * <p><b>{@code id} 是分片 id 的字符串形式</b>（{@code String.valueOf(sys_chunk.id)}）——
     * 契约里它的类型是 {@code string}，而 mock 用的是 {@code 'h3'}/{@code 'h2-1'} 这类
     * **层级引用串**。本批取真 id，理由：前端点目录要**滚到那个标题块**
     * （需求 §4.6 的联动），只有一个真 id 能定位到块；引用串定位不了任何东西。
     *
     * @param level 标题层级 = 该 HEADING 块 {@code title_path} 按 {@code " > "} 分出的段数
     *              （分隔符复用 {@code ChunkingService.TITLE_PATH_SEPARATOR}，不另写字面量）
     */
    public record TocItem(String id, String label, int level) {
    }

    public record TocView(List<TocItem> list) {
    }

    // ==================== T-603 · 分片列表 + 分片统计 ====================

    /**
     * 分片行（对齐前端 {@code ChunkInfo}，{@code types/index.ts:108}）。
     *
     * <p><b>{@code tokens} 是派生的，不是列</b>：{@code sys_chunk} 没有 token 计数列，
     * 而切分窗口本来就是按 token 量的 ⇒ 复用 {@code ChunkingOptions#estimateTokens}
     * （{@code IdentifierTokenizer#windowTokens}，`T-506`）。⚠️ 它与**真实嵌入模型的
     * tokenizer 有偏差**（那是模型私有、本仓无从复现），需要精确值的地方应显式用自己的
     * tokenizer —— 这与"分片窗口用哪个口径"是同一个口径，不是新引入的近似。
     *
     * @param index        文档内阅读顺序（{@code order_no}，1 起）
     * @param parentId     顶层块为 {@code null}；子块指向 {@code blockType = TABLE} 的父块。
     *                     ⚠️ **禁止用 `0` 表示"无父块"**（见 {@code SysChunk#parentId} 的 javadoc）
     */
    public record ChunkRow(long id, long docId, String docTitle, String titlePath, String blockType,
                           int index, String content, int tokens, Long parentId, Long childrenCount) {
    }

    /**
     * 分片统计（对齐前端 {@code ChunkStat}，{@code types/index.ts:741}）。
     *
     * <h3>🔴 {@code total} = **顶层块数**，四个桶的合计恒等于它（`G7-2`）</h3>
     * <p>表格子块（{@code parent_id is not null}）**不进任何一个桶、也不进 {@code total}**，
     * 而是单独走 {@code tableSubBlocks}。于是：
     * <pre>
     *   text + table + image + summary == total          （桶内自洽）
     *   total + tableSubBlocks          == 全表行数       （没有一行掉在地上）
     * </pre>
     * <b>两条都要断言</b>：只断言第一条时，「把 {@code total} 改小」就能假绿（`汇总.md` §5
     * 那条「A 的合计 = B 先问三句」）。
     *
     * <p>口径与 {@code sys_document.chunk_count} 同源（那里也只计顶层块）——
     * 所以详情页两处显示的"分片数"不会打架。
     *
     * @param image            图片块数；**可空**（`T-512` 之前落的旧文档没有 IMAGE 块，
     *                         与 {@code ChunkInfo.blockType} 的枚举演进同源）
     * @param tableSubBlocks   表格子块数（**不属于**上面四个桶）
     * @param avgTokens        **分母与 {@code total} 同源**（顶层块）——分母与分子不同源时，
     *                         两个数说的不是一批行。空文档（{@code total = 0}）为 {@code null}
     *                         而不是 0（「判不了与判否分开报」）
     * @param titlePathCoverage 标题路径覆盖率（%）：{@code title_path} 非空的**顶层块** ÷ 顶层块。
     *                         ⚠️ 只算顶层块：表格子块**继承父块的 {@code title_path}**
     *                         （{@code ChunkingService#appendTable} 父子传同一个），
     *                         把它们算进来会让一篇含大表的文档"覆盖率虚高"
     */
    public record ChunkStat(long total, long text, long table, long summary, Long image,
                            long tableSubBlocks, Double avgTokens, double titlePathCoverage) {
    }

    /**
     * 分片列表分页体（对齐《10》§14：{@code { list, total, stat }}）。
     *
     * <p>{@code total} = **过滤后**的条数（带 {@code type} 时是那一档的条数），
     * 与 {@code stat.total} 不是一回事 —— 后者是不带过滤的全量顶层块数。
     */
    public record ChunkPage(List<ChunkRow> list, long total, ChunkStat stat) {
    }

    // ==================== T-604 · 父块 + 全部子块（`G7-1`） ====================

    /**
     * 父子块关系（`G7-1`：**父块完整内容 + 全部子块**）。
     *
     * <h3>🔴 相对《10》§14 的契约差异：多了 {@code children[]}，去掉了 {@code child}</h3>
     * <p>原契约是 {@code { child: {ref, content}, parent: {...} }} —— 而 `G7-1` 的原文要求
     * 「返回父块完整内容 + **全部**子块」，**一个 {@code child} 表达不了"全部"**。
     * 那个形状是 mock 的产物（mock 里恰好只有一个子块，历史上看不出来）。
     * 本批按门禁改：{@code children[]} + {@code total}；{@code child} **移除**
     * （它的原始用途是"检索命中场景显示命中的那一个子块"，而检索详情面板用的是
     * 打点里的 {@code trace.chunks}，从不调本接口 ⇒ 没有真实消费方会被破坏）。
     *
     * <h3>⚠️ {@code rows} 与 {@code returnedRows} 的口径</h3>
     * <p>{@code rows} = **数据行数（不含表头）**，与 {@code TableWindow.Window#totalRows} 同口径
     * （也即"子块数与数据行一一对应"那条前提的同一批行）。
     * <b>本页返回整表</b>（{@code returnedRows == rows}）：需求 §4.5 对这一页的措辞就是
     * 「N 行 × M 列**全部返回**」，而"命中行 ± 窗口"的裁剪只属于**检索回表**
     * （{@code TableWindow}，`T-705`）—— 这一页是用户自己点进来看的，没有命中行。
     *
     * @param parent  父块；{@code ref} 形如 {@code T-03}（{@code T-} + 两位 {@code order_no}）
     * @param children 全部子块（行式文本），按 {@code order_no} 升序
     * @param total   {@code children.size()} 的显式副本（前端不必再算，也让"全部"可断言）
     */
    public record ParentChunkView(ParentBlock parent, List<ChildBlock> children, long total) {

        /**
         * @param ref       表格块引用号（{@code T-} + 两位 {@code order_no}）—— 需求 §4.5 的展示形态
         * @param title     **表标题**（Markdown 里的 {@code **…**} 前导行）；没有为空串。
         *                  ⚠️ **刻意不回落成"所属章节"**：那样同一个字段会指两件事。
         *                  所属章节走 {@code titlePath}，页面上怎么显示是前端的事
         * @param titlePath 所属章节（标题路径）
         * @param rows      数据行数（不含表头）；父块内容不是管道表时为 0
         * @param cols      列数
         * @param returnedRows 本次**实际返回**的数据行数（本页恒等于 {@code rows}）
         */
        public record ParentBlock(long id, int orderNo, String ref, String title, String titlePath,
                                  int rows, int cols, int returnedRows, String content) {
        }

        /** @param ref 形如 {@code T-03-002}（父块 ref + 三位子块序号） */
        public record ChildBlock(long id, int orderNo, String ref, String content) {
        }
    }

    // ==================== T-605 · 索引任务记录 ====================

    /**
     * 索引任务行（对齐前端 {@code IndexTask}）。
     *
     * <p>{@code durationSec} 由 {@code finished_at - locked_at} 派生（{@code sys_async_task}
     * **没有 started_at**，{@code locked_at} 是"被领取执行"的时刻，是最接近的起点）。
     * 未完成时为 {@code null}。
     *
     * @param version **恒 {@code null}** —— 同 {@link DocMetaView} 的 {@code version}：
     *                那不是"这份任务没有版本"，而是**没有该来源**。不要拿 {@code parse_version}
     *                顶替（语义不同）
     */
    public record IndexTaskRow(String id, String stage, String status, String at,
                               Long chunkCount, Long durationSec, String detail, String version) {
    }

    public record IndexTaskPage(List<IndexTaskRow> list, long total) {
    }

    // ==================== T-601 · 属性修改（写侧） ====================

    /**
     * `PATCH /api/doc/{id}/attributes` 的请求体（需求 `06` §6.7 的字面 body：
     * `{ secretLevel, kbId, tags[] }`）。
     *
     * <p><b>三个字段都可缺省</b>，语义是"**只改我传了的那些**" —— 传 {@code null} 不是
     * "把它清空"。所以它们都是包装类型，不能用原始类型（那会把"没传"读成 0/空）。
     *
     * <p>🔴 `tags` **本版本不支持**（文档级内容标签**没有归属表**，见 {@link DocMetaView} 的
     * {@code tags}）。传了不会静默忽略：**明确回业务错**，否则用户以为存上了
     * （那是本仓最不能接受的形态 —— 与"假导出"同类）。
     *
     * @param secretLevel 新的文档密级。**只能提高**：必须 ≥ 当前生效密级
     *                    （{@code max(文档, 库)}），否则 `10030`（`G7-3`）
     * @param kbId        **移动到**该知识库（不传 = 不动）。要同时满足
     *                    「对源库 `MANAGE`」与「对目标库 `doc:upload`」—— 见
     *                    {@code DocAttributeService} 的口径说明
     */
    public record AttributesRequest(String secretLevel, Long kbId, List<String> tags) {
    }

    /**
     * 属性保存结果（对齐前端 `api/index.ts:172` 的
     * `{ saved: boolean; docId: number; needReindex: boolean }`）。
     *
     * <p>{@code needReindex} = **本次修改触发了重建索引**（移动知识库那一路）。
     * 它对应需求 `06` §6.7 的两句：「属性修改**后立即生效，无需重建索引**；
     * **移动知识库除外**」+「移动知识库**触发重建**」—— 于是页面据此提示用户
     * 「已移动，正在重建索引」。⚠️ 只改密级/不传 `kbId` 时恒 {@code false}。
     */
    public record AttributeSaved(boolean saved, long docId, boolean needReindex) {
    }

    // ==================== T-610 · 单篇重建 ====================

    /**
     * 单篇重建的受理结果（对齐前端 `api/index.ts:178` 的
     * `{ docId: number; taskId: string; status: string }`）。
     *
     * <p>🔴 **它只负责受理**（与库级的 `POST /kb/{id}/reindex` 同一条口径，`10` §5）：
     * 重建要重新解析整篇（含表格识别与图片理解），是长任务 ⇒ 立刻返回 {@code taskId}，
     * 进度走 `GET /doc/tasks/{taskId}`。
     *
     * <p>{@code status} 恒为 {@code ACCEPTED}：此刻作业**刚入队、还没被 worker 领取**，
     * 报 {@code RUNNING} 是一句关于执行状态的谎（与 `10:708` 给库级那条登记的值一致）。
     */
    public record DocReindexAccepted(long docId, String taskId, String status) {
    }

    // ==================== T-606 · 版本列表（S7-M06-D · G7-5） ====================

    /**
     * 单个版本行（对齐前端 {@code DocVersion}，{@code types/index.ts:777}）。
     *
     * <p>🔴 一个版本 = 一条 {@code sys_document} 行（版本模型的选型见 `V39` 头注 + 计划 §5）。
     * 同一 {@code doc_group_id} 下的多行按 {@code version_no} 降序排出，**恰有一行** {@code current=true}
     * —— 正常态下它也是版本号最高的那一行，但"是当前版本"与"版本号最高"是两件事
     * （回滚失败留下的作废行会让两者分家，见 {@code hasNewer} 一条）。
     *
     * @param version    展示版本号，形如 {@code v3}（= {@code "v" + version_no}）。⚠️ 前端 `rollback`
     *                   把它原样回传，服务端解析回 {@code version_no}（见 {@code DocVersionService#parseVersion}）
     * @param size       该版本的文件字节数（{@code size_bytes}）
     * @param chunkCount 该版本的分片数（{@code chunk_count}）
     * @param owner      该版本的上传人（真名，取不到回退用户名 / "未知"）
     * @param at         该版本的上传时刻（{@code created_at}，格式化）
     * @param current    是否当前版本（{@code is_current}）—— 前端据它**隐藏「回滚」按钮**（§6.7）
     * @param note       版本备注；取 {@code remark}（补录备注）或 {@code null}。⚠️ 不伪造 ——
     *                   没有备注就是 {@code null}，前端不显示（不是显示空串）
     * @param hasNewer   同组是否存在**更高版本号**的行（需求 §6.4「已有更新版本」标记）。
     *                   ⚠️ 原来是"当前版本恒 {@code false}"，**实测被推翻**：回滚失败会留下
     *                   一行版本号更高但已作废的残骸（{@code DocVersionService#abandon}），
     *                   此时当前版本的 {@code hasNewer} **就是 true**。判据钉在
     *                   {@code DocVersionFlowTest#abandonedVersionIsNotReportedAsCurrent}
     */
    public record DocVersionRow(String version, long size, long chunkCount, String owner,
                               String at, boolean current, String note, boolean hasNewer) {
    }

    /**
     * 版本列表体（对齐前端 {@code docApi.versionList}，`api/index.ts:147`：
     * {@code { current: string, list: DocVersion[] }}）。
     *
     * @param current 当前版本号（取**带 {@code is_current=1} 的那一行**，不是 {@code max(version_no)}
     *                —— 回滚失败会留下版本号更高的作废行，用 max 会把它报成当前版本；
     *                见 {@code DocVersionService#list}）——前端标题栏直接用
     * @param list    全组版本，**按 version_no 降序**（当前版本在最前）
     */
    public record DocVersionList(String current, List<DocVersionRow> list) {
    }

    // ==================== T-607 · 版本对比（仅元数据 · §6.8 文本 diff 预留） ====================

    /**
     * 版本对比结果（`GET /doc/{id}/versions/{v}/diff?against={v2}`）。
     *
     * <p>🔴 **v1 只做元数据对比**（需求 §6.8 逐字：「不做版本内容的在线 diff 高亮对比 ——
     * v1 只做元数据对比 + 文本 diff 接口预留」）。所以本结构给两个版本的**可比元数据对照**，
     * 而 {@link #textDiff} **恒为 {@code null}**（接口预留，不是"这次没算出来"）。
     *
     * @param left     基准版本的元数据（URL 里的 {@code {v}}）
     * @param right    对比版本的元数据（query 里的 {@code against={v2}}）
     * @param textDiff **恒 {@code null}** —— §6.8 明确 v1 不做文本 diff。留字段是为了"接口预留"，
     *                 让将来接文本 diff 时**契约不变**（前端已能识别 {@code null} = 不展示 diff 区）
     */
    public record VersionDiff(VersionMeta left, VersionMeta right, String textDiff) {

        /**
         * 一个版本的可比元数据。
         *
         * @param version    版本号 {@code v{n}}
         * @param size       文件字节数
         * @param chunkCount 分片数
         * @param tableTotal 表格总数（元数据对比里最能看出"内容变没变"的一项）
         * @param owner      上传人
         * @param at         上传时刻
         */
        public record VersionMeta(String version, long size, long chunkCount, long tableTotal,
                                  String owner, String at) {
        }
    }

    // ==================== T-608 · 回滚（G7-6） ====================

    /**
     * 回滚结果（对齐前端 {@code docApi.rollback}，`api/index.ts:148`：
     * {@code { docId, version, rolledBack, note }}）。
     *
     * <p>🔴 回滚**不改写历史**（需求 §6.4「建议保留一条新版本记录而不是改写历史，保证可追溯」）：
     * 回到 {@code v1} 会**新增**一条版本（内容复制自 {@code v1}、{@code is_current=1}），
     * 旧的当前版本翻 {@code is_current=0} 并清其向量。历史问答的引用锚定在
     * {@code sys_retrieval_trace_chunk}，回滚**一个字节都不动那张表** ⇒ 引用不变（`G7-6`）。
     *
     * @param docId      **新版本**的 doc id（不是被回滚到的那个旧 id）
     * @param version    新版本的版本号（{@code "v" + newVersionNo}）
     * @param rolledBack 恒 {@code true}（失败走异常，不用这个字段表达失败）
     * @param note       给用户看的一句话（如"已回滚到 v1（保留新版本记录，可追溯）"）
     */
    public record RollbackResult(long docId, String version, boolean rolledBack, String note) {
    }

    /**
     * 回滚请求体（对齐前端 {@code docApi.rollback}，`api/index.ts:148~149`：
     * {@code http.post(..., { version })}）。
     *
     * <p>⚠️ 版本号走**请求体**而不是路径 —— 前端就是这么发的（{@code DocDetailView.vue:237}）。
     * 改成路径参数会让前端那行代码静默失效（后端收到空 body ⇒ 报"请指定版本号"，
     * 而现象是"点了回滚没反应"）。
     *
     * @param version 目标版本号，形如 {@code v2}（也接受 {@code 2}，见
     *                {@code DocVersionService#parseVersion}）
     */
    public record RollbackRequest(String version) {
    }
}
