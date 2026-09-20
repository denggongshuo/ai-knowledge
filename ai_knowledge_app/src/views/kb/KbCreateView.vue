<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  Lock,
  InfoFilled,
  Check,
  Plus,
  Cpu,
  Picture,
} from '@element-plus/icons-vue'
import { kbApi, sysApi } from '@/api'
import type { ConfigDiffItem, DeptNode, EmbeddingInfo, KbConfigForm, RetrievalMode, SecretLevel, SplitPresetDef } from '@/types'

const router = useRouter()

// ---------------- 展示字典（前端 UI 文案） ----------------
const SECRET_LABEL: Record<SecretLevel, string> = { PUBLIC: '公开', INTERNAL: '内部', CONFIDENTIAL: '机密', SECRET: '绝密' }
const RETRIEVAL_LABEL: Record<RetrievalMode, string> = { VECTOR: '向量检索', HYBRID: '混合检索', KEYWORD: '关键词检索' }
const DOMAINS = ['研发 / 平台', '研发 / 应用', '销售 / 售前', '人力资源', '财务 / 税务', '运维 / 安全', '培训 / 赋能']
const PRESET_DESC: Record<string, string> = {
  GENERAL: '结构一般、内容混合的常规资料',
  STRUCTURED: '标题层级清晰的规范 / 手册 / API 文档',
  CONTRACT: '条款编号明确、需按条切分',
  TABLE_HEAVY: '报价单、参数表为主的资料',
  QA_PAIR: 'FAQ、问答知识库',
}
const SECRET_ORDER: SecretLevel[] = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET']

/** 默认配置（同时作为「确认创建」步骤的 diff 基准） */
function baseConfig(): KbConfigForm {
  return {
    name: '',
    code: 'kb_',
    deptId: 1,
    visibleRoles: ['SUPER_ADMIN'],
    secretLevel: 'INTERNAL',
    businessDomain: '研发 / 平台',
    description: '',
    customTags: [],
    splitPolicy: { preset: 'GENERAL', chunkSize: 512, overlap: 64, recursiveSplit: true, semanticSplit: false },
    tablePolicy: { atomicTable: true, dualRepresentation: true, repeatHeader: true, mergeCrossPage: true, ocrTable: true, rowLimit: 30, rowWindow: 5 },
    retrievalConfig: { mode: 'HYBRID', recallThreshold: 0.15, rerankTopK: 5, rerankEnabled: true, tableWeight: 1.2, identifierProtect: true },
  }
}

// ---------------- 状态 ----------------
const step = ref(0)
const loading = ref(false)
const submitting = ref(false)
const form = ref<KbConfigForm>(baseConfig())
const presets = ref<SplitPresetDef[]>([])
/**
 * 嵌入模型信息（`#225` · `T-O16`）—— 初值 `null`，**不写死一份猜的默认值**。
 * 页面在 `/kb/presets` 回来之前、以及它失败的时候显示 `—`；
 * 写死的话那三个值和真值长得一模一样，看不出"这是猜的"（`#85` 那一族）。
 */
const embedding = ref<EmbeddingInfo | null>(null)

/** 详情行的完整文本；`—` = 还没有这个值（不猜）。 */
const embeddingText = computed(() => {
  const e = embedding.value
  return e ? `${e.model} · ${e.dimension} 维 · ${e.norm}` : '—'
})
/** 配置摘要里的短文本（不带归一化口径）。 */
const embeddingShort = computed(() => {
  const e = embedding.value
  return e ? `${e.model} · ${e.dimension} 维` : '—'
})
const vlModel = ref({ name: '—', enabled: false, appliedAt: '' })
const deptTree = ref<DeptNode[]>([])
const roleOptions = ref<{ code: string; name: string }[]>([])
const customTagInput = ref('')

const STEPS = ['基本信息', '解析与分片', '标签与检索', '确认创建']

