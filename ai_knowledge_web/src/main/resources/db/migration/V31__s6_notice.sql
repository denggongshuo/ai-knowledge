-- ===========================================================================
-- S6 · M11：通知中心（`T-B00`~`T-B03`）
--
-- 需求出处 ——《09-需求-系统配置与通用.md》第 346~441 行「FR-SYS-02 · 通知中心」：
--   §2.2 四类通知（上传与解析 / 系统告警 / 权限变更 / 系统消息）
--   §2.3 「待我处理」聚合（未命中问题 + 解析失败）
--   §2.5 通知设置，其中**系统告警强制开启**
--   §2.7 已读状态**按用户维度存**（同一通知对不同人是各自的已读状态）
--   §2.9 通知落库 + 站内信；**通知生成点统一**（异步任务完成回调处集中产出）
--
-- ---------------------------------------------------------------------------
-- 为什么是三张表
-- ---------------------------------------------------------------------------
--   · sys_notice         —— 通知本身（一条通知一行）
--   · sys_notice_read    —— **已读是关系，不是属性**（需求 §2.7 明写"按用户维度存"）
--   · sys_notice_setting —— 按类型的开关（需求 §2.5），**也是按用户维度**
--
-- 已读为什么必须独立成表：需求 §2.7 的原话是「同一通知**对不同用户**是各自的已读状态」——
-- 那就意味着通知本身可以是**广播**的（一条行、多人可见），于是"已读"是这个"人 × 通知"
-- 二元关系上的属性，塞进 sys_notice 就退化成"最后一个标已读的人说了算"。
-- 取值方式：**行在 = 已读**（`read_at` 供展示/追溯），标回未读就是删行 ——
-- 需求 §2.7 说「已读可逆」，删行正好表达它，不需要第三个状态。
--
-- ---------------------------------------------------------------------------
-- 🔴 归属列**必须叫 `user_id`**（别改成 `target_user_id`）
-- ---------------------------------------------------------------------------
-- 覆盖率闸（`probe-teardown.mjs#assertArtifactCoverage` / `ProbeArtifactCoverageTest`）
-- 的扫描集是「带 `kb_id`/`doc_id`/`user_id` 的表」。把这一列命名成 `target_user_id`
-- **确实能躲开那道闸** —— 但那正是闸要防的事：闸的价值是**逼新产物过一次人的判断**，
-- 绕开它等于把它关掉。列表查询的过滤条件是 `user_id is null or user_id = ?`，
-- 叫 `user_id` 语义也顺（"这条通知属于谁"）。
-- ⇒ 两侧登记表都已登记（脚本侧 `USER_ARTIFACTS` / Java 侧 `ProbeArtifacts.REGISTERED`）。
--
-- ---------------------------------------------------------------------------
-- `user_id` 可空 = 广播；⚠️ 但本批**不产广播通知**
-- ---------------------------------------------------------------------------
-- 表结构支持广播（NULL = 所有人可见），因为需求 §2.2 的「系统消息」类天然是广播
-- （模型配置变更、版本更新）。但本批的**唯一产出方**是异步任务终态
-- （`NoticePublisher#publishForTask`），而任务**总有 `owner_id`** ⇒ 本批落地的每一行
-- 都是**定向**的。
-- ⚠️ 这一点与清理口径直接相关：两侧清理器都按 `user_id in (...)` 删，
-- **广播行（`user_id is null`）谁也删不掉**。故收尾判据里有一条
-- 「`sys_notice` 中 `user_id is null` 的行数为 0」—— 将来真开始产广播通知时，
-- 那条判据会当场转红，逼人补一条清理路径，而不是默默攒残留。
--
-- ---------------------------------------------------------------------------
-- 其余设计取舍
-- ---------------------------------------------------------------------------
--   · `actions_json` **NOT NULL**：需求 §2.10 验收要点第一条是「每条通知**都有至少一个
--     可执行动作**」。允许空串/空数组就是允许一条不可执行的"通知"，故列上直接卡住。
--     结构固定为 `[{"label":…,"to":…,"primary":…}]`，**手写序列化**不引 ObjectMapper
--     （承本仓"手写 4 字段 JSON"的先例，见 `O4` 的扫描结论）。
--   · `source_type` / `source_task_id`：从通知**反查是哪次任务产出的**。
--     ⚠️ 不加外键指向 `sys_async_task` —— 与 `V22` 同口径（该表建表时没建任何外键，
--     见 `#210`），而且任务行会被测试收尾删掉，带外键反而让清理互相卡住。
--   · **不加 `deleted_at`**：需求 §2.7 提到「历史通知需定期归档」，那是**未做**的事
--     （见计划 §3.1「明确不做」②），列先留着就是"为一条不存在的流程加字段"。
--     真做归档的那一批一起加（届时也能看清归档查询的真实形状）—— 与 `V29` 对
--     `image_md5` 索引的处置同一条口径。
--
-- ---------------------------------------------------------------------------
-- 种子数据：**不 seed**（有意为之，不是漏了）
-- ---------------------------------------------------------------------------
-- 《01-示例数据字典》§8 的 28 条（未读 5 / 上传与解析 9 / 系统告警 4 / 权限变更 3 /
-- 系统消息 12 / 待我处理 23）是**原型演示值**，不是种子契约。三条理由不 seed：
--   ① 新系统「一条通知都没有」是**正确状态**，不是空缺陷；
--   ② 已读**按用户存** ⇒ 种子里的"已读"归属哪个用户**本身没有正确答案**；
--   ③ seed 会让 `sys_notice` 的基线 ≠ 0，给下一批的"残留自检"制造噪声
--      （`#105` / `#184` 的形态：看着有数，其实是自己种的）。
-- ⇒ 页面空态走空；页面级证据由**真流程现造**（真跑一次导出/解析 → 通知真的落库），
--    那比 seed 更接近真实验证。
--
-- ✅ 可逆：`drop table sys_notice_read, sys_notice_setting, sys_notice`（无外部引用）。
--
-- ---------------------------------------------------------------------------
-- 🔴 排序规则**必须**是 `utf8mb4_unicode_ci`（不是多数表用的 `utf8mb4_0900_ai_ci`）
-- ---------------------------------------------------------------------------
-- 本仓**存在两套排序规则**（实测 2026-09-19）：
--   · 21 张表 = `utf8mb4_0900_ai_ci`（迁移里显式写了）；
--   · 7 张表 = `utf8mb4_unicode_ci` = **库默认** —— 它们都是**没写 `COLLATE`** 的那几份迁移：
--     `sys_async_task` / `sys_chat_*` / `sys_retrieval_trace*` / `flyway_schema_history`。
--
-- 两套之间**跨表比较字符串会报 1267** `Illegal mix of collations`
-- （两侧的排序规则都是 `IMPLICIT`，MySQL 拒绝隐式选择；若是"列 vs 参数"则参数会被强制同化，
-- 所以这个坑**只在建表时看不出来，跑起来才炸，且炸在跨表那句 SQL 上**）。
--
-- ⚠️ 本表**有一处跨表字符串比较**：`NoticeRepository#sweepOrphanNotices` 要问
-- 「`sys_notice.source_task_id` 指向的 `sys_async_task` 行还在不在」——
-- 而 `sys_async_task` 是 `utf8mb4_unicode_ci`。⇒ 本表跟着它，选 `utf8mb4_unicode_ci`。
-- （初期版本抄了 `V13` 的 `0900_ai_ci`，**第一条用例当场 1267**，已订正并记进计划 §9。）
--
-- 已登记为遗留：**两套排序规则本身**该收敛（谁统一到谁、要不要动已建的表），
-- 属跨批基础设施债，不在本批射程内。
--
-- ⚠️ 表名前缀 `sys_` 是既定约定（见 `V22` 头注释）。
-- ===========================================================================

