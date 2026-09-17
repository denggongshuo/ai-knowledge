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
import { dirname, resolve } from 'node:path'

/** `ai-knowledge/`（仓库根）。本文件在 `ai_knowledge_app/scripts/lib/`，故上溯三级。 */
export const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..')

const MILVUS = process.env.E2E_MILVUS || 'http://127.0.0.1:19530'
const COLLECTION = 'knowledge_vectors'
/** 与后端 `MilvusConstants.PARTITION_PREFIX` 必须一致（`kb` + id + `_g` + 代）。 */
const PARTITION_PREFIX = 'kb'
const PARTITION_SEP = '_g'
/** MinIO 桶在容器内的挂载点（`docker exec kb-minio` 里的路径）。 */
const MINIO_ROOT = '/data/rag-documents'

/** 覆盖率闸扫描的列 —— 三个都是「外键式归属列」，能间接枚举出会被探针写脏的表。 */
const SCOPE_COLUMNS = ['kb_id', 'doc_id', 'user_id']

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
  { id: 'sql:sys_parse_issue', kind: 'sql', table: 'sys_parse_issue', via: 'kb_id', why: '未识别表格清单' },
  { id: 'sql:sys_chunk', kind: 'sql', table: 'sys_chunk', via: 'kb_id', why: '分片（C1 起）' },
  { id: 'sql:sys_document', kind: 'sql', table: 'sys_document', via: 'kb_id', why: '文档行' },
  { id: 'sql:sys_kb_grant', kind: 'sql', table: 'sys_kb_grant', via: 'kb_id', why: '知识库授权' },
  { id: 'sql:sys_kb', kind: 'sql', table: 'sys_kb', via: 'code', why: '主表（按 code 命中，非 id）' },
]

/**
 * 探针账号（`sys_user` 一族）产生的全部持久化产物。
 *
 * <p>⚠️ `sys_login_log` 是 2026-09-17 才补上的 —— 在那之前 `purgeProbeUsers` 只删用户，
 * 于是每次跑完都留下 `user_id` 指向已删用户的**孤儿行**（实测存量 3984 行）。
 * 该表**有 `username` 列**，本可按前缀删，纯粹是漏了。
 */