onMounted(async () => {
  loading.value = true
  try {
    const [p, t, r] = await Promise.all([kbApi.presets(), sysApi.deptTree(), sysApi.roles({})])
    presets.value = p.presets
    embedding.value = p.embedding
    vlModel.value = p.vlModel ?? vlModel.value
    deptTree.value = (t as unknown as DeptNode[]) ?? []
    roleOptions.value = ((r.list as unknown as { code: string; name: string }[]) ?? []).map((x) => ({ code: x.code, name: x.name }))
    // 用服务端默认配置初始化（保留已填的名称/编码）
    const keep = { name: form.value.name, code: form.value.code }
    form.value = { ...p.defaults, ...keep }
  } catch {
    ElMessage.warning('配置预设加载失败，已使用本地默认值')
  } finally {
    loading.value = false
  }
})

// ---------------- 预设联动 ----------------
function applyPreset(id: string) {
  const p = presets.value.find((x) => x.id === id)
  if (!p) {
    form.value.splitPolicy.preset = 'CUSTOM'
    return
  }
  form.value.splitPolicy = {
    preset: p.id,
    chunkSize: p.chunkSize,
    overlap: p.overlap,
    recursiveSplit: true,
    semanticSplit: p.semanticSplit,
  }
  form.value.tablePolicy.atomicTable = p.tableAtomic
}

/** 手改单项参数 → 预设标记为「自定义」 */
function markCustom() {
  if (form.value.splitPolicy.preset !== 'CUSTOM') form.value.splitPolicy.preset = 'CUSTOM'
}

const currentPresetLabel = computed(() => {
  if (form.value.splitPolicy.preset === 'CUSTOM') return '自定义'
  return presets.value.find((p) => p.id === form.value.splitPolicy.preset)?.label ?? '通用文档'
})

// ---------------- 自定义标签 ----------------
function addTag() {
  const v = customTagInput.value.trim()
  if (!v) return
  if (form.value.customTags.includes(v)) {
    ElMessage.warning('标签已存在')
    return
  }
  form.value.customTags.push(v)
  customTagInput.value = ''
}
function removeTag(t: string) {
  form.value.customTags = form.value.customTags.filter((x) => x !== t)
}

// ---------------- 校验 ----------------
function validateStep0(): boolean {
  if (!form.value.name.trim()) return fail('请填写知识库名称')
  if (form.value.name.trim().length < 2 || form.value.name.trim().length > 40) return fail('知识库名称需 2-40 字')
  if (!/^kb_[a-z0-9_]{2,}$/.test(form.value.code.trim())) return fail('知识库编码需以 kb_ 开头，仅小写字母、数字与下划线')
  if (!form.value.deptId) return fail('请选择归属部门')
  if (!form.value.visibleRoles.length) return fail('请至少选择一个可见角色')
  if (!form.value.businessDomain) return fail('请选择业务域')
  return true
}
function fail(msg: string) {
  ElMessage.warning(msg)
  return false
}

function next() {
  if (step.value === 0 && !validateStep0()) return
  if (step.value < STEPS.length - 1) step.value++
}
function prev() {
  if (step.value > 0) step.value--
}

