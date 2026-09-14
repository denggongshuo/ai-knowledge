package com.knowledge.ai.core.enums;

import java.util.List;

/**
 * 新建知识库的默认配置 —— 前端 {@code defaultKbConfig()} 的后端版本（T-401 / T-402）。
 *
 * <p><b>为什么必须存在</b>：{@code GET /kb/presets} 要返回 {@code defaults}，
 * 而创建向导第 4 步的「非默认项汇总」是把用户填的表单与**这份默认值**逐项 diff。
 * 两边只要有一个字不同（哪怕只是 {@code businessDomain} 写成"研发/平台"少一个空格），
 * 每个用户都会看到一堆"其实没改过"的变更提示 —— 页面看起来像坏了。
 * 所以默认值必须与前端**逐字一致**，并靠 {@code KbModuleFlowTest} 钉住。
 *
 * <p>数值源：{@code ai_knowledge_app/src/mock/data.ts:389} 的 {@code defaultKbConfig()}。
 *
 * <p><b>与 {@link KbPresets} 的分工</b>：{@code KbPresets} 是 5 个**可选**预先设定，
 * 本类是**初始值**（= GENERAL 预设 + 系统默认表格/检索参数）。两者在
 * {@code splitPolicy} 上有重叠是正常的 —— 默认值本来就是"选中通用预设"的结果。
 *
 * <p>⚠️ {@code businessDomain} 取「研发 / 平台」（斜杠两侧各一个半角空格）、
 * {@code recallThreshold} 取 {@code 0.15}（不是 {@code 0.5} 之类"看起来更合理"的值）、
 * {@code tableWeight} 取 {@code 1.2} —— 都是前端既成口径，改前先改前端。
 */
public final class KbConfigDefaults {

    private KbConfigDefaults() {
    }

    // ==================== 治理标签 ====================

    /** 默认密级。演示数据里的库全是 INTERNAL，保持一致。 */
    public static final SecretLevel SECRET_LEVEL = SecretLevel.INTERNAL;

    /** 默认业务域。 */
    public static final String BUSINESS_DOMAIN = "研发 / 平台";

    /** 默认归属部门（研发中心）。 */
    public static final long DEPT_ID = 1L;

    /** 默认可见角色。 */
    public static final List<String> VISIBLE_ROLES = List.of("SUPER_ADMIN");

    // ==================== 分片策略（= GENERAL 预设） ====================

    public static final String SPLIT_PRESET = "GENERAL";

    public static final int CHUNK_SIZE = 512;

    public static final int OVERLAP = 64;

    /** 第二级 · 递归分隔符，默认开。 */
    public static final boolean RECURSIVE_SPLIT = true;

    /** 第二级 · 语义切分，默认关（断点不可复现）。 */
    public static final boolean SEMANTIC_SPLIT = false;

    // ==================== 表格处理策略（六项 + 两个数值） ====================

    public static final boolean ATOMIC_TABLE = true;

    public static final boolean DUAL_REPRESENTATION = true;

    public static final boolean REPEAT_HEADER = true;

    public static final boolean MERGE_CROSS_PAGE = true;

    public static final boolean OCR_TABLE = true;

    public static final int ROW_LIMIT = 30;

    public static final int ROW_WINDOW = 5;

    // ==================== 检索配置 ====================

    public static final RetrievalMode RETRIEVAL_MODE = RetrievalMode.HYBRID;

    public static final double RECALL_THRESHOLD = 0.15;

    public static final int RERANK_TOP_K = 5;

    public static final boolean RERANK_ENABLED = true;

    public static final double TABLE_WEIGHT = 1.2;

    /** 标识符保护：型号 / 金额 / 日期整体成 token。 */
    public static final boolean IDENTIFIER_PROTECT = true;

    // ==================== 校验口径（与前端 validateStep0 同源） ====================

    /** 库名长度下限（前端 {@code validateStep0}：name 2~40 字）。 */
    public static final int NAME_MIN = 2;

    public static final int NAME_MAX = 40;

    /**
     * 库编码规则：{@code kb_} 前缀 + **至少 2 位**小写字母/数字/下划线。
     *
     * <p>与前端 {@code /^kb_[a-z0-9_]{2,}$/} 逐字一致 —— 大写 {@code kb_X} 会被前端拒、
     * 后端也必须拒。两边不一致的后果是"前端过了后端拒"（用户看到 10030 却不知哪错了）
     * 或反过来"前端拦得住、直接调接口能建出脏编码"。
     */
    public static final String CODE_PATTERN = "^kb_[a-z0-9_]{2,}$";

    /** 库描述长度上限，与 {@code sys_kb.description VARCHAR(200)} 同值。 */
    public static final int DESCRIPTION_MAX = 200;
}
