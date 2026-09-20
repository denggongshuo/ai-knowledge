<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { Refresh, InfoFilled, WarningFilled, TrendCharts, Timer } from '@element-plus/icons-vue'
import { opsApi } from '@/api'
import type { RetrievalOverview, PipelineStage, ScoreBucket, RerankEffect, KbQuality } from '@/types'

const loading = ref(false)
const range = ref('7d')

const overview = ref<RetrievalOverview | null>(null)
const pipeline = ref<PipelineStage[]>([])
const sideMs = ref(0)
const generationMs = ref(0)
const otherMs = ref(0)
const pipelineRows = ref(0)
const skippedNoGeneration = ref(0)
const totalMs = ref(0)
const distribution = ref<ScoreBucket[]>([])
const distTotal = ref(0)
const unscored = ref(0)
const noRerank = ref(0)
const emptyRecall = ref(0)
const rerank = ref<RerankEffect | null>(null)
const kbQuality = ref<KbQuality[]>([])
const othersQuestions = ref(0)

/**
 * 🔴 空窗 ≠ 全是 0。
 *
 * `sys_retrieval_trace` 在真实环境里可能**一行都没有**（本批开工实测就是 0），
 * 而需求 §2.8 最后一条点名要「显示**数据不足**而不是空白或假数据」——
 * 把四个指标渲染成 `0` 正好就是那句话要防的：0% 命中率会被读成"一条都没答好"。
 */
const noData = computed(() => overview.value !== null && overview.value.totalQuestions === 0)

async function load() {
  loading.value = true
  try {
    // 五个端点**同一个窗口**（页 11 那批的教训：一个不传 range 就会让两张卡说的不是同一段时间）
    const [o, p, d, r, k] = await Promise.all([
      opsApi.retrievalOverview({ range: range.value }),
      opsApi.pipeline({ range: range.value }),
      opsApi.scoreDistribution({ range: range.value }),
      opsApi.rerankEffect({ range: range.value }),
      opsApi.kbQuality({ range: range.value }),
    ])
    overview.value = o
    pipeline.value = p.list
    sideMs.value = p.retrievalSideMs
    generationMs.value = p.generationMs
    otherMs.value = p.otherMs
    pipelineRows.value = p.pipelineRows
    skippedNoGeneration.value = p.skippedNoGeneration
    totalMs.value = p.totalMs
    distribution.value = d.list
    distTotal.value = d.total
    unscored.value = d.unscored
    noRerank.value = d.noRerank
    emptyRecall.value = d.emptyRecall
    rerank.value = r
    kbQuality.value = k.list
    othersQuestions.value = k.othersQuestions
  } finally {
    loading.value = false
  }
}

onMounted(load)
// 下拉一改，**五块一起重拉** —— 只重拉一部分会让页面上的数来自两个窗口
watch(range, load)

const maxCost = computed(() => Math.max(...pipeline.value.map((p) => p.costMs), 1))
const maxBucket = computed(() => Math.max(...distribution.value.map((b) => b.count), 1))
/** Rerank 在检索侧的占比 */
const rerankShare = computed(() => {
  const r = pipeline.value.find((p) => p.name.includes('Rerank'))
  return r && sideMs.value ? Math.round((r.costMs / sideMs.value) * 100) : 0
})

/** `null` = **数据不足**（不是 0） */
function fmt(v: number | null | undefined, digits = 2) {
  return v === null || v === undefined ? '—' : Number(v).toFixed(digits)
}

function fmtMs(v: number | null | undefined) {
  return v === null || v === undefined ? '—' : Math.round(v)
}

function pct(v: number | null | undefined) {
  return v === null || v === undefined ? '—' : `${v}%`
}

/** 走势：`UNKNOWN` 是**样本不足**，不能显示成"稳定" */
const TREND_LABEL: Record<string, string> = {
  DOWN: '持续下降',
  UP: '回升',
  STABLE: '稳定',
  UNKNOWN: '样本不足',
}
const TREND_TYPE: Record<string, 'danger' | 'success' | 'info'> = {
  DOWN: 'danger',
  UP: 'success',
  STABLE: 'success',
  UNKNOWN: 'info',
}