// ---------------- 第 4 步：非默认项汇总 ----------------
const diffs = computed<ConfigDiffItem[]>(() => {
  const b = baseConfig()
  const f = form.value
  const out: ConfigDiffItem[] = []
  const push = (label: string, before: string | number, after: string | number) => {
    if (String(before) !== String(after)) out.push({ label, before: String(before), after: String(after) })
  }
  push('密级', SECRET_LABEL[b.secretLevel], SECRET_LABEL[f.secretLevel])
  push('业务域', b.businessDomain, f.businessDomain)
  push('归属部门', deptName(b.deptId), deptName(f.deptId))
  push('可见角色', b.visibleRoles.join('、'), f.visibleRoles.join('、'))
  push('分片策略预设', '通用文档', currentPresetLabel.value)
  push('chunkSize', b.splitPolicy.chunkSize, f.splitPolicy.chunkSize)
  push('overlap', b.splitPolicy.overlap, f.splitPolicy.overlap)
  push('语义切分', b.splitPolicy.semanticSplit ? '开启' : '关闭', f.splitPolicy.semanticSplit ? '开启' : '关闭')
  push('双表示', b.tablePolicy.dualRepresentation ? '开' : '关', f.tablePolicy.dualRepresentation ? '开' : '关')
  push('扫描件表格识别', b.tablePolicy.ocrTable ? '开' : '关', f.tablePolicy.ocrTable ? '开' : '关')
  push('回表行数上限', `${b.tablePolicy.rowLimit} 行`, `${f.tablePolicy.rowLimit} 行`)
  push('检索方式', RETRIEVAL_LABEL[b.retrievalConfig.mode], RETRIEVAL_LABEL[f.retrievalConfig.mode])
  push('召回阈值', b.retrievalConfig.recallThreshold, f.retrievalConfig.recallThreshold)
  push('Rerank 保留数', b.retrievalConfig.rerankTopK, f.retrievalConfig.rerankTopK)
  push('Rerank 开关', b.retrievalConfig.rerankEnabled ? '开' : '关', f.retrievalConfig.rerankEnabled ? '开' : '关')
  push('表格 Rerank 加权', b.retrievalConfig.tableWeight, f.retrievalConfig.tableWeight)
  push('标识符保护', b.retrievalConfig.identifierProtect ? '开' : '关', f.retrievalConfig.identifierProtect ? '开' : '关')
  if (f.customTags.length) out.push({ label: '自定义标签', before: '—', after: f.customTags.join('、') })
  return out
})

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

