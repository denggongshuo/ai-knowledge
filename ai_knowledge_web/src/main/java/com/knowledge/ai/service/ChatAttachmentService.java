package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.storage.ChatAttachmentStorage;
import com.knowledge.ai.entity.SysChatAttachment;
import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.pipeline.vision.VisionDescriber;
import com.knowledge.ai.pipeline.vision.VisionResult;
import com.knowledge.ai.repository.SysChatAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 问答图片附件（`T-805` 预上传 + `T-907` 图片理解 + 回看）。
 *
 * <h3>`T-805` 与 `T-907` 的分界</h3>
 * <p>`T-805`（`S5-M08-C`）的边界是「**只落 MinIO + 落库，不调 VL**」——
 * 所以那一批本类**没有**描述生成、进度事件、缓存。`T-907` 把这三件接上：
 * 新增 {@link #describe}（编排入口）与描述字段的落库/回读。
 * 二者的共同前提是同一条：**图片在用户点发送之前就传完了，而描述是发送时才生成的**
 * （需求 `09` FR-SYS-04 的时序）。
 *
 * <h3>为什么"回看"的归属只到人，不到会话</h3>
 * <p>预上传时**会话还不存在**（会话是 {@code completions} 里才建的）。
 * 需求 `09:661` 写的是"需校验**会话**归属"——那条口径要等附件真的**绑上消息**
 * 才谈得上（`T-806`/`T-512`：把 {@code attachmentId} 写进消息行）。
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
    private final VisionDescriber visionDescriber;

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
                // 只有 IMAGE 一种（`09` §4.6 的 attachments[] 形状里它就是常量）。
                // 写死而不是存列：没有第二种取值时，那个列只会是一个恒为同值的噪声列
                "IMAGE",
                row.getName(),
                row.getSize(),
                row.getMd5(),
                row.getMime(),
                // 每次读取现签 —— 库里存的是对象名，URL 有 TTL（PresignTtl#CHAT_ATTACHMENT_SECONDS）
                storage.presignedUrl(row.getObjectKey()),
                // 以下五个自 `T-907` 起是**真值**（此前恒 null）。仍然可能为 null ——
                // 那表示「这张图还没有被理解过」，与"理解过但没看出东西"（degraded=true
                // + 空描述）是两件事。`degraded` 用 Boolean 承载这个三态
                row.getDescription(),
                row.getSearchSummary(),
                row.getVlModelName(),
                row.getDegraded(),
                row.getPromptVersion());
    }

    /**
     * 按 id 取原图**字节**（`T-806` · 需求 `09` §4.7 的「直接读原图」）。
     *
     * <h3>为什么它与 {@link #describe} 分成两个方法，而不是加一个布尔参数</h3>
     * <p>两条路要做的事**没有交集**：{@code describe} 读字节是**为了喂 VL**（还会查缓存、
     * 写回描述五列、发进度事件）；本方法读字节是**为了喂对话模型**（不调 VL、不写库、
     * 不发事件）。把它们合成一个方法，调用方就得在方法里判"这次到底要不要调 VL"——
     * 而那个判断一旦写错，表现是「点了直接读原图，却仍然走了一遍 40~60 秒的 VL 描述」
     * （真 VL 实测耗时，见 `#179`），页面看起来完全正常。
     *
     * <h3>归属校验与 {@code describe} 逐字同源</h3>
     * <p>同样走 {@code findByIdAndUserId} —— 校验与取数是**同一条 SQL**。
     * 「直接读原图」不因为"只读字节"就放松：它比描述更敏感（描述是中间产物，
     * 原图是用户上传的原始内容）。
     *
     * @param userId        提问人（归属校验到人 —— 会话在此刻还没建，见类注释）
     * @param attachmentIds 前端传来的附件 id（**字符串形式**）
     * @return 与 {@code attachmentIds} **等长同序**的原图
     * @throws BizException 任一 id 不存在 / 不属于当前用户（**同码 NOT_FOUND**，不区分）
     */
    @Transactional(readOnly = true)
    public List<Original> originals(Long userId, List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        List<Original> out = new ArrayList<>(attachmentIds.size());
        for (String rawId : attachmentIds) {
            Long id = parseId(rawId);
            if (id == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            SysChatAttachment row = repository.findByIdAndUserId(id, userId)
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
            out.add(new Original(String.valueOf(row.getId()),
                    storage.readBytes(row.getObjectKey()), row.getMime()));
        }
        return out;
    }

    /** 一张原图（`T-806`）。{@code mime} 一路上都有真值，见 {@code StreamingChatCaller.ChatImage}。 */
    public record Original(String attachmentId, byte[] bytes, String mime) {
    }

    /**
     * 理解一组图片（`T-907` · 需求 `09` FR-SYS-04）—— 问答链路的入口。
     *
     * <h3>为什么"附件不存在"直接报错，而不是跳过它</h3>
     * <p>§4.10 的兜底表最后一行写着：「**最糟的处理是静默忽略图片** —— 用户以为模型看到了图，
     * 实际没有，这类问题极难排查。」所以一个解析不出来的 {@code attachmentId}
     * **不进链路**（与 §4.10 第 1 行「格式/大小不合规 → 直接返回业务错误码，不进链路」同形）。
     * 它的表现是 {@code event: error} + 一句能照着做的提示 —— 而不是"答案看起来正常、
     * 只是没看图"。
     *
     * <h3>为什么这里就把字节读出来</h3>
     * <p>VL 的两条传输通路里有一条（base64 回退）必须要有字节，而**压缩也只作用于它**。
     * 与其在调用器里再回一次对象存储（多一次往返、还要把 storage 依赖渗进 pipeline 层），
     * 不如在这里一次读齐。上限 10 MB / 张、单次 ≤3 张（§4.5），
     * 最坏 30 MB 瞬时内存，可接受。
     *
     * @param userId        提问人（归属校验到人 —— 会话在此刻还没建）
     * @param attachmentIds 前端传来的附件 id（**字符串形式**）
     * @param question      用户问题。问答期**带上**（§4.3：VL 会聚焦到相关区域）
     * @return 与 {@code attachmentIds} **等长同序**的结果
     * @throws BizException 任一 id 不存在 / 不属于当前用户（**同码 NOT_FOUND**，不区分）
     */
    @Transactional
    public List<Described> describe(Long userId, List<String> attachmentIds, String question) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        List<SysChatAttachment> rows = new ArrayList<>(attachmentIds.size());
        for (String rawId : attachmentIds) {
            Long id = parseId(rawId);
            if (id == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            rows.add(repository.findByIdAndUserId(id, userId)
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND)));
        }

        List<VisionDescriber.VisionInput> inputs = new ArrayList<>(rows.size());
        for (SysChatAttachment row : rows) {
            inputs.add(new VisionDescriber.VisionInput(row.getMd5(),
                    storage.readBytes(row.getObjectKey()),
                    storage.presignedUrl(row.getObjectKey()),
                    row.getMime(), question));
        }

        List<VisionResult> results = visionDescriber.describeAll(inputs);

        List<Described> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            SysChatAttachment row = rows.get(i);
            VisionResult result = results.get(i);
            persistDescription(row, result);
            out.add(new Described(String.valueOf(row.getId()), result));
        }
        return out;
    }

    /**
     * 把描述写回附件行。
     *
     * <p>⚠️ **降级产出也写**（{@code degraded = 1} + 空描述）：库里那一列
     * {@code NULL} 的语义是「**还没有被理解过**」，而降级是「**试过了、没成**」。
     * 两者都要能读出来 —— 后者正是前端「重试理解图片」那个入口存在的理由（§4.10）。
     * 把它们压成一个 {@code NULL}，用户就永远不知道自己那张图试过没有。
     */
    private void persistDescription(SysChatAttachment row, VisionResult result) {
        if (result == null) {
            return;
        }
        row.setDescription(result.desc() == null || result.desc().isBlank() ? null : result.desc());
        row.setSearchSummary(result.searchSummary() == null || result.searchSummary().isBlank()
                ? null : truncate(result.searchSummary(), 1000));
        row.setVlModelName(blankToNull(result.vlModelName()));
        row.setDegraded(result.degraded());
        row.setPromptVersion(result.promptVersion());
        repository.save(row);
    }

    /** {@code search_summary} 列是 VARCHAR(1000) —— 超长截断而不是让整次问答失败。 */
    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 字符串 id → 数字；解析不出返回 {@code null}（调用方按 {@code NOT_FOUND} 处理）。 */
    private static Long parseId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(rawId.strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 一件附件的理解结果（回给编排用）。
     *
     * @param attachmentId 附件 id 的字符串形式（与请求里那个**同一个形状**）
     */
    public record Described(String attachmentId, VisionResult result) {
    }

    /** 浏览器没给文件名时给一个能显示的占位（`name` 列 NOT NULL）。 */
    private static String nameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "图片" : name;
    }
}
