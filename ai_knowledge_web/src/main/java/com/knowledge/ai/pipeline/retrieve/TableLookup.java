package com.knowledge.ai.pipeline.retrieve;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表格回表（`T-705` · `G5-6`）—— 把命中的**子块（一行）**换成它所在的**表**。
 *
 * <h3>为什么需要这一步</h3>
 * <p>`D-5` §1.4 把一张表拆成两份：父块是整表 Markdown（**不进检索池**），
 * 子块是逐行的「列名 值」（**进检索池**）。这个拆分对召回是对的 —— 一行一条语义，
 * 向量能算准。但它对**生成**是错的：检索回来的是一条孤立的行，
 * 「型号 A-100，单价 1800」既没有列含义、也答不了「交期最长的是哪个型号」，
 * 因为"还有哪些型号"根本不在这一行里。
 *
 * <p>所以召回之后要把上下文补回来 —— 这是 `G5-6` 的全部内容。
 *
 * <h3>为什么是**替换**而不是**追加**</h3>
 * <p>追加会让同一次命中共出现两条（一条行、一条表），两条的分数还一样。
 * 后果有两层：引用角标指到两处（用户不知道在引哪一条）；
 * 生成侧把同一张表读两遍（白白吃掉一块上下文）。
 * 所以 {@link RetrievedChunk#withTableContext} 换掉内容，把命中行留在
 * {@link RetrievedChunk.TableContext#matchedRow()} 里 —— 那一行**没有第二个来源**。
 *
 * <h3>本类是纯函数</h3>
 * <p>取父块与兄弟子块是 {@link com.knowledge.ai.service.TableLookupService} 的事。
 * 这里只有"给定了事实之后怎么改"—— 于是窗口、越界、行数对不上这些分支
 * 可以脱离数据库逐个用例地钉。
 */
@Slf4j
final class TableLookup {

    private TableLookup() {
    }

    /**
     * 一条表格子块回表所需的事实（由 `Service` 查库得到）。
     *
     * @param parentChunkId 父块分片 id
     * @param parentMarkdown 父块内容（整表 Markdown）
     * @param matchedRow    这个子块在兄弟中的序号，**1 起**（兄弟按 {@code order_no} 排）
     * @param childCount    该父块一共产出了几个子块
     */
    record TableFact(long parentChunkId, String parentMarkdown, int matchedRow, int childCount) {
    }

    /**
     * 窗口参数（库级 {@code TablePolicy} 的两个字段）。
     *
     * @param rowLimit  超过这么多行才裁窗口
     * @param rowWindow 命中行前后各留几行
     */
    record TableParams(int rowLimit, int rowWindow) {
    }

    /**
     * 把 {@code facts} 里有的那些分片换成表格上下文。
     *
     * <p>顺序与条数**不变** —— 回表不是重排，名次、分数、降权标记一律不动。
     * 动了排序就等于在精排之后再排一次，而那次排序没有任何依据。
     *
     * <p>参数按**分片自己所属的库**取，不是按"本次检索的主库"：一次检索可能跨库，
     * 而 {@code rowLimit} 是库级配置（报价库想要大窗口、制度库想要小窗口，
     * 各有各的道理）。取首库或取最值都会让某个库拿到别人设的参数。
     *
     * @param chunks   精排后的结果
     * @param facts    chunkId → 回表事实；不在里面 = 不是表格子块（或父块没取到）
     * @param byKb     kbId → 该库的窗口参数
     * @param fallback 该库没有配置行时用的参数（{@code KbConfigDefaults}）
     */
    static List<RetrievedChunk> apply(List<RetrievedChunk> chunks,
                                      Map<String, TableFact> facts,
                                      Map<Long, TableParams> byKb,
                                      TableParams fallback) {
        if (chunks == null || chunks.isEmpty() || facts == null || facts.isEmpty()) {
            return chunks;
        }

        List<RetrievedChunk> out = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            TableFact fact = facts.get(chunk.chunkId());
            if (fact == null) {
                out.add(chunk);
                continue;
            }
            TableParams params = byKb.getOrDefault(chunk.kbId(), fallback);
            TableWindow.Window window = TableWindow.of(fact.parentMarkdown(), fact.matchedRow(),
                    fact.childCount(), params.rowLimit(), params.rowWindow());
            if (window.note() != null) {
                // 不静默：退回整表是"能容忍但要知道"的情况（每一轮检索都会发生 = 参数或数据有问题）
                log.warn("[回表] 退回整表：chunkId={} parentId={} 原因={}",
                        chunk.chunkId(), fact.parentChunkId(), window.note());
            }
            out.add(chunk.withTableContext(window.markdown(), new RetrievedChunk.TableContext(
                    fact.parentChunkId(), window.rowFrom(), window.rowTo(), window.totalRows(),
                    window.windowed(), chunk.content(), window.note())));
        }
        return List.copyOf(out);
    }
}
