<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  Lock,
  InfoFilled,
  WarningFilled,
  Setting,
  Delete,
  Refresh,
  Collection,
  Upload,
  TrendCharts,
  Files,
  Picture,
} from '@element-plus/icons-vue'
import { kbApi, sysApi } from '@/api'
import type { KbConfigForm, SplitPresetDef, SecretLevel, RetrievalMode, DeptNode, ConfigDiffItem, ReindexImpact, EmbeddingInfo } from '@/types'

const route = useRoute()
const router = useRouter()
const kbId = computed(() => String(route.params.id ?? '1'))

const SECRET_LABEL: Record<SecretLevel, string> = { PUBLIC: '公开', INTERNAL: '内部', CONFIDENTIAL: '机密', SECRET: '绝密' }
const RETRIEVAL_LABEL: Record<RetrievalMode, string> = { VECTOR: '向量检索', HYBRID: '混合检索', KEYWORD: '关键词检索' }
const DOMAINS = ['研发 / 平台', '研发 / 应用', '销售 / 售前', '人力资源', '财务 / 税务', '运维 / 安全', '培训 / 赋能']
const SECRET_ORDER: SecretLevel[] = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET']
const PRESET_DESC: Record<string, string> = {
  GENERAL: '结构一般、内容混合的常规资料',
  STRUCTURED: '标题层级清晰的规范 / 手册 / API 文档',
  CONTRACT: '条款编号明确、需按条切分',
  TABLE_HEAVY: '报价单、参数表为主的资料',
  QA_PAIR: 'FAQ、问答知识库',
}

const loading = ref(false)
const saving = ref(false)
const rebuilding = ref(false)
const activeTab = ref('basic')
const meta = ref({ name: '', code: '', department: '', docCount: 0, chunkCount: 0, status: '', statusLabel: '', createdAt: '', updatedAt: '' })
const form = ref<KbConfigForm | null>(null)
const orig = ref<KbConfigForm | null>(null)
const presets = ref<SplitPresetDef[]>([])
/**
 * 嵌入模型信息（`#225` · `T-O16`）。
 *
 * <h3>🔴 初值为什么是 `null`，而不是一份写死的 `BAAI/bge-m3 · 1024 维`</h3>
 * 那三个值原来在前端各写了一份（这里的初值与后端 `KbService#embeddingInfo()` 是
 * **两个来源**）。页面在 `/kb/presets` 回来之前、以及**它失败的时候**，
 * 显示的就是那一份猜的 —— 而它长得和真值一模一样（`#85` 那一族的「陷阱：
 * 没人用所以漂移看不见」）。改成 `null`：拿到之前/拿不到时显示「—」，**不猜**。
 * ⚠️ 与同一个文件里的 `vlModel` 同一条口径（它的初值本来就是 `'—'`）。
 */
const embedding = ref<EmbeddingInfo | null>(null)

/**
 * 嵌入模型的展示文本。`—` = **还没有这个值**（接口没回来，或它失败了）——
 * 与 `avgTopScore` 用 `null` 而不是 0 是同一条口径：**不拿猜的值冒充**（`#225`）。
 */
const embeddingText = computed(() => {
  const e = embedding.value
  return e ? `${e.model} · ${e.dimension} 维 · ${e.norm}` : '—'
})
const vlModel = ref({ name: '—', enabled: false, appliedAt: '' })
const deptTree = ref<DeptNode[]>([])
const roleOptions = ref<{ code: string; name: string }[]>([])
const customTagInput = ref('')

// 变更影响确认
const confirmVisible = ref(false)
const impact = ref<ReindexImpact | null>(null)
const rebuildMode = ref<'IMMEDIATE' | 'SCHEDULED'>('SCHEDULED')
const impactLoading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    const [cfg, t, r] = await Promise.all([kbApi.config(kbId.value), sysApi.deptTree(), sysApi.roles({})])
    meta.value = {
      name: cfg.name,
      code: cfg.code,
      department: cfg.department,
      docCount: cfg.docCount,
      chunkCount: cfg.chunkCount,
      status: cfg.status,
      statusLabel: cfg.statusLabel,
      createdAt: cfg.createdAt,
      updatedAt: cfg.updatedAt,
    }
    form.value = structuredClone(cfg.config)
    orig.value = structuredClone(cfg.config)
    presets.value = cfg.presetOptions
    embedding.value = cfg.embedding
    vlModel.value = cfg.vlModel ?? vlModel.value
    deptTree.value = (t as unknown as DeptNode[]) ?? []
    roleOptions.value = ((r.list as unknown as { code: string; name: string }[]) ?? []).map((x) => ({ code: x.code, name: x.name }))
  } finally {
    loading.value = false
  }
})

