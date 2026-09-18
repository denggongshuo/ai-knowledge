package com.knowledge.ai.repository;

import com.knowledge.ai.entity.SysChatAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 问答图片附件仓储（`T-805`）。
 *
 * <p>只有一个查询，而且是**归属查询**而不是按主键查 —— 这不是巧合：
 * 回看接口要求"不是本人的一律 {@code NOT_FOUND}"，所以"查得到"与"是你的"必须是
 * **同一条 SQL**。分两步写（先按 id 查、再比 userId）会出现一个中间态：
 * 存在但非本人的那条记录已经被读进内存了，后面的分支只要有一处写错（早返回、
 * 日志里带上 id、异常里带上实体），就把"这个 id 存在"泄露出去了。
 */
public interface SysChatAttachmentRepository extends JpaRepository<SysChatAttachment, Long> {

    /**
     * 按 id + 归属人查。查不到即"不存在 **或** 不是你的"——调用方**不得**区分这两者。
     */
    Optional<SysChatAttachment> findByIdAndUserId(Long id, Long userId);
}
