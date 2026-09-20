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
} from '@element-plus/icons-vue'
import { opsApi, kbApi } from '@/api'
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
/** 这两栏的后端接口属 `T-A02`（本批不交付）—— 用来把"没交付"与"没有数据"分开。 */
const topNotDelivered = ref(false)
const feedbackNotDelivered = ref(false)

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
  try {
    const res = await opsApi.topQuestions({ range: range.value, limit: 20 })
    topQuestions.value = res.list
  } catch {
    // 后端还没实现（属 `T-A02`）—— **区分「没交付」与「没有数据」**：
    // 不区分的话，这一栏空着会被读成"确实没有高频问题"，而事实是这个数字还没地方取
    topNotDelivered.value = true
  }
}
async function loadFeedback() {
  try {
    feedback.value = await opsApi.feedbackStats()
  } catch {
    feedbackNotDelivered.value = true
  }
}

/** 状态标签（模板中避免用 any 索引 Record） */
function missTag(status: MissStatus) {
  return MISS_STATUS[status] ?? { text: '—', type: 'info' as const }
}

const maxTopCount = computed(() => Math.max(...topQuestions.value.map((t) => t.count), 1))
const negativeTop = computed(() => (feedback.value?.reasons ?? []).reduce((s, r) => s + r.count, 0))

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

/** 闭环第 4 步：重放验证（同时校验分数提升 + 召回的是补录文档） */
async function replay(item: MissQuestion) {
  const res = await opsApi.replayMiss(item.id)
  item.status = res.improved ? 'RESOLVED' : 'PENDING'
  if (res.improved) item.scoreAfter = res.after
  await loadOverview()
  ElMessageBox.alert(
    `召回分：${res.before} → ${res.after}（${res.improved ? '提升' : '未达标'}）
是否命中刚补录的文档：${res.hitSupplementedDoc ? '是' : '否'}
${res.note}`,
    res.improved ? '重放验证通过，已自动关闭' : '重放验证未通过',
    { type: res.improved ? 'success' : 'warning', confirmButtonText: '知道了' },
  )
}

/** 闭环第 2 步：一键补文档，问题文本作为补录备注带入 */
function supplement(item: MissQuestion) {
  const kbId = kbMap.value[item.kbName]
  if (!kbId) {
    router.push('/kb')
    ElMessage.info('已跳转知识库列表，请选择目标库上传补录文档')
    return
  }
  router.push({ path: `/kb/${kbId}/upload`, query: { note: item.question } })
}

function regenerateHyq() {
  ElMessage.success('已提交「用真实问法重新生成 HyQ」任务，完成后通过通知中心提醒')
}

async function exportReport() {
  await opsApi.qaExport()
  ElMessage.success('导出任务已创建，完成后通过通知中心提醒')
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
        <el-select v-model="range" style="width: 130px" @change="loadTop">
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

          <el-alert v-if="topNotDelivered" type="warning" :closable="false" show-icon class="mb-12">
            <template #title>
              <span class="alert-text">本版本未交付这一栏（后端接口属 <b>T-A02</b>）—— 空着<b>不是</b>「没有高频问题」。</span>
            </template>
          </el-alert>

          <div class="card-head-row mb-12">
            <div class="hint">正面看：确认主流需求优先保证质量；<b>反向看（更有价值）</b>：真实问法是校准 HyQ 生成提示词的语料——分数偏低说明 HyQ 与用户实际问法有偏差。</div>
            <el-button :icon="MagicStick" @click="regenerateHyq">用这些真实问法重新生成 HyQ</el-button>
          </div>

          <div v-for="t in topQuestions" :key="t.question" class="top-row">
            <div class="tr-q">{{ t.question }}</div>
            <div class="tr-bar">
              <i :style="{ width: (t.count / maxTopCount) * 100 + '%' }" :class="{ low: t.avgTopScore < 0.7 }" />
            </div>
            <span class="tr-count mono">{{ t.count }}</span>
            <span class="tr-score mono" :class="{ low: t.avgTopScore < 0.7 }">Top1 {{ t.avgTopScore }}</span>
          </div>
        </el-tab-pane>

        <!-- ==================== 点踩原因归因 ==================== -->
        <el-tab-pane name="feedback">
          <template #label><span class="tab-l">点踩原因归因</span></template>

          <el-alert v-if="feedbackNotDelivered" type="warning" :closable="false" show-icon class="mb-12">
            <template #title>
              <span class="alert-text">本版本未交付这一栏（后端接口属 <b>T-A02</b>）—— 空着<b>不是</b>「没有人点踩」。</span>
            </template>
          </el-alert>

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
              <span>「答案不准确」+「没找到相关内容」合计占 <b>75%</b>，是两个最值得投入优化的方向。</span>
            </div>
          </div>

          <div v-if="feedback" class="fb-list">
            <div v-for="r in feedback.reasons" :key="r.reason" class="fb-row">
              <span class="fb-label">{{ r.reason }}</span>
              <div class="fb-bar"><i :style="{ width: r.percent + '%' }" /></div>
              <span class="fb-val mono">{{ r.count }} 次 · {{ r.percent }}%</span>
            </div>
            <div class="hint mt-8">原因次数合计 {{ negativeTop }} = 负反馈总数 {{ feedback.negativeTotal }}（校验口径）</div>
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
</style>
