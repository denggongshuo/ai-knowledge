/**
 * E2E 探针自清 —— **单一登记表 + 覆盖率闸 + 残留自检**（`S4-D2`，2026-09-17）。
 *
 * <h2>为什么要有这个文件</h2>
 * 「**新增持久化产物、清理函数不认识它**」在本项目已**复现三次**：
 * <ol>
 *   <li>`S4-M05-C1` 新增 `sys_chunk` 表 → 孤儿块；</li>
 *   <li>`S4-M05-C3` 探针文档开始被向量化 → Milvus 里留下幽灵向量；</li>
 *   <li>`S4-D` 新增按代分区 `kb{id}_g{代}` → 每次跑完留下几个**空分区**（实测 4 个）。</li>
 * </ol>
 * 三次的**共同根因不是"忘了写清理"，而是"清理逻辑散在 5 个脚本里各写一遍"** ——
 * 新增产物时没人回头把 5 处都改一遍。本批把它收敛成一处，并加两道闸：
 *
 * <h2>两道闸（这才是本文件真正的交付物）</h2>
 * <ul>
 *   <li><b>{@link assertArtifactCoverage}（覆盖率闸）</b> —— 扫 `information_schema`，
 *       凡带 `kb_id`/`doc_id`/`user_id` 列的表格，**要么在登记表里、要么在豁免表里写明理由**，
 *       否则**直接抛错点名该表**。它把「人必须记得改清理」换成「机器会拦住你」。</li>
 *   <li><b>{@link findKbProbeResidue}（残留自检）</b> —— 清理完再看一眼**副作用是否真的发生**，
 *       且必须用**独立观测点**（SQL 计数 / `partitions/list` / MinIO 列文件），
 *       不看清理命令的返回值。见长期记忆「Success 不等于执行了」。</li>
 * </ul>
 *
 * <h2>⚠️ 新增产物的正确姿势</h2>
 * 在 {@link KB_ARTIFACTS}（或 {@link USER_ARTIFACTS}）里**加一行**。
 * 别在调用方脚本里写 if —— 那正是本文件要消灭的形态。
 *
 * <h2>⚠️ 覆盖率闸只能覆盖「带 kb_id/doc_id/user_id 列」的表</h2>
 * 无这三列却仍被探针写脏的表（现役只有 `sys_audit_log`，见 {@link OUT_OF_SCOPE}）
 * **闸抓不到**，只能靠豁免表逐条人工声明。加新表时若它是这种形态，务必手动确认一遍。
 */

import { spawnSync } from 'node:child_process'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
// `T-O30`（`S9-M12-A`）：跨轮残留水位。**同一目录下的兄弟模块**，不引任何依赖。
import { deltaOf, readWatermark, writeWatermark } from './residue-watermark.mjs'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { unlinkSync, writeFileSync } from 'node:fs'

/** `ai-knowledge/`（仓库根）。本文件在 `ai_knowledge_app/scripts/lib/`，故上溯三级。 */
export const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..')

const MILVUS = process.env.E2E_MILVUS || 'http://127.0.0.1:19530'
const COLLECTION = 'knowledge_vectors'
/** 与后端 `MilvusConstants.PARTITION_PREFIX` 必须一致（`kb` + id + `_g` + 代）。 */
const PARTITION_PREFIX = 'kb'
const PARTITION_SEP = '_g'
/** MinIO 桶在容器内的挂载点（`docker exec kb-minio` 里的路径）。 */
const MINIO_ROOT = '/data/rag-documents'

/**
 * 覆盖率闸扫描的列**模式** —— 三个都是「外键式归属列」，能间接枚举出会被探针写脏的表。
 *
 * <p>🔴 `T-O15`（2026-09-20）：原来是**精确相等**（`in ('kb_id','doc_id','user_id')`），
 * 于是**换个列名就能从闸下面走过去** —— `target_user_id` / `last_user_id` / `owner_user_id`
 * 都带归属语义却一个都扫不到。`V31` 的头注点名警告过这个形状，
 * 而 `S8-M10-A` 的 `sys_qa_miss.last_user_id` 是一个**真的需要这么命名**的例子
 * （聚合行，叫 `user_id` 是用名字撒谎）⇒ 与其靠"记得手工登记"，不如让闸自己长眼睛。
 *
 * <p>⚠️ 两边必须一致：Java 侧 `ProbeArtifactCoverageTest.SCOPE_PATTERNS`。
 * 两份登记表靠 `ProbeArtifactCoverageTest` 对账 —— 模式不一致会让对账失去意义。
 */
const SCOPE_PATTERNS = ['%kb_id%', '%doc_id%', '%user_id%']

/**
 * 归属列 → 它指向的**父表**。用来数「无主残留」（指向一个已不存在的父行）。
 *
 * <p>⚠️ 这张表存在的理由本身就是一条教训：**只要"删除"与"自检"都依赖同一个会被删掉的父行，
 * 它们就永远是同一句话** —— 旧版按 `join sys_kb ... where k.code=?` 数残留，
 * 而 `sys_kb` 被同一轮清理删掉 ⇒ 恒返回 0（`#136`）。
 * 换成"父行还在不在"来问，就绕开了那个循环。
 *
 * <p>`user_id` 刻意**不在**这里：`sys_login_log` 的设计就是"用户删了日志要留"（风控证据），
 * 按它数会把**设计如此**当成残留。
 */
const PARENT_TABLE = { kb_id: 'sys_kb', doc_id: 'sys_document' }

// ---------------------------------------------------------------------------
// 登记表 —— **新增持久化产物只改这里**
// ---------------------------------------------------------------------------

/**
 * 探针库（`sys_kb` 一族）产生的全部持久化产物。
 *
 * <p><b>顺序即执行顺序</b>，且**不能重排**：跨存储的三类必须排在 SQL 之前
 * （删了 `sys_kb` 行就查不到 id → Milvus 定位不到；删了 `sys_document` 行就取不到
 * `storage_path` → MinIO 对象名丢失）。
 */
export const KB_ARTIFACTS = [
  {
    id: 'minio:storage_path', kind: 'minio', column: 'storage_path',
    why: '上传原件。删了 sys_document 行就再也取不到对象名',
  },
  {
    id: 'minio:parse_path', kind: 'minio', column: 'parse_path',
    why: '解析产物（MD / JSON）。同上',
  },
  {
    id: 'milvus:vectors', kind: 'milvus-vectors',
    why: 'T-508 起探针文档会被向量化。不删就是「文档没了、向量还在」的幽灵数据 —— 检索得到而库里查无此文',
  },
  {
    id: 'milvus:partitions', kind: 'milvus-partitions',
    why: 'T-406 起按代分区存放。entities/delete 只删**点**不删**容器**，于是每次跑完留下空分区',
  },
  {
    id: 'neo4j:graph', kind: 'neo4j-graph',
    why: '🔴 `T-C00`（S9-M12-A）新增的**第四种存储产物** —— 实体/关系写进 Neo4j，'
      + '而前三种（MinIO 对象 / Milvus 向量 / Milvus 分区）的兜底**一个都不认识它**。'
      + '⚠️ 脚本侧**没有**它的清理能力（要引 neo4j 客户端或起 `cypher-shell` 子进程），'
      + '所以这一行是**登记 + 表态**，不是"已覆盖"：'
      + '① Java 侧 `GraphWriteFlowTest` 的 `@AfterEach` 按登记的 docId 清（那是本批真正在做的）；'
      + '② E2E 侧当前**不写图**（`m05.graph.enabled=false` 只在测试期开）⇒ 脚本侧暂时无对象可清。'
      + '⚠️ 哪天真让 E2E 打开图谱开关，这一行必须跟着升级成真清理 —— '
      + '否则"产物认识、清理不认识"就是这个仓库已复现五次的那个形状。',
  },
  {
    id: 'sql:sys_async_task', kind: 'sql', table: 'sys_async_task', via: 'kb_id',
    why: 'T-O01 起探针上传会入队解析作业。⚠️ 这一行是**被覆盖率闸逼出来的** —— '
      + 'S4-E1 新增了这张表，闸立刻点名它"未登记"，正好验证了 S4-D2 那道闸的价值',
  },
  { id: 'sql:sys_parse_issue', kind: 'sql', table: 'sys_parse_issue', via: 'kb_id', why: '未识别表格清单' },
  { id: 'sql:sys_chunk', kind: 'sql', table: 'sys_chunk', via: 'kb_id', why: '分片（C1 起）' },
  { id: 'sql:sys_document', kind: 'sql', table: 'sys_document', via: 'kb_id', why: '文档行' },
  {
    id: 'sql:sys_download_record', kind: 'sql', table: 'sys_download_record', via: 'kb_id',
    why: '下载记录（V40 · S7-M06-E）。⚠️ 同一张表在 USER_ARTIFACTS 里还有**第二条** —— '
      + '下载必然同时锚定"哪个库的哪一篇"与"谁下的"，两条归属线各自都要能收干净；'
      + '只按 kb_id 清会漏掉"文档已删、库还在"的行，只按 user_id 清会漏掉种子账号下的行',
  },
  { id: 'sql:sys_kb_grant', kind: 'sql', table: 'sys_kb_grant', via: 'kb_id', why: '知识库授权' },
  { id: 'sql:sys_kb', kind: 'sql', table: 'sys_kb', via: 'code', why: '主表（按 code 命中，非 id）' },
]

/**
 * 探针账号（`sys_user` 一族）产生的全部持久化产物。
 *
 * <p>⚠️ `sys_login_log` 是 2026-09-17 才补上的 —— 在那之前 `purgeProbeUsers` 只删用户，
 * 于是每次跑完都留下 `user_id` 指向已删用户的**孤儿行**（实测存量 3984 行）。
 * 该表**有 `username` 列**，本可按前缀删，纯粹是漏了。
 *
 * <h3>⚠️ 本表**只管数据库表** —— MinIO 对象在表外单独收（`#163`）</h3>
 * <p>问答附件（`chat-attachments/{userId}/{uuid}.{ext}`）**不挂在任何探针库下**，
 * 所以 {@link minioKeysOf}（要 join `sys_kb`）看不见它们。它们由
 * {@link minioKeysOfUserPrefix} 按 **userId 前缀**单独快照并删除，
 * 在 `teardownUserProbe` 里与这张表**并排**走。
 *
 * <p>**不加进本表**是有原因的：本表的每一项都会被
 * {@link findUserProbeResidue} 当成"一张有 `via` 的表"去数（它无条件读 `a.table`），
 * 混进一个非表条目会让那段计数逻辑拿到 `undefined` 去拼 SQL。
 * 与其在计数循环里加一个 `if (a.kind === 'minio-...') continue`（那就又多了一处
 * "新增产物时要同步改的地方"），不如让它走自己那条显式的路。
 */
