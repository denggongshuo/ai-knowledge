<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
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
const topUsers = ref<ActiveUserStat[]>([])

onMounted(async () => {
  loading.value = true
  try {
    const [o, t, d, h, u] = await Promise.all([
      opsApi.usageOverview(),
      opsApi.trend(),
      opsApi.byDept(),
      opsApi.byKb(),
      opsApi.topUsers({ range: '7d', limit: 10 }),
    ])
    overview.value = o
    trend.value = t.list
    trendTotal.value = t.total
    depts.value = d.list
    deptSum.value = { active: d.active, users: d.users, questions: d.questions }
    heats.value = h.list
    heatTotal.value = h.questions
    topUsers.value = u.list
  } finally {
    loading.value = false
  }
})

const maxTrend = computed(() => Math.max(...trend.value.map((t) => t.value), 1))
const maxHeat = computed(() => Math.max(...heats.value.map((h) => h.questions), 1))

/** 部门读数：提问数高+人均低 = 推广到了但没形成习惯；提问数低+人均高 = 少数人的工具 */
function deptSignal(d: DeptUsage): { text: string; type: 'warning' | 'success' | 'info' } {
  const deptAvg = overview.value?.avgPerUser ?? 17.6
  if (d.perUser < deptAvg && d.questions > 100) return { text: '人多但用得浅', type: 'warning' }
  if (d.perUser >= deptAvg && d.activeUsers / d.totalUsers < 0.4) return { text: '用得深但没推开', type: 'info' }
  return { text: '健康', type: 'success' }
}

function scoreClass(v: number) {
  if (v >= 0.78) return 'good'
  if (v >= 0.7) return ''
  return 'low'
}

function exportReport() {
  ElMessage.success('导出任务已创建，完成后通过通知中心提醒')
}
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
        <el-button :icon="Download" @click="exportReport">导出报表</el-button>
      </div>
    </div>

    <!-- 四指标 -->
    <div v-if="overview" class="stat-row">
      <div class="stat-card">
        <div class="sc-val">
          {{ overview.activeUsers }}<span class="sc-of">/ {{ overview.totalUsers }}</span>
        </div>
        <div class="sc-bar"><i :style="{ width: overview.activeRate + '%' }" /></div>
        <div class="sc-label">活跃用户 · 覆盖率 {{ overview.activeRate }}%</div>
      </div>
      <div class="stat-card">
        <div class="sc-val">{{ overview.totalQuestions.toLocaleString() }}</div>
        <div class="sc-label">提问总数（使用规模）</div>
      </div>
      <div class="stat-card highlight">
        <div class="sc-val">{{ overview.avgPerUser }}</div>
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
          <div class="ts-item"><span>工作日均值</span><b class="mono">{{ overview.workdayAvg }}</b></div>
          <div class="ts-item"><span>周末均值</span><b class="mono">{{ overview.weekendAvg }}</b></div>
          <div class="ts-item"><span>日均</span><b class="mono">{{ overview.dailyAvg }}</b></div>
        </div>

        <div class="guide-note">
          <el-icon><InfoFilled /></el-icon>
          <span>
            周末提问量骤降（工作日 {{ overview?.workdayAvg }} vs 周末 {{ overview?.weekendAvg }}），说明使用场景集中在工作时段——
            据此可把<b>索引重建、模型切换这类重任务安排到夜间或周末执行</b>。某天异常高峰通常对应一次推广或内容上线，可作效果验证。
          </span>
        </div>
      </el-card>

      <!-- 知识库热度 -->
      <el-card shadow="never">
        <template #header>
          <div class="card-head-row">
            <h3><el-icon><DataLine /></el-icon> 知识库热度</h3>
            <span class="hint">合计 {{ heatTotal.toLocaleString() }}（与检索质量页一致）</span>
          </div>
        </template>
        <div v-for="h in heats" :key="h.kbName" class="heat-row">
          <span class="hr-name ellipsis">{{ h.kbName }}</span>
          <div class="hr-bar"><i :style="{ width: (h.questions / maxHeat) * 100 + '%' }" /></div>
          <span class="hr-val mono">{{ h.questions }}</span>
          <span class="hr-pct mono">{{ h.percent }}%</span>
        </div>

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
            <span class="mono">{{ Math.round((row.activeUsers / row.totalUsers) * 100) }}%</span>
          </template>
        </el-table-column>
        <el-table-column prop="questions" label="提问数" width="100" sortable />
        <el-table-column label="人均" width="90" sortable :sort-method="(a: DeptUsage, b: DeptUsage) => b.perUser - a.perUser">
          <template #default="{ row }">
            <b class="mono">{{ row.perUser }}</b>
          </template>
        </el-table-column>
        <el-table-column label="占比" width="100">
          <template #default="{ row }"><span class="mono">{{ row.percent }}%</span></template>
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
        <b class="mono">{{ overview?.avgPerUser }} 人均</b>
        <span>/</span>
        <b class="mono">100.0%</b>
      </div>

      <div class="guide-note">
        <el-icon><InfoFilled /></el-icon>
        <span>
          <b>提问数高 + 人均低</b> → 人多但用得浅（推广到了但没形成习惯），对策是提升使用频率；
          <b>提问数低 + 人均高</b> → 用得深但没推开（少数人的工具），对策是扩大覆盖面。
          示例中研发中心（45.6% 但人均 17.2）与销售中心（人均 20.7 但活跃率仅 35%）正是这两种典型。
        </span>
      </div>
    </el-card>

    <!-- 活跃用户 TOP -->
    <el-card shadow="never" class="mt-12">
      <template #header>
        <div class="card-head-row">
          <h3><el-icon><UserFilled /></el-icon> 活跃用户 TOP{{ topUsers.length }}</h3>
          <span class="hint">提问多 ≠ 用得好：提问多 + 分低说明他总在问知识库答不上来的问题</span>
        </div>
      </template>

      <div class="user-grid">
        <div v-for="(u, i) in topUsers" :key="u.name" class="user-card" :class="{ warn: u.avgTopScore < 0.7 }">
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
            <b class="mono" :class="scoreClass(u.avgTopScore)">{{ u.avgTopScore }}</b>
          </div>
        </div>
      </div>

      <div class="guide-note">
        <el-icon><InfoFilled /></el-icon>
        <span>
          <b>「提问多 + 相似度低」</b>（如刘强 43 次 / 0.68）说明他总在问知识库答不上来的问题——可能不是内容缺失，而是<b>他不知道该去哪查</b>，可单独观察甚至给个使用引导。
          反之「提问多 + 分高」是典型的重度满意用户，<b>适合做推广案例</b>。用户维度统计仅对管理员开放，且不展示具体提问内容。
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
</style>
