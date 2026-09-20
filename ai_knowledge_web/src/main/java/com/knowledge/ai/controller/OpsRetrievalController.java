package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.ops.OpsRetrievalDtos;
import com.knowledge.ai.service.OpsRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营分析 —— 检索质量分析页（需求 `08` §2.6 的五条端点；`T-A03`）。
 *
 * <h3>🔴 权限：只有一个新的只读码 `ops:retrieval:view`，**只给超管**</h3>
 * <p>它**刻意不给 `AUDITOR`**（与页 11 的 `ops:qa:view` 不同）——
 * 于是同一个 token 在页 11 是 200、在页 20 是 403，这条边界**可证伪**。
 * 承 `#153`/`#168`：两个码射程若完全相同，新加的那个就是**谁也区分不出**的空码，
 * 那正是"不该加码、该复用码"的信号。
 *
 * <h3>为什么五个端点全是只读，且**一个审计点都不加**</h3>
 * <p>需求 §2.6 列的五条全是 `GET`，没有写动作 ⇒ 不触发《05》§4.3 的高危六类
 * ⇒ **审计冻结清单仍 37**（新增审计点要同步三处，见 `plan/汇总.md` §5 —— 本批不涉及）。
 *
 * <h3>为什么读侧也挂码（而不是"只要求登录"）</h3>
 * <p>与 `OpsQaController` 同一条理由：这一页是**全站视角**的检索质量，
 * 与 `GET /system/thresholds`（表单/展示数据源）不是一类东西。
 */
@RestController
@RequestMapping("/api/ops/retrieval")
@RequiredArgsConstructor
public class OpsRetrievalController {

    private final OpsRetrievalService opsRetrievalService;

    /** `GET /ops/retrieval/overview?range=7d` —— 四个统计指标（需求 §2.1）。 */
    @GetMapping("/overview")
    @RequiresPerm("ops:retrieval:view")
    public ApiResponse<OpsRetrievalDtos.Overview> overview(
            @RequestParam(required = false) String range) {
        return ApiResponse.ok(opsRetrievalService.overview(range));
    }

    /** `GET /ops/retrieval/pipeline?range=7d` —— 各阶段耗时与输出量（需求 §2.2）。 */
    @GetMapping("/pipeline")
    @RequiresPerm("ops:retrieval:view")
    public ApiResponse<OpsRetrievalDtos.Pipeline> pipeline(
            @RequestParam(required = false) String range) {
        return ApiResponse.ok(opsRetrievalService.pipeline(range));
    }

    /** `GET /ops/retrieval/score-distribution?range=7d` —— 相似度分档分布（需求 §2.3）。 */
    @GetMapping("/score-distribution")
    @RequiresPerm("ops:retrieval:view")
    public ApiResponse<OpsRetrievalDtos.ScoreDistribution> scoreDistribution(
            @RequestParam(required = false) String range) {
        return ApiResponse.ok(opsRetrievalService.scoreDistribution(range));
    }

    /** `GET /ops/retrieval/rerank-effect?range=7d` —— 重排前后对比（需求 §2.4）。 */
    @GetMapping("/rerank-effect")
    @RequiresPerm("ops:retrieval:view")
    public ApiResponse<OpsRetrievalDtos.RerankEffect> rerankEffect(
            @RequestParam(required = false) String range) {
        return ApiResponse.ok(opsRetrievalService.rerankEffect(range));
    }

    /** `GET /ops/retrieval/kb-quality?range=7d` —— 各知识库质量排行（需求 §2.5）。 */
    @GetMapping("/kb-quality")
    @RequiresPerm("ops:retrieval:view")
    public ApiResponse<OpsRetrievalDtos.KbQuality> kbQuality(
            @RequestParam(required = false) String range) {
        return ApiResponse.ok(opsRetrievalService.kbQuality(range));
    }
}
