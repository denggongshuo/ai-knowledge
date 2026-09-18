-- ===========================================================================
-- S6 · 阶段 A：`sys_model_config` 字段集对齐（`T-900`~`T-903` 的地基）
--
-- 为什么必须改表
-- ---------------------------------------------------------------------------
-- `V1` 建这张表时模型配置页还没定稿（它是 S0 的骨架）。到 `S6-A` 开工时，
-- 契约有两处已经冻结，而**这张表两边都对不上**：
--
--   ① **前端** `ai_knowledge_app/src/types/index.ts:326-370` 的 `ModelConfigItem`
--      —— 按「切真实接口前端零改动」的规矩，它是**形状的权威**；
--   ② **需求** `12-模型能力升级-变更清单.md` §4 的目标 schema。
--
-- 两者一致地要求：`protocol` / `params` / `capabilities` / `sort_order` /
-- `last_test_at` / `last_test_ok` / `last_latency_ms` / **三列计费**
-- （`input_price` / `output_price` / `per_call_price` + `currency`）—— 而 `V1` 里
-- **一个都没有**，只有 `temperature` / `max_tokens` / `billing_price` 这几个"半成品"。
--
-- ⚠️ `plan/汇总-归档.md` §C 的摸底表把该表记作「字段齐全」—— 那是 2026-09-15 的判断，
--    距今三批，**已过时**。本批按实测对齐，并回填订正那份摸底（见计划 §附）。
--
-- ---------------------------------------------------------------------------
-- 可逆性：**完全可逆**，因为表实测 0 行
-- ---------------------------------------------------------------------------
-- `select count(*) from sys_model_config` = 0（S6-A 开工前实测）。所以这里敢用
-- `DROP COLUMN` —— 有数据之后这类改动就必须走"加新列 + 回填 + 删旧列"三步了。
-- ⚠️ **本迁移之后**，任何进一步的删列都要重新评估这一点。
--
-- ---------------------------------------------------------------------------
-- 🔴 唯一索引不能直接建在 `(model_type, is_active)` 上
-- ---------------------------------------------------------------------------
-- 需求 §1.6 要「同类型至多一条启用」，用唯一索引保证（应用层并发下会出双启用）。
-- 但**直接 `UNIQUE(model_type, is_active)` 是错的**：同一类型下**多条未启用**的行
-- 都是 `is_active = 0`，它们的 `(model_type, 0)` 完全相同 ⇒ 第二条就插不进去 ——
-- 而"同一类型配 3 个对话模型、启用其中 1 个"恰恰是需求要支持的正常用法。
--
-- 正解是**生成列**：只在"启用"时才有值，未启用时为 `NULL`；
-- 而 MySQL 的唯一索引**不限制多个 NULL**。于是约束精确地变成
-- 「每个 model_type 至多一条启用」，其余随便多条。
-- ⚠️ 用 `STORED` 而不是 `VIRTUAL`：唯一索引在两者上都可用，但 STORED 的
-- 求值时机更直白（写入时算一次），排查"为什么插不进去"时 `select active_key` 看得见值。
--
-- ---------------------------------------------------------------------------
-- `api_key_cipher` 本批**恒为 NULL**（用户 2026-09-18 裁定）
-- ---------------------------------------------------------------------------
-- 它是「加密后的 API Key」，而仓里**没有任何通用加解密工具**（`javax.crypto`
-- 只出现在 `JwtService` 签 JWT）。本批不引加密库、也不自己写 —— 那样要连带解决
-- 密钥轮换与历史密文兼容，而收益是负的（密钥仍放在 `.env` 里）。
-- 所以**保持 `V1` 的列注释所定义的语义**：`NULL` = 用环境变量同名配置。
-- 页面上的 Key 改为只读掩码，文案指向运维改 `.env`。
-- ⇒ 因此 `api_key_mask` 这一列**不加**：掩码由读取时从 `.env` 的 key 现算，
--    落一个必然与真实 key 脱节的快照没有意义（换了 key 而掩码没变 = 撒谎）。
--
-- ---------------------------------------------------------------------------
-- 种子**不在本迁移里做**
-- ---------------------------------------------------------------------------
-- 四类模型的引导值在 `application.yml` 的 `app.model.*`（← `.env` 的 `CHAT_API_KEY` 等），
-- 而 **Flyway 的 SQL 读不到环境变量**。所以种子由 `ModelConfigSeeder` 在**启动期幂等**落 ——
-- 它与本迁移必须**同批合入**：只加种子不加 DB 来源是死数据，只加来源不加种子会把系统打挂
-- （表空 ⇒ `activeSpec` 抛 `MODEL_NOT_CONFIGURED` ⇒ 问答 / 检索 / 解析全线不可用）。
-- ===========================================================================

