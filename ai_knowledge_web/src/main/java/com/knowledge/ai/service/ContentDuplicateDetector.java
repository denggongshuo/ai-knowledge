package com.knowledge.ai.service;

import com.knowledge.ai.core.text.ContentFingerprint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 内容级近重复识别（`T-O09` 步 1/2）—— **提示型，不阻断**。
 *
 * <h3>它补的是 md5 那一道漏</h3>
 * <p>上传侧的既有去重键是 {@code (kb_id, owner_id, md5)}（`T-501`），回答的是
 * "这两个文件是不是同一串字节"。它拦不住三类真重复（`O9` §2.1）：
 * 同一份合同改了句话的 v2、同一内容的 PDF 与 DOCX、转存过的同一文件。
 * 本类用**规范化文本的指纹**（{@link ContentFingerprint}）来回答
 * "这两个文档是不是同一份内容"。
 *
 * <h3>为什么是"提示"而不是"拒绝"（`O9` 判据 1 的"不阻断"）</h3>
 * <p>"内容相同"**不等于**"用户不该传"。反例是真实存在的：
 * <ul>
 *   <li>法务要归档 PDF 与 DOCX 两个版本，两者本就该都在；</li>
 *   <li>同一份模板的两份合同确实只有小改动，但它们是**两份合同**；</li>
 *   <li>指纹只在同一解析器版本内可比（见 {@link ContentFingerprint} 类注释），
 *       它的证据强度不足以支撑一次拒绝。</li>
 * </ul>
 * <p>所以口径是：**告诉用户 + 给出已存在的那一篇**，由人决定。
 *
 * <h3>为什么必须给出「已有的那一篇是哪一篇」</h3>
 * <p>`O9` §4 步 2 的原话：比 {@code duplicated=true} 有用得多的是
 * 「该文件与《XX 制度 V2》内容高度相同」。一个布尔值用户没法行动 ——
 * 他既不知道跟谁重了，也没法判断"那我还要不要传"。给出 docId 与标题，
 * 用户才有下一步动作（去看那一篇、改名、或者照传）。
 */
@Slf4j
@Service
public class ContentDuplicateDetector {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 在**同库**里找一篇内容指纹相同、且不是自己、也未软删的文档。
     *
     * <p>为什么限定同库：跨库的内容相同**不是重复** —— 两个部门各自维护同一份国标，
     * 那是两件事。把它报成重复只会制造噪音。
     *
     * <p>只查一篇（{@code order by id limit 1}）：提示里给一个具体的对象才有用，
     * 列十个"也重复"的文档等于什么都没说。
     *
     * @param contentSha256 本文件的内容指纹；{@code null}（空文档）→ 直接返回"不重复"
     */
    @Transactional(readOnly = true)
    public Hint detect(long kbId, long docId, String contentSha256) {
        if (contentSha256 == null || contentSha256.isBlank()) {
            return Hint.NONE;
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select d.id, d.title from sys_document d
                         where d.kb_id = :kbId and d.id <> :docId
                           and d.content_sha256 = :csha and d.deleted_at is null
                         order by d.id limit 1
                        """)
                .setParameter("kbId", kbId)
                .setParameter("docId", docId)
                .setParameter("csha", contentSha256)
                .getResultList();

        if (rows.isEmpty()) {
            return Hint.NONE;
        }
        Object[] row = rows.getFirst();
        long existingId = ((Number) row[0]).longValue();
        String title = (String) row[1];
        return new Hint(true, existingId, title,
                "该文件与《" + title + "》内容高度相同（规范化文本指纹一致）——"
                        + "可能是同一份文档的不同版本或另一种格式的同一内容。**不影响本次入库**，"
                        + "如确认是重复，可自行删除其中一份");
    }

    /**
     * 近重复提示。**它是建议，不是判定** —— 调用方无论如何都应当继续入库。
     *
     * @param duplicated 是否发现同库同指纹的另一篇
     * @param docId      已存在的那一篇（{@link #duplicated} 为 {@code false} 时为 {@code null}）
     * @param docTitle   已存在的那一篇的标题
     * @param message    给用户看的话（**不含 docId 也能读**：用户看到的是标题，不是 id）
     */
    public record Hint(boolean duplicated, Long docId, String docTitle, String message) {

        /** 没有发现重复。**不是 null** —— "查过了，没有" 与 "没查" 必须能区分。 */
        public static final Hint NONE = new Hint(false, null, null, "");
    }
}