// ---------------- dirty 检测 ----------------
function getByPath(obj: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((acc, k) => (acc == null ? acc : (acc as Record<string, unknown>)[k]), obj)
}
function fmt(v: unknown): string {
  if (v == null) return '—'
  if (typeof v === 'boolean') return v ? '开启' : '关闭'
  if (Array.isArray(v)) return v.length ? v.join('、') : '—'
  if (typeof v === 'number') return String(v)
  return String(v)
}
function dirty(path: string): boolean {
  if (!form.value || !orig.value) return false
  return fmt(getByPath(form.value, path)) !== fmt(getByPath(orig.value, path))
}
function beforeVal(path: string): string {
  if (!orig.value) return '—'
  const v = getByPath(orig.value, path)
  if (path === 'deptId') return deptName(Number(v))
  if (path === 'secretLevel') return SECRET_LABEL[v as SecretLevel] ?? fmt(v)
  if (path === 'retrievalConfig.mode') return RETRIEVAL_LABEL[v as RetrievalMode] ?? fmt(v)
  if (path === 'splitPolicy.preset') return presetLabel(String(v))
  if (path === 'visibleRoles') return ((v as string[]) ?? []).join('、') || '—'
  return fmt(v)
}

const TAB_FIELDS: Record<string, string[]> = {
  basic: ['name', 'description', 'deptId'],
  split: [
    'splitPolicy.preset',
    'splitPolicy.chunkSize',
    'splitPolicy.overlap',
    'splitPolicy.semanticSplit',
    'retrievalConfig.mode',
    'retrievalConfig.recallThreshold',
    'retrievalConfig.rerankTopK',
    'retrievalConfig.rerankEnabled',
  ],
  table: ['tablePolicy.dualRepresentation', 'tablePolicy.repeatHeader', 'tablePolicy.mergeCrossPage', 'tablePolicy.ocrTable', 'tablePolicy.rowLimit', 'tablePolicy.rowWindow'],
  tags: ['secretLevel', 'businessDomain', 'customTags', 'visibleRoles'],
  danger: [],
}
/** 触发重建的字段（分片与索引 + 表格处理） */
const REBUILD_FIELDS = [...TAB_FIELDS.split!, ...TAB_FIELDS.table!]

function dirtyCount(fields: string[]) {
  return fields.filter((f) => dirty(f)).length
}
const tabDirty = computed(() => ({
  basic: dirtyCount(TAB_FIELDS.basic!),
  split: dirtyCount(TAB_FIELDS.split!),
  table: dirtyCount(TAB_FIELDS.table!),
  tags: dirtyCount(TAB_FIELDS.tags!),
}))
const totalDirty = computed(() => tabDirty.value.basic + tabDirty.value.split + tabDirty.value.table + tabDirty.value.tags)
const needReindex = computed(() => dirtyCount(REBUILD_FIELDS) > 0)

const diffList = computed<ConfigDiffItem[]>(() => {
  const all = [...TAB_FIELDS.basic!, ...TAB_FIELDS.split!, ...TAB_FIELDS.table!, ...TAB_FIELDS.tags!]
  return all
    .filter((f) => dirty(f))
    .map((f) => ({ label: FIELD_LABEL[f] ?? f, before: beforeVal(f), after: currentVal(f) }))
})

const FIELD_LABEL: Record<string, string> = {
  name: '知识库名称',
  description: '描述',
  deptId: '归属部门',
  'splitPolicy.preset': '分片策略预设',
  'splitPolicy.chunkSize': 'chunkSize',
  'splitPolicy.overlap': 'overlap',
  'splitPolicy.semanticSplit': '语义切分',
  'retrievalConfig.mode': '检索方式',
  'retrievalConfig.recallThreshold': '召回阈值',
  'retrievalConfig.rerankTopK': 'Rerank 保留数',
  'retrievalConfig.rerankEnabled': 'Rerank 开关',
  'tablePolicy.dualRepresentation': '双表示',
  'tablePolicy.repeatHeader': '表头重复',
  'tablePolicy.mergeCrossPage': '跨页表格合并',
  'tablePolicy.ocrTable': '扫描件表格识别',
  'tablePolicy.rowLimit': '回表行数上限',
  'tablePolicy.rowWindow': '命中行窗口',
  secretLevel: '密级',
  businessDomain: '业务域',
  customTags: '自定义标签',
  visibleRoles: '可见角色',
}
function currentVal(path: string): string {
  if (!form.value) return '—'
  const v = getByPath(form.value, path)
  if (path === 'deptId') return deptName(Number(v))
  if (path === 'secretLevel') return SECRET_LABEL[v as SecretLevel] ?? fmt(v)
  if (path === 'retrievalConfig.mode') return RETRIEVAL_LABEL[v as RetrievalMode] ?? fmt(v)
  if (path === 'splitPolicy.preset') return presetLabel(String(v))
  if (path === 'visibleRoles') return ((v as string[]) ?? []).join('、') || '—'
  return fmt(v)
}

