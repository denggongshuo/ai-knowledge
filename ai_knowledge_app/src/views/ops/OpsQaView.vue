<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search,
  Download,
  Refresh,
  InfoFilled,
  WarningFilled,
  Upload,
  MagicStick,
  CircleCheck,
  ChatDotRound,
  Picture,
  Share,
} from '@element-plus/icons-vue'
import { opsApi, kbApi, docApi } from '@/api'
import type { QaOverview, MissQuestion, MissStatus, TopQuestion, FeedbackStats } from '@/types'

const router = useRouter()

const MISS_STATUS: Record<MissStatus, { text: string; type: 'danger' | 'warning' | 'success' | 'info' }> = {
  PENDING: { text: '待处理', type: 'danger' },
  IN_LIST: { text: '已加入清单', type: 'warning' },
  RESOLVED: { text: '已解决', type: 'success' },
  IGNORED: { text: '已忽略', type: 'info' },
}

const loading = ref(false)
const range = ref('7d')
const activeTab = ref('misses')

const overview = ref<QaOverview | null>(null)
const misses = ref<MissQuestion[]>([])
const missTotal = ref(0)
const topQuestions = ref<TopQuestion[]>([])
const feedback = ref<FeedbackStats | null>(null)

const selected = ref<MissQuestion[]>([])
const statusFilter = ref('all')
const kbFilter = ref('all')
const keyword = ref('')
const kbMap = ref<Record<string, number>>({})

onMounted(async () => {
  loading.value = true
  try {
    const kbs = await kbApi.list()
    const map: Record<string, number> = {}
    for (const k of (kbs.list as unknown as { id: number; name: string }[]) ?? []) map[k.name] = k.id
    kbMap.value = map
    await Promise.all([loadOverview(), loadMisses(), loadTop(), loadFeedback()])
  } finally {
    loading.value = false
  }
})

async function loadOverview() {
  overview.value = await opsApi.qaOverview()
}
async function loadMisses() {
  const res = await opsApi.misses({
    status: statusFilter.value,
    kbId: kbFilter.value,
    keyword: keyword.value || undefined,
  })
  misses.value = res.list
  missTotal.value = res.total
}
async function loadTop() {
  const res = await opsApi.topQuestions({ range: range.value, limit: 20 })
  topQuestions.value = res.list
}
async function loadFeedback() {
  // ⚠️ `range` 要发给它 —— 这一栏原先不传（后端默认 7 天），
  // 于是"时间范围"下拉只影响榜单，两个卡片说的不是同一个窗口
  feedback.value = await opsApi.feedbackStats({ range: range.value })
}
/** 时间范围变化 ⇒ **两栏一起重拉**（一个下拉管一页，别只刷新其中一栏）。 */
function reloadStats() {
  void Promise.all([loadTop(), loadFeedback()])
}

/** 状态标签（模板中避免用 any 索引 Record） */
function missTag(status: MissStatus) {
  return MISS_STATUS[status] ?? { text: '—', type: 'info' as const }
}

const maxTopCount = computed(() => Math.max(...topQuestions.value.map((t) => t.count), 1))

/**
 * 归因三项的构成 —— 🔴 **三个数一起读才对得上**。
 *
 * 需求 §1.10 的验收要点写着「点踩原因次数合计 = 统计的负反馈总数」，而需求 §6.8 又说
 * 点踩时原因是**可选**的 ⇒ 只要有一行"点踩但没填原因"，那条等式就**永远不成立**。
 * 后端把三个数都如实给了（`reasons` / `unreasoned` / `negativeTotal`），
 * 页面必须**一起显示**：只显示前两个的话，用户会以为系统的账对不上，而错的是那句验收要点。
 */
const negativeTop = computed(() => (feedback.value?.reasons ?? []).reduce((s, r) => s + r.count, 0))
const unreasoned = computed(() => feedback.value?.unreasoned ?? 0)
const unreasonedPercent = computed(() =>
  feedback.value && feedback.value.negativeTotal > 0
    ? Math.round((unreasoned.value * 1000) / feedback.value.negativeTotal) / 10
    : 0,
)

/** 需求 §1.6 的结论落点：前两个原因合计占多少（原文是"合计占 75%，最值得投入"）。 */
const topTwoShare = computed(() => {
  const f = feedback.value
  if (!f || f.negativeTotal === 0) return null
  const first2 = ['答案不准确', '没找到相关内容']
  const sum = f.reasons.filter((r) => first2.includes(r.reason)).reduce((s, r) => s + r.count, 0)
  return Math.round((sum * 1000) / f.negativeTotal) / 10
})

