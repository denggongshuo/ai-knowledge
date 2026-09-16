-- ============================================================================
-- V12 · S4 文档域骨架表（T-500 / T-501 / T-509 / T-510）
--
-- **这是文档的「身份 + 状态 + 解析质量」表，不是分片的表。**
-- `sys_chunk` 随 T-502~T-505 在 S4-B 落 V13 —— 分片的列要由
-- 「表格双表示 / 父子块」的真实产出定型，现在建等于先猜一遍
-- （同 V1 表头的设计意图原话：「业务表随各自模块的 Sprint 再建」）。
--
-- 字段口径的事实源（三处对齐，缺一不可）：
--   ① 前端 ai_knowledge_app/src/types/index.ts 的 KbDocRow / DocInfo
--   ② docs/需求文档/10-接口约定汇总.md §14 的 GET /kb/{kbId}/docs 行
--   ③ docs/需求文档/06-需求-知识库与文档.md §3.5 文档列表字段
--
-- ⚠️ 本表**不 seed 任何行**。理由：sys_kb.doc_count 已 seed 成示例值
--    （128/42/316… 合计 1246），再 seed 12 行文档会造出"两套口径都不完整"
--    的局面，反而让"到底有几篇"变成猜谜。上传页初始显示「共 0 篇」是真值。
--    该口径分叉（sys_kb.doc_count 与本表不同源）已登记为遗留，见
--    plan/2026-09-16-S4-M05-A.md §1 W3 与 §9 第 3 条。
-- ============================================================================
CREATE TABLE sys_document
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    kb_id         BIGINT       NOT NULL COMMENT '所属知识库（不变量 I-2：文档必属恰好一个库）',
    owner_id      BIGINT       NOT NULL COMMENT '上传人（I-8 去重键的左半边）',
    title         VARCHAR(256) NOT NULL COMMENT '文档标题，**不含扩展名**（与前端 KbDocRow.title 一致）',
    file_name     VARCHAR(256) NOT NULL COMMENT '原始文件名（含扩展名）：下载与打包下载要用',
    type          VARCHAR(16)  NOT NULL COMMENT 'PDF/DOC/DOCX/XLS/XLSX/PPT/PPTX/TXT/MD（《06》§3.1 的 9 种）',
    size_bytes    BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数；也是 storage-quota.used 改成 sum 的前提',
    md5           CHAR(32)     NOT NULL COMMENT '内容指纹，32 位小写十六进制（I-8 去重键的右半边）',
    storage_path  VARCHAR(512) NULL COMMENT 'MinIO 对象名，**不存 URL**（存事实不存视图，同 sys_user.avatar）',

    -- ---------- 状态机（《03》§4.2）----------
    -- UPLOADED ──> PARSING ──> INDEXING ──> READY
    --                  │            │
    --                  └────────────┴────> FAILED（可重试）
    -- 与前端 DocInfo['status'] 逐字一致；READY 是**唯一可被检索**的状态。
    -- S4-A 只写 UPLOADED（无解析管线），PARSING/INDEXING/READY 在 S4-B 起可达。
    status        VARCHAR(16)  NOT NULL DEFAULT 'UPLOADED',
    error_msg     VARCHAR(512) NULL COMMENT '失败的具体原因（G4-3：不许只说"失败"两字）',

    -- ---------- 解析质量（独立于 status，这是《06》§3.6 的核心设计）----------
    -- 表格识别失败是**静默故障**：文档状态仍是「已就绪」，表格内容却没进索引。
    -- 所以质量必须独立成列 —— 两行的状态列一模一样，质量列才看得出差别。
    chunk_count   INT          NOT NULL DEFAULT 0 COMMENT '分片数（只计顶层块，见 BlockType#countsInChunkTotal）',
    table_total   INT          NOT NULL DEFAULT 0 COMMENT '表格总数',
    table_parsed  INT          NOT NULL DEFAULT 0 COMMENT '已识别表格数',
    image_total   INT          NOT NULL DEFAULT 0 COMMENT '图片总数（V2 新增）',
    image_parsed  INT          NOT NULL DEFAULT 0 COMMENT '已理解图片数（VL 描述成功）',

    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    -- 软删位：T-611 删除文档走软删（进回收站），30 天后由 T-616 彻底删。
    -- NULL = 未删；hasBusinessData 与回收站列表都按它过滤。
    deleted_at    DATETIME     NULL,

    PRIMARY KEY (id),
    KEY idx_doc_kb (kb_id),
    KEY idx_doc_owner (owner_id),

    -- ⚠️ **刻意不是唯一索引**，两张需求在这里互相张力，取舍如下：
    --   I-8 ：「同一知识库内**不允许**文档 MD5 重复」
    --   《06》§3.2：「命中 (owner_id, md5) → 提示『文件已存在，是否覆盖』
    --                → 覆盖即产生新版本」
    -- 若加 UNIQUE，「覆盖 = 归档为新版本」产生的 v2 与 v1 共享同一 md5，
    -- 约束立刻被违反 —— 二者不能同时成立。
    -- 本批取《06》§3.2 的行为语义（去重是**提示**，不是拦），
    -- 因此这里只建**普通索引**（供 check-md5 快查），把唯一性交给应用层判断。
    -- 两处需求各自点名了去重键的一半，本实现取**两者的合取** = (kb_id, owner_id, md5)。
    -- 三个都必要：
    --   · 带 kb_id —— 同一份文件传到另一个库是**正常用法**，不该被判重复
    --   · 带 owner_id —— 判据 4 钉死了"换个人传同 md5 不命中"
    --   · 带 md5 —— 指纹本体
    KEY idx_doc_md5 (kb_id, owner_id, md5)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='文档（S4 文档域骨架，分片表见 V13）';
