<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Lightning,
  Lock,
  Refresh,
  Warning,
  InfoFilled,
  Collection,
  TrendCharts,
  Right,
  Plus,
  Connection,
  Delete,
  Edit,
} from '@element-plus/icons-vue'
import { sysApi } from '@/api'
import type { ModelConfigItem, ModelConfigResponse, ModelType, ModelUsage, VlImpact } from '@/types'

// ---------------- 四类模型数据 ----------------
const loading = ref(false)
const config = reactive<ModelConfigResponse>({
  stats: { chatCount: 0, rerankCount: 0, vlCount: 0, defaultChatName: '—', defaultChatOk: false, monthTokensWan: 0 },
  chat: [],
  embedding: {
    id: 0,
    modelType: 'EMBEDDING',
    displayName: '—',
    protocol: 'OpenAI 兼容',
    baseUrl: '',
    apiKeyMasked: '',
    modelName: '',
    params: { temperature: 0, maxTokens: 0, timeoutSec: 60, retries: 2 },
    capabilities: { stream: false, vision: false, contextWindow: 8192, supportSystemRole: false },
    billingUnit: 'PER_TOKEN',
    inputPrice: null,
    outputPrice: null,
    perCallPrice: null,
    currency: 'CNY',
    isActive: true,
    sortOrder: 1,
    lastTestOk: true,
  },
  rerank: [],
  vl: [],
})

const TYPE_LABEL: Record<ModelType, string> = {
  CHAT: '对话模型',
  EMBEDDING: '嵌入模型',
  RERANK: '重排模型',
  VL: 'VL 视觉模型',
}

/** 计量方式文案（费用三态：有单价 / NONE 不计费 / 未配置） */
function billingText(m: ModelConfigItem) {
  if (m.billingUnit === 'NONE') return '本地部署 · 不计 API 费用'
  if (m.billingUnit === 'PER_CALL') {
    return m.perCallPrice == null ? '未配置单价（费用未知）' : `¥ ${m.perCallPrice} / 次`
  }
  if (m.inputPrice == null && m.outputPrice == null) return '未配置单价（费用未知）'
  const i = m.inputPrice == null ? '—' : `¥${m.inputPrice}`
  const o = m.outputPrice == null ? '—' : `¥${m.outputPrice}`
  return `输入 ${i} / 输出 ${o} · 每百万 tokens`
}

/** 能力标记 */
function capsOf(m: ModelConfigItem) {
  const c = m.capabilities
  const list: string[] = []
  if (c.stream) list.push('流式')
  if (c.vision) list.push('视觉')
  if (c.contextWindow) list.push(`${Math.round(c.contextWindow / 1000)}K 窗口`)
  if (c.supportSystemRole) list.push('system')
  return list
}

/** 单个模型行的测试中状态 */
const testingIds = ref<number[]>([])
function isTesting(id: number) {
  return testingIds.value.includes(id)
}

async function refresh() {
  loading.value = true
  try {
    const res = await sysApi.modelConfig()
    Object.assign(config, res)
  } finally {
    loading.value = false
  }
}

/** 连接测试（启用前必须通过） */
async function testModel(m: ModelConfigItem) {
  testingIds.value.push(m.id)
  try {
    const res = await sysApi.testModel(m.id)
    m.lastTestOk = res.ok
    m.lastTestAt = new Date().toLocaleString('zh-CN', { hour12: false })
    m.lastLatencyMs = res.latencyMs
    ElMessage.success(`${m.displayName}：${res.message} · ${res.latencyMs}ms`)
  } finally {
    testingIds.value = testingIds.value.filter((i) => i !== m.id)
  }
}

// ---------------- 启用 / 切换 ----------------
const switching = ref(false)

async function activate(m: ModelConfigItem) {
  if (!m.lastTestOk) {
    ElMessage.warning('请先通过连接测试再启用')
    return
  }
  // 重排：切换前建议先跑评测集对比 Recall@5
  if (m.modelType === 'RERANK') {
    try {
      await ElMessageBox.confirm(
        '重排模型直接改变召回结果，切换后同一问题在不同时间可能召回不同内容。建议先用评测集对比 Recall@5 再决定。已发生的检索结果不变，下一次检索即生效（不需要重建索引）。',
        '切换重排模型',
        { type: 'warning', confirmButtonText: '确认切换', cancelButtonText: '再想想' },
      )
    } catch {
      return
    }
  }
  // VL：仅新解析的文档生效
  if (m.modelType === 'VL') {
    vlPending.value = m
    await loadVlImpact()
    vlDialog.value = true
    return
  }
  await doActivate(m)
}

async function doActivate(m: ModelConfigItem) {
  switching.value = true
  try {
    await sysApi.activateModel(m.id)
    ElMessage.success(`已启用「${m.displayName}」`)
    await refresh()
  } finally {
    switching.value = false
  }
}