export const USER_ARTIFACTS = [
  {
    id: 'sql:sys_login_log', kind: 'sql-user', table: 'sys_login_log', via: 'username',
    why: '登录日志。按 username 前缀删（用户行删掉后 user_id 就成孤儿了）',
  },
  { id: 'sql:sys_user_role', kind: 'sql-user', table: 'sys_user_role', via: 'user', why: '用户角色关联' },
  { id: 'sql:sys_user', kind: 'sql-user', table: 'sys_user', via: 'username', why: '用户主表' },
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

/** 单引号包裹并转义 —— 传给 `sh -c` 的 JSON 体统一走这里，避免手工拼串漏转义。 */
const sq = (s) => `'${String(s).replace(/'/g, "'\\''")}'`

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

/** Milvus REST v2（2.4+ 自带，零新增依赖）。返回解析后的 JSON；失败时 `{code:-1, raw}`。 */
export function milvusPost(path, body) {
  const out = spawnSync('bash', ['-c',
    `curl -s -m 20 -X POST ${MILVUS}/v2/vectordb/${path} ` +
    `-H 'Content-Type: application/json' -d ${sq(JSON.stringify(body))}`,
  ], { cwd: REPO_ROOT, encoding: 'utf8' }).stdout ?? ''
  try {
    return JSON.parse(out)
  } catch {
    return { code: -1, raw: out }
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
    `where table_schema = database() and column_name in (${SCOPE_COLUMNS.map((c) => `'${c}'`).join(',')}) ` +
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
      `产物覆盖率闸未通过：以下表带 ${SCOPE_COLUMNS.join('/')} 列但**未登记** —— ${unknown.join(' · ')}。`
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
export async function teardownKbProbe(code) {
  // ① 快照 —— 删行之后就取不到了
  const ids = kbIdsOf(code)
  const minioKeys = minioKeysOf(code)

  // ② MinIO 对象
  const minioRemoved = minioRemove(minioKeys)

  // ③ Milvus：先删点，再删容器（顺序固定）
  milvusDeleteVectors(ids)
  milvusDropPartitions(ids)

  // ④ SQL：子表 → 主表（一条语句跑完，减少往返）
  mysqlScalar(
    KB_ARTIFACTS.filter((a) => a.kind === 'sql')
      .map((a) => (a.via === 'code'
        ? `delete from ${a.table} where code='${code}';`
        : `delete t from ${a.table} t join sys_kb k on k.id = t.${a.via} where k.code='${code}';`))
      .reduce((acc, s) => acc + s, ''),
  )

  // ⑤ 残留自检 —— 独立观测点
  const residue = await findKbProbeResidue(code, { ids, minioKeys })
  return { code, ids, minioKeys, removed: { minio: minioRemoved, sql: '见 residue' }, residue }
}

/** 用户族探针（`e2e-audit-backfill` 那类）。幂等。 */
export function teardownUserProbe(prefix) {
  const loginLogs = mysqlCount(`select count(*) from sys_login_log where username like '${prefix}%';`)
  mysqlScalar(
    USER_ARTIFACTS.filter((a) => a.kind === 'sql-user').map((a) => {
      if (a.via === 'username') return `delete from ${a.table} where username like '${prefix}%';`
      if (a.via === 'user') {
        return `delete t from ${a.table} t join sys_user u on u.id = t.user_id where u.username like '${prefix}%';`
      }
      throw new Error(`USER_ARTIFACTS 里 ${a.table} 的 via 取值无法处理：${a.via}`)
    }).reduce((acc, s) => acc + s, ''),
  )
  return { prefix, loginLogs, residue: findUserProbeResidue(prefix) }
}

// ---------------------------------------------------------------------------
// 闸 ②：残留自检（**独立观测点**，不信清理命令的返回值）
// ---------------------------------------------------------------------------

/**
 * 探针库是否还有残留。四类独立观测点：SQL 行 / Milvus 向量 / Milvus 分区 / MinIO 对象。
 *
 * @param code 探针库 code
 * @param snapshot 清理**前**取的 `{ids, minioKeys}`；不给则只能查 SQL 侧
 */
export async function findKbProbeResidue(code, snapshot = {}) {
  const { ids = [], minioKeys = [] } = snapshot

  const sql = {}
  for (const a of KB_ARTIFACTS.filter((x) => x.kind === 'sql')) {
    sql[a.table] = mysqlCount(a.via === 'code'
      ? `select count(*) from ${a.table} where code='${code}';`
      : `select count(*) from ${a.table} t join sys_kb k on k.id = t.${a.via} where k.code='${code}';`)
  }

  return {
    sql,
    sqlTotal: Object.values(sql).reduce((a, b) => a + b, 0),
    milvusVectors: await milvusVectorCount(ids),
    milvusPartitions: await milvusLeftoverPartitions(ids),
    /** ⚠️ 只有「清理前快照到的那些对象」能这么查 —— 更早的轮次留下的孤儿对象查不到。 */
    minioStillThere: minioStillPresent(minioKeys),
  }
}

/** 用户族探针是否还有残留。 */
export function findUserProbeResidue(prefix) {
  const sql = {}
  for (const a of USER_ARTIFACTS) {
    sql[a.table] = mysqlCount(
      a.via === 'username' ? `select count(*) from ${a.table} where username like '${prefix}%';`
        : `select count(*) from ${a.table} t join sys_user u on u.id = t.user_id where u.username like '${prefix}%';`,
    )
  }
  return { sql, sqlTotal: Object.values(sql).reduce((a, b) => a + b, 0) }
}

/** 把 `findKbProbeResidue` 的结果压成一行中文，给脚本的 `check(...)` 描述用。 */
export function describeKbResidue(r) {
  return `库行=${r.sql.sys_kb} 文档=${r.sql.sys_document} 分片=${r.sql.sys_chunk} `
    + `问题行=${r.sql.sys_parse_issue} 授权=${r.sql.sys_kb_grant} · `
    + `Milvus 向量=${r.milvusVectors} 分区=[${r.milvusPartitions.join(',') || '—'}] `
    + `MinIO 对象=${r.minioStillThere.length}`
}

/** `findKbProbeResidue` 是否全清。 */
export const kbResidueClean = (r) =>
  r.sqlTotal === 0 && r.milvusVectors === 0 && r.milvusPartitions.length === 0 && r.minioStillThere.length === 0
