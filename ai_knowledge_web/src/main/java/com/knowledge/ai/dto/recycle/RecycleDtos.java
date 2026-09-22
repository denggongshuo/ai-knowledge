package com.knowledge.ai.dto.recycle;

import java.util.List;

/**
 * 回收站（`T-616` · 需求 `06` §7）的对外契约。
 *
 * <p>字段与前端 {@code types/index.ts:1324 RecycleItem} **逐字对齐** ——
 * 本仓的教训是"两份各自演化的声明，字段对不上时不报错、只是静默空白"。
 *
 * <h3>🔴 两条写接口都要带 `type`，这不是可选的便利参数</h3>
 * <p>{@code sys_document.id} 与 {@code sys_kb.id} 是**两个独立的自增序列**
 * ⇒ 单独一个 `ids` 列表**是有歧义的**：id=5 可能既是文档又是知识库。
 * 需求 `06` §7.8 的契约里没有这个字段（原样写的是 `{ ids: [], targetKbId? }`），
 * 而"先查文档、查不到再查知识库"那种兜底会在两者都存在时**静默地恢复/删掉错的那一个**
 * —— 本仓对"会静默做错事"的兜底一律不接受。故加为**必填**，并同步改前端（1 行）。
 */
public final class RecycleDtos {

    private RecycleDtos() {
    }

    /**
     * 回收站列表的一行（文档或知识库，由 {@code type} 区分）。
     *
     * @param type          {@code DOC} / {@code KB}（与 `GET /recycle/items?type=` 同一个词表）
     * @param origin        文档：**原所属知识库**名；知识库：**所属部门**名
     *                      （需求 §7.4 的两行原文，两族的"来源"含义不同）
     * @param docCount      知识库条目：其下**会被连带恢复**的文档数。
     *                      ⚠️ 文档条目恒 {@code null}（不是 0 —— 0 是在说"这篇文档下有 0 篇文档"）
     * @param deletedBy     删除人姓名；{@code null} = 该行在 `V41` 之前被删，**删人已不可考**
     *                      ⇒ 前端显示「—」（不编一个"未知用户"）
     * @param remainDays    剩余保留天数 = {@code 30 − (今天 − 删除日)}。⚠️ 由**服务端**算：
     *                      "今天"在服务端才算得准（时区），而前端 `types` 里它是**必填**的
     * @param originDeleted 文档条目：**原知识库也已删除** ⇒ 恢复时要选目标库（需求 §7.5）
     * @param hasNewer      文档条目：同一文档组**已有更新版本** ⇒ 恢复旧版会造成版本混乱（§7.5 ③）
     */
    public record RecycleItem(long id, String type, String name, String origin, Integer docCount,
                              long size, String deletedBy, String deletedAt, int remainDays,
                              Boolean originDeleted, Boolean hasNewer) {
    }

    /**
     * 回收站列表。
     *
     * @param keepDays 保留期（恒 {@link com.knowledge.ai.service.RecycleService#KEEP_DAYS}）。
     *                 ⚠️ 它是**响应**而不是前端的常量：页头那句「保留 30 天」如果写死在前端，
     *                 哪天策略改了就会出现"后端清 60 天、页面说 30 天"的静默不一致
     */
    public record RecyclePage(List<RecycleItem> list, long total, int keepDays) {
    }

    /**
     * 恢复请求。
     *
     * @param type       **必填** {@code DOC} / {@code KB}，理由见类注释
     * @param ids        要恢复的 id（同一 type 下）
     * @param targetKbId 仅文档：原知识库已删时指定恢复到的库（需求 §7.5「文档」Tab）
     */
    public record RestoreRequest(String type, List<Long> ids, Long targetKbId) {
    }

    /**
     * 恢复结果。
     *
     * @param needReindex 恒 {@code true}（需求 §7.2「恢复后**需要重建索引**才能参与检索」）。
     *                    ⚠️ **刻意不做"恢复时顺带重建"** —— 重建是 `T-406`/`T-610` 的入口，
     *                    而且它很贵（花钱、花时间）。这里如实告诉用户下一步该做什么，
     *                    而不是替他做一个他可能不想要的重活
     * @param affectedDocs 本次一共影响多少篇文档。⚠️ 恢复知识库时它是**连带**恢复的篇数
     *                     （需求 §7.3「必须在确认框里讲清楚影响多少文档」）—— 服务端算，
     *                     因为前端只能拿列表里的 `docCount` 估，而列表可能是上一页的
     */
    public record RestoreResult(int restored, boolean needReindex, int affectedDocs,
                                Long targetKbId, String note) {
    }

    /**
     * 彻底删除请求（🚩 不可逆）。
     *
     * @param type **必填** {@code DOC} / {@code KB}，理由见类注释
     */
    public record PurgeRequest(String type, List<Long> ids) {
    }

    /** 彻底删除结果：{@code deleted} = 实际删掉的条数（恒等于请求条数，因为整批拒绝语义）。 */
    public record PurgeResult(int deleted) {
    }

    /** 清空回收站的结果：{@code cleared} = 清掉的**条目数**（不是文档数）。 */
    public record ClearResult(int cleared) {
    }

    /**
     * 定时清理结果（`POST /recycle/cleanup`）。
     *
     * @param cleaned 本次清掉的条目数。⚠️ **幂等判据看的就是它**：连跑两次，第二次必须是 {@code 0}
     * @param note    给调用方（定时任务 / 运维）看的一句话
     */
    public record CleanupResult(int cleaned, String note) {
    }
}