export const USER_ARTIFACTS = [
  {
    id: 'sql:sys_login_log', kind: 'sql-user', table: 'sys_login_log', via: 'username',
    why: '登录日志。按 username 前缀删（用户行删掉后 user_id 就成孤儿了）',
  },
  { id: 'sql:sys_user_role', kind: 'sql-user', table: 'sys_user_role', via: 'user', why: '用户角色关联' },

  // ---- S5-M08-A 新增（问答）：**子表排在父表之前**，顺序即执行顺序 ----
  // 覆盖率闸在加进来之前就会点名这两张带 user_id 的表，所以它们是**被闸逼出来的**
  // —— 与 sys_async_task 那一行同一种来历。
  {
    id: 'sql:sys_retrieval_trace_chunk', kind: 'sql-user', table: 'sys_retrieval_trace_chunk',
    via: 'subquery',
    deleteSql: 'delete from sys_retrieval_trace_chunk where trace_id in '
      + '(select t.trace_id from sys_retrieval_trace t where t.user_id in ({userIds}));',
    countSql: 'select count(*) from sys_retrieval_trace_chunk where trace_id in '
      + '(select t.trace_id from sys_retrieval_trace t where t.user_id in ({userIds}));',
    why: '检索打点的分片明细。⚠️ 它**没有 user_id**（归属要靠 trace_id 顺上去）——'
      + '这正是「产物认识、归属维度不认识」的第 N 次变形，所以登记表多了一种 via',
  },
  {
    id: 'sql:sys_chat_message', kind: 'sql-user', table: 'sys_chat_message',
    via: 'subquery',
    // ⚠️ 子查询里仍写 `s.id` 而不是 `id`：不带别名限定会报
    // 1052 Column 'id' in field list is ambiguous（这个错第一次跑 E2E 才暴露，
    // 而 `mysqlScalar` 把 stderr 吞了，报出来只有 "mysql 查询失败（status=1）：" 后面一片空白）
    deleteSql: 'delete from sys_chat_message where session_id in '
      + '(select s.id from sys_chat_session s where s.user_id in ({userIds}));',
    countSql: 'select count(*) from sys_chat_message where session_id in '
      + '(select s.id from sys_chat_session s where s.user_id in ({userIds}));',
    why: '问答消息。同上：归属靠 session_id 顺上去',
  },
  {
    id: 'sql:sys_retrieval_trace', kind: 'sql-user', table: 'sys_retrieval_trace', via: 'user',
    why: '检索链路打点（T-707）。⚠️ 只有 user_id 没有 kb_id/doc_id —— 库族清理看不见它',
  },
  {
    // ⚠️ 同一张表要有**两条**登记：库族那条走 `kb_id`，这条走 `owner_id`。
    // 导出类任务（`audit-export`）**没有知识库维度**（`kb_id` 为 NULL），
    // 只按 `kb_id` 清理会把它们永远留在表里 —— 实测 E2E 跑完就剩这一行，
    // 而它的 `owner_id` 当时也是 NULL（`AuditExportService` 没传业务坐标，已一并修好）
    id: 'sql:sys_async_task_by_owner', kind: 'sql-user', table: 'sys_async_task', via: 'subquery',
    deleteSql: 'delete from sys_async_task where owner_id in ({userIds});',
    countSql: 'select count(*) from sys_async_task where owner_id in ({userIds});',
    why: '异步任务按**提交人**清。⚠️ 归属列是 `owner_id` 不是 `user_id` —— 别按 `user_id` 写，那会报未知列',
  },
  {
    id: 'sql:sys_chat_session', kind: 'sql-user', table: 'sys_chat_session', via: 'user',
    why: '问答会话（T-802 建表半边）',
  },
  {
    id: 'sql:sys_download_record_by_user', kind: 'sql-user', table: 'sys_download_record', via: 'user',
    why: '下载记录按**下载人**清（V40 · S7-M06-E）。⚠️ 与库族那条是同一张表的两个归属：'
      + '文档软删后 `kb_id` 那条线还能命中，但"库先没了"的行只有这条线收得掉',
  },
  {
    id: 'sql:sys_chat_attachment', kind: 'sql-user', table: 'sys_chat_attachment', via: 'user',
    why: '问答图片附件（T-805）。⚠️ 它**自己有 user_id**，不顺着会话删 ——'
      + '预上传发生在会话之前，附件可以先于会话存在。'
      + '⚠️ 它同时是一个 MinIO 对象（chat-attachments/ 前缀）：本表只登记**行**，'
      + '桶里的对象不在清理器的能力范围内（与 documents/ 同一条取舍，靠生命周期规则）',
  },

  // ---- S6-M11 新增（通知中心）：`T-B03` 的**外溢面** ----
  // 🔴 这三张表是**本批新造出来的产物**：`NoticePublisher` 接线之后，
  //    **凡是跑完一个异步任务的用例/E2E 都会产通知行** —— 它们此前一行都不产，
  //    所以"谁清通知"这个问题在 `T-B03` 之前**不存在**。
  // ⚠️ 它们的归属**不是 `kb_id`**（通知没有知识库维度）⇒ 进不了 `KB_ARTIFACTS`；
  //    这里登记的是"探针账号名下那些"，**种子超管（admin）名下的那些靠
  //    `teardownKbProbe` 里的无主兜底**（任务行被删 ⇒ 通知成孤儿）。两条合起来才叫覆盖。
  { id: 'sql:sys_notice_read', kind: 'sql-user', table: 'sys_notice_read', via: 'user',
    why: '通知已读状态。⚠️ 子表，**必须先于 sys_notice**（顺序即执行顺序）' },
  { id: 'sql:sys_notice_setting', kind: 'sql-user', table: 'sys_notice_setting', via: 'user',
    why: '通知设置。没有行 = 默认开启（四类都开）' },
  { id: 'sql:sys_notice', kind: 'sql-user', table: 'sys_notice', via: 'user',
    why: '站内通知（T-B03 产出）。⚠️ `user_id` **可空**（NULL = 广播），'
      + '而本表按 `user_id in (...)` 删 ⇒ **广播行删不掉**。'
      + '本批的唯一产出方是异步任务终态、总带 owner ⇒ 不产广播行；'
      + '收尾判据里有一条「user_id is null 的行数为 0」盯着它' },

  // ---- S8-M10-A 新增（未命中清单）：`T-A00` 的沉淀池 ----
  // 🔴 归属列叫 `last_user_id`（**最近**提问人）而不是 `user_id` —— 因为它是**聚合行**：
  //    一行可能对应好几个提问人，叫 user_id 是用名字撒谎。
  // ⚠️ 代价：覆盖率闸扫的是**精确等于** `user_id` 的列名 ⇒ **闸看不见本表**。
  //    所以这一行是**手工**登记的，不是被闸逼出来的 —— 正是 `V31` 头注警告的那个形状
  //    （换个列名就能躲开闸）。这里没有躲的意思：登记是主动的，理由也写在这里。
  // ⚠️ 顺序：**必须先于 `sys_user`**（顺序即执行顺序）。
  { id: 'sql:sys_qa_miss', kind: 'sql-user', table: 'sys_qa_miss', via: 'subquery',
    deleteSql: 'delete from sys_qa_miss where last_user_id in ({userIds});',
    countSql: 'select count(*) from sys_qa_miss where last_user_id in ({userIds});',
    why: '未命中池（T-A00）。按 `last_user_id` 删。⚠️ 真实用户提问沉淀出来的行**不该被清** ——'
      + '这里删的是探针账号名下的那些（`via: subquery` 走的是探针用户 id 列表）' },

  { id: 'sql:sys_user', kind: 'sql-user', table: 'sys_user', via: 'username', why: '用户主表' },
  {
    // 🔴 **既不是库族也不是用户族**：这张表**一个归属列都没有**（无 kb_id/doc_id/user_id），
    // 所以上面两条路都够不着它 —— 只能按**日期**清当天。它是本仓第三种"归属维度不存在"的形态
    // （前两种：`kb_id` 为 NULL 的导出任务、没有归属列只能靠两侧对账发现的 `sys_chat_message`）。
    id: 'sql:sys_model_usage', kind: 'sql-date', table: 'sys_model_usage', via: 'date',
    deleteSql: 'delete from sys_model_usage where occurred_at = curdate();',
    countSql: 'select count(*) from sys_model_usage where occurred_at = curdate();',
    why: '模型用量（T-905）。⚠️ 与 `#148` 同族：探针写的行与真实用量在业务上**无法区分**'
      + '（同一个模型名、同一个用途），清当天会连同真实用量一起清掉。'
      + '之所以仍敢清：本表是**聚合表**，行数上界 = 天数 × 模型数 × 用途数，**不随调用次数增长**。'
      + '⚠️ 它同时是**覆盖率闸的盲区**（闸扫的是"带归属列的表"，本表不属于那一类）——'
      + '台账记在 Java 侧 `ProbeArtifacts.NO_OWNERSHIP_COLUMN`，本条的登记正是那个台账逼出来的',
  },
  {
    // 🔴 **它有 `kb_id` 列，但那列不是归属、是分组维度** —— 所以 `via: 'kb_id'` 那条路
    // 在这里**会漏掉大半**：哨兵 `kb_id = 0` 表示"跨库提问"，而当前链路上
    // **每次提问都覆盖全部可见库** ⇒ 绝大多数行的 `kb_id` 就是 0。
    // 按日期清才是对的，而且它是**缓存**（源是 `sys_retrieval_trace`，可整体重算）
    // ⇒ 清它没有信息损失 —— 与 `sys_model_usage` 那条"清掉就真没了"性质相反。
    id: 'sql:sys_ops_retrieval_daily', kind: 'sql-date', table: 'sys_ops_retrieval_daily', via: 'date',
    deleteSql: 'delete from sys_ops_retrieval_daily where stat_date >= curdate() - interval 7 day;',
    countSql: 'select count(*) from sys_ops_retrieval_daily where stat_date >= curdate() - interval 7 day;',
    why: '检索质量按天汇总（T-A04 预计算）。窗口 7 天与各用例类的 `clearTraceWindow` 同口径'
      + '——**打点删哪一段，缓存就跟着删哪一段，缓存不能比事实活得久**。'
      + '⚠️ 它与 Java 侧 `ProbeCleanup#sweepProbePrecompute` 必须**逐字同一条 SQL**：'
      + '两侧对账只管表名在不在，管不了窗口取几天，那一层靠这条注释与人工复核',
  },
  {
    id: 'sql:sys_ops_usage_daily', kind: 'sql-date', table: 'sys_ops_usage_daily', via: 'date',
    deleteSql: 'delete from sys_ops_usage_daily where stat_date >= curdate() - interval 7 day;',
    countSql: 'select count(*) from sys_ops_usage_daily where stat_date >= curdate() - interval 7 day;',
    why: '使用统计按天汇总（T-A04 预计算）。`user_id` 同理是分组维度且有哨兵 0（无归属提问），'
      + '按人删不掉；它是缓存，按日期清没有信息损失',
  },
]

/**
 * **显式豁免**：确实会被探针写脏、但**有意不清**的表。
 *
 * <p>豁免必须写理由 —— 没理由的豁免等于把闸关掉。
 */
export const OUT_OF_SCOPE = [
  {
    table: 'sys_audit_log',
    why: '审计日志按设计**只增不删**（合规）。且行自带 target/operator，**不指向已删行**，'
      + '与孤儿登录日志性质不同；清了反而是在毁审计证据。',
  },
]

// ---------------------------------------------------------------------------
// 基础动作（原先散在 5 个脚本里各一份）
// ---------------------------------------------------------------------------


/**
 * 直连 MySQL 取一个标量（多行时返回多行文本）。
 *
 * <p>走 `docker exec kb-mysql`，**不引 mysql 客户端依赖**。连接参数从
 * `ai_knowledge_web/.env` 读，保证与后端同一套。
 */
export function mysqlScalar(sql) {
  const envFile = `${REPO_ROOT.replace(/\\/g, '/')}/ai_knowledge_web/.env`
  const script = `
set -a; . ${JSON.stringify(envFile)}; set +a
docker exec kb-mysql mysql --default-character-set=utf8mb4 -u"$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -e ${JSON.stringify(sql)} 2>/dev/null
`.trim()
  const r = spawnSync('bash', ['-c', script], { cwd: REPO_ROOT, encoding: 'utf8' })
  if (r.status !== 0) throw new Error(`mysql 查询失败（status=${r.status}）：${r.stderr || r.stdout}`)
  return (r.stdout ?? '').trim()
}

/** 行计数（`mysqlScalar` 的常用包装）。 */
export const mysqlCount = (sql) => Number(mysqlScalar(sql) || 0)

/**
 * 删 MinIO 里的对象（**只删库行是不够的**）。
 *
 * <p>不删的后果是实测过的（`S4-M05-C2`）：两个 E2E 各跑一轮就在桶里留下 9 个对象，
 * 而 `GET /kb/storage-quota` 会**全桶递归列举**（`KbService#minioUsedBytes`）——
 * 攒到上千个之后慢到读超时，把毫不相干的 `KbWriteFlowTest` 拖红。
 * 那是最费时间的假红形态：错误的信号指向错误的地方。
 *
 * <h3>⚠️ 必须把路径塞进 `sh -c` 里，不能当裸参数传给 `docker exec`</h3>
 * <p>Git Bash 会把形似 Unix 绝对路径的**裸参数**改写成 Windows 路径
 * （`/data/…` → `C:/Program Files/Git/data/…`，MSYS 的自动转换）。
 * 于是 `rm -rf` 删的是一个不存在的路径，而 `-f` 让它**静默返回 0** ——
 * 实测就是这个形态：命令报成功，对象一个没少。`MSYS_NO_PATHCONV=1` + 整串进 `sh -c` 双重保险。
 */
export function minioRemove(rawObjectKeys) {
  const keys = normalizeKeys(rawObjectKeys)
  if (keys.length === 0) return 0
  const paths = keys.map((k) => `'${MINIO_ROOT}/${k.replace(/'/g, "'\\''")}'`).join(' ')
  spawnSync('bash', ['-c', `MSYS_NO_PATHCONV=1 docker exec kb-minio sh -c "rm -rf ${paths}"`],
    { cwd: REPO_ROOT, encoding: 'utf8' })
  return keys.length
}

/**
 * 这些对象**是否还在** —— 独立观测点，不看 `rm` 的返回值（`-f` 让它恒返回 0）。
 *
 * <p>用 `ls` 而不是 `for f in …; do [ -e "$f" ]` 循环：后者的 `$f` 会被**外层 bash**
 * 先展开掉（它落在外层双引号里），于是循环体拿到空值、恒判「不存在」→ 又一个恒真守卫。
 */
export function minioStillPresent(rawObjectKeys) {
  const keys = normalizeKeys(rawObjectKeys)
  if (keys.length === 0) return []
  const paths = keys.map((k) => `'${MINIO_ROOT}/${k.replace(/'/g, "'\\''")}'`).join(' ')
  const out = spawnSync('bash', ['-c',
    `MSYS_NO_PATHCONV=1 docker exec kb-minio sh -c "ls ${paths} 2>/dev/null"`,
  ], { cwd: REPO_ROOT, encoding: 'utf8' }).stdout ?? ''
  return out.split('\n').map((s) => s.trim()).filter(Boolean)
}

/**
 * **按用户前缀**列出 MinIO 对象（`#163`）—— 问答附件那条路。
 *
 * <h3>为什么需要它</h3>
 * <p>{@link minioKeysOf} 只能按**库**取（它从 `sys_document.storage_path` /
 * `parse_path` 两列读），而问答附件**不属于任何探针库** —— 它的归属是
 * `chat-attachments/{userId}/{uuid}.{ext}` 里的 **userId**（与 `avatars/` 同一条取舍）。
 * 于是用户族的 teardown 删了**行**、却一直没删**对象**：每轮都在桶里多一个。
 *
 * <h3>为什么可以直接 `find`</h3>
 * <p>MinIO 在这个部署里的数据目录是挂进容器的（{@link #MINIO_ROOT}），
 * 所以"列一个前缀"就是列一个目录 —— 不需要 S3 客户端。
 *
 * <p>⚠️ **用 `ls -1` 而不是 `find`**：`kb-minio` 的镜像是极简的，**没有 `find`**
 * （实测 `exit 127`）。而"没找到"与"这个命令不存在"在原实现里长得一模一样 ——
 * 又一处恒真守卫。`ls` 则确实存在（{@link minioStillPresent} 一直用它）。
 *
 * <p>⚠️ 与 {@link minioRemove} 同一条坑：整串必须进 `sh -c` 并带
 * `MSYS_NO_PATHCONV=1`，否则 MSYS 会把 `/data/…` 改写成 `C:/Program Files/Git/data/…`。
 *
 * <p>逐个 id 起一次调用（而不是把多个目录拼给一条命令）：探针账号每次只有一两个，
 * 而拼串要处理 `ls` 的目录表头格式 —— 那正是 `minioStillPresent` 那个 javadoc
 * 警告过的"外层 bash 先展开"的那类坑。
 *
 * @param userIds 用户 id 集合（**必须在删 `sys_user` 之前抓**，与其它归属 id 同理）
 */
/**
 * 「**路径即归属、第二段是 userId**」的全部前缀 —— 它们不属于任何探针库，
 * 所以 `minioKeysOf`（要 join `sys_kb`）**看不见**它们，只能按用户前缀单独收。
 *
 * <p>🔴 每**新增**一个这种形状的前缀都要加到这里，否则那个脚本造一个对象、
 * 桶里就多一个（`#163` 的原始形状）。目前五个：
 * <ul>
 *   <li>{@code chat-attachments/}（`T-805` 预上传的问答图片）；</li>
 *   <li>{@code batch-download/}（`T-614` 打包 zip，`S7-M06-E` 新增）；</li>
 *   <li>{@code doc-export/}（`T-615` 下载记录清单 CSV，`S7-M06-E` 新增）；</li>
 *   <li>{@code audit-export/}（`T-O11` 审计日志导出）；</li>
 *   <li>{@code ops-export/}（`T-A02` 运营报表导出）。</li>
 * </ul>
 * <p>⚠️ 前三个的来历是「事后补的」：`S7-M06-E` 新造了 `batch-download/` 与
 * `doc-export/`，**当时只加进了 Java 侧的登记表**（`ProbeCleanup#MINIO_PREFIXES`），
 * 本函数漏了 —— 而"脚本侧漏了"的表现是 **E2E 每跑一轮桶里多几个对象**，
 * 且没有任何闸会点名（Java 侧那条 `everyKnownPrefixIsClassified` 查的是 Java 侧的清单）。
 * <p>🔴 后两个（`S7-M06-F` 补）是**同一条闸**在两侧对账时才抓出来的 ——
 * 它们的对象一直由 `e2e-audit-backfill` / `e2e-ops-qa-stats` **各自点名清**，
 * 于是"按调用点清"看着没问题；而 {@link teardownUserProbe} 这条**通用**的收尾
 * 一个都删不到它们，只能等下一轮 `mvn test` 的 Java 兜底扫走。
 * <p>盯这条的机器检查：`ProbeArtifactCoverageTest#minioPrefixListsAgreeWithTheScriptSide`
 * —— 它要求本清单与 Java 侧 `Owner.PATH_USER_ID` 的那几个**逐条相等**，
 * 并顺带挡住"拼错前缀"（拼错的后果是 `ls` 一个不存在的目录 ⇒ 空输出 ⇒ 清理"成功"却一个没删）。
 */
export const USER_MINIO_PREFIXES = [
  'chat-attachments/', 'batch-download/', 'doc-export/', 'audit-export/', 'ops-export/',
]

export function minioKeysOfUserPrefix(userIds) {
  return USER_MINIO_PREFIXES.flatMap((prefix) => minioKeysOfPathUserPrefix(prefix, userIds))
}

/**
 * 任意 `{前缀}{userId}/…` 形状下的对象名（`T-O19` 提取）。
 *
 * <p>由来：`ops-export/`（`T-A02` 新增）与 `audit-export/` 一样是**路径即归属**，
 * 而 `e2e-ops-qa-stats` 真跑一次导出就会在桶里落一个对象 —— 它必须自己收，
 * 否则那个对象要等下一次 `mvn test` 的 Java 侧兜底才被扫走
 * （而"会造行的脚本自己不清"正是 `T-O19` 记的那个形状）。
 *
 * <p>⚠️ 为什么把 `chat-attachments/` 那版**改成委托**而不是复制一份：
 * 两份 `ls | 拼串` 的代码一定会漂（`MSYS_NO_PATHCONV` 那条坑只会在其中一份上被踩到）。
 *
 * @param prefix  带斜杠的前缀，如 `ops-export/`
 * @param userIds 用户 id 集合（**必须在删 `sys_user` 之前抓**）
 */
export function minioKeysOfPathUserPrefix(prefix, userIds) {
  const ids = normalizeKeys(userIds)
  const keys = []
  for (const id of ids) {
    const out = spawnSync('bash', ['-c',
      `MSYS_NO_PATHCONV=1 docker exec kb-minio sh -c `
      + `"ls -1 '${MINIO_ROOT}/${prefix}${id}' 2>/dev/null"`,
    ], { cwd: REPO_ROOT, encoding: 'utf8' }).stdout ?? ''
    for (const name of out.split('\n').map((s) => s.trim()).filter(Boolean)) {
      keys.push(`${prefix}${id}/${name}`)
    }
  }
  return keys
}

/**
 * 归一成对象名数组。
 *
 * <p>**两种入参都要吃**：`mysql -N` 的多行文本（`minioKeysOf` 那条路），
 * 以及已经切好的数组（`teardownKbProbe` 内部把自己的快照回传）。
 * 只认字符串会在第二条路上炸成 `(raw ?? "").split is not a function` —— 实测踩过。
 *
 * <p>`mysql -N` 把空值打成字面量 `NULL`，要滤掉。
 */
function normalizeKeys(raw) {
  const lines = Array.isArray(raw) ? raw : String(raw ?? '').split('\n')
  return lines.map((s) => String(s).trim()).filter((s) => s && s !== 'NULL')
}

/**
 * Milvus REST v2（2.4+ 自带，零新增依赖）。返回解析后的 JSON；失败时 `{code:-1, raw}`。
 *
 * <h3>🔴 请求体必须走**临时文件**（`--data-binary @file`），不能内联进命令串</h3>
 * <p>本机是 Git for Windows，`bash -c` 里**内联的非 ASCII 会被按当前代码页重新编码**
 * ⇒ 到了服务端就是一堆乱码。而 Milvus 的集合带 BM25 分析器，它对 `bm25_text` 有
 * **UTF-8 校验** ⇒ 报的是一句看着毫无头绪的话：
 * <pre>
 *   code 65535 · input with analyzer should be utf-8 format, but row: 0 not utf-8 format
 * </pre>
 * （实测踩到：`S7-M06-F` 的 E2E 往探针分区种中文正文时。而 `probe-orphan-vectors.mjs`
 * 一路没踩到，纯粹因为它的探针内容是 ASCII 的 `'probe vector'` —— 这种"换一份数据就炸"
 * 的形态最难归因。）
 * <p>这与长期记忆里那条同源：**`curl -d '{"中文"}'` 在本机必 400，一律 `--data-binary @file`**。
 * 写文件比拼串还顺带解决了引号转义 —— 原先那个 `sq()` 助手就是为转义而生的，
 * 现在整个去掉。
 */
export function milvusPost(path, body) {
  const payload = JSON.stringify(body)
  const tmp = join(tmpdir(), `ak-milvus-${process.pid}-${Date.now()}-${Math.floor(Math.random() * 1e6)}.json`)
  writeFileSync(tmp, payload, 'utf8')
  try {
    const out = spawnSync('bash', ['-c',
      `curl -s -m 20 -X POST ${MILVUS}/v2/vectordb/${path} ` +
      `-H 'Content-Type: application/json' --data-binary @'${tmp.replace(/'/g, "'\\''")}'`,
    ], { cwd: REPO_ROOT, encoding: 'utf8' }).stdout ?? ''
    try {
      return JSON.parse(out)
    } catch {
      return { code: -1, raw: out }
    }
  } finally {
    // 用后即焚：探针脚本可能一轮调几十次，攒着会把 tmp 塞满
    try {
      unlinkSync(tmp)
    } catch {
      // 删不掉也不该让探针失败（与 `DocStorage#remove` 同一取舍）
    }
  }
}

/**
 * 探针库当前在 Milvus 里的**向量条数**。
 *
 * <p>⚠️ **必须用 `entities/query` 的 `count(*)`，不能用 `get_stats` 的 `rowCount`** ——
 * 后者只统计**已 flush** 的数据，而写入路径刻意不 flush（见 `MilvusChunkWriter` 类 javadoc），
 * 拿它判残留会**恒得 0**，等于没查（实测 `rowCount=0` 而实查 81 条）。
 *
 * <p>⚠️ **删除的可见性有几秒延迟**（实测：`deleteCount=9` 之后立刻 count 仍得 9）
 * → 只查一次会把「刚删完」读成「没删掉」→ **假失败**。故轮询。
 */
