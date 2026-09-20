-- ===========================================================================
-- V33 · S8-M10-A（`T-O13`）· 排序规则收敛
--
-- 实测分布（`information_schema` 实查）：**18 张 `utf8mb4_0900_ai_ci`
-- vs 10 张 `utf8mb4_unicode_ci`**，而库默认也是 `unicode_ci`。
--
-- ---------------------------------------------------------------------------
-- 为什么要收 —— 它是一颗**只在跑到那条 SQL 时才炸**的地雷
-- ---------------------------------------------------------------------------
-- 跨表比较两个**不同排序规则**的字符串列会报
--   1267 Illegal mix of collations (utf8mb4_0900_ai_ci) and (utf8mb4_unicode_ci)
-- 建表时看不出来、代码评审看不出来、编译更看不出来 —— 只有那条 SQL 真的执行时才炸。
-- `S6-M11` 的第一条用例就是这么炸的（`sys_notice.source_task_id` 当时是 `0900_ai_ci`，
-- 而 `sys_async_task.task_id` 是 `unicode_ci`，`NoticeRepository#findTask` 那个 join
-- 直接抛）。当时的处置是**把 V31 改成 unicode_ci 去迁就它** —— 那只是让那一次不炸，
-- 分歧本身还在。
--
-- ⚠️ 单列与字面量/绑定参数比较**不会**炸（MySQL 按可归约性挑列那一侧），
-- 所以"我只查一张表"的直觉在这里不管用：**危险形状只有"两张表的字符串列相互比较"**。
--
-- ---------------------------------------------------------------------------
-- 🔴 根因不在表上，在**库/服务端默认**
-- ---------------------------------------------------------------------------
-- `docker-compose.yml:50` 写的是 `--collation-server=utf8mb4_unicode_ci`，
-- 而 V1/V5/V7/… 这些迁移里**显式写了** `COLLATE=utf8mb4_0900_ai_ci`，
-- V17/V22/V23/V25/V27/V31 没写 ⇒ 继承库默认 ⇒ 两派从此并存。
-- ⇒ **只 `ALTER TABLE` 不够**：不把库默认一起改掉，下次建表（不写 COLLATE 的那些）
-- 又会落回 `unicode_ci`，分歧原样再生。本迁移两条都做：
--   ① `ALTER DATABASE` 改默认（**立即生效**，新表不再分裂）；
--   ② 把现存 9 张业务表 `CONVERT TO` 过去。
-- ⚠️ `docker-compose.yml` 那行也一并改成 `utf8mb4_0900_ai_ci`（同一批，见本批提交），
-- 但**它要重建容器才生效** —— 所以它是"对齐意图"，真正兜住的是 ①。
--
-- ---------------------------------------------------------------------------
-- 方向：向 `0900_ai_ci` 收敛（18 : 10）
-- ---------------------------------------------------------------------------
-- ① 它是 MySQL 8 原生默认（`unicode_ci` 是 5.x 时代的）；
-- ② 多数派，要改的表更少（9 张 vs 18 张）；
-- ③ 改了库默认之后，"新建表不写 COLLATE"这条会**默认落在正确的那一边** ——
--    也就是说这次收敛之后，**约定从"必须记得写"变成"不写也对"**。
--
-- ⚠️ `flyway_schema_history` **刻意不动**：它是 Flyway 自己的记账表，
-- 没有任何业务查询 join 它，改了收益为零；而它的 DDL 归 Flyway 所有，
-- 我们不该替它决定。这条豁免在 `CollationUniformityTest` 里是**冻结白名单**
-- （只许这一张，多一张就转红）。
--
-- 可逆：反向 `ALTER` 即可（`CONVERT TO utf8mb4_unicode_ci`）。
-- 实测代价：9 张表合计不到千行，整条迁移秒级完成。
-- ===========================================================================

ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE sys_async_task           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE sys_chat_session         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE sys_chat_message         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE sys_chat_attachment      CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE sys_retrieval_trace      CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE sys_retrieval_trace_chunk CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE sys_notice               CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE sys_notice_read          CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE sys_notice_setting       CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
