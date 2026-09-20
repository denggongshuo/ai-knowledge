package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.DocStatus;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.ops.MissKeyNormalizer;
import com.knowledge.ai.core.ops.MissStatus;
import com.knowledge.ai.core.ops.QaMissRepository;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.core.setting.SystemSettings;
import com.knowledge.ai.dto.ops.OpsDtos;
import com.knowledge.ai.entity.SysUser;
import com.knowledge.ai.pipeline.retrieve.RetrievalResult;
import com.knowledge.ai.pipeline.retrieve.RetrievalService;
import com.knowledge.ai.pipeline.retrieve.RetrievalTraceWriter;
import com.knowledge.ai.pipeline.retrieve.RetrievedChunk;
import com.knowledge.ai.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 重放验证（`T-A01` · 需求 `08` §1.4 闭环第 4 步）。
 *
 * <h3>这是整个闭环的命门</h3>
 * <p>需求原话：「没有它，『已解决』就只能靠人工说一句"我补过了"——而实际上补的文档
 * 未必覆盖那个问法。系统重跑一次能给出**客观判据**。」
 *
 * <h3>判定：两条同时成立才算解决</h3>
 * <ol>
 *   <li><b>分数达标</b> —— {@code after >= 判定线}（不是"涨了就行"，见 §1.4 步 5
 *       「分数**达标**自动关闭」）；</li>
 *   <li><b>命中的是刚补录那篇</b> —— 需求 §1.4 的注：「否则可能只是别的文档碰巧被召回」。
 *       ⚠️ 判据是「**Top1 就是补录那篇**」，**不是**"召回列表里有没有它"。
 *       后者太弱：补录的文档排在第二名时它照样为真，而那个让它达标的分其实是别人给的
 *       —— 那正是这条注要防的形状。两条判据落在**同一条**上才自洽。</li>
 * </ol>
 * <p>两条都为真 ⇒ {@code RESOLVED}（自动关闭）；否则退回 {@code PENDING}。
 * 用户 2026-09-20 裁定按此口径（`AskUserQuestion` 第一选项）。
 *
 * <h3>🔴 重放**不落打点**、不计入统计</h3>
 * <p>它走的直接是 {@link RetrievalService#search}（纯计算），**不经过**
 * {@code ChatStreamService#finish} —— 而那才是写 `sys_retrieval_trace` 的地方。
 * 这是结构上的保证，不是纪律：如果重放也落一行打点，
 * 需求 §1.1 的四个指标（提问总数 / 未命中数 / 平均 Top1）与使用统计的"活跃用户"
 * 都会把**一次重放**算成**一次提问**，而页面上的数只会"略微偏大"，看不出原因。
 * **重放是针对某一条沉淀结论的复核，不是一次新的提问。**
 *
 * <h3>前置自检：判不了的时候**不许判**</h3>
 * <p>三种情形下重放**不改状态**，只如实回一句"为什么还判不了"：
 * <ol>
 *   <li>池子里没有"补录前"的分数（旧行）—— 没有对比就没有结论；</li>
 *   <li>找不到补录文档 —— 关联键是 {@code V34} 的 {@code remark}（问题原文），
 *       普通上传不带它；</li>
 *   <li>补录文档**还没就绪**（还在解析/建索引，或解析失败）。
 *       🔴 这一条最要紧：不等它就判，会得到"未达标"这个**错误的结论**，
 *       而它与"补的文档确实没用"长得一模一样 —— 于是操作者会去补第二篇文档。</li>
 * </ol>
 *
 * <h3>为什么用原提问人的身份重跑</h3>
 * <p>检索的可见范围由提问人的角色 / 部门 / 数据范围决定（{@code RetrievalScope}）。
 * 换成操作人（很可能是超管）会让**可见范围同时变宽** —— 分差里混着"补了文档"与
 * "多看了几个库"两个变量，而结论只有一个数。提问人已注销时退回操作人身份，
 * 并在 `note` 里**写明这次不是同一个视角**（不静默换人）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayService {

    /** 身份没换人时 `note` 追加的那句话 —— 空串（前缀已在拼装处留好）。 */
    private static final String SAME_VIEW = "";

    /** 判定结论（见 `OpsDtos.ReplayResult#verdict` 的「为什么不能与 status 合并」）。 */
    private static final String VERDICT_RESOLVED = "RESOLVED";

    private static final String VERDICT_NOT_RESOLVED = "NOT_RESOLVED";

    private static final String VERDICT_BLOCKED = "BLOCKED";

    private final QaMissRepository qaMissRepository;

    private final SystemSettingService systemSettingService;

    private final RetrievalService retrievalService;

    private final SysUserRepository userRepository;

    private final UserPrincipalAssembler principalAssembler;

    /**
     * 重跑一条未命中问题的原问法，按其结果决定状态。
     *
     * <p>⚠️ **本方法没有 {@code @Transactional}**：中间要跑一次真检索（嵌入 + Milvus + 重排），
     * 那是外网调用。把它圈进一个数据库事务里，等于在整个模型调用期间占着一条连接不放。
     * 写库那一步自己开事务（{@link QaMissRepository#markReplayed}）。
     *
     * @param operator 触发重放的人（只为"提问人已注销"时的兜底与留痕）
     */
    public OpsDtos.ReplayResult replay(long id, LoginUser operator) {
        QaMissRepository.Row row = qaMissRepository.findById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "未命中问题不存在：" + id);
        }
        MissStatus current = MissStatus.parse(row.status());
        double threshold = missThreshold();

        // ---------- 前置自检 ①：有没有可比的"补录前" ----------
        Double before = row.maxTopScore();
        if (before == null) {
            return blocked(row, current, "这条问题没有记录到「补录前」的相似度"
                    + "（它可能是更早版本沉淀的行），没有对比就下不了结论，所以这次不作判定");
        }

        // ---------- 前置自检 ②：找得到补录文档吗 ----------
        List<QaMissRepository.SupplementedDoc> docs =
                qaMissRepository.supplementedDocs(MissKeyNormalizer.keyOf(row.question()));
        if (docs.isEmpty()) {
            return blocked(row, current, "还没有为这条问题补录文档。"
                    + "点「补文档」跳上传页，上传时让「补录备注」里留着这条问题原文，"
                    + "重放才认得出是哪一篇");
        }

        // ---------- 前置自检 ③：补录文档就绪了吗 ----------
        List<QaMissRepository.SupplementedDoc> ready = docs.stream()
                .filter(doc -> DocStatus.READY.name().equals(doc.status()))
                .toList();
        if (ready.isEmpty()) {
            return blocked(row, current, notReadyNote(docs));
        }

        // ---------- 重跑原问题 ----------
        Identity identity = resolveIdentity(row.lastUserId(), operator);
        RetrievalResult result = retrievalService.search(identity.user(), row.question(), null);
        List<RetrievedChunk> chunks = result.chunks();
        // 与打点 `top1_score`、池子 `max_top_score` **同一个选择器**（`RetrievalTraceWriter#top`）。
        // 一次拿到"名次最靠前那一条"，分与"谁给的分"因此**必然指同一条** ——
        // 判"命中补录文档"必须问这一条是谁，而不是问"召回列表里有没有它"：
        // 后者会把「补录那篇挂在第二名、分是别人抬上去的」判成已解决，
        // 而那正是需求 §1.4 的注要防的形状
        RetrievedChunk top = RetrievalTraceWriter.top(chunks);
        Double after = top == null ? null : top.score();

        Set<Long> docIds = ready.stream()
                .map(QaMissRepository.SupplementedDoc::docId)
                .collect(Collectors.toSet());
        boolean hit = top != null && docIds.contains(top.docId());
        boolean improved = after != null && after > before;
        boolean passed = after != null && after >= threshold && hit;

        // ---------- 定状态并落库 ----------
        // ⚠️ IGNORED 不动：需求 §1.3 记忽略人的理由是「避免同一问题被反复忽略又反复冒出」，
        //    让一次重放把它悄悄拉回待处理，正好把那条理由推翻
        MissStatus next = current == MissStatus.IGNORED
                ? current
                : (passed ? MissStatus.RESOLVED : MissStatus.PENDING);
        qaMissRepository.markReplayed(id, next, after, LocalDateTime.now());

        String note = noteOf(passed, improved, hit, after, before, threshold, ready, current)
                + identity.note();
        log.info("[重放验证] missId={} before={} after={} 命中补录文档={} 判定={} 状态={}→{} 操作人={}",
                id, before, after, hit, passed ? "达标" : "未达标", current, next,
                operator == null ? null : operator.getUserId());
        String verdict = passed ? VERDICT_RESOLVED : VERDICT_NOT_RESOLVED;
        return new OpsDtos.ReplayResult(before, after, improved, hit, verdict, next.name(), note);
    }

    // ==================== 内部 ====================

    /** 重放用的身份 + 一句需要说明的话（没换人时为空串）。 */
    private record Identity(LoginUser user, String note) {
    }

    /**
     * 尽量用**原提问人**的身份重跑 —— 理由见类注释。
     *
     * <p>⚠️ 这里复用 {@link UserPrincipalAssembler}（登录时那一套），**不手搓权限**：
     * 角色 / 权限 / 数据范围 / 密级上限四样任何一样漏了，重放的可见范围就与当初那次提问
     * 不一样，而分差里混着什么东西就说不清了。
     *
     * <p>{@code tokenId} 传 {@code null}：它是登出撤销用的（jti），这里没有会话可言。
     * 检索链路不读它。
     */
    private Identity resolveIdentity(Long askerId, LoginUser operator) {
        if (askerId != null) {
            SysUser asker = userRepository.findByIdAndDeletedAtIsNull(askerId).orElse(null);
            if (asker != null) {
                return new Identity(
                        principalAssembler.toLoginUser(principalAssembler.assemble(asker), null), SAME_VIEW);
            }
        }
        return new Identity(operator,
                "（原提问人已不在，本次以操作人视角重跑 —— 可见范围可能与当初那次不同，"
                        + "两次的分不完全可比）");
    }

    /** 当前判定线（与页面卡片显示的**同一个数**）。 */
    private double missThreshold() {
        return systemSettingService.snapshot()
                .decimal(SystemSettings.RETRIEVAL_MISS_THRESHOLD, SystemSettings.DEFAULT_MISS_THRESHOLD);
    }

    /** 补录文档还没就绪 —— 把"还在处理"与"已经失败"分开说（两者的下一步动作不一样）。 */
    private static String notReadyNote(List<QaMissRepository.SupplementedDoc> docs) {
        QaMissRepository.SupplementedDoc failed = docs.stream()
                .filter(doc -> DocStatus.FAILED.name().equals(doc.status()))
                .findFirst()
                .orElse(null);
        if (failed != null) {
            String reason = failed.errorMsg() == null || failed.errorMsg().isBlank()
                    ? "未给出原因" : failed.errorMsg();
            return "补录文档「" + failed.title() + "」解析失败：" + reason
                    + " —— 修好后重新上传（并保留「补录备注」），再重放。这次不作判定";
        }
        String status = docs.get(0).status();
        return "补录文档还在处理中（状态 " + status + "），索引还没建好。"
                + "现在就重放会得出「未达标」这个**错误的结论** —— 等它变为 READY 后再重放";
    }

    /** 一句话说清这次判定。**必须具体**：要能回答"我该去做什么"。 */
    private static String noteOf(boolean passed, boolean improved, boolean hit,
                                Double after, double before, double threshold,
                                List<QaMissRepository.SupplementedDoc> ready,
                                MissStatus current) {
        String titles = ready.stream()
                .map(QaMissRepository.SupplementedDoc::title)
                .distinct()
                .collect(Collectors.joining("、"));

        StringBuilder sb = new StringBuilder();
        if (after == null) {
            sb.append("重放没有召回任何内容（一条都没有）—— 补录文档虽然已就绪，但这次提问没搜到它，")
                    .append("先检查它是否真的进了索引");
        } else {
            sb.append(String.format("召回分 %.6f → %.6f（%s；判定线 %.2f）",
                    before, after, improved ? "提升" : "未提升", threshold));
            if (passed) {
                sb.append("，且召回的正是刚补录的「").append(titles).append("」—— 已自动关闭");
            } else if (!hit) {
                sb.append("，但召回的**不是**刚补录的「").append(titles)
                        .append("」—— 分可能是别的文档碰巧抬上去的，已退回待处理");
            } else {
                sb.append("，未达到判定线，已退回待处理 —— 说明这份补录文档还不足以答上这个问题");
            }
        }
        if (current == MissStatus.IGNORED) {
            sb.append("。（这条问题已被忽略，重放只报告分数，不会把它拉回待处理）");
        }
        return sb.toString();
    }

    /**
     * 判不了时的回话：**原状态原样返回**。
     *
     * <p>刻意不写成"未达标退回待处理" —— 排序失误（先重放再等索引）不该被记成一个
     * 关于内容的结论。与"闸的退出码分档"是同一条纪律：**判不了要和判否分开报**，
     * 否则前者会被当成后者处理，而两者的下一步动作完全不同。
     */
    private static OpsDtos.ReplayResult blocked(QaMissRepository.Row row, MissStatus current, String note) {
        return new OpsDtos.ReplayResult(row.maxTopScore(), null, false, false,
                VERDICT_BLOCKED, current.name(), note);
    }
}