export const milvusVectorCount = async (ids) => {
  if (!ids || ids.length === 0) return 0
  for (let i = 0; i < 6; i++) {
    const r = milvusPost('entities/query', {
      collectionName: COLLECTION,
      filter: `kb_id in [${ids.join(',')}]`,
      outputFields: ['count(*)'],
    })
    const n = Number(r?.data?.[0]?.['count(*)'] ?? -1)
    if (n === 0) return 0
    if (i < 5) await sleep(2000)
    else return n
  }
  return -1
}

/** 当前 collection 的全部分区名。查不到返回 `null`（区分「空」与「没查成」）。 */
export function milvusPartitionNames() {
  const r = milvusPost('partitions/list', { collectionName: COLLECTION })
  return Array.isArray(r?.data) ? r.data : null
}

/** 某探针库**残留的代分区名**（独立观测点：`partitions/list`，不看 drop 的返回值）。 */
export async function milvusLeftoverPartitions(ids) {
  if (!ids || ids.length === 0) return []
  for (let i = 0; i < 4; i++) {
    const names = milvusPartitionNames()
    if (names === null) return ['<partitions/list 查询失败>']
    const left = names.filter((n) => ids.some((id) => n.startsWith(`${PARTITION_PREFIX}${id}${PARTITION_SEP}`)))
    if (left.length === 0) return []
    if (i < 3) await sleep(1500)
    else return left
  }
  return []
}

/**
 * **无主分区兜底**（`T-O28` 那一族的第三件 · `S7-M06-F` 补）。
 *
 * <h3>它补的是哪个洞</h3>
 * <p>{@link milvusDropPartitions} 只丢**调用方点名的那几个 kb** 的代分区
 * （`milvusDropPartitions([kbId, ...])`）。于是一旦某一轮的收尾没跑到
 * （被打断 / 被强杀 / 崩在半路），它留下的分区就**再也没有人认领** ——
 * 后面每一轮都不认识那个 kbId。
 * <p><b>实测</b>：本批收尾时集合里躺着 **4 个** `kb*_g1` 分区，
 * 而它们的 `sys_kb` 行早就不在了（`kb1008100` / `kb1008101` / `kb1013472` / `kb1013474`）。
 * 它们**是空的**（向量一条不剩），所以 `T-O27` 那条"以条数计"的判据看不见它们 ——
 * 而那正是分区这一族的老形状：**容器是容器，点是点**。
 *
 * <h3>🔴 只按「名字形状 + 库还在不在」判，不做任何猜测</h3>
 * <p>形状不符的一律**不动**：`_default`（集合自带的）、
 * `zzvecprobe` / `zzprobe_*`（`MilvusVectorWriteTest` 等探针自建的）、
 * 以及人工建的任何分区 —— 它们**不属于任何库**，也就谈不上"无主"。
 * 这与 `ProbeCleanup#pathOwnerId` 那条"解析不出来就跳过、不猜"是同一条纪律：
 * **猜错的代价是删掉别人的东西。**
 *
 * @returns {{dropped: string[], failed: string[], scanned: number}}
 */
export function sweepOrphanPartitions() {
  const names = milvusPartitionNames()
  if (names === null) {
    // 「空」与「没查成」必须分开报（否则一次查询失败会被读成"没有无主分区"）
    return { dropped: [], failed: [], scanned: -1 }
  }
  const alive = existingKbIds()
  const orphan = names.filter((n) => {
    const m = /^kb(\d+)_g\d+$/.exec(n)
    return m ? !alive.has(Number(m[1])) : false
  })
  for (const name of orphan) {
    // 顺序固定：release → drop（裸 drop 会以"分区已加载"失败）
    milvusPost('partitions/release', { collectionName: COLLECTION, partitionNames: [name] })
    milvusPost('partitions/drop', { collectionName: COLLECTION, partitionName: name })
  }
  const after = milvusPartitionNames() ?? []
  return {
    dropped: orphan.filter((n) => !after.includes(n)),
    failed: orphan.filter((n) => after.includes(n)),
    scanned: names.length,
  }
}

/** 删点：按 `kb_id` 过滤删掉探针库的全部向量。 */
export function milvusDeleteVectors(ids) {
  if (!ids || ids.length === 0) return
  milvusPost('entities/delete', { collectionName: COLLECTION, filter: `kb_id in [${ids.join(',')}]` })
}

/**
 * 丢掉探针库的**代分区**。顺序**固定**：`entities/delete`（调用方先做）→ `release` → `drop`。
 *
 * <p>⚠️ 裸 drop 会失败，服务端原话是
 * `partition cannot be dropped, partition is loaded, please release it first`
 * （Java SDK 那侧看到的是同一个原因，但只给了 `65535`）。
 */
export function milvusDropPartitions(ids) {
  if (!ids || ids.length === 0) return
  const names = milvusPartitionNames()
  if (names === null) return
  const mine = names.filter((n) => ids.some((id) => n.startsWith(`${PARTITION_PREFIX}${id}${PARTITION_SEP}`)))
  for (const name of mine) {
    milvusPost('partitions/release', { collectionName: COLLECTION, partitionNames: [name] })
    milvusPost('partitions/drop', { collectionName: COLLECTION, partitionName: name })
  }
}

// ---------------------------------------------------------------------------
// `T-O27`：Milvus **孤儿向量**清扫
// ---------------------------------------------------------------------------

/**
 * 单次 `entities/query` 的取数上限（Milvus 的硬上限就是 16384）。
 *
 * <p>⚠️ 与下面 `MILVUS_MAX_PAGES` 一起构成"输入完整性"这条判据 —— 见 `milvusDistinctKbIds`。
 */
const MILVUS_PAGE = 16384

/** 扫 `kb_id` 的翻页护栏（20 页 ≈ 32 万条向量；本仓探针规模远达不到）。 */
const MILVUS_MAX_PAGES = 20

/**
 * Milvus 里**出现过的全部 `kb_id`**（去重）。
 *
 * <h3>🔴 必须翻页，不能一次 query 完</h3>
 * <p>`entities/query` 单次上限 {@link MILVUS_PAGE} 行。只取一页的话，当一个库的向量
 * 超过这个数，**后面那些 `kb_id` 根本没进过输入** —— 而现象是"扫描跑成功了、
 * 一个孤儿都没找到"。这正是 `#149` 那一族：**闸覆盖了一类产物、另一类不在它的输入里**；
 * 这里更隐蔽 —— 不是"少了一类"，是"**同一类少了一半**"。
 *
 * <h3>为什么不能只看分区名（`T-O27` 的判据原文）</h3>
 * <p>分区是**容器**：`entities/delete` 删点不删容器（`milvus:partitions` 那条登记里写着）。
 * 于是"分区都在"与"向量都在"是两件事 —— 反过来，"没有孤儿分区"也**推不出**
 * "没有孤儿向量"。判据必须以**条数**为准，分区名只作旁证。
 *
 * @returns {{ids: number[], truncated: boolean}} `truncated=true` ⇒ 输入不完整，**结论不可信**
 */
export function milvusDistinctKbIds() {
  const seen = new Set()
  for (let page = 0; page < MILVUS_MAX_PAGES; page++) {
    const r = milvusPost('entities/query', {
      collectionName: COLLECTION,
      filter: 'kb_id >= 0',
      outputFields: ['kb_id'],
      limit: MILVUS_PAGE,
      offset: page * MILVUS_PAGE,
    })
    const rows = r?.data
    if (!Array.isArray(rows)) {
      // 查询失败 ⇒ 这是"判不了"，必须与"判否"分开报（否则会被读成"没有孤儿"）
      throw new Error(`Milvus 扫 kb_id 失败：${JSON.stringify(r).slice(0, 300)}`)
    }
    for (const row of rows) {
      const id = Number(row?.kb_id)
      if (Number.isFinite(id)) seen.add(id)
    }
    if (rows.length < MILVUS_PAGE) return { ids: [...seen], truncated: false }
  }
  return { ids: [...seen], truncated: true }
}

/** 单个 kb 在 Milvus 里的向量条数（与 `milvusVectorCount` 同一个观测点：`count(*)`）。 */
export function milvusCountOfKb(kbId) {
  const r = milvusPost('entities/query', {
    collectionName: COLLECTION,
    filter: `kb_id == ${Number(kbId)}`,
    outputFields: ['count(*)'],
  })
  const n = Number(r?.data?.[0]?.['count(*)'] ?? -1)
  return Number.isFinite(n) ? n : -1
}

/** MySQL 里**现存**的 kb id 集合（判"孤儿"的那另一半输入）。 */
export function existingKbIds() {
  return new Set(normalizeKeys(mysqlScalar('select id from sys_kb')).map((s) => Number(s)))
}

/**
 * **扫一次孤儿向量**：Milvus 里出现过、而 `sys_kb` 里已经没有的 `kb_id`。
 *
 * <p>它**只算不清**（`sweepOrphanVectors` 才真删）—— 分开是为了让"判据"能独立跑，
 * 也让探针可以在删之前把证据打出来。
 *
 * @returns {{orphans: number[], before: Record<string, number>, truncated: boolean,
 *            totalBefore: number}}
 */
export function findOrphanVectors() {
  const { ids, truncated } = milvusDistinctKbIds()
  const alive = existingKbIds()
  const orphans = ids.filter((id) => !alive.has(id))

  const before = {}
  let totalBefore = 0
  for (const id of orphans) {
    const n = milvusCountOfKb(id)
    before[id] = n
    totalBefore += Math.max(n, 0)
  }
  return { orphans, before, truncated, totalBefore }
}

/**
 * **清掉孤儿向量**，并用**独立观测点**验证真的删掉了。
 *
 * <h3>为什么删完要再数一次（而不是信 `entities/delete` 的返回）</h3>
 * <p>删除的可见性有几秒延迟（`milvusVectorCount` 的注释实测过：`deleteCount=N` 之后
 * 立刻 count 仍是 N）。所以这里**轮询到 0**；轮询不到就是**真失败**，
 * 而不是"删了但没看见"。
 *
 * <p>⚠️ 判定用的是 `count(*)` 之后**再查一次 `kb_id`**（两个观测点）：
 * 只信 `deleteCount` 就等于拿"调用方说删了"当证据 ——
 * 本仓那条「SDK 调用成功不等于业务事实已成立」正是这个形状。
 *
 * @returns {{before: Record<string, number>, after: Record<string, number>,
 *            swept: number[], failed: number[], totalBefore: number, totalAfter: number}}
 */
export async function sweepOrphanVectors({ dryRun = false } = {}) {
  const { orphans, before, truncated, totalBefore } = findOrphanVectors()
  if (truncated) {
    // 输入不完整时**不删**：宁可留到下一轮，也不要凭半份输入做不可逆的事
    throw new Error('Milvus 扫 kb_id 撞到翻页上限 ⇒ 输入不完整，本轮**不执行删除**（调大 MILVUS_MAX_PAGES 后重试）')
  }
  if (dryRun || orphans.length === 0) {
    return { before, after: before, swept: [], failed: [], totalBefore, totalAfter: totalBefore }
  }

  milvusDeleteVectors(orphans)

  const after = {}
  const swept = []
  const failed = []
  let totalAfter = 0
  for (const id of orphans) {
    for (let i = 0; i < 6; i++) {
      const n = milvusCountOfKb(id)
      if (n === 0) break
      if (i < 5) await sleep(2000)
    }
    const left = milvusCountOfKb(id)
    after[id] = left
    totalAfter += Math.max(left, 0)
    if (left === 0) swept.push(id)
    else failed.push(id)
  }
  return { before, after, swept, failed, totalBefore, totalAfter }
}

