package com.knowledge.ai.core.notice;

import com.knowledge.ai.core.async.AsyncTaskService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * **通知的唯一产出点**（`T-B03`）—— 需求《09》§2.9：「通知生成点统一（如异步任务完成回调处
 * 集中产出），**避免散落在各业务代码里**」。
 *
 * <h3>🔴 为什么不能挂在 `SysAsyncTaskRepository#markDone` 上（本节是本类的存在理由）</h3>
 * <p>终态写入有**两条路**，它们的唯一交汇点是 `markDone` / `markFailed`：
 * <pre>
 *   ① TaskContext#complete / #fail → persistTerminal → repository.markDone / markFailed
 *   ② reportDone / reportFailed    → ├ 内存命中：走 ①
 *                                    └ 内存未命中：**直达** repository.markDone / markFailed
 * </pre>
 * <p>把通知挂在那两个交汇点上**看起来最省事**，但它是错的：
 * {@code markDone(taskId, chunkDone, chunkTotal)} 的入参里**没有 result**，而
 * `SysAsyncTaskRepository#find` 的 javadoc 写着「⚠️ **`result` 不持久化**」。
 * ⇒ 挂在那里 = 通知**永远拿不到任务结果** = {@code audit-export} 的通知**丢掉下载地址** =
 * 直接违反需求 §2.10 的「每条通知**都有至少一个可执行动作**」。
 *
 * <p>所以本类的调用点是 **`AsyncTaskService` 里那 4 处**（① 的 complete/fail + ② 的库回退分支），
 * 它们**全部只调下面这两个方法**。为了让"少接一条路"这件事**当场可见**而不是等用户抱怨
 * 没收到通知，有一条判据钉着它：`NoticePublishFlowTest#bothTerminalPathsPublish`。
 *
 * <h3>⚠️ 发布失败**绝不连累任务终态**</h3>
 * <p>两个方法整体包 try/catch：任务终态是**事实源**（"这事到底做完了没"），
 * 通知是**衍生品**。让一个衍生品的写入失败去回滚事实源，是把小事故升级成大事故。
 * 但**必须留痕** —— 静默吞掉会让人以为通知发出去了，所以是 {@code warn} + 带上 taskId
 * （能直接去 `sys_async_task` 查）。这条取舍与 {@code TaskContext#persistProgress} 同源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoticePublisher {

    /**
     * **本批覆盖的任务类型**（需求 §2.1 列了六类，这里只覆盖能真取证的三种）。
     *
     * <p>覆盖：{@code audit-export}（真的能跑一次导出）· {@code doc-upload}（真的能传一篇）·
     * {@code KB_REINDEX}（需求 §2.10 点名要「全库重建完成 + 查看检索质量变化入口」）。
     *
     * <p>⚠️ **不覆盖、且是刻意不覆盖的**（见计划 §3.1「明确不做」⑥）：
     * {@code DOC_SCAN}（需求未点名要通知，且它的结果形状本批没核实）·
     * 定时清理（触发点在批处理里，本批拿不到真取证）。
     * **不为不存在的流程写代码** —— 承 `#187` 的判例。
     *
     * <p>⚠️ **{@code batch-download} 本批（`S7-M06-E`）已接入** —— 上面那句"批量下载刻意不覆盖"
     * 的前提（"流程还没做"）已随本批消失。
     */
    private static final String TYPE_AUDIT_EXPORT = "audit-export";

    /**
     * 运营报表导出（`T-A02`）。⚠️ 与 {@code OpsExportService.TASK_TYPE} **逐字一致**
     * —— 那两处是同一个协议的两端，改一处必须改另一处（用例 `OpsExportFlowTest` 钉住了它）。
     */
    private static final String TYPE_OPS_EXPORT = "ops-export";

    private static final String TYPE_DOC_UPLOAD = "doc-upload";

    private static final String TYPE_KB_REINDEX = "KB_REINDEX";

    /**
     * 打包下载（`T-614` · `S7-M06-E`）。⚠️ 与 {@code DownloadService.TASK_TYPE} **逐字一致**。
     *
     * <p>由 `S6-M11` §9-⑨「批量下载刻意不产通知（流程还没做）」升级而来 —— 本批流程做了，
     * 通知就得接上（需求 `06` §6.6：打包完成经**通知中心**给地址）。归 {@code SYSTEM}
     * （用户 2026-09-21 裁定：语义最近，不动前后端 NoticeType 契约）。
     */
    private static final String TYPE_BATCH_DOWNLOAD = "batch-download";

    /**
     * 下载记录清单导出（`T-615`）。⚠️ 与 {@code DownloadService.EXPORT_TASK_TYPE} **逐字一致**。
     *
     * <p>导的是**下载记录**（`sys_download_record` 里"我下过什么"），不是文档列表 ——
     * 原型 `09-文档下载.html:660` 把 {@code POST /doc/export-list} 逐字注成
     * 「导出下载记录清单」。
     */
    private static final String TYPE_DOC_EXPORT_LIST = "doc-export-list";

    /**
     * 下载相关通知的兜底入口（`T-613`~`T-615`）。
     *
     * <p>为什么不是 {@code kbPath(kbId)}：这几个任务**没有知识库维度**
     * （用户可以跨库勾选，{@code TaskSubject.kbId} 留 null），兜底到 `/kb` 会让
     * 「回文档列表」跳到知识库列表 —— 那不是他刚才待的页面。下载任务列表在 `/doc/download`。
     */
    private static final String DOWNLOAD_PATH = "/doc/download";

    /**
     * 回收站超期清理（`T-616`）。⚠️ 与 {@code RecycleService.TASK_TYPE_CLEANUP} **逐字一致**。
     *
     * <p>它是本仓**第一个没有提交人**的任务类型（定时任务提交，`TaskSubject.none()`）
     * ⇒ 也是第一个走**广播**的通知 —— 见 {@link #BROADCAST_TYPES}。
     */
    private static final String TYPE_RECYCLE_CLEANUP = "recycle-cleanup";

    /**
     * **本来就该广播**的任务类型（没有"提交人"这个概念）。
     *
     * <h3>🔴 为什么要一张白名单，而不是"owner 为 NULL 就广播"</h3>
     * <p>{@code owner_id} 为 NULL 有**两种完全不同的成因**：
     * <ul>
     *   <li><b>本来就该广播</b>（定时/系统任务 —— 它压根没有提交人）；</li>
     *   <li><b>提交方漏传坐标</b>（`#110`：`AuditExportService` 当年就没传 `TaskSubject`
     *       ⇒ 那条导出任务**永远查不出是谁触发的**）。</li>
     * </ul>
     * <p>把两者混成一条"NULL 就广播"，第二种缺陷会**从"少一条通知"变成"给所有人发一条
     * 莫名其妙的通知"** —— 那是把静默缺陷升级成噪声缺陷。所以这里**逐个类型点名**：
     * 白名单之外的 NULL 仍然打那条 warn（提醒提交方漏了坐标）。
     *
     * <p>⚠️ 这张表还有个副产品：它让「哪些通知是广播」这件事**可检索**
     * —— 「这条通知为什么所有人都收到了」有一个能查的答案。
     */
    private static final java.util.Set<String> BROADCAST_TYPES = java.util.Set.of(TYPE_RECYCLE_CLEANUP);

    private final NoticeRepository noticeRepository;

    /** 任务成功。{@code result} 是任务体自己给的结果对象（形状见下面的各分支）。 */
    public void onTaskDone(String taskId, Object result) {
        publish(taskId, true, result, null);
    }

    /** 任务终态失败。 */
    public void onTaskFailed(String taskId, String errorMsg) {
        publish(taskId, false, null, errorMsg);
    }

    // ==================== 私有 ====================

    private void publish(String taskId, boolean done, Object result, String errorMsg) {
        try {
            NoticeRepository.TaskCoord coord = noticeRepository.findTask(taskId);
            if (coord == null) {
                // 任务行查不到：可能是它被清理了（测试收尾）或 taskId 不对。
                // 不是错误路径 —— 不发通知即可，但要留痕（否则"通知少了"无从查起）。
                log.warn("通知发布跳过：查不到任务行 taskId={}（已被清理？）", taskId);
                return;
            }
            NoticePayload payload = payloadOf(coord, done, result, errorMsg);
            if (payload == null) {
                // 该任务类型本批不产通知 —— 正常路径，不记日志（否则每篇文档一条噪声）
                return;
            }
            boolean broadcast = coord.ownerId() == null;
            if (broadcast && !BROADCAST_TYPES.contains(coord.type())) {
                // 没有提交人就没处可发（`TaskSubject.none()` 的任务）。不是错误，但要留痕：
                // 这一族的上一形态是 `#110`「导出任务 owner_id 为 NULL ⇒ 永远查不出是谁触发的」，
                // 那次是**提交方漏传坐标**，已被修；今天再现就说明有新入口又漏了。
                log.warn("通知发布跳过：任务没有提交人（owner_id 为 NULL）taskId={} type={}",
                        taskId, coord.type());
                return;
            }
            // ⚠️ 广播**不能过 `isEnabled`**：那条查询按 `user_id` 找用户设置，
            //    而广播没有"哪个人"可查。好在它本来也不该被某一个人的开关静音 ——
            //    「清理失败」是全站性的运维事件（需求 06 §7.9）。
            if (!broadcast && !noticeRepository.isEnabled(coord.ownerId(), payload.noticeType())) {
                // 用户在设置里关掉了这一类（需求 §2.5）。debug 级 —— 这是**预期行为**，不是异常。
                log.debug("通知按用户设置跳过：userId={} type={}", coord.ownerId(), payload.noticeType());
                return;
            }
            noticeRepository.insert(payload.noticeType().name(), payload.title(), payload.content(),
                    NoticeActionsJson.write(payload.actions()), payload.needAction(),
                    payload.muteable(), coord.ownerId(), coord.type(), taskId);
            log.info("通知已产出 taskId={} type={} noticeType={} ownerId={}",
                    taskId, coord.type(), payload.noticeType(), coord.ownerId());
        } catch (Exception ex) {
            log.warn("通知发布失败（**不影响任务终态**）：taskId={} err={}", taskId, ex.getMessage());
        }
    }

    /**
     * 任务类型 + 终态 → 通知内容。**四类通知在此一处映射，别处不许再写**（`T-B03` 的字面要求）。
     *
     * @return {@code null} = 该类型本批不产通知（调用方静默跳过）
     */
    private NoticePayload payloadOf(NoticeRepository.TaskCoord coord, boolean done,
                                    Object result, String errorMsg) {
        Long kbId = coord.kbId();
        Long docId = coord.docId();
        return switch (coord.type() == null ? "" : coord.type()) {
            case TYPE_AUDIT_EXPORT -> done
                    ? exportDone("审计导出完成", "导出文件", "去审计页", "/audit", result)
                    // 导出失败对用户是"想要的 CSV 没拿到" —— 归 ALERT（需求 §2.2 的「任务中断」）
                    : new NoticePayload(NoticeType.ALERT, "审计导出失败",
                            "导出任务未完成：" + reason(errorMsg) + "。可调整筛选条件后重新导出。",
                            List.of(NoticeAction.primary("重新导出", "/audit")), true, false);

            // 运营报表导出（`T-A02`）。走与审计导出**同一段** fileUrl 处理 —— 见 exportDone
            case TYPE_OPS_EXPORT -> done
                    ? exportDone("运营报表导出完成", "导出文件", "回运营页", "/ops/qa", result)
                    : new NoticePayload(NoticeType.ALERT, "运营报表导出失败",
                            "导出任务未完成：" + reason(errorMsg) + "。可调整筛选条件后重新导出。",
                            List.of(NoticeAction.primary("重新导出", "/ops/qa")), true, false);

            // 下载记录清单导出（`T-615`）。与上面两个导出同一形状（都是 CSV + fileUrl）
            case TYPE_DOC_EXPORT_LIST -> done
                    ? exportDone("下载记录导出完成", "导出文件", "回下载页", DOWNLOAD_PATH, result)
                    : new NoticePayload(NoticeType.ALERT, "下载记录导出失败",
                            "导出任务未完成：" + reason(errorMsg) + "。可到下载页重新导出。",
                            List.of(NoticeAction.primary("回下载页", DOWNLOAD_PATH)), true, false);

            case TYPE_DOC_UPLOAD -> done
                    ? new NoticePayload(NoticeType.UPLOAD, "解析完成",
                            "文档已解析入库" + (docId == null ? "" : "（文档 #" + docId + "）")
                                    + "，可检索。",
                            docId == null
                                    ? List.of(NoticeAction.primary("查看文档列表", kbPath(kbId)))
                                    : List.of(NoticeAction.primary("查看文档", "/doc/" + docId)),
                            false, true)
                    : new NoticePayload(NoticeType.UPLOAD, "解析失败",
                            "文档解析失败：" + reason(errorMsg) + "。重试不会产生脏向量数据。",
                            docId == null
                                    ? List.of(NoticeAction.primary("重试上传", kbPath(kbId)))
                                    : List.of(NoticeAction.primary("去文档详情", "/doc/" + docId),
                                            NoticeAction.secondary("查看解析质量", kbPath(kbId))),
                            true, true);

            // 打包下载完成（`T-614`）。走与两个导出**同一段** fileUrl 处理（zip 的下载地址在 result.fileUrl）
            case TYPE_BATCH_DOWNLOAD -> done
                    ? exportDone("打包下载完成", "打包文件", "回下载页", DOWNLOAD_PATH, result)
                    : new NoticePayload(NoticeType.ALERT, "打包下载失败",
                            "打包任务未完成：" + reason(errorMsg) + "。可减少文件数量后重试。",
                            List.of(NoticeAction.primary("回下载页", DOWNLOAD_PATH)), true, false);

            // 需求 §2.10 点名：「全库重建完成后产出通知，**并附「查看检索质量变化」入口**」
            case TYPE_KB_REINDEX -> done
                    ? new NoticePayload(NoticeType.SYSTEM, "全库重建完成",
                            "知识库索引已重建，检索已切到新版本。可查看检索质量变化。",
                            List.of(NoticeAction.primary("查看检索质量变化", "/ops/retrieval"),
                                    NoticeAction.secondary("回知识库", kbPath(kbId))),
                            false, true)
                    : new NoticePayload(NoticeType.ALERT, "全库重建失败",
                            "重建任务中断：" + reason(errorMsg) + "。当前检索仍指向重建前的版本，"
                                    + "服务未受影响。请重试重建。",
                            List.of(NoticeAction.primary("重试重建", kbPath(kbId))), true, false);

            // 回收站超期清理（`T-616`）。🔴 **成功不产通知**（"清理跑成功了"不值得打扰任何人），
            // 只有**失败**才发 —— 且发**广播**（它没有提交人，白名单见 BROADCAST_TYPES）。
            // 需求 06 §7.9 的原话：「定时清理任务需幂等，且失败要有告警（走通知中心）」。
            case TYPE_RECYCLE_CLEANUP -> done ? null
                    : new NoticePayload(NoticeType.ALERT, "回收站清理失败",
                            "超期清理未完成：" + reason(errorMsg)
                                    + "。⚠️ 清理失败会造成**存储泄漏**（到期对象与向量留在库里），"
                                    + "请尽快查看服务日志并手工重试。",
                            List.of(NoticeAction.primary("去回收站", "/recycle")), true, false);

            default -> null;
        };
    }

    /**
     * 导出成功 —— 🔴 **这条是本类存在理由的证明**：它要的是 `result` 里的 `fileUrl`，
     * 而那个东西**只在这一层拿得到**（`sys_async_task.result` 不持久化）。
     *
     * <p>三处（审计件 `T-304` / 运营报表 `T-A02` / 下载记录 `T-615`）共用这一段：
     * "`fileUrl` 有就用它当按钮、没有就退回页面入口"这条规则**只该有一份** ——
     * 各写一份的话，一边记得处理 `fileUrl` 为空、另一边忘，现象是"通知里有个点不动的按钮"。
     * 所以标题与兜底入口做成参数，**规则本身不复制**。
     *
     * @param noun          产出物的**中文名**（"导出文件" / "打包文件"）。做参数是为了让
     *                      三个caller各自的正文与按钮文案都对：复用同一段规则不等于
     *                      复用同一句话 —— 把 zip 叫成"导出文件"就是文案与事实不符
     * @param fallbackLabel / {@code fallbackPath}：`fileUrl` 缺失时退回去的入口
     */
    private NoticePayload exportDone(String title, String noun, String fallbackLabel,
                                     String fallbackPath, Object result) {
        String fileUrl = stringField(result, "fileUrl");
        Long rows = longField(result, "rows");
        String detail = rows == null ? "" : "共 " + rows + " 行。";
        if (fileUrl == null || fileUrl.isBlank()) {
            // 结果里没有地址（不该发生）—— 不能给一个点不动的按钮
            log.warn("任务完成但结果里没有 fileUrl，通知退回页面入口：title={}", title);
            return new NoticePayload(NoticeType.SYSTEM, title,
                    noun + "已生成，" + detail + "请到页面重新获取下载地址。",
                    List.of(NoticeAction.primary(fallbackLabel, fallbackPath)), false, true);
        }
        return new NoticePayload(NoticeType.SYSTEM, title,
                noun + "已生成，" + detail + "下载地址有时效，请尽快下载。",
                List.of(NoticeAction.primary("下载" + noun, fileUrl)), false, true);
    }

    private static String kbPath(Long kbId) {
        return kbId == null ? "/kb" : "/kb/" + kbId + "/upload";
    }

    /** 失败原因：**不许只说"失败"两字**（《02》§静默降级视为缺陷）。 */
    private static String reason(String errorMsg) {
        return errorMsg == null || errorMsg.isBlank() ? "未提供细节，请查看任务记录" : errorMsg;
    }

    /** 从任务结果里取一个字符串字段（结果是 Map / record 都可能，只认 Map —— 那是导出用的形状）。 */
    private static String stringField(Object result, String key) {
        if (result instanceof Map<?, ?> map) {
            Object v = map.get(key);
            return v == null ? null : String.valueOf(v);
        }
        return null;
    }

    private static Long longField(Object result, String key) {
        if (result instanceof Map<?, ?> map) {
            Object v = map.get(key);
            return v instanceof Number n ? n.longValue() : null;
        }
        return null;
    }

    /** 一条待落库的通知。 */
    private record NoticePayload(NoticeType noticeType, String title, String content,
                                 List<NoticeAction> actions, boolean needAction, boolean muteable) {
    }
}
