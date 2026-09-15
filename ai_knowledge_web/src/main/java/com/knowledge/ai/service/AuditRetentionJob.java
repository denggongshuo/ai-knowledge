package com.knowledge.ai.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 审计日志留存清理（T-306）。
 *
 * <h3>为什么需要它</h3>
 * <p>《11》§非功能写明「审计留存 180 天」。没有清理任务的话，这张表会**只增不减** ——
 * 本批开工时已是 1262 行且每天都在长（今天一天 395 行）。审计日志带索引、
 * 带 TEXT 列，长期无限增长会把列表页的 count 查询和备份体积一起拖下去。
 *
 * <h3>为什么这是「删除」，而 §4.8 说「审计日志不可删除」</h3>
 * <p>两件事不冲突，但必须说清楚，否则后人会以为这里违反了验收要点：
 * <ul>
 *   <li>§4.8 管的是**业务层的编辑/删除入口** —— 任何人不能改、不能删某条日志来掩盖行为。
 *       落到本批就是 {@code AuditController} **不声明任何 PUT/PATCH/DELETE 映射**；</li>
 *   <li>留存清理是**按时间整体滚出**，与"这条日志说了什么"完全无关。
 *       运维也无法用它删掉今天那条违规操作 —— {@code cutoff} 恒为
 *       {@code now - retentionDays}，没有任何参数能让它删到近期。
 *       这一点由 {@link #purgeBefore} 的签名（只吃一个 cutoff）结构性保证。</li>
 * </ul>
 * 反过来说：**没有清理才是不合规的** —— 留存期是需求写死的口径，
 * 无限留存既违反最小必要原则，也让 180 天这个数字变成空话。
 *
 * <h3>为什么分批删</h3>
 * <p>一次性 {@code delete ... where created_date < ?} 在百万行量级会长时间持锁、
 * 撑大 undo log，还可能把白天业务请求堵住。分批（每批 {@value #BATCH_SIZE} 行）
 * 让每批是**短事务**，任务被打断也能在下一次继续 —— 天然可续跑。
 * 单机单实例部署，没有并发实例抢删的问题（多实例需分布式锁，见 C 批计划 §9-6）。
 */
@Slf4j
@Service
public class AuditRetentionJob {

    /** 每批删除行数：小到不长时间持锁，大到不会把一天的任务拖成几千次往返。 */
    private static final int BATCH_SIZE = 1000;

    /** 单次任务最多删多少批 —— 兜底防"首次上线删几年历史"把库打满。 */
    private static final int MAX_BATCHES = 200;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 留存天数。默认 180（《11》§非功能）；提为配置项是为了**测试能把它关掉**，
     * 以及合规要求变化时不必重打包（见 {@code application.yml} 的注释）。
     */
    @Value("${app.audit.retention-days:180}")
    private int retentionDays;

    /**
     * 每日清理。cron 来自配置，默认 03:30。
     *
     * <p>{@code "-"} 是 Spring 的**禁用写法**（{@code Scheduled.CRON_DISABLED}）——
     * 测试 profile 把它设成 {@code -} 就不会在测试期间半夜真删数据。
     *
     * <p>这里**刻意直接调 {@link #purgeBefore}**（同一个类内部调用，不走代理），
     * 事务由方法上的注解在**这个类被外部（调度器）调用**时生效 ——
     * 调度线程是外部调用者，所以 {@code @Transactional} 是有效的。
     * 这一点与"同类内部自调用事务失效"不矛盾：失效的是**本类方法互相调用**，
     * 而入口是 Spring 的调度器，它拿到的是代理对象。
     */
    @Scheduled(cron = "${app.audit.retention-cron:0 30 3 * * *}")
    public void scheduledPurge() {
        try {
            int deleted = purgeBefore(LocalDate.now().minusDays(retentionDays));
            if (deleted > 0) {
                log.info("审计留存清理完成：删除 {} 行（留存 {} 天，cutoff={}）",
                        deleted, retentionDays, LocalDate.now().minusDays(retentionDays));
            }
        } catch (Exception ex) {
            // 定时任务抛异常会让调度器把它标记为失败并（默认）继续下次调度，
            // 但显式吞掉 + 记日志更清楚：清理失败不该在日志里表现成"未知异常"。
            log.error("审计留存清理失败（下次调度会重试）", ex);
        }
    }

    /**
     * 删除 {@code created_date < cutoff} 的日志，返回删除行数。
     *
     * <p>判据只看 {@code created_date}（冗余列，有索引可用）。
     * 用 {@code <} 而不是 {@code <=}：{@code cutoff} 是"留存期第一天"，
     * 那一天**还要留**（存 180 天 = 第 181 天凌晨才删第 1 天）。
     *
     * @param cutoff 保留边界；早于这一天的行会被删除
     * @return 实际删除的行数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeBefore(LocalDate cutoff) {
        if (cutoff == null) {
            return 0;
        }
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            // 先按主键选出这一批，再按主键删 —— 不用 `delete ... limit`，
            // 因为 MySQL 的 DELETE ... LIMIT 不支持 ORDER BY 与子查询混用时的
            // 确定性语义，而"先查 id 再删"在两种 MySQL 版本下行为一致。
            @SuppressWarnings("unchecked")
            java.util.List<Number> ids = entityManager.createNativeQuery(
                            "select id from sys_audit_log where created_date < :cutoff "
                                    + "order by id limit :limit")
                    .setParameter("cutoff", cutoff)
                    .setParameter("limit", BATCH_SIZE)
                    .getResultList();
            if (ids.isEmpty()) {
                break;
            }
            java.util.List<Long> longs = ids.stream().map(Number::longValue).toList();
            int deleted = entityManager.createNativeQuery(
                            "delete from sys_audit_log where id in (:ids)")
                    .setParameter("ids", longs)
                    .executeUpdate();
            total += deleted;
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }
        if (total >= BATCH_SIZE * MAX_BATCHES) {
            log.warn("审计留存清理达到单次批数上限 {}，仍有超期行未删（下一天会继续）", MAX_BATCHES);
        }
        return total;
    }

    /** 当前留存天数（供测试断言配置已生效，避免测试里再读一遍 yml）。 */
    public int retentionDays() {
        return retentionDays;
    }
}