/** 空召回的那一组没有均分（后端给 `null`）—— 显示 `—`，**不显示 `0`**。 */
function scoreText(v: number | null) {
  return v == null ? '—' : v.toFixed(3)
}
/** 条形图的降权阈值：没分数时**不算低**（不知道，不是差）。 */
function scoreLow(v: number | null) {
  return v != null && v < 0.7
}

// ---------------- 状态流转 ----------------
async function setStatus(item: MissQuestion, status: MissStatus) {
  const res = await opsApi.patchMiss(item.id, status)
  if (overview.value) overview.value.pending = res.pending
  // ⚠️ **改完重新拉一次列表**，不本地改 `item`。
  // 原来这里写的是 `item.ignoredBy = '系统管理员'` —— 一个**写死的假值**：
  // 谁点的忽略，只有服务端知道（`ignored_by`），前端猜不出来。
  // 本地改还会让"被筛掉的行留在列表里"（比如筛选条件是"待处理"时刚被忽略的那条）。
  await loadMisses()
  ElMessage.success(`已标记为「${MISS_STATUS[status].text}」`)
}
async function batchSet(status: MissStatus) {
  if (!selected.value.length) return ElMessage.warning('请先选择问题')
  for (const item of selected.value) await opsApi.patchMiss(item.id, status)
  await Promise.all([loadMisses(), loadOverview()])
  ElMessage.success(`已批量标记为「${MISS_STATUS[status].text}」`)
  selected.value = []
}

/**
 * 闭环第 4 步：重放验证（跑后端 `POST /ops/qa/misses/{id}/replay`）。
 *
 * <h3>🔴 状态由**后端**给，前端不推断</h3>
 * 原来这里写的是 `item.status = res.improved ? 'RESOLVED' : 'PENDING'` —— 两个错：
 * ① 需求 §1.4 步 5 的判据是「分数**达标**自动关闭」，不是"涨了就算"；
 * ② 「判不了」（补录文档还在解析、或压根没补录）这一档**不改状态**，
 *    `improved` 是 false，于是它会被显示成"未通过"—— 而那是**一个关于内容的错误结论**。
 * 现在 `status` / `verdict` 都取后端回话。
 */
async function replay(item: MissQuestion) {
  const res = await opsApi.replayMiss(item.id)
  // 重新拉列表：状态、`补录前 → 补录后` 都在服务端，本地改一份迟早与它对不上
  await Promise.all([loadMisses(), loadOverview()])

  const score = (v: number | null) => (v == null ? '—' : v.toFixed(6))
  const title =
    res.verdict === 'RESOLVED'
      ? '重放验证通过，已自动关闭'
      : res.verdict === 'BLOCKED'
        ? '这次重放判不了'
        : '重放验证未通过'
  ElMessageBox.alert(
    `召回分：${score(res.before)} → ${score(res.after)}
是否命中刚补录的文档：${res.hitSupplementedDoc ? '是' : '否'}
当前状态：${MISS_STATUS[res.status]?.text ?? res.status}

${res.note}`,
    title,
    { type: res.verdict === 'RESOLVED' ? 'success' : 'warning', confirmButtonText: '知道了' },
  )
}

/**
 * 闭环第 2 步：一键补文档，**问题文本作为补录备注带入**（需求 §1.4 步 2）。
 *
 * <h3>🔴 为什么"横跨多库"这一路必须把 note 一起带走</h3>
 * `kbName` 是那一行**涉及的知识库名**（后端 `kb_ids` 逗号串的显示名，可能是「A、B、C」）。
 * 库里查不到对应 id 时（跨多库、或那个库已删）就只能跳知识库列表让用户自己选目标库 ——
 * 而这一步原来**把 note 丢了**：上传页收不到问题原文，重放验证就永远认不出"刚补录的是哪一篇"。
 * 于是闭环卡在第 2 步与第 4 步之间，**而现象只是"重放说没命中补录文档"**。
 * ⇒ 现在把 note 挂在 `/kb` 的 query 上，由库列表透传到那一条「上传文档」入口。
 *
 * ⚠️ 顺带说明为什么**不**"默认补到第一个库"：`kb_ids` 记的是**检索覆盖了哪些库**，
 * 不是"答案该在哪个库"——挑一个替用户决定，会让补录落到一个他自己都没选的库上。
 */
function supplement(item: MissQuestion) {
  const kbId = kbMap.value[item.kbName]
  if (!kbId) {
    router.push({ path: '/kb', query: { note: item.question } })
    ElMessage.info('已跳转知识库列表，请选择目标库上传补录文档（问题原文已随行带入）')
    return
  }
  router.push({ path: `/kb/${kbId}/upload`, query: { note: item.question } })
}

