<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import {
  UploadFilled,
  FolderOpened,
  Collection,
  Refresh,
  Search,
  WarningFilled,
  View,
  Delete,
  InfoFilled,
  Setting,
  Grid,
  Lock,
  Picture,
} from '@element-plus/icons-vue'
import { kbApi, docApi } from '@/api'
import { md5OfFile } from '@/utils/md5'
import type { UploadItem, KbDocRow, ParseIssue, ParseConfigView } from '@/types'

const route = useRoute()
const router = useRouter()
const kbId = computed(() => String(route.params.id ?? '1'))

const kbName = ref('加载中…')
const queue = ref<UploadItem[]>([])
const docs = ref<KbDocRow[]>([])
const total = ref(0)
const issues = ref<ParseIssue[]>([])
const parseConfig = ref<ParseConfigView | null>(null)
const loading = ref(false)
const selected = ref<KbDocRow[]>([])
const keyword = ref('')
const statusFilter = ref('')
const uploadRef = ref()
const folderInput = ref<HTMLInputElement>()

const ACCEPT = ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'md']
const acceptStr = ACCEPT.map((e) => `.${e}`).join(',')
const MAX_SIZE = 100 * 1024 * 1024
const MAX_COUNT = 20

const timers = new Set<ReturnType<typeof setInterval>>()
let failedOnce = false