function deptName(id: number): string {
  const walk = (nodes: DeptNode[], prefix: string): string | null => {
    for (const n of nodes) {
      if (n.id === id) return prefix + n.name
      if (n.children) {
        const r = walk(n.children, prefix + n.name + ' / ')
        if (r) return r
      }
    }
    return null
  }
  return walk(deptTree.value, '') ?? '—'
}
function presetLabel(id: string): string {
  if (id === 'CUSTOM') return '自定义'
  return presets.value.find((p) => p.id === id)?.label ?? '通用文档'
}

// ---------------- 交互 ----------------
function applyPreset(id: string) {
  if (!form.value) return
  const p = presets.value.find((x) => x.id === id)
  if (!p) {
    form.value.splitPolicy.preset = 'CUSTOM'
    return
  }
  form.value.splitPolicy = { preset: p.id, chunkSize: p.chunkSize, overlap: p.overlap, recursiveSplit: true, semanticSplit: p.semanticSplit }
}
function markCustom() {
  if (form.value && form.value.splitPolicy.preset !== 'CUSTOM') form.value.splitPolicy.preset = 'CUSTOM'
}
function addTag() {
  if (!form.value) return
  const v = customTagInput.value.trim()
  if (!v) return
  if (form.value.customTags.includes(v)) return ElMessage.warning('标签已存在')
  form.value.customTags.push(v)
  customTagInput.value = ''
}
function removeTag(t: string) {
  if (form.value) form.value.customTags = form.value.customTags.filter((x) => x !== t)
}

function discard() {
  if (!orig.value) return
  form.value = structuredClone(orig.value)
  ElMessage.info('已放弃全部修改')
}

// ---------------- 保存 ----------------
async function openSaveConfirm() {
  if (!totalDirty.value) return ElMessage.info('没有需要保存的修改')
  // ⚠️ 这行守卫**不是**防御性代码，是 `form` 的联合类型（`KbConfigForm | null`）必须收敛。
  // `totalDirty` 内部已经判过 `!form.value`（见其计算属性），所以运行期永远不为 null；
  // 但 TS 的窄化不跨函数边界 —— 少了这行 `vue-tsc --build` 会红在下一行的实参上
  // （S4-M05-A 施工时发现：`check.mjs` 的 type-check 在 HEAD 上就是红的）。
  if (!form.value) return
  confirmVisible.value = true
  impactLoading.value = true
  impact.value = null
  try {
    // 发**完整表单**（不是 diff 列表）：服务端用与保存同一段代码算变了哪些字段，
    // 估算才和真实保存一致（发中文展示串的话服务端得反推 chunkSize）
    impact.value = await kbApi.impact(kbId.value, form.value)
  } finally {
    impactLoading.value = false
  }
}

