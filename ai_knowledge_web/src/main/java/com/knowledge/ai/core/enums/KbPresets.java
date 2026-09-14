package com.knowledge.ai.core.enums;

import java.util.List;

/**
 * 分片策略预设（5 个）——**产品口径的可执行版本**（T-402）。
 *
 * <p>与 {@code AuditRiskPolicy}（T-301）同型：把口径写成一张**可被用例逐条比对**的表，
 * 于是"后端预设与前端 mock 漂移"会**直接测试失败**，而不是等联调时发现
 * {@code chunkSize} 对不上、库已按错误参数建了一堆分片。
 *
 * <p><b>数值源</b>：{@code ai_knowledge_app/src/mock/data.ts:380} 的 {@code SPLIT_PRESETS}
 * （前端唯一的现成口径），逐条抄来。前端那份是 UI 的展示源，本表是**后端的判定源**，
 * 两边靠 {@code KbModuleFlowTest} 的逐条断言钉住 —— 改一边不改另一边会红。
 *
 * <p><b>为什么硬编码而不建 {@code sys_kb_preset} 表</b>（B1 计划 §5.2）：预设是
 * **产品口径而不是用户数据**。一旦入库，立刻要处理"谁能改预设、改了之后已建的库怎么办、
 * 缓存何时失效"这一串问题，而收益只是"改预设不用发版" —— 对 5 行常量来说不划算。
 *
 * <p>⚠️ {@code tableAtomic} 五项都是 {@code true}：表格整表封存是**第一级的锁定规则**
 * （前端「第一级 · 结构边界」标注为锁定），不是可调参数。之所以仍在预设里逐条写出，
 * 是为了让前端选预设时能一次带出全部参数（含锁定项）—— 少写一个字段，
 * 前端 {@code applyPreset} 就会把该项落到 {@code undefined}。
 */
public final class KbPresets {

    /**
     * 一个内置预设。
     *
     * @param id           取值与前端 {@code SplitPreset} 逐字一致（{@code GENERAL} … {@code QA_PAIR}）
     * @param label        展示名
     * @param desc         一句话适用场景（向导里显示在预设卡片上）
     * @param chunkSize    切分窗口（tokens）
     * @param overlap      相邻分片重叠（tokens）
     * @param semanticSplit 第二级语义切分；**五项全为 false** —— 断点不可复现，默认关
     * @param tableAtomic  表格整表封存（锁定项，恒 true）
     */
    public record Preset(String id, String label, String desc,
                         int chunkSize, int overlap, boolean semanticSplit, boolean tableAtomic) {
    }

    private static final List<Preset> ALL = List.of(
            new Preset("GENERAL", "通用文档", "结构一般、内容混合的常规资料",
                    512, 64, false, true),
            new Preset("STRUCTURED", "结构化规范", "标题层级清晰的规范 / 手册 / API 文档",
                    640, 80, false, true),
            new Preset("CONTRACT", "合同条款", "条款编号明确、需按条切分",
                    384, 48, false, true),
            new Preset("TABLE_HEAVY", "表格密集", "报价单、参数表为主的资料",
                    512, 64, false, true),
            new Preset("QA_PAIR", "问答对", "FAQ、问答知识库",
                    320, 32, false, true));

    private KbPresets() {
    }

    /** 全部预设，**顺序即展示顺序**（前端 {@code preset-row} 按此渲染）。 */
    public static List<Preset> all() {
        return ALL;
    }

    /** 按 id 取预设；{@code CUSTOM} 与未知 id 都返回空（{@code CUSTOM} 不是内置预设）。 */
    public static Preset find(String id) {
        if (id == null) {
            return null;
        }
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    /** 合法 id 列表 —— 供用例与文档生成，避免各处再抄一遍字面量。 */
    public static List<String> ids() {
        return ALL.stream().map(Preset::id).toList();
    }
}
