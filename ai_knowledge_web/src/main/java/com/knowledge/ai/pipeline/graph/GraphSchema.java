package com.knowledge.ai.pipeline.graph;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 图谱 schema 的幂等建约束（`T-C00` 步 4）。
 *
 * <h3>为什么要建约束，而不是"靠 MERGE 就够了"</h3>
 * <p>{@code MERGE} 保证的是"**这条语句**不会造出重复"，但**并发**下不保证 ——
 * 两个解析 worker 同时抽到同一个实体名时，没有唯一约束就会真的落成两个
 * `:Entity {name: "一致性哈希"}` 节点（Neo4j 的 `MERGE` 在无约束时是"先查后写"，
 * 中间有窗口）。而实体分裂的后果是**静默的**：关系挂在两个同名节点上，
 * 一跳邻居只查得到一半，看上去"图谱就是没召回到"。
 * <p>⇒ 唯一约束既是正确性要求，也是让 `MERGE` 真正原子的**前提**。
 *
 * <h3>为什么用 `ApplicationRunner` 而不是 `@PostConstruct`</h3>
 * <p>本仓已有先例（{@code StorageStartupReporter}）走 `ApplicationRunner`：
 * 那时容器已就绪、配置已解析。`@PostConstruct` 会在 Bean 初始化阶段就去连 Neo4j，
 * 而 Neo4j 不可用时那个异常会**把应用启动整个拖挂** —— 与 `07:525`
 * 「系统主链路永远不依赖 Neo4j 的可用性」直接冲突。
 *
 * <h3>🔴 永不抛</h3>
 * <p>建约束失败（Neo4j 没起来 / 权限不足 / 版本不支持 `IF NOT EXISTS`）
 * **只记日志**。图谱是增强不是依赖 —— 起不来时正确行为是"图写不进去、文档照常入库"，
 * 而不是"应用起不来"。
 *
 * <h3>为什么 `IF NOT EXISTS` 就够（多实例并发启动）</h3>
 * <p>两个实例同时建同一条约束：Neo4j 的 DDL 是串行的，后到的那个会看到已存在
 * ⇒ `IF NOT EXISTS` 让它变成 no-op。⚠️ 这条**实测过**（判据 8：连跑两次不抛、约束数不翻倍）。
 */
@Slf4j
@Component
@Order(20)
public class GraphSchema implements ApplicationRunner {

    /**
     * 三条唯一约束。
     *
     * <ul>
     *   <li>{@code Entity.name} —— 需求 `07:462`「实体名是联合主键（同一全库实体合一，跨库共享）」；</li>
     *   <li>{@code Document.docId} —— 回源与"按文档删旧建新"的锚；</li>
     *   <li>{@code Chunk.chunkId} —— 回指 MySQL 分片主键（`07:464`「仅作锚点」）。</li>
     * </ul>
     *
     * <p>⚠️ **不建向量索引** —— `07:464` 明写 `(:Chunk)` 节点"不存全文、不建向量"。
     */
    private static final List<String> CONSTRAINTS = List.of(
            "CREATE CONSTRAINT entity_name_unique IF NOT EXISTS FOR (e:Entity) REQUIRE e.name IS UNIQUE",
            "CREATE CONSTRAINT document_doc_id_unique IF NOT EXISTS FOR (d:Document) REQUIRE d.docId IS UNIQUE",
            "CREATE CONSTRAINT chunk_chunk_id_unique IF NOT EXISTS FOR (c:Chunk) REQUIRE c.chunkId IS UNIQUE");

    private final ObjectProvider<Driver> driverProvider;

    private final boolean enabled;

    public GraphSchema(ObjectProvider<Driver> driverProvider,
                       @Value("${m05.graph.enabled:true}") boolean enabled) {
        this.driverProvider = driverProvider;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureSchema();
    }

    /**
     * 建约束。**幂等且永不抛** —— 可被用例直接调（判据 8 连调两次）。
     *
     * @return 真正执行了建约束的条数（{@code 0} 也可能是"开关关闭"）
     */
    public int ensureSchema() {
        if (!enabled) {
            log.info("[图谱] 开关未开启（m05.graph.enabled=false），跳过 schema 初始化");
            return 0;
        }
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            log.warn("[图谱] Neo4j 驱动不可用，跳过 schema 初始化（图谱是增强不是依赖，不影响启动）");
            return 0;
        }
        int applied = 0;
        try (var session = driver.session()) {
            for (String ddl : CONSTRAINTS) {
                session.run(ddl).consume();
                applied++;
            }
            log.info("[图谱] schema 就绪：{} 条唯一约束（幂等，重复启动为 no-op）", applied);
        } catch (Exception ex) {
            // 🔴 永不抛：Neo4j 没起来不该让应用起不来
            log.error("[图谱] schema 初始化失败（**不影响启动与文档入库**）：已建 {} 条，原因={}",
                    applied, ex.toString());
        }
        return applied;
    }
}
