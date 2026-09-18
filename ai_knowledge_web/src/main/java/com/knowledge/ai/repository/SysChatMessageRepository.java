package com.knowledge.ai.repository;

import com.knowledge.ai.entity.SysChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 问答消息仓储。
 *
 * <p>写入侧是"拿自增 id 去填 `done.messageId`"（`T-800`）；
 * 读侧三个查询是 `T-802` 的余下半边（`S5-M08-B`）。
 *
 * <h3>为什么列表要"批量补 count 与模型"而不是逐条查</h3>
 * <p>会话列表一页 20 条，逐条去查"这个会话有几条消息"就是 20 次往返（N+1）。
 * 两个批量方法都收 {@code sessionIds} 集合、一次查完，再在服务层按 sessionId 装回去。
 * 消息表将来会长得很快（每次提问两行），N+1 在这里不是洁癖问题。
 */
public interface SysChatMessageRepository extends JpaRepository<SysChatMessage, Long> {

    /**
     * 某会话的消息，按时间正序。
     *
     * <p>排序用 {@code id} 而不是 {@code created_at}：同一秒内落库的 user/assistant
     * 两行 {@code created_at} 会完全相同，按时间排会让它们**偶发乱序**
     * （"回答排在提问前面"）。自增 id 是严格单调的，与插入顺序同序。
     */
    List<SysChatMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    /**
     * 按消息 id + 归属人取一行（`T-807` 反馈的归属校验）。
     *
     * <p>归属校验与取数是**同一条 SQL**，与
     * {@code SysChatAttachmentRepository#findByIdAndUserId} 同一条纪律 ——
     * 不留"先按 id 查出来再比 userId"的中间态，那种写法只要有一处分支写错
     * （早返回、日志带上 id、异常带上实体）就泄露了"这个 id 存在"。
     *
     * <p>⚠️ 带上 {@code deletedAt is null}：已软删的会话里的消息**不该还能被反馈**——
     * 用户已经删掉那段对话了，再往里写反馈就是把数据写进一个他自己看不到的地方。
     *
     * @return 查不到即"不存在 **或** 不是你的 **或** 所在会话已删"，调用方**不得**区分
     */
    @Query("""
            select m from SysChatMessage m
            where m.id = :messageId
              and m.sessionId in (select s.id from SysChatSession s
                                  where s.userId = :userId and s.deletedAt is null)
            """)
    Optional<SysChatMessage> findOwned(@Param("messageId") Long messageId, @Param("userId") Long userId);

    /**
     * 批量取各会话的消息条数。
     *
     * @return 每项 {@code [sessionId(Long), count(Long)]}；**没有消息的会话不会出现在结果里**
     *         （调用方按 0 处理，不要指望它返回一行 0）
     */
    @Query("""
            select m.sessionId, count(m) from SysChatMessage m
            where m.sessionId in :sessionIds
            group by m.sessionId
            """)
    List<Object[]> countBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /**
     * 批量取各会话**最后一条 assistant 消息**的模型归因。
     *
     * <p>为什么模型名要从消息行取，而不是从 {@code sys_model_config} 按会话的
     * {@code model_id} 反查：后者**当前是 0 行**（`汇总.md` §3 的 `#15`，模型配置尚未接进 DB），
     * 反查恒为空 —— 会话列表上「所用模型」那一行会永远不显示。
     * 而消息行的 {@code model_name} 是 `done` 事件**如实回带**的真实模型名（`T-808` 的落点），
     * 它既有值、又恰好是需求要的「**所用**模型，便于回看与归因」（`09:124`）。
     *
     * <p>取"最后一条"用 {@code max(id)} 自连接：{@code session_id} 上有索引
     * （{@code idx_cm_session}），子查询走覆盖索引；换成 {@code order by ... limit 1}
     * 逐会话跑就是 N+1，而这里一页最多 20 个会话。
     *
     * @return 每项 {@code [sessionId(Long), modelId(Long|null), modelName(String|null)]}
     */
    @Query(value = """
            select m.session_id, m.model_id, m.model_name
            from sys_chat_message m
            join (select session_id, max(id) as mid
                  from sys_chat_message
                  where session_id in (:sessionIds) and role = 'assistant'
                  group by session_id) t on t.mid = m.id
            """, nativeQuery = true)
    List<Object[]> lastAssistantModelBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);
}