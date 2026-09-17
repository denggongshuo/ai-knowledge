-- ---------------------------------------------------------------------------
-- S4 · 阶段 E1：sys_async_task（`T-O01` 解析执行架构解耦的地基）
--
-- 这张表回答四件现在**完全答不出**的事：
--   ① 排队中的解析作业**存在哪里**（现在只活在内存 registry 的 ConcurrentHashMap 里）；
--   ② 进程重启后**还剩哪些没做完**（现在重启即丢，`GET /doc/tasks/{id}` 返回 10045）；
--   ③ 每个库**同时在跑几篇**（现在没有配额概念，`CallerRunsPolicy` 把容量问题
--      反压给 HTTP 请求线程，用户看到的是"上传卡住"）；
--   ④ 失败了**该不该重试**（现在没有重试，也没有区分"我们的问题"与"文档的问题"）。
--
-- ---------------------------------------------------------------------------
-- 为什么是表驱动，而不是 MQ / Redis Stream
-- ---------------------------------------------------------------------------
-- `优化/O1` §4 已论证：这个规模用不到 MQ 的吞吐，而其运维成本与「任务态要查得到」
-- 的需求并不匹配。Redis Stream 虽在栈里（Valkey 8.1），但**任务态仍然必须落 MySQL**
-- （`GET /doc/tasks/{taskId}` 要重启后可查）—— 那样就有两处状态要同步，
-- 正是最该避免的形态。
--
-- 表驱动 + `SELECT ... FOR UPDATE SKIP LOCKED` 在 MySQL 8.0+ 上是成熟范式
-- （本仓用 8.4），且**天然持久化** —— 一次同时满足「重启不丢」与「重启后可查」。
--
-- ---------------------------------------------------------------------------
-- 与 sys_audit_log / sys_document 边界
-- ---------------------------------------------------------------------------
--   · sys_document.status  = **用户可见的文档生命周期**（UPLOADED/PARSING/INDEXING/READY/FAILED）
--   · sys_async_task       = **调度层**（排队中/执行中/已完成/失败 + 重试次数 + 领取者）
-- 两者刻意不合并：排队是调度事实，不是文档状态。一个库排队 300 篇时，
-- 那 300 行的 status 都还是 UPLOADED —— 这是**如实**，不是缺失。
--
-- ⚠️ status 复用 AsyncTaskStatus 的取值（PENDING/RUNNING/DONE/FAILED），
--    不新增 'QUEUED' —— PENDING 的 label 本来就是「排队中」（AsyncTaskStatus.java:22），
--    再加一个同义词只会让读代码的人多问一次"这两个有什么区别"。
--    （`优化/O1` 步 2 写的是「新增 QUEUED 态」，本批按实物订正，见计划 §1-6/§3.4 假设 3。）
--
-- ---------------------------------------------------------------------------
-- 列与索引的取舍
-- ---------------------------------------------------------------------------
-- · `idempotency_key` **刻意不加唯一索引**：现有语义是「**仅任务未结束时**该键被占，
--   完成即释放」（AsyncTaskService.java:56 的注释）。唯一索引表达不了
--   "只在某状态下唯一"，加了会让"跑完再传同一个文件"被数据库拒绝。
--   去重改为查询时带状态过滤（见 SysAsyncTaskRepository#findActiveByIdempotencyKey）。
-- · `idx_task_claim (type, status, next_retry_at, created_at)` 是**领取路径唯一的熱索引**：
--   领取语句的谓词是 `type=? and status='PENDING' and (next_retry_at is null or <=now())`，
--   排序是 `created_at` —— 四列同序，一条索引走完。
-- · `locked_by` / `locked_at`：领取者与领取时刻。用途有二 ——
--   ① 崩溃恢复：进程被杀后 RUNNING 行永远不会自己变回 PENDING，
--      要靠 `locked_at` 超时把它们**回收**（O1 判据 4 的落地方式）；
--   ② 排查时能看出"是谁领的"（S4-D 的 `G3-5` 那种进程级取证要用）。
-- · `kb_id` / `doc_id`：解析作业要用。非解析类任务（审计导出、批量下载）留 NULL。
--
-- ⚠️ 跑过的迁移**只增不改**：V1~V16 一行不动，本次改动全部落在这里。
-- ---------------------------------------------------------------------------