/**
 * 图谱补边（`T-C04` · 需求 `07:544`）—— 「补文档」之外的第二条闭环路。
 *
 * <h3>🔴 为什么两条路都要有</h3>
 * 「补文档」解决的是"库里根本没有相关内容"；而有些问题是**文档本来就有、只是模型没抽出
 * 那条关系** —— 重传文档解决不了，只能人工把那条边补上。
 *
 * <h3>⚠️ 锚点必须由人挑（文档 → 分片）</h3>
 * 需求 `07:469` 要求每条关系可溯源回原文，回源锚就是 `sourceChunkId`；
 * 而 `sys_qa_miss` **没有 trace 关联**（它是按问法聚合的行），程序拿不到"那次没命中的分片"。
 * ⇒ 只能让操作员挑。后端会校验"这个分片确实属于这篇文档"，挑错了会被拒。
 *
 * <h3>⚠️ 关系动词是闭集（需求 `07:468`）</h3>
 * 用下拉而不是输入框：自由输入会造出检索侧永远匹配不到的动词，而"图里积压着没人查的边"
 * 是看不出来的（边数照涨）。
 */
const PREDICATES = ['基于', '提出者', '包含', '使用', '依赖', '属于', '负责', '引用'] as const

const edgeVisible = ref(false)
const edgeTarget = ref<MissQuestion | null>(null)
const edgeSubmitting = ref(false)
const edgeForm = ref({ subject: '', predicate: '基于' as string, object: '', docId: undefined as number | undefined, chunkId: undefined as number | undefined })
/** 文档候选（跨库全量）与分片候选（按选中的文档拉）。 */
const edgeDocs = ref<{ id: number; title: string; kbName?: string }[]>([])
const edgeChunks = ref<{ id: number; orderNo: number; preview: string }[]>([])
const edgeDocsLoading = ref(false)
const edgeChunksLoading = ref(false)

function openGraphEdge(item: MissQuestion) {
  edgeTarget.value = item
  // 主体/客体默认带入问题原文：`S9-M12-C` 立的那条"实体名逐字照抄"规则在这里同样成立 ——
  // 正文里逐字出现过的词才回得到原文
  edgeForm.value = { subject: item.question, predicate: '基于', object: '', docId: undefined, chunkId: undefined }
  edgeChunks.value = []
  edgeVisible.value = true
  void loadEdgeDocs()
}

/**
 * 文档候选：只列**已解析成功（READY）**的 —— 未解析的文档没有分片，选它锚不了任何东西。
 *
 * ⚠️ `GET /doc/all` **不吃 `status` 参数**（它只有 keyword/kbId/type/page/size）⇒
 * 筛选只能在前端做。列表本身可用，但"选了没解析完的文档"这件事挡不住 ——
 * 那由**后端**在提交时拒（`GraphEdgeService` 校验 `status = READY`，报「尚未解析成功」）。
 * 这里只做"尽量不让人选到"，判据不依赖它。
 */
async function loadEdgeDocs() {
  edgeDocsLoading.value = true
  try {
    const res = await docApi.all({ size: 100 })
    edgeDocs.value = res.list
      .filter((d) => d.status === 'READY')
      .map((d) => ({ id: d.id, title: d.title, kbName: d.kbName }))
  } catch {
    edgeDocs.value = []
    ElMessage.warning('文档列表拉取失败，暂时无法选择锚点文档')
  } finally {
    edgeDocsLoading.value = false
  }
}

/**
 * 选中文档后拉它的分片。
 *
 * ⚠️ `chunkId` 必须在换文档时**清空** —— 留着上一篇文档的分片 id，后端会以
 * 「分片 X 不属于文档 Y」拒绝，而用户看到的只是"提交失败"，看不出是自己换了文档。
 */
async function loadEdgeChunks(docId: number | undefined) {
  edgeForm.value.chunkId = undefined
  edgeChunks.value = []
  if (!docId) return
  edgeChunksLoading.value = true
  try {
    const res = await docApi.chunks(docId, 'all', 1, 100)
    edgeChunks.value = res.list.map((c) => ({
      id: c.id,
      orderNo: c.index,
      preview: (c.content ?? '').slice(0, 40),
    }))
  } catch {
    ElMessage.warning('分片列表拉取失败')
  } finally {
    edgeChunksLoading.value = false
  }
}