CREATE TABLE sys_notice
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    type           VARCHAR(16)   NOT NULL COMMENT '四类之一：UPLOAD / ALERT / PERMISSION / SYSTEM（需求 09 §2.2）',
    title          VARCHAR(200)  NOT NULL COMMENT '列表加粗行文案',
    content        VARCHAR(1000) NOT NULL COMMENT '正文；失败类必须给「现象 + 下一步动作」（承 02 §静默降级视为缺陷）',
    actions_json   VARCHAR(1000) NOT NULL COMMENT '动作数组 JSON，至少 1 条 —— 需求 09 §2.10「每条通知都有至少一个可执行动作」的落点，故列上卡 NOT NULL',
    need_action    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '需要人工介入：进「待我处理」栏（需求 09 §2.3）',
    muteable       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '可「不再提醒」（需求 09 §2.4）。⚠️ 本批只落列、不消费：前端 mute 仍是 no-op，见计划 §9',
    user_id        BIGINT        NULL COMMENT '归属人。NULL = 广播（所有人都看得见）；⚠️ 本批的唯一产出方是异步任务终态，总带 owner ⇒ 本批不产广播行',
    source_type    VARCHAR(32)   NULL COMMENT '产出它的异步任务类型（doc-parse / audit-export …）；非任务产出的通知为 NULL',
    source_task_id VARCHAR(64)   NULL COMMENT '产出它的 taskId —— 从通知能反查任务，排查时不用猜',
    created_at     DATETIME      NOT NULL COMMENT '落库时刻；列表按它倒序 + 分组（今天/昨天/更早由前端算）',

    PRIMARY KEY (id),
    -- 列表主查询：where (user_id is null or user_id = ?) order by created_at desc
    KEY idx_notice_owner (user_id, created_at),
    -- 分类计数：where user_id = ? group by type（需求 09 §2.3 的六个计数）
    KEY idx_notice_owner_type (user_id, type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='站内通知（需求 09 FR-SYS-02）';

-- 已读：行在 = 已读。标回未读 = 删行（需求 09 §2.7「已读可逆」）。
-- ⚠️ 没有自增主键 —— PK 是 (notice_id, user_id) 这个二元组本身，
--    与 `sys_async_task` 用 `task_id` 当 PK 是同一种处置（见 `#150`）。
CREATE TABLE sys_notice_read
(
    notice_id BIGINT   NOT NULL COMMENT '通知 id（对应 sys_notice.id）；⚠️ 不建外键，与 V22 同口径',
    user_id   BIGINT   NOT NULL COMMENT '读过的人',
    read_at   DATETIME NOT NULL COMMENT '标记时刻（展示用；追溯"什么时候读的"）',

    PRIMARY KEY (notice_id, user_id),
    -- 「一键全部已读」与未读数统计都按 user_id 走
    KEY idx_notice_read_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='通知已读状态（按用户维度存，需求 09 §2.7）';

-- 设置：**没有行 = 默认开启**。四类默认都 enabled=true，
-- 而 ALERT 由业务侧强制（需求 09 §2.5）—— 它连"关掉的行"都不该存在。
CREATE TABLE sys_notice_setting
(
    user_id BIGINT      NOT NULL,
    type    VARCHAR(16) NOT NULL COMMENT '四类之一；ALERT 不允许存 enabled=0',
    enabled TINYINT(1)  NOT NULL COMMENT '0 = 该用户关掉了这一类',

    PRIMARY KEY (user_id, type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='通知按类型开关（需求 09 §2.5；系统告警强制开启）';