function costClass(ms: number) {
  if (ms >= 1000) return 'cost-high'
  if (ms >= 200) return 'cost-mid'
  return ''
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
        <!--
          ⚠️ 原型上这里有个「导出报表」按钮，**已摘掉**：需求 `08` §2.6 给这一页列的是
          五条 GET，**没有导出端点**（导出是页 11 §1.7 的）。
          留一个点了只弹 toast 的按钮比没有入口更糟 —— 它让人以为已经导过了（判例 `T-908`）。
          真要做导出，接页 11 那条 `POST /ops/qa/export` 的同一套异步链路，属新的一批。
        -->
      </div>
    </div>

    <!-- 四指标 -->
    <el-alert v-if="noData && !loading" type="info" :closable="false" show-icon class="mb-12">
      <template #title>
        <span class="alert-text">
          这段时间<b>没有检索打点</b>，四个指标都显示「—」而不是 0。
          「0% 命中率」会被读成"一条都没答好"，而真相是"没有数据可算"。
          <br />要出数得有真实问答产生打点（`sys_retrieval_trace`）。
        </span>
      </template>
    </el-alert>

    <div v-if="overview" class="stat-row">
      <div class="stat-card">
        <div class="sc-val">{{ fmt(overview.avgTopScore) }}</div>
        <div class="sc-label">平均 Top1 相似度</div>
      </div>
      <div class="stat-card">
        <div class="sc-val">{{ pct(overview.afterTop1HitRate) }}</div>
        <div class="sc-label">Rerank 后 Top1 命中率</div>
      </div>
      <div class="stat-card good">
        <div class="sc-val">
          {{ overview.rerankGain === null ? '—' : `+${overview.rerankGain}%` }}
        </div>
        <div class="sc-label">Rerank 净提升</div>
      </div>
      <div class="stat-card">
        <div class="sc-val">{{ fmt(overview.avgCitedChunks, 1) }}</div>
        <div class="sc-label">平均引用分片数</div>
      </div>
    </div>

    <!-- 🔴 四个数都只在「可比集」上算 —— 不说这一句，读者会把它们当成全部提问的数 -->
    <div v-if="overview" class="guide-note mb-12">
      <el-icon><InfoFilled /></el-icon>
      <span>
        以上四指标算在<b>可比集</b>上：重排真的跑过、且有 Top1 的
        <b>{{ overview.comparableQuestions }}</b> / {{ overview.totalQuestions }} 次提问。
        另有 <b>{{ overview.noRerankQuestions }}</b> 次<b>没重排</b>（分数是 RRF 融合分，与
        {{ overview.missThreshold }} 这条判定线<b>不同量纲</b>，混进去只会让均值失真）、
        <b>{{ overview.emptyRecallQuestions }}</b> 次<b>空召回</b>（没有 Top1，不是"Top1 是 0 分"）。
        <br />
        「命中」的口径是<b>Top1 ≥ 未命中判定线 {{ overview.missThreshold }}</b> ——
        它说的是「**库里有了相关内容**」，不是「问题被回答了」。
      </span>
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
            <!-- 需求 §2.2 的「关注指标」列里**只有两项有采集点**，其余如实显示 — -->
            <template v-if="p.metric">
              <span class="tiny muted">{{ p.metric }}</span>
              <b class="tiny">{{ p.metricValue ?? '—' }}</b>
            </template>
          </div>
        </div>

        <div class="sum-line">
          <span>
            检索侧 <b class="mono">{{ sideMs }}</b> + 生成 <b class="mono">{{ generationMs }}</b>
            + 其他 <b class="mono">{{ otherMs }}</b> = 全链路
            <b class="mono">{{ totalMs }} ms</b> ≈ {{ (totalMs / 1000).toFixed(2) }} s
          </span>
        </div>

        <div class="hint mt-8">
          「其他」= <b>可见范围 + 表格回表 + 未归段</b> —— 需求 §2.2 列的六段里没有前两段，
          可它们是真实耗时；不单列这一栏的话「检索侧 + 生成」对不上全链路，而差额没有任何一栏显示它。
          <br />
          本表算在 <b>{{ pipelineRows }}</b> 次跑完全链路的检索上；另有
          <b>{{ skippedNoGeneration }}</b> 次空召回没调模型 —— 对它们「生成耗时」<b>不存在</b>
          （填 0 就是把"没生成"记成"耗时 0ms"），所以整行排除而不是补 0。
        </div>

        <el-alert type="warning" :closable="false" show-icon class="mt-12">
          <template #title>
            <span class="alert-text">
              Rerank 是检索侧最大耗时项（{{ pipeline.find((p) => p.name.includes('Rerank'))?.costMs }} ms，占检索侧 <b>{{ rerankShare }}%</b>）。
              若要压延迟，<b>优先减少重排候选数，而不是砍掉这一环</b>——
              <template v-if="overview?.rerankGain != null">它贡献了 <b>{{ overview.rerankGain }}</b> 个百分点的命中提升。</template>
              <template v-else>它贡献了多少命中提升<b>现在还算不出来</b>（要看右边的「重排前 / 重排后」对比）。</template>
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

        <div v-if="rerank && rerank.covered > 0" class="rerank-box">
          <div class="rr-compare">
            <div class="rr-item">
              <div class="rr-label">重排前 Top1 命中</div>
              <div class="rr-val mono">{{ pct(rerank.beforeTop1HitRate) }}</div>
              <div class="rr-bar"><i :style="{ width: (rerank.beforeTop1HitRate ?? 0) + '%' }" /></div>
            </div>
            <div class="rr-arrow">→</div>
            <div class="rr-item">
              <div class="rr-label">重排后 Top1 命中</div>
              <div class="rr-val mono good">{{ pct(rerank.afterTop1HitRate) }}</div>
              <div class="rr-bar good"><i :style="{ width: (rerank.afterTop1HitRate ?? 0) + '%' }" /></div>
            </div>
          </div>

          <div class="rr-summary">
            <div class="rs-item">
              <span>净提升</span>
              <b class="good mono">
                {{ rerank.gainPercent === null ? '—' : `+${rerank.gainPercent}%` }}
              </b>
            </div>
            <div class="rs-item">
              <span>代价</span>
              <b class="mono">{{ fmtMs(rerank.avgLatencyMs) }} ms</b>
            </div>
          </div>

          <div class="hint mt-8">
            🔴 前后两个比率算在<b>同一批</b>检索上（{{ rerank.covered }} / {{ rerank.comparable }} 次）——
            只有分母相同，差值才是「重排带来的提升」；分母不同的话，差值里会混进
            「有多少次检索压根没有前值」这个成分，而页面上看不出来。
            <br />
            「重排前 Top1」的分数<b>只在它进了重排结果 top_n 时才拿得到</b>（重排服务只回前 N 条），
            所以这两个数覆盖了多少次要一起看。
          </div>
        </div>

        <div v-else class="rr-empty">
          重排前后<b>没有可比的数据</b> —— 「净提升」算不出来（<b>不是</b> 0 个百分点）。
          可能是这段时间没有重排过的检索，也可能重排前的 Top1 都没进重排结果。
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

      <div class="hint mt-8">
        🔴 需求里只写了五档，这里多一档「<b>无法定档</b>」：<b>空召回</b>的提问
        <code>top1_score</code> 是 NULL（<b>不是 0</b>，见 <code>G5-13</code>），<b>没重排</b>的提问分数是
        RRF 融合分、与档位边界不同量纲 —— 两类都<b>装不进那五档</b>。
        漏掉它们的话「五档合计 = 提问总数」看着成立、实际少
        {{ unscored }} 次（未重排 {{ noRerank }} + 空召回 {{ emptyRecall }}）。
        <br />
        另：「未命中」这一档数的是<b>提问次数</b>；页 11 那份清单数的是<b>归一化聚合后的行数</b>
        （同一问法的不同写法合成一行）—— 两个数<b>本来就不该相等</b>，
        需求 §2.8 把它们写成了一个等式。
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
          <template #default="{ row }"><span class="mono">{{ fmt(row.avgTopScore) }}</span></template>
        </el-table-column>
        <el-table-column
          label="未命中率"
          width="110"
          sortable
          :sort-method="(a: KbQuality, b: KbQuality) => (a.missRate ?? 999) - (b.missRate ?? 999)"
        >
          <template #default="{ row }">
            <span class="mono" :class="{ low: (row.missRate ?? 0) >= 10 }">
              {{ row.missRate === null ? '—' : `${row.missRate}%` }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="质量走势" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="TREND_TYPE[row.trend]">
              {{ TREND_LABEL[row.trend] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="主要问题" min-width="220">
          <template #default="{ row }">
            <span class="muted">{{ row.issue ?? '—' }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="guide-note">
        <el-icon><InfoFilled /></el-icon>
        <span>
          「走势」列区分<b>稳定偏低</b>与<b>持续下降</b>——后者更紧急，通常是最近有变更导致。
          判定方式是把窗口对半切、比两个半区的未命中率；<b>任一半区样本不足 5 次就显示「样本不足」</b>，
          不写成「稳定」——把"没数据"说成"稳定"正好会废掉这一列。
          <br />
          归属口径：<b>只把单库提问归给库</b>，跨库提问整体计入「其他」
          （{{ othersQuestions }} 次）—— 一次提问命中多个库时按库拆分会把同一句话算多次，
          各库合计就会超过提问总数。
          <br />
          「主要问题」列需要<b>文档质量列 + 解析问题表</b>（如"表格未识别""扫描件 OCR 质量差"），
          与检索打点是两张表的事 ⇒ 本版本<b>不猜</b>，一律显示「—」。
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
.mb-12 {
  margin-bottom: 12px;
}
.mt-8 {
  margin-top: 8px;
}
/* 没有可比数据时的诚实空态（不是 0，也不是空白） */
.rr-empty {
  padding: 28px 12px;
  text-align: center;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
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
