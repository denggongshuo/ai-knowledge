<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh, InfoFilled, WarningFilled, TrendCharts, Timer } from '@element-plus/icons-vue'
import { opsApi } from '@/api'
import type { RetrievalOverview, PipelineStage, ScoreBucket, RerankEffect, KbQuality } from '@/types'

const loading = ref(false)
const range = ref('7d')

const overview = ref<RetrievalOverview | null>(null)
const pipeline = ref<PipelineStage[]>([])
const sideMs = ref(0)
const totalMs = ref(0)
const distribution = ref<ScoreBucket[]>([])
const distTotal = ref(0)
const rerank = ref<RerankEffect | null>(null)
const kbQuality = ref<KbQuality[]>([])

onMounted(async () => {
  loading.value = true
  try {
    const [o, p, d, r, k] = await Promise.all([
      opsApi.retrievalOverview(),
      opsApi.pipeline(),
      opsApi.scoreDistribution(),
      opsApi.rerankEffect(),
      opsApi.kbQuality(),
    ])
    overview.value = o
    pipeline.value = p.list
    sideMs.value = p.retrievalSideMs
    totalMs.value = p.totalMs
    distribution.value = d.list
    distTotal.value = d.total
    rerank.value = r
    kbQuality.value = k.list
  } finally {
    loading.value = false
  }
})

const maxCost = computed(() => Math.max(...pipeline.value.map((p) => p.costMs), 1))
const maxBucket = computed(() => Math.max(...distribution.value.map((b) => b.count), 1))
/** Rerank 在检索侧的占比（288 / 428 ≈ 67%） */
const rerankShare = computed(() => {
  const r = pipeline.value.find((p) => p.name.includes('Rerank'))
  return r && sideMs.value ? Math.round((r.costMs / sideMs.value) * 100) : 0
})

function costClass(ms: number) {
  if (ms >= 1000) return 'cost-high'
  if (ms >= 200) return 'cost-mid'
  return ''
}

function exportReport() {
  ElMessage.success('导出任务已创建，完成后通过通知中心提醒')
}
</script>

