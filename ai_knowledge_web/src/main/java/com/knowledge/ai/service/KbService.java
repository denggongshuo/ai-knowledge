package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditContext;
import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.enums.KbConfigDefaults;
import com.knowledge.ai.core.enums.KbPresets;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.core.enums.RetrievalMode;
import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.ai.ModelConfigSpec;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.dto.kb.KbDtos;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Objects;
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

    /**
     * 配置行的共用 select（列索引表见 {@link #configRowOrDeny} 的注释）。
     *
     * <p>⚠️ 末尾以 {@code and } 结尾，拼装条件时**必须补一个空格** —— 这里是普通
     * 字符串字面量所以行尾空格还在，但拼装侧仍显式写 {@code + " " +}：
     * B1 曾用 text block 写这段，text block 会剥掉行尾空白，拼出 {@code andEXISTS}
     * 这种只在真跑时才暴露的语法错（B1 计划 §8.1）。
     */
    private static final String CONFIG_SELECT = """
            select k.id, k.name, k.code, k.description, k.dept_id, k.doc_count, k.chunk_count,
                   k.status, k.created_at, k.updated_at, k.secret_level, k.business_domain,
                   k.custom_tags, k.split_policy_json, k.table_policy_json, k.retrieval_config_json,
                   k.reindex_required
            from sys_kb k where k.id = :id and """;

    /**
     * 重建速率（分片 / 分钟），仅用于估算耗时。
     *
     * <p>取值来自《09》§1.10 的示例：38,502 个分片 ≈ 约 42 分钟，即约 917 分片/分钟，
     * 取整到 900 留一点余量。**是估算口径不是性能承诺** —— S4 的真实重建跑出数据后
     * 应当以实测替换它（已登记为建议）。
     */
    private static final long REBUILD_CHUNKS_PER_MINUTE = 900L;

    private final DataScopeService dataScopeService;

    private final ModelClientRegistry modelClientRegistry;

    /** 与 {@code AuditLogService} 同一个（Boot 自动配置的 Jackson 3）。 */
    private final ObjectMapper jsonMapper;

    /** 对象存储客户端（{@code T-408} 的配额卡要算桶用量）。 */
    private final MinioClient minioClient;

    /**
     * 业务桶名。用字段注入而不是构造器参数：Lombok 的 {@code @RequiredArgsConstructor}
     * 不会给 {@code final} 字段注入 {@code @Value} 的配置值（拿到的是 {@code null}）。
     */
    @Value("${minio.bucket:rag-documents}")
    private String minioBucket;

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
    public KbDtos.KbConfig config(long kbId) {
        LoginUser user = LoginUserContext.require();
        Object[] row = configRowOrDeny(kbId, user);

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
                asBoolean(row[16]),
                roleCodesOf(kbId),
                config,
                embeddingInfo(),
                vlModelInfo(),
                KbPresets.all());
    }

    // ==================== T-404 · 保存配置 ====================

    /**
     * 保存配置（🚩 审计打点：模块「知识库」，动作「保存配置」）。
     *
     * <p><b>为什么标 HIGH</b>：本请求**可以改 {@code visibleRoles}**，而它落到
     * {@code sys_kb_grant} 上就是"该库可见角色集全量替换" —— 勾上 {@code KB_ADMIN}
     * 会把库的最高档位从 {@code READ} 抬到 {@code UPLOAD}，正是《05》§4.3①
     * 「**知识库授权级别提高**」。这与建库标 NORMAL 不矛盾：建库时新库还没有任何
     * 内容、初始授权也不覆盖任何既有权限；而编辑一个已有内容的库是真扩权。
     *
     * <p>口径的代价与备选见 B2 计划 §3.4 假设 4：策略表是按 {@code (module, action)}
     * 做键的静态表，做不到"只改了描述就降级"，两害相权选了"宁可粗、不漏标"。
     *
     * <p><b>不改 {@code code}</b>：{@code uk_kb_code} + 《01》§10.1 把它定义为
     * 创建后不可改的持久标识。提交了不同的值**显式报错而不是静默忽略** ——
     * 静默忽略会让用户以为改成功了。
     *
     * <p><b>登记顺序与 {@code KbGrantService#save} 同口径</b>：先读旧值 → 登记 → 再写库。
     * 反过来的话 {@code executeUpdate} 之后旧值就没了，2026-09-14 那次已经把这条写成注释。
     *
     * @return {@code needReindex} 由服务端按字段集算出（只有 14 个"分片 + 表格 + 检索"
     *         字段会触发重建，逐条见 {@link #diffConfig}）
     */
    @Transactional
    @AuditLog(module = AuditModule.KB, action = "保存配置", risk = RiskLevel.HIGH,
            target = "'知识库#' + #kbId")
    public KbDtos.KbConfigSaved saveConfig(long kbId, KbDtos.KbConfigForm form) {
        LoginUser user = LoginUserContext.require();
        Object[] row = configRowOrDeny(kbId, user);

        // ---------- ① 校验 ----------
        String storedCode = (String) row[2];
        if (form.code() == null || !storedCode.equals(form.code().trim())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "知识库编码 `" + storedCode + "` 创建后不可修改");
        }
        requireDept(form.deptId());
        KbDtos.SplitPolicy split = requireValidSplitPolicy(form.splitPolicy());
        KbDtos.TablePolicy table = form.tablePolicy() == null ? defaultTablePolicy() : form.tablePolicy();
        KbDtos.RetrievalConfig retrieval =
                form.retrievalConfig() == null ? defaultRetrievalConfig() : form.retrievalConfig();
        SecretLevel secret = resolveSecretLevel(form.secretLevel());

        List<String> oldRoles = roleCodesOf(kbId);
        List<String> newRoles = normalizeRoles(form.visibleRoles());

        // ---------- ② 算 diff 并登记（必须在写库之前）----------
        List<ConfigChange> changes = diffConfig(row, form, split, table, retrieval, secret,
                oldRoles, newRoles);
        boolean needReindex = changes.stream().anyMatch(ConfigChange::rebuild);
        for (ConfigChange change : changes) {
            AuditContext.change(change.label(), change.before(), change.after());
        }

        // ---------- ③ 写库 ----------
        LocalDateTime now = LocalDateTime.now();
        // reindex_required 是**粘性**的：本次改动不需要重建时**不清零** ——
        // 上一个改动留下的"脏"还没被 T-406（S4）清掉，清了就等于谎报索引是新的。
        boolean reindexRequired = asBoolean(row[16]) || needReindex;
        Query update = entityManager.createNativeQuery("""
                        update sys_kb set name = :name, description = :description, dept_id = :deptId,
                               secret_level = :secretLevel, business_domain = :businessDomain,
                               custom_tags = :customTags, split_policy_json = :splitPolicy,
                               table_policy_json = :tablePolicy, retrieval_config_json = :retrievalConfig,
                               reindex_required = :reindexRequired, updated_at = :now
                        where id = :id
                        """);
        update.setParameter("id", kbId);
        update.setParameter("name", form.name().trim());
        update.setParameter("description", blankToNull(form.description()));
        update.setParameter("deptId", form.deptId());
        update.setParameter("secretLevel", secret.name());
        update.setParameter("businessDomain", form.businessDomain());
        update.setParameter("customTags", toJson(form.customTags() == null || form.customTags().isEmpty()
                ? null : form.customTags()));
        update.setParameter("splitPolicy", toJson(split));
        update.setParameter("tablePolicy", toJson(table));
        update.setParameter("retrievalConfig", toJson(retrieval));
        update.setParameter("reindexRequired", reindexRequired ? 1 : 0);
        update.setParameter("now", now);
        update.executeUpdate();

        // ---------- ④ 可见角色变了才动授权行 ----------
        // 只在真的变了时替换：全量替换会重写 created_at，白白破坏审计线索。
        if (!new LinkedHashSet<>(oldRoles).equals(new LinkedHashSet<>(newRoles))) {
            replaceGrants(kbId, newRoles, now);
        }

        return new KbDtos.KbConfigSaved(true, needReindex, TIME_FORMAT.format(now));
    }

    // ==================== T-405 · 影响预估 ====================

    /**
     * 变更影响预估。请求体与保存**同一个表单**，diff 也复用同一段代码
     * （理由见 {@link KbDtos.ReindexImpact} 的注释）。
     *
     * <p><b>估算模型</b>：分片数与"有效步长"成反比 —— 同一份正文，
     * 窗口越细切得越多。有效步长 = {@code chunkSize - overlap}（这正是两级分片
     * 流水线里第二级的推进量）。所以
     * {@code chunkAfter ≈ chunkBefore × 旧步长 ÷ 新步长}。
     *
     * <p>这是**估算**不是承诺：G3-4 只要求与重建后的真实值同量级（±10%），
     * 且该判据要等 S4 有重建能力才能验（本批挂起）。
     */
    @Transactional(readOnly = true)
    public KbDtos.ReindexImpact impact(long kbId, KbDtos.KbConfigForm form) {
        LoginUser user = LoginUserContext.require();
        Object[] row = configRowOrDeny(kbId, user);

        KbDtos.SplitPolicy newSplit = requireValidSplitPolicy(form.splitPolicy());
        KbDtos.SplitPolicy oldSplit = parseOr(row[13], KbDtos.SplitPolicy.class, defaultSplitPolicy());

        long docCount = ((Number) row[5]).longValue();
        long chunkBefore = ((Number) row[6]).longValue();
        long stepOld = Math.max(1, oldSplit.chunkSize() - oldSplit.overlap());
        long stepNew = Math.max(1, newSplit.chunkSize() - newSplit.overlap());
        // 空库（chunkBefore = 0）就该返 0 —— 硬凑成 1 会让"这个库还没有文档"
        // 显示成"预计 1 个分片"，是那种界面上看不出来的假数据。
        long chunkAfter = chunkBefore == 0
                ? 0
                : Math.max(1, Math.round(chunkBefore * (double) stepOld / stepNew));

        long minutes = chunkAfter == 0 ? 0 : Math.max(1, (chunkAfter + REBUILD_CHUNKS_PER_MINUTE - 1)
                / REBUILD_CHUNKS_PER_MINUTE);
        // tokens 按"每个分片嵌入 chunkSize 个 token"折算：与正在编辑的那个参数同源，
        // 所以把 chunkSize 调大时它会同向变，而不是像写死系数那样对不上。
        long tokens = chunkAfter * newSplit.chunkSize();
        return new KbDtos.ReindexImpact(docCount, chunkBefore, chunkAfter, minutes, tokens);
    }

    // ==================== T-408 · 存储配额 ====================

    /**
     * 全站配额（消费方是 S4 的 {@code DocDownloadView} 配额卡）。
     *
     * <p>三个计数取自 {@code sys_kb} 的**冗余计数列**而不是实时统计文档表：
     * 它们是建库/上传时维护的投影（B1 建库写 0），与列表页显示的同一口径，
     * 不会出现"列表说 1246、配额说 0"这种同页矛盾。
     *
     * <p>{@code monthTokens} 恒 0 —— 无来源表（B2 计划 §1 W-a）。
     */
    @Transactional(readOnly = true)
    public KbDtos.StorageQuota storageQuota() {
        // 软删的库不计入配额（`T-407`）：用户看到的用量应与列表里的库对得上 ——
        // 回收站里的库还在列表外，却把用量算进去，就是"数字对不上"的那类投诉
        Object[] agg = (Object[]) entityManager.createNativeQuery("""
                        select count(*), coalesce(sum(doc_count), 0), coalesce(sum(chunk_count), 0)
                        from sys_kb
                        where deleted_at is null
                        """).getSingleResult();
        return new KbDtos.StorageQuota(
                humanSize(minioUsedBytes()),
                ((Number) agg[0]).longValue(),
                ((Number) agg[1]).longValue(),
                ((Number) agg[2]).longValue(),
                0L);
    }

    // ==================== T-411 · 只读解析配置 ====================

    /**
     * 上传页四栏只读展示。**是"库配置 + 系统常量"的投影，没有写侧**
     * （改配置走 {@code PUT /kb/{id}/config}，理由见 {@link KbDtos.ParseConfigView}）。
     *
     * <p>文案逐条对齐 {@code mock/data.ts} 的 {@code PARSE_CONFIG_VIEW}：那张表是
     * 前端·mock·需求三方对账过的既有口径，此处只把**随库变化的部分**
     * （预设 / chunkSize / overlap / 六项表格策略 / 嵌入与 VL 模型）换成真值。
     */
    @Transactional(readOnly = true)
    public KbDtos.ParseConfigView parseConfig(long kbId) {
        LoginUser user = LoginUserContext.require();
        Object[] row = configRowOrDeny(kbId, user);

        KbDtos.SplitPolicy split = parseOr(row[13], KbDtos.SplitPolicy.class, defaultSplitPolicy());
        KbDtos.TablePolicy table = parseOr(row[14], KbDtos.TablePolicy.class, defaultTablePolicy());
        KbDtos.EmbeddingInfo embedding = embeddingInfo();
        KbDtos.VlModelInfo vl = vlModelInfo();

        List<KbDtos.ParseConfigGroup> splitGroups = List.of(
                group("策略预设", presetText(split.preset()), false, false, false),
                group("第一级 · 结构边界", "标题层级 / 表格整表 / 代码块 / 完整列表", true, false, false),
                group("第二级 · 递归分隔符", split.recursiveSplit()
                        ? "开 · 段落 → 句号 → 分号 → 空格" : "关", false, false, false),
                // 语义切分按《06》§1.4.3 只作"无标题且超长"的兜底，故恒带风险提示
                group("第二级 · 语义切分", split.semanticSplit()
                        ? "开 · 仅无标题且超长时兜底" : "关 · 仅无标题且超长时兜底", false, true, false),
                group("chunkSize / overlap", split.chunkSize() + " / " + split.overlap() + " tokens",
                        false, false, false),
                group("嵌入模型", embedding.model() + " · " + embedding.dimension() + " 维 · "
                        + embedding.norm(), true, false, false));

        List<KbDtos.ParseConfigGroup> tableGroups = List.of(
                group("整表原子化", (table.atomicTable() ? "开" : "关") + " · 锁定不切分", true, false, false),
                group("双表示", table.dualRepresentation()
                        ? "Markdown 给大模型 / 行式文本给向量" : "关", false, false, false),
                group("表头重复", table.repeatHeader() ? "开" : "关", false, false, false),
                group("跨页表格合并", table.mergeCrossPage() ? "开 · 列数一致时并回一张表" : "关",
                        false, false, false),
                group("扫描件表格识别", table.ocrTable() ? "开 · 失败标红" : "关", false, false, false),
                group("回表行数上限", table.rowLimit() + " 行 · ±" + table.rowWindow(), false, false, false),
                group("表格召回通道", "未单开（Rerank 加权 " + KbConfigDefaults.TABLE_WEIGHT + " 代替）",
                        false, false, false));

        List<KbDtos.ParseConfigGroup> imageGroups = List.of(
                group("图片处理", "开 · 图片整体原子化（不切分）", true, false, false),
                group("题注优先", "有题注则并入图片描述", false, false, false),
                group("VL 描述生成", vl.enabled() ? "开 · 产出描述 + 检索摘要" : "关 · 未启用 VL 模型",
                        false, false, vl.enabled()),
                group("当前 VL 模型", vl.enabled() ? vl.name() : "未配置", true, false, false),
                group("图片去重", "按 md5 去重，同图只理解一次", false, false, false),
                group("识别失败标记", "开 · 标红以便补录", false, true, false));

        List<KbDtos.ParseConfigGroup> enhanceGroups = List.of(
                group("标题路径注入", "把「3. 接口设计 > 3.1 鉴权流程」拼进分片", false, false, true),
                group("HyQ 假设性问题", "为每个分片生成可能被问到的问句一并索引", false, false, true),
                group("文档级摘要", "仅文档级，不做 chunk 级摘要", false, false, true),
                group("内容标签抽取", "复用摘要同一次 LLM 调用，边际成本为零", false, false, true));

        return new KbDtos.ParseConfigView(splitGroups, tableGroups, imageGroups, enhanceGroups);
    }

    // ==================== 内部：配置读（三处接口共用）====================

    /**
     * 按「库存在 + 求交放行」读一行配置原始列。
     *
     * <p>顺序刻意与 {@link #config} 一致：先判存在（10043），再判可见（10022）。
     * 反过来的话正常用户输错 id 只会拿到"无权限"，没有可执行的提示。
     *
     * <p><b>列索引表</b>（三处接口共用一份 select，改名/加列时一起看）：
     * <pre>
     *   0 id  1 name  2 code  3 description  4 dept_id  5 doc_count  6 chunk_count  7 status
     *   8 created_at  9 updated_at  10 secret_level  11 business_domain  12 custom_tags
     *   13 split_policy_json  14 table_policy_json  15 retrieval_config_json  16 reindex_required
     * </pre>
     *
     * @throws BizException 10043 库不存在 / 10022 求交不放行（② 部门在范围外，或 ③ 无任何授权）
     */
    @SuppressWarnings("unchecked")
    private Object[] configRowOrDeny(long kbId, LoginUser user) {
        requireKb(kbId);
        DataScopeService.ScopeCondition cond = dataScopeService.kbVisibility(user);
        // ⚠️ 拼接处保留前导空格：text block **会剥掉每行行尾空白**，
        // 写成 `... and """ + cond.sql()` 会拼出 `andEXISTS`（语法错，已实测踩到）。
        Query query = entityManager.createNativeQuery(CONFIG_SELECT + " " + cond.sql());
        query.setParameter("id", kbId);
        cond.params().forEach(query::setParameter);

        List<Object[]> rows = query.getResultList();
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.KB_ACCESS_DENIED);
        }
        return rows.getFirst();
    }

    // ==================== 内部：diff ====================

    /**
     * 一条字段变更：展示用标签 + 前后值（已是展示文本）+ 是否使索引失效。
     */
    private record ConfigChange(String label, String before, String after, boolean rebuild) {
    }

    /**
     * 逐字段比对并产出变更清单。顺序 = 前端 {@code TAB_FIELDS} 的拼接顺序
     * （basic → split → table → tags），标签逐条对齐前端 {@code FIELD_LABEL}。
     *
     * <p><b>值与"是否变了"都按展示文本比较</b>，与前端 {@code dirty()}（用 {@code fmt()} 比）
     * 同口径。代价：纯空白差异（描述尾部多个空格）不算变更 —— 换来的是"审计里的变更条数"
     * 与"页面上高亮的字段个数"永远一致，不会一边说 3 项、一边只高亮 2 个。
     */
    private List<ConfigChange> diffConfig(Object[] row, KbDtos.KbConfigForm form,
                                          KbDtos.SplitPolicy split, KbDtos.TablePolicy table,
                                          KbDtos.RetrievalConfig retrieval, SecretLevel secret,
                                          List<String> oldRoles, List<String> newRoles) {
        KbDtos.SplitPolicy oldSplit = parseOr(row[13], KbDtos.SplitPolicy.class, defaultSplitPolicy());
        KbDtos.TablePolicy oldTable = parseOr(row[14], KbDtos.TablePolicy.class, defaultTablePolicy());
        KbDtos.RetrievalConfig oldRetrieval =
                parseOr(row[15], KbDtos.RetrievalConfig.class, defaultRetrievalConfig());

        List<ConfigChange> out = new ArrayList<>();
        // ---------- 基础信息（不影响索引）----------
        add(out, "知识库名称", (String) row[1], form.name().trim(), false);
        add(out, "描述", (String) row[3], blankToNull(form.description()), false);
        add(out, "归属部门", departmentName(((Number) row[4]).longValue()),
                departmentName(form.deptId()), false);

        // ---------- 分片 + 检索（前端 TAB_FIELDS.split，**14 个重建字段里的前 8 个**）----------
        add(out, "分片策略预设", presetText(oldSplit.preset()), presetText(split.preset()), true);
        add(out, "chunkSize", oldSplit.chunkSize(), split.chunkSize(), true);
        add(out, "overlap", oldSplit.overlap(), split.overlap(), true);
        add(out, "语义切分", oldSplit.semanticSplit(), split.semanticSplit(), true);
        add(out, "检索方式", modeText(oldRetrieval.mode()), modeText(retrieval.mode()), true);
        add(out, "召回阈值", oldRetrieval.recallThreshold(), retrieval.recallThreshold(), true);
        add(out, "Rerank 保留数", oldRetrieval.rerankTopK(), retrieval.rerankTopK(), true);
        add(out, "Rerank 开关", oldRetrieval.rerankEnabled(), retrieval.rerankEnabled(), true);

        // ---------- 表格处理（前端 TAB_FIELDS.table，**后 6 个重建字段**）----------
        add(out, "双表示", oldTable.dualRepresentation(), table.dualRepresentation(), true);
        add(out, "表头重复", oldTable.repeatHeader(), table.repeatHeader(), true);
        add(out, "跨页表格合并", oldTable.mergeCrossPage(), table.mergeCrossPage(), true);
        add(out, "扫描件表格识别", oldTable.ocrTable(), table.ocrTable(), true);
        add(out, "回表行数上限", oldTable.rowLimit(), table.rowLimit(), true);
        add(out, "命中行窗口", oldTable.rowWindow(), table.rowWindow(), true);

        // ---------- 治理标签（不影响索引）----------
        add(out, "密级", secretText((String) row[10]), secretText(secret.name()), false);
        add(out, "业务域", (String) row[11], form.businessDomain(), false);
        add(out, "自定义标签", parseTags((String) row[12]), form.customTags(), false);
        // 可见角色排最后：它是本接口唯一会改授权的字段，也是本动作标高危的原因
        add(out, "可见角色", roleCodesOrdered(oldRoles), roleCodesOrdered(newRoles), false);
        return out;
    }

    /** 展示文本相同即视为未变；{@code null} 交给 {@code AuditChange} 规范化成 {@code —}。 */
    private static void add(List<ConfigChange> out, String label, Object before, Object after,
                            boolean rebuild) {
        String b = text(before);
        String a = text(after);
        if (Objects.equals(b, a)) {
            return;
        }
        out.add(new ConfigChange(label, b, a, rebuild));
    }

    /** 值 → 展示文本。布尔/列表/数字都在这里统一，避免各处各写一套格式。 */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag ? "开启" : "关闭";
        }
        if (value instanceof List<?> list) {
            return list.isEmpty()
                    ? null
                    : String.join("、", list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    /**
     * 预设 id → 展示名。
     *
     * <p>用 {@code KbPresets} 而不是 {@code SplitPreset} 枚举：页面下拉渲染的是前者
     * （{@code KbPresets} 的标签逐条对齐 {@code mock/data.ts} 的 SPLIT_PRESETS，
     * 与枚举那份确实不同字 —— "通用文档" vs "通用"）。
     *
     * <p><b>{@code CUSTOM} 必须特判成「自定义」</b>：它不是内置预设，
     * {@link KbPresets#find} 对它返 {@code null}，直接兜底会把审计明细写成英文 {@code CUSTOM}，
     * 而页面在同一位置显示的是中文「自定义」—— 同一条变更两处文案不一致。
     * 口径与前端 {@code KbDetailView#presetLabel} 逐字一致。
     *
     * <p>真正未知的 id 原样返回：宁可让人看见一个陌生的英文 id，
     * 也不要显示成"自定义"（那会掩盖脏数据）。
     */
    private static String presetText(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if ("CUSTOM".equals(id)) {
            return "自定义";
        }
        KbPresets.Preset preset = KbPresets.find(id);
        return preset == null ? id : preset.label();
    }

    /** 密级 → 中文（{@code SecretLevel.label}，与前端 {@code SECRET_LABEL} 逐字一致）。 */
    private static String secretText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SecretLevel.valueOf(raw).getLabel();
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    /** 检索方式 → 中文（{@code RetrievalMode.label}，与前端 {@code RETRIEVAL_LABEL} 逐字一致）。 */
    private static String modeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return RetrievalMode.valueOf(raw).getLabel();
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    /** 角色 code 集合 → 按**角色 id** 排序的名字串。两侧同一排序，比较才不会因顺序误报变更。 */
    @SuppressWarnings("unchecked")
    private String roleCodesOrdered(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        List<String> names = entityManager.createNativeQuery(
                        "select name from sys_role where code in (:codes) order by id")
                .setParameter("codes", new ArrayList<>(codes))
                .getResultList();
        return names.isEmpty() ? null : String.join("、", names);
    }

    // ==================== 内部：校验（写侧）====================

    /**
     * 分片几何校验：{@code 0 <= overlap < chunkSize}。
     *
     * <p>不成立时"有效步长"（{@code chunkSize - overlap}）为零或负，分片会退化 ——
     * 估算侧会除零、重建侧会无限切片。前端表单也限制了这个区间，这里是**服务端兜底**。
     *
     * <p>⚠️ {@code create} 尚未接这道校验（B1 已上线，本批不动它的行为），
     * 已登记为遗留 —— 不等价之处在于：创建能存进一个"改的时候会被拒"的配置。
     */
    private KbDtos.SplitPolicy requireValidSplitPolicy(KbDtos.SplitPolicy policy) {
        KbDtos.SplitPolicy value = policy == null ? defaultSplitPolicy() : policy;
        if (value.chunkSize() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "chunkSize 必须大于 0");
        }
        if (value.overlap() < 0 || value.overlap() >= value.chunkSize()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "overlap 必须大于等于 0 且小于 chunkSize（当前 " + value.overlap() + " / " + value.chunkSize() + "）");
        }
        return value;
    }

    /** 可见角色归一化：**超管恒在**（与创建同口径，否则保存一次就把超管挤出去了）。 */
    private static List<String> normalizeRoles(List<String> roles) {
        Set<String> codes = new LinkedHashSet<>();
        codes.add("SUPER_ADMIN");
        if (roles != null) {
            roles.stream().filter(code -> code != null && !code.isBlank()).forEach(codes::add);
        }
        return new ArrayList<>(codes);
    }

    /** 空串归 {@code null}：V9 的 {@code description} 可空，存空串会与 NULL 两个语义并存。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ==================== 内部：MinIO 用量 ====================

    /**
     * MinIO 桶内对象总字节数；失败返 {@code -1}（展示成 {@code —}）。
     *
     * <p>失败**不抛错**：配额是辅助信息，MinIO 抖动不该把下载页整块打不开 ——
     * 与 {@code StorageStartupReporter} 的"失败不阻断"同口径。
     *
     * <p>逐对象累加是 O(n)。当前只在页面挂载时调一次，且桶里还没有文档（返 0）；
     * 等 S4 文档量上来若成瓶颈，改为在 {@code sys_kb} 上累加字节数（B2 计划 §5）。
     */
    private long minioUsedBytes() {
        long total = 0;
        try {
            Iterable<Result<Item>> objects = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(minioBucket).recursive(true).build());
            for (Result<Item> result : objects) {
                total += result.get().size();
            }
            return total;
        } catch (Exception ex) {
            log.warn("MinIO 桶用量统计失败，配额卡 used 显示为 —：{}", ex.toString());
            return -1L;
        }
    }

    /** 字节数 → 展示串（前端契约是带单位的字符串，如 {@code "2.4 GB"}）。 */
    private static String humanSize(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    private static KbDtos.ParseConfigGroup group(String label, String value,
                                                 boolean locked, boolean warning, boolean enabled) {
        return new KbDtos.ParseConfigGroup(label, value, locked, warning, enabled);
    }

    /**
     * TINYINT(1) → 布尔。
     *
     * <p><b>不能直接 {@code ((Number) raw).intValue() != 0}</b>：MySQL 的
     * {@code TINYINT(1)} 在 JDBC 里映射成 {@link Boolean}（不是 Integer），
     * 强转 {@code Number} 会 {@code ClassCastException} → 9999。
     * 与 {@code DataScopeService#kbLevel} 那条"count(*) 返回 BIGINT 不是 BOOLEAN"
     * 是同一类坑、方向相反：那边是 Number 不是 Boolean，这边是 Boolean 不是 Number。
     */
    private static boolean asBoolean(Object raw) {
        if (raw instanceof Boolean flag) {
            return flag;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        return false;
    }

    // ==================== 内部：校验 ====================

    private void requireKb(long kbId) {
        // 软删的库视为不存在（`T-407`）：对已删除的库读配置/存配置都应得到 10043，
        // 而不是"能读能改"（那会让回收站里的库看起来还活着）
        Number n = (Number) entityManager.createNativeQuery(
                        "select count(*) from sys_kb where id = :id and deleted_at is null")
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
     * 全量替换该库的**角色**授权行（额外授权用户不在此列 —— 那是
     * {@code KbGrantService#save} 的 extraUserIds，设置页不管）。
     *
     * <p>为什么是"删光重建"而不是逐行 diff：可见角色是**整体读写单元**，
     * 与 {@code KbGrantService} 的全量替换同型；逐行 diff 在"档位由角色 code 推导"
     * 的模型下反而更容易漂移（同一个角色换个档位要 update 还是 delete+insert 没有定论）。
     *
     * <p>⚠️ 会重写 {@code created_at}，所以调用方**必须先确认角色集真的变了**
     * （{@code saveConfig} 里用 Set 比较过），否则每次保存都刷掉授权行的创建时间。
     */
    private void replaceGrants(long kbId, List<String> roleCodes, LocalDateTime now) {
        entityManager.createNativeQuery(
                        "delete from sys_kb_grant where kb_id = :id and principal_type = 'ROLE'")
                .setParameter("id", kbId)
                .executeUpdate();
        writeGrants(kbId, roleCodes, now);
    }

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
