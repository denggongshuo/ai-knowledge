package com.knowledge.ai.core.ai;

import java.util.List;

/**
 * 不重排的占位客户端（`T-702`）—— 未配置 Rerank 模型时的降级实现。
 *
 * <h3>为什么要有它，而不是让 {@code rerankClient()} 返回 null</h3>
 * <p>检索链路上"Rerank 不可用"是一条**正常的业务分支**（`.env` 里 {@code RERANKER_API_*}
 * 可以为空，用户也可以不启用重排模型），不是异常。返回 {@code null} 会让每个调用点
 * 都要判空——而漏判一个就是 NPE，且**只在没配 Rerank 的机器上复现**。
 * 一个永远 {@code available() == false}、永远返回空列表的实现，把这个分支收口成一种取值。
 *
 * <p><b>为什么 {@link #describe()} 不含端点信息</b>：它代表的是"**没有**端点"，
 * 给一个像样的 URL 反而会让人以为配上了。
 */
public final class NoOpRerankClient implements RerankClient {

    /** 无状态，全局一个实例即可。 */
    public static final NoOpRerankClient INSTANCE = new NoOpRerankClient();

    private NoOpRerankClient() {
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String describe() {
        return "未配置重排模型（走不重排降级）";
    }

    @Override
    public List<RerankHit> rerank(String query, List<String> documents, int topK) {
        return List.of();
    }
}
