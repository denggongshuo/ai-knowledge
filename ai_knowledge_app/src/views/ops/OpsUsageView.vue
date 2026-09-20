<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { Download, InfoFilled, TrendCharts, UserFilled, DataLine } from '@element-plus/icons-vue'
import { opsApi } from '@/api'
import type { UsageOverview, TrendPoint, DeptUsage, KbHeat, ActiveUserStat } from '@/types'

const loading = ref(false)
const range = ref('7d')

const overview = ref<UsageOverview | null>(null)
const trend = ref<TrendPoint[]>([])
const trendTotal = ref(0)
const depts = ref<DeptUsage[]>([])
const deptSum = ref({ active: 0, users: 0, questions: 0 })
const heats = ref<KbHeat[]>([])
const heatTotal = ref(0)
const othersQuestions = ref(0)
const othersPercent = ref<number | null>(null)
const topUsers = ref<ActiveUserStat[]>([])
const topTotal = ref(0)

/**
 * 拉五个端点。
 *
 * 🔴 **`range` 必须传下去**（`S8-M10-E` 接真时修的）。改之前五个方法里四个把
 * `range: '7d'` 写死、页面也从不传 —— 于是右上角那个下拉框**换了不生效**，
 * 而页面上的数**看着完全正常**（它确实是一份正确的 7 天统计）。
 */
