package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.ops.OpsUsageDtos;
import com.knowledge.ai.service.OpsUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营分析 —— 使用统计页（需求 `08` §3.6 的五条端点；`T-A05`）。
 *
 * <h3>🔴 权限：**按维度拆成两个码**，而不是一个</h3>
 * <p>需求 §3.7 把这一页的可见性分了两层：「**用户维度统计仅对管理员开放**，且不展示具体提问内容」。
 * 前半句是**维度级**的限制 —— 聚合量（提问总数 / 各部门活跃 / 库热度）与
 * **个人维度**（某一行的提问数是**某个人**的）根本不是同一件事。落成两个码：
 *
 * <table>
 *   <tr><th>端点</th><th>码</th><th>谁能读</th></tr>
 *   <tr><td>{@code overview} / {@code trend} / {@code by-dept} / {@code by-kb}</td>
 *       <td>{@code ops:usage:view}</td><td>超管 + **AUDITOR**</td></tr>
 *   <tr><td>{@code top-users}</td><td>{@code ops:usage:users:view}</td><td>**只有超管**</td></tr>
 * </table>
 *
 * <p>⇒ 于是有一条**可证伪**的边界：**同一个 AUDITOR token**，四个统计端点 200、
 * `top-users` **403**。承 `#153`/`#168` 的教训 —— 若两个码射程完全相同，
 * 新加的那个就是谁也区分不出的空码（那正是 `V35` 头注里写下的判据）。
 *
 * <p>⚠️ **为什么不复用 `ops:qa:view`**（页 11 那个）：那会把 `top-users` 一起给 `AUDITOR`，
 * 而页 21 是**唯一**会把"某个人问了多少次"摆出来的运营页 —— 与 §3.7 那半句直接冲突。
 *
 * <h3>为什么五个端点全是只读，且一个审计点都不加</h3>
 * <p>需求 §3.6 列的五条全是 `GET`，没有写动作 ⇒ 不触发《05》§4.3 的高危六类
 * ⇒ **审计冻结清单仍 37**。⚠️ 尤其注意 `top-users` **不是**"导出数据"：
 * 它回的是**排行榜**（姓名 + 次数 + 平均分），既没有提问内容也没有明细，
 * 与 `OpsQaController` 那个带提问原文的 `POST /export` 不是一类东西
 * —— `AuditRiskPolicy` 的 `DATA_EXPORT` 类里列的是「导出报表」，本条不落那一类。
 */
@RestController
@RequestMapping("/api/ops/usage")
@RequiredArgsConstructor
public class OpsUsageController {

    private final OpsUsageService opsUsageService;

    /** `GET /ops/usage/overview?range=7d` —— 四个核心指标（需求 §3.1）。 */
    @GetMapping("/overview")
    @RequiresPerm("ops:usage:view")
    public ApiResponse<OpsUsageDtos.Overview> overview(
            @RequestParam(required = false) String range) {
        return ApiResponse.ok(opsUsageService.overview(range));
    }

    /**
     * `GET /ops/usage/trend?range=7d&granularity=day` —— 时间序列（需求 §3.2）。
     *
     * <p>⚠️ {@code granularity} 本批只支持 `day`（需求 §3.2 的展示要求只有"近 7 天柱状图"）。
     * 传别的值**不报错、按 day 处理** —— 与 `range` 越界夹取同一条口径。
     */
    @GetMapping("/trend")
    @RequiresPerm("ops:usage:view")
    public ApiResponse<OpsUsageDtos.Trend> trend(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String granularity) {
        return ApiResponse.ok(opsUsageService.trend(range, granularity));
    }

    /** `GET /ops/usage/by-dept?range=7d` —— 部门维度（需求 §3.3）。 */
    @GetMapping("/by-dept")
    @RequiresPerm("ops:usage:view")
    public ApiResponse<OpsUsageDtos.ByDept> byDept(
            @RequestParam(required = false) String range) {
        return ApiResponse.ok(opsUsageService.byDept(range));
    }

    /** `GET /ops/usage/by-kb?range=7d` —— 知识库热度（需求 §3.4）。 */
    @GetMapping("/by-kb")
    @RequiresPerm("ops:usage:view")
    public ApiResponse<OpsUsageDtos.ByKb> byKb(
            @RequestParam(required = false) String range) {
        return ApiResponse.ok(opsUsageService.byKb(range));
    }

    /**
     * `GET /ops/usage/top-users?range=7d&limit=10` —— 活跃用户排行（需求 §3.5）。
     *
     * <p>⚠️ 权限码是**另一个**（`ops:usage:users:view`，只给超管）—— 见类注释。
     *
     * <p>⚠️ {@code limit} 收 {@code Integer} 而不是 {@code int}：前端可以不传（默认 10）。
     */
    @GetMapping("/top-users")
    @RequiresPerm("ops:usage:users:view")
    public ApiResponse<OpsUsageDtos.TopUsers> topUsers(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(opsUsageService.topUsers(range, limit));
    }
}