-- ---------- ① 改名（语义对齐前端契约）----------
-- `is_default` → `is_active`：**注意语义变了**。V1 的 `is_default` 是"该类型的默认模型"，
-- 允许多条共存（`idx_model_type_default` 不是唯一索引）；而 `is_active` 是"当前启用的那一条"，
-- 同类型**至多一条**。表 0 行，故没有数据要迁。旧索引随列改名一并处理（见 ② 之后）。
ALTER TABLE sys_model_config
    RENAME COLUMN name TO display_name,
    RENAME COLUMN is_default TO is_active,
    RENAME COLUMN remark TO note;

-- ---------- ② 删掉被替代的列 ----------
-- `provider`     —— 前端 `ModelConfigItem` **没有这个字段**，需求 §1.5 的字段表里也没有
--                   （供应商对使用方不可见，路由信息全在 base_url 里）。
-- `enabled`      —— 与 `is_active` 重复。两个布尔量表达同一件事时，早晚会不一致。
-- `temperature` / `max_tokens` —— 并入 `params` JSON（前端契约是 `params` 对象）。
-- `billing_price`—— 拆成 `input_price` / `output_price` / `per_call_price` 三列 + `currency`
--                   （需求 §1.5：按 token 计费要分输入/输出单价；按次是另一个量纲）。
-- ⚠️ `idx_model_type_default` 建在 `is_default` 上，改名后自动跟随；它表达的是
--    "默认模型"这个已经不存在的语义，故一并删掉（唯一性改由 `active_key` 承担）。
ALTER TABLE sys_model_config
    DROP COLUMN provider,
    DROP COLUMN enabled,
    DROP COLUMN temperature,
    DROP COLUMN max_tokens,
    DROP COLUMN billing_price;

ALTER TABLE sys_model_config
    DROP INDEX idx_model_type_default;

