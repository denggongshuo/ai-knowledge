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
     * 批量下载（`T-614` 流程本身还没做）· 定时清理（触发点在批处理里，本批拿不到真取证）。
     * **不为不存在的流程写代码** —— 承 `#187` 的判例。
     */
    private static final String TYPE_AUDIT_EXPORT = "audit-export";

    private static final String TYPE_DOC_UPLOAD = "doc-upload";

    private static final String TYPE_KB_REINDEX = "KB_REINDEX";

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
            if (coord.ownerId() == null) {
                // 没有提交人就没处可发（`TaskSubject.none()` 的任务）。不是错误，但要留痕：
                // 这一族的上一形态是 `#110`「导出任务 owner_id 为 NULL ⇒ 永远查不出是谁触发的」，
                // 那次是**提交方漏传坐标**，已被修；今天再现就说明有新入口又漏了。
                log.warn("通知发布跳过：任务没有提交人（owner_id 为 NULL）taskId={} type={}",
                        taskId, coord.type());
                return;
            }
            if (!noticeRepository.isEnabled(coord.ownerId(), payload.noticeType())) {
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
                    ? exportDone(result)
                    // 导出失败对用户是"想要的 CSV 没拿到" —— 归 ALERT（需求 §2.2 的「任务中断」）
                    : new NoticePayload(NoticeType.ALERT, "审计导出失败",
                            "导出任务未完成：" + reason(errorMsg) + "。可调整筛选条件后重新导出。",
                            List.of(NoticeAction.primary("重新导出", "/audit")), true, false);

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

            default -> null;
        };
    }

    /**
     * 导出成功 —— 🔴 **这条是本类存在理由的证明**：它要的是 `result` 里的 `fileUrl`，
     * 而那个东西**只在这一层拿得到**（`sys_async_task.result` 不持久化）。
     */
    private NoticePayload exportDone(Object result) {
        String fileUrl = stringField(result, "fileUrl");
        Long rows = longField(result, "rows");
        String detail = rows == null ? "" : "共 " + rows + " 行。";
        if (fileUrl == null || fileUrl.isBlank()) {
            // 结果里没有地址（不该发生）—— 不能给一个点不动的按钮，退回"去审计页"
            log.warn("导出任务完成但结果里没有 fileUrl，通知退回审计页入口");
            return new NoticePayload(NoticeType.SYSTEM, "审计导出完成",
                    "导出文件已生成，" + detail + "请到审计页重新导出获取下载地址。",
                    List.of(NoticeAction.primary("去审计页", "/audit")), false, true);
        }
        return new NoticePayload(NoticeType.SYSTEM, "审计导出完成",
                "导出文件已生成，" + detail + "下载地址有时效，请尽快下载。",
                List.of(NoticeAction.primary("下载导出文件", fileUrl)), false, true);
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