async function submitGraphEdge() {
  const t = edgeTarget.value
  if (!t) return
  const f = edgeForm.value
  if (!f.subject.trim() || !f.object.trim()) {
    ElMessage.warning('主体与客体都要填')
    return
  }
  if (!f.docId || !f.chunkId) {
    ElMessage.warning('请先选锚点文档与分片 —— 没有锚点的边回不到原文')
    return
  }
  edgeSubmitting.value = true
  try {
    const res = await opsApi.addGraphEdge(t.id, {
      subject: f.subject.trim(),
      predicate: f.predicate,
      object: f.object.trim(),
      docId: f.docId,
      chunkId: f.chunkId,
    })
    // 🔴 显示**后端回的 `detail`**，不写死一句"已补边"：
    //    `applied=false`（图库不可用）与"真的补上了"必须长得不一样
    if (res.applied) {
      ElMessage.success(`已补边：${res.detail}`)
      edgeVisible.value = false
    } else {
      ElMessageBox.alert(
        `这条边**没有**落库。\n\n原因：${res.detail}\n\n可以稍后重试 —— 图库不可用不影响文档与问答。`,
        '补边未生效',
        { type: 'warning', confirmButtonText: '知道了' },
      )
    }
  } catch (e) {
    // 参数类错误（动词越界 / 分片不属于该文档 / 文档不可见）由后端给可读文案
    ElMessage.error(e instanceof Error ? e.message : '补边失败')
  } finally {
    edgeSubmitting.value = false
  }
}

/**
 * 需求 §1.5 的「用这些真实问法重新生成 HyQ」入口 —— 🔴 **本版本不交付**。
 *
 * 原实现弹的是 `ElMessage.success('已提交…任务，完成后通过通知中心提醒')`，
 * 而它**一个请求都没发**（承 `T-908`「不返回假 taskId」的判例：假启动比没有入口更糟，
 * 因为它让人以为已经做了）。真重跑要动检索语料重建（属 `T-910`/`T-710`），
 * 而需求那一行写的是「**建议**提供」—— 所以这里**如实说明**，不假装。
 */
function regenerateHyq() {
  ElMessageBox.alert(
    `这一栏的真实问法确实可以当语料用，但「一键重跑 HyQ」本版本**未交付** ——
它要连带重建已入库文档的假设问句（属 T-910 / T-710 的范围）。

现在能做的是：把这一栏导出成报表（右上角「导出报表」），
交给人或外部流程去用。`,
    '未交付的功能',
    { type: 'info', confirmButtonText: '知道了' },
  )
}

/**
 * 导出运营报表（需求 §1.7 / §1.8）。
 *
 * ⚠️ **筛选条件要一起发**：导出的是页面上筛出来的那张表。
 * 原来这里只调 `qaExport()` 不带参数 —— 后端会把**全部**清单导出去，
 * 于是用户拿到的与他在页面上看到的不是同一批数据（而报表的用途正是"汇报我看到的那张表"）。
 * ⚠️ 提示语用**后端回的 `note`** 而不是前端写死一句：写死的话，
 * "后端说不支持"与"后端说已提交"在前端**长得一模一样**。
 */
async function exportReport() {
  const res = await opsApi.qaExport({
    status: statusFilter.value,
    kbId: kbFilter.value,
    keyword: keyword.value || undefined,
  })
  ElMessage.success(res.note || '导出任务已创建，完成后通过通知中心提醒')
}
</script>

