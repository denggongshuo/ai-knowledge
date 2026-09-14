-- ---------------------------------------------------------------------------
-- S3 开工前收尾：sys_kb 补「创建人」列（owner_id）
--
-- 为什么这一列在 S3 开工前就要有：
--   ① **G2-6「有业务数据禁删」**需要真实数据源 —— 《05》§1.5 定义「有业务数据」
--      = 创建过知识库或上传过文档。文档表（sys_document）要到 S4 才建，
--      而知识库创建人的判定点现在就能落地；
--   ② M04 列表（T-400）与创建向导（T-401）本就要落创建人，先于接口把列备好，
--      避免 T-401 再开一次迁移改这张刚演进的表。
--
-- 语义与字段口径：
--   - **可空**：S2 的 8 个演示库是 seed 出来的，本迁移按用户名回填；
--     匹配不到就留 NULL（NULL 只表示"演示数据未关联到账号"，不参与任何业务判定）。
--   - **不设外键**：与 sys_login_log 同理 —— 账号被彻底删除后知识库必须保留，
--     否则删除账号会级联失败。归属校验走应用层（UserAdminService#hasBusinessData）。
--   - 上传文档的去重键 `(owner_id, md5)`（《01》§6 / I-8）是**文档级** owner，
--     落在 S4 的 sys_document.owner_id；本列是**知识库级**创建人，两者不同层。
--
-- 回填为什么按 **username** 而不是 real_name：
--   V6 的姓名来自姓名池组合，**重名**（`王芳` 同时是 wang.fang 与 emp002），
--   按姓名匹配要额外处理并列；而按中文名匹配还受客户端字符集影响。username 是
--   唯一且 ASCII 的，不会有这两类问题。
-- 创建人为什么按「库归属部门 → 该部门内的账号」挑：
--   数据范围 ② 以「库的归属部门」为基准，创建人落在同一部门内最自然。
--   两个例外都写在这里，免得后来者当成脏数据：
--     · `kb_prod_doc`（dept 1 · 研发中心**直属**）没有直属账号，用其子组
--       （dept 11 · 平台架构组）的 lin.gong —— 同一中心内，部门树上是父子关系；
--     · `kb_training_ext`（dept 5 · 培训中心）确实无对应演示账号，**刻意留 NULL**
--       作为"演示数据未关联到账号"的样本，恰好覆盖 owner_id 可空这条路径。
-- ---------------------------------------------------------------------------
ALTER TABLE sys_kb
    ADD COLUMN owner_id BIGINT NULL COMMENT '创建人用户 id；演示数据可能为 NULL，无外键（账号删除后库须保留）',
    ADD KEY idx_kb_owner (owner_id);

UPDATE sys_kb k
JOIN sys_user u ON u.username = 'lin.gong' AND u.deleted_at IS NULL
SET k.owner_id = u.id
WHERE k.code = 'kb_prod_doc';

UPDATE sys_kb k
JOIN sys_user u ON u.username = 'wang.fang' AND u.deleted_at IS NULL
SET k.owner_id = u.id
WHERE k.code IN ('kb_hr_policy', 'kb_hr_general');

UPDATE sys_kb k
JOIN sys_user u ON u.username = 'li.na' AND u.deleted_at IS NULL
SET k.owner_id = u.id
WHERE k.code IN ('kb_sales_contract', 'kb_sales_talk');

UPDATE sys_kb k
JOIN sys_user u ON u.username = 'liu.qiang' AND u.deleted_at IS NULL
SET k.owner_id = u.id
WHERE k.code IN ('kb_ops_incident', 'kb_aftersale');
