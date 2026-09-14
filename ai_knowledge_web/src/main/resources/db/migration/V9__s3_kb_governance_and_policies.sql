-- ---------------------------------------------------------------------------
-- S3 · 阶段 B1：sys_kb 补「治理标签 + 三类策略」列（T-400 / T-401 / T-403 的数据前提）
--
-- 为什么这 7 列在 B1 就要有：
--   V7 建的是**骨架**（身份 / 部门 / 计数 / 状态），V8 补了创建人。而 B1 的四个接口
--   全部围绕"库的可配置全貌"：
--     · T-400 列表      —— 只要名字与计数，用不到本迁移；
--     · T-401 创建      —— 请求体是完整的 KbConfigForm，密级 / 业务域 / 描述 /
--                          自定义标签 + 三类策略**全部要落库**，否则"创建成功"是假的；
--     · T-403 配置读取  —— 要**逐字回显**提交过的值（验收判据 3：config 与提交值深等）。
--   所以这 7 列不是"顺手补全"，而是 T-401/T-403 成立的必要条件。
--
-- 两类列的存储形态**刻意不同**（见 B1 计划 §5.1 的选型）：
--   · **治理标签走真列**：secret_level 是检索期的过滤维度（《11》§2.1 I-9，
--     对应 MilvusConstants.FIELD_SECRET_LEVEL），business_domain 是治理语义列 ——
--     两者将来都可能要按列做 SQL 筛选，塞进 JSON 就筛不动了；
--   · **三类策略走 JSON 列**：它们是**整体读写单元**（页面上一个 Tab 一次提交，
--     从没有"单独改 rowWindow"的接口），JSON 让后端结构与前端 KbConfigForm
--     逐字同构，省掉手工映射这一层会各自演化的漂移源。
--     代价是无法按策略字段 SQL 筛选 —— B1 无此需求，写在这里以免后人以为是疏忽。
--
-- 不加索引：B1 没有任何按策略字段 / 业务域筛选的查询。加了不用只会拖慢写入。
--
-- ⚠️ 跑过的迁移**只增不改**：V1~V8 一行不动，本次改动全部落在这里。
-- ---------------------------------------------------------------------------

ALTER TABLE sys_kb
    ADD COLUMN description           VARCHAR(200) NULL COMMENT '库描述；对齐前端 KbConfigForm#description，最多 200 字',
    ADD COLUMN secret_level          VARCHAR(16) NOT NULL DEFAULT 'INTERNAL'
        COMMENT '密级：PUBLIC / INTERNAL / CONFIDENTIAL / SECRET（《01》§3）。检索期过滤维度，故用真列不用 JSON',
    ADD COLUMN business_domain       VARCHAR(64) NOT NULL DEFAULT ''
        COMMENT '业务域（治理语义列，取值见前端 BUSINESS_DOMAINS）。真列而非 JSON，为将来的按域筛选留路',
    ADD COLUMN custom_tags           TEXT NULL COMMENT '自定义标签，JSON 数组：["2026 年度","技术白皮书"]；读侧 NULL 归为 []',
    ADD COLUMN split_policy_json     TEXT NULL COMMENT '分片策略 JSON（preset/chunkSize/overlap/recursiveSplit/semanticSplit）；NULL 读侧用默认值兜底',
    ADD COLUMN table_policy_json     TEXT NULL COMMENT '表格处理策略 JSON（六项）；NULL 读侧用默认值兜底',
    ADD COLUMN retrieval_config_json TEXT NULL COMMENT '检索配置 JSON（mode/recallThreshold/…）；NULL 读侧用默认值兜底';

-- ---------------------------------------------------------------------------
-- 8 个演示库**就地回填、不新增行**（保持 8 库 / 34 授权的种子口径）。
--
-- 为什么 business_domain 按 dept_id 映射而不是统一填一个值：
--   业务域是治理维度，demo 数据的意义在于"看起来像真的"——8 个库分属 5 个部门，
--   全填同一个域会让治理页的按域筛选退化成单值。映射取《01》§1 部门与业务域的对应关系。
--   刻意不逐库 UPDATE（8 条语句），一条 CASE 更好读也更好核对。
--
-- secret_level 统一 INTERNAL：演示数据里没有"公开库"，而 SECRET 会因
--   密级上限（LoginUser#secretLevelUpper）把大部分账号挡在库外，拿它当默认值是给自己埋雷。
--   V1 的 ALTER 已给 DEFAULT 'INTERNAL'，这里显式写一遍是为了让"回填了什么"在脚本里可读。
-- ---------------------------------------------------------------------------

UPDATE sys_kb
SET business_domain = CASE dept_id
                          WHEN 1 THEN '研发 / 平台'
                          WHEN 2 THEN '销售 / 售前'
                          WHEN 3 THEN '人力资源'
                          WHEN 4 THEN '运维 / 安全'
                          WHEN 5 THEN '培训 / 赋能'
                          ELSE ''
    END,
    secret_level    = 'INTERNAL'
WHERE business_domain = '';

-- ---------------------------------------------------------------------------
-- 三策略列按 KbConfigDefaults 的默认值统一回填。
--
-- **JSON 文本必须与后端 Jackson 序列化同形**（字段顺序 = Java 侧声明顺序），
-- 这样"回填值可由 KbConfigDefaults 重算"才成立 —— 否则库里存的与新建库写进去的
-- 虽然语义相同、diff 却对不上，将来按文本比对会被误判成脏数据。
-- 数值口径源：ai_knowledge_app/src/mock/data.ts:380（SPLIT_PRESETS）与 :389（defaultKbConfig），
-- 与 core/enums/KbPresets / KbConfigDefaults 三方一致（用例逐条比对，见 KbModuleFlowTest）。
--
-- 只用一条 UPDATE 覆盖 8 行：三策略列此刻全是 NULL（刚加的列），无需 where 收窄。
-- ---------------------------------------------------------------------------

UPDATE sys_kb
SET split_policy_json     = '{"preset":"GENERAL","chunkSize":512,"overlap":64,"recursiveSplit":true,"semanticSplit":false}',
    table_policy_json     = '{"atomicTable":true,"dualRepresentation":true,"repeatHeader":true,"mergeCrossPage":true,"ocrTable":true,"rowLimit":30,"rowWindow":5}',
    retrieval_config_json = '{"mode":"HYBRID","recallThreshold":0.15,"rerankTopK":5,"rerankEnabled":true,"tableWeight":1.2,"identifierProtect":true}';

-- ---------------------------------------------------------------------------
-- kb_prod_doc 的自定义标签（对齐 mock data.ts:486 —— `kb.id === 1 ? ['2026 年度','技术白皮书'] : []`）。
-- 之所以只给这一个库写：前端 mock 也只给了 kb1，其余库为空数组。
-- 空数组**写 NULL 不写 '[]'** —— 与 A 阶段冻结的 changes_json 同一口径：
-- NULL = "没有"，[] = "采集过，结果为空"，两者在本场景语义不同。
-- ---------------------------------------------------------------------------

UPDATE sys_kb
SET custom_tags = '["2026 年度","技术白皮书"]'
WHERE code = 'kb_prod_doc';