// ---------------------------------------------------------------------------
// 闸 ①：覆盖率
// ---------------------------------------------------------------------------

/**
 * **覆盖率闸**：带 `kb_id`/`doc_id`/`user_id` 列的表格，要么已登记、要么显式豁免。
 *
 * <p>⚠️ **必须在脚本开头调用，不能放在 finally 里** —— 放 finally 会导致
 * 「闸抛错 → 清理不执行 → 反而制造残留」。**失败要发生在建探针之前。**
 *
 * @returns {{scanned:number, registered:number, exempt:number, tables:string[]}}
 * @throws 发现未登记的产物表时
 */
export function assertArtifactCoverage() {
  const raw = mysqlScalar(
    `select table_name, group_concat(distinct column_name order by column_name) ` +
    `from information_schema.columns ` +
    `where table_schema = database() and (${SCOPE_PATTERNS.map((p) => `column_name like '${p}'`).join(' or ')}) ` +
    `group by table_name order by table_name;`,
  )
  const registered = new Set([
    ...KB_ARTIFACTS.filter((a) => a.kind === 'sql').map((a) => a.table),
    ...USER_ARTIFACTS.map((a) => a.table),
  ])
  const exempt = new Set(OUT_OF_SCOPE.map((x) => x.table))

  const tables = raw.split('\n').map((l) => l.trim()).filter(Boolean)
  const unknown = tables
    .map((l) => l.split('\t'))
    .filter(([t]) => t && !registered.has(t) && !exempt.has(t))
    .map(([t, cols]) => `${t}(${cols ?? '?'})`)

  if (unknown.length > 0) {
    throw new Error(
      `产物覆盖率闸未通过：以下表带 ${SCOPE_PATTERNS.join(' / ')} 之类的归属列但**未登记** —— ${unknown.join(' · ')}。`
      + '处置：到 ai_knowledge_app/scripts/lib/probe-teardown.mjs 的 KB_ARTIFACTS / USER_ARTIFACTS '
      + '加一行；若它本就不该被清（例：只增不删的日志表），加到 OUT_OF_SCOPE 并**写明理由**。'
      + `（已登记 ${registered.size} 张 / 显式豁免 ${exempt.size} 张）`,
    )
  }
  return { scanned: tables.length, registered: registered.size, exempt: exempt.size, tables: tables.map((l) => l.split('\t')[0]) }
}

// ---------------------------------------------------------------------------
// 清 理
// ---------------------------------------------------------------------------

/** 探针库的 id 列表（**必须在删 `sys_kb` 行之前取**，否则再也定位不到 Milvus 侧）。 */
export const kbIdsOf = (code) => normalizeKeys(mysqlScalar(`select id from sys_kb where code='${code}';`))

/**
 * 让该探针库的**解析作业停下来**（`S4-E1` 新增）。
 *
 * <h3>⚠️ 为什么这是必须的，而不是"顺手多加一步"</h3>
 * `T-O01` 把解析搬进了后台 worker，于是出现了一个**清理与执行赛跑**的新形态：
 * 清理把探针库删干净的那一刻，worker 可能正拿着这个库的解析作业在跑 ——
 * 它跑完之后会**继续往这个已经不存在的库里写**（`sys_chunk` 行、Milvus 代分区）。
 * 实测现象：4 个 E2E 全绿、收尾判据也全绿（那一刻确实干净），**几分钟后**回头看，
 * 库里多了 6 行孤儿块、Milvus 里多了 2 个空分区。
 *
 * <p>这比"清理漏了一处"更难发现 —— **残留自检本身是对的，只是它跑在竞态之前**。
 * 所以修法不是"再查一遍"，而是把顺序改成：**先让写入方停手，再清理**。
 *
 * <h3>两步各自能做什么、不能做什么</h3>
 * <ul>
 *   <li><b>PENDING</b> → 直接判死（还没被领走，改状态即可，worker 不会再领它）；</li>
 *   <li><b>RUNNING</b> → **只能等**。同 JVM 里没有安全的手段强杀一个正在跑的解析线程
 *       （这与"不拆进程"是同一个限制，见 `ai_knowledge_web/plan/2026-09-17-S4-E1.md` §9）。
 *       等不到就**照实把它报出来**，交给残留自检去红 —— 不静默。</li>
 * </ul>
 */
async function quiesceParseJobs(code) {
  const cancelled = mysqlCount(
    `select count(*) from sys_async_task a join sys_kb k on k.id = a.kb_id `
    + `where k.code='${code}' and a.type='doc-parse' and a.status='PENDING';`)
  if (cancelled > 0) {
    mysqlScalar(
      `update sys_async_task a join sys_kb k on k.id = a.kb_id `
      + `set a.status='FAILED', a.stage='失败', a.error_msg='探针清理：作业已取消', a.finished_at=now(), a.updated_at=now() `
      + `where k.code='${code}' and a.type='doc-parse' and a.status='PENDING';`)
  }
  const runningSql =
    `select count(*) from sys_async_task a join sys_kb k on k.id = a.kb_id `
    + `where k.code='${code}' and a.type='doc-parse' and a.status='RUNNING';`
  for (let i = 0; i < 30; i++) {
    if (mysqlCount(runningSql) === 0) {
      return { cancelled, waitedMs: i * 2000 }
    }
    await sleep(2000)
  }
  // 等不到就说出来 —— 而且**不只是说出来**：这个 `timedOut` 会一路传到
  // `kbResidueClean`，把调用方的收尾判据**变红**（`#187` 修的就是"只 warn 不红"）。
  console.warn(`⚠️ 探针库 ${code} 仍有解析作业在跑（等了 60s）—— 明细见收尾判据（它会报红）`)
  return { cancelled, waitedMs: 60000, timedOut: true }
}

/** 探针库在 MinIO 里的对象名（`storage_path` / `parse_path`）—— 同样**必须在删行前取**。 */
function minioKeysOf(code) {
  const keys = []
  for (const a of KB_ARTIFACTS.filter((x) => x.kind === 'minio')) {
    const raw = mysqlScalar(
      `select d.${a.column} from sys_document d join sys_kb k on k.id = d.kb_id ` +
      `where k.code='${code}' and d.${a.column} is not null and d.${a.column} <> '';`,
    )
    keys.push(...normalizeKeys(raw))
  }
  return keys
}

/**
 * **幂等**清理一个探针库及其全部产物。脚本**开头与结尾各调一次**。
 *
 * <p>顺序由 {@link KB_ARTIFACTS} 决定，且跨存储三类必须排在 SQL 之前（理由见那边注释）。
 *
 * @returns {Promise<{code:string, ids:string[], minioKeys:string[], removed:object, residue:object}>}
 */
/**
 * **无主通知兜底**（`S6-M11` · `T-B03` 的外溢面）。
 *
 * <h3>为什么它进不了上面两张登记表的循环</h3>
 * `sys_notice` 的归属**不是 `kb_id`**（通知没有知识库维度）⇒ 进不了 `KB_ARTIFACTS`；
 * 而它按 `via: 'user'` 只能删到**探针账号**名下那些 —— E2E 脚本用的是**种子超管 `admin`**，
 * 那些通知的 `owner_id` 永远有效，`user_id in (...)` 够不着。
 * 于是只能问第三根线：**产出它的任务行还在不在**。
 *
 * <p>⚠️ 这与 Java 侧 `ProbeCleanup#sweepOrphanNotices` 是**同一件事的两份实现**
 * （一个 JS 一个 Java，两侧刻意不合并，只对账 —— 见 `ProbeArtifacts` 的类注释）。
 * 改判据时**两边都要改**。
 *
 * <p>⚠️ 顺序**固定**：先清"人没了"的读行 → 再删主表 → 再清"通知没了"的读行
 * （上一句刚制造出来的那批）。顺序反了会漏一轮。
 *
 * @returns {number} 删掉的通知行数
 */
/**
 * 无主未命中问题（`T-A00` · `S8-M10-A` 新增）。
 *
 * <p>与 `sweepOrphanNotices` 同一条来历：`MissSink` 接线之后，**凡是走真 HTTP 提问
 * 且被判未命中的脚本都会往池子里写** —— 而那是打点那条路旁路写的，不在任何脚本的清理代码里。
 *
 * <p>⚠️ 归属只有 `last_user_id` 这一条线索（列名**不是** `user_id`，因为它是聚合行），
 * 所以覆盖率闸看不见这张表 —— 它靠的是 `USER_ARTIFACTS` 里的**手工登记**。
 * ⚠️ **顺序**：排在用户族清理**之后**（人先没了，行才变无主）。
 */
export function sweepOrphanMisses() {
  const before = mysqlCount('select count(*) from sys_qa_miss;')
  mysqlScalar(
    'delete from sys_qa_miss where last_user_id is not null'
    + ' and last_user_id not in (select id from sys_user);',
  )
  return before - mysqlCount('select count(*) from sys_qa_miss;')
}

/**
 * **无主产物兜底**（`T-O28` 那一族的脚本侧半边 · `S7-M06-F` 补）。
 *
 * <h3>它补的是哪个洞（实测）</h3>
 * <p>本文件其余所有清理路径都**从 `sys_kb` 读 id**（{@link teardownKbProbe} 的 `kbIdsOf`、
 * {@link sweepAbandonedKbProbes} 的 `group_concat(code)`）。于是**只要父行先没了，
 * 它们就一行都删不到** —— 而"父行先没了"在本仓是常态：
 * 产品代码的`彻底删除 / 清空回收站`自己就会把 `sys_kb` 行删掉（那正是被测行为）。
 * <p>🔴 这与 `#136` 是**同一个形状的第三次复现**（前两次是 `join sys_kb` 的自检与
 * 删除各自恒返回 0）：**删除与自检都依赖同一个会被删掉的父行，它们就永远是同一句话**。
 * <p><b>实测</b>：本批几次被中断的 E2E 轮次之后，库里躺着
 * `sys_async_task` **18 行** · `sys_chunk` **4 行** · `sys_download_record` **3 行**
 * —— 它们的 `kb_id` 指向一个已经不存在的库，于是**谁也认领不了**，
 * 只有 `findKbProbeResidue` 的 `无主残留=N` 一直在报（而它**刻意不计入** `kbResidueClean`，
 * 所以每一轮的收尾都照样全绿）。
 *
 * <h3>问法与 Java 侧 `ProbeCleanup.sweepOrphanKbArtifacts` 完全一致</h3>
 * <p>不问"你清了没有"，只问"**库里还剩不剩无主的东西**"。
 * 于是"漏一处"的代价从"永久累积"变成"多一次 DELETE"。
 * <p>⚠️ `not in (select id from sys_kb)` 里的子查询读的是 `sys_kb`，
 * 与删除目标**不同表** ⇒ 不会撞 MySQL 的 1093。
 * ⚠️ **顺序**：必须排在"删 kb 行"之后、`sweepOrphanNotices` 之前 ——
 * 删掉任务行之后，那些任务产的通知才**变成**无主。
 *
 * @returns 删掉的行数（> 0 说明有类漏清了 —— 这是**信号**，不是正常现象）
 */