// ---------------- 提交 ----------------
async function submit() {
  submitting.value = true
  try {
    const res = await kbApi.create(form.value)
    ElMessage.success(`知识库「${res.name}」创建成功，即将跳转文档上传`)
    // 创建成功后跳转到该知识库的「文档上传」页，引导下一步
    router.push(`/kb/${res.id}/upload`)
  } catch {
    ElMessage.error('创建失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

function goBack() {
  router.push('/kb')
}
</script>

<template>
  <div v-loading="loading" class="page create-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="head-left">
        <el-button text :icon="ArrowLeft" @click="goBack">返回列表</el-button>
        <div>
          <h2 class="page-title">创建知识库</h2>
          <p class="page-desc">指定分片策略与检索方式，上传文档后自动构建索引</p>
        </div>
      </div>
    </div>

    <!-- 步骤条 -->
    <el-card shadow="never" class="wizard-card">
      <el-steps :active="step" finish-status="success" align-center class="wizard-steps">
        <el-step v-for="s in STEPS" :key="s" :title="s" />
      </el-steps>

      <div class="wizard-body">
        <!-- ============ 步骤 ① 基本信息 ============ -->
        <div v-show="step === 0" class="pane">
          <el-form label-position="top" class="form-grid">
            <el-form-item label="知识库名称" required>
              <el-input v-model="form.name" maxlength="40" show-word-limit placeholder="2-40 字，如：产品技术文档库" />
            </el-form-item>
            <el-form-item required>
              <template #label>
                知识库编码
                <el-tooltip content="创建后不可修改" placement="top">
                  <el-icon class="label-icon"><Lock /></el-icon>
                </el-tooltip>
              </template>
              <el-input v-model="form.code" placeholder="kb_prod_doc" />
              <div class="hint">kb_ 前缀 + 业务语义，创建后不可修改</div>
            </el-form-item>

            <el-form-item label="归属部门" required>
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
                placeholder="选择归属部门（决定可见范围基准）"
                style="width: 100%"
              />
              <div class="hint">归属部门是可见范围的基准，与角色数据范围叠加</div>
            </el-form-item>
            <el-form-item label="业务域" required>
              <el-select v-model="form.businessDomain" placeholder="选择业务域" style="width: 100%">
                <el-option v-for="d in DOMAINS" :key="d" :label="d" :value="d" />
              </el-select>
            </el-form-item>

            <el-form-item label="密级" required>
              <el-radio-group v-model="form.secretLevel">
                <el-radio-button v-for="l in SECRET_ORDER" :key="l" :value="l">{{ SECRET_LABEL[l] }}</el-radio-button>
              </el-radio-group>
              <div class="hint">下传到库内每篇文档，可单独提高但不能降低</div>
            </el-form-item>

            <el-form-item class="span-2" label="可见角色" required>
              <el-checkbox-group v-model="form.visibleRoles">
                <el-checkbox-button v-for="r in roleOptions" :key="r.code" :value="r.code">{{ r.name }}</el-checkbox-button>
              </el-checkbox-group>
              <div class="hint">知识库级授权的初始值，默认勾选超级管理员</div>
            </el-form-item>

            <el-form-item class="span-2" label="描述">
              <el-input v-model="form.description" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="≤ 200 字，一句话说明这个库收录什么" />
            </el-form-item>
          </el-form>
        </div>

        <!-- ============ 步骤 ② 解析与分片 ============ -->
        <div v-show="step === 1" class="pane">
          <div class="field-block">
            <div class="field-title">分片策略预设 <span class="req">*</span></div>
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
            <div class="hint">
              选预设会自动带出下方全部参数，仍可在高级面板逐项覆盖；当前：<b>{{ currentPresetLabel }}</b>
            </div>
          </div>

          <div class="two-col">
            <!-- 左：分片策略 -->
            <div>
              <div class="field-title">分片策略</div>
              <div class="policy-box">
                <div class="policy-level level-one">
                  <div class="pl-head">
                    <span class="tag-mini">第一级</span>
                    <b>结构边界</b>
                    <span class="locked"><el-icon><Lock /></el-icon> 锁定</span>
                  </div>
                  <div class="chip-row">
                    <el-tag size="small" type="info">标题层级</el-tag>
                    <el-tag size="small" type="info">表格整表</el-tag>
                    <el-tag size="small" type="info">代码块</el-tag>
                    <el-tag size="small" type="info">完整列表</el-tag>
                  </div>
                  <div class="hint">检测到即封存，任何情况下不会被切开</div>
                </div>

                <div class="policy-level">
                  <div class="pl-head">
                    <span class="tag-mini info">第二级</span>
                    <b>超长兜底</b>
                  </div>
                  <div class="hint mb">仅当结构单元 &gt; chunkSize 时触发</div>

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

                  <el-alert type="warning" :closable="false" show-icon class="mt-8">
                    <template #title>
                      <span class="alert-text">成本高、断点不可复现（同文档两次索引结果不一致），仅在结构差的文档上开</span>
                    </template>
                  </el-alert>
                </div>
              </div>

              <div class="inline-fields">
                <el-form-item label="chunkSize" class="inline-item">
                  <el-input-number v-model="form.splitPolicy.chunkSize" :min="128" :max="2048" :step="64" controls-position="right" @change="markCustom" />
                  <span class="unit">tokens</span>
                </el-form-item>
                <el-form-item label="overlap" class="inline-item">
                  <el-input-number v-model="form.splitPolicy.overlap" :min="0" :max="512" :step="16" controls-position="right" @change="markCustom" />
                  <span class="unit">tokens</span>
                </el-form-item>
              </div>
            </div>

            <!-- 右：表格处理 -->
            <div>
              <div class="field-title">表格处理</div>
              <div class="table-box">
                <div class="switch-row">
                  <div>
                    <div class="sr-title">整表原子化</div>
                    <div class="hint">表格不参与切分</div>
                  </div>
                  <div class="row-inline">
                    <el-icon class="ok-icon"><Lock /></el-icon>
                    <el-switch :model-value="true" disabled />
                  </div>
                </div>
                <div class="switch-row">
                  <div>
                    <div class="sr-title">双表示</div>
                    <div class="hint">Markdown 给大模型，行式文本给向量</div>
                  </div>
                  <el-switch v-model="form.tablePolicy.dualRepresentation" />
                </div>
                <div class="switch-row">
                  <div>
                    <div class="sr-title">表头重复</div>
                    <div class="hint">拆表时每块带表头与表标题</div>
                  </div>
                  <el-switch v-model="form.tablePolicy.repeatHeader" />
                </div>
                <div class="switch-row">
                  <div>
                    <div class="sr-title">跨页表格合并</div>
                    <div class="hint">列数一致时并回一张表</div>
                  </div>
                  <el-switch v-model="form.tablePolicy.mergeCrossPage" />
                </div>
                <div class="switch-row">
                  <div>
                    <div class="sr-title">扫描件表格识别</div>
                    <div class="hint">失败会标红，不静默丢弃</div>
                  </div>
                  <el-switch v-model="form.tablePolicy.ocrTable" />
                </div>
                <div class="switch-row">
                  <div>
                    <div class="sr-title">回表行数上限</div>
                    <div class="hint">超出则取命中行窗口</div>
                  </div>
                  <div class="row-inline">
                    <el-input-number v-model="form.tablePolicy.rowLimit" :min="5" :max="200" size="small" controls-position="right" style="width: 92px" />
                    <span class="unit">行 ±</span>
                    <el-input-number v-model="form.tablePolicy.rowWindow" :min="0" :max="50" size="small" controls-position="right" style="width: 86px" />
                  </div>
                </div>
              </div>

              <div class="field-block mt-16">
                <div class="field-title">嵌入模型</div>
                <div class="readonly-box">
                  <span class="mono">{{ embeddingText }}</span>
                  <el-icon><Lock /></el-icon>
                </div>
                <div class="hint">由服务端统一配置，前端只读；更换需全库重建索引</div>
              </div>

              <div class="field-block mt-16">
                <div class="field-title">VL 视觉模型</div>
                <div class="readonly-box">
                  <span class="mono">
                    <el-icon class="vl-ic"><Picture /></el-icon>
                    {{ vlModel.enabled ? vlModel.name : '未配置' }} · 全局唯一
                  </span>
                  <el-icon><Lock /></el-icon>
                </div>
                <div class="hint">含图片的文档会由 VL 模型转成文字描述后参与检索；模型由服务端统一配置，前端只读</div>
              </div>
            </div>
          </div>

          <div class="cost-note">
            <el-icon><InfoFilled /></el-icon>
            预计索引成本：约 2.5 万 tokens / 篇（文档级摘要 + HyQ + 嵌入）
          </div>
        </div>

        <!-- ============ 步骤 ③ 标签与检索 ============ -->
        <div v-show="step === 2" class="pane">
          <div class="two-col">
            <div>
              <div class="field-title"><span class="tag-mini">人工填写</span> 治理标签</div>
              <div class="tag-box">
                <el-form-item label="密级" required class="inline-item">
                  <el-select v-model="form.secretLevel" style="width: 100%">
                    <el-option v-for="l in SECRET_ORDER" :key="l" :label="SECRET_LABEL[l]" :value="l" />
                  </el-select>
                </el-form-item>
                <el-form-item label="业务域" required class="inline-item">
                  <el-select v-model="form.businessDomain" style="width: 100%">
                    <el-option v-for="d in DOMAINS" :key="d" :label="d" :value="d" />
                  </el-select>
                </el-form-item>

                <el-form-item label="自定义标签" class="inline-item">
                  <div class="tag-input-row">
                    <el-input v-model="customTagInput" placeholder="回车添加" @keyup.enter="addTag">
                      <template #prefix><el-icon><Plus /></el-icon></template>
                    </el-input>
                    <el-button :icon="Plus" @click="addTag">添加</el-button>
                  </div>
                  <div class="chip-row mt-8">
                    <el-tag v-for="t in form.customTags" :key="t" closable @close="removeTag(t)">{{ t }}</el-tag>
                    <span v-if="!form.customTags.length" class="hint">如「2026 年度」「技术白皮书」</span>
                  </div>
                </el-form-item>

                <el-alert type="info" :closable="false" show-icon>
                  <template #title><span class="alert-text">密级下传到库内每篇文档，可单独提高但不能降低</span></template>
                </el-alert>
              </div>
            </div>

            <div>
              <div class="field-title"><span class="tag-mini info">自动生成</span> 内容标签</div>
              <div class="auto-box">
                <div class="chip-row">
                  <el-tag type="info" class="dim">解析后填充</el-tag>
                  <el-tag type="info" class="dim">—</el-tag>
                  <el-tag type="info" class="dim">—</el-tag>
                </div>
                <div class="hint mt-12">
                  解析完成时由大模型抽取 3~5 个主题词，挂在<b>文档级</b>而非知识库级。<br /><br />
                  <b>复用「文档级摘要」的同一次调用，不额外产生费用</b>，可在文档详情页人工修正。
                </div>
              </div>
            </div>
          </div>

          <div class="field-title mt-16"><span class="tag-mini info">系统默认</span> 检索配置</div>
          <div class="two-col">
            <div>
              <el-form-item label="检索方式" class="inline-item">
                <el-radio-group v-model="form.retrievalConfig.mode">
                  <el-radio-button value="VECTOR">向量检索</el-radio-button>
                  <el-radio-button value="HYBRID">混合检索</el-radio-button>
                  <el-radio-button value="KEYWORD">关键词检索</el-radio-button>
                </el-radio-group>
                <div class="hint">混合检索 = 向量 + 关键词双通道；型号类精确查询依赖关键词通道</div>
              </el-form-item>

              <div class="inline-fields three">
                <el-form-item label="Rerank 保留数" class="inline-item">
                  <el-input-number v-model="form.retrievalConfig.rerankTopK" :min="3" :max="15" size="small" controls-position="right" />
                </el-form-item>
                <el-form-item label="召回阈值" class="inline-item">
                  <el-input-number v-model="form.retrievalConfig.recallThreshold" :min="0" :max="1" :step="0.05" :precision="2" size="small" controls-position="right" />
                </el-form-item>
                <el-form-item label="Rerank" class="inline-item">
                  <el-switch v-model="form.retrievalConfig.rerankEnabled" />
                </el-form-item>
              </div>
            </div>

            <div>
              <div class="table-retrieval">
                <div class="tr-head">
                  <b>表格检索</b>
                  <el-tag size="small" type="info">不单开召回通道</el-tag>
                </div>
                <div class="switch-row">
                  <div class="sr-title">Rerank 加权</div>
                  <div class="row-inline">
                    <el-input-number v-model="form.retrievalConfig.tableWeight" :min="1" :max="3" :step="0.1" :precision="1" size="small" controls-position="right" style="width: 92px" />
                    <el-switch :model-value="true" disabled />
                  </div>
                </div>
                <div class="hint">查参数、查价格时避免被正文挤掉</div>
                <div class="switch-row mt-8 top-line">
                  <div class="sr-title">标识符保护</div>
                  <el-switch v-model="form.retrievalConfig.identifierProtect" />
                </div>
                <div class="hint">型号 / 金额 / 日期整体成 token，避免 A-100 被切成 A / 100 而误命中 A-1000</div>
              </div>
            </div>
          </div>
        </div>

        <!-- ============ 步骤 ④ 确认创建 ============ -->
        <div v-show="step === 3" class="pane">
          <div class="summary-head">
            <el-icon class="sum-icon"><Check /></el-icon>
            <div>
              <div class="sum-title">确认创建「{{ form.name || '未命名知识库' }}」</div>
              <div class="hint">以下仅列出与默认值不同的项，便于核对本次改动</div>
            </div>
          </div>

          <el-table v-if="diffs.length" :data="diffs" border size="small" class="diff-table">
            <el-table-column prop="label" label="配置项" width="180" />
            <el-table-column prop="before" label="默认值" />
            <el-table-column prop="after" label="本次设置">
              <template #default="{ row }"><b class="after-val">{{ row.after }}</b></template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="全部为默认配置，可直接创建" :image-size="80" />

          <div class="confirm-info">
            <div class="ci-row"><span>知识库编码</span><b class="mono">{{ form.code }}</b></div>
            <div class="ci-row"><span>归属部门</span><b>{{ deptName(form.deptId) }}</b></div>
            <div class="ci-row"><span>嵌入模型</span><b class="mono">{{ embeddingShort }}</b></div>
            <div class="ci-row"><span>VL 视觉模型</span><b class="mono">{{ vlModel.enabled ? vlModel.name : '未配置' }}</b></div>
            <div class="ci-row"><span>预计索引成本</span><b>约 2.5 万 tokens / 篇</b></div>
          </div>

          <el-alert type="success" :closable="false" show-icon>
            <template #title>
              <span class="alert-text">创建成功后将跳转到该知识库的「文档上传」页，上传文档即自动构建索引</span>
            </template>
          </el-alert>
        </div>
      </div>

      <!-- 底部操作条 -->
      <div class="wizard-foot">
        <span class="foot-note">
          <el-icon><Cpu /></el-icon>
          嵌入模型由服务端统一配置，前端全程只读
        </span>
        <div class="foot-actions">
          <el-button v-if="step > 0" @click="prev">上一步</el-button>
          <el-button v-if="step < STEPS.length - 1" type="primary" @click="next">下一步</el-button>
          <el-button v-else type="primary" :loading="submitting" @click="submit">确认创建</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.create-page {
  height: 100%;
  overflow-y: auto;
}
.page-head {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}
.head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.head-left .page-desc {
  margin: 0;
}
.wizard-card :deep(.el-card__body) {
  padding: 20px 24px 0;
}
.wizard-steps {
  margin-bottom: 8px;
}
.wizard-body {
  min-height: 420px;
  padding: 12px 0;
}
.pane {
  animation: fade-in 0.24s ease;
}
@keyframes fade-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
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
.hint.mb {
  margin: 0 0 8px;
}
.hint.mt-12 {
  margin-top: 12px;
}
.label-icon {
  margin-left: 4px;
  color: var(--el-text-color-placeholder);
  vertical-align: -2px;
}
.req {
  color: var(--el-color-danger);
}
.mt-8 {
  margin-top: 8px;
}
.mt-16 {
  margin-top: 16px;
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

/* 预设 */
.field-block {
  margin-bottom: 18px;
}
.field-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--el-text-color-primary);
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
.preset-btn:hover {
  border-color: var(--el-color-primary-light-5);
}
.preset-btn.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}
.pb-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.pb-desc {
  font-size: 11.5px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}

/* 两级策略 */
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}
.policy-box,
.table-box,
.tag-box,
.auto-box,
.table-retrieval {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
}
.policy-level {
  padding: 11px 13px;
}
.level-one {
  background: #f7fbff;
  border-bottom: 1px solid var(--el-border-color-lighter);
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
  white-space: nowrap;
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
  padding: 9px 0;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.switch-row:first-child {
  border-top: none;
}
.top-line {
  border-top: 1px dashed var(--el-border-color-lighter);
}
.sr-title {
  font-size: 12.5px;
  color: var(--el-text-color-primary);
}
.ok-icon {
  color: var(--el-color-success);
}
.alert-text {
  font-size: 12px;
  line-height: 1.6;
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
.cost-note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 16px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 标签与检索 */
.tag-box {
  padding: 12px 13px;
}
.tag-input-row {
  display: flex;
  gap: 8px;
}
.auto-box {
  padding: 12px 13px;
  border-style: dashed;
  background: #fafcff;
}
.dim {
  opacity: 0.4;
}
.table-retrieval {
  padding: 12px 13px;
}
.tr-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
}
.tr-head b {
  font-size: 13px;
  font-weight: 500;
}

/* 确认创建 */
.summary-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.sum-icon {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}
.sum-title {
  font-size: 15px;
  font-weight: 600;
}
.diff-table {
  margin-bottom: 16px;
}
.after-val {
  color: var(--el-color-primary);
}
.confirm-info {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 24px;
  padding: 14px 16px;
  background: #f7f9fc;
  border-radius: 8px;
  margin-bottom: 16px;
}
.ci-row {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.ci-row b {
  color: var(--el-text-color-primary);
}

/* 底部条 */
.wizard-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 14px 0;
}
.foot-note {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.foot-actions {
  display: flex;
  gap: 10px;
}
</style>
