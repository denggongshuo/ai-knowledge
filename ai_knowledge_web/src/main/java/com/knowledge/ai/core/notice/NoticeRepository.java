package com.knowledge.ai.core.notice;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知三张表的读写（`T-B00`/`T-B01`/`T-B02`）。
 *
 * <h3>为什么是 native query 而不是 JPA 实体</h3>
 * <p>与 {@code SysAsyncTaskRepository} / {@code SysRetrievalTraceRepository} 同一条口径：
 * 本表没有实体、没有关联，全部是"按条件查一行/写一行"，映射成实体只会多一层
 * 与库结构同步的负担（`ddl-auto: validate` 会把不一致变成启动失败，而这里没有收益）。
 *
 * <h3>🔴 可见性只有一处定义：{@link #VISIBLE}</h3>
 * <p>「一条通知，谁能看见」的规则是 —— **定向的给本人，广播的给所有人**。
 * 它出现在**六个**查询里（列表 / 三个计数 / 已读的写 / 未读的写），
 * 任何一处写漏都会造成两种事故之一：**把别人的通知露出去**，或**自己的通知数不出来**。
 * 所以把它抽成一个常量，**所有方法都从它拼**。
 *
 * <h3>⚠️ 已读的写操作必须同时校验可见性</h3>
 * <p>标已读/标未读的 `where` 里**都带上了 {@link #VISIBLE}**。
 * 只按 `notice_id` 写的话，用户可以拿别人的通知 id 试出"这条存在"（探测），
 * 并且能把别人的通知标成已读 —— 那是越权写，不是小问题。
 */
@Repository
@RequiredArgsConstructor
public class NoticeRepository {

    /**
     * 可见性条件（别名固定为 `n`）。
     *
     * <p>`user_id is null` = 广播（需求 §2.2 的「系统消息」类天然是广播）；
     * 非 null = 定向给那个人。⚠️ 本批的唯一产出方是异步任务终态、**总带 owner**，
     * 所以落地的每一行都是定向的 —— 广播只是表结构支持的形态（见 `V31` 头注释）。
     */
    private static final String VISIBLE = "(n.user_id is null or n.user_id = :uid)";

    private final EntityManager entityManager;

    // ==================== 列表与计数 · `T-B00` ====================

    /** 列表一行（含按用户算出来的已读状态）。 */
    public record Row(long id, String type, String title, String content, String actionsJson,
                      boolean needAction, boolean muteable, LocalDateTime createdAt, boolean read) {
    }

    /** 列表的六个计数（需求 §2.3 的左侧分类导航）。 */
    public record Counts(int all, int unread, Map<NoticeType, Integer> byType) {

        public int of(NoticeType type) {
            return byType.getOrDefault(type, 0);
        }
    }

    /**
     * 列表（需求 §2.4：「加载更多」而不是分页 ⇒ 调用方按 `offset` 递增地要）。
     *
     * @param type   null = 全部
     * @param read   null = 不筛；{@code true} 只看已读；{@code false} 只看未读
     */
    public List<Row> findPage(long userId, NoticeType type, Boolean read, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "select n.id, n.type, n.title, n.content, n.actions_json, n.need_action, n.muteable, "
                        + "n.created_at, "
                        + "exists(select 1 from sys_notice_read r "
                        + "       where r.notice_id = n.id and r.user_id = :uid) as is_read "
                        + "from sys_notice n where " + VISIBLE);
        if (type != null) {
            sql.append(" and n.type = :type");
        }
        if (read != null) {
            // 已读 = 存在已读行；未读 = 不存在。⚠️ 两个方向都要写全，别只写一个然后取反 ——
            // 取反会把"广播通知里我没标过已读的"和"根本没有这条"混在一起（这里虽然后者不存在，但语义要清楚）。
            sql.append(read
                    ? " and exists(select 1 from sys_notice_read r2 where r2.notice_id = n.id and r2.user_id = :uid)"
                    : " and not exists(select 1 from sys_notice_read r3 where r3.notice_id = n.id and r3.user_id = :uid)");
        }
        sql.append(" order by n.created_at desc, n.id desc limit :limit offset :offset");

        var query = entityManager.createNativeQuery(sql.toString())
                .setParameter("uid", userId)
                .setParameter("limit", limit)
                .setParameter("offset", offset);
        if (type != null) {
            query.setParameter("type", type.name());
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Row> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(new Row(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    (String) r[2],
                    (String) r[3],
                    (String) r[4],
                    toBool(r[5]),
                    toBool(r[6]),
                    toLdt(r[7]),
                    toBool(r[8])));
        }
        return result;
    }

    /** 计数：全部 + 未读 + 四类各自（需求 §2.10 验收要点第一条：合计 = 全部）。 */
    public Counts counts(long userId) {
        int all = scalarInt("select count(*) from sys_notice n where " + VISIBLE, userId);
        int unread = unreadCount(userId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select n.type, count(*) from sys_notice n where " + VISIBLE + " group by n.type")
                .setParameter("uid", userId)
                .getResultList();
        Map<NoticeType, Integer> byType = new LinkedHashMap<>();
        for (NoticeType t : NoticeType.values()) {
            byType.put(t, 0);
        }
        for (Object[] r : rows) {
            NoticeType t = NoticeType.parseOrNull((String) r[0]);
            if (t != null) {
                byType.put(t, ((Number) r[1]).intValue());
            }
        }
        return new Counts(all, unread, byType);
    }

    /** 未读数（顶栏角标，需求 §2.6）。 */
    public int unreadCount(long userId) {
        return scalarInt("select count(*) from sys_notice n where " + VISIBLE
                + " and not exists(select 1 from sys_notice_read r where r.notice_id = n.id and r.user_id = :uid)",
                userId);
    }

    // ==================== 已读 · `T-B02` ====================

    /**
     * 标已读 / 标未读（需求 §2.7：**按用户维度**，且「已读可逆」）。
     *
     * <p>「行在 = 已读」—— 标已读是插行、标回未读是删行，**没有第三种状态**。
     *
     * @return 是否真的作用到了一行（{@code false} = 通知不存在，或它不归这个人看 ⇒ 调用方回 404）
     */
    @Transactional
    public boolean setRead(long noticeId, long userId, boolean read) {
        // 先确认可见性：既挡住越权写，也给调用方一个"该不该回 404"的答案。
        int visible = scalarInt("select count(*) from sys_notice n where n.id = :id and " + VISIBLE,
                userId, noticeId);
        if (visible == 0) {
            return false;
        }
        if (read) {
            // 幂等：重复标已读不刷新 read_at（"什么时候读的"应忠实于第一次）
            entityManager.createNativeQuery(
                            "insert into sys_notice_read (notice_id, user_id, read_at) "
                                    + "values (:id, :uid, :now) "
                                    + "on duplicate key update notice_id = notice_id")
                    .setParameter("id", noticeId)
                    .setParameter("uid", userId)
                    .setParameter("now", LocalDateTime.now())
                    .executeUpdate();
        } else {
            entityManager.createNativeQuery(
                            "delete from sys_notice_read where notice_id = :id and user_id = :uid")
                    .setParameter("id", noticeId)
                    .setParameter("uid", userId)
                    .executeUpdate();
        }
        return true;
    }

    /**
     * 全部标为已读（需求 §2.7：**不需要二次确认**，因为已读可逆）。
     *
     * <p>用 `insert ... select` 一条语句搞定，而不是"先查再逐条插" —— 后者在通知很多时
     * 会退化成 N 次往返。`not exists` 保证幂等（已经有已读行的不再插）。
     *
     * @return 真正新增的已读行数（= 前端 `{updated}`）
     */
    @Transactional
    public int markAllRead(long userId) {
        return entityManager.createNativeQuery(
                        "insert into sys_notice_read (notice_id, user_id, read_at) "
                                + "select n.id, :uid, :now from sys_notice n where " + VISIBLE
                                + " and not exists(select 1 from sys_notice_read r "
                                + "                 where r.notice_id = n.id and r.user_id = :uid)")
                .setParameter("uid", userId)
                .setParameter("now", LocalDateTime.now())
                .executeUpdate();
    }

    // ==================== 设置 · `T-B02` ====================

    /**
     * 该用户的设置。
     *
     * <p>⚠️ **没有行 = 默认开启**：四类默认都开，只有被显式关掉的才落行。
     * 于是返回的是"库里的差异"，补齐四类是**调用方**的事（见 `NoticeService#settings`）——
     * 那样新增第五类时不必回填数据。
     */
    public Map<NoticeType, Boolean> settings(long userId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select type, enabled from sys_notice_setting where user_id = :uid")
                .setParameter("uid", userId)
                .getResultList();
        Map<NoticeType, Boolean> map = new LinkedHashMap<>();
        for (Object[] r : rows) {
            NoticeType t = NoticeType.parseOrNull((String) r[0]);
            if (t != null) {
                map.put(t, toBool(r[1]));
            }
        }
        return map;
    }

    /** 落一条设置差异。 */
    @Transactional
    public void saveSetting(long userId, NoticeType type, boolean enabled) {
        entityManager.createNativeQuery(
                        "insert into sys_notice_setting (user_id, type, enabled) values (:uid, :type, :enabled) "
                                + "on duplicate key update enabled = :enabled")
                .setParameter("uid", userId)
                .setParameter("type", type.name())
                .setParameter("enabled", enabled)
                .executeUpdate();
    }

    /**
     * 该用户有没有关掉这一类 —— **发布前的闸**（`T-B03`）。
     *
     * <p>🔴 问的是「设置开关到底管不管事」。需求 §2.5 的表格里「系统告警」写着
     * 「❌ **强制开启**」—— 如果关掉一个开关并不影响通知的产出，那句"强制开启"就是
     * **纯装饰**（关不关都一样，也就无所谓强制）。所以开关必须真的**拦住发布**。
     *
     * <p>默认开启：**没有行 = 开着**（见 {@link #settings}）。
     * ⚠️ {@link NoticeType#canDisable()} 为 {@code false} 的类型永远返回 {@code true} ——
     * 这一句和 `NoticeService#saveSettings` 里剔除 ALERT 的那一句是**两道锁**：
     * 一道挡住"设不上"，一道挡住"就算设上了也不生效"（防的是有人直接改库/绕过接口写行）。
     */
    public boolean isEnabled(long userId, NoticeType type) {
        if (!type.canDisable()) {
            return true;
        }
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(
                        "select enabled from sys_notice_setting where user_id = :uid and type = :type")
                .setParameter("uid", userId)
                .setParameter("type", type.name())
                .getResultList();
        return rows.isEmpty() || toBool(rows.getFirst());
    }

    // ==================== 发布 · `T-B03` ====================

    /** 发一条通知（唯一写入口是 {@link NoticePublisher}）。 */
    @Transactional
    public void insert(String type, String title, String content, String actionsJson,
                       boolean needAction, boolean muteable, Long userId,
                       String sourceType, String sourceTaskId) {
        entityManager.createNativeQuery(
                        "insert into sys_notice (type, title, content, actions_json, need_action, "
                                + "muteable, user_id, source_type, source_task_id, created_at) "
                                + "values (:type, :title, :content, :actions, :need, :mute, :uid, "
                                + ":stype, :stask, :now)")
                .setParameter("type", type)
                .setParameter("title", title)
                .setParameter("content", content)
                .setParameter("actions", actionsJson)
                .setParameter("need", needAction)
                .setParameter("mute", muteable)
                .setParameter("uid", userId)
                .setParameter("stype", sourceType)
                .setParameter("stask", sourceTaskId)
                .setParameter("now", LocalDateTime.now())
                .executeUpdate();
    }

    /**
     * 任务的坐标（发通知要用它决定"发给谁、文案里提哪个库/文档"）。
     *
     * <p>⚠️ 这些字段**不在** {@code AsyncTaskInfo} 里（那是个对外快照），只能回表查。
     * 这也是「通知生成点必须挂在手里有 `result` 的那一层」的原因 ——
     * 回表查能拿到坐标，**拿不到结果**（`result` 不持久化）。
     */
    public record TaskCoord(String type, Long kbId, Long docId, Long ownerId) {
    }

    public TaskCoord findTask(String taskId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select type, kb_id, doc_id, owner_id from sys_async_task where task_id = :id")
                .setParameter("id", taskId)
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] r = rows.getFirst();
        return new TaskCoord((String) r[0], toLong(r[1]), toLong(r[2]), toLong(r[3]));
    }

    // ==================== 「待我处理」· `T-B01` ====================

    /**
     * 存在解析问题的**文档数**（不是问题行数）。
     *
     * <p>与 11 页的口径一致（`mockPendingItems` 的文案是"**3 篇**文档存在未识别表格或解析失败"）。
     * ⚠️ `sys_parse_issue` **没有状态列** —— 「存在即待处理」就是它的语义，
     * 所以这里没有 `where status = 'PENDING'` 可写（不是漏了）。
     *
     * <p>🔴 **这是 `pending` 里唯一「有源」的那一项**。另一半（未命中问题）的源
     * 是未命中清单，属 `T-A00`（S8）—— 那个数**不发**而不是发 0，见 `NoticeService#pending`。
     */
    public int pendingParseIssueCount() {
        return scalarInt("select count(distinct doc_id) from sys_parse_issue");
    }

    // ==================== 小工具 ====================

    private int scalarInt(String sql) {
        return scalarInt(sql, null, null);
    }

    private int scalarInt(String sql, Long uid) {
        return scalarInt(sql, uid, null);
    }

    private int scalarInt(String sql, Long uid, Long id) {
        var query = entityManager.createNativeQuery(sql);
        if (uid != null) {
            query.setParameter("uid", uid);
        }
        if (id != null) {
            query.setParameter("id", id);
        }
        Object v = query.getSingleResult();
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static boolean toBool(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return ((Number) v).intValue() != 0;
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    /** Hibernate 7 的 native query 可能回 {@code java.sql.Timestamp} 或 {@code LocalDateTime}。 */
    private static LocalDateTime toLdt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }
}
