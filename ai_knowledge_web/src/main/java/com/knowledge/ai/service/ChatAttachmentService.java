package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.storage.ChatAttachmentStorage;
import com.knowledge.ai.entity.SysChatAttachment;
import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.repository.SysChatAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

/**
 * 问答图片附件（`T-805`）—— 预上传与回看。
 *
 * <h3>本批的边界：「只落 MinIO + 落库，不调 VL」</h3>
 * <p>这是 `T-805` 任务行括注写死的。所以本类**没有**：描述生成、进度事件、
 * 缓存、会话归属校验 —— 它们分别是 `T-806`（前三个的一部分）与 `T-512`（描述与缓存）。
 *
 * <h3>为什么"回看"的归属只到人，不到会话</h3>
 * <p>预上传时**会话还不存在**（会话是 {@code completions} 里才建的），而
 * {@code completions} 本批不消费 {@code attachmentIds}（`T-512` 才接）。
 * 需求 `09:661` 写的是"需校验**会话**归属"——那条口径要等附件真的绑上消息才谈得上。
 * 本批把它落到**能做到的那一层**：属于当前用户。
 * 这是**弱一点的**保证，不是空保证：它挡住了"拿到别人的附件 id 就能看图"。
 *
 * <h3>顺序：先落对象存储，再落库</h3>
 * <p>反过来的话，库里有行而桶里没有对象 —— 回看时拿到一个必然 404 的预签名 URL，
 * 而"这张图上传失败"与"这张图丢了"在库里长得一模一样。
 * 先落桶的代价是可能留下**孤儿对象**（落库失败时），而对象存储有生命周期规则兜底
 * （`T-O07` 的留存规则表）——**孤儿对象可回收，孤儿行不可分辨**。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAttachmentService {

    private final ChatAttachmentStorage storage;
    private final SysChatAttachmentRepository repository;

    /**
     * 预上传一张图片。
     *
     * @param userId 上传人（会话还不存在，归属只能记在它上面）
     */
    @Transactional
    public ChatDtos.AttachmentUploadResult upload(Long userId, MultipartFile file) {
        ChatAttachmentStorage.Stored stored = storage.store(userId, file);

        SysChatAttachment row = new SysChatAttachment();
        row.setUserId(userId);
        row.setObjectKey(stored.objectKey());
        // 原文件名只用于展示。null 时给一个占位——`name` 列 NOT NULL，
        // 而"浏览器没给文件名"（如某些粘贴场景）是真实存在的
        row.setName(nameOf(file));
        row.setSize(stored.size());
        row.setMime(stored.mime());
        row.setMd5(stored.md5());
        row.setCreatedAt(LocalDateTime.now());

        SysChatAttachment saved;
        try {
            saved = repository.save(row);
        } catch (RuntimeException ex) {
            // 落库失败 → 桶里那个对象成了孤儿。删掉它，别把回收成本留给生命周期规则。
            // 删失败只留日志（storage 内部已吞）：那才是真的要靠规则兜底
            log.error("附件落库失败，回滚对象：userId={} object={} err={}",
                    userId, stored.objectKey(), ex.getMessage(), ex);
            storage.remove(stored.objectKey());
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "图片保存失败，请稍后重试");
        }

        log.info("[问答] 附件预上传完成：userId={} attachmentId={} size={}",
                userId, saved.getId(), stored.size());
        return new ChatDtos.AttachmentUploadResult(
                String.valueOf(saved.getId()),
                storage.presignedUrl(stored.objectKey()),
                stored.md5(),
                stored.size(),
                stored.mime());
    }

    /**
     * 回看附件。
     *
     * <p>⚠️ 「不存在」与「不是你的」返回**同一个** {@code NOT_FOUND}，这是有意的 ——
     * 区分开就等于告诉调用方"这个 id 确实存在，只是不归你"，那是一个可枚举的信息泄露面。
     * 与 {@code ChatSessionService#requireOwned} 同一条口径。
     *
     * <p>查询直接走 {@code findByIdAndUserId}：归属校验与取数是**同一条 SQL**，
     * 不留"先按 id 查出来再比"的中间态（那种写法只要有一处分支写错就泄露存在性）。
     */
    @Transactional(readOnly = true)
    public ChatDtos.AttachmentView get(Long userId, Long attachmentId) {
        SysChatAttachment row = repository.findByIdAndUserId(attachmentId, userId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));

        return new ChatDtos.AttachmentView(
                String.valueOf(row.getId()),
                // 本批只有 IMAGE 一种（`09` §4.6 的 attachments[] 形状里它就是常量）。
                // 写死而不是存列：没有第二种取值时，那个列只会是一个恒为同值的噪声列
                "IMAGE",
                row.getName(),
                row.getSize(),
                row.getMd5(),
                row.getMime(),
                // 每次读取现签 —— 库里存的是对象名，URL 有 TTL（PresignTtl#CHAT_ATTACHMENT_SECONDS）
                storage.presignedUrl(row.getObjectKey()),
                // 以下五个是 VL 描述（`09` §4.6），属 `T-512` —— **本批如实给 null，不填假值**。
                // 填 "" 会被前端当成"模型确实没看出什么"，与"还没理解过"是两回事
                null, null, null, null, null);
    }

    /** 浏览器没给文件名时给一个能显示的占位（`name` 列 NOT NULL）。 */
    private static String nameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "图片" : name;
    }
}