async function confirmSave() {
  if (!form.value) return
  saving.value = true
  try {
    await kbApi.saveConfig(kbId.value, form.value)
    meta.value.name = form.value.name
    meta.value.department = deptName(form.value.deptId)
    if (needReindex.value) {
      const res = await kbApi.reindex(kbId.value, rebuildMode.value)
      if (rebuildMode.value === 'IMMEDIATE') {
        rebuilding.value = true
        ElMessage.success('配置已保存，重建索引已启动（重建期间本库检索质量下降）')
        setTimeout(() => (rebuilding.value = false), 3500)
      } else {
        ElMessage.success(`配置已保存，已加入计划任务（${res.scheduledAt} 自动执行）`)
      }
    } else {
      ElMessage.success('配置已保存')
    }
    orig.value = structuredClone(form.value)
    confirmVisible.value = false
  } catch {
    ElMessage.error('保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

// ---------------- 危险操作 ----------------
async function clearIndex() {
  await ElMessageBox.confirm('清空索引后该知识库将无法被检索，需重新上传或重建才能恢复。确认继续？', '清空索引', { type: 'warning' })
  await kbApi.clearIndex(kbId.value)
  ElMessage.success('索引已清空')
}
async function deleteKb() {
  const name = meta.value.name
  await ElMessageBox.prompt(`此操作不可恢复，将同时清理向量数据。请输入知识库名称「${name}」以确认。`, '删除知识库', {
    type: 'warning',
    inputPlaceholder: name,
    inputValidator: (v: string) => v === name || '名称不匹配',
    confirmButtonText: '删除',
    confirmButtonClass: 'el-button--danger',
  })
  // 软删进回收站（接口约定 §5 `DELETE /kb/{id}`）
  await kbApi.remove(kbId.value)
  ElMessage.success('知识库已删除，可在回收站恢复')
  router.push('/kb')
}
</script>

<template>
  <div v-loading="loading" class="page detail-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="head-left">
        <el-button text :icon="ArrowLeft" @click="router.push('/kb')">返回列表</el-button>
        <div>
          <h2 class="page-title">{{ meta.name }}</h2>
          <p class="page-desc">
            {{ meta.department }} · {{ meta.docCount }} 篇文档 · {{ meta.chunkCount.toLocaleString() }} 个分片 ·
            {{ meta.code }}
          </p>
        </div>
      </div>
      <div class="head-actions">
        <el-button :icon="Upload" @click="router.push(`/kb/${kbId}/upload`)">上传文档</el-button>
        <el-button :icon="Files" @click="router.push(`/kb/${kbId}/upload`)">文档列表</el-button>
      </div>
    </div>

    <!-- 重建中提示 -->
    <el-alert v-if="rebuilding" type="warning" :closable="false" show-icon class="mb-12">
      <template #title>索引重建进行中，表单已锁定，请稍候…</template>
    </el-alert>

    <!-- 页头常驻提示 -->
    <el-alert type="info" :closable="false" show-icon class="mb-12">
      <template #title>
        <span class="alert-text">
          本页配置变更<b>不会自动生效</b>，保存后需重建索引。重建期间该知识库的检索质量会下降，<b>建议在业务低峰期执行</b>。
        </span>
      </template>
    </el-alert>

    <el-card v-if="form" shadow="never" :class="{ 'form-locked': rebuilding }">
      <el-tabs v-model="activeTab">
        <!-- ========== 基本设置 ========== -->
        <el-tab-pane name="basic">
          <template #label>
            <span class="tab-label">基本设置<el-badge v-if="tabDirty.basic" :value="tabDirty.basic" class="tab-badge" /></span>
          </template>
          <el-form label-position="top" class="form-grid">
            <el-form-item label="知识库名称" :class="{ dirty: dirty('name') }">
              <el-input v-model="form.name" maxlength="40" show-word-limit />
              <div v-if="dirty('name')" class="diff-note">由「{{ beforeVal('name') }}」改为「{{ form.name }}」</div>
            </el-form-item>
            <el-form-item>
              <template #label>知识库编码 <el-icon class="lock-ico"><Lock /></el-icon></template>
              <el-input :model-value="meta.code" disabled />
              <div class="hint">创建后不可修改</div>
            </el-form-item>
            <el-form-item label="归属部门" :class="{ dirty: dirty('deptId') }">
              <el-tree-select
                v-model="form.deptId"
                :data="deptTree"
                :props="{
                  label: 'name',
                  children: 'children',
                  disabled: (d: DeptNode) => d.id === 0,
                }"
                node-key="id"
                check-strictly
                :render-after-expand="false"
                style="width: 100%"
              />
              <div v-if="dirty('deptId')" class="diff-note">由「{{ beforeVal('deptId') }}」改为「{{ deptName(form.deptId) }}」</div>
            </el-form-item>
            <el-form-item label="状态">
              <!-- W1：不按 status 分支 —— 后端的域内真值是 READY/INDEXING，
                   而 KnowledgeBase['status'] 是 ACTIVE|BUILDING|PAUSED，两者不是一个词表。
                   按 status 判 ACTIVE 会把真后端的 READY 渲染成裸字符串「READY」。 -->
              <el-tag size="small" type="success">{{ meta.statusLabel || meta.status }}</el-tag>
            </el-form-item>
            <el-form-item class="span-2" label="描述" :class="{ dirty: dirty('description') }">
              <el-input v-model="form.description" type="textarea" :rows="3" maxlength="200" show-word-limit />
              <div v-if="dirty('description')" class="diff-note">描述已修改</div>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- ========== 分片与索引 ========== -->
        <el-tab-pane name="split">
          <template #label>
            <span class="tab-label">分片与索引<el-badge v-if="tabDirty.split" :value="tabDirty.split" class="tab-badge" /></span>
          </template>

          <div class="field-title">分片策略预设</div>
          <div class="preset-row">
            <button
              v-for="p in presets"
              :key="p.id"
              class="preset-btn"
              :class="{ active: form.splitPolicy.preset === p.id }"
              @click="applyPreset(p.id)"
            >
              <span class="pb-label">{{ p.label }}</span>
              <span class="pb-desc">{{ PRESET_DESC[p.id] }}</span>
            </button>
          </div>
          <div class="hint mb-16">
            当前：<b>{{ presetLabel(form.splitPolicy.preset) }}</b>；选预设会覆盖参数，手改单项后标记为「自定义」
            <span v-if="dirty('splitPolicy.preset')" class="diff-note inline">由「{{ beforeVal('splitPolicy.preset') }}」改为「{{ presetLabel(form.splitPolicy.preset) }}」</span>
          </div>

          <div class="two-col">
            <div>
              <div class="field-title">切分参数</div>
              <div class="policy-box">
                <div class="level-one">
                  <div class="pl-head">
                    <span class="tag-mini">第一级</span><b>结构边界</b>
                    <span class="locked"><el-icon><Lock /></el-icon> 锁定</span>
                  </div>
                  <div class="chip-row">
                    <el-tag size="small" type="info">标题层级</el-tag>
                    <el-tag size="small" type="info">表格整表</el-tag>
                    <el-tag size="small" type="info">代码块</el-tag>
                    <el-tag size="small" type="info">完整列表</el-tag>
                  </div>
                </div>
                <div class="level-two">
                  <div class="switch-row">
                    <div>
                      <div class="sr-title">递归分隔符</div>
                      <div class="hint">段落 → 句号 → 分号 → 空格</div>
                    </div>
                    <el-tag size="small" type="success">默认开</el-tag>
                  </div>
                  <div class="switch-row">
                    <div>
                      <div class="sr-title">语义切分</div>
                      <div class="hint">无标题且超长时兜底</div>
                    </div>
                    <el-switch v-model="form.splitPolicy.semanticSplit" @change="markCustom" />
                  </div>
                  <div v-if="dirty('splitPolicy.semanticSplit')" class="diff-note">由「{{ beforeVal('splitPolicy.semanticSplit') }}」改为「{{ fmt(form.splitPolicy.semanticSplit) }}」</div>
                </div>
              </div>

              <div class="inline-fields">
                <el-form-item label="chunkSize" class="inline-item" :class="{ dirty: dirty('splitPolicy.chunkSize') }">
                  <el-input-number v-model="form.splitPolicy.chunkSize" :min="128" :max="2048" :step="64" controls-position="right" @change="markCustom" />
                  <span class="unit">tokens</span>
                  <div v-if="dirty('splitPolicy.chunkSize')" class="diff-note">由 {{ beforeVal('splitPolicy.chunkSize') }} 改为 {{ form.splitPolicy.chunkSize }}</div>
                </el-form-item>
                <el-form-item label="overlap" class="inline-item" :class="{ dirty: dirty('splitPolicy.overlap') }">
                  <el-input-number v-model="form.splitPolicy.overlap" :min="0" :max="512" :step="16" controls-position="right" @change="markCustom" />
                  <span class="unit">tokens</span>
                  <div v-if="dirty('splitPolicy.overlap')" class="diff-note">由 {{ beforeVal('splitPolicy.overlap') }} 改为 {{ form.splitPolicy.overlap }}</div>
                </el-form-item>
              </div>
            </div>

            <div>
              <div class="field-title">检索参数</div>
              <el-form-item label="检索方式" :class="{ dirty: dirty('retrievalConfig.mode') }">
                <el-radio-group v-model="form.retrievalConfig.mode">
                  <el-radio-button value="VECTOR">向量</el-radio-button>
                  <el-radio-button value="HYBRID">混合</el-radio-button>
                  <el-radio-button value="KEYWORD">关键词</el-radio-button>
                </el-radio-group>
                <div v-if="dirty('retrievalConfig.mode')" class="diff-note">
                  由「{{ beforeVal('retrievalConfig.mode') }}」改为「{{ RETRIEVAL_LABEL[form.retrievalConfig.mode] }}」
                </div>
              </el-form-item>
              <div class="inline-fields three">
                <el-form-item label="召回阈值" class="inline-item" :class="{ dirty: dirty('retrievalConfig.recallThreshold') }">
                  <el-input-number v-model="form.retrievalConfig.recallThreshold" :min="0" :max="1" :step="0.05" :precision="2" size="small" controls-position="right" />
                  <div v-if="dirty('retrievalConfig.recallThreshold')" class="diff-note">由 {{ beforeVal('retrievalConfig.recallThreshold') }} 改为 {{ form.retrievalConfig.recallThreshold }}</div>
                </el-form-item>
                <el-form-item label="Rerank 保留数" class="inline-item" :class="{ dirty: dirty('retrievalConfig.rerankTopK') }">
                  <el-input-number v-model="form.retrievalConfig.rerankTopK" :min="3" :max="15" size="small" controls-position="right" />
                  <div v-if="dirty('retrievalConfig.rerankTopK')" class="diff-note">由 {{ beforeVal('retrievalConfig.rerankTopK') }} 改为 {{ form.retrievalConfig.rerankTopK }}</div>
                </el-form-item>
                <el-form-item label="Rerank" class="inline-item" :class="{ dirty: dirty('retrievalConfig.rerankEnabled') }">
                  <el-switch v-model="form.retrievalConfig.rerankEnabled" />
                </el-form-item>
              </div>
              <div class="readonly-box mt-8">
                <span class="mono">{{ embeddingText }}</span>
                <el-icon><Lock /></el-icon>
              </div>
              <div class="hint">嵌入模型由服务端统一配置，前端只读</div>
              <div class="readonly-box mt-8">
                <span class="mono">
                  <el-icon class="vl-ic"><Picture /></el-icon>
                  VL 视觉模型：{{ vlModel.enabled ? vlModel.name : '未配置' }}
                  <template v-if="vlModel.enabled"> · 全局唯一<template v-if="vlModel.appliedAt"> · 自 {{ vlModel.appliedAt }} 起生效</template></template>
                </span>
                <el-icon><Lock /></el-icon>
              </div>
              <div class="hint">VL 模型全局唯一，仅影响含图片的文档（图片转文字描述后参与检索）；如需更换，请在「系统管理 · 模型配置」页操作</div>
            </div>
          </div>
        </el-tab-pane>

        <!-- ========== 表格处理 ========== -->
        <el-tab-pane name="table">
          <template #label>
            <span class="tab-label">表格处理<el-badge v-if="tabDirty.table" :value="tabDirty.table" class="tab-badge" /></span>
          </template>
          <div class="table-box">
            <div class="switch-row">
              <div>
                <div class="sr-title">整表原子化</div>
                <div class="hint">识别到表格即整块封存，不参与任何切分</div>
              </div>
              <div class="row-inline">
                <el-icon class="ok-icon"><Lock /></el-icon>
                <el-switch :model-value="true" disabled />
              </div>
            </div>
            <div class="switch-row" :class="{ dirty: dirty('tablePolicy.dualRepresentation') }">
              <div>
                <div class="sr-title">双表示</div>
                <div class="hint">Markdown 给大模型 / 行式文本给向量</div>
              </div>
              <el-switch v-model="form.tablePolicy.dualRepresentation" />
            </div>
            <div class="switch-row" :class="{ dirty: dirty('tablePolicy.repeatHeader') }">
              <div>
                <div class="sr-title">表头重复</div>
                <div class="hint">拆表时每个子块带表头与表标题</div>
              </div>
              <el-switch v-model="form.tablePolicy.repeatHeader" />
            </div>
            <div class="switch-row" :class="{ dirty: dirty('tablePolicy.mergeCrossPage') }">
              <div>
                <div class="sr-title">跨页表格合并</div>
                <div class="hint">列数一致时并回一张表</div>
              </div>
              <el-switch v-model="form.tablePolicy.mergeCrossPage" />
            </div>
            <div class="switch-row" :class="{ dirty: dirty('tablePolicy.ocrTable') }">
              <div>
                <div class="sr-title">扫描件表格识别</div>
                <div class="hint">含 OCR + 表格结构识别；失败会在文档列表标红</div>
              </div>
              <el-switch v-model="form.tablePolicy.ocrTable" />
            </div>
            <div class="switch-row" :class="{ dirty: dirty('tablePolicy.rowLimit') || dirty('tablePolicy.rowWindow') }">
              <div>
                <div class="sr-title">回表行数上限</div>
                <div class="hint">超出时按命中行窗口返回，避免撑爆上下文</div>
              </div>
              <div class="row-inline">
                <el-input-number v-model="form.tablePolicy.rowLimit" :min="5" :max="200" size="small" controls-position="right" style="width: 92px" />
                <span class="unit">行 ±</span>
                <el-input-number v-model="form.tablePolicy.rowWindow" :min="0" :max="50" size="small" controls-position="right" style="width: 86px" />
              </div>
            </div>
          </div>
          <div v-if="dirtyCount(TAB_FIELDS.table!)" class="diff-note mt-8">{{ dirtyCount(TAB_FIELDS.table!) }} 项表格处理配置已修改，保存后需重建索引</div>
        </el-tab-pane>

        <!-- ========== 标签与授权 ========== -->
        <el-tab-pane name="tags">
          <template #label>
            <span class="tab-label">标签与授权<el-badge v-if="tabDirty.tags" :value="tabDirty.tags" class="tab-badge" /></span>
          </template>
          <div class="two-col">
            <div>
              <div class="field-title"><span class="tag-mini">治理标签</span></div>
              <el-form-item label="密级" :class="{ dirty: dirty('secretLevel') }">
                <el-select v-model="form.secretLevel" style="width: 100%">
                  <el-option v-for="l in SECRET_ORDER" :key="l" :label="SECRET_LABEL[l]" :value="l" />
                </el-select>
                <div v-if="dirty('secretLevel')" class="diff-note">由「{{ beforeVal('secretLevel') }}」改为「{{ SECRET_LABEL[form.secretLevel] }}」</div>
                <div class="hint">密级下传到库内每篇文档，可单独提高但不能降低</div>
              </el-form-item>
              <el-form-item label="业务域" :class="{ dirty: dirty('businessDomain') }">
                <el-select v-model="form.businessDomain" style="width: 100%">
                  <el-option v-for="d in DOMAINS" :key="d" :label="d" :value="d" />
                </el-select>
                <div v-if="dirty('businessDomain')" class="diff-note">由「{{ beforeVal('businessDomain') }}」改为「{{ form.businessDomain }}」</div>
              </el-form-item>
              <el-form-item label="自定义标签" :class="{ dirty: dirty('customTags') }">
                <div class="tag-input-row">
                  <el-input v-model="customTagInput" placeholder="回车添加" @keyup.enter="addTag" />
                  <el-button @click="addTag">添加</el-button>
                </div>
                <div class="chip-row mt-8">
                  <el-tag v-for="t in form.customTags" :key="t" closable @close="removeTag(t)">{{ t }}</el-tag>
                  <span v-if="!form.customTags.length" class="hint">暂无自定义标签</span>
                </div>
              </el-form-item>
            </div>
            <div>
              <div class="field-title"><span class="tag-mini info">知识库可见角色</span></div>
              <el-form-item :class="{ dirty: dirty('visibleRoles') }">
                <el-checkbox-group v-model="form.visibleRoles">
                  <el-checkbox-button v-for="r in roleOptions" :key="r.code" :value="r.code">{{ r.name }}</el-checkbox-button>
                </el-checkbox-group>
                <div v-if="dirty('visibleRoles')" class="diff-note">由「{{ beforeVal('visibleRoles') }}」改为「{{ form.visibleRoles.join('、') }}」</div>
                <div class="hint">知识库级授权的初始值；额外授权用户请在「授权管理」中调整</div>
              </el-form-item>
              <el-alert type="info" :closable="false" show-icon class="mt-8">
                <template #title><span class="alert-text">标签与授权变更<b>不触发重建索引</b>，保存后立即生效</span></template>
              </el-alert>
            </div>
          </div>
        </el-tab-pane>

        <!-- ========== 危险操作 ========== -->
        <el-tab-pane name="danger">
          <template #label><span class="tab-label">危险操作</span></template>
          <div class="danger-list">
            <div class="danger-item">
              <div>
                <div class="di-title"><el-icon><Refresh /></el-icon>清空索引</div>
                <div class="hint">删除该知识库全部向量数据，文档保留但无法被检索，需重建才能恢复</div>
              </div>
              <el-button :icon="Refresh" @click="clearIndex">清空索引</el-button>
            </div>
            <div class="danger-item">
              <div>
                <div class="di-title"><el-icon><Collection /></el-icon>重建全部索引</div>
                <div class="hint">按当前配置重新切分与向量化全部文档</div>
              </div>
              <el-button :icon="TrendCharts" @click="openSaveConfirm">重建索引</el-button>
            </div>
            <div class="danger-item danger">
              <div>
                <div class="di-title"><el-icon><Delete /></el-icon>删除知识库</div>
                <div class="hint">将同时清理该库下全部文档与向量数据，操作不可恢复</div>
              </div>
              <el-button type="danger" :icon="Delete" @click="deleteKb">删除知识库</el-button>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>

      <!-- 底部常驻变更条 -->
      <div class="save-bar" :class="{ active: totalDirty > 0 }">
        <div class="sb-left">
          <el-icon v-if="totalDirty" class="sb-icon"><WarningFilled /></el-icon>
          <el-icon v-else class="sb-icon ok"><InfoFilled /></el-icon>
          <span v-if="totalDirty">
            有 <b>{{ totalDirty }}</b> 项修改未保存{{ needReindex ? '，保存后需重建索引' : '' }}
          </span>
          <span v-else>当前配置与服务端一致</span>
        </div>
        <div class="sb-actions">
          <el-button :disabled="!totalDirty" @click="discard">放弃修改</el-button>
          <el-button type="primary" :disabled="!totalDirty" :icon="Setting" @click="openSaveConfirm">
            {{ needReindex ? '保存并重建索引' : '保存修改' }}
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 变更影响确认弹窗 -->
    <el-dialog v-model="confirmVisible" title="确认配置变更" width="620px">
      <div class="confirm-block">
        <div class="cb-title">本次变更（{{ diffList.length }} 项）</div>
        <el-table :data="diffList" size="small" border max-height="200">
          <el-table-column prop="label" label="配置项" width="150" />
          <el-table-column prop="before" label="当前值" />
          <el-table-column prop="after" label="变更后">
            <template #default="{ row }"><b class="after-val">{{ row.after }}</b></template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="needReindex" class="confirm-block">
        <div class="cb-title">重建影响</div>
        <div v-loading="impactLoading" class="impact-grid">
          <div class="ig-row"><span>需重新切分与向量化的文档</span><b>{{ impact?.docCount ?? '—' }} 篇</b></div>
          <div class="ig-row"><span>预计分片数变化</span><b>{{ impact ? `${impact.chunkBefore.toLocaleString()} → 约 ${impact.chunkAfter.toLocaleString()} 个` : '—' }}</b></div>
          <div class="ig-row"><span>预估耗时</span><b>{{ impact ? `约 ${impact.estimateMinutes} 分钟` : '—' }}</b></div>
          <div class="ig-row"><span>预估 tokens 消耗</span><b>{{ impact ? `约 ${(impact.estimateTokens / 10000).toFixed(0)} 万` : '—' }}</b></div>
        </div>
      </div>

      <div v-if="needReindex" class="confirm-block">
        <div class="cb-title">执行时机</div>
        <el-radio-group v-model="rebuildMode" class="mode-group">
          <el-radio value="IMMEDIATE">立即重建（重建期间本库检索质量下降）</el-radio>
          <el-radio value="SCHEDULED">加入计划任务（今日 02:00 自动执行）<el-tag size="small" type="success" class="ml-6">推荐</el-tag></el-radio>
        </el-radio-group>
      </div>

      <template #footer>
        <el-button @click="confirmVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="confirmSave">确认保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.detail-page {
  height: 100%;
  overflow-y: auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
}
.head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.page-desc {
  margin: 0;
}
.head-actions {
  display: flex;
  gap: 10px;
  flex: none;
}
.mb-12 {
  margin-bottom: 12px;
}
.mb-16 {
  margin-bottom: 16px;
}
.mt-8 {
  margin-top: 8px;
}
.ml-6 {
  margin-left: 6px;
}
.alert-text {
  font-size: 12.5px;
  line-height: 1.6;
}
.form-locked {
  opacity: 0.7;
  pointer-events: none;
}
.tab-label {
  display: inline-flex;
  align-items: center;
}
.tab-badge {
  margin-left: 8px;
}
.tab-badge :deep(.el-badge__content) {
  border: none;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 20px;
}
.span-2 {
  grid-column: 1 / -1;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-top: 4px;
}
.lock-ico {
  color: var(--el-text-color-placeholder);
  vertical-align: -2px;
}
.req {
  color: var(--el-color-danger);
}
.row-inline {
  display: flex;
  align-items: center;
  gap: 6px;
}
.unit {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* dirty 标记 */
.dirty :deep(.el-input__wrapper),
.dirty :deep(.el-textarea__inner),
.dirty :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}
.diff-note {
  font-size: 12px;
  color: var(--el-color-warning);
  margin-top: 4px;
  line-height: 1.5;
}
.diff-note.inline {
  margin-left: 8px;
  display: inline;
}

/* 预设 */
.field-title {
  font-size: 13.5px;
  font-weight: 600;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 7px;
}
.preset-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 10px;
}
.preset-btn {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
  text-align: left;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  transition: all 0.16s;
}
.preset-btn.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}
.pb-label {
  font-size: 13px;
  font-weight: 600;
}
.pb-desc {
  font-size: 11.5px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}