<template>
  <div v-loading="loading" class="page ops-qa">
    <div class="page-head">
      <div>
        <h2 class="page-title">问答运营</h2>
        <p class="page-desc">哪些问题没答好 → 沉淀成清单，驱动补内容（知识库唯一能自我演进的机制）</p>
      </div>
      <div class="head-actions">
        <el-select v-model="range" style="width: 130px" @change="reloadStats">
          <el-option label="近 7 天" value="7d" />
          <el-option label="近 30 天" value="30d" />
          <el-option label="近 90 天" value="90d" />
        </el-select>
        <el-button :icon="Download" @click="exportReport">导出报表</el-button>
      </div>
    </div>

    <!-- 四个统计卡 -->
    <div v-if="overview" class="stat-row">
      <div class="stat-card">
        <div class="sc-val">{{ overview.totalQuestions.toLocaleString() }}</div>
        <div class="sc-label">提问总数</div>
      </div>
      <div class="stat-card danger">
        <div class="sc-val">{{ overview.missed }}<span class="sc-sub">{{ overview.missedRate }}%</span></div>
        <div class="sc-label">未命中提问（Top1 &lt; {{ overview.missThreshold }}）</div>
      </div>
      <div class="stat-card">
        <div class="sc-val">{{ overview.avgTopScore ?? '—' }}</div>
        <div class="sc-label">平均顶级召回相似度</div>
      </div>
      <div class="stat-card warn">
        <div class="sc-val">{{ overview.pending }}<el-badge is-dot class="sc-dot" /></div>
        <div class="sc-label">待处理问题（同步侧边栏角标）</div>
      </div>
    </div>

    <el-card shadow="never" class="mt-12">
      <el-tabs v-model="activeTab">
        <!-- ==================== 未命中问题清单 ==================== -->
        <el-tab-pane name="misses">
          <template #label><span class="tab-l">未命中问题清单<span class="tab-num">{{ missTotal }}</span></span></template>

          <el-alert type="info" :closable="false" show-icon class="mb-12">
            <template #title>
              <span class="alert-text">
                未命中判定线用全局 <b>0.35</b>，与知识库级召回阈值 <b>0.15</b> 是两个不同参数——口径必须与检索侧一致，否则会出现「统计说没命中，但检索日志里明明召回了」。
              </span>
            </template>
          </el-alert>

          <div class="filter-bar">
            <el-select v-model="statusFilter" style="width: 150px" @change="loadMisses">
              <el-option label="全部状态" value="all" />
              <el-option label="待处理" value="PENDING" />
              <el-option label="已加入清单" value="IN_LIST" />
              <el-option label="已解决" value="RESOLVED" />
              <el-option label="已忽略" value="IGNORED" />
            </el-select>
            <el-input v-model="keyword" placeholder="搜索问题原文" :prefix-icon="Search" clearable style="width: 220px" @keyup.enter="loadMisses" @clear="loadMisses" />
            <el-button :icon="Refresh" @click="loadMisses">刷新</el-button>
            <div class="flex-spacer" />
            <template v-if="selected.length">
              <span class="sel-note">已选 {{ selected.length }} 项</span>
              <el-button size="small" @click="batchSet('IN_LIST')">批量加入清单</el-button>
              <el-button size="small" @click="batchSet('IGNORED')">批量忽略</el-button>
            </template>
          </div>

          <el-table :data="misses" row-key="id" @selection-change="(v: MissQuestion[]) => (selected = v)">
            <el-table-column type="selection" width="40" />
            <el-table-column label="用户问题（原始问法）" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="q-cell">
                  <b>{{ row.question }}</b>
                  <span class="tiny muted">
                    {{ row.kbName }}
                    <el-tag v-if="row.hasImageDesc" size="small" type="primary" effect="plain" class="img-tag">
                      <el-icon><Picture /></el-icon>含图片描述
                    </el-tag>
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="提问次数" width="100" sortable :sort-method="(a: MissQuestion, b: MissQuestion) => b.askCount - a.askCount">
              <template #default="{ row }">
                <el-tag size="small" :type="row.askCount >= 10 ? 'danger' : 'info'">{{ row.askCount }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="提问人" width="100">
              <!-- ⚠️ 这里原来用 `prop="asker"`：值为 null 时格子是**空的**，与"这一列没数据"分不开 -->
              <template #default="{ row }">{{ row.asker ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="最高相似度" width="140">
              <template #default="{ row }">
                <span v-if="row.scoreAfter != null" class="score-up">
                  <span class="mono muted">{{ row.topScore ?? '—' }}</span> → <b class="mono">{{ row.scoreAfter }}</b>
                </span>
                <span v-else class="mono score-low">{{ row.topScore ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="最近提问" width="140">
              <template #default="{ row }"><span class="tiny muted">{{ row.lastAskAt }}</span></template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="missTag(row.status).type">{{ missTag(row.status).text }}</el-tag>
                <div v-if="row.ignoredBy" class="tiny muted">by {{ row.ignoredBy }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="240" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" :icon="Upload" @click="supplement(row)">补文档</el-button>
                <el-button size="small" text :icon="Share" @click="openGraphEdge(row)">图谱补边</el-button>
                <el-button size="small" text :icon="Refresh" @click="replay(row)">重放验证</el-button>
                <el-dropdown trigger="click" @command="(cmd: string) => setStatus(row, cmd as MissStatus)">
                  <el-button size="small" text>更多</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="IN_LIST">加入清单</el-dropdown-item>
                      <el-dropdown-item command="RESOLVED">标记已解决</el-dropdown-item>
                      <el-dropdown-item command="IGNORED" divided>忽略</el-dropdown-item>
                      <el-dropdown-item v-if="row.status === 'IGNORED'" command="PENDING">恢复为待处理</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
            </el-table-column>
          </el-table>

          <div class="hint mt-8">
            默认按提问次数降序，把「很多人问但答不上来」排在最前。用户问法<b>保持原样不做归一化改写</b>——真实问法是校准 HyQ 的原始素材。
            标记<b>「含图片描述」</b>的问题，其知识库文档包含图片，且图片已由 VL 模型转成文字描述参与检索；若此类问题持续未命中，优先排查 VL 描述质量（而非文本切分）。
            <br />⚠️ <b>本版本还不会出现这个标记</b> —— 检索打点里没有「这次提问带没带图」这一列，后端无从判断，故如实<b>不发这个字段</b>（不猜一个）。
          </div>
        </el-tab-pane>

        <!-- ==================== 高频问题榜 ==================== -->
        <el-tab-pane name="top">
          <template #label><span class="tab-l">高频问题榜</span></template>

          <div class="card-head-row mb-12">
            <div class="hint">正面看：确认主流需求优先保证质量；<b>反向看（更有价值）</b>：真实问法是校准 HyQ 生成提示词的语料——分数偏低说明 HyQ 与用户实际问法有偏差。</div>
            <el-button :icon="MagicStick" @click="regenerateHyq">用这些真实问法重新生成 HyQ</el-button>
          </div>

          <el-alert type="info" :closable="false" show-icon class="mb-12">
            <template #title>
              <span class="alert-text">
                「次数」是<b>归一化聚合</b>的次数——同一问法的不同写法必然合并（<b>怎么配？</b> ≡ <b>怎么配</b>），
                而<b>意思一样但问法不同</b>的不合并（那要语义聚类，阈值暂无真实语料可校准，已挂起）。
                ⇒ 读作「这条问法被问了几次」，不是「有几个人问了同一个意思」。
              </span>
            </template>
          </el-alert>

          <div v-if="!topQuestions.length" class="empty-hint">
            这段时间窗里没有提问打点 —— <b>不是</b>「没有高频问题」，而是还没有数据。
          </div>

          <div v-for="t in topQuestions" :key="t.question" class="top-row">
            <div class="tr-q">{{ t.question }}</div>
            <div class="tr-bar">
              <i :style="{ width: (t.count / maxTopCount) * 100 + '%' }" :class="{ low: scoreLow(t.avgTopScore) }" />
            </div>
            <span class="tr-count mono">{{ t.count }}</span>
            <span class="tr-score mono" :class="{ low: scoreLow(t.avgTopScore) }">Top1 {{ scoreText(t.avgTopScore) }}</span>
          </div>
        </el-tab-pane>

        <!-- ==================== 点踩原因归因 ==================== -->
        <el-tab-pane name="feedback">
          <template #label><span class="tab-l">点踩原因归因</span></template>

          <div v-if="feedback" class="fb-top">
            <div class="fb-rate">
              <div class="fr-val">{{ feedback.negativeRate }}%</div>
              <div class="fr-label">负反馈率（阈值 {{ feedback.threshold }}%）</div>
              <el-tag size="small" :type="feedback.negativeRate < feedback.threshold ? 'success' : 'danger'">
                {{ feedback.negativeRate < feedback.threshold ? '低于阈值，健康' : '超过阈值，需关注' }}
              </el-tag>
            </div>
            <div class="fb-note">
              <el-icon><InfoFilled /></el-icon>
              <span v-if="topTwoShare != null">
                「答案不准确」+「没找到相关内容」合计占 <b>{{ topTwoShare }}%</b>，是两个最值得投入优化的方向。
              </span>
              <span v-else>这段时间窗里还没有点踩 —— 占比算不出来（不是 0%）。</span>
            </div>
          </div>

          <div v-if="feedback" class="fb-list">
            <div v-for="r in feedback.reasons" :key="r.reason" class="fb-row">
              <span class="fb-label">{{ r.reason }}</span>
              <div class="fb-bar"><i :style="{ width: r.percent + '%' }" /></div>
              <span class="fb-val mono">{{ r.count }} 次 · {{ r.percent }}%</span>
            </div>
            <!--
              🔴 未填原因那一行**必须显示**：点踩时原因可不填（需求 §6.8"可选"），
              那部分不属于任何原因、也不能丢 —— 少了它，下面那行"合计 = 总数"就对不上，
              而用户会以为系统的账错了（真正错的是需求 §1.10 那句验收要点）
            -->
            <div v-if="unreasoned > 0" class="fb-row">
              <span class="fb-label">（点踩未填原因）</span>
              <div class="fb-bar"><i class="muted-bar" :style="{ width: unreasonedPercent + '%' }" /></div>
              <span class="fb-val mono">{{ unreasoned }} 次 · {{ unreasonedPercent }}%</span>
            </div>
            <div class="hint mt-8">
              校验口径：原因合计 {{ negativeTop }} + 未填原因 {{ unreasoned }} = 负反馈总数
              {{ feedback.negativeTotal }}
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 闭环五步说明 -->
    <el-card shadow="never" class="mt-12">
      <template #header>
        <div class="card-head-row">
          <h3><el-icon><ChatDotRound /></el-icon> 未命中闭环五步</h3>
          <span class="hint">第 4 步是命门：没有它，「已解决」只能靠人工说一句"我补过了"</span>
        </div>
      </template>
      <div class="loop-steps">
        <div class="loop-step">
          <span class="ls-no">1</span>
          <div><div class="ls-title">未命中沉淀</div><div class="hint">低于阈值自动入池，无需人工发现</div></div>
        </div>
        <span class="ls-arrow">→</span>
        <div class="loop-step">
          <span class="ls-no">2</span>
          <div><div class="ls-title">一键补文档</div><div class="hint">带问题跳上传页，问题文本作为补录备注</div></div>
        </div>
        <span class="ls-arrow">→</span>
        <div class="loop-step">
          <span class="ls-no">3</span>
          <div><div class="ls-title">重建索引</div><div class="hint">只重建受影响的知识库</div></div>
        </div>
        <span class="ls-arrow">→</span>
        <div class="loop-step highlight">
          <span class="ls-no"><el-icon><MagicStick /></el-icon></span>
          <div><div class="ls-title">自动重放验证</div><div class="hint">重跑原问题，同时校验分数提升 + 召回的是补录文档</div></div>
        </div>
        <span class="ls-arrow">→</span>
        <div class="loop-step">
          <span class="ls-no"><el-icon><CircleCheck /></el-icon></span>
          <div><div class="ls-title">标记已解决</div><div class="hint">分数达标自动关闭，未达标退回待处理</div></div>
        </div>
      </div>
      <div class="loop-note">
        <el-icon><WarningFilled /></el-icon>
        重放未达标时状态<b>退回待处理</b>；「已忽略」记录忽略人且不计入待办数（但仍保留在清单里可查）。
      </div>
    </el-card>
  </div>

  <!-- ==================== 图谱补边（T-C04 · 需求 07:544） ==================== -->
  <el-dialog v-model="edgeVisible" title="图谱补边" width="620px" :close-on-click-modal="false">
    <el-alert type="info" :closable="false" show-icon class="mb-12">
      <template #title>
        <span class="alert-text">
          针对这条未命中问题，人工补一条<b>实体关系边</b>（置信度固定 <b>1.0</b>）。
          有些问题是文档本来就有、<b>只是模型没抽出那条关系</b> —— 重传文档解决不了，只能在这里补。
          <br />⚠️ <b>锚点（文档 + 分片）必填</b>：需求要求每条关系都能溯源回原文，挑错分片会被后端拒绝。
        </span>
      </template>
    </el-alert>

    <el-form label-width="86px" label-position="right">
      <el-form-item label="未命中问题">
        <div class="edge-question">{{ edgeTarget?.question }}</div>
      </el-form-item>
      <el-form-item label="主体">
        <el-input v-model="edgeForm.subject" placeholder="实体名（建议照抄正文原词）" maxlength="128" />
      </el-form-item>
      <el-form-item label="关系">
        <el-select v-model="edgeForm.predicate" style="width: 220px">
          <el-option v-for="p in PREDICATES" :key="p" :label="p" :value="p" />
        </el-select>
        <span class="hint ml-8">动词是闭集（需求固定 8 个），自由输入检索侧匹配不到</span>
      </el-form-item>
      <el-form-item label="客体">
        <el-input v-model="edgeForm.object" placeholder="实体名（建议照抄正文原词）" maxlength="128" />
      </el-form-item>
      <el-form-item label="锚点文档">
        <el-select
          v-model="edgeForm.docId"
          filterable
          :loading="edgeDocsLoading"
          placeholder="只列已解析成功的文档"
          style="width: 100%"
          @change="loadEdgeChunks"
        >
          <el-option v-for="d in edgeDocs" :key="d.id" :label="d.title" :value="d.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="锚点分片">
        <el-select
          v-model="edgeForm.chunkId"
          filterable
          :loading="edgeChunksLoading"
          :disabled="!edgeForm.docId"
          placeholder="先选文档"
          style="width: 100%"
        >
          <el-option
            v-for="c in edgeChunks"
            :key="c.id"
            :label="`#${c.orderNo} · ${c.preview}`"
            :value="c.id"
          />
        </el-select>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="edgeVisible = false">取消</el-button>
      <el-button type="primary" :loading="edgeSubmitting" @click="submitGraphEdge">补这条边</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ops-qa {
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
.head-actions {
  display: flex;
  gap: 10px;
  flex: none;
}
.mt-12 {
  margin-top: 12px;
}
.mt-8 {
  margin-top: 8px;
}
.mb-12 {
  margin-bottom: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.tiny {
  font-size: 12px;
}
.mono {
  font-family: var(--font-mono);
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.hint b {
  color: var(--el-text-color-regular);
}
.alert-text {
  font-size: 12.5px;
  line-height: 1.6;
}
.tab-l {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.tab-num {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  font-size: 11px;
  border-radius: 8px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.card-head-row h3 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}

/* 统计卡 */
.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.stat-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 16px 18px;
  background: #fff;
}
.stat-card.danger {
  border-color: #fde2e2;
  background: #fffafa;
}
.stat-card.warn {
  border-color: #f0d9b5;
  background: #fffbf5;
}
.sc-val {
  font-size: 26px;
  font-weight: 600;
  font-family: var(--font-mono);
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.sc-sub {
  font-size: 13px;
  font-weight: 400;
  color: var(--el-color-danger);
}
.sc-dot {
  margin-left: 6px;
  vertical-align: super;
}
.sc-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
}

/* 清单 */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.flex-spacer {
  flex: 1;
}
.sel-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.q-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.q-cell b {
  font-size: 13px;
}
.img-tag {
  margin-left: 6px;
  vertical-align: middle;
}
.img-tag .el-icon {
  margin-right: 2px;
  vertical-align: -1px;
}
.score-low {
  color: var(--el-color-danger);
  font-weight: 600;
}
.score-up b {
  color: var(--el-color-success);
}

/* 高频问题榜 */
.top-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.tr-q {
  width: 260px;
  font-size: 13px;
  flex: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tr-bar {
  flex: 1;
  height: 10px;
  background: var(--el-border-color-extra-light);
  border-radius: 5px;
  overflow: hidden;
}
.tr-bar i {
  display: block;
  height: 100%;
  border-radius: 5px;
  background: var(--el-color-primary);
  transition: width 0.4s;
}
.tr-bar i.low {
  background: var(--el-color-warning);
}
.tr-count {
  width: 44px;
  text-align: right;
  font-size: 13px;
  flex: none;
}
.tr-score {
  width: 100px;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.tr-score.low {
  color: var(--el-color-warning);
  font-weight: 600;
}

/* 点踩归因 */
.fb-top {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-bottom: 20px;
}
.fb-rate {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 16px 20px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  flex: none;
}
.fr-val {
  font-size: 28px;
  font-weight: 600;
  font-family: var(--font-mono);
}
.fr-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.fb-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  color: #3a6ea5;
  background: #f5f9ff;
  border-radius: 8px;
  padding: 12px 14px;
  line-height: 1.7;
}
.fb-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.fb-label {
  width: 110px;
  font-size: 13px;
  flex: none;
}
.fb-bar {
  flex: 1;
  height: 8px;
  background: var(--el-border-color-extra-light);
  border-radius: 4px;
  overflow: hidden;
}
.fb-bar i {
  display: block;
  height: 100%;
  background: var(--el-color-danger);
  border-radius: 4px;
}
.fb-val {
  width: 130px;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex: none;
}
/* 「点踩未填原因」那一行：与"某个原因"区分开 —— 它不是一个原因，是**没有原因** */
.fb-bar i.muted-bar {
  background: var(--el-border-color-darker);
}

/* 空态提示：说清"是没数据"而不是"是零" */
.empty-hint {
  padding: 16px 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
}

/* 闭环 */
.loop-steps {
  display: flex;
  align-items: stretch;
  gap: 8px;
}
.loop-step {
  flex: 1;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.loop-step.highlight {
  border-color: var(--el-color-primary-light-5);
  background: #f7fbff;
}
.ls-no {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex: none;
}
.ls-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 3px;
}
.ls-arrow {
  display: flex;
  align-items: center;
  color: var(--el-text-color-placeholder);
}
.loop-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
  padding: 10px 12px;
  background: var(--el-color-warning-light-9);
  border-radius: 8px;
  font-size: 12.5px;
  color: #8a6d3b;
  line-height: 1.6;
}
/* 补边弹窗里那条问题的原文：只读展示，不截断到看不清 */
.edge-question {
  width: 100%;
  padding: 6px 10px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
}
.ml-8 {
  margin-left: 8px;
}
</style>
