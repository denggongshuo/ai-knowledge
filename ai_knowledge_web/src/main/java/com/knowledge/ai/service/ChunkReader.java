package com.knowledge.ai.service;

import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 读分片块（`T-508`）—— 从 `sys_chunk` 取出待向量化的块。
 *
 * <h3>为什么从库里读、而不是直接把分片结果传下去</h3>
 * <p>两条路都走得通，但**从库里读**才是 `T-508` 真正需要的能力：
 * <ul>
 *   <li>向量化的输入是「**已落库的块**」—— 主键 id 就是 Milvus 主键，必须一致；</li>
 *   <li>`T-406` 的重建索引将来是**独立入口**（不重解析、只重向量化），
 *       那时手上只有 docId，没有内存里的 {@code List<Block>}；</li>
 *   <li>顺带把"落库的块长什么样"变成可断言的（幂等判据要拿它跟向量条数对账）。</li>
 * </ul>
 * 代价是多一次查询，可以忽略。
 *
 * <h3>密级取自知识库</h3>
 * <p>{@code sys_chunk} 没有密级列 —— 密级是**知识库级**的属性（{@code sys_kb.secret_level}）。
 * 这里 join 出来写进 Milvus 的标量字段，供检索侧按用户密级上限过滤（`I-9` 的用法）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkReader {

    private final EntityManager entityManager;

    /**
     * 读一篇文档的全部块（按文档顺序）。
     *
     * <p>**不在 SQL 里过滤"哪些块进检索池"** —— 那个口径属于 {@link VectorChunk#vectorText()}，
     * 在 SQL 里再写一遍 {block_type != 'TABLE' or parent_id is not null} 会让同一条规则
     * 散落两处、将来只改一处。这里照实取回，挑的活由向量化层做。
     */
    @Transactional(readOnly = true)
    public List<VectorChunk> readByDocId(long docId) {
        List<?> rows = entityManager.createNativeQuery("""
                        select c.id, c.chunk_type, c.raw_content, c.title_path, c.parent_id,
                               k.secret_level, c.is_boilerplate, c.is_decorative
                          from sys_chunk c
                          join sys_document d on d.id = c.doc_id
                          join sys_kb k on k.id = d.kb_id
                         where c.doc_id = :docId
                         order by c.order_no
                        """)
                .setParameter("docId", docId)
                .getResultList();

        List<VectorChunk> chunks = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] c = (Object[]) row;
            chunks.add(new VectorChunk(
                    ((Number) c[0]).longValue(),
                    (String) c[1],
                    (String) c[2],
                    (String) c[3],
                    c[5] == null ? null : String.valueOf(c[5]),
                    c[4] == null ? null : ((Number) c[4]).longValue(),
                    // `O9` 步 4：样板块**读得到、但不进召回池**。判据在 VectorChunk#inRecallPool，
                    // 不在这里 —— 读取层的职责是"照实读出来"，不是"顺手筛掉"
                    isTrue(c[6]),
                    // `T-O03`：装饰图同理 —— 读取层照实读，筛的活在向量化层
                    isTrue(c[7])));
        }
        log.debug("读取待向量化分片：docId={} 块数={}", docId, chunks.size());
        return chunks;
    }

    /**
     * MySQL 的 {@code TINYINT(1)} 经原生查询回来是 {@code Integer}（0/1），
     * 但驱动版本不同时可能是 {@code Boolean} 或 {@code Long} —— 三种都收。
     *
     * <p>直接强转 {@code (Boolean)} 会在某次驱动升级后变成 {@code ClassCastException}，
     * 而那个异常发生在**向量化路径**上，现象是"解析失败"，与"布尔列读法"八竿子打不着。
     */
    private static boolean isTrue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof Number n && n.intValue() != 0;
    }
}
