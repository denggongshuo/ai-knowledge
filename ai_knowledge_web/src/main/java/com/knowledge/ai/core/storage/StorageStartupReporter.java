package com.knowledge.ai.core.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动期五存储自检报告（T-008 / 验收门 G0-5）。
 *
 * <p>所有初始化动作（{@code MilvusCollectionInitializer} @Order(20)、
 * {@code MinioBucketInitializer} @Order(30)）跑完之后再体检，
 * 所以这份报告反映的是"初始化之后"的真实状态，不是"刚连上那一瞬间"。
 *
 * <p>输出形如：
 * <pre>
 * ==================== 存储连通性自检（T-008 / G0-5） ====================
 *   [ OK ] MySQL   mysql:8.4 schema=knowledge_base · 12ms
 *   [ OK ] Redis   PING=PONG · 3ms
 *   [ OK ] Milvus  collection=knowledge_vectors fields=8 dim=1024 metric=L2 · 48ms
 *   [ OK ] MinIO   bucket=rag-documents · http://127.0.0.1:9000 · 9ms
 *   [ OK ] Neo4j   bolt://127.0.0.1:7687 · Neo4j 5.26.0 · 15ms
 *   → 5/5 连通，/actuator/health 与 GET /api/health/storage 可复查
 * ======================================================================
 * </pre>
 *
 * <p><b>默认 fail-fast = false</b>：本地开发时某个容器没起来，
 * 仍然希望应用能启动并用其它功能（例如只调前端页面）。
 * 需要"缺依赖就拒绝启动"的场合（CI / spike 取证），把
 * {@code app.storage.fail-fast=true} 打开即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(100)
public class StorageStartupReporter implements ApplicationRunner {

    private final StorageConnectivityService connectivity;

    @Value("${app.storage.fail-fast:false}")
    private boolean failFast;

    @Override
    public void run(ApplicationArguments args) {
        List<StorageStatus> statuses = connectivity.probeAll();

        StringBuilder report = new StringBuilder("\n")
                .append("==================== 存储连通性自检（T-008 / G0-5） ====================\n");
        for (StorageStatus status : statuses) {
            report.append(status.up() ? "  [ OK ] " : "  [FAIL] ")
                    .append(pad(status.store(), 7))
                    .append(pad(status.costMs() + "ms", 7))
                    .append(status.detail())
                    .append('\n');
        }

        long downCount = statuses.stream().filter(s -> !s.up()).count();
        if (downCount == 0) {
            report.append("  → ").append(statuses.size()).append("/").append(statuses.size())
                    .append(" 连通；复查：/actuator/health 或 GET /api/health/storage\n");
            report.append("======================================================================");
            log.info(report.toString());
            return;
        }

        String downNames = statuses.stream()
                .filter(s -> !s.up())
                .map(StorageStatus::store)
                .reduce((a, b) -> a + ", " + b)
                .orElse("(未知)");
        report.append("  → ").append(statuses.size() - downCount).append("/").append(statuses.size())
                .append(" 连通，不可用：").append(downNames)
                .append("\n======================================================================");
        log.error(report.toString());

        if (failFast) {
            throw new IllegalStateException("存储自检失败（app.storage.fail-fast=true）：不可用 → " + downNames);
        }
        log.warn("存储自检存在失败项，应用继续启动（app.storage.fail-fast=false）。"
                + "上表中 [FAIL] 项对应的功能不可用，其余功能不受影响。");
    }

    private String pad(String text, int width) {
        if (text.length() >= width) {
            return text + " ";
        }
        return text + " ".repeat(width - text.length());
    }
}
