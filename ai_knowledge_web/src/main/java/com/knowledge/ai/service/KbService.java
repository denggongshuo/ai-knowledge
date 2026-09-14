package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.enums.KbConfigDefaults;
import com.knowledge.ai.core.enums.KbPresets;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.ai.ModelConfigSpec;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.dto.kb.KbDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库主体（M04 · T-400 / T-401 / T-402 / T-403）。
 *
 * <h3>本类只做「读 + 建库」</h3>
 * <p>B1 的边界：全部接口**不产生需要重建索引的副作用**（B1 计划 §3.3）。
 * 保存配置（{@code T-404}）、重建索引（{@code T-406}）、清空 / 软删（{@code T-407}）
 * 都在 B2 / B3 —— 它们要引入 {@code needReindex} 状态与 before/after 登记，
 * 混在本批里会让"读侧是否可信"的验收失去隔离。
 *
 * <h3>可见性一律复用求交引擎，绝不自己写 where</h3>
 * <p>{@code T-400} 的数据范围与 {@code T-403} 的单库校验，都走
 * {@link DataScopeService#kbVisibility}（② 数据范围 ∩ ③ 知识库授权，无"或"分支）。
 * <b>任何绕过它直接 {@code select * from sys_kb} 的写法，都会让 G2-7 / G2-8 已有的
 * SQL 层取证失去意义</b> —— 那两条判据证明的正是"过滤真的进了 SQL"。
 * 单库读取没有可复用的封装（{@code KbGrantService#visibleKbIds} 是列表形态），
 * 所以这里用同一条 SQL 片段 + {@code k.id = :id} 收窄，拼装来源仍是
 * {@code DataScopeService}，不是手抄的条件。
 *
 * <h3>JSON 列用注入的 ObjectMapper（Jackson 3）</h3>
 * <p>与 {@code AuditLogService} 同一口径：注入 Boot 4 自动配置的那个
 * （{@code tools.jackson.databind.ObjectMapper}），不自己 {@code new} ——
 * classpath 上同时有 Jackson 3 与 Jackson 2（JWT 库的传递依赖），
 * 自己 new 会绕开 Boot 的统一配置。实证见 {@code JacksonProbeTest}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 状态 → 展示文案。W1 的落点：域内真值不动，展示由派生字段承担。 */
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "READY", "已就绪",
            "INDEXING", "索引中");

    private final DataScopeService dataScopeService;

    private final ModelClientRegistry modelClientRegistry;

    /** 与 {@code AuditLogService} 同一个（Boot 自动配置的 Jackson 3）。 */
    private final ObjectMapper jsonMapper;

    @PersistenceContext
    private EntityManager entityManager;

    // ==================== T-400 · 列表 ====================

    /**
     * 知识库列表（数据范围 ∩ 授权，SQL 层过滤）。
     *
     * <p><b>{@code keyword} 在 SQL 里收窄而不是查完内存过滤</b>：与数据范围同理由 ——
     * 内存过滤在数据量上去之后是全表扫描，且一旦漏掉一个分支就是越权
     * （{@code DataScopeService} 类注释里那段话同样适用于这里）。
     *
     * @param scope {@code all} / {@code mine}；{@code mine} = 我创建的（W5）。
     *              {@code null} / 其它值按 {@code all} 处理 —— 前端「全部」按钮传的就是 {@code all}。
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public KbDtos.KbPage list(String scope, String keyword, Long deptId, String status) {
        LoginUser user = LoginUserContext.require();
        DataScopeService.ScopeCondition cond = dataScopeService.kbVisibility(user);
        Map<String, Object> params = new LinkedHashMap<>(cond.params());

        StringBuilder sql = new StringBuilder(
                "select k.id, k.name, k.doc_count, k.chunk_count from sys_kb k where ")
                .append(cond.sql());
        StringBuilder countSql = new StringBuilder("select count(*) from sys_kb k where ")
                .append(cond.sql());

        if ("mine".equals(scope)) {
            // W5：前端 KbListView 的「我创建的」按钮真实发 scope=mine，
            // 语义取 owner_id = 当前用户（B1 计划 §3.4 假设 2），不是"本部门"。
            sql.append(" and k.owner_id = :owner");
            countSql.append(" and k.owner_id = :owner");
            params.put("owner", user.getUserId());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and k.name like :kw");
            countSql.append(" and k.name like :kw");
            params.put("kw", "%" + keyword.trim() + "%");
        }
        if (deptId != null && deptId > 0) {
            sql.append(" and k.dept_id = :dept");
            countSql.append(" and k.dept_id = :dept");
            params.put("dept", deptId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and k.status = :status");
            countSql.append(" and k.status = :status");
            params.put("status", status);
        }
        sql.append(" order by k.id");

        Query countQuery = entityManager.createNativeQuery(countSql.toString());
        Query listQuery = entityManager.createNativeQuery(sql.toString());
        params.forEach((name, value) -> {
            countQuery.setParameter(name, value);
            listQuery.setParameter(name, value);
        });

        long total = ((Number) countQuery.getSingleResult()).longValue();
        List<Object[]> rows = listQuery.getResultList();
        List<KbDtos.KbBrief> list = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            list.add(new KbDtos.KbBrief(
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue()));
        }
        return new KbDtos.KbPage(list, total);
    }

    // ==================== T-402 · 预设 ====================

    /** 新建向导的数据源：5 个预设 + 默认配置 + 只读嵌入模型 + VL 模型投影。 */
    @Transactional(readOnly = true)
    public KbDtos.KbPresetsResponse presets() {
        return new KbDtos.KbPresetsResponse(
                KbPresets.all(),
                defaultConfigForm(),
                embeddingInfo(),
                vlModelInfo());
    }

    // ==================== T-401 · 创建 ====================

    /**
     * 建库（🚩 审计打点：模块「知识库」，首用 {@code AuditModule.KB}）。
     *
     * <p><b>风险等级 NORMAL</b>：§4.3 四类高危不含"建库"（它不扩大既有权限，
     * 新建的库初始只授给向导里勾选的角色）。同批在 {@code AuditRiskPolicy} 登记一行。
     *
     * <p><b>为什么不用 {@code AuditContext.change()} 登记变更明细</b>：建库没有 before
     * —— 与 A 阶段的判据 5 同口径（{@code changes_json} 落 NULL，不写 {@code []}），
     * 前端详情抽屉对 {@code null} 与 {@code []} 的渲染等价，但语义不同。
     */
    @Transactional
    @AuditLog(module = AuditModule.KB, action = "创建知识库", risk = RiskLevel.NORMAL,
            target = "#form.name")
    public KbDtos.KbCreated create(KbDtos.KbConfigForm form) {
        // ============ ① 业务校验（结构校验已由 @Valid 完成）============
        // 部门必须存在：deptId 是数据范围 ② 的计算基准，指到不存在的部门等于建了个
        // 谁都看不见的库（DEPT / DEPT_AND_CHILD 范围下永远匹配不上）。
        requireDept(form.deptId());
        // 编码唯一：先查一次给出明确错误码，同时保留数据库唯一约束作最后一道闸
        // （并发下两个请求可能同时通过这一步，靠 uk_kb_code 兜底）。
        Number existing = (Number) entityManager.createNativeQuery(
                        "select count(*) from sys_kb where code = :code")
                .setParameter("code", form.code().trim())
                .getSingleResult();
        if (existing != null && existing.longValue() > 0) {
            throw BizException.of(ErrorCode.KB_CODE_EXISTS);
        }

        // ============ ② 落库 ============
        LoginUser user = LoginUserContext.require();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", form.code().trim());
        params.put("name", form.name().trim());
        params.put("deptId", form.deptId());
        params.put("status", "READY");
        // 新库没有文档，计数从 0 起；owner 取当前登录用户（scope=mine 的判定依据）
        params.put("ownerId", user.getUserId());
        params.put("description", form.description());
        params.put("secretLevel", resolveSecretLevel(form.secretLevel()).name());
        params.put("businessDomain", form.businessDomain());
        params.put("customTags", toJson(form.customTags() == null || form.customTags().isEmpty()
                ? null : form.customTags()));
        params.put("splitPolicy", toJson(form.splitPolicy() == null ? defaultSplitPolicy() : form.splitPolicy()));
        params.put("tablePolicy", toJson(form.tablePolicy() == null ? defaultTablePolicy() : form.tablePolicy()));
        params.put("retrievalConfig",
                toJson(form.retrievalConfig() == null ? defaultRetrievalConfig() : form.retrievalConfig()));
        params.put("now", now);

        Query insert = entityManager.createNativeQuery("""
                        insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status, owner_id,
                                            description, secret_level, business_domain, custom_tags,
                                            split_policy_json, table_policy_json, retrieval_config_json,
                                            created_at, updated_at)
                        values (:code, :name, :deptId, 0, 0, :status, :ownerId,
                                :description, :secretLevel, :businessDomain, :customTags,
                                :splitPolicy, :tablePolicy, :retrievalConfig, :now, :now)
                        """);
        params.forEach(insert::setParameter);
        insert.executeUpdate();
        // 用 code 回查主键：原生 insert 拿不到自增值，而 code 此刻刚被证明唯一
        Long id = ((Number) entityManager.createNativeQuery(
                        "select id from sys_kb where code = :code")
                .setParameter("code", params.get("code"))
                .getSingleResult()).longValue();

        // ============ ③ 可见角色 → 授权行（档位映射见 §3.4 假设 1）============
        writeGrants(id, form.visibleRoles(), now);

        return new KbDtos.KbCreated(id, (String) params.get("name"), "READY",
                "创建成功，即将跳转文档上传页");
    }

    // ==================== T-403 · 配置读取 ====================

    /**
     * 设置页回显。
     *
     * <p><b>可见性校验的顺序是刻意的</b>：先判"库存在吗"（10043），再判"看得见吗"
     * （10022）。反过来的话，越权者探不到库 id 是否有效 —— 但那样"库不存在"与
     * "无权限"会返回同一个错误码，正常用户输错 id 时拿不到可执行的提示。
     * 本仓既有口径（{@code KbGrantController} 的 {@code requireKb}）也是先判存在。
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public KbDtos.KbConfig config(long kbId) {
        LoginUser user = LoginUserContext.require();
        requireKb(kbId);

        DataScopeService.ScopeCondition cond = dataScopeService.kbVisibility(user);
        // ⚠️ 拼接处保留前导空格：text block **会剥掉每行行尾空白**，
        // 写成 `... and """ + cond.sql()` 会拼出 `andEXISTS`（语法错，已实测踩到）。
        Query query = entityManager.createNativeQuery("""
                        select k.id, k.name, k.code, k.description, k.dept_id, k.doc_count, k.chunk_count,
                               k.status, k.created_at, k.updated_at, k.secret_level, k.business_domain,
                               k.custom_tags, k.split_policy_json, k.table_policy_json, k.retrieval_config_json
                        from sys_kb k where k.id = :id and """ + " " + cond.sql());
        query.setParameter("id", kbId);
        cond.params().forEach(query::setParameter);

        List<Object[]> rows = query.getResultList();
        if (rows.isEmpty()) {
            // 库存在但求交不放行 —— ② 部门在范围外，或 ③ 没有任何授权（I-7 的两种拦法）
            throw BizException.of(ErrorCode.KB_ACCESS_DENIED);
        }
        Object[] row = rows.getFirst();

        String status = (String) row[7];
        KbDtos.KbConfigForm config = new KbDtos.KbConfigForm(
                (String) row[1],
                (String) row[2],
                ((Number) row[4]).longValue(),
                roleCodesOf(kbId),
                (String) row[10],
                (String) row[11],
                (String) row[3],
                parseTags((String) row[12]),
                parseOr(row[13], KbDtos.SplitPolicy.class, defaultSplitPolicy()),
                parseOr(row[14], KbDtos.TablePolicy.class, defaultTablePolicy()),
                parseOr(row[15], KbDtos.RetrievalConfig.class, defaultRetrievalConfig()));

        return new KbDtos.KbConfig(
                ((Number) row[0]).longValue(),
                (String) row[1],
                (String) row[2],
                (String) row[3],
                departmentName(((Number) row[4]).longValue()),
                ((Number) row[4]).longValue(),
                ((Number) row[5]).longValue(),
                ((Number) row[6]).longValue(),
                status,
                statusLabel(status),
                toTimeText(row[8]),
                toTimeText(row[9]),
                roleCodesOf(kbId),
                config,
                embeddingInfo(),
                vlModelInfo(),
                KbPresets.all());
    }

    // ==================== 内部：校验 ====================

    private void requireKb(long kbId) {
        Number n = (Number) entityManager.createNativeQuery("select count(*) from sys_kb where id = :id")
                .setParameter("id", kbId)
                .getSingleResult();
        if (n == null || n.longValue() == 0) {
            throw BizException.of(ErrorCode.KB_NOT_FOUND);
        }
    }

    private void requireDept(Long deptId) {
        if (deptId == null || deptId <= 0) {
            throw BizException.of(ErrorCode.DEPT_NOT_FOUND);
        }
        Number n = (Number) entityManager.createNativeQuery("select count(*) from sys_department where id = :id")
                .setParameter("id", deptId)
                .getSingleResult();
        if (n == null || n.longValue() == 0) {
            throw BizException.of(ErrorCode.DEPT_NOT_FOUND);
        }
    }

    // ==================== 内部：授权写入 ====================

    /**
     * 按可见角色写入 {@code sys_kb_grant}（档位映射 = B1 计划 §3.4 假设 1）。
     *
     * <p><b>超管恒加授 MANAGE</b>，即便向导里没勾 —— 否则会建出一个**创建者自己都看不见
     * 的孤儿库**（超管在 ALL 范围下确实看得见全部库，但 DEPT 范围的库管员建库时若漏勾，
     * 后续 {@code /kb/grants} 页面就会呈现"这个库没有任何角色可见"）。
     * 这条与 V7 的种子口径（{@code SUPER_ADMIN → MANAGE}）一致。
     */
    private void writeGrants(long kbId, List<String> visibleRoles, LocalDateTime now) {
        Set<String> codes = new LinkedHashSet<>();
        codes.add("SUPER_ADMIN");
        if (visibleRoles != null) {
            visibleRoles.stream().filter(c -> c != null && !c.isBlank()).forEach(codes::add);
        }

        List<Object[]> roles = entityManager.createNativeQuery(
                        "select id, code from sys_role where code in (:codes) order by id")
                .setParameter("codes", new ArrayList<>(codes))
                .getResultList();

        for (Object[] role : roles) {
            String code = (String) role[1];
            String level = switch (code) {
                case "SUPER_ADMIN" -> "MANAGE";
                case "KB_ADMIN" -> "UPLOAD";
                default -> "READ";
            };
            entityManager.createNativeQuery("""
                            insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                            values (:kbId, 'ROLE', :roleId, :level, :now, :now)
                            """)
                    .setParameter("kbId", kbId)
                    .setParameter("roleId", ((Number) role[0]).longValue())
                    .setParameter("level", level)
                    .setParameter("now", now)
                    .executeUpdate();
        }
    }

    /** 该库已授权的角色 code（{@code order by id}，与创建时提交顺序同一口径）。 */
    @SuppressWarnings("unchecked")
    private List<String> roleCodesOf(long kbId) {
        List<String> codes = entityManager.createNativeQuery("""
                        select r.code from sys_kb_grant g
                        join sys_role r on r.id = g.principal_id
                        where g.kb_id = :id and g.principal_type = 'ROLE' and g.level <> 'NONE'
                        order by r.id
                        """)
                .setParameter("id", kbId)
                .getResultList();
        return new ArrayList<>(codes);
    }

    // ==================== 内部：投影 ====================

    /**
     * 嵌入模型投影，{@code locked} 恒 true（{@code G3-2} 判据）。
     *
     * <p>维度取 {@code MilvusConstants.DIMENSION}（= 实际建集合用的那个值），
     * <b>不是</b>从模型配置读 —— 两者若不一致，是集合维度配错了，
     * 应当由启动自检报出来，而不是在这里悄悄显示模型声称的维度。
     *
     * <p>{@code norm} 由 {@code MilvusConstants.METRIC_TYPE} 如实派生，而不是写死
     * 字符串：库里用的是 L2，展示就说 L2。写死 "L2 归一化" 的后果是，
     * 哪天度量换成 COSINE，页面仍然宣称 L2 —— 界面上看不出来的那种错。
     */
    private KbDtos.EmbeddingInfo embeddingInfo() {
        String model = "BAAI/bge-m3";
        try {
            ModelConfigSpec spec = modelClientRegistry.activeSpec(ModelType.EMBEDDING);
            model = spec.model();
        } catch (BizException ex) {
            // 未配置嵌入模型时不让 /kb/presets 整体失败：向导仍要能用（只是显示默认型号）
            log.warn("嵌入模型未配置，/kb/presets 回退到默认展示值：{}", ex.getMessage());
        }
        return new KbDtos.EmbeddingInfo(model, MilvusConstants.DIMENSION,
                normLabel(MilvusConstants.METRIC_TYPE), true);
    }

    /** 度量方式 → 展示文案。L2 因 bge-m3 已做归一化而等价于余弦。 */
    private String normLabel(String metricType) {
        return switch (metricType) {
            case "L2" -> "L2 归一化";
            case "COSINE" -> "余弦相似度";
            case "IP" -> "内积";
            default -> metricType;
        };
    }

    /**
     * VL 视觉模型投影。
     *
     * <p>取不到时返 {@code {name:'', enabled:false, appliedAt:''}} 而**不抛错**：
     * VL 模型是可选能力（只有含图片的文档用得到），它没配不该让整个创建向导打不开。
     * 这与 {@code embeddingInfo} 的容错口径一致。
     *
     * <p>{@code appliedAt} 恒空串 —— 无来源（W3），前端用 {@code v-if} 守卫。
     */
    private KbDtos.VlModelInfo vlModelInfo() {
        try {
            ModelConfigSpec spec = modelClientRegistry.activeSpec(ModelType.VL);
            return new KbDtos.VlModelInfo(spec.model(), true, "");
        } catch (BizException ex) {
            return new KbDtos.VlModelInfo("", false, "");
        }
    }

    /** 状态 → 展示文案；未知状态原样返回（宁可显示裸值，也不显示错误的标签）。 */
    private String statusLabel(String status) {
        if (status == null) {
            return "";
        }
        return STATUS_LABELS.getOrDefault(status, status);
    }

    private String departmentName(long deptId) {
        if (deptId <= 0) {
            return UserPrincipalAssembler.UNASSIGNED_DEPT_NAME;
        }
        Object name = entityManager.createNativeQuery("select name from sys_department where id = :id")
                .setParameter("id", deptId)
                .getSingleResult();
        return name == null ? UserPrincipalAssembler.UNASSIGNED_DEPT_NAME : (String) name;
    }

    // ==================== 内部：JSON ====================

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        return jsonMapper.writeValueAsString(value);
    }

    /**
     * 读 JSON 列；{@code null} 或解析失败都用默认值兜底，**不报错**。
     *
     * <p>兜底的理由：这三列是 V9 新加的，历史上手工插入的行必然是 NULL。
     * 让"某行没配策略"表现成 500 会把整个设置页打挂；而策略有明确的系统默认值，
     * 用默认值渲染是**可解释**的行为（用户看到的正是新库会用的那套参数）。
     */
    private <T> T parseOr(Object raw, Class<T> type, T fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return jsonMapper.readValue((String) raw, type);
        } catch (Exception ex) {
            log.warn("知识库策略 JSON 解析失败，回退默认值：type={} raw={}", type.getSimpleName(), raw, ex);
            return fallback;
        }
    }

    /**
     * 自定义标签：{@code NULL} → 空列表（前端 {@code customTags: []}）。
     *
     * <p>与 {@code changes_json} 的口径刻意不同：那里 {@code NULL} 与 {@code []}
     * 语义不同（"没有对照"vs"采集了但为空"），这里两者都是"没有标签"，
     * 归成 {@code []} 才不会让前端渲染出 {@code null}。
     *
     * <p>用 {@code TypeReference} 而不是 {@code getTypeFactory().constructCollectionType(…)}：
     * Jackson 3（{@code tools.jackson}）已把 TypeFactory 从 ObjectMapper 上摘走，
     * 后者编译不过。{@code TypeReference} 是本仓 {@code JacksonProbeTest} 实证过的写法。
     */
    private List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            log.warn("知识库自定义标签 JSON 解析失败，归为空列表：raw={}", raw, ex);
            return List.of();
        }
    }

    private SecretLevel resolveSecretLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return KbConfigDefaults.SECRET_LEVEL;
        }
        try {
            return SecretLevel.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "密级取值不合法：" + raw);
        }
    }

    // ==================== 内部：默认值 ====================

    /** 默认配置表（与前端 {@code defaultKbConfig()} 逐字一致，见 {@code KbConfigDefaults}）。 */
    public static KbDtos.KbConfigForm defaultConfigForm() {
        return new KbDtos.KbConfigForm(
                "",
                "kb_",
                KbConfigDefaults.DEPT_ID,
                KbConfigDefaults.VISIBLE_ROLES,
                KbConfigDefaults.SECRET_LEVEL.name(),
                KbConfigDefaults.BUSINESS_DOMAIN,
                "",
                List.of(),
                defaultSplitPolicy(),
                defaultTablePolicy(),
                defaultRetrievalConfig());
    }

    private static KbDtos.SplitPolicy defaultSplitPolicy() {
        return new KbDtos.SplitPolicy(KbConfigDefaults.SPLIT_PRESET,
                KbConfigDefaults.CHUNK_SIZE, KbConfigDefaults.OVERLAP,
                KbConfigDefaults.RECURSIVE_SPLIT, KbConfigDefaults.SEMANTIC_SPLIT);
    }

    private static KbDtos.TablePolicy defaultTablePolicy() {
        return new KbDtos.TablePolicy(KbConfigDefaults.ATOMIC_TABLE,
                KbConfigDefaults.DUAL_REPRESENTATION, KbConfigDefaults.REPEAT_HEADER,
                KbConfigDefaults.MERGE_CROSS_PAGE, KbConfigDefaults.OCR_TABLE,
                KbConfigDefaults.ROW_LIMIT, KbConfigDefaults.ROW_WINDOW);
    }

    private static KbDtos.RetrievalConfig defaultRetrievalConfig() {
        return new KbDtos.RetrievalConfig(KbConfigDefaults.RETRIEVAL_MODE.name(),
                KbConfigDefaults.RECALL_THRESHOLD, KbConfigDefaults.RERANK_TOP_K,
                KbConfigDefaults.RERANK_ENABLED, KbConfigDefaults.TABLE_WEIGHT,
                KbConfigDefaults.IDENTIFIER_PROTECT);
    }

    /**
     * 原生查询里的 DATETIME 列转字符串。Hibernate 7 返回 {@link LocalDateTime}
     * （直接强转 {@code java.sql.Timestamp} 会 ClassCastException，已踩过），两种都收。
     */
    private String toTimeText(Object value) {
        if (value instanceof LocalDateTime ldt) {
            return TIME_FORMAT.format(ldt);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return TIME_FORMAT.format(ts.toLocalDateTime());
        }
        return value == null ? "" : String.valueOf(value);
    }
}
