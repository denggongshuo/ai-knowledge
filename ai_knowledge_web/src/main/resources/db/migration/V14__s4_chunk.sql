-- ============================================================================
-- V14 · S4 分片块（T-503 / T-504 / T-505）
--
-- 本批把流水线从「内容出来了」推进到「块产生出来了」：
--   解析产物（parse.json）──> 两级分片 ──> 块 ──> sys_chunk
--
-- ⚠️ 编号说明：D-5 §三 原文写「sys_chunk 落 V13」，但 S4-B 先用了 V13
--    （sys_parse_issue + sys_document.parse_path —— 解析要有落点、失败要有表）。
--    sys_chunk 顺延到 V14，D-5 §三与 §四已同步订正。**编号本身没有语义**，
--    这里再记一次是为了让后来的迁移作者不必去翻两处文档才知道 V13 去哪了。
--
-- ⚠️ 本表**一行为一个块**。视图（「喂给嵌入模型的文本」）**不进表** ——
--    它是索引期的纯函数投影（见 docs/后端开发文档/10-M05-分片产出契约（D-5）.md §1.7）。
--    把多视图拆成多行会破坏父子关系；若将来为了排查"到底是哪份文本进了向量"
--    而需要持久化视图，**另开一张表**，不要往这里塞。
-- ============================================================================

CREATE TABLE sys_chunk
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id      BIGINT       NOT NULL COMMENT '所属文档（D-5 §1.1 docId）；Milvus 标量字段 doc_id 同源',
    kb_id       BIGINT       NOT NULL COMMENT '所属知识库（D-5 §1.1 kbId）；冗余以避免每次 join sys_document',
    -- 双名口径（D-5 §1.3）：接口出参 key 是 blockType，入库列名 / Milvus 标量字段名固定为 chunk_type。
    -- ❌ 废弃的第三种写法 chunkType —— 任何地方都不要用。
    chunk_type  VARCHAR(32)  NOT NULL COMMENT '块类型，取 BlockType 的 8 值之一（D-5 §1.3）',
    -- ⚠️ 顶层块为 NULL，**禁止用 0 当哨兵**（D-5 §1.2 硬口径）：0 会与真实 id 混淆。
    --    顶层 = 正文 / 标题 / 代码 / 列表 / 表格父块 / 图片 / 摘要；只有表格子块非空。
    parent_id   BIGINT       NULL COMMENT '表格子块指向其 TABLE 父块的 id；顶层块为 NULL（禁用 0 当哨兵）',
    -- 既进 Milvus 标量 title_path（供过滤），又进索引期投影（供语义）—— 两件事互不替代（D-5 §1.7）。
    -- 可空：标题之前的块（封面段）没有路径。
    title_path  VARCHAR(512) NULL COMMENT '标题路径，如「3. 接口设计 > 3.1 鉴权流程」；标题之前的块为 NULL',
    -- ★ 本列是 O8「块视图分离」的落点：**原始内容，永不改写**。
    --    表格父块 = Markdown 管道表（给大模型读）；表格子块 = 行式文本（给向量检索）；
    --    正文块 = 原文。嵌入文本 = 本列 + 标题路径的**索引期投影**，不是写回本列。
    --    改注入策略时应只重建索引 —— 本列不变就意味着不必重解析。
    raw_content MEDIUMTEXT   NOT NULL COMMENT '块的原始内容，永不改写（O8：块存事实，视图是投影）',
    order_no    INT          NOT NULL COMMENT '文档内顺序，1-based；回表与详情页按它还原阅读顺序',
    page_no     INT          NULL COMMENT '所在页码，1-based（引用卡片显示「第 N 页」）；非分页格式（TXT/MD）为 NULL',
    -- 只有 created_at，没有 updated_at：块是**整批重写**的（重跑解析时按 doc_id 先删后插），
    -- 从不单行更新。留一个恒等于 created_at 的 updated_at 只会让人以为它可以变。
    created_at  DATETIME     NOT NULL COMMENT '写入时间；重跑解析后整批重建，故它也是「最近一次分片」的时间',

    PRIMARY KEY (id),
    -- ① 列表与回表的顺序读：查询某文档的块并按阅读顺序取（详情页、T-705 回表）
    KEY idx_chunk_doc (doc_id, order_no),
    -- ② 父子断言与回表：T-705 命中子块后按 parent_id 取父块；
    --    也是 G4-6「子块 parentId 必指向 TABLE 父块」那条 SQL 断言的支撑索引
    KEY idx_chunk_parent (parent_id),
    -- ③ 按库统计 chunk_count（sys_kb.chunk_count 与文档列表的分片列）
    KEY idx_chunk_kb (kb_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='分片块（M05 产出侧 → M07 消费侧的接缝，D-5）';