.policy-box,
.table-box {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
}
.level-one {
  padding: 11px 13px;
  background: #f7fbff;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.level-two {
  padding: 4px 13px 10px;
}
.pl-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.pl-head b {
  font-size: 13px;
  font-weight: 500;
}
.locked {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-color-success);
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.tag-mini {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 4px;
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary-dark-2);
}
.tag-mini.info {
  background: var(--el-color-info-light-9);
  color: var(--el-color-info);
}
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 13px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.switch-row:last-child {
  border-bottom: none;
}
.switch-row.dirty {
  background: var(--el-color-primary-light-9);
}
.sr-title {
  font-size: 12.5px;
}
.ok-icon {
  color: var(--el-color-success);
}
.inline-fields {
  display: flex;
  gap: 16px;
  margin-top: 12px;
}
.inline-fields.three {
  gap: 12px;
}
.inline-item {
  margin-bottom: 0;
  flex: 1;
}
.readonly-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f7f9fc;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 9px 12px;
  color: var(--el-text-color-secondary);
  font-size: 12.5px;
}
.vl-ic {
  margin-right: 4px;
  color: #2b6cb0;
  vertical-align: -2px;
}
.tag-input-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

/* 危险操作 */
.danger-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.danger-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.danger-item.danger {
  border-color: #fde2e2;
  background: #fffafa;
}
.di-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13.5px;
  font-weight: 600;
  margin-bottom: 4px;
}
.danger-item.danger .di-title {
  color: var(--el-color-danger);
}

/* 底部变更条 */
.save-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 16px -20px -20px;
  padding: 14px 20px;
  border-top: 1px solid var(--el-border-color-lighter);
  background: #fff;
  border-radius: 0 0 8px 8px;
}
.save-bar.active {
  background: #fffbf5;
}
.sb-left {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.sb-icon {
  color: var(--el-color-warning);
}
.sb-icon.ok {
  color: var(--el-text-color-placeholder);
}
.sb-actions {
  display: flex;
  gap: 10px;
}

/* 弹窗 */
.confirm-block {
  margin-bottom: 18px;
}
.cb-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}
.after-val {
  color: var(--el-color-primary);
}
.impact-grid {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  background: #f7f9fc;
  border-radius: 8px;
  min-height: 60px;
}
.ig-row {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.ig-row b {
  color: var(--el-text-color-primary);
}
.mode-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
}
</style>