<template>
  <div v-loading="loading" class="page ops-retrieval">
    <div class="page-head">
      <div>
        <h2 class="page-title">检索质量分析</h2>
        <p class="page-desc">为什么没答好 → 用数据定位是补内容还是调参数（链路每一段都有打点）</p>
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
        <div class="sc-val">{{ overview.avgTopScore }}</div>
        <div class="sc-label">平均 Top1 相似度</div>
      </div>
      <div class="stat-card">
        <div class="sc-val">{{ overview.afterTop1HitRate }}<span class="unit">%</span></div>
        <div class="sc-label">Rerank 后 Top1 命中率</div>
      </div>
      <div class="stat-card good">
        <div class="sc-val">+{{ overview.rerankGain }}<span class="unit">%</span></div>
        <div class="sc-label">Rerank 净提升</div>
      </div>
      <div class="stat-card">
        <div class="sc-val">{{ overview.avgCitedChunks }}</div>
        <div class="sc-label">平均引用分片数</div>
      </div>
    </div>

    <div class="grid-2 mt-12">
      <!-- 检索链路耗时分解 -->
      <el-card shadow="never">
        <template #header>
          <div class="card-head-row">
            <h3><el-icon><Timer /></el-icon> 检索链路耗时分解</h3>
            <span class="hint">检索侧 {{ sideMs }} ms · 全链路 {{ totalMs }} ms</span>
          </div>
        </template>

        <div v-for="p in pipeline" :key="p.name" class="stage-row" :class="{ highlight: p.name.includes('Rerank') }">
          <div class="sg-name">{{ p.name }}</div>
          <div class="sg-bar">
            <i :style="{ width: (p.costMs / maxCost) * 100 + '%' }" :class="costClass(p.costMs)" />
          </div>
          <span class="sg-cost mono" :class="costClass(p.costMs)">{{ p.costMs }} ms</span>
          <div class="sg-metric">
            <span class="tiny muted">{{ p.metric }}</span>
            <b class="tiny">{{ p.metricValue }}</b>
          </div>
        </div>

        <div class="sum-line">
          <span>检索侧合计 <b class="mono">{{ pipeline.slice(0, 5).map((p) => p.costMs).join(' + ') }} = {{ sideMs }} ms</b></span>
          <span>全链路 <b class="mono">{{ sideMs }} + {{ pipeline[5]?.costMs }} = {{ totalMs }} ms ≈ {{ (totalMs / 1000).toFixed(2) }} s</b></span>
        </div>

        <el-alert type="warning" :closable="false" show-icon class="mt-12">
          <template #title>
            <span class="alert-text">
              Rerank 是检索侧最大耗时项（{{ pipeline.find((p) => p.name.includes('Rerank'))?.costMs }} ms，占检索侧 <b>{{ rerankShare }}%</b>）。
              若要压延迟，<b>优先减少重排候选数，而不是砍掉这一环</b>——它贡献了 {{ overview?.rerankGain }} 个百分点的命中提升。
            </span>
          </template>
        </el-alert>
      </el-card>

      <!-- Rerank 效果验证 -->
      <el-card shadow="never">
        <template #header>
          <div class="card-head-row">
            <h3><el-icon><TrendCharts /></el-icon> Rerank 效果验证</h3>
            <span class="hint">用数据回答「值不值得留」</span>
          </div>
        </template>

        <div v-if="rerank" class="rerank-box">
          <div class="rr-compare">
            <div class="rr-item">
              <div class="rr-label">重排前 Top1 命中</div>
              <div class="rr-val mono">{{ rerank.beforeTop1HitRate }}%</div>
              <div class="rr-bar"><i :style="{ width: rerank.beforeTop1HitRate + '%' }" /></div>
            </div>
            <div class="rr-arrow">→</div>
            <div class="rr-item">
              <div class="rr-label">重排后 Top1 命中</div>
              <div class="rr-val mono good">{{ rerank.afterTop1HitRate }}%</div>
              <div class="rr-bar good"><i :style="{ width: rerank.afterTop1HitRate + '%' }" /></div>
            </div>
          </div>

          <div class="rr-summary">
            <div class="rs-item">
              <span>净提升</span>
              <b class="good mono">+{{ rerank.gainPercent }}%</b>
            </div>
            <div class="rs-item">
              <span>代价</span>
              <b class="mono">{{ rerank.avgLatencyMs }} ms</b>
            </div>
          </div>

          <div class="hint mt-8">
            同时量化了代价与收益。建议把每个检索增强项（HyQ、标题路径注入、文档级摘要）都做类似的 A/B 验证，<b>用数据决定去留</b>，而不是凭直觉。
          </div>
        </div>
      </el-card>
    </div>

    <!-- Top1 相似度分布 -->
    <el-card shadow="never" class="mt-12">
      <template #header>
        <div class="card-head-row">
          <h3>Top1 相似度分布（五档色阶）</h3>
          <span class="hint">合计 {{ distTotal.toLocaleString() }} = 提问总数</span>
        </div>
      </template>

      <div v-for="b in distribution" :key="b.label" class="bucket-row" :class="{ danger: b.danger }">
        <div class="bk-label">
          <el-tag size="small" :type="b.danger ? 'danger' : b.label === '优秀' ? 'success' : 'info'">{{ b.label }}</el-tag>
        </div>
        <span class="bk-range mono">{{ b.range }}</span>
        <div class="bk-bar"><i :style="{ width: (b.count / maxBucket) * 100 + '%' }" /></div>
        <span class="bk-count mono">{{ b.count }}</span>
        <span class="bk-pct mono">{{ b.percent }}%</span>
      </div>

      <div class="bucket-sum">
        <span>合计</span>
        <b class="mono">{{ distTotal.toLocaleString() }}</b>
        <b class="mono">100.0%</b>
      </div>

      <div class="read-guide">
        <div class="rg-title"><el-icon><WarningFilled /></el-icon> 怎么读这张图</div>
        <div class="rg-row">
          <b>关注「边缘」档（0.35~0.40）</b>：这些是勉强过线的召回，最容易产出「看似合理实则无关」的答案，<b>比明确的未命中更危险</b>。
        </div>
        <div class="rg-row">
          <b>分布整体左移</b> → 通常意味着<b>文档覆盖不足</b>（该补内容）；<b>整体右移但未命中率不降</b> → 是<b>阈值设置问题</b>（该调参数）。
        </div>
      </div>
    </el-card>

    <!-- 各知识库质量排行 -->
    <el-card shadow="never" class="mt-12">
      <template #header>
        <div class="card-head-row">
          <h3>各知识库检索质量排行</h3>
          <span class="hint">把问题落到具体知识库，才知道该找谁处理</span>
        </div>
      </template>

      <el-table :data="kbQuality" border>
        <el-table-column prop="kbName" label="知识库" min-width="160" />
        <el-table-column prop="questions" label="提问数" width="100" sortable />
        <el-table-column label="平均 Top1" width="110">
          <template #default="{ row }"><span class="mono">{{ row.avgTopScore }}</span></template>
        </el-table-column>
        <el-table-column label="未命中率" width="110" sortable :sort-method="(a: KbQuality, b: KbQuality) => b.missRate - a.missRate">
          <template #default="{ row }">
            <span class="mono" :class="{ low: row.missRate >= 10 }">{{ row.missRate }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="质量走势" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.trend === 'DOWN' ? 'danger' : 'success'">
              {{ row.trend === 'DOWN' ? '持续下降' : '稳定' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="issue" label="主要问题" min-width="220">
          <template #default="{ row }">
            <span :class="{ 'issue-text': row.issue !== '—' }">{{ row.issue }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="guide-note">
        <el-icon><InfoFilled /></el-icon>
        <span>
          「走势」列区分<b>稳定偏低</b>与<b>持续下降</b>——后者更紧急，通常是最近有变更导致。示例中「客户合同库」对应索引未完成（274/316），
          「外部培训资料库」对应扫描件 OCR 质量差，两条线索都直指可执行动作。
        </span>
      </div>
    </el-card>

    <div class="foot-note">
      <el-icon><Refresh /></el-icon>
      数据前置依赖：检索链路的每一段都要打点（traceId / query / 各阶段耗时 / 召回 chunkId 与分数 / 最终引用）。明细保留 30 天按天分区，聚合指标长期保留；未命中与点踩的记录必记。
    </div>
  </div>
</template>

<style scoped>
.ops-retrieval {
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
.alert-text {
  font-size: 12.5px;
  line-height: 1.6;
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
.stat-card.good {
  border-color: var(--el-color-success-light-9);
  background: #f7fdf5;
}
.sc-val {
  font-size: 26px;
  font-weight: 600;
  font-family: var(--font-mono);
}
.sc-val .unit {
  font-size: 15px;
  margin-left: 2px;
}
.sc-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
}

/* 阶段 */
.stage-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.stage-row.highlight {
  background: #fffbf5;
  margin: 0 -8px;
  padding: 8px;
  border-radius: 6px;
}
.sg-name {
  width: 90px;
  font-size: 12.5px;
  flex: none;
}
.sg-bar {
  flex: 1;
  height: 8px;
  background: var(--el-border-color-extra-light);
  border-radius: 4px;
  overflow: hidden;
}
.sg-bar i {
  display: block;
  height: 100%;
  background: var(--el-color-primary);
  border-radius: 4px;
}
.sg-bar i.cost-mid {
  background: var(--el-color-warning);
}
.sg-bar i.cost-high {
  background: var(--el-color-danger);
}
.sg-cost {
  width: 70px;
  text-align: right;
  font-size: 12px;
  flex: none;
}
.sg-cost.cost-mid {
  color: var(--el-color-warning);
  font-weight: 600;
}
.sg-cost.cost-high {
  color: var(--el-color-danger);
  font-weight: 600;
}
.sg-metric {
  width: 150px;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  flex: none;
}
.sum-line {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  flex-wrap: wrap;
}
.sum-line b {
  color: var(--el-text-color-primary);
}

/* Rerank */
.rerank-box {
  padding: 4px 0;
}
.rr-compare {
  display: flex;
  align-items: center;
  gap: 16px;
}
.rr-item {
  flex: 1;
}
.rr-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.rr-val {
  font-size: 24px;
  font-weight: 600;
}
.rr-val.good {
  color: var(--el-color-success);
}
.rr-bar {
  height: 8px;
  background: var(--el-border-color-extra-light);
  border-radius: 4px;
  overflow: hidden;
  margin-top: 8px;
}
.rr-bar i {
  display: block;
  height: 100%;
  background: var(--el-text-color-placeholder);
  border-radius: 4px;
}
.rr-bar.good i {
  background: var(--el-color-success);
}
.rr-arrow {
  font-size: 20px;
  color: var(--el-text-color-placeholder);
  flex: none;
}
.rr-summary {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}
.rs-item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: #f7f9fc;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.rs-item b {
  font-size: 15px;
  color: var(--el-text-color-primary);
}
.rs-item b.good {
  color: var(--el-color-success);
}

/* 分布 */
.bucket-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
}
.bucket-row.danger {
  background: #fffafa;
  margin: 0 -10px;
  padding: 8px 10px;
  border-radius: 6px;
}
.bk-label {
  width: 64px;
  flex: none;
}
.bk-range {
  width: 100px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.bk-bar {
  flex: 1;
  height: 12px;
  background: var(--el-border-color-extra-light);
  border-radius: 6px;
  overflow: hidden;
}
.bk-bar i {
  display: block;
  height: 100%;
  background: var(--el-color-primary);
  border-radius: 6px;
}
.bucket-row.danger .bk-bar i {
  background: var(--el-color-danger);
}
.bk-count {
  width: 60px;
  text-align: right;
  font-size: 13px;
  flex: none;
}
.bk-pct {
  width: 60px;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.bucket-sum {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: flex-end;
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
  margin-top: 6px;
  font-size: 12.5px;
}
.bucket-sum span {
  color: var(--el-text-color-secondary);
}
.bucket-sum b {
  min-width: 60px;
  text-align: right;
}
.read-guide {
  margin-top: 16px;
  padding: 12px 14px;
  background: var(--el-color-warning-light-9);
  border-radius: 8px;
}
.rg-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #8a6d3b;
  margin-bottom: 8px;
}
.rg-row {
  font-size: 12.5px;
  color: #8a6d3b;
  line-height: 1.75;
}
.rg-row b {
  color: #6b4c1f;
}

/* 知识库排行 */
.low {
  color: var(--el-color-danger);
  font-weight: 600;
}
.issue-text {
  color: var(--el-color-warning);
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
.foot-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}
</style>
