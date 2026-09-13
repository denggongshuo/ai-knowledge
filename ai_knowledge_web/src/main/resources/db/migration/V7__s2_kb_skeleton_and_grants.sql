-- ---------------------------------------------------------------------------
-- S2：知识库骨架表 + 授权种子（T-211 的数据前提）
--
-- **这是骨架，不是 S3 的完整 schema**：S2 只需要"库的身份 + 归属部门 + 计数"
-- 才能让 /kb/grants 三接口与求交引擎（T-212）真跑起来。密级/可见性/检索配置/
-- 生命周期等列由 S3 的迁移演进（T-400 起会按《06》补全）。
-- 8 个库与计数逐条来自《01-示例数据字典》§3；6~8 号字典只给了合计
-- （650 篇 / 13,192 分片），名称与拆分为演示编造。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_kb
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(64) NOT NULL COMMENT '库编码，如 kb_prod_doc',
    name       VARCHAR(128) NOT NULL,
    dept_id    BIGINT      NOT NULL COMMENT '归属部门（数据范围 ② 的计算基准）',
    doc_count  INT         NOT NULL DEFAULT 0,
    chunk_count INT        NOT NULL DEFAULT 0,
    status     VARCHAR(16) NOT NULL DEFAULT 'READY' COMMENT 'READY / INDEXING（S3 扩展）',
    created_at DATETIME    NOT NULL,
    updated_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_code (code),
    KEY idx_kb_dept (dept_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='知识库（S2 骨架，S3 演进）';

INSERT INTO sys_kb (code, name, dept_id, doc_count, chunk_count, status, created_at, updated_at)
VALUES ('kb_prod_doc',       '产品技术文档库', 1, 128,  8240,  'READY',    NOW(), NOW()),
       ('kb_hr_policy',      '人力资源制度库', 3, 42,   1180,  'READY',    NOW(), NOW()),
       ('kb_sales_contract', '客户合同库',     2, 316,  12640, 'INDEXING', NOW(), NOW()),
       ('kb_ops_incident',   '运维故障知识库', 4, 87,   2610,  'READY',    NOW(), NOW()),
       ('kb_training_ext',   '外部培训资料库', 5, 23,   640,   'READY',    NOW(), NOW()),
       ('kb_sales_talk',     '销售话术库',     2, 220,  4400,  'READY',    NOW(), NOW()),
       ('kb_aftersale',      '售后服务库',     4, 230,  4542,  'READY',    NOW(), NOW()),
       ('kb_hr_general',     '综合管理制度库', 3, 200,  4250,  'READY',    NOW(), NOW());

-- ---------------------------------------------------------------------------
-- 授权种子：以《01》§2.5 的口径为准（mock 只给 3/4 库授了 AUDITOR，与
-- 「审计员=全部知识库只读」冲突，这里按字典）：
--   SUPER_ADMIN → 全部库 MANAGE（全权）
--   KB_ADMIN    → 全部库 UPLOAD（可上传；可见范围再由②数据范围收窄到本部门及以下）
--   AUDITOR     → 全部库 READ（只读，合规核查）
--   USER        → 全部库 READ（仅已授权知识库 + 只读，不可上传）
-- 另给 kb1 两个额外授权用户（对应 mock 详情里的李娜/王芳演示）。
-- ---------------------------------------------------------------------------
INSERT INTO sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
SELECT k.id, 'ROLE', r.id,
       CASE r.code WHEN 'SUPER_ADMIN' THEN 'MANAGE'
                   WHEN 'KB_ADMIN'    THEN 'UPLOAD'
                   ELSE 'READ' END,
       NOW(), NOW()
FROM sys_kb k CROSS JOIN sys_role r
WHERE r.code IN ('SUPER_ADMIN', 'KB_ADMIN', 'AUDITOR', 'USER');

INSERT INTO sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
SELECT k.id, 'USER', u.id, 'UPLOAD', NOW(), NOW()
FROM sys_kb k JOIN sys_user u ON u.username IN ('li.na', 'wang.fang')
WHERE k.code = 'kb_prod_doc';