async function load() {
  loading.value = true
  try {
    const r = range.value
    const [o, t, d, h, u] = await Promise.all([
      opsApi.usageOverview({ range: r }),
      opsApi.trend({ range: r }),
      opsApi.byDept({ range: r }),
      opsApi.byKb({ range: r }),
      opsApi.topUsers({ range: r, limit: 10 }),
    ])
    overview.value = o
    trend.value = t.list
    trendTotal.value = t.total
    depts.value = d.list
    deptSum.value = { active: d.active, users: d.users, questions: d.questions }
    heats.value = h.list
    heatTotal.value = h.questions
    othersQuestions.value = h.othersQuestions
    othersPercent.value = h.othersPercent
    topUsers.value = u.list
    topTotal.value = u.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
// 换时间范围要**重新拉**，否则选 30 天看到的还是 7 天的数（那正是上面那条注释说的事）
watch(range, load)

// ==================== 空值一律显示 `—` ====================
// 后端把"算不出来"如实回 `null`（分母为 0 时）。页面**不能**把它渲染成
// `null%` / 空字符串 / 0 —— 那三个都会被读成一个**结论**，而真相是"没有分母"。
const DASH = '—'
function num1(v: number | null | undefined) {
  return v === null || v === undefined ? DASH : v
}
/** 百分数：`null` ⇒ `—`；否则固定一位小数（与后端的舍入口径一致）。 */
function pct(v: number | null | undefined) {
  return v === null || v === undefined ? DASH : v.toFixed(1) + '%'
}
/** 部门活跃率：`totalUsers` 为 0 时**也算不出来**（0/0）。 */
function activeRateOf(d: DeptUsage) {
  return d.totalUsers === 0 ? DASH : Math.round((d.activeUsers / d.totalUsers) * 100) + '%'
}

const maxTrend = computed(() => Math.max(...trend.value.map((t) => t.value), 1))
const maxHeat = computed(() => Math.max(...heats.value.map((h) => h.questions), 1))

/** 部门读数：提问数高+人均低 = 推广到了但没形成习惯；提问数低+人均高 = 少数人的工具 */
function deptSignal(d: DeptUsage): { text: string; type: 'warning' | 'success' | 'info' } {
  const avg = overview.value?.avgPerUser
  // 人均**算不出来**时不给读数 —— 拿"没有分母"去和一个数比大小，结论必然是错的
  if (avg === null || avg === undefined || d.perUser === null) {
    return { text: '样本不足', type: 'info' }
  }
  if (d.perUser < avg && d.questions > 0) return { text: '人多但用得浅', type: 'warning' }
  if (d.perUser >= avg && d.activeUsers / Math.max(d.totalUsers, 1) < 0.4) {
    return { text: '用得深但没推开', type: 'info' }
  }
  return { text: '健康', type: 'success' }
}

/**
 * 相似度的着色。
 *
 * 🔴 `null`（该用户一行有分数的都没有）**必须单独判**：JS 里 `null < 0.7` 是 `true`，
 * 直接写 `v < 0.7` 会把每一个"没测到"的人染成"分低"—— 那是一个**关于内容的结论**，
 * 与"没测到"是两件事（`S8-M10-B` 定的「判不了必须与判否分开报」）。
 */
function scoreTone(v: number | null) {
  if (v === null) return 'unknown'
  if (v >= 0.78) return 'good'
  if (v >= 0.7) return ''
  return 'low'
}
function isWarn(v: number | null) {
  return v !== null && v < 0.7
}

/**
 * 「导出报表」—— **本版本不提供**。
 *
 * ⚠️ 原来这里弹的是「导出任务已创建，完成后通过通知中心提醒」，而**一个请求都没发**：
 * 需求 `08` §3.6 给这一页列的五条端点里**没有导出**（§3.9 的"不做"清单里也没有它），
 * 而「导出运营报表」那个动作在页 11（`POST /ops/qa/export`），导出的是**未命中清单**、
 * 不是使用统计。⇒ 那条 toast 说的是**一件没有发生的事**，按本仓口径要销掉。
 * 处置与页 11 的 `regenerateHyq()` 一致：**如实说明**，不假装。
 */
const exportNote = '本版本未提供使用统计的导出（需求 08 §3.6 未列此端点；页 11 的导出导的是未命中清单）'
</script>

<template>
  <div v-loading="loading" class="page ops-usage">
    <div class="page-head">
      <div>
        <h2 class="page-title">使用统计</h2>
        <p class="page-desc">
          这东西到底有没有人在用 → 判断价值与推广方向 ·
          <span class="hint">口径：「活跃」指周期内至少提问 1 次</span>
        </p>
      </div>
      <div class="head-actions">
        <el-select v-model="range" style="width: 130px">
          <el-option label="近 7 天" value="7d" />
          <el-option label="近 30 天" value="30d" />
          <el-option label="近 90 天" value="90d" />
        </el-select>
        <el-tooltip :content="exportNote" placement="bottom">
          <el-button :icon="Download" disabled>导出报表</el-button>
        </el-tooltip>
      </div>
    </div>

    <!-- 四指标 -->
    <div v-if="overview" class="stat-row">
      <div class="stat-card">
        <div class="sc-val">
          {{ overview.activeUsers }}<span class="sc-of">/ {{ overview.totalUsers }}</span>
        </div>
        <div class="sc-bar"><i :style="{ width: (overview.activeRate ?? 0) + '%' }" /></div>
        <div class="sc-label">活跃用户 · 覆盖率 {{ pct(overview.activeRate) }}</div>
      </div>
      <div class="stat-card">
        <div class="sc-val">{{ overview.totalQuestions.toLocaleString() }}</div>
        <div class="sc-label">提问总数（使用规模）</div>
      </div>
      <div class="stat-card highlight">
        <div class="sc-val">{{ num1(overview.avgPerUser) }}</div>
        <div class="sc-label">人均提问（使用深度，&lt; 3 次说明没形成习惯）</div>
      </div>
      <div class="stat-card">
        <div class="sc-val">{{ overview.coveredDepts }}<span class="sc-of">/ {{ overview.totalDepts }}</span></div>
        <div class="sc-label">覆盖部门（推广广度）</div>
      </div>
    </div>

    <div class="grid-2 mt-12">
      <!-- 趋势 -->
      <el-card shadow="never">
        <template #header>
          <div class="card-head-row">
            <h3><el-icon><TrendCharts /></el-icon> 近 7 天提问趋势</h3>
            <span class="hint">合计 {{ trendTotal.toLocaleString() }} = 提问总数</span>
          </div>
        </template>

        <div class="chart">
          <div v-for="t in trend" :key="t.date" class="bar-col">
            <span class="bar-val mono">{{ t.value }}</span>
            <div class="bar-track">
              <i
                :style="{ height: (t.value / maxTrend) * 100 + '%' }"
                :class="{ weekend: t.weekend, today: t.today }"
              />
            </div>
            <span class="bar-label" :class="{ weekend: t.weekend }">{{ t.label.slice(0, 5) }}</span>
          </div>
        </div>

        <div v-if="overview" class="trend-sum">
          <div class="ts-item"><span>工作日均值</span><b class="mono">{{ num1(overview.workdayAvg) }}</b></div>
          <div class="ts-item"><span>周末均值</span><b class="mono">{{ num1(overview.weekendAvg) }}</b></div>
          <div class="ts-item"><span>日均</span><b class="mono">{{ num1(overview.dailyAvg) }}</b></div>
        </div>

        <div class="guide-note">
          <el-icon><InfoFilled /></el-icon>
          <span>
            工作日与周末的日均差说明<b>使用场景是否集中在工作时段</b>（工作日 {{ num1(overview?.workdayAvg) }} vs
            周末 {{ num1(overview?.weekendAvg) }}）——
            差距大时可把<b>索引重建、模型切换这类重任务安排到夜间或周末执行</b>。某天异常高峰通常对应一次推广或内容上线，可作效果验证。
          </span>
        </div>
      </el-card>

      <!-- 知识库热度 -->
      <el-card shadow="never">
        <template #header>
          <div class="card-head-row">
            <h3><el-icon><DataLine /></el-icon> 知识库热度</h3>
            <span class="hint">合计 {{ (heatTotal + othersQuestions).toLocaleString() }}（与检索质量页一致）</span>
          </div>
        </template>
        <div v-for="h in heats" :key="h.kbName" class="heat-row">
          <span class="hr-name ellipsis">{{ h.kbName }}</span>
          <div class="hr-bar"><i :style="{ width: (h.questions / maxHeat) * 100 + '%' }" /></div>
          <span class="hr-val mono">{{ h.questions }}</span>
          <span class="hr-pct mono">{{ pct(h.percent) }}</span>
        </div>
        <!-- 「其他」桶与单库行**并排显示**：它一起参与占比分配 ⇒ 两者合计恰好 100% -->
        <div v-if="othersQuestions > 0" class="heat-row">
          <span class="hr-name ellipsis">其他（跨库提问）</span>
          <div class="hr-bar"><i :style="{ width: (othersQuestions / maxHeat) * 100 + '%' }" /></div>
          <span class="hr-val mono">{{ othersQuestions }}</span>
          <span class="hr-pct mono">{{ pct(othersPercent) }}</span>
        </div>

        <!--
          🔴 诚实空态：**当前链路上单库行恒为 0** ——
          `ChatRequest` 没有 `kbIds`、提问页也没有库选择器，每次提问覆盖**全部可见库**
          ⇒ 一次提问装不进任何一个库，只能归「其他」。
          这不是"数据还没到"，是**源不存在** ⇒ 如实说明，**不拿别的数填满它**。
        -->
        <el-alert
          v-if="heats.length === 0"
          type="info"
          :closable="false"
          class="mt-12"
          title="暂时没有「单库提问」的行"
          description="当前每次提问都覆盖全部可见库（提问页没有库范围选择），因此无法把一次提问归给某一个库 —— 全部计入上面的「其他（跨库提问）」。这与「检索质量分析」页的各库排行是同一个口径、同一个数。"
        />

        <div class="guide-note mt-12">
          <el-icon><InfoFilled /></el-icon>
          <span>各知识库被提问次数与占比，合计 = 提问总数。与「检索质量分析」页的知识库提问数<b>必须一致</b>——同一个数不要在两页各写一遍。</span>
        </div>
      </el-card>
    </div>

    <!-- 部门维度 -->
    <el-card shadow="never" class="mt-12">
      <template #header>
        <div class="card-head-row">
          <h3><el-icon><UserFilled /></el-icon> 部门维度</h3>
          <span class="hint">要同时看「提问数」和「人均」——只看提问数会误判（人多的大部门天然占优）</span>
        </div>
      </template>

      <el-table :data="depts" border>
        <el-table-column prop="dept" label="部门" min-width="140" />
        <el-table-column prop="activeUsers" label="活跃用户" width="100" sortable />
        <el-table-column prop="totalUsers" label="部门人数" width="100" />
        <el-table-column label="活跃率" width="100">
          <template #default="{ row }">
            <span class="mono">{{ activeRateOf(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="questions" label="提问数" width="100" sortable />
        <!-- ⚠️ 排序里也要处理 null：`b.perUser - a.perUser` 只要有一边是 null 就是 NaN，
             而 NaN 参与的排序**不会报错**，只会给出一个说不清的顺序 -->
        <el-table-column
          label="人均"
          width="90"
          sortable
          :sort-method="(a: DeptUsage, b: DeptUsage) => (b.perUser ?? -1) - (a.perUser ?? -1)"
        >
          <template #default="{ row }">
            <b class="mono">{{ num1(row.perUser) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="占比" width="100">
          <template #default="{ row }"><span class="mono">{{ pct(row.percent) }}</span></template>
        </el-table-column>
        <el-table-column label="读数" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="deptSignal(row).type">{{ deptSignal(row).text }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-sum">
        <span>合计</span>
        <b class="mono">{{ deptSum.active }} 活跃</b>
        <span>/</span>
        <b class="mono">{{ deptSum.users }} 人</b>
        <span>/</span>
        <b class="mono">{{ deptSum.questions.toLocaleString() }} 提问</b>
        <span>/</span>
        <b class="mono">{{ num1(overview?.avgPerUser) }} 人均</b>
        <span>/</span>
        <b class="mono">100.0%</b>
      </div>

      <div class="guide-note">
        <el-icon><InfoFilled /></el-icon>
        <span>
          <b>提问数高 + 人均低</b> → 人多但用得浅（推广到了但没形成习惯），对策是提升使用频率；
          <b>提问数低 + 人均高</b> → 用得深但没推开（少数人的工具），对策是扩大覆盖面。
          上表「读数」列就是按这条规则判的。
        </span>
      </div>
    </el-card>

    <!-- 活跃用户 TOP -->
    <el-card shadow="never" class="mt-12">
      <template #header>
        <div class="card-head-row">
          <h3><el-icon><UserFilled /></el-icon> 活跃用户 TOP{{ topUsers.length }}<span class="hint">（共 {{ topTotal }} 人）</span></h3>
          <span class="hint">提问多 ≠ 用得好：提问多 + 分低说明他总在问知识库答不上来的问题</span>
        </div>
      </template>

      <div v-if="topUsers.length === 0" class="hint">本窗口内还没有人提问。</div>
      <div class="user-grid">
        <div v-for="(u, i) in topUsers" :key="u.name" class="user-card" :class="{ warn: isWarn(u.avgTopScore) }">
          <div class="uc-head">
            <span class="uc-rank">{{ i + 1 }}</span>
            <div>
              <div class="uc-name">{{ u.name }}</div>
              <div class="tiny muted">{{ u.dept }}</div>
            </div>
            <div class="uc-right">
              <div class="uc-q mono">{{ u.questions }}</div>
              <div class="tiny muted">提问数</div>
            </div>
          </div>
          <div class="uc-score">
            <span class="tiny muted">平均 Top1</span>
            <b class="mono" :class="scoreTone(u.avgTopScore)">{{ u.avgTopScore === null ? DASH : u.avgTopScore.toFixed(3) }}</b>
          </div>
        </div>
      </div>

      <div class="guide-note">
        <el-icon><InfoFilled /></el-icon>
        <span>
          <b>「提问多 + 相似度低」</b>说明他总在问知识库答不上来的问题——可能不是内容缺失，而是<b>他不知道该去哪查</b>，可单独观察甚至给个使用引导。
          反之「提问多 + 分高」是典型的重度满意用户，<b>适合做推广案例</b>。
          ⚠️ 「平均 Top1」的分母**只含有分数的行**（空召回不进）—— 该用户全是空召回时显示 <b>—</b>，
          那是<b>没测到</b>，不是"分低"。用户维度统计仅对管理员开放，且不展示具体提问内容。
        </span>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.ops-usage {
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
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.head-actions {
  display: flex;
  gap: 10px;
  flex: none;
}
.mt-12 {
  margin-top: 12px;
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
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  align-items: start;
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
.stat-card.highlight {
  border-color: var(--el-color-primary-light-7);
  background: #f7fbff;
}
.sc-val {
  font-size: 26px;
  font-weight: 600;
  font-family: var(--font-mono);
}
.sc-of {
  font-size: 14px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  margin-left: 4px;
}
.sc-bar {
  height: 6px;
  background: var(--el-border-color-extra-light);
  border-radius: 3px;
  overflow: hidden;
  margin-top: 10px;
}
.sc-bar i {
  display: block;
  height: 100%;
  background: var(--el-color-primary);
  border-radius: 3px;
}
.sc-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 8px;
}

/* 柱状图 */
.chart {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  height: 200px;
  padding: 10px 4px 0;
}
.bar-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
}
.bar-val {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.bar-track {
  flex: 1;
  width: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.bar-track i {
  width: 60%;
  background: linear-gradient(180deg, #6fb3ff, var(--el-color-primary));
  border-radius: 5px 5px 0 0;
  transition: height 0.5s;
  min-height: 3px;
}
.bar-track i.weekend {
  background: linear-gradient(180deg, #c8c9cc, #a8abb2);
}
.bar-track i.today {
  background: linear-gradient(180deg, #a0cfff, #79bbff);
}
.bar-label {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
  white-space: nowrap;
}
.bar-label.weekend {
  color: var(--el-text-color-placeholder);
}
.trend-sum {
  display: flex;
  gap: 12px;
  margin-top: 14px;
}
.ts-item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 12px;
  background: #f7f9fc;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.ts-item b {
  font-size: 15px;
  color: var(--el-text-color-primary);
}

/* 热度 */
.heat-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 0;
}
.hr-name {
  width: 130px;
  font-size: 12.5px;
  flex: none;
}
.hr-bar {
  flex: 1;
  height: 8px;
  background: var(--el-border-color-extra-light);
  border-radius: 4px;
  overflow: hidden;
}
.hr-bar i {
  display: block;
  height: 100%;
  background: var(--el-color-primary);
  border-radius: 4px;
}
.hr-val {
  width: 54px;
  text-align: right;
  font-size: 12.5px;
  flex: none;
}
.hr-pct {
  width: 54px;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.guide-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
  padding: 10px 12px;
  background: #f5f9ff;
  border-radius: 8px;
  font-size: 12px;
  color: #3a6ea5;
  line-height: 1.7;
}
.guide-note b {
  color: #2b5580;
}
.table-sum {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 10px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}

/* 用户卡 */
.user-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}
.user-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 14px;
}
.user-card.warn {
  border-color: #f0d9b5;
  background: #fffbf5;
}
.uc-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.uc-rank {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  flex: none;
}
.uc-name {
  font-size: 13.5px;
  font-weight: 600;
}
.uc-right {
  margin-left: auto;
  text-align: right;
}
.uc-q {
  font-size: 18px;
  font-weight: 600;
}
.uc-score {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.uc-score b {
  font-size: 15px;
}
.uc-score b.good {
  color: var(--el-color-success);
}
.uc-score b.low {
  color: var(--el-color-danger);
}
/* 🔴 「没测到」既不绿也不红 —— 给它一个**中性**色。染色等于下结论，
   而"这个用户全是空召回"与"他的分数低"是两句相反的话（见 scoreTone 的注释）。 */
.uc-score b.unknown {
  color: var(--el-text-color-secondary);
}
</style>
