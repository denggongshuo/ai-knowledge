package com.knowledge.ai.repository;

import com.knowledge.ai.entity.SysModelUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 模型用量仓储（`T-905`）。
 *
 * <h3>为什么写侧是"先查再改"而不是 upsert</h3>
 * <p>表上有 {@code (occurred_at, model_type, purpose, model_name)} 这一组事实上的唯一维度，
 * 但**刻意没有建唯一索引** —— 建了它就得处理"并发热路径撞索引"的分支（重试/忽略/报错），
 * 而本表的消费方只需要"合计大致正确"。所以口径是：
 * {@link #findBucket} 查不到就插一行、查到就累加。
 * ⚠️ 代价如实记着：**并发下同一格可能被拆成两行**（合计仍然对，因为读侧是 SUM）。
 * 用一个会丢数据的 upsert 换"每格恰好一行"，不如接受"偶尔拆行但 SUM 正确"。
 *
 * <h3>月统计为什么不在这里写 SQL</h3>
 * <p>{@link #findByOccurredAtBetween} 只做范围取数，聚合交给服务层 ——
 * 因为 {@code byPurpose} 的四桶与 {@code byModel} 的分组**还要与计价列做 join**
 * （单价在 {@code sys_model_config} 上），一条 SQL 做完会把两个表的耦合写进仓储。
 */
public interface SysModelUsageRepository extends JpaRepository<SysModelUsage, Long> {

    /**
     * 取某一格（日期 + 模型类型 + 模型名 + 用途）的现有累计。
     *
     * <p>按 {@code modelName} 而不是 {@code modelId} 定位：id 可空（模型被删），
     * 而名字是 NOT NULL —— 用它做键才不会出现"两行 model_id 都是 NULL 的不同模型被并成一格"。
     */
    @Query("""
            select u from SysModelUsage u
             where u.occurredAt = :day
               and u.modelType = :modelType
               and u.modelName = :modelName
               and u.purpose = :purpose
            """)
    Optional<SysModelUsage> findBucket(@Param("day") LocalDate day,
                                       @Param("modelType") String modelType,
                                       @Param("modelName") String modelName,
                                       @Param("purpose") String purpose);

    /**
     * 取某日期区间内的全部行（含两端）。
     *
     * <p>区间取"本月 1 日 ~ 今天"—— 未来的日期不该出现，所以上界传今天而不是月末。
     */
    List<SysModelUsage> findByOccurredAtBetween(LocalDate from, LocalDate to);

    /**
     * 按月清空（**只给测试与运维用**）。
     *
     * <p>为什么需要它：本表是**跨用例累积**的 —— 跑一遍全量测试会往里写若干行，
     * 而"用量合计 = 某个数"的断言会在下一轮变成另一个数。用例侧靠
     * "自己造的行自己删"（`#108` 的纪律），但要留下一个能一次清干净的入口。
     */
    @Modifying
    @Query("delete from SysModelUsage u where u.occurredAt between :from and :to")
    int deleteBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
