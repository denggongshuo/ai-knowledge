package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.kb.KbDtos;
import com.knowledge.ai.service.KbService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库主体（M04 · 《10》§5，T-400 / T-401 / T-402 / T-403）。
 *
 * <h3>⚠️ 与 {@link KbGrantController} 同居 {@code /api/kb} 前缀</h3>
 * <p>本类与授权接口共用前缀，四个映射各有归属，**不要在这里再加 {@code /grants} 系**：
 * <pre>
 *   GET  /api/kb                    ← 本类（列表）
 *   GET  /api/kb/presets            ← 本类（字面路径）
 *   POST /api/kb                    ← 本类（创建）
 *   GET  /api/kb/{kbId}/config      ← 本类（配置读取）
 *   GET  /api/kb/grants             ← KbGrantController
 *   GET  /api/kb/{kbId}/grants      ← KbGrantController
 *   PUT  /api/kb/{kbId}/grants      ← KbGrantController
 * </pre>
 * <p>两条 {@code GET} 会**同时匹配** {@code /api/kb/{kbId}} 之外的路径时，Spring 的
 * 字面路径优先于路径变量，所以 {@code /kb/presets} 不会被当成 {@code kbId=presets}
 * 去吃（{@code KbGrantController} 类注释记录了同一个坑在 mock 侧的形态）。
 * 但这条优先级**只在精确段数一致时成立** —— 若将来把列表改成 {@code /kb/{scope}}，
 * 就会与 {@code /kb/presets} 撞车。这是把它写在这里的原因。
 *
 * <h3>权限挂法（三个档位，都不是随手选的）</h3>
 * <ul>
 *   <li>{@code GET /kb} → {@code kb:view}（「查看知识库列表」，5 个角色全有）；</li>
 *   <li>{@code GET /kb/{id}/config} → {@code kb:list}（「查看知识库详情」，
 *       比 {@code view} 窄 —— 设置页能看到策略与授权角色，不是所有能看列表的人都该看）。
 *       此外还有**逐库可见性校验**在 Service 里（{@code @RequiresPerm} 只管功能权限，
 *       不管逐库授权 —— 见 {@code RequiresPerm} 类注释的三维求交说明）；</li>
 *   <li>{@code POST /kb} → {@code kb:create}；风险 NORMAL（建库不属 §4.3 四类）。</li>
 * </ul>
 *
 * <p><b>{@code GET /kb/presets} 刻意不挂权限码，只要求登录</b>：它是创建向导的
 * **表单数据源**（预设 / 默认值 / 只读模型），与 {@code GET /depts/tree}、
 * {@code GET /roles} 同型。挂上 {@code kb:create} 会把"能进创建页"与"能读表单字典"
 * 绑死，而前者由前端路由守卫控制、两者不是一回事。
 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KbController {

    private final KbService kbService;

    /**
     * {@code GET /kb?scope=&keyword=&deptId=&status=} —— 列表（数据范围 ∩ 授权，SQL 层）。
     *
     * <p><b>{@code scope} 是前端真实发的参数</b>（{@code KbListView.vue:24} 的
     * 单选按钮「全部 / 我创建的」），《06》§1.7 与《10》§5 此前只登记了
     * {@code keyword/deptId/status} —— 本批补上（B1 计划 §1.3 W5）。
     * 缺了它，「我创建的」点了没反应，属**静默失效**（不报错、只是结果不对）。
     */
    @GetMapping
    @RequiresPerm("kb:view")
    public ApiResponse<KbDtos.KbPage> list(@RequestParam(required = false) String scope,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Long deptId,
                                           @RequestParam(required = false) String status) {
        return ApiResponse.ok(kbService.list(scope, keyword, deptId, status));
    }

    /** {@code GET /kb/presets} —— 5 个预设 + 默认配置 + 只读嵌入模型 + VL 模型（只要求登录）。 */
    @GetMapping("/presets")
    public ApiResponse<KbDtos.KbPresetsResponse> presets() {
        return ApiResponse.ok(kbService.presets());
    }

    /** {@code POST /kb} —— 建库（body = 完整 {@code KbConfigForm}）；审计：知识库 / 创建知识库 / NORMAL。 */
    @PostMapping
    @RequiresPerm("kb:create")
    public ApiResponse<KbDtos.KbCreated> create(@Valid @RequestBody KbDtos.KbConfigForm form) {
        return ApiResponse.ok(kbService.create(form));
    }

    /** {@code GET /kb/{kbId}/config} —— 设置页回显；越权 10022 / 不存在 10043。 */
    @GetMapping("/{kbId}/config")
    @RequiresPerm("kb:list")
    public ApiResponse<KbDtos.KbConfig> config(@PathVariable long kbId) {
        return ApiResponse.ok(kbService.config(kbId));
    }
}