CREATE TABLE sys_async_task
(
    task_id         VARCHAR(64)  NOT NULL COMMENT '任务 ID（16 位十六进制的 traceId 同源，见 TraceContext#newTraceId）',
    type            VARCHAR(32)  NOT NULL COMMENT '任务类型：doc-parse / doc-batch-download / audit-export',
    status          VARCHAR(16)  NOT NULL COMMENT '调度层状态，取值域 = AsyncTaskStatus（PENDING/RUNNING/DONE/FAILED）',

    -- ---------- 解析作业的业务坐标（非解析类任务留 NULL）----------
    kb_id           BIGINT       NULL COMMENT '所属知识库 —— 并发配额的键（同一个库的批量导入不得饿死别人）',
    doc_id          BIGINT       NULL COMMENT '目标文档',

    -- ---------- 追溯 ----------
    owner_id        BIGINT       NULL COMMENT '提交人（O6 的「谁在何时触发了 X 的解析」要读它）',
    trace_id        VARCHAR(64)  NULL COMMENT '提交时的 traceId，把异步日志与触发它的那次请求串成一条',

    idempotency_key VARCHAR(191) NULL COMMENT '幂等键。⚠️ 刻意**不是唯一索引**：现有语义是「仅任务未结束时被占，完成即释放」',

    -- ---------- 进度（对外就是 GET /doc/tasks/{taskId} 的响应体）----------
    stage           VARCHAR(64)  NULL COMMENT '业务阶段文案，前端直接展示（如"解析中"）',
    percent         INT          NOT NULL DEFAULT 0 COMMENT '完成百分比 0~100，单向递增',
    chunk_done      INT          NOT NULL DEFAULT 0 COMMENT '已完成分片数',
    chunk_total     INT          NOT NULL DEFAULT 0 COMMENT '总分片数；未知为 0，前端展示"处理中"而不是 0%',

    -- ---------- 重试与死信 ----------
    -- attempt 是「已经失败过几次」。可重试错误（基础设施类）退避后回到 PENDING；
    -- 不可重试错误（无文本层 / GBK / 超限 —— 文档自身的问题）**直接终态**：
    -- 对用户撒谎说"重试中"比直接说"这文件不行"更糟（O1 §4 步 7）。
    attempt         INT          NOT NULL DEFAULT 0 COMMENT '已失败次数（用于退避重试与死信判定）',
    next_retry_at   DATETIME     NULL COMMENT '下一次可领取时刻。NULL = 立即可领（首次入队走这条）',
    error_msg       VARCHAR(512) NULL COMMENT '失败原因（终态时写；G4-3：不许只说"失败"两字）',

    -- ---------- 领取（SKIP LOCKED 的可见性旁证）----------
    locked_by       VARCHAR(64)  NULL COMMENT '领取者标识（workerId + 线程名），崩溃回收与排查用',
    locked_at       DATETIME     NULL COMMENT '领取时刻。RUNNING 行超时未更新即视为**持有者已死**，回收为 PENDING',

    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    finished_at     DATETIME     NULL COMMENT '终态时刻；未结束为 NULL',

    PRIMARY KEY (task_id),

    -- 领取路径的唯一热索引：谓词四列同序，一条索引走完
    KEY idx_task_claim (type, status, next_retry_at, created_at),
    -- 「这篇文档的任务都怎么样了」—— 排查与 DocumentIssue 的追溯
    KEY idx_task_doc (doc_id),
    -- 幂等查询（带状态过滤，故不是唯一索引）
    KEY idx_task_idem (idempotency_key)
) COMMENT '异步任务登记表（T-O01）—— 表驱动调度：重启不丢、可查、可重试';
