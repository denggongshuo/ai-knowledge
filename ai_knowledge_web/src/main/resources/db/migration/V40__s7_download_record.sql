-- ===========================================================================
-- V40 · S7 · M06 阶段 E —— 下载留痕（门禁 `G7-9`）
--
-- 新建**一张表** `sys_download_record`。没有改表。
-- 由来：需求 `06` §6.6 + 门禁 `G7-9`「每次下载写审计 + 下载记录」——
--   审计走既有 `sys_audit_log`（`@AuditLog`），**下载记录**是**另一份**、给"下载历史"页
--   （`GET /doc/download-records`）用的。两者不是一回事：审计是合规留痕（谁在何时做了什么、
--   含高危分级），下载记录是**面向用户的历史清单**（我下过哪些文件、多大、什么时候）。
--
-- 🔴 **为什么不复用 `sys_audit_log`**：审计表的 `changes_json` 是"变更对照"结构，
--   而下载记录要的是 `docId/size/type` 这类**结构化列**（前端 `DownloadRecord` 契约
--   `types/index.ts:952` 逐字要 id/docId/docTitle/kbName/size/operator/at/ip/type）。
--   把它塞进审计的 JSON 里，"下载历史分页 + 按用户查"就退化成 JSON 解析，得不偿失。
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- sys_download_record —— 下载历史
-- ---------------------------------------------------------------------------
-- · doc_id / kb_id / user_id —— 谁下了哪个库的哪篇。**都非空**：下载记录必然锚定到
--   一篇真实文档与一个真实用户（不像审计有"系统动作/匿名"那一档）。
-- · download_type —— 'SINGLE'（单篇 `GET /download-url`）/ 'BATCH'（打包 `batch-download`
--   里被打进 zip 的每一篇各记一条）。前端 `DownloadRecord.type` 逐字是这两个值。
-- · size_bytes —— 该文档字节数（打包时是单篇大小，不是整个 zip）。DEFAULT 0：
--   取不到大小（对象已被清）时记 0 而不是让整条记录写不进去。
-- · ip —— 可空：取不到远端地址（如内部调用）时写 NULL，记录页显示「—」。
--
-- 🔴 **展示列 docTitle / kbName 不落这张表**：查询时 join `sys_document` / `sys_kb` 取。
--   理由 = `DocStorage` 类注释同一条纪律「存事实，不存视图」：文档改名 / 库改名后，
--   落库的冗余名就成了过期快照。代价是 join，而 join 的键都有索引。
--   ⚠️ 文档**软删后**记录仍在（下载历史应保留），此时 left join 取不到标题 ⇒
--   查询侧用占位「已删除」补（见 `DownloadService` 的记录查询）。
--
-- ⚠️ collation 显式 `utf8mb4_0900_ai_ci`：与 `V33` 收敛后的库默认一致。
--   `download_type` 是本表唯一的字符串列，将来若和别表的字符串列比较（不太可能），
--   两套 collation 会报 `1267`（`S6-M11`/`S8-M10-A` 踩过）—— 出生就对齐，零成本。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_download_record (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    doc_id        BIGINT       NOT NULL COMMENT '被下载的文档 id',
    kb_id         BIGINT       NOT NULL COMMENT '文档所属知识库 id（冗余，便于按库统计与 join 取 kbName）',
    user_id       BIGINT       NOT NULL COMMENT '下载人 id',
    download_type VARCHAR(16)  NOT NULL COMMENT '下载方式：SINGLE=单篇 / BATCH=打包下载中的一篇',
    size_bytes    BIGINT       NOT NULL DEFAULT 0 COMMENT '该文档字节数（取不到记 0；打包时是单篇大小不是 zip 大小）',
    ip            VARCHAR(64)  NULL COMMENT '下载来源 IP（取不到写 NULL，记录页显示「—」）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下载时刻',
    PRIMARY KEY (id),
    KEY idx_dl_user (user_id, created_at) COMMENT '按用户查下载历史（分页按时间倒序）',
    KEY idx_dl_doc (doc_id) COMMENT '按文档反查下载记录'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT ='下载记录（G7-9）—— 面向用户的下载历史，与合规审计 sys_audit_log 分开';
