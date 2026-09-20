package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.dto.doc.DocDtos;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * `T-601` · 文档属性修改（`PATCH /api/doc/{id}/attributes`，需求 `06` §6.7）。
 *
 * <h3>可改的只有三个：密级 / 所属知识库 / 内容标签</h3>
 * <p>需求 §6.7 把面板分成两栏：**可编辑**（密级、所属知识库、内容标签）与**只读**
 * （文档 ID / 原文件名 / 文件类型 / 文件大小 / 页数·分片 / MD5 / 存储路径 / 上传人 /
 * 上传时间 / 最近索引 / 被引用次数）。请求体就是那三个：`{ secretLevel, kbId, tags[] }`。
 *
 * <h3>🔴 三件事值得单独说</h3>
 * <ol>
 *   <li><b>{@code tags} 明确回"本版本不支持"</b>（不是静默忽略）：文档级内容标签
 *       **没有归属表**（{@code IndexEnhancer} 的类 javadoc 自己写着）。静默忽略会让用户
 *       以为存上了 —— 那是本仓最不能接受的形态（与"假导出"同类）。</li>
 *   <li><b>密级只能提高</b>（{@code G7-3}）：新值必须 ≥ **当前生效密级** `max(文档, 库)`。
 *       比较走 {@link SecretLevel#within}（**唯一**一处比密级高低的落点）——
 *       ⚠️ 不要改成字符串比较：密级名按字母序与密级序**错位**，那样会把判断做反，
 *       而现象是"有人看得见绝密"而不是报错。</li>
 *   <li><b>移动知识库触发重建</b>（{@code G7-4}）：那是**换库 + 搬运向量**，
 *       整块逻辑在 {@link DocReindexService#moveToKb}（含授权、防降级绕过、审计留痕）。</li>
 * </ol>
 *
 * <h3>🔴 执行顺序：先密级、后移动 —— 按"部分失败落在哪一侧"选的</h3>
 * <p>两个字段可以同时出现在一个请求里（面板只有一个保存按钮）。若中途失败，
 * 本类选择让状态停在**更严的那一侧**：
 * <ul>
 *   <li>先改密级 ⇒ 后一步失败时，用户看到的是"密级提高了、库还没搬"——
 *       **不会有人因此多看到东西**，而重试就是再点一次保存；</li>
 *   <li>反过来（先搬库）如果密级校验失败，文档已经进了新库，而那次移动的降级防护
 *       是拿**旧密级**算的 —— 那才是会出事的顺序。</li>
 * </ul>
 * ⚠️ 代价照实说：可能"改了一半"（密级生效、移动没生效）。这是**有意的取舍**，
 * 不是漏了事务 —— 跨 Milvus 的操作本来就放不进一个数据库事务，硬包只会让人以为它是原子的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocAttributeService {

    private final EntityManager entityManager;

    private final DocAccessService docAccessService;

    private final DocReindexService docReindexService;

    /**
     * 保存属性。三个字段都可缺省，语义是"**只改我传了的那些**"。
     *
     * @return {@code { saved, docId, needReindex }} —— {@code needReindex} 表示
     *         本次真的触发了重建索引（即移动了知识库），页面据此提示用户
     */
    public DocDtos.AttributeSaved save(long docId, DocDtos.AttributesRequest request) {
        DocAccessService.DocRow doc = docAccessService.requireVisibleDoc(docId);

        // ① `tags` 没有归属表 ⇒ 明确拒绝，**不假装存了**。放在最前面：它是"这个请求本就不该发"
        if (request.tags() != null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "本版本不支持修改文档的内容标签：标签的归属表还没建（`T-609`）。"
                            + "其余属性（密级 / 所属知识库）可以正常保存。");
        }

        // ② 密级（`G7-3`）。⚠️ 在**移动之前**校验 —— 基准是"当前生效密级"
        if (request.secretLevel() != null && !request.secretLevel().isBlank()) {
            applySecretLevel(doc, request.secretLevel());
        }

        // ③ 移动知识库（`G7-4`）。**跨 Bean 调用** ⇒ `@AuditLog` 的切面才会生效
        boolean moved = false;
        if (request.kbId() != null && request.kbId() != doc.kbId()) {
            moved = docReindexService.moveToKb(docId, request.kbId());
        }

        log.info("[文档属性] docId={} 密级={} 移动={} 触发重建={}",
                docId, request.secretLevel(), request.kbId(), moved);
        return new DocDtos.AttributeSaved(true, docId, moved);
    }

    /**
     * 只放行**提高**（{@code G7-3}：把文档密级降到低于知识库密级被拒；只能提高）。
     *
     * <p>写入的是**文档自己那一列**（{@code sys_document.secret_level}），
     * 生效值仍由 `max(文档, 库)` 决定 —— 用户设成与库相同也照样写它自己的值，
     * 这样「继承（未覆盖）」与「主动设成和库一样」在数据上**始终分得开**（见 `V37`）。
     */
    private void applySecretLevel(DocAccessService.DocRow doc, String raw) {
        SecretLevel wanted;
        try {
            wanted = SecretLevel.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "密级取值只能是 PUBLIC / INTERNAL / CONFIDENTIAL / SECRET，收到的是：" + raw);
        }
        SecretLevel current = doc.effectiveSecretLevel();
        // 只能提高：`wanted` 必须**不低于**当前生效值。
        // ⚠️ 用 `current.within(wanted)`（"当前 ≤ 目标"）而不是 `wanted.rank >= current.rank` ——
        //    比大小的落点只有 SecretLevel 里那一处，散开来就会有人写反。
        if (current != null && !current.within(wanted)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "文档密级只能提高，不能降低（`I-6`）—— 当前生效密级是「" + current.getLabel()
                            + "」，不能改成「" + wanted.getLabel() + "」。");
        }
        entityManager.createNativeQuery(
                        "update sys_document set secret_level = :level, updated_at = :now where id = :id")
                .setParameter("level", wanted.name())
                .setParameter("now", LocalDateTime.now())
                .setParameter("id", doc.id())
                .executeUpdate();
    }
}