async function removeModel(m: ModelConfigItem) {
  try {
    await ElMessageBox.confirm(`确认删除模型「${m.displayName}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await sysApi.deleteModel(m.id)
    ElMessage.success('已删除')
    await refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---------------- 新增 / 编辑弹窗 ----------------
const editDialog = ref(false)
const editMode = ref<'create' | 'edit'>('create')
const saving = ref(false)
const editForm = reactive({
  id: 0,
  modelType: 'CHAT' as ModelType,
  displayName: '',
  protocol: 'OpenAI 兼容',
  baseUrl: '',
  apiKey: '',
  apiKeyMasked: '',
  keyChanged: false,
  modelName: '',
  stream: true,
  vision: false,
  contextWindow: 128000,
  supportSystemRole: true,
  temperature: 0.3,
  maxTokens: 2048,
  timeoutSec: 60,
  retries: 2,
  billingUnit: 'PER_TOKEN' as ModelConfigItem['billingUnit'],
  inputPrice: null as number | null,
  outputPrice: null as number | null,
  perCallPrice: null as number | null,
  note: '',
})

function openCreate(type: ModelType) {
  editMode.value = 'create'
  Object.assign(editForm, {
    id: 0,
    modelType: type,
    displayName: '',
    protocol: 'OpenAI 兼容',
    baseUrl: '',
    apiKey: '',
    apiKeyMasked: '',
    keyChanged: true,
    modelName: '',
    stream: type !== 'EMBEDDING' && type !== 'RERANK',
    vision: type === 'VL',
    contextWindow: type === 'CHAT' ? 128000 : 8192,
    supportSystemRole: type === 'CHAT',
    temperature: type === 'VL' ? 0.1 : 0.3,
    maxTokens: type === 'VL' ? 1500 : 2048,
    timeoutSec: type === 'VL' ? 120 : 60,
    retries: 2,
    billingUnit: 'PER_TOKEN',
    inputPrice: null,
    outputPrice: null,
    perCallPrice: null,
    note: '',
  })
  editDialog.value = true
}

function openEdit(m: ModelConfigItem) {
  editMode.value = 'edit'
  Object.assign(editForm, {
    id: m.id,
    modelType: m.modelType,
    displayName: m.displayName,
    protocol: m.protocol,
    baseUrl: m.baseUrl,
    apiKey: '',
    apiKeyMasked: m.apiKeyMasked,
    keyChanged: false,
    modelName: m.modelName,
    stream: m.capabilities.stream,
    vision: m.capabilities.vision,
    contextWindow: m.capabilities.contextWindow,
    supportSystemRole: m.capabilities.supportSystemRole,
    temperature: m.params.temperature,
    maxTokens: m.params.maxTokens,
    timeoutSec: m.params.timeoutSec,
    retries: m.params.retries,
    billingUnit: m.billingUnit,
    inputPrice: m.inputPrice,
    outputPrice: m.outputPrice,
    perCallPrice: m.perCallPrice,
    note: m.note ?? '',
  })
  editDialog.value = true
}

/** 编辑 Base URL / Key / 模型名 → 测试结论失效，需重测（弹窗内给出明确提示） */
const editNeedsRetest = computed(() => editMode.value === 'edit')

async function saveModel() {
  if (!editForm.displayName.trim() || !editForm.baseUrl.trim() || !editForm.modelName.trim()) {
    ElMessage.warning('显示名称、Base URL、模型名称为必填项')
    return
  }
  saving.value = true
  try {
    const payload: Partial<ModelConfigItem> & { modelType: ModelType } = {
      modelType: editForm.modelType,
      displayName: editForm.displayName,
      protocol: editForm.protocol,
      baseUrl: editForm.baseUrl,
      modelName: editForm.modelName,
      capabilities: {
        stream: editForm.stream,
        vision: editForm.vision,
        contextWindow: editForm.contextWindow,
        supportSystemRole: editForm.supportSystemRole,
      },
      params: {
        temperature: editForm.temperature,
        maxTokens: editForm.maxTokens,
        timeoutSec: editForm.timeoutSec,
        retries: editForm.retries,
      },
      billingUnit: editForm.billingUnit,
      inputPrice: editForm.billingUnit === 'PER_TOKEN' ? editForm.inputPrice : null,
      outputPrice: editForm.billingUnit === 'PER_TOKEN' ? editForm.outputPrice : null,
      perCallPrice: editForm.billingUnit === 'PER_CALL' ? editForm.perCallPrice : null,
      note: editForm.note,
    }
    if (editMode.value === 'create') {
      await sysApi.createModel(payload)
      ElMessage.success('已新增。请先在列表中通过「连接测试」，再启用')
    } else {
      await sysApi.updateModel(editForm.id, payload)
      ElMessage.success('已保存。参数变更后需重新测试才能启用')
    }
    editDialog.value = false
    await refresh()
  } finally {
    saving.value = false
  }
}

// ---------------- 图片理解：理解进度示例（与问答页同链路） ----------------
const vlImpact = reactive<VlImpact>({ kbCount: 0, imageDocCount: 0, imageCount: 0, estimateMinutes: 0, extraTokens: 0 })
const vlImpactLoading = ref(false)
async function loadVlImpact() {
  vlImpactLoading.value = true
  try {
    Object.assign(vlImpact, await sysApi.vlImpact())
  } finally {
    vlImpactLoading.value = false
  }
}

// ---------------- VL 切换确认 ----------------
const vlDialog = ref(false)
const vlPending = ref<ModelConfigItem | null>(null)

async function confirmVlSwitch(rebuild: boolean) {
  const m = vlPending.value
  if (!m) return
  await doActivate(m)
  if (rebuild) {
    const res = await sysApi.rebuildImageDocs()
    ElMessage.success(
      `已启动重建：${res.imageDocCount} 篇含图文档 / ${res.imageCount} 张图片。任务 ${res.taskId} 可到通知中心查看`,
    )
  }
  vlDialog.value = false
  vlPending.value = null
}

// ---------------- 嵌入模型（只读单例） ----------------
const embedImpact = reactive({ kbCount: 8, docCount: 1246, chunkCount: 38502, estimateMinutes: 42, extraTokens: 11800000 })
const pendingRebuild = ref(false)
const changeDialog = ref(false)
const rebuildForm = reactive({
  newModel: 'text-embedding-3-large',
  newDimension: 3072,
  rebuildMode: 'DEGRADED' as 'DEGRADED' | 'PAUSE',
  confirmed: false,
})

async function confirmChange() {
  if (!rebuildForm.confirmed) {
    ElMessage.warning('请先勾选「我已知晓影响范围」')
    return
  }
  await sysApi.changeEmbedding({
    model: rebuildForm.newModel,
    dimension: rebuildForm.newDimension,
    rebuildMode: rebuildForm.rebuildMode,
  })
  changeDialog.value = false
  rebuildForm.confirmed = false
  pendingRebuild.value = false
  ElMessage.success('已启动全库索引重建，任务编号可到通知中心查看，重建期间可继续使用关键词检索')
}

// ---------------- 用量统计 ----------------
const usage = reactive({
  range: 'month' as string,
  calls: 0,
  tokens: 0,
  cost: null as number | null,
  currency: 'CNY',
  unpricedCount: 0,
  avgLatencySec: 1.6,
  byPurpose: [] as { name: string; tokens: number; percent: number }[],
  byModel: [] as ModelUsage['byModel'],
})

/** 按模型拆分的费用三态文案 */
function rowCost(item: { cost: number | null; billingUnit: ModelConfigItem['billingUnit'] }) {
  if (item.billingUnit === 'NONE') return '本地部署 · 不计 API 费用'
  if (item.cost == null) return '—'
  return `¥ ${item.cost.toFixed(1)}`
}

/** 模型类型中文名（模板用，避免在模板里做类型断言） */
function typeLabel(t: ModelType) {
  return TYPE_LABEL[t]
}

onMounted(async () => {
  const [usageRes, impactRes] = await Promise.all([sysApi.modelUsage(), sysApi.embeddingImpact()])
  Object.assign(embedImpact, impactRes ?? {})
  usage.calls = usageRes.calls
  usage.tokens = usageRes.tokens
  usage.cost = usageRes.cost
  usage.currency = usageRes.currency
  usage.unpricedCount = usageRes.unpricedCount
  usage.avgLatencySec = usageRes.avgLatencySec
  usage.byPurpose = usageRes.byPurpose ?? []
  usage.byModel = usageRes.byModel ?? []
  await refresh()
})
</script>

<template>
  <div class="page sys-model-page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">模型配置</h2>
        <p class="page-desc">
          四类模型：对话 / 嵌入 / 重排 / VL。除嵌入外均可配置多条；同一类型同时只有一条启用。
        </p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="refresh">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate('CHAT')">新增对话模型</el-button>
      </div>
    </div>

    <!-- 待重建提示 -->
    <div v-if="pendingRebuild" class="rebuild-banner">
      <el-icon><Warning /></el-icon>
      <span>系统启动时检测到<b>服务端嵌入模型配置与库内索引不一致</b>，索引不重建将无法继续检索，请确认后执行全库重建。</span>
      <el-button size="small" type="warning" @click="changeDialog = true">立即处理</el-button>
    </div>

    <!-- 顶部统计 -->
    <div class="stat-grid">
      <div class="stat-card">
        <el-icon class="si"><Lightning /></el-icon>
        <div class="stat-mid">
          <div class="sv">对话 {{ config.stats.chatCount }} · 重排 {{ config.stats.rerankCount }} · VL {{ config.stats.vlCount }}</div>
          <div class="sl">模型数量（按类型）</div>
        </div>
      </div>
      <div class="stat-card">
        <el-icon class="si p"><Collection /></el-icon>
        <div class="stat-mid">
          <div class="sv ellipsis">{{ config.stats.defaultChatName }}</div>
          <div class="sl">
            <span v-if="config.stats.defaultChatOk" class="connect-ok"><i class="dot"></i>已连通</span>
            <span v-else class="connect-bad"><i class="dot"></i>未连接</span>
            当前默认对话模型
          </div>
        </div>
      </div>
      <div class="stat-card">
        <el-icon class="si o"><TrendCharts /></el-icon>
        <div class="stat-mid">
          <div class="sv">{{ config.stats.monthTokensWan }} 万</div>
          <div class="sl">本月消耗 tokens</div>
        </div>
      </div>
    </div>

    <!-- ① 对话模型 -->
    <div class="card">
      <div class="card-head">
        <span class="card-title">① 对话模型</span>
        <el-tag size="small" type="info" effect="plain">可配多条</el-tag>
        <span class="sub">生成期使用；<b>问答页可直接切换</b>（切换 = 开启新会话）</span>
        <div class="head-right">
          <el-button size="small" type="primary" plain :icon="Plus" @click="openCreate('CHAT')">新增</el-button>
        </div>
      </div>
      <div class="card-body">
        <el-table :data="config.chat" size="small" class="model-table">
          <el-table-column label="显示名称" min-width="180">
            <template #default="{ row }">
              <div class="name-cell">
                <span class="nm">{{ row.displayName }}</span>
                <span v-if="row.isActive" class="active-tag">当前默认</span>
              </div>
              <div v-if="row.note" class="tiny muted">{{ row.note }}</div>
            </template>
          </el-table-column>
          <el-table-column label="模型名称" min-width="180">
            <template #default="{ row }"><span class="mono tiny">{{ row.modelName }}</span></template>
          </el-table-column>
          <el-table-column label="Base URL" min-width="200">
            <template #default="{ row }"><span class="mono tiny muted">{{ row.baseUrl }}</span></template>
          </el-table-column>
          <el-table-column label="能力标记" min-width="180">
            <template #default="{ row }">
              <el-tag v-for="c in capsOf(row)" :key="c" size="small" effect="plain" class="cap-tag">{{ c }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="连接状态" min-width="150">
            <template #default="{ row }">
              <div v-if="row.lastTestOk" class="conn">
                <span class="connect-ok"><i class="dot"></i>正常</span>
                <span class="tiny muted">{{ row.lastLatencyMs }}ms · {{ row.lastTestAt }}</span>
              </div>
              <span v-else class="connect-bad"><i class="dot"></i>未通过测试</span>
            </template>
          </el-table-column>
          <el-table-column label="计量方式" min-width="200">
            <template #default="{ row }">
              <span class="tiny" :class="{ 'cost-unknown': row.billingUnit !== 'NONE' && row.inputPrice == null && row.outputPrice == null && row.perCallPrice == null }">
                {{ billingText(row) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="250" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text :icon="Connection" :loading="isTesting(row.id)" @click="testModel(row)">测试</el-button>
              <el-button
                size="small"
                text
                type="primary"
                :disabled="row.isActive || !row.lastTestOk"
                :title="row.lastTestOk ? '' : '请先通过连接测试'"
                :loading="switching"
                @click="activate(row)"
              >
                {{ row.isActive ? '已启用' : '启用' }}
              </el-button>
              <el-button size="small" text :icon="Edit" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" text type="danger" :icon="Delete" :disabled="row.isActive" @click="removeModel(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- ② 嵌入模型（只读单例） -->
    <div class="card embed-card">
      <div class="card-head embed-head">
        <span class="card-title">② 嵌入模型</span>
        <span class="server-tag"><el-icon><Lock /></el-icon>全局单例 · 只读</span>
      </div>
      <div class="card-body">
        <div class="embed-grid">
          <div class="embed-main">
            <div class="field">
              <label>模型名称</label>
              <div class="ro-input"><span class="mono">{{ config.embedding.modelName }}</span><el-icon><Lock /></el-icon></div>
              <div class="hint warn">嵌入模型必须全局唯一：索引期与查询期必须是同一个模型，且 Milvus collection 维度固定，混用会让检索完全失效</div>
            </div>
            <div class="form-grid3">
              <div class="field">
                <label>向量维度</label>
                <div class="ro-input dark"><span class="mono">{{ config.embedding.dimension }}</span></div>
              </div>
              <div class="field">
                <label>归一化</label>
                <div class="ro-input dark"><span>{{ config.embedding.norm }}</span></div>
              </div>
              <div class="field">
                <label>批量大小</label>
                <div class="ro-input dark"><span class="mono">{{ config.embedding.batchSize }}</span></div>
              </div>
            </div>
            <div class="field">
              <label>相似度度量</label>
              <el-radio-group :model-value="config.embedding.metric" disabled>
                <el-radio value="COSINE">余弦相似度</el-radio>
                <el-radio value="DOT">内积</el-radio>
                <el-radio value="EUCLIDEAN">欧氏距离</el-radio>
              </el-radio-group>
            </div>
          </div>
          <div class="impact-box embed-impact">
            <div class="impact-head">
              <el-icon><Warning /></el-icon>
              <b>更换嵌入模型的影响（全库）</b>
            </div>
            <div class="impact-row"><span>受影响知识库</span><span class="mono">{{ embedImpact.kbCount }} 个（全部）</span></div>
            <div class="impact-row"><span>需重新向量化</span><span class="mono">{{ embedImpact.docCount.toLocaleString() }} 篇文档</span></div>
            <div class="impact-row"><span>需重建分片</span><span class="mono">{{ embedImpact.chunkCount.toLocaleString() }} 个</span></div>
            <div class="impact-row"><span>预估耗时</span><span class="mono">约 {{ embedImpact.estimateMinutes }} 分钟</span></div>
            <div class="impact-row last"><span>预估额外 tokens</span><span class="mono warn-num">约 {{ (embedImpact.extraTokens / 10000).toFixed(0) }} 万</span></div>
          </div>
        </div>
        <div class="info-box">
          <el-icon><InfoFilled /></el-icon>
          <span class="tiny">
            嵌入模型<b>由服务端配置，前端不提供新增入口（单例）</b>。运维变更配置后，系统启动时检测到与库内索引不一致，会在本页顶部提示「需重建索引」。
          </span>
        </div>
        <el-button type="warning" plain style="margin-top: 12px" @click="changeDialog = true">
          <el-icon><Refresh /></el-icon> 模拟「检测到配置变更」
        </el-button>
      </div>
    </div>

    <!-- ③ 重排模型 -->
    <div class="card">
      <div class="card-head">
        <span class="card-title">③ 重排模型</span>
        <el-tag size="small" type="info" effect="plain">可配多条 · 全局唯一启用</el-tag>
        <span class="sub">检索期精排；<b>只能在模型配置页切换</b>（切换前建议先跑评测集对比 Recall@5）</span>
        <div class="head-right">
          <el-button size="small" type="primary" plain :icon="Plus" @click="openCreate('RERANK')">新增</el-button>
        </div>
      </div>
      <div class="card-body">
        <el-table :data="config.rerank" size="small" class="model-table">
          <el-table-column label="显示名称" min-width="200">
            <template #default="{ row }">
              <div class="name-cell">
                <span class="nm">{{ row.displayName }}</span>
                <span v-if="row.isActive" class="active-tag">当前启用</span>
              </div>
              <div v-if="row.note" class="tiny muted">{{ row.note }}</div>
            </template>
          </el-table-column>
          <el-table-column label="模型名称" min-width="200">
            <template #default="{ row }"><span class="mono tiny">{{ row.modelName }}</span></template>
          </el-table-column>
          <el-table-column label="连接状态" min-width="160">
            <template #default="{ row }">
              <div v-if="row.lastTestOk" class="conn">
                <span class="connect-ok"><i class="dot"></i>正常</span>
                <span class="tiny muted">{{ row.lastLatencyMs }}ms · {{ row.lastTestAt }}</span>
              </div>
              <span v-else class="connect-bad"><i class="dot"></i>未通过测试</span>
            </template>
          </el-table-column>
          <el-table-column label="计量方式" min-width="200">
            <template #default="{ row }"><span class="tiny">{{ billingText(row) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="250" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text :icon="Connection" :loading="isTesting(row.id)" @click="testModel(row)">测试</el-button>
              <el-button size="small" text type="primary" :disabled="row.isActive || !row.lastTestOk" :loading="switching" @click="activate(row)">
                {{ row.isActive ? '已启用' : '切换' }}
              </el-button>
              <el-button size="small" text :icon="Edit" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" text type="danger" :icon="Delete" :disabled="row.isActive" @click="removeModel(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- ④ VL 模型 -->
    <div class="card">
      <div class="card-head">
        <span class="card-title">④ VL 视觉模型</span>
        <el-tag size="small" type="info" effect="plain">可配多条 · 全局唯一启用</el-tag>
        <span class="sub">承担解析期图表理解 + 问答期图片转描述；<b>只能在模型配置页切换</b></span>
        <div class="head-right">
          <el-button size="small" type="primary" plain :icon="Plus" @click="openCreate('VL')">新增</el-button>
        </div>
      </div>
      <div class="card-body">
        <el-table :data="config.vl" size="small" class="model-table">
          <el-table-column label="显示名称" min-width="200">
            <template #default="{ row }">
              <div class="name-cell">
                <span class="nm">{{ row.displayName }}</span>
                <span v-if="row.isActive" class="active-tag">当前启用</span>
              </div>
              <div v-if="row.note" class="tiny muted">{{ row.note }}</div>
            </template>
          </el-table-column>
          <el-table-column label="模型名称" min-width="230">
            <template #default="{ row }"><span class="mono tiny">{{ row.modelName }}</span></template>
          </el-table-column>
          <el-table-column label="能力标记" min-width="150">
            <template #default="{ row }">
              <el-tag v-for="c in capsOf(row)" :key="c" size="small" effect="plain" class="cap-tag">{{ c }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="连接状态" min-width="160">
            <template #default="{ row }">
              <div v-if="row.lastTestOk" class="conn">
                <span class="connect-ok"><i class="dot"></i>正常</span>
                <span class="tiny muted">{{ row.lastLatencyMs }}ms · {{ row.lastTestAt }}</span>
              </div>
              <span v-else class="connect-bad"><i class="dot"></i>未通过测试</span>
            </template>
          </el-table-column>
          <el-table-column label="计量方式" min-width="200">
            <template #default="{ row }"><span class="tiny">{{ billingText(row) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="250" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text :icon="Connection" :loading="isTesting(row.id)" @click="testModel(row)">测试</el-button>
              <el-button size="small" text type="primary" :disabled="row.isActive || !row.lastTestOk" :loading="switching" @click="activate(row)">
                {{ row.isActive ? '已启用' : '切换' }}
              </el-button>
              <el-button size="small" text :icon="Edit" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" text type="danger" :icon="Delete" :disabled="row.isActive" @click="removeModel(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="info-box">
          <el-icon><InfoFilled /></el-icon>
          <span class="tiny">
            VL 与嵌入的关键区别：换嵌入模型<b>必须全库重建（约 {{ embedImpact.estimateMinutes }} 分钟）</b>；换 VL 模型<b>只影响含图文档</b>，纯文本文档不受影响。
          </span>
        </div>
      </div>
    </div>

    <!-- 用量统计 -->
    <div class="card">
      <div class="card-head">
        <span class="card-title">用量统计</span>
        <span class="sub">本月（09-01 ~ 09-12）</span>
        <div class="head-right">
          <el-segmented v-model="usage.range" :options="[{ label: '本月', value: 'month' }, { label: '上月', value: 'lastMonth' }]" size="small" />
        </div>
      </div>
      <div class="card-body">
        <div class="usage-grid">
          <div><div class="uv">{{ usage.calls.toLocaleString() }}</div><div class="tiny muted">调用次数</div></div>
          <div><div class="uv">{{ (usage.tokens / 10000).toFixed(1) }} 万</div><div class="tiny muted">消耗 tokens</div></div>
          <div>
            <div class="uv">
              <template v-if="usage.cost == null">—</template>
              <template v-else>¥ {{ usage.cost }}</template>
              <el-tooltip placement="top">
                <template #content>
                  <div style="max-width: 260px; line-height: 1.7">
                    费用估算 = Σ(tokens × 单价)，为<b>派生指标、仅供参考</b>，非财务口径。<br />
                    偏差来源：供应商调价、缓存命中折扣、阶梯价 / 预充值优惠、按次或按图计价。
                  </div>
                </template>
                <el-icon class="cost-info"><InfoFilled /></el-icon>
              </el-tooltip>
            </div>
            <div class="tiny muted">费用估算</div>
          </div>
          <div><div class="uv">{{ usage.avgLatencySec }} s</div><div class="tiny muted">平均响应延迟</div></div>
        </div>

        <div v-if="usage.unpricedCount > 0" class="unpriced-note">
          <el-icon><InfoFilled /></el-icon>
          <span class="tiny">含 {{ usage.unpricedCount }} 个模型未配置单价，实际费用可能更高</span>
        </div>

        <div class="tiny muted sec-label">按用途分布</div>
        <div v-for="p in usage.byPurpose" :key="p.name" class="purpose-row">
          <div class="purpose-head">
            <span class="tiny">{{ p.name }}</span>
            <span class="tiny mono muted">{{ (p.tokens / 10000).toFixed(0) }} 万 tokens · {{ p.percent }}%</span>
          </div>
          <div class="progress"><i :style="{ width: p.percent + '%' }"></i></div>
        </div>

        <div class="tiny muted sec-label">按模型拆分</div>
        <el-table :data="usage.byModel" size="small" class="model-table">
          <el-table-column label="模型" min-width="220">
            <template #default="{ row }"><span class="mono tiny">{{ row.modelName }}</span></template>
          </el-table-column>
          <el-table-column label="类型" width="100">
            <template #default="{ row }"><el-tag size="small" effect="plain">{{ typeLabel(row.modelType) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="调用次数" width="110" align="right">
            <template #default="{ row }"><span class="mono tiny">{{ row.calls.toLocaleString() }}</span></template>
          </el-table-column>
          <el-table-column label="tokens" width="110" align="right">
            <template #default="{ row }"><span class="mono tiny">{{ (row.tokens / 10000).toFixed(1) }} 万</span></template>
          </el-table-column>
          <el-table-column label="费用估算" min-width="180" align="right">
            <template #default="{ row }">
              <span class="tiny" :class="{ 'cost-none': row.billingUnit === 'NONE', 'cost-unknown': row.billingUnit !== 'NONE' && row.cost == null }">
                {{ rowCost(row) }}
              </span>
            </template>
          </el-table-column>
        </el-table>

        <div class="info-box">
          <el-icon><InfoFilled /></el-icon>
          <span class="tiny">
            索引一次性成本主要由<b>文档级摘要 + HyQ</b> 构成（合计 63%），问答运行成本只占 31%。二者成本模型不同：索引按文档量一次性，问答随使用量线性增长。
          </span>
        </div>
      </div>
    </div>

    <!-- 新增 / 编辑模型弹窗 -->
    <el-dialog v-model="editDialog" :title="editMode === 'create' ? `新增${TYPE_LABEL[editForm.modelType]}` : `编辑 · ${editForm.displayName}`" width="680px" destroy-on-close>
      <div class="edit-body">
        <div class="form-grid2">
          <div class="field">
            <label>显示名称 <span class="req">*</span></label>
            <el-input v-model="editForm.displayName" placeholder="如 DeepSeek-V3（硅基流动）" />
            <div class="hint">用于问答页切换器展示</div>
          </div>
          <div class="field">
            <label>模型类型</label>
            <el-select v-model="editForm.modelType" style="width: 100%" :disabled="editMode === 'edit'">
              <el-option label="对话 CHAT" value="CHAT" />
              <el-option label="重排 RERANK" value="RERANK" />
              <el-option label="VL 视觉" value="VL" />
            </el-select>
            <div class="hint">创建后不可修改</div>
          </div>
        </div>

        <div class="form-grid2">
          <div class="field">
            <label>接口协议</label>
            <el-select v-model="editForm.protocol" style="width: 100%">
              <el-option label="OpenAI 兼容" value="OpenAI 兼容" />
              <el-option label="Azure OpenAI" value="Azure OpenAI" />
            </el-select>
          </div>
          <div class="field">
            <label>模型名称 <span class="req">*</span></label>
            <el-input v-model="editForm.modelName" class="mono" placeholder="deepseek-ai/DeepSeek-V3" />
          </div>
        </div>

        <div class="field">
          <label>Base URL <span class="req">*</span></label>
          <el-input v-model="editForm.baseUrl" class="mono" placeholder="https://api.siliconflow.cn/v1" />
        </div>

        <div class="field">
          <label>API Key</label>
          <div class="api-key-row">
            <el-input v-if="editMode === 'edit' && !editForm.keyChanged" :model-value="editForm.apiKeyMasked" class="mono" disabled />
            <el-input v-else v-model="editForm.apiKey" class="mono" type="password" show-password placeholder="粘贴明文，保存后不再回显" />
            <el-button v-if="editMode === 'edit' && !editForm.keyChanged" @click="editForm.keyChanged = true">更换</el-button>
          </div>
          <div class="hint">加密存储，保存后不再明文回显，日志中脱敏</div>
        </div>

        <div class="field">
          <label>能力标记</label>
          <div class="caps-row">
            <el-checkbox v-model="editForm.stream">支持流式</el-checkbox>
            <el-checkbox v-model="editForm.vision">支持视觉（vision）</el-checkbox>
            <el-checkbox v-model="editForm.supportSystemRole">支持 system role</el-checkbox>
            <span class="cap-ctx">
              上下文窗口
              <el-input-number v-model="editForm.contextWindow" :min="2000" :max="1000000" :step="1000" size="small" controls-position="right" />
              tokens
            </span>
          </div>
          <div class="hint">问答页切换时用它做上下文校验，并对能力差异做提示</div>
        </div>

        <div class="form-grid4">
          <div class="field">
            <label>温度</label>
            <el-input-number v-model="editForm.temperature" :min="0" :max="2" :step="0.1" style="width: 100%" />
          </div>
          <div class="field">
            <label>最大输出</label>
            <el-input-number v-model="editForm.maxTokens" :min="0" :max="8192" :step="256" style="width: 100%" />
          </div>
          <div class="field">
            <label>超时（秒）</label>
            <el-input-number v-model="editForm.timeoutSec" :min="10" :max="300" :step="10" style="width: 100%" />
          </div>
          <div class="field">
            <label>失败重试</label>
            <el-input-number v-model="editForm.retries" :min="0" :max="5" style="width: 100%" />
          </div>
        </div>

        <div class="field">
          <label>计费方式 <span class="muted tiny">（费用估算的依据，可留空）</span></label>
          <el-radio-group v-model="editForm.billingUnit">
            <el-radio value="PER_TOKEN">按 token</el-radio>
            <el-radio value="PER_CALL">按次</el-radio>
            <el-radio value="NONE">本地部署（不计 API 费用）</el-radio>
          </el-radio-group>
          <div v-if="editForm.billingUnit === 'PER_TOKEN'" class="price-row">
            <span class="tiny">输入</span>
            <el-input-number v-model="editForm.inputPrice" :min="0" :precision="2" size="small" placeholder="留空" controls-position="right" />
            <span class="tiny">元 / 百万 tokens</span>
            <span class="tiny" style="margin-left: 12px">输出</span>
            <el-input-number v-model="editForm.outputPrice" :min="0" :precision="2" size="small" placeholder="留空" controls-position="right" />
            <span class="tiny">元 / 百万 tokens</span>
          </div>
          <div v-else-if="editForm.billingUnit === 'PER_CALL'" class="price-row">
            <span class="tiny">每次</span>
            <el-input-number v-model="editForm.perCallPrice" :min="0" :precision="2" size="small" placeholder="留空" controls-position="right" />
            <span class="tiny">元 / 次</span>
          </div>
          <div class="hint">
            <b>留空 = 未配置（费用未知）</b>；<b>选择「本地部署」= 已知不计费</b>。两者语义不同，用量表中分别显示「—」与「本地部署 · 不计 API 费用」。
          </div>
        </div>

        <div class="field">
          <label>备注</label>
          <el-input v-model="editForm.note" placeholder="用途说明，如「仅用于扫描件解析」" />
        </div>

        <div v-if="editNeedsRetest" class="test-box warn-box">
          <el-icon><Warning /></el-icon>
          <span class="tiny">保存后原连接测试结论将失效，需<b>重新测试通过</b>才能启用（避免把不可用配置激活导致全站问答挂掉）。</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveModel">保存</el-button>
      </template>
    </el-dialog>

    <!-- 切换 VL 模型确认 -->
    <el-dialog v-model="vlDialog" title="切换 VL 视觉模型" width="620px" destroy-on-close>
      <div v-loading="vlImpactLoading">
        <div class="rebuild-warn">
          <el-icon><Warning /></el-icon>
          <span class="tiny">
            <b>仅新解析的文档生效</b>：已入库文档的图片描述不会自动更新（仍是旧模型产出）。纯文本文档不受影响，无需全库重建。
          </span>
        </div>
        <div class="field">
          <label>影响范围（仅含图文档）</label>
          <div class="impact-box solid">
            <div class="impact-row"><span>涉及知识库</span><span class="mono">{{ vlImpact.kbCount }} 个</span></div>
            <div class="impact-row"><span>含图片 / 扫描件的文档</span><span class="mono">{{ vlImpact.imageDocCount }} 篇</span></div>
            <div class="impact-row"><span>需重新理解的图片</span><span class="mono">{{ vlImpact.imageCount }} 张</span></div>
            <div class="impact-row"><span>预估耗时</span><span class="mono">约 {{ vlImpact.estimateMinutes }} 分钟</span></div>
            <div class="impact-row last"><span>预估额外 tokens</span><span class="mono warn-num">约 {{ (vlImpact.extraTokens / 10000).toFixed(1) }} 万</span></div>
          </div>
        </div>
        <div class="info-box">
          <el-icon><InfoFilled /></el-icon>
          <span class="tiny">对比：换嵌入模型需要<b>全库重建（约 {{ embedImpact.estimateMinutes }} 分钟 / 8 个库）</b>；换 VL 只影响上面这些含图文档。</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="vlDialog = false">取消</el-button>
        <el-button @click="confirmVlSwitch(false)">仅切换（不重建）</el-button>
        <el-button type="primary" @click="confirmVlSwitch(true)">切换并重建含图文档</el-button>
      </template>
    </el-dialog>

    <!-- 更换嵌入模型确认弹窗 -->
    <el-dialog v-model="changeDialog" title="检测到嵌入模型配置已变更" width="620px" destroy-on-close>
      <div class="rebuild-body">
        <div class="rebuild-warn">
          <el-icon><Warning /></el-icon>
          <span class="tiny">系统启动时检测到<b>服务端的嵌入模型配置与库内索引不一致</b>。索引不重建将无法继续检索，请确认后执行全库重建。</span>
        </div>
        <div class="model-compare">
          <div class="mc-side">
            <div class="tiny muted">库内索引（当前）</div>
            <div class="mono mc-name">{{ config.embedding.modelName }}</div>
            <div class="tiny muted">{{ config.embedding.dimension }} 维</div>
          </div>
          <el-icon class="mc-arrow"><Right /></el-icon>
          <div class="mc-side new">
            <div class="tiny muted">服务端配置（新）</div>
            <div class="mono mc-name">{{ rebuildForm.newModel }}</div>
            <div class="tiny muted">{{ rebuildForm.newDimension }} 维</div>
          </div>
        </div>
        <div class="field">
          <label>影响范围</label>
          <div class="impact-box solid">
            <div class="impact-row"><span>受影响的全部知识库</span><span class="mono">{{ embedImpact.kbCount }} 个</span></div>
            <div class="impact-row"><span>需重新向量化的文档</span><span class="mono">{{ embedImpact.docCount.toLocaleString() }} 篇</span></div>
            <div class="impact-row"><span>需重建的分片</span><span class="mono">{{ embedImpact.chunkCount.toLocaleString() }} 个</span></div>
            <div class="impact-row"><span>预估耗时</span><span class="mono">约 {{ embedImpact.estimateMinutes }} 分钟</span></div>
            <div class="impact-row last"><span>预估额外 tokens 消耗</span><span class="mono warn-num">约 {{ (embedImpact.extraTokens / 10000).toFixed(0) }} 万</span></div>
          </div>
        </div>
        <div class="field">
          <label>重建期间的服务状态</label>
          <el-radio-group v-model="rebuildForm.rebuildMode" class="mode-list">
            <el-radio value="DEGRADED" border class="mode-item">
              <div class="mode-inner"><b>降级为关键词检索</b><span class="tiny muted">推荐，问答仍可用但精度下降</span></div>
            </el-radio>
            <el-radio value="PAUSE" border class="mode-item">
              <div class="mode-inner"><b>暂停问答服务</b><span class="tiny muted">重建更快，但用户完全无法提问</span></div>
            </el-radio>
          </el-radio-group>
        </div>
        <div class="rebuild-warn">
          <el-icon><Warning /></el-icon>
          <span class="tiny">
            向量维度从 {{ config.embedding.dimension }} 变为 {{ rebuildForm.newDimension }}，<b>Milvus collection 需要整体重建</b>，旧数据不会保留。
            重建过程中若中断，需要重新开始。
          </span>
        </div>
        <el-checkbox v-model="rebuildForm.confirmed" class="confirm-check">
          <span class="tiny">我已知晓影响范围，确认更换并重建全部索引</span>
        </el-checkbox>
      </div>
      <template #footer>
        <el-button @click="changeDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmChange">确认重建全部索引</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sys-model-page {
  height: 100%;
  overflow-y: auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}
.head-actions {
  display: flex;
  gap: 8px;
}

/* 待重建横幅 */
.rebuild-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--el-color-warning-light-9);
  border: 1px solid #faecd8;
  border-radius: 8px;
  padding: 11px 14px;
  margin-bottom: 14px;
  font-size: 12.5px;
  color: #8a6d3b;
}
.rebuild-banner .el-icon {
  color: var(--el-color-warning);
  flex: none;
}

/* 统计 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
.stat-card {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.stat-card .si {
  width: 42px;
  height: 42px;
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 20px;
  flex: none;
}
.stat-card .si.p {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.stat-card .si.o {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.stat-mid {
  min-width: 0;
}
.stat-card .sv {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.3;
}
.stat-card .sl {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.connect-ok {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
  padding: 1px 8px;
  border-radius: 4px;
}
.connect-bad {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  padding: 1px 8px;
  border-radius: 4px;
}
.connect-ok .dot,
.connect-bad .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

/* 卡片 */
.card {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  margin-bottom: 16px;
}
.embed-card {
  border-color: #faecd8;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.card-title {
  font-size: 14px;
  font-weight: 600;
}
.embed-head {
  background: #fffcf6;
}
.server-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: auto;
}
.card-body {
  padding: 14px 18px 18px;
}
.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.head-right {
  margin-left: auto;
  display: flex;
  gap: 8px;
  align-items: center;
}

/* 模型表格 */
.model-table {
  --el-table-border-color: var(--el-border-color-lighter);
}
.name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.name-cell .nm {
  font-weight: 500;
}
.active-tag {
  font-size: 11px;
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
  border: 1px solid var(--el-color-success-light-7);
  padding: 0 6px;
  border-radius: 4px;
}
.cap-tag {
  margin: 2px 4px 2px 0;
}
.conn {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.cost-unknown {
  color: var(--el-text-color-placeholder);
}
.cost-none {
  color: var(--el-text-color-secondary);
}

/* 字段 */
.field {
  margin-bottom: 15px;
}
.field label {
  display: block;
  font-size: 13px;
  color: var(--el-text-color-regular);
  margin-bottom: 6px;
}
.req {
  color: var(--el-color-danger);
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
  line-height: 1.6;
}
.hint.warn {
  color: var(--el-color-warning);
}
.form-grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}
.form-grid3 {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 0 12px;
}
.form-grid4 {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0 12px;
}
.api-key-row {
  display: flex;
  gap: 8px;
}
.api-key-row .el-input {
  flex: 1;
}
.ro-input {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--el-bg-color-page);
  color: var(--el-text-color-secondary);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  height: 32px;
  padding: 0 12px;
  font-size: 13px;
}
.ro-input.dark {
  justify-content: flex-start;
  color: var(--el-text-color-primary);
}
.caps-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
}
.cap-ctx {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.cap-ctx .el-input-number {
  width: 130px;
}
.price-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.price-row .el-input-number {
  width: 130px;
}
.test-box {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 13px;
  border-radius: 8px;
}
.test-box .el-icon {
  flex: none;
}
.test-box .tiny {
  line-height: 1.6;
}
.warn-box {
  background: var(--el-color-warning-light-9);
  color: #8a6d3b;
}
.warn-box .el-icon {
  color: var(--el-color-warning);
}

/* 嵌入 */
.embed-grid {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: 20px;
  align-items: start;
}
.impact-box {
  border: 1px solid #faecd8;
  border-radius: 8px;
  padding: 12px 13px;
  background: #fffcf6;
}
.impact-box.solid {
  background: transparent;
  border-color: var(--el-border-color-lighter);
}
.impact-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  color: #8a6d3b;
  font-size: 13px;
}
.impact-head .el-icon {
  color: var(--el-color-warning);
}
.impact-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--el-text-color-regular);
  padding: 5px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.impact-row.last {
  border-bottom: none;
}
.warn-num {
  color: var(--el-color-warning);
}

.info-box {
  display: flex;
  gap: 9px;
  padding: 10px 12px;
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
  color: #3a6ea5;
  margin-top: 12px;
}
.info-box .el-icon {
  color: var(--el-color-primary);
  flex: none;
  margin-top: 2px;
}
.info-box .tiny {
  line-height: 1.65;
}

/* 用量 */
.usage-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}
.uv {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.3;
  display: flex;
  align-items: center;
  gap: 4px;
}
.cost-info {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  cursor: help;
}
.tiny {
  font-size: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.mono {
  font-family: var(--font-mono);
}
.unpriced-note {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 11px;
  background: var(--el-color-warning-light-9);
  border-radius: 6px;
  color: #8a6d3b;
  margin-bottom: 16px;
}
.unpriced-note .el-icon {
  color: var(--el-color-warning);
}
.sec-label {
  margin: 14px 0 10px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.purpose-row {
  margin-bottom: 12px;
}
.purpose-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
}
.progress {
  height: 7px;
  border-radius: 4px;
  background: var(--el-border-color-lighter);
  overflow: hidden;
}
.progress i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--el-color-primary);
}

/* 弹窗 */
.edit-body .field {
  margin-bottom: 14px;
}
.rebuild-body .field {
  margin-bottom: 14px;
}
.rebuild-warn {
  display: flex;
  gap: 9px;
  padding: 11px 13px;
  background: var(--el-color-warning-light-9);
  border-radius: 8px;
  color: #8a6d3b;
  margin-bottom: 16px;
}
.rebuild-warn .el-icon {
  color: var(--el-color-warning);
  flex: none;
  margin-top: 2px;
}
.rebuild-warn .tiny {
  line-height: 1.65;
}
.model-compare {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px;
  background: #fafbfd;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  margin-bottom: 16px;
}
.mc-side {
  flex: 1;
  text-align: center;
}
.mc-name {
  font-size: 13.5px;
  font-weight: 500;
  margin: 5px 0 3px;
}
.mc-side.new .mc-name {
  color: var(--el-color-primary);
}
.mc-arrow {
  color: var(--el-color-primary);
  flex: none;
}
.mode-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.mode-item {
  width: 100%;
  height: 46px;
  margin-right: 0;
}
.mode-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 6px;
  width: 100%;
}
.confirm-check {
  margin-top: 4px;
}
</style>