export function sweepOrphanKbRows() {
  const count = () => mysqlCount(
    'select (select count(*) from sys_async_task where kb_id is not null and kb_id not in (select id from sys_kb))'
    + ' + (select count(*) from sys_async_task where doc_id is not null and doc_id not in (select id from sys_document))'
    + ' + (select count(*) from sys_chunk where kb_id is not null and kb_id not in (select id from sys_kb))'
    + ' + (select count(*) from sys_chunk where doc_id is not null and doc_id not in (select id from sys_document))'
    + ' + (select count(*) from sys_parse_issue where kb_id is not null and kb_id not in (select id from sys_kb))'
    + ' + (select count(*) from sys_document where kb_id is not null and kb_id not in (select id from sys_kb))'
    + ' + (select count(*) from sys_download_record where kb_id is not null and kb_id not in (select id from sys_kb))'
    + ' + (select count(*) from sys_kb_grant where kb_id is not null and kb_id not in (select id from sys_kb));',
  )
  const before = count()
  mysqlScalar(
    'delete from sys_async_task where kb_id is not null and kb_id not in (select id from sys_kb);'
    + 'delete from sys_async_task where doc_id is not null and doc_id not in (select id from sys_document);'
    + 'delete from sys_chunk where kb_id is not null and kb_id not in (select id from sys_kb);'
    + 'delete from sys_chunk where doc_id is not null and doc_id not in (select id from sys_document);'
    + 'delete from sys_parse_issue where kb_id is not null and kb_id not in (select id from sys_kb);'
    + 'delete from sys_document where kb_id is not null and kb_id not in (select id from sys_kb);'
    + 'delete from sys_download_record where kb_id is not null and kb_id not in (select id from sys_kb);'
    + 'delete from sys_kb_grant where kb_id is not null and kb_id not in (select id from sys_kb);',
  )
  return before - count()
}

export function sweepOrphanNotices() {
  const before = mysqlCount('select count(*) from sys_notice;')
  mysqlScalar(
    'delete from sys_notice_read where user_id not in (select id from sys_user);'
    + 'delete from sys_notice where (source_task_id is not null and source_task_id not in (select task_id from sys_async_task))'
    + ' or (user_id is not null and user_id not in (select id from sys_user));'
    + 'delete from sys_notice_read where notice_id not in (select id from sys_notice);'
    + 'delete from sys_notice_setting where user_id not in (select id from sys_user);',
  )
  return before - mysqlCount('select count(*) from sys_notice;')
}

/**
 * 无主会话族（`T-O23` · `S7-M06-B` 立）。
 *
 * <h3>它补的是哪个洞</h3>
 * <p>三个 E2E 脚本的会话族清理**按水位线**（`coalesce(max(id),0)` 起算），也就是
 * **只保护"本轮造的"**。于是一旦某一轮因任何原因没跑到收尾（被打断 / 抛错 / 被强杀），
 * 那一轮的会话族就**永远没人认领** —— 后面每一轮的水位线都在它上面，谁都够不着。
 * <b>实测</b>：`S7-M06-B` 开工核基线时库里躺着 1 会话 + 2 消息 + 1 打点 + 5 明细，
 * 来自**上一批 O 轨的证伪实验**，而上一批的收尾写着"残留全 0"。
 *
 * <h3>谓词：**该会话的每一条打点都无主**</h3>
 * <p>「无主」= 这条打点引用的分片**在 `sys_chunk` 里一个都不剩**。这是本仓既有的
 * 「无主残留」口径（`sweepOrphanNotices` / `sweepOrphanMisses` 同源），
 * 判据是**与归属无关的观测点**（"它还挂得上父行吗"），而不是"谁清的"。
 *
 * <p>🔴 **为什么要求"每一条"都无主**，而不是"存在一条无主就删"：
 * 真实用户的会话里若**只有**某一条打点引用的文档被彻底删了，那个会话**不该**被清 ——
 * 他其余的问答还指着活着的文档。要求**全部**无主，等价于"整个会话说的都是已经不存在的内容"，
 * 此时它引用的分片早就悬空，删它不损失任何还能取到的信息。
 *
 * <p>⚠️ **顺序有要求**：会话那一步必须**在删明细之前**跑（它要读"分片还在不在"的**当时**状态）。
 * 先删明细的话，每条打点都会变成"一条明细都不剩"，判据就退化成"清空全部打点"。
 */
export function sweepOrphanChatFamily() {
  const count = () => mysqlCount('select count(*) from sys_chat_session;')
    + mysqlCount('select count(*) from sys_chat_message;')
    + mysqlCount('select count(*) from sys_retrieval_trace;')
    + mysqlCount('select count(*) from sys_retrieval_trace_chunk;')
  const before = count()

  // "每一条打点都无主"的会话 —— 同一段子查询在下面三处复用（**必须读删除前的状态**）
  const orphanSession = 'select t.session_id from sys_retrieval_trace t'
    + ' where t.session_id is not null'
    + '   and exists (select 1 from sys_retrieval_trace_chunk c where c.trace_id = t.trace_id)'
    + '   and not exists (select 1 from sys_retrieval_trace_chunk c'
    + '                    join sys_chunk k on k.id = c.chunk_id where c.trace_id = t.trace_id)'

  mysqlScalar(
    `delete from sys_chat_message where session_id in (${orphanSession});`
    + `delete from sys_chat_session where id in (${orphanSession});`,
  )
  // 悬空明细 → 悬空打点（自上而下，与外溢面同一条顺序纪律）
  mysqlScalar('delete from sys_retrieval_trace_chunk where chunk_id not in (select id from sys_chunk);')
  mysqlScalar('delete from sys_retrieval_trace where trace_id not in'
    + ' (select trace_id from sys_retrieval_trace_chunk);')
  return before - count()
}

/**
 * 兜底：把**被中断的轮次**留下的探针库一并收掉（`T-O23` 的第二种形态 · `S7-M06-B` 立）。
 *
 * <h3>它补的是哪个洞</h3>
 * <p>每个脚本收尾时只清**自己那两个 `code`**（那是刻意的：射程要精确）。于是一旦某一轮
 * 没跑到 `finally`（被打断、被强杀、CDP 挂死），它建的探针库就**永远没人认领** ——
 * 后面每一轮都不认识那些 code。<b>实测</b>：`S7-M06-B` 施工期间被 `TaskStop` 打断过三次，
 * 库里于是躺着 **9 个探针库 + 15 条分片 + 4 个会话**，而每一轮的收尾都报"残留 = 0"
 * （它只报自己那一份，且**它说的没错**）。
 *
 * <h3>为什么敢按前缀删</h3>
 * <p>与 {@link teardownUserProbe} 同一条口径：**前缀是探针自己取的**（`kb_e2e*` 这种
 * 人类不会用的名字），所以"命中前缀"本身就是归属证据。⚠️ 前缀必须**逐字登记在调用方**
 * （不要用 `like '%probe%'` 这种宽匹配 —— 那会误伤 `hybrid-probe` 之外的合法库）。
 *
 * @param prefixes 探针库 code 的**前缀**列表（调用方自己的 + 同族脚本的）
 * @returns 收掉的库的 code 列表
 */
export async function sweepAbandonedKbProbes(prefixes) {
  const where = prefixes.map((p) => `code like '${p}%'`).join(' or ')
  if (!where) return []
  // 🔴 **`group_concat` 一行都没匹配到时返回 SQL NULL**，而 `mysql -N` 把它打成
  //    **字面量字符串 `"NULL"`** ⇒ 下面的 `.filter(Boolean)` 会把它当成一个**真实的库 code** 留下来
  //    ⇒ 收尾日志打出「收掉上一轮留下的探针库 — 1 个：NULL」（本批实测就是这么打的）。
  //    后果不重（`teardownKbProbe('NULL')` 一行也删不到），但它**让台账与事实脱钩** ——
  //    而那正是本仓反复吃亏的那个形状（另一处 `normalizeKeys` 就滤了 `'NULL'`，
  //    两处口径不一致正是漏洞的来源）
  const csv = String(mysqlScalar(`select group_concat(code) from sys_kb where ${where};`) || '')
  const codes = csv.split(',').map((s) => s.trim()).filter((s) => s && s !== 'NULL')
  for (const code of codes) {
    await teardownKbProbe(code)
  }
  return codes
}

export async function teardownKbProbe(code) {
  // ⓪ `S4-E1` 起多了一步：**先让解析作业停下来**，否则它们是"删完之后还在写"的源头
  // 🔴 `#187`：**必须接住它的返回值**。原写法是 `await quiesceParseJobs(code)` ——
  //    返回值被整个丢掉，于是"等不到"这件事只留在一条 `console.warn` 里，
  //    调用方的收尾判据**照样全绿**。这正是"没静默"那句承诺没兑现的地方：
  //    信息**存在**，但在传递的路上被扔了。
  const quiesce = await quiesceParseJobs(code)

  // ① 快照 —— 删行之后就取不到了
  const ids = kbIdsOf(code)
  const minioKeys = minioKeysOf(code)

  // ② MinIO 对象
  const minioRemoved = minioRemove(minioKeys)

  // ③ Milvus：先删点，再删容器（顺序固定）
  milvusDeleteVectors(ids)
  milvusDropPartitions(ids)

  // ④ SQL：子表 → 主表（一条语句跑完，减少往返）
  //
  // ⚠️ 归属列是 `kb_id`/`doc_id` 的那些表**改用 ① 抓到的 ids 直删**，不再 `join sys_kb`。
  // 原来的写法看着没问题（join 在删 kb 之前执行），但它把「能不能删到」绑在
  // `sys_kb` 行还在不在上 —— 而**残留自检**那一步 `sys_kb` 必然已经没了，
  // 于是同一个 join 在那边**恒不命中 ⇒ 恒返回 0**（`#136`）。
  // 删与查必须用**同一份 ids**，才能保证"删了什么"与"查了什么"是同一个集合。
  const idList = ids.join(',')
  mysqlScalar(
    KB_ARTIFACTS.filter((a) => a.kind === 'sql')
      .map((a) => {
        if (a.via === 'code') return `delete from ${a.table} where code='${code}';`
        // 探针库压根没建过（teardown 幂等重入）—— 没有行可删，也不该拼出 `in ()`
        if (idList === '') return ''
        return `delete from ${a.table} where ${a.via} in (${idList});`
      })
      .reduce((acc, s) => acc + s, ''),
  )

  // ④.3 无主分区（`T-O28` 那一族的第三件）—— 与"无主行"同一条来历：
  //      某一轮的收尾没跑到，它留下的分区就再也没人认领（后一轮不认识那个 kbId）。
  const orphanPartitions = sweepOrphanPartitions()

  // ④.4 无主行（`#136` 形状的第三次复现）—— **必须排在 ④ 之后**：
  //      ④ 刚把探针库的行删掉，而**产品代码自己也可能删过库行**（彻底删除 / 清空回收站），
  //      那一路留下的子行只有这一步收得掉。
  const orphanRows = sweepOrphanKbRows()

  // ④.5 无主通知（`T-B03` 的外溢面）—— **必须排在 ④ 之后**：
  //    ④ 刚把任务行删掉，通知**在这一刻才变成**无主。顺序反了要等下一轮才扫得到。
  const noticesRemoved = sweepOrphanNotices()

  // ④.6 无主未命中问题（`T-A00` 的外溢面）—— 与 ④.5 同理由、同顺序要求。
  const missesRemoved = sweepOrphanMisses()

  // ④.7 无主会话族（`T-O23`）—— **必须排在 ④ 之后**：④ 刚把探针库的分片删掉，
  //     那些打点**在这一刻才变成**无主。顺序反了要等下一轮才扫得到。
  //     ⚠️ 它清的不只是"本轮探针库"的会话族，还包括**前几轮异常退出留下的**
  //     （水位线够不着的那批）—— 那正是这个函数存在的理由。
  const chatRemoved = sweepOrphanChatFamily()

  // ⑤ 残留自检 —— 独立观测点
  const residue = await findKbProbeResidue(code, { ids, minioKeys, quiesceTimedOut: !!quiesce.timedOut })

  // ⑥ 🔴 `T-O30`：**收尾做完之后**才记水位。
  //
  //    顺序是判据的一部分：`findKbProbeResidue` 读水位、这里写水位。
  //    反过来的话，"刚被兜底清掉的那批"会被写成新基线 ——
  //    于是下一轮真新增时看起来是平的（**把红抹掉**）。
  //
  //    ⚠️ 只记 `orphanTotal`（存量），**不记 `sqlTotal` 那些本轮的** ——
  //    后者本来就该是 0，记它没有意义（它一非 0 就是红，不需要水位）。
  const watermark = writeWatermark(residue.orphanTotal)

  return {
    code, ids, minioKeys,
    removed: {
      minio: minioRemoved, sql: '见 residue', notices: noticesRemoved, misses: missesRemoved,
      chatFamily: chatRemoved, orphanRows, orphanPartitions,
    },
    residue, quiesce, watermark,
  }
}