-- ---------- ③ 新增列 ----------
-- 全部**可空**（除 `protocol` / `currency` / `sort_order` / `last_test_ok` 给了默认值）——
-- 理由：这些字段都是"配了才有意义"。强制 NOT NULL 就得给它们编默认值，
-- 而"没配单价"与"单价是 0"语义不同（`G6-6` 要的三态），编不得。
--
-- 🔴 **NULL 分支**（硬口径要求写明）：
--   · `api_key_cipher` 恒 NULL      → 取 `.env` 同名配置（V1 列注释已定义的语义）
--   · `input_price` / `output_price` / `per_call_price` 为 NULL → **未配置单价（费用未知）**；
--     填 `0` 或 `billing_unit = 'NONE'` → **已知不计费**。两者**语义不同**，UI 必须区分
--   · `params` / `capabilities` 为 NULL → 读取侧给**契约里的默认值**
--     （`temperature=0.3` / `maxTokens=2048` / `timeoutSec=60` / `retries=2`），
--     而不是给空对象 —— 前端拿空对象会渲染成 `undefined`
--   · `dimension` / `norm` / `batch_size` / `metric` 为 NULL → 非 EMBEDDING 的常态
--   · `last_test_at` 为 NULL       → **从未测试过**（与 `last_test_ok = 0` 是两件事：
--     前者是"没测过"，后者是"测了但没通过"）
--   · `activated_at` 为 NULL       → 从未启用过（`W11` 的 `vlModel.appliedAt` 用它。
--     刻意**不复用 `updated_at`**：改个备注也会让它变，"生效时间"就是假的）
ALTER TABLE sys_model_config
    ADD COLUMN protocol        VARCHAR(32)   NOT NULL DEFAULT 'OpenAI 兼容' COMMENT '接口协议；决定请求体格式',
    ADD COLUMN dimension       INT           NULL COMMENT '向量维度（仅 EMBEDDING，只读展示 1024）',
    ADD COLUMN norm            VARCHAR(16)   NULL COMMENT '归一化方式（仅 EMBEDDING）',
    ADD COLUMN batch_size      INT           NULL COMMENT '批量大小（仅 EMBEDDING）',
    ADD COLUMN metric          VARCHAR(16)   NULL COMMENT '相似度度量（仅 EMBEDDING）：COSINE / DOT / EUCLIDEAN',
    ADD COLUMN params          TEXT          NULL COMMENT '生成参数 {temperature,maxTokens,timeoutSec,retries} JSON 串；NULL 时读取侧给默认值',
    ADD COLUMN capabilities    TEXT          NULL COMMENT '能力标记 {stream,vision,contextWindow,supportSystemRole} JSON 串；问答页切模型时做上下文校验与差异提示',
    ADD COLUMN input_price     DECIMAL(12,6) NULL COMMENT '输入单价（元/百万 tokens）；NULL = 未配置（费用未知），与 0/NONE 语义不同',
    ADD COLUMN output_price    DECIMAL(12,6) NULL COMMENT '输出单价（元/百万 tokens）；同上',
    ADD COLUMN per_call_price  DECIMAL(12,6) NULL COMMENT '按次单价（元/次）；同上',
    ADD COLUMN currency        VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
    ADD COLUMN sort_order      INT           NOT NULL DEFAULT 0 COMMENT '列表内排序（越小越前）',
    ADD COLUMN last_test_at    DATETIME      NULL COMMENT '最近一次连接测试时间；NULL = 从未测试过（与 last_test_ok=0 是两件事）',
    ADD COLUMN last_test_ok    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '最近一次连接测试是否通过；启用门槛读它（G6-3）',
    ADD COLUMN last_latency_ms INT           NULL COMMENT '最近一次测试的首字延迟（毫秒）',
    ADD COLUMN activated_at    DATETIME      NULL COMMENT '最近一次被启用/切换的时间（W11 的 vlModel.appliedAt）。⚠️ 不复用 updated_at',
    ADD COLUMN created_by      BIGINT        NULL COMMENT '创建人（sys_user.id）';

-- `billing_unit` 的取值域在 V1 是自由 VARCHAR，前端契约是 `PER_TOKEN` / `PER_CALL` / `NONE`。
-- 表 0 行，不需要数据订正；取值域由实体层的枚举收口（拼错的值写不进库）。
--
-- ⚠️ `params` / `capabilities` 用 **`TEXT` 而不是 MySQL 的 `JSON` 类型** —— 跟 `V9` 的
--    三张 `*_policy_json` 成例（那边刻意选了 TEXT，理由写在 `SysKb` 的类注释里）：
--    实体侧把 JSON 列映射成 `String`、序列化归 Service；若声明成 `JSON` 类型，
--    `ddl-auto: validate` 会拿它跟 String 字段比对而**启动直接失败**。

-- ---------- ④ 唯一索引（生成列，理由见文件头）----------
ALTER TABLE sys_model_config
    ADD COLUMN active_key VARCHAR(16)
        GENERATED ALWAYS AS (IF(is_active = 1, model_type, NULL)) STORED
        COMMENT '仅用于唯一索引：启用时 = model_type，未启用时 = NULL（唯一索引不限制多个 NULL）';

ALTER TABLE sys_model_config
    ADD UNIQUE KEY uk_model_active (active_key);
