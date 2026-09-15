package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.PermissionLogic;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.kb.KbDtos;
import com.knowledge.ai.service.KbService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库主体（M04 · 《10》§5，T-400 ~ T-405 / T-408 / T-411）。
 *
 * <h3>⚠️ 与 {@link KbGrantController} 同居 {@code /api/kb} 前缀</h3>
 * <p>本类与授权接口共用前缀，映射各归其主，**不要在这里再加 {@code /grants} 系**：
 * <pre>
 *   GET    /api/kb                    ← 本类（列表）
 *   GET    /api/kb/presets            ← 本类（字面路径）
 *   GET    /api/kb/storage-quota      ← 本类（字面路径）
 *   POST   /api/kb                    ← 本类（创建）
 *   GET    /api/kb/{kbId}/config      ← 本类（配置读取）
 *   PUT    /api/kb/{kbId}/config      ← 本类（保存配置）
 *   POST   /api/kb/{kbId}/config/impact ← 本类（影响预估）
 *   GET    /api/kb/{kbId}/parse-config  ← 本类（只读解析配置）
 *   GET    /api/kb/grants             ← KbGrantController
 *   GET    /api/kb/{kbId}/grants      ← KbGrantController
 *   PUT    /api/kb/{kbId}/grants      ← KbGrantController
 * </pre>
 * <p>两条 {@code GET} 会**同时匹配** {@code /api/kb/{kbId}} 之外的路径时，Spring 的
 * 字面路径优先于路径变量，所以 {@code /kb/presets} 不会被当成 {@code kbId=presets}
 * 去吃（{@code KbGrantController} 类注释记录了同一个坑在 mock 侧的形态）。
 * 但这条优先级**只在精确段数一致时成立** —— 若将来把列表改成 {@code /kb/{scope}}，
 * 就会与 {@code /kb/presets} 撞车。这是把它写在这里的原因。
 *
 * <h3>权限挂法（四个档位，都不是随手选的）</h3>
 * <ul>
 *   <li>{@code GET /kb} → {@code kb:view}（「查看知识库列表」，5 个角色全有）；</li>
 *   <li>{@code GET /kb/{id}/config} 与 {@code /parse-config} → {@code kb:list}
 *       （「查看知识库详情」，比 {@code view} 窄 —— 设置页能看到策略与授权角色，
 *       不是所有能看列表的人都该看）。
 *       此外还有**逐库可见性校验**在 Service 里（{@code @RequiresPerm} 只管功能权限，
 *       不管逐库授权 —— 见 {@code RequiresPerm} 类注释的三维求交说明）；</li>
 *   <li>{@code POST /kb} → {@code kb:create}；风险 NORMAL（建库不属 §4.3 四类）；</li>
 *   <li>{@code PUT /kb/{id}/config} → {@code kb:update} <b>AND</b> {@code kb:config}；
 *       风险 **HIGH**（可改可见角色 = §4.3① 权限扩大，理由见方法注释）；</li>
 *   <li>{@code GET /kb/storage-quota} → {@code kb:view}（全站只读计数）；</li>
 *   <li>{@code POST /kb/{id}/config/impact} → {@code kb:config}（只读预估）。</li>
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

    /**
     * {@code PUT /kb/{kbId}/config} —— 保存配置（body = 完整 {@code KbConfigForm}）。
     *
     * <p><b>权限用 AND 而不是任一</b>：这一个请求同时写"库的身份字段"
     * （名称 / 描述 / 归属部门 → {@code kb:update}）与"检索参数"
     * （分片 / 表格 / 检索 → {@code kb:config}）。取任一等于把两个权限中较弱的那个
     * 当成了足够条件，是**放开**的方向；AND 才是收紧的方向。
     *
     * <p>当前两个码的持有角色完全相同（{@code SUPER_ADMIN} / {@code KB_ADMIN}），
     * 所以 AND 与任一**实际效果一致** —— 但两者一旦分叉，AND 仍然是安全的那一侧。
     *
     * <p>⚠️ <b>该请求还能改 {@code visibleRoles}</b>（可见角色），落到
     * {@code sys_kb_grant}。它对应的是 {@code kb:grant}，而 {@code kb:grant} 的持有角色
     * 与上面两个也完全相同 —— 所以本批没有再叠一个 {@code kb:grant}。
     * **这三个码哪天不再同权，就是这里必须改的时候**（已登记为遗留）。
     */
    @PutMapping("/{kbId}/config")
    @RequiresPerm(value = {"kb:update", "kb:config"}, logic = PermissionLogic.AND)
    public ApiResponse<KbDtos.KbConfigSaved> saveConfig(@PathVariable long kbId,
                                                        @Valid @RequestBody KbDtos.KbConfigRequest request) {
        return ApiResponse.ok(kbService.saveConfig(kbId, request.config()));
    }

    /**
     * {@code POST /kb/{kbId}/config/impact} —— 变更影响预估。
     *
     * <p>只读（不改任何状态），权限只挂 {@code kb:config}：它是保存确认弹窗的数据源，
     * 而弹窗里能看到的只有"要等多久、多少 tokens"，没有比配置本身更敏感的东西。
     */
    @PostMapping("/{kbId}/config/impact")
    @RequiresPerm("kb:config")
    public ApiResponse<KbDtos.ReindexImpact> impact(@PathVariable long kbId,
                                                    @Valid @RequestBody KbDtos.KbConfigRequest request) {
        return ApiResponse.ok(kbService.impact(kbId, request.config()));
    }

    /**
     * {@code GET /kb/{kbId}/parse-config} —— 上传页四栏只读解析配置。
     *
     * <p>权限挂 {@code kb:list}（查看知识库详情）而不是 {@code kb:view}：它暴露的是
     * **该库的**解析参数，与 {@code /kb/{id}/config} 同一档。{@code kb:list} 的持有角色
     * 已覆盖全部可上传角色（{@code USER} 在内），不会把上传页打挂。
     * 同样有 Service 里的逐库可见性校验。
     */
    @GetMapping("/{kbId}/parse-config")
    @RequiresPerm("kb:list")
    public ApiResponse<KbDtos.ParseConfigView> parseConfig(@PathVariable long kbId) {
        return ApiResponse.ok(kbService.parseConfig(kbId));
    }

    /**
     * {@code GET /kb/storage-quota} —— 全站配额卡（消费方是文档下载页）。
     *
     * <p><b>路径注意</b>：这是**字面路径**，与 {@code /kb/{kbId}/...} 段数不同（2 段 vs 3 段），
     * 当下不冲突。但一旦将来补上 {@code GET /kb/{kbId}}（T-410 详情），
     * {@code storage-quota} 就会同时匹配 {@code kbId=storage-quota} ——
     * Spring 的字面路径优先会兜住，不过那正是本类注释开头提醒过的形态。
     */
    @GetMapping("/storage-quota")
    @RequiresPerm("kb:view")
    public ApiResponse<KbDtos.StorageQuota> storageQuota() {
        return ApiResponse.ok(kbService.storageQuota());
    }
}