/** 用户族探针（`e2e-audit-backfill` 那类）。幂等。 */
export function teardownUserProbe(prefix) {
  // 🔴 常驻设施 probe.profile **绝不参与任何前缀清理** —— 名字虽以 probe. 开头，但它是 V6 点名的常驻联调账号（128 基线含它）。
  const loginLogs = mysqlCount(`select count(*) from sys_login_log where username like '${prefix}%' and username <> 'probe.profile';`)

  // ⚠️ **必须在删 `sys_user` 之前**把 id 抓下来 —— 与 kb 族是同一条教训（`#136`/`#137`）：
  // 旧写法 `join sys_user u on u.id = t.user_id where u.username like ?`，
  // 而 `sys_user` 就是这条语句自己要删的表之一 ⇒ 删除与自检都依赖同一行，
  // 自检那边必然恒返回 0。抓成 ids 之后两边用的是**同一个集合**，且不再依赖 `sys_user` 还在。
  const userIds = userIdsOf(prefix)
  const userList = userIds.join(',')

  mysqlScalar(
    USER_ARTIFACTS.filter((a) => a.kind === 'sql-user' || a.kind === 'sql-date').map((a) => {
      // 排除常驻设施 probe.profile（与 userIdsOf / loginLogs 同一保护）
      if (a.via === 'username') return `delete from ${a.table} where username like '${prefix}%' and username <> 'probe.profile';`
      // `via: 'date'` 与用户无关，**必须排在上面那条 `userList === ''` 之前** ——
      // 否则"探针账号压根没建过"时会把它一起跳过，而当天的用量行照样留着
      // （那种漏最隐蔽：清理函数看着跑了，日报里却是探针的数字）
      if (a.via === 'date') return a.deleteSql
      // 探针账号压根没建过（幂等重入）—— 没有行可删，也不该拼出 `in ()`
      if (userList === '') return ''
      if (a.via === 'user') return `delete from ${a.table} where user_id in (${userList});`
      // 子表没有 user_id，顺着父表找。⚠️ 它仍必须排在父表**之前**（顺序即执行顺序）
      if (a.via === 'subquery') return a.deleteSql.replaceAll('{userIds}', userList)
      throw new Error(`USER_ARTIFACTS 里 ${a.table} 的 via 取值无法处理：${a.via}`)
    }).reduce((acc, s) => acc + s, ''),
  )
  // 🔴 `#163`：**桶里那批也要收** —— 它们不挂在任何探针库下面（`minioKeysOf` 要 join
  //    `sys_kb`，看不见它们），归属是 `chat-attachments/{userId}/…` 里的 userId。
  //    此前这里只删行不删对象 ⇒ 每轮在桶里多一个。
  //    ⚠️ 快照要在**删对象之前**取，否则"还在吗"这个观察点永远为空（恒真守卫那一族）。
  const minioKeys = minioKeysOfUserPrefix(userIds)
  const minioRemoved = minioRemove(minioKeys)

  return {
    prefix, loginLogs, userIds, minioKeys, minioRemoved,
    residue: findUserProbeResidue(prefix, { userIds, minioKeys }),
  }
}

/**
 * 探针账号在清理**前**的 id 集合。
 *
 * <p>⚠️ 删完 `sys_user` 再查就永远是空的 —— 而"空"与"真的没有"看起来一模一样。
 * 这是本批修的那条教训在用户族的同一个形状。
 */
export function userIdsOf(prefix) {
  return normalizeKeys(mysqlScalar(`select id from sys_user where username like '${prefix}%' and username <> 'probe.profile';`))
}

// ---------------------------------------------------------------------------
// 🔴 只清**归属行**，不动账号本身（`T-O19` · 2026-09-20）
// ---------------------------------------------------------------------------

/**
 * 可以做"只清行、不动账号"的那几项 —— **手写白名单，不从登记表推导**。
 *
 * <h3>🔴 为什么是白名单而不是 `USER_ARTIFACTS.filter(via in ...)`</h3>
 * <p><b>第一版就是从登记表推导的，当天就出了事故</b>：推导出来的射程包含
 * `sql:sys_user_role`（`via: 'user'`）⇒ `delete from sys_user_role where user_id = 1`
 * —— 把**种子超管 `admin` 的角色删光了**，下一个 E2E 立刻报「建库：无操作权限」
 * （`10020`），而现象像"权限配错了"。
 *
 * <p>根因是**两份清单的用途不同**，而它们的形状看起来一样：
 * <ul>
 *   <li>{@link USER_ARTIFACTS} 服务的是「**这个探针账号马上就要被删掉**」——
 *       那时连 `sys_user_role` 一起清是**对的**（人都不在了，留着授权是脏数据）；</li>
 *   <li>本函数服务的是「**这个账号还要继续用**」（`e2e-*` 用的是**种子**超管）——
 *       同样的表清掉就是**破坏**。授权表尤其：删掉它账号就变哑巴。</li>
 * </ul>
 * ⇒ **射程必须逐条表态**，而"能推导"正是它危险的地方（推导会把新登记项自动吸收进来，
 * 包括那些不该吸收的）。这与本仓 `ProbeArtifacts.NO_OWNERSHIP_COLUMN` 台账同一条纪律：
 * **新增产物必须过一次人的判断。**
 *
 * <p>本名单里每一项都写清楚"为什么它对种子账号也是安全的"。
 */
const USER_SCOPED = [
  {
    // 聚合表（行数上界 = 天数 × 模型数 × 用途数，不随调用次数增长），
    // 且**一个归属列都没有** ⇒ 只能按日期清。对种子账号同样安全：它清的是"用量统计"，
    // 不是账号的任何能力。⚠️ 代价是当天的真实用量一并清掉（`#184` 已登记）。
    entry: USER_ARTIFACTS.find((a) => a.id === 'sql:sys_model_usage'),
    why: '模型用量：按日期清，聚合表，不碰账号能力',
  },
  {
    entry: USER_ARTIFACTS.find((a) => a.id === 'sql:sys_qa_miss'),
    why: '未命中池：按 `last_user_id` 清。⚠️ 种子账号名下的池子行**只可能是本脚本造的**'
      + '（真实用户提问沉淀的行挂在真实用户 id 上）',
  },
  {
    entry: USER_ARTIFACTS.find((a) => a.id === 'sql:sys_ops_retrieval_daily'),
    why: '检索质量按天汇总（`T-A04`）：按日期清。对种子账号安全的理由比 `sys_model_usage` **更强** ——'
      + '它是**纯缓存**（源是 `sys_retrieval_trace`，整张删掉都能重算出来）'
      + '⇒ 清它**没有信息损失**，也不碰账号的任何能力（`sys_user_role` 不在射程里）',
  },
  {
    entry: USER_ARTIFACTS.find((a) => a.id === 'sql:sys_ops_usage_daily'),
    why: '使用统计按天汇总（`T-A04`）：同上 —— 纯缓存，按日期清，无信息损失',
  },
].filter((x) => x.entry)

/**
 * 清这几张表里挂在 {@code userId} 名下的行，**不动 `sys_user`，也不动任何授权行**。
 *
 * <h3>它存在的理由（`T-O19`）</h3>
 * <p>本仓有三个 E2E 脚本**自己做清理**（`e2e-chat-flow` / `e2e-ops-miss-flow` /
 * `e2e-ops-replay-flow` / 以及本批新增的 `e2e-ops-qa-stats`）：它们按**会话 / 打点水位线**
 * 清会话族。而 `sys_model_usage`（按日期）与 `sys_qa_miss`（按 `last_user_id`）
 * **只登记在 `USER_ARTIFACTS` 里** ⇒ 它们**一行都清不到**。
 * <p>🔴 而**恰恰是这些脚本在造那些行**：`e2e-chat-flow` 是唯一真读
 * `sum(calls) from sys_model_usage` 的脚本（也就是说它真的在写这张表），
 * 而它真提问 ⇒ 未命中时 `MissSink` 会往池子里写行。
 * ⇒ **清理函数存在、调用方存在，但"会造行的脚本"不在调用方里** ——
 * 这是「清理必须走必走路径」的**第四种形态**，前三种是 `#186`（`step()` 一抛就不执行）·
 * `#187`（`await` 的返回值被丢）· 本仓验证脚本的 `process.exit()` 早退。
 *
 * <p><b>为什么不直接调 {@link teardownUserProbe}</b>：那个函数会把**账号本身**删掉，
 * 而这些脚本用的是**种子超管**账号 —— 调它等于把种子删了。
 *
 * @param userId 要清的账号 id
 * @return 实际执行的 SQL（供脚本打印，便于"这次到底清了什么"可追溯）
 */
export function purgeUserScopedArtifacts(userId) {
  const id = Number(userId)
  if (!id) {
    return ''
  }
  // 🔴 **给未来的自己上一道闸**：授权表绝不该出现在这个射程里。
  // 加了它就意味着"清一次探针残留"会把一个**还在用**的账号变成哑巴 ——
  // 而现象（接口报「无操作权限」`10020`）看起来像权限配错了，不像清理的锅。
  // ⇒ 与其靠注释提醒，不如让它在**执行前**就炸（本仓对"只写 javadoc 拦不住"有判例）。
  const forbidden = USER_SCOPED.filter(({ entry }) => entry.table === 'sys_user_role')
  if (forbidden.length) {
    throw new Error(
      'purgeUserScopedArtifacts 的射程里出现了 sys_user_role —— 那会把账号的授权删掉，'
        + '而被清的账号**还要继续用**（脚本用的是种子账号）。见本函数的 javadoc：'
        + '第一版从登记表推导射程时正是这么出的事故。',
    )
  }
  const sql = USER_SCOPED.map(({ entry }) => {
    if (entry.via === 'date') {
      return entry.deleteSql
    }
    return entry.deleteSql.replaceAll('{userIds}', String(id))
  }).reduce((acc, s) => acc + s, '')
  if (sql) {
    mysqlScalar(sql)
  }
  return sql
}

/**
 * 上面那几项的**残留计数**（独立观测点：不看清理命令的返回值）。
 *
 * <p>🔴 **只数「能归属到人」的那些**：`sys_model_usage` **一个归属列都没有**
 * （它的 `why` 里写着这件事），所以它**结构上数不出"是谁造的"** ——
 * 拿它的当日行数当残留会让每一个有真实用量的日子都报红。
 * 不数它，并在返回值里**显式说明**（静默少一类正是这个仓库反复吃亏的形状）。
 */
export function countUserScopedArtifacts(userId) {
  const id = Number(userId)
  if (!id) {
    return { rows: 0, attributable: [], notAttributable: [] }
  }
  const attributable = USER_SCOPED.filter(({ entry }) => entry.via !== 'date')
  const notAttributable = USER_SCOPED.filter(({ entry }) => entry.via === 'date')
  const rows = attributable
    .map(({ entry }) => Number(mysqlScalar(entry.countSql.replaceAll('{userIds}', String(id)))) || 0)
    .reduce((acc, n) => acc + n, 0)
  return {
    rows,
    attributable: attributable.map(({ entry }) => entry.table),
    notAttributable: notAttributable.map(({ entry }) => entry.table),
  }
}


// ---------------------------------------------------------------------------
// 闸 ②：残留自检（**独立观测点**，不信清理命令的返回值）
// ---------------------------------------------------------------------------