// ---------------- 加载 ----------------
async function loadMeta() {
  try {
    const cfg = await kbApi.config(kbId.value)
    kbName.value = cfg.name
  } catch {
    kbName.value = '知识库'
  }
}
async function loadDocs() {
  loading.value = true
  try {
    const res = await kbApi.docs(kbId.value, {
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined,
    })
    docs.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
async function loadIssues() {
  try {
    const res = await kbApi.parseIssues(kbId.value)
    issues.value = res.list
  } catch {
    issues.value = []
  }
}
async function loadParseConfig() {
  try {
    parseConfig.value = await kbApi.parseConfig(kbId.value)
  } catch {
    parseConfig.value = null
  }
}

onMounted(() => {
  loadMeta()
  loadDocs()
  loadIssues()
  loadParseConfig()
})
// 页面切走时暂停/清理轮询
onBeforeUnmount(() => {
  timers.forEach((t) => clearInterval(t))
  timers.clear()
})

// ---------------- 文件校验与入队 ----------------
function extOf(name: string) {
  return name.split('.').pop()?.toLowerCase() ?? ''
}

function onFileChange(file: { name: string; size?: number; raw?: File }) {
  if (!file.raw) return
  enqueue([file.raw])
}

function enqueue(files: File[]) {
  const accepted: File[] = []
  for (const f of files) {
    const ext = extOf(f.name)
    if (!ACCEPT.includes(ext)) {
      ElMessage.warning(`「${f.name}」格式不支持，仅支持 ${ACCEPT.join(' / ').toUpperCase()}`)
      continue
    }
    if (f.size > MAX_SIZE) {
      ElMessage.warning(`「${f.name}」超过 100 MB 上限`)
      continue
    }
    if (queue.value.length + accepted.length >= MAX_COUNT) {
      ElMessage.warning(`单次最多 ${MAX_COUNT} 个文件，其余已忽略`)
      break
    }
    accepted.push(f)
  }
  if (!accepted.length) return
  for (const f of accepted) {
    const item: UploadItem = {
      uid: `u_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
      file: f,
      name: f.name,
      size: f.size,
      type: extOf(f.name).toUpperCase(),
      status: 'UPLOADING',
      percent: 0,
      chunkDone: 0,
      chunkTotal: 0,
    }
    queue.value.unshift(item)
    // ⚠️ **必须把队列里的那个（响应式代理）交给 runItem，不能直接传 `item`**。
    //
    // `queue` 是 `ref<UploadItem[]>`：`unshift(item)` 存的是**原始对象**，而模板里
    // `v-for="item in queue"` 读到的是它的**响应式代理**。之后在 `runItem` 里写
    // `item.percent = p` 如果落在原始对象上，就绕过了代理的 set 陷阱 ——
    // **数据变了但不会触发任何重新渲染**，页面上那一条永远停在
    // 「上传中 · 0%」，而网络面板里请求早已 200。
    //
    // 这个坑在改成真上传之前一直没暴露：那时的进度条也写原始对象，同样不刷新，
    // 但因为进度是"匀速跑完就完事"，看起来只是在动、看不出是假刷新。
    // 换成真进度后它立刻现形（E2E 等「已完成」等不到，DOM 却停在 0%）。
    // 改法的代价只有一次 `find`，换的是"数据与界面一致"这条底线。
    const live = queue.value.find((q) => q.uid === item.uid) ?? item
    void runItem(live)
  }
  uploadRef.value?.clearFiles?.()
}

/** 上传队列项对应的原始 File。 */
function fileOf(item: UploadItem): File | undefined {
  return item.file
}

/**
 * 真上传：算 MD5 → 去重预判 → 真字节上传（XHR 真进度）→ 轮询任务进度。
 *
 * <h3>本函数是 W1/W2/W5 三条偏差的收口点</h3>
 * 上一版这里全是演的：进度条是 `setInterval` 编的、失败按扩展名挑一个文件演、
 * 「解析中」的 chunkDone 也是编的。现在每一步都对着真接口：
 * <ul>
 *   <li>MD5 = `md5OfFile(file)`，**真读文件字节**（上一版是 `simpleHash(name, size)`，
 *       同名同大小必然同 hash，去重判定等于随机）；</li>
 *   <li>上传 = `kbApi.uploadDoc(kbId, form, onProgress)`，进度来自
 *       `XMLHttpRequest.upload.onprogress`；</li>
 *   <li>进度 = `docApi.task(taskId)` 轮询（上一版 `docApi.task` 已定义但零调用方）。</li>
 * </ul>
 *
 * <h3>为什么「上传完成」不等于「已完成」</h3>
 * 上传只把文件送到后端并落进 MinIO；解析、分片、向量化在 **S4-B/S4-C** 才做。
 * 所以本批任务跑到 `DONE` 之后文档状态是 `UPLOADED`（不是 `READY`），
 * 队列项标「已完成」指的是**上传这一跳完成了**。等解析管线到位，
 * 这里改成继续轮询文档状态即可 —— 那时 `PARSING`/`INDEXING` 才真正可达。
 */
async function runItem(item: UploadItem) {
  const file = fileOf(item)

  // ---------- 1) 算 MD5 + 去重预判 ----------
  let md5 = ''
  try {
    if (!file) throw new Error('no file')
    md5 = await md5OfFile(file)
    item.md5 = md5
    const dup = await kbApi.checkMd5(kbId.value, { name: item.name, md5 })
    item.duplicated = dup.duplicated
    if (dup.duplicated) {
      // 提示而非阻断：《06》§3.2 的口径是「命中 → 提示『文件已存在，是否覆盖』」，
      // 是否继续由用户决定（后端不加唯一约束，见 V12 的 idx_doc_md5 注释）
      ElMessage.info(`「${item.name}」已存在，将归档为新版本（不覆盖历史问答引用）`)
    }
  } catch (e) {
    // 去重预判失败不该拦住上传（它只是"省一次传输"的优化）
    console.warn('[kb-upload] MD5/去重预判失败，继续上传', e)
  }
  if (!queue.value.includes(item)) return

  // ---------- 2) 真上传（真进度）----------
  try {
    if (!file) throw new Error('no file')
    const form = new FormData()
    form.append('file', file)
    const up = await kbApi.uploadDoc(kbId.value, form, (p) => {
      item.percent = p
    })
    item.docId = up.docId
    item.taskId = up.taskId
    item.chunkTotal = up.chunkTotal
  } catch (e) {
    item.status = 'FAILED'
    item.errorMsg = e instanceof Error ? e.message : '上传失败，请重试'
    maybeNotify()
    return
  }
  if (!queue.value.includes(item)) return

  // ---------- 3) 轮询任务进度（真后端）----------
  item.status = 'PARSING'
  item.percent = 100
  pollTask(item)
}

/**
 * 轮询 `GET /doc/tasks/{taskId}` 直到终态。
 *
 * <p>间隔 600ms（《06》§3.7 的"轮询或 SSE"，本批取轮询；SSE 是 S5 的事）。
 * **页面切走时由 `onBeforeUnmount` 统一清掉**（`timers` 集合）——
 * 这是本条既有设计，不因改成真轮询而改变。
 *
 * <p>`status` 为 `DONE` 时标「已完成」并刷新列表；`FAILED` 时把**后端给的具体原因**
 * 直接显示（`G4-3`：失败必须给可执行的动作，不许只说"失败"两字）。
 */
function pollTask(item: UploadItem) {
  const taskId = item.taskId
  if (!taskId) {
    finishItem(item, true)
    return
  }
  const timer = setInterval(async () => {
    try {
      const t = await docApi.task(taskId)
      item.percent = t.percent
      item.chunkTotal = t.chunkTotal
      item.chunkDone = t.chunkDone
      if (t.percent >= 100 || t.errorMsg) {
        clearInterval(timer)
        timers.delete(timer)
        if (t.errorMsg) {
          item.status = 'FAILED'
          item.errorMsg = t.errorMsg
        } else {
          finishItem(item, true)
        }
      }
    } catch {
      // 任务查不到（10045）等情形：停轮询并标记失败，**不静默**留一个空转的定时器
      clearInterval(timer)
      timers.delete(timer)
      item.status = 'FAILED'
      item.errorMsg = '任务状态查询失败，请刷新页面查看结果'
    }
  }, 600)
  timers.add(timer)
}

/** 收尾：标完成、刷新列表与告警。 */
function finishItem(item: UploadItem, ok: boolean) {
  item.status = ok ? 'DONE' : 'FAILED'
  item.percent = 100
  maybeNotify()
}

/**
 * 继续盯文档状态直到终态（`S4-E1` 新增）。
 *
 * <h3>为什么必须有这一步</h3>
 * `S4-E1` 起**上传与解析是两个作业**：队列项的「已完成」只代表**转存**完成
 * （D-5 里"上传"的边界），文档此刻如实是「已上传」。解析由后端的 ParseWorker
 * 领作业执行，几秒后才推进到「索引中」→「已就绪」。
 *
 * <p>没有这一步的话，页面在"上传完成"那一刻刷一次列表就把「已上传」定住了 ——
 * 用户看到的是一篇永远停在"已上传"的文档，**而这正是本项目最不能接受的形态**：
 * 状态停在一个不再是事实的值上，且没有任何东西会去纠正它。
 *
 * <p>为什么是轮询列表而不是继续轮询上传任务：转存任务已经终态了，它不会再有变化；
 * 真相在**文档行**上（由解析作业推进）。轮询在全部行进入终态或超时后自停，
 * 不会留一个空转的定时器。
 */
function watchDocsUntilTerminal() {
  const NON_TERMINAL = ['UPLOADED', 'PARSING', 'INDEXING']
  const MAX_TICKS = 40 // 40 × 2s = 80s，覆盖单篇解析（含向量化）的正常耗时
  let ticks = 0
  const timer = setInterval(async () => {
    ticks += 1
    await loadDocs()
    await loadIssues()
    const stillRunning = docs.value.some((d) => NON_TERMINAL.includes(d.status))
    if (!stillRunning || ticks >= MAX_TICKS) {
      clearInterval(timer)
      timers.delete(timer)
    }
  }, 2000)
  timers.add(timer)
}

/** 整批完成通知（与告警区分） */
function maybeNotify() {
  const pending = queue.value.filter((q) => q.status === 'UPLOADING' || q.status === 'PARSING')
  if (pending.length) return
  const done = queue.value.filter((q) => q.status === 'DONE').length
  const failed = queue.value.filter((q) => q.status === 'FAILED').length
  if (done + failed === 0) return
  ElNotification({
    title: '上传处理完成',
    message: `${done + failed} 个文件已处理，${done} 成功 / ${failed} 失败`,
    type: failed ? 'warning' : 'success',
  })
  if (failed) {
    ElNotification({
      title: '存在上传失败的文件',
      message: '失败原因已在队列中标出，可修正后重试；失败不会产生脏向量数据。',
      type: 'error',
    })
  }
  loadDocs()
  loadIssues()
  // `S4-E1`：转存完成 ≠ 解析完成 —— 继续盯到文档行进入终态为止（见该函数注释）
  watchDocsUntilTerminal()
}

function retry(item: UploadItem) {
  item.status = 'UPLOADING'
  item.percent = 0
  item.chunkDone = 0
  item.errorMsg = undefined
  void runItem(item)
}
function removeItem(item: UploadItem) {
  queue.value = queue.value.filter((q) => q !== item)
}
function clearDone() {
  queue.value = queue.value.filter((q) => q.status !== 'DONE')
}
function pauseAll() {
  timers.forEach((t) => clearInterval(t))
  timers.clear()
  ElMessage.info('已暂停进度轮询（上传已提交的仍会在后端完成）')
}

function onExceed() {
  ElMessage.warning(`单次最多 ${MAX_COUNT} 个文件，请分批上传`)
}

/** 从文件夹导入（webkitdirectory） */
function pickFolder() {
  folderInput.value?.click()
}
function onFolderPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files ? Array.from(input.files) : []
  const real = files.filter((f) => f.size > 0 && !f.name.startsWith('.'))
  if (!real.length) {
    ElMessage.info('未选择有效文件')
    return
  }
  enqueue(real)
  input.value = ''
}

// ---------------- 文档列表操作 ----------------
function statusTag(s: KbDocRow['status']): { text: string; type: 'success' | 'warning' | 'danger' | 'info' } {
  const map: Record<KbDocRow['status'], { text: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
    READY: { text: '已就绪', type: 'success' },
    INDEXING: { text: '索引中', type: 'warning' },
    PARSING: { text: '解析中', type: 'warning' },
    UPLOADED: { text: '已上传', type: 'info' },
    FAILED: { text: '解析失败', type: 'danger' },
  }
  return map[s]
}
function qualityText(d: KbDocRow) {
  const parts: string[] = []
  let danger = false
  if (d.tableTotal > 0) {
    parts.push(`表格 ${d.tableParsed}/${d.tableTotal}`)
    if (d.tableParsed < d.tableTotal) danger = true
  }
  const it = d.imageTotal ?? 0
  if (it > 0) {
    const ip = d.imageParsed ?? 0
    parts.push(`图片 ${ip}/${it}`)
    if (ip < it) danger = true
  }
  // 页级文本层覆盖（`S4-E2` 新增）：`40 页里 38 页有文本` 比 `平均 300 字/页` 信息量大得多 ——
  // 后者会被平均值稀释，前者直接指出"少了 2 页"。
  //
  // ⚠️ 它**只展示、不标红**，这是刻意的：这一列分不清"少的那 2 页是扫描页"还是
  //    "少的那 2 页本来就是空白页"。后者在正常文档里很常见（章节隔页、末尾空白页），
  //    拿 `pagesWithText < pagesTotal` 标红会让**大量正常文档常年显示"需补录"** ——
  //    而常见的红标等于没有红标（本仓的「误报比漏报更伤」，S4-B 的 W1' 已为此返工过）。
  //    "要不要处理"的唯一权威信号是顶部告警条 —— 它由后端的 **SCANNED** 页驱动
  //    （`disposition = MISSING`），空白页在那条链路上已经被排除掉了。
  const pt = d.pagesTotal ?? 0
  if (pt > 0) {
    parts.push(`${d.pagesWithText ?? 0}/${pt} 页有文本`)
  }
  if (!parts.length) return { text: '无表格 / 图片', type: 'info' as const }
  return { text: parts.join(' · ') + (danger ? '（需补录）' : ''), type: danger ? ('danger' as const) : ('success' as const) }
}

/**
 * 告警条只数**真正需要处理**的那些问题（`S4-E2`）。
 *
 * 后端 `disposition = 'EMPTY_PAGE'` 的是**空白页/纯版式页** —— 它不是"内容丢了"，
 * 用户对一张空白页没有任何可采取的动作。把它算进"N 篇文档存在解析问题"会让告警条
 * **常态化变红**（有版式页的文档很常见），红条一旦常见就没人看了。
 *
 * 旧行（`disposition` 为 null）按"未知"计 —— 它们落库时只有"内容缺失"一种语义
 * （那时没有 OCR/VL），所以计入是**还原当时的事实**，不是猜。
 */
/**
 * 「安全」列的标记（`T-O04` · `优化/O4` 步 3）。返回 `null` = **不显示任何标记**。
 *
 * 🔴 **三态，少一态就会误导**：
 * - `'SKIPPED'` → 「未扫描」（黄色）—— ClamAV 未部署 / 连不上，**没扫成**；
 * - `null` / `undefined` → **不显示**（返回 null）—— 该行早于扫描能力上线，**从没试过**；
 * - `'CLEAN'` → 「已扫描」（绿色）；`'INFECTED'` → 「已拦截」（红色）。
 *
 * ⚠️ 前两者**绝不能合并**：把历史行也标成「未扫描」会让运维以为清理任务漏跑了，
 * 而"这台机器本来就没装"与"这批老数据没扫过"是两件完全不同的事。
 * 这条与后端 `V28` 把 `scan_status` 留成可空是**同一条纪律的两端**。
 *
 * ⚠️ 也**不显示** `has_macro`：宏是「放行 + 标记」，标记的落点是下载/预览页
 * （见 `O4` §3 流程图），不在这个列表里 —— 列表上多一列会让它被淹没在
 * 大多数文档都有的"无宏"里。真要做的话，等有消费方时再说。
 */
function scanTag(d: KbDocRow): { text: string; type: 'success' | 'danger' | 'warning' } | null {
  switch (d.scanStatus) {
    case 'CLEAN':
      return { text: '已扫描', type: 'success' }
    case 'INFECTED':
      return { text: '已拦截', type: 'danger' }
    case 'SKIPPED':
      return { text: '未扫描', type: 'warning' }
    default:
      // `null` / `undefined` = 从没试过（本批之前的历史行）—— **不显示**，理由见上
      return null
  }
}

// ⚠️ 排除**两种"无需处理"**的处置，它们的共同点是"用户对它没有任何可采取的动作"：
//   · `EMPTY_PAGE` —— 空白页 / 纯版式页（S4-E2）；
//   · `SKIPPED`    —— 图片按策略跳过（T-512 步 5.1）：尺寸过小（图标/分隔线）或
//                     同图重复（公司 logo）—— 这两种**几乎每份带图文档都有**，
//                     计进来会让告警条常态化变红，把真正该看的「图片理解失败」淹掉。
//                     本仓为这条纪律返工过：`O2` 的页级阈值 10 就是被探针否掉的。
//   两者的 `kind` 不同（`EMPTY_PAGE` vs `IMAGE_SKIPPED`），详情里仍分得开 ——
//   这里排除的只是"要不要报警"，不是"要不要记录"。
const actionableIssues = computed(() =>
  issues.value.filter((i) => i.disposition !== 'EMPTY_PAGE' && i.disposition !== 'SKIPPED')
)
const issueDocCount = computed(() => new Set(actionableIssues.value.map((i) => i.docId)).size)
function sizeText(b: number) {
  const mb = b / 1024 / 1024
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${(b / 1024).toFixed(0)} KB`
}
function previewDisabled(d: KbDocRow) {
  return d.status === 'INDEXING' || d.status === 'PARSING'
}
function preview(d: KbDocRow) {
  if (previewDisabled(d)) return
  router.push(`/doc/${d.id}/preview`)
}
async function reindex(d: KbDocRow) {
  await docApi.reindex(d.id)
  ElMessage.success(`「${d.title}」已发起重建索引`)
}
async function removeDoc(d: KbDocRow) {
  await ElMessageBox.confirm(`确认删除「${d.title}」？将移入回收站，30 天内可恢复。`, '移入回收站', { type: 'warning' })
  await docApi.remove(d.id)
  ElMessage.success('已移入回收站')
  loadDocs()
}
async function batchRemove() {
  if (!selected.value.length) return
  await ElMessageBox.confirm(`确认删除选中的 ${selected.value.length} 篇文档？将移入回收站。`, '批量删除', { type: 'warning' })
  await docApi.batchRemove(selected.value.map((d) => d.id))
  ElMessage.success('已批量移入回收站')
  selected.value = []
  loadDocs()
}
async function batchReindex() {
  if (!selected.value.length) return
  for (const d of selected.value) await docApi.reindex(d.id)
  ElMessage.success(`已对 ${selected.value.length} 篇文档发起重建索引`)
  selected.value = []
}
function gotoIssue() {
  if (actionableIssues.value[0]) router.push(`/doc/${actionableIssues.value[0].docId}/preview`)
}
</script>

<template>
  <div class="page upload-page">
    <!-- 页头 -->
    <div class="page-head">
      <div>
        <h2 class="page-title">文档上传</h2>
        <p class="page-desc">当前知识库：<b class="kb-name">{{ kbName }}</b> · 支持批量上传，上传后自动解析并构建向量索引</p>
      </div>
      <div class="head-actions">
        <el-button :icon="FolderOpened" @click="pickFolder">从文件夹导入</el-button>
        <el-button type="primary" :icon="Collection" @click="router.push(`/kb/${kbId}`)">返回知识库</el-button>
      </div>
    </div>

    <input ref="folderInput" type="file" webkitdirectory multiple class="hidden-input" @change="onFolderPicked" />

    <div class="split">
      <!-- 左：上传区 -->
      <div>
        <el-card shadow="never">
          <el-upload
            ref="uploadRef"
            drag
            multiple
            :auto-upload="false"
            :show-file-list="false"
            :accept="acceptStr"
            :limit="MAX_COUNT"
            :on-change="onFileChange"
            :on-exceed="onExceed"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">将文件拖到此处，或 <em>点击选择文件</em></div>
            <template #tip>
              <div class="upload-tip">
                单次最多 20 个文件，单个文件 ≤ 100 MB<br />
                支持格式：PDF · DOC · DOCX · XLS · XLSX · PPT · PPTX · TXT · MD
              </div>
            </template>
          </el-upload>
        </el-card>

        <!-- 上传队列 -->
        <el-card shadow="never" class="queue-card">
          <template #header>
            <div class="card-head-row">
              <div class="row-inline">
                <h3>上传队列</h3>
                <el-tag size="small" type="info">共 {{ queue.length }} 个文件</el-tag>
              </div>
              <div class="row-inline">
                <el-button size="small" @click="pauseAll">全部暂停</el-button>
                <el-button size="small" @click="clearDone">清空已完成</el-button>
              </div>
            </div>
          </template>

          <el-empty v-if="!queue.length" description="暂无上传任务，拖入文件开始" :image-size="70" />

          <div
            v-for="item in queue"
            :key="item.uid"
            class="file-row"
            :class="{ 'row-parsing': item.status === 'PARSING', 'row-failed': item.status === 'FAILED' }"
          >
            <div class="file-ic" :class="'ft-' + item.type.toLowerCase()">{{ item.type }}</div>
            <div class="file-main">
              <div class="row-between">
                <div class="ellipsis file-name">{{ item.name }}</div>
                <el-tag v-if="item.status === 'PARSING'" size="small" type="warning">解析中</el-tag>
                <el-tag v-else-if="item.status === 'DONE'" size="small" type="success">已完成</el-tag>
                <el-tag v-else-if="item.status === 'FAILED'" size="small" type="danger">解析失败</el-tag>
                <span v-else class="mono file-pct">{{ item.percent }}%</span>
              </div>

              <el-progress
                class="file-progress"
                :percentage="item.percent"
                :show-text="false"
                :stroke-width="6"
                :status="item.status === 'DONE' ? 'success' : item.status === 'FAILED' ? 'exception' : undefined"
              />

              <div class="file-sub">
                <template v-if="item.status === 'UPLOADING'">
                  上传中 · {{ sizeText((item.size * item.percent) / 100) }} / {{ sizeText(item.size) }} ·
                  {{ item.speedKbps }} KB/s
                </template>
                <template v-else-if="item.status === 'PARSING'">
                  正在切分文本并生成向量 · 已处理 {{ item.chunkDone }} / {{ item.chunkTotal }} 个分片
                </template>
                <template v-else-if="item.status === 'DONE'">
                  共 {{ sizeText(item.size) }} · 生成 {{ item.chunkTotal }} 个分片
                  <el-tag v-if="item.duplicated" size="small" type="warning" class="dup-tag">同名文件已归档为新版本</el-tag>
                </template>
                <template v-else>
                  <span class="fail-text">{{ item.errorMsg }}</span>
                </template>
              </div>
            </div>

            <div class="file-ops">
              <el-button v-if="item.status === 'FAILED'" size="small" @click="retry(item)">重试</el-button>
              <el-button
                v-else-if="item.status === 'DONE'"
                size="small"
                text
                :icon="View"
                @click="item.docId && router.push(`/doc/${item.docId}/preview`)"
              />
              <el-button size="small" text :icon="Delete" @click="removeItem(item)" />
            </div>
          </div>
        </el-card>
      </div>

      <!-- 右：上传须知 -->
      <div>
        <el-card shadow="never">
          <template #header><h3>上传须知</h3></template>
          <ul class="notice-list">
            <li>上传前按 <code class="mono">用户 + 文件 MD5</code> 去重，重复文件将归档为新版本</li>
            <li>扫描版 PDF 需开启 OCR，解析耗时约为普通文档的 3~5 倍</li>
            <li>含表格的扫描件会做表格结构识别，<b>识别失败的表格会在下方标红提示</b>，不会静默丢弃</li>
            <li>Excel 按 Sheet 切分，每行数据作为一个独立片段</li>
            <li>解析失败可直接重试，不会产生脏向量数据</li>
          </ul>
        </el-card>
      </div>
    </div>

    <!-- 解析配置（全宽只读） -->
    <el-card shadow="never" class="mt-16">
      <template #header>
        <div class="card-head-row">
          <h3>解析配置</h3>
          <div class="row-inline">
            <el-tag size="small" type="info">继承知识库「{{ kbName }}」</el-tag>
            <el-button size="small" :icon="Setting" @click="router.push(`/kb/${kbId}`)">前往知识库配置</el-button>
          </div>
        </div>
      </template>

      <div v-if="parseConfig" class="four-col">
        <div class="cfg-col">
          <div class="cfg-head"><el-icon><Grid /></el-icon><b>分片策略</b></div>
          <div v-for="g in parseConfig.split" :key="g.label" class="cfg-row">
            <span class="cfg-label">{{ g.label }}</span>
            <span class="cfg-value" :class="{ locked: g.locked, warn: g.warning }">
              <el-icon v-if="g.locked"><Lock /></el-icon>{{ g.value }}
            </span>
          </div>
        </div>

        <div class="cfg-col">
          <div class="cfg-head"><el-icon><InfoFilled /></el-icon><b>表格处理</b></div>
          <div v-for="g in parseConfig.table" :key="g.label" class="cfg-row">
            <span class="cfg-label">{{ g.label }}</span>
            <span class="cfg-value" :class="{ locked: g.locked }">
              <el-icon v-if="g.locked"><Lock /></el-icon>{{ g.value }}
            </span>
          </div>
        </div>

        <div class="cfg-col">
          <div class="cfg-head"><el-icon><Picture /></el-icon><b>图片处理</b></div>
          <div v-for="g in parseConfig.image" :key="g.label" class="cfg-row">
            <span class="cfg-label">{{ g.label }}</span>
            <span class="cfg-value" :class="{ locked: g.locked, warn: g.warning }">
              <el-icon v-if="g.locked"><Lock /></el-icon>
              <el-tag v-if="g.enabled" size="small" type="success">已开启</el-tag>
              {{ g.value }}
            </span>
          </div>
        </div>

        <div class="cfg-col">
          <div class="cfg-head"><el-icon><InfoFilled /></el-icon><b>索引增强</b></div>
          <div v-for="g in parseConfig.enhance" :key="g.label" class="cfg-row">
            <span class="cfg-label">{{ g.label }}</span>
            <span class="cfg-value">
              <el-tag v-if="g.enabled" size="small" type="success">已开启</el-tag>
              <el-tag v-else size="small" type="info">未开启</el-tag>
              <span class="cfg-note">{{ g.value }}</span>
            </span>
          </div>
        </div>
      </div>

      <div class="cfg-foot">
        <el-icon><InfoFilled /></el-icon>
        解析配置按知识库维度生效，此处只读；如需为单篇文档覆盖，请上传后在文档详情中调整并重建索引。图片由当前 VL 模型生成描述与检索摘要（描述全程在后端内部流转，前端只读展示），图片整体原子化不做切分。
      </div>
    </el-card>

    <!-- 文档列表 -->
    <el-card shadow="never" class="mt-16">
      <template #header>
        <div class="card-head-row">
          <h3>文档列表 <span class="total-note">共 {{ total }} 篇</span></h3>
          <div class="row-inline">
            <el-input v-model="keyword" placeholder="文件名 / 上传人" :prefix-icon="Search" clearable style="width: 200px" @keyup.enter="loadDocs" @clear="loadDocs" />
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="loadDocs">
              <el-option label="已就绪" value="READY" />
              <el-option label="索引中" value="INDEXING" />
              <el-option label="解析中" value="PARSING" />
              <el-option label="解析失败" value="FAILED" />
            </el-select>
            <el-button :icon="Refresh" @click="loadDocs" />
          </div>
        </div>
      </template>

      <!-- 解析问题告警条 -->
      <div v-if="actionableIssues.length" class="issue-bar">
        <el-icon class="issue-icon"><WarningFilled /></el-icon>
        <span class="issue-text">
          <b>{{ issueDocCount }} 篇文档存在解析问题</b>：{{ actionableIssues[0]?.docTitle }} 第 {{ actionableIssues[0]?.pageNo }} 页 —— {{ actionableIssues[0]?.reason }}。
          有问题的页面内容无法被检索命中，建议改用可复制文本的源文件后重建索引。
        </span>
        <el-button size="small" @click="gotoIssue">查看详情</el-button>
      </div>

      <!-- 批量操作 -->
      <div v-if="selected.length" class="batch-bar">
        <span>已选 {{ selected.length }} 篇</span>
        <el-button size="small" :icon="Refresh" @click="batchReindex">批量重建索引</el-button>
        <el-button size="small" type="danger" :icon="Delete" @click="batchRemove">批量删除</el-button>
      </div>

      <el-table v-loading="loading" :data="docs" row-key="id" @selection-change="(v: KbDocRow[]) => (selected = v)">
        <el-table-column type="selection" width="40" />
        <el-table-column label="文件名" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="doc-name">
              <span class="dot-type" :class="'ft-' + row.type.toLowerCase()">{{ row.type }}</span>
              <b class="ellipsis">{{ row.title }}</b>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="70" />
        <el-table-column label="大小" width="90">
          <template #default="{ row }"><span class="mono">{{ sizeText(row.size) }}</span></template>
        </el-table-column>
        <el-table-column label="分片数" width="80">
          <template #default="{ row }"><span class="mono">{{ row.chunkCount || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status).type">{{ statusTag(row.status).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="解析质量" width="140">
          <template #default="{ row }">
            <el-tag size="small" :type="qualityText(row).type" :effect="qualityText(row).type === 'danger' ? 'light' : 'plain'">
              {{ qualityText(row).text }}
            </el-tag>
          </template>
        </el-table-column>
        <!--
          安全扫描（`T-O04` · `优化/O4` 步 3）。**独立一列而不是并进「解析质量」**：
          扫描发生在解析**之前**，它的结论会决定这篇文档**能不能被解析** ——
          把两件不同阶段的事塞进一列，读的人会以为"质量差的文档"与"有问题的文档"是一回事。
          ⚠️ 三态必须分开显示（见 `scanTag` 的注释），其中 `null` **不显示标记**。
        -->
        <el-table-column label="安全" width="90">
          <template #default="{ row }">
            <el-tag v-if="scanTag(row)" size="small" :type="scanTag(row)!.type" effect="plain">
              {{ scanTag(row)!.text }}
            </el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="owner" label="上传人" width="90" />
        <el-table-column label="上传时间" width="140">
          <template #default="{ row }"><span class="muted">{{ row.uploadedAt }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text :icon="View" :disabled="previewDisabled(row)" @click="preview(row)">预览</el-button>
            <el-button size="small" text :icon="Refresh" @click="reindex(row)">重建</el-button>
            <el-button size="small" text type="danger" :icon="Delete" @click="removeDoc(row)" />
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.upload-page {
  height: 100%;
  overflow-y: auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-desc {
  margin: 0;
}
.kb-name {
  color: var(--el-color-primary);
}
.head-actions {
  display: flex;
  gap: 10px;
  flex: none;
}
.hidden-input {
  display: none;
}
.split {
  display: grid;
  grid-template-columns: minmax(0, 2.4fr) minmax(240px, 1fr);
  gap: 16px;
  align-items: start;
}
.mt-16 {
  margin-top: 16px;
}
.row-inline {
  display: flex;
  align-items: center;
  gap: 8px;
}
.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.card-head-row h3 {
  font-size: 15px;
  font-weight: 600;
  margin: 0;
}
.total-note {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  margin-left: 6px;
}
.upload-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  margin-top: 8px;
}

/* 队列 */
.queue-card {
  margin-top: 16px;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  margin-bottom: 10px;
  transition: background 0.2s;
}
.file-row:last-child {
  margin-bottom: 0;
}
.row-parsing {
  background: #fbfdff;
  border-color: var(--el-color-primary-light-8);
}
.row-failed {
  background: #fffafa;
  border-color: #fde2e2;
}
.file-ic {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  flex: none;
  background: #909399;
}
.ft-pdf {
  background: #f56c6c;
}
.ft-doc,
.ft-docx {
  background: #409eff;
}
.ft-xls,
.ft-xlsx {
  background: #67c23a;
}
.ft-ppt,
.ft-pptx {
  background: #e6a23c;
}
.ft-txt,
.ft-md {
  background: #909399;
}
.file-main {
  flex: 1;
  min-width: 0;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-name {
  font-size: 13.5px;
  font-weight: 500;
}
.file-pct {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.file-progress {
  margin-top: 6px;
}
.file-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 5px;
}
.fail-text {
  color: var(--el-color-danger);
}
.dup-tag {
  margin-left: 6px;
}
.file-ops {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: none;
}

/* 上传须知 */
.notice-list {
  margin: 0;
  padding-left: 16px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  line-height: 2;
}
.notice-list b {
  color: var(--el-color-danger);
}

/* 解析配置（四栏：分片 / 表格 / 图片 / 索引增强） */
.four-col {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 22px;
}
.cfg-head {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 10px;
  color: var(--el-color-primary);
}
.cfg-head b {
  font-size: 13.5px;
  color: var(--el-text-color-primary);
  font-weight: 600;
}
.cfg-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.cfg-row:last-child {
  border-bottom: none;
}
.cfg-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.cfg-value {
  font-size: 12px;
  color: var(--el-text-color-primary);
  text-align: right;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.cfg-value.locked {
  color: var(--el-color-success);
}
.cfg-value.warn {
  color: var(--el-color-warning);
}
.cfg-note {
  display: block;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  line-height: 1.5;
  margin-top: 2px;
}
.cfg-foot {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 16px;
  padding: 10px 12px;
  background: #f5f9ff;
  border-radius: 8px;
  font-size: 12px;
  color: #3a6ea5;
  line-height: 1.6;
}

/* 文档列表 */
.issue-bar {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 12px;
  background: #fffafa;
  border: 1px solid #fde2e2;
  border-radius: 8px;
  margin-bottom: 12px;
}
.issue-icon {
  color: var(--el-color-danger);
  flex: none;
}
.issue-text {
  flex: 1;
  font-size: 12.5px;
  color: #a33;
  line-height: 1.6;
}
.batch-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
  margin-bottom: 12px;
  font-size: 13px;
}
.doc-name {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.dot-type {
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  padding: 1px 5px;
  border-radius: 3px;
  flex: none;
}
.muted {
  color: var(--el-text-color-secondary);
}
</style>