/**
 * 探针库是否还有残留。**五类**独立观测点：SQL 行 / **无主残留** / Milvus 向量 / Milvus 分区 / MinIO 对象。
 *
 * <h3>⚠️ 这一版修的是一个「假绿」（`#136`）</h3>
 * <p>旧写法是 `join sys_kb k on k.id = t.kb_id where k.code=?` —— 看着没问题，
 * 但 `sys_kb` 那一行**被同一轮清理删掉了**（`KB_ARTIFACTS` 里它排最后），
 * 于是这个 join **永远不命中**，六个表**恒返回 0**。
 * 实测反例：自检报「库行=0 文档=0 分片=0 …」的同时，`sys_async_task` 里躺着 **634 行**
 * （`kb_id` 全指向已删的库）。**「闸说干净」与「真的干净」是两件事。**
 *
 * <p>修法不是"改个 join 条件"，而是**换一个与归属无关的观测点**（见 {@link #orphans}）：
 * 只要"删除"与"自检"都依赖同一个会被删掉的父行，它们就永远是同一句话。
 *
 * @param code 探针库 code
 * @param snapshot 清理**前**取的 `{ids, minioKeys}`；不给则 `ids` 类计数无意义（见 {@link #idsUsed}）
 */
export async function findKbProbeResidue(code, snapshot = {}) {
  const { ids = [], minioKeys = [], quiesceTimedOut = false } = snapshot

  // ① 按**清理前抓到的 ids** 数 —— 与 `teardownKbProbe` 的删除用的是同一份 ids，
  //    所以"删了什么"与"查了什么"是同一个集合（旧版差的就是这一点）
  const idList = ids.join(',')
  const sql = {}
  for (const a of KB_ARTIFACTS.filter((x) => x.kind === 'sql')) {
    if (a.via === 'code') {
      sql[a.table] = mysqlCount(`select count(*) from ${a.table} where code='${code}';`)
    } else {
      sql[a.table] = idList === '' ? 0
        : mysqlCount(`select count(*) from ${a.table} where ${a.via} in (${idList});`)
    }
  }

  // ② **无主残留**：归属列指向一个**已不存在的父行**的行。
  //
  //    这一类与"这轮探针是谁"无关 —— 它问的是"库里有没有孤儿"，
  //    所以**任何一轮**留下的都会被逮到。这正是旧版白丢掉的观测点：
  //    一旦 `sys_kb` 行被删，按归属数就永远是 0，而孤儿数照样数得出来。
  //
  //    ⚠️ **刻意不计入 `sqlTotal`/`kbResidueClean`**：它跨轮、跨来源，
  //    把它并进去会让"跑完 mvn test 再跑 E2E"变成假红（Java 侧的历史残留会被算到 E2E 头上）。
  //    它的用法是**报警**（`describeKbResidue` 会打出来），以及在自检脚本里被负向锚断言。
  const orphans = {}
  for (const a of KB_ARTIFACTS.filter((x) => x.kind === 'sql' && PARENT_TABLE[x.via])) {
    const parent = PARENT_TABLE[a.via]
    orphans[a.table] = mysqlCount(
      `select count(*) from ${a.table} t left join ${parent} p on p.id = t.${a.via} `
      + `where t.${a.via} is not null and p.id is null;`)
  }
  const orphanTotal = Object.values(orphans).reduce((a, b) => a + b, 0)

  // 🔴 `T-O30`（`S9-M12-A`）：把跨轮水位算进来 —— **存量**与**本轮新增**分开报。
  //
  //    这里**读**水位（不写）：写水位的时机是**收尾做完之后**
  //    （`teardownKbProbe` 的末尾），而本函数是"看一眼还剩什么"。
  //    顺序反了会把"刚被兜底清掉"误读成"这轮本来就是干净的"。
  //
  //    ⚠️ 调用方**可以不接**（`orphanDelta` 缺失 ⇒ `kbResidueClean` 退化）。
  //    那条路径不静默：`describeKbResidue` 打的格式不一样，一眼看得出。
  const orphanDelta = { ...deltaOf(orphanTotal, readWatermark()), byTable: orphans }

  return {
    sql,
    sqlTotal: Object.values(sql).reduce((a, b) => a + b, 0),
    /** 本轮的 ids 抓到了几个 —— **0 表示上表里 `in (ids)` 那几格无从查起**（不是"干净"）。 */
    idsUsed: ids.length,
    orphans,
    orphanTotal,
    /** 存量 / 基线 / **本轮新增** —— 判红看 `added`，见 `kbResidueClean`。 */
    orphanDelta,
    milvusVectors: await milvusVectorCount(ids),
    milvusPartitions: await milvusLeftoverPartitions(ids),
    /** ⚠️ 只有「清理前快照到的那些对象」能这么查 —— 更早的轮次留下的孤儿对象查不到。 */
    minioStillThere: minioStillPresent(minioKeys),
    /**
     * 🔴 **静默等待失败**（`#187`）：清理前没等到解析作业停下来。
     *
     * <p>它的含义**不是**"现在有残留"，而是**"这份残留报告不可信"**：
     * 还在跑的 worker 会在删完之后**继续往已删的库写**，所以此刻数出来的 0
     * 只说明"这一刻是 0"。实测后果是**几分钟后**多出 156 行孤儿分片 + 1 个空分区
     * （`S6-M09-B` 收尾时逮到的那次）。
     *
     * <p>所以它**计入** {@link kbResidueClean} —— 见那里的注释：这条不是"多一个数"，
     * 是把一个"沉默的不可知"变成一条**红判据**。
     */
    quiesceTimedOut,
  }
}

/**
 * 用户族探针是否还有残留。
 *
 * <p>⚠️ `via: 'user'` / `'subquery'` 两类**必须**用清理前抓到的 `userIds` 来数 ——
 * 它们旧版都 `join sys_user`，而 `sys_user` 正是被删的那一行 ⇒ 恒返回 0。
 *
 * @param snapshot 清理**前**取的 `{userIds}`；不给则现查一次（此时若账号已删，只能得到空集 ——
 *                 返回值里用 {@link #idsUsed} 标出来，调用方**不许**把 0 当"干净"）
 */
export function findUserProbeResidue(prefix, snapshot = {}) {
  const userIds = snapshot.userIds ?? userIdsOf(prefix)
  const userList = userIds.join(',')
  const sql = {}
  for (const a of USER_ARTIFACTS) {
    if (a.via === 'username') {
      // 按 username 删的那些**不依赖 sys_user**，所以它任何时候都是真的
      sql[a.table] = mysqlCount(`select count(*) from ${a.table} where username like '${prefix}%';`)
    } else if (a.via === 'date') {
      // `via: 'date'` 同样**不依赖 sys_user** —— 与 username 那一条同理，
      // 任何时候查出来都是真的（而不是"账号没了所以查不到 ⇒ 记 0"）
      sql[a.table] = mysqlCount(a.countSql)
    } else if (userList === '') {
      sql[a.table] = 0
    } else {
      // `via: 'user'` 没有专属 SQL，用通用的一条；`subquery` 自带
      const text = a.countSql ?? `select count(*) from ${a.table} where user_id in ({userIds});`
      sql[a.table] = mysqlCount(text.replaceAll('{userIds}', userList))
    }
  }
  return {
    sql,
    sqlTotal: Object.values(sql).reduce((a, b) => a + b, 0),
    /** 本轮抓到几个用户 id —— **0 表示上面那几格无从查起**（不是"干净"）。 */
    idsUsed: userIds.length,
    /**
     * 🔴 **桶里**还在的那批（`#163`）—— 观察点与上面那些 SQL 计数**独立性最高**：
     * 它根本不经过 MySQL，所以"行删干净了"骗不过它。
     *
     * <p>⚠️ 与 kb 族的 `minioStillThere` 同一条限制：只有**清理前快照到的那些对象**
     * 能这么查 —— 更早的轮次留下的孤儿对象这里查不到（要查得全前缀扫，那是另一件事）。
     */
    minioStillThere: snapshot.minioKeys ? minioStillPresent(snapshot.minioKeys) : [],
  }
}

/** 把 `findKbProbeResidue` 的结果压成一行中文，给脚本的 `check(...)` 描述用。 */
export function describeKbResidue(r) {
  // ⚠️ `无主` 是**跨轮**的数（任何一轮留下的孤儿都算），所以它出现在这一行里是**报警**，
  // 不参与 `kbResidueClean` —— 见 findKbProbeResidue 的注释。
  //
  // 🔴 `T-O30`（`S9-M12-A`）起多两个数：**存量 / 本轮新增**。
  //    在此之前只有一个 `无主残留=N`，而它**从不判红** —— 实测从 7 涨到 25
  //    而每一轮收尾都照绿。现在存量仍不判红（理由不变），但**新增会判红**，
  //    见 `kbResidueClean` 与 `residue-watermark.mjs`。
  const delta = r.orphanDelta
  const orphanLine = delta
    ? `无主残留=${delta.total}（存量基线 ${delta.baseline} · 本轮新增 ${delta.added}${delta.firstRun ? ' · 首次运行' : ''}）`
    : `无主残留=${r.orphanTotal}`
  return `库行=${r.sql.sys_kb} 文档=${r.sql.sys_document} 分片=${r.sql.sys_chunk} `
    + `问题行=${r.sql.sys_parse_issue} 授权=${r.sql.sys_kb_grant} 作业=${r.sql.sys_async_task} · `
    + `${orphanLine} · `
    + `Milvus 向量=${r.milvusVectors} 分区=[${r.milvusPartitions.join(',') || '—'}] `
    + `MinIO 对象=${r.minioStillThere.length}`
    + (r.quiesceTimedOut
      ? ' · 🔴 **解析作业没停下来（等了 60s）—— 这份报告不可信**：'
        + '还在跑的 worker 会在删完之后继续往已删的库写（实测几分钟后多出 156 行孤儿分片 + 1 个空分区）'
      : '')
    + (r.idsUsed === 0 ? ' · ⚠️ 本轮没抓到 kb ids，上述按归属的数**无从查起**（不是"干净"）' : '')
}

/**
 * `findKbProbeResidue` 是否全清。
 *
 * <p>⚠️ **存量的 `orphanTotal` 刻意不在条件里** —— 它是跨轮的，并进来会让"跑完 `mvn test`
 * 再跑 E2E"变成假红（Java 侧的历史残留被算到 E2E 头上）。
 *
 * <p>🔴 <b>`T-O30`（`S9-M12-A`）起：**本轮新增**的孤儿**进条件**。</b>
 * 这是本函数自 `S4-D2` 建立以来第一次改判据，理由值得写全：
 * 旧口径下那个 `无主残留=N` **从不判红**，于是它**报了 25 次而每一轮收尾都照绿**
 * —— 一个一直在报、从不判红的数字等于没有。而它涨的时候正是最该有人停下来的时刻
 * （有人在造谁也认领不了的产物）。现在：**存量不计**（跨轮的老问题不该由这一轮背），
 * **新增计**（这一轮造的就是这一轮的问题）。水位由 `residue-watermark.mjs` 维护。
 *
 * <p>⚠️ `orphanDelta` **缺失时退化为旧行为**（不判新增）—— 那是给"还没接水位的调用方"
 * 留的兼容路径，而它**不静默**：`describeKbResidue` 会打出没有 delta 的那种格式，
 * 一眼看得出这个调用方没接。接法是 `findKbProbeResidue` 自己算（见那边的注释）。
 */
export const kbResidueClean = (r) =>
  r.sqlTotal === 0 && r.milvusVectors === 0 && r.milvusPartitions.length === 0
  && r.minioStillThere.length === 0
  // 🔴 `T-O30`：**本轮新增**的无主残留 ⇒ 判红。存量（`orphanDelta.baseline` 以内）不计。
  && (!r.orphanDelta || r.orphanDelta.added === 0)
  // 🔴 `#187`：**等不到解析作业停下来 ⇒ 清理不算干净**。
  //    它的语义不是"现在有残留"（此刻多半真是 0），而是"这份报告不可信" ——
  //    还在跑的 worker 会在删完之后继续写，实测几分钟后多出 156 行孤儿分片 + 1 个空分区。
  //    之所以并进这个布尔量而不是另开一个：**所有调用方都在 check 它** ——
  //    并进来等于让超时自动把每一处收尾判据变红，不必去改 6 个脚本。
  && !r.quiesceTimedOut
