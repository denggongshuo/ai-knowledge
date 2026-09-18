<script setup lang="ts">
import { ref, computed, nextTick, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Promotion, Delete, FolderOpened, Timer, Connection, Picture, PictureFilled, InfoFilled, View, DocumentCopy, CaretTop, CaretBottom } from '@element-plus/icons-vue'
import { chatStream, chatApi, sysApi } from '@/api'
import { FEEDBACK_REASONS } from '@/types'
import type { ChatAttachment, ChatMessage, ChatSessionItem, Citation, ModelOption, RetrievalTrace } from '@/types'

const WELCOME: ChatMessage = {
  id: 'welcome',
  role: 'assistant',
  content:
    '你好，我是 AI 知识助手 👋\n\n我可以基于企业内部知识库回答你关于产品、技术、制度等方面的问题。\n\n可以试试问：\n- 「系统的分片方案是怎么设计的？」\n- 「检索链路的延迟大概多少？」\n- 「知识图谱怎么做多跳推理？」\n- 「权限模型是怎么分层求交的？」\n\n也可以直接粘贴或上传图片提问（如报表截图、架构图），我会先理解图片再检索。',
  createdAt: Date.now(),
}

/** 消息列表（含欢迎语） */
const messages = ref<ChatMessage[]>([{ ...WELCOME }])
const input = ref('')
const sending = ref(false)
const listRef = ref()

// ---------------- 对话模型切换（V2） ----------------
const models = ref<ModelOption[]>([])
const selectedModelId = ref<number | null>(null)
const currentModel = computed(() => models.value.find((m) => m.id === selectedModelId.value) ?? null)

async function loadModels() {
  const { list } = await sysApi.modelOptions('CHAT')
  models.value = list
  selectedModelId.value = list.find((m) => m.isActive)?.id ?? list[0]?.id ?? null
}

/** 本轮预估上下文（system prompt + top-k 分片 + 摘要 + 图片描述 ≈ 3~5K） */
const BASE_CONTEXT_TOKENS = 4200
function estimateContextTokens() {
  const history = messages.value.reduce((s, m) => s + Math.ceil(m.content.length / 1.6), 0)
  return BASE_CONTEXT_TOKENS + history
}

/** 切换模型：会话与模型绑定 → 开启新会话（原会话保留） */
async function onModelChange(nextId: number) {
  const next = models.value.find((m) => m.id === nextId)
  if (!next) return
  if (next.capabilities.contextWindow < estimateContextTokens()) {
    try {
      await ElMessageBox.confirm(
        `当前上下文约 ${estimateContextTokens().toLocaleString()} tokens，新模型「${next.displayName}」窗口为 ${(next.capabilities.contextWindow / 1000).toFixed(0)}K，可能顶到上限。请选择处理方式。`,
        '上下文窗口校验',
        { type: 'warning', confirmButtonText: '开新会话', cancelButtonText: '截断历史（不推荐）', distinguishCancelAndClose: true },
      )
      newSession(next)
    } catch (action) {
      if (action === 'cancel') {
        // 保留本轮，仅截断历史（保留欢迎语与最近 2 轮）
        messages.value = [messages.value[0]!, ...messages.value.slice(-4)]
        ElMessage.success('已截断历史，继续使用当前会话')
      }
    }
    return
  }
  newSession(next)
}

function newSession(next?: ModelOption) {
  messages.value = [{ ...WELCOME }]
  activeSession.value = ''
  if (next) {
    ElMessage.success(`已切换到「${next.displayName}」，原对话已保留在左侧列表`)
  }
}

function onNewChat() {
  messages.value = [{ ...WELCOME }]
  activeSession.value = ''
}

// ---------------- 图片附件（V2） ----------------
const attachments = ref<ChatAttachment[]>([])
const fileInput = ref<HTMLInputElement>()
const MAX_IMAGES = 3
const MAX_SIZE = 10 * 1024 * 1024

/**
 * 待发送附件的**原始字节**，按本地 id 存。
 *
 * <p>为什么不放进 `ChatAttachment`：那个对象是响应式的（驱动缩略图与进度条），
 * 把 `File` 塞进去会让 Vue 去代理它的全部属性；而且它**上传完就该被释放** ——
 * 留在数组里等于每张图多占一份内存，直到用户离开页面。
 */
const pendingFiles = new Map<string, File>()

function pickFiles() {
  fileInput.value?.click()
}

function onFilePicked(e: Event) {
  const files = Array.from((e.target as HTMLInputElement).files ?? [])
  void addFiles(files)
  ;(e.target as HTMLInputElement).value = ''
}

/** 粘贴图片（Ctrl+V 直接贴图） */
function onPaste(e: ClipboardEvent) {
  const files = Array.from(e.clipboardData?.files ?? []).filter((f) => f.type.startsWith('image/'))
  if (files.length) {
    e.preventDefault()
    void addFiles(files)
  }
}

async function addFiles(files: File[]) {
  const imgs = files.filter((f) => f.type.startsWith('image/'))
  if (!imgs.length) return
  if (attachments.value.length + imgs.length > MAX_IMAGES) {
    ElMessage.warning(`单次最多 ${MAX_IMAGES} 张图片`)
    return
  }
  for (const f of imgs) {
    if (f.size > MAX_SIZE) {
      ElMessage.warning(`${f.name} 超过 10MB，已跳过`)
      continue
    }
    const att: ChatAttachment = {
      id: `local_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
      type: 'IMAGE',
      name: f.name,
      size: f.size,
      // 空串 = 「还不知道」。⚠️ 不在这里自己拼一个伪 md5：服务端算完会回填（见 uploadOne）——
      // 它将来是 VL 描述缓存的 key 前半段（需求 `09` §4.8），伪值会让两张不同的图撞进同一项
      md5: '',
      mime: f.type,
      url: URL.createObjectURL(f),
      stage: 'UPLOADING',
      progress: 0,
    }
    pendingFiles.set(att.id, f)
    attachments.value.push(att)
    void uploadOne(att)
  }
}

/**
 * 预上传：只落存储 + 落库，不调 VL（描述在后端内部完成）。
 *
 * <p>🔴 **必须改"数组里那一个"，不能改传进来的 `att`**：`attachments` 是
 * `ref<ChatAttachment[]>`，`push(att)` 进去的是原始对象，而**读回来的是它的响应式代理** ——
 * 改原始对象**不会触发重渲染**。表现是缩略图永远停在「上传中 0%」：
 * 上传其实成功了、库里也有行，而界面上看不出任何变化。
 *
 * <p>本文件里 `runStream` 的 `const current = () => messages.value.find(...)` 就是这条正解，
 * 本处此前与它不一致 —— 而这条路径**从来没有被端到端跑过**（`T-805` 之前是 404），
 * 所以它一直没被发现。`S5-M08-C` 加页面级用例时才露出来。
 */
async function uploadOne(att: ChatAttachment) {
  const localId = att.id
  const file = pendingFiles.get(localId)
  // 取数组里的**响应式代理**再改；取不到（已经被移除）就退回原对象，改了也无害
  const live = attachments.value.find((a) => a.id === localId) ?? att
  if (!file) {
    live.stage = 'FAILED'
    return
  }
  const form = new FormData()
  form.append('file', file)
  try {
    // 进度来自 XHR 的 upload.onprogress（`http.upload` 的第三参）——
    // 此前是 `http.post` 拿不到进度，只好用一个每 80ms 自增 12% 的定时器假装
    const res = await chatApi.uploadAttachment(form, (percent) => {
      live.progress = percent
    })
    live.progress = 100
    live.id = res.attachmentId
    // md5 / mime 以**服务端算的**为准：前端送出去的那两个是客户端说了算的
    live.md5 = res.md5
    live.mime = res.mime
    live.url = live.url ?? res.presignedUrl
    pendingFiles.delete(localId)
  } catch {
    live.stage = 'FAILED'
    ElMessage.error(`${att.name} 上传失败，请重试`)
  }
}

function removeAttachment(id: string) {
  attachments.value = attachments.value.filter((a) => a.id !== id)
  pendingFiles.delete(id)
}

function formatSize(n: number) {
  return n > 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.round(n / 1024)} KB`
}

// ---------------- 发送 / 流式 ----------------
async function scrollToBottom() {
  await nextTick()
  listRef.value?.scrollTo({ top: listRef.value.scrollHeight, behavior: 'smooth' })
}

function send() {
  const text = input.value.trim()
  const ready = attachments.value.filter((a) => a.stage !== 'FAILED' && (a.progress ?? 0) >= 100)
  if ((!text && !ready.length) || sending.value) return
  if (attachments.value.some((a) => a.stage === 'UPLOADING')) {
    ElMessage.warning('图片仍在上传，请稍候')
    return
  }
  input.value = ''

  const atts = ready.map((a) => ({ ...a }))
  const userMsg: ChatMessage = {
    id: `u_${Date.now()}`,
    role: 'user',
    content: text || '（仅图片）',
    createdAt: Date.now(),
    attachments: atts,
  }
  const aiMsg: ChatMessage = {
    id: `a_${Date.now()}`,
    role: 'assistant',
    content: '',
    streaming: true,
    citations: [],
    createdAt: Date.now(),
    modelId: selectedModelId.value ?? undefined,
    modelName: currentModel.value?.displayName,
    attachments: atts.map((a) => ({ ...a, stage: undefined })),
  }
  messages.value.push(userMsg, aiMsg)
  attachments.value = []
  sending.value = true
  void scrollToBottom()

  runStream(userMsg.content, atts.map((a) => a.id), false, aiMsg.id)
}

/** 「直接读原图」兜底：仅当对话模型 vision=true 时可用，且只对该轮生效 */
function canReadOriginal(m: ChatMessage) {
  return m.role === 'assistant' && !!m.attachments?.length && currentModel.value?.capabilities.vision === true
}

function readOriginal(aiMsg: ChatMessage) {
  if (!canReadOriginal(aiMsg)) return
  const idx = messages.value.findIndex((m) => m.id === aiMsg.id)
  const u = [...messages.value.slice(0, idx)].reverse().find((m) => m.role === 'user')
  if (!u) return
  const atts = (u.attachments ?? []).map((a) => ({ ...a }))
  const ai: ChatMessage = {
    id: `a_${Date.now()}`,
    role: 'assistant',
    content: '',
    streaming: true,
    citations: [],
    createdAt: Date.now(),
    modelId: selectedModelId.value ?? undefined,
    modelName: currentModel.value?.displayName,
    attachments: atts,
    usedVision: true,
  }
  messages.value.push(ai)
  sending.value = true
  void scrollToBottom()
  runStream(u.content, atts.map((a) => a.id), true, ai.id)
}

// ---------------- 反馈（T-807 / G5-14） ----------------

/** 正在提交反馈的消息 id（挡住同一行的重复点击）。 */
const feedbackBusy = ref('')

/** 欢迎语不是真实消息，后端没有它的行 —— 对它点赞只会拿到一个 NOT_FOUND。 */
function canFeedback(m: ChatMessage) {
  return m.role === 'assistant' && !m.streaming && !!m.content && m.id !== WELCOME.id
}

/**
 * 提交反馈。点赞直接提交；点踩由调用方带上用户选的原因。
 *
 * <p>⚠️ 后端返回的是**落库后的当前值**（反馈可改，是覆盖语义），所以这里用返回值
 * 覆盖本地状态而不是用刚提交的 `type` —— 两者不一致就说明服务端做了别的决定，
 * 以服务端为准，别让界面显示一个库里没有的状态。
 */
async function submitFeedback(m: ChatMessage, type: 'like' | 'unlike', reason?: string) {
  if (!canFeedback(m) || feedbackBusy.value) return
  feedbackBusy.value = m.id
  try {
    const res = await chatApi.feedback({ messageId: m.id, type, ...(reason ? { reason } : {}) })
    m.feedback = res.feedback as 'like' | 'unlike'
    m.feedbackReason = res.reason
    ElMessage.success(type === 'like' ? '感谢反馈' : '已记录，我们会据此改进检索')
  } catch {
    ElMessage.error('反馈提交失败，请稍后重试')
  } finally {
    feedbackBusy.value = ''
  }
}

/** 反馈按钮的高亮态：点赞=主色 / 点踩=危险色 / 未反馈=默认。 */
function feedbackType(m: ChatMessage, which: 'like' | 'unlike'): 'primary' | 'danger' | 'default' {
  if (m.feedback !== which) return 'default'
  return which === 'like' ? 'primary' : 'danger'
}

/** 复制回答正文。失败时给下一步动作（`navigator.clipboard` 在非安全上下文下不可用）。 */
async function copyAnswer(m: ChatMessage) {
  try {
    await navigator.clipboard.writeText(m.content)
    ElMessage.success('已复制回答')
  } catch {
    ElMessage.error('复制失败（浏览器未授权剪贴板），请手动选中文本复制')
  }
}

function runStream(text: string, attachmentIds: string[], useVision: boolean, aiId: string) {
  const current = () => messages.value.find((m) => m.id === aiId)

  chatStream(
    text,
    {
      onAttachment(data) {
        const m = current()
        const att = m?.attachments?.find((a) => a.id === data.attachmentId)
        if (!att) return
        if (data.stage === 'DESCRIBING') {
          att.stage = 'DESCRIBING'
        } else {
          att.stage = 'DONE'
          att.desc = data.desc
          att.searchSummary = data.searchSummary
          att.vlModelName = data.vlModel
          att.degraded = data.degraded
        }
        void scrollToBottom()
      },
      onRetrieval(data) {
        const m = current()
        if (!m) return
        m.trace = data.trace as RetrievalTrace
        m.citations = (data.chunks as unknown[]).slice(0, 5).map((c, i) => {
          const raw = c as { chunkId: number; docId: number; docTitle: string; score: number; source: 'vector' | 'bm25' | 'graph' }
          return {
            index: i + 1,
            docId: raw.docId,
            docTitle: raw.docTitle ?? '未知文档',
            chunkId: raw.chunkId,
            source: raw.source,
            score: raw.score,
          } as Citation
        })
      },
      onDelta({ content }) {
        const m = current()
        if (m) {
          m.content += content
          void scrollToBottom()
        }
      },
      onDone(data) {
        const m = current()
        if (m) {
          m.streaming = false
          // 🔴 把本地临时 id 换成**服务端那条消息的真 id**。
          // `done.messageId` 存在的唯一理由就是这个：`T-807` 的 `/chat/feedback`
          // 要拿它去定位那一行。换掉之前，页面上发出去的永远是 `a_1789…` 这种本地串，
          // 后端解析不出数字、一律 NOT_FOUND —— 而**界面看起来一切正常**
          // （按钮点了、没报错提示之外没有任何异常），只是反馈一条也存不进来。
          // 与 `V23` 头注释写的「事件的参与者不齐，数据就永久缺一块」是同一种形状。
          if (data.messageId) m.id = String(data.messageId)
          m.modelId = data.modelId ?? m.modelId
          m.modelName = data.modelName ?? m.modelName
          if (data.usedVision) m.usedVision = true
        }
        sending.value = false
        void scrollToBottom()
      },
      onError(msg) {
        const m = current()
        if (m) {
          m.streaming = false
          m.content += `\n\n> ⚠️ ${msg}`
        }
        sending.value = false
        ElMessage.error(msg)
      },
    },
    { modelId: selectedModelId.value, attachmentIds, useVision },
  )
}

// ---------------- 检索侧面板 ----------------
function stagePercent(trace: RetrievalTrace | undefined, total: number) {
  if (!trace) return 0
  return Math.round((total / trace.totalMs) * 100)
}
function sourceLabel(s: string) {
  return { vector: '向量', bm25: '关键词', graph: '图谱' }[s] ?? s
}
function sourceColor(s: string) {
  return { vector: 'primary', bm25: 'success', graph: 'purple' }[s] ?? 'info'
}

// ---------------- 会话列表（每项显示所用模型） ----------------
const sessions = ref<ChatSessionItem[]>([])
const activeSession = ref('s1')

onMounted(async () => {
  const [sessRes] = await Promise.all([chatApi.sessions(), loadModels()])
  sessions.value = sessRes.list
})
</script>

<template>
  <div class="chat-page">
    <!-- 左侧：会话列表 -->
    <aside class="chat-side">
      <div class="side-head">
        <span>会话列表</span>
        <el-button text type="primary" :icon="FolderOpened" size="small" @click="onNewChat">新会话</el-button>
      </div>
      <div
        v-for="s in sessions"
        :key="s.id"
        class="session-item"
        :class="{ active: activeSession === s.id }"
        @click="activeSession = s.id"
      >
        <div class="si-title">{{ s.title }}</div>
        <div class="si-meta">
          <span>{{ s.time }}</span>
          <span>{{ s.count }} 条</span>
        </div>
        <div v-if="s.modelName" class="si-model">{{ s.modelName }}</div>
      </div>
    </aside>

    <!-- 右侧：对话区 -->
    <main class="chat-main">
      <!-- 模型切换器（只列已启用且测试通过的对话模型；切换 = 开新会话） -->
      <div class="model-bar">
        <span class="mb-label">对话模型</span>
        <el-select v-model="selectedModelId" size="small" style="width: 240px" @change="onModelChange">
          <el-option v-for="m in models" :key="m.id" :label="m.displayName" :value="m.id">
            <div class="opt-row">
              <span>{{ m.displayName }}</span>
              <span class="opt-caps">
                <em v-if="m.capabilities.stream">流式</em>
                <em v-if="m.capabilities.vision">视觉</em>
                <em>{{ (m.capabilities.contextWindow / 1000).toFixed(0) }}K</em>
              </span>
            </div>
          </el-option>
        </el-select>
        <span class="mb-tip">
          <el-icon><InfoFilled /></el-icon>
          切换模型将<b>开启新会话</b>，原会话保留在左侧列表
        </span>
        <span v-if="currentModel && !currentModel.capabilities.stream" class="mb-warn">该模型不支持流式，回答将一次性返回</span>
        <span v-if="currentModel && !currentModel.capabilities.supportSystemRole" class="mb-warn">不支持 system role，指令遵循可能变弱</span>
      </div>

      <div ref="listRef" class="msg-list">
        <div v-for="m in messages" :key="m.id" class="msg" :class="m.role">
          <div class="avatar-box">
            <el-avatar :size="30" class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</el-avatar>
          </div>
          <div class="bubble-wrap">
            <!-- 用户消息 -->
            <template v-if="m.role === 'user'">
              <div v-if="m.attachments?.length" class="thumb-row">
                <div v-for="a in m.attachments" :key="a.id" class="thumb">
                  <img v-if="a.url" :src="a.url" :alt="a.name" />
                  <div v-else class="thumb-ph"><el-icon><Picture /></el-icon></div>
                  <span class="thumb-badge" :class="a.stage === 'FAILED' ? 'bad' : ''">
                    <template v-if="a.stage === 'FAILED'">失败</template>
                    <template v-else-if="(a.progress ?? 0) < 100">{{ a.progress }}%</template>
                    <template v-else>已上传</template>
                  </span>
                </div>
              </div>
              <div class="bubble user-bubble">{{ m.content }}</div>
            </template>

            <!-- AI 消息 -->
            <div v-else class="bubble ai-bubble">
              <!-- 图片理解进度 / 结果 -->
              <div v-if="m.attachments?.length" class="attach-status">
                <template v-for="a in m.attachments" :key="a.id">
                  <div v-if="a.stage === 'DESCRIBING'" class="as-line describing">
                    <span class="spinner"></span>
                    正在理解图片（{{ a.vlModelName ?? 'VL 模型' }}）…
                  </div>
                  <div v-else-if="a.stage === 'DONE'" class="as-done">
                    <div class="as-line">
                      <el-icon><PictureFilled /></el-icon>
                      图片已理解
                      <span v-if="a.degraded" class="degraded-tag">由备用模型完成</span>
                      <el-popover placement="bottom-start" :width="420" trigger="click">
                        <template #reference><span class="as-link">查看描述</span></template>
                        <div class="desc-pop">
                          <div class="tiny muted" style="margin-bottom: 6px">完整描述（只读，后端内部流转）</div>
                          <pre class="desc-pre">{{ a.desc }}</pre>
                          <div class="tiny muted" style="margin-top: 8px">检索摘要：{{ a.searchSummary }}</div>
                        </div>
                      </el-popover>
                    </div>
                  </div>
                </template>
              </div>

              <pre class="ai-content">{{ m.content }}<span v-if="m.streaming" class="cursor-blink">▍</span></pre>

              <!-- 引用角标行 -->
              <div v-if="m.citations?.length" class="cit-row">
                <el-tag
                  v-for="c in m.citations"
                  :key="c.index"
                  size="small"
                  :type="sourceColor(c.source) as any"
                  effect="light"
                  class="cit-tag"
                >
                  [{{ c.index }}] {{ c.docTitle.slice(0, 12) }} · {{ (c.score * 100).toFixed(0) }}%
                </el-tag>
              </div>

              <!-- 检索详情面板 -->
              <div v-if="m.trace" class="trace-panel">
                <div class="tp-head">
                  <span class="tp-title"><el-icon><Timer /></el-icon> 检索链路 · 共 {{ m.trace.totalMs }}ms</span>
                  <el-tag v-if="m.trace.graphUsed" size="small" type="warning" effect="plain" class="tp-graph">
                    <el-icon><Connection /></el-icon> 图谱旁路 {{ m.trace.graphCostMs }}ms
                  </el-tag>
                </div>
                <div class="tp-stages">
                  <div v-for="st in m.trace.stages" :key="st.name" class="tp-stage">
                    <span class="tp-name">{{ st.name }}</span>
                    <div class="tp-bar"><div class="tp-fill" :style="{ width: stagePercent(m.trace, st.costMs) + '%' }"></div></div>
                    <span class="tp-cost">{{ st.costMs }}ms</span>
                  </div>
                </div>
                <div class="tp-cites">
                  <div v-for="c in m.citations" :key="c.index" class="tp-cite">
                    <el-tag size="small" :type="sourceColor(c.source) as any" effect="plain">[{{ c.index }}]</el-tag>
                    <span class="tp-cite-title">{{ c.docTitle }}</span>
                    <span class="tp-cite-score">{{ (c.score * 100).toFixed(0) }}% · {{ sourceLabel(c.source) }}</span>
                  </div>
                </div>
              </div>

              <!-- 消息标注：所用模型 + 原图兜底入口 -->
              <div v-if="!m.streaming && m.content" class="msg-foot">
                <span v-if="m.modelName" class="mf-model">由 {{ m.modelName }} 生成</span>
                <span v-if="m.usedVision" class="mf-vision"><el-icon><View /></el-icon> 已读原图</span>
                <!-- 右侧动作组：原图兜底 + 反馈（T-807，需求 07 §6.8 点名的四件里除「重新生成」）。
                     ⚠️ 全部收进**一个** margin-left:auto 的容器 —— 曾经是给每个 el-button
                     各加一条 margin-left:auto，那样两个按钮会被推到行的两端 -->
                <span class="mf-actions">
                  <el-button
                    v-if="m.attachments?.length"
                    size="small"
                    text
                    :icon="Picture"
                    :disabled="!canReadOriginal(m)"
                    :title="canReadOriginal(m) ? '' : '当前对话模型不支持视觉（vision）'"
                    @click="readOriginal(m)"
                  >
                    直接读原图
                  </el-button>
                  <el-button size="small" text :icon="DocumentCopy" :disabled="!canFeedback(m)" @click="copyAnswer(m)">
                    复制
                  </el-button>
                  <el-button
                    size="small"
                    text
                    :icon="CaretTop"
                    :type="feedbackType(m, 'like')"
                    :disabled="!canFeedback(m) || feedbackBusy === m.id"
                    @click="submitFeedback(m, 'like')"
                  >
                    {{ m.feedback === 'like' ? '已点赞' : '点赞' }}
                  </el-button>
                  <el-popover :width="188" trigger="click" :disabled="!canFeedback(m) || feedbackBusy === m.id">
                    <template #reference>
                      <el-button
                        size="small"
                        text
                        :icon="CaretBottom"
                        :type="feedbackType(m, 'unlike')"
                        :disabled="!canFeedback(m) || feedbackBusy === m.id"
                      >
                        {{ m.feedback === 'unlike' ? '已点踩' : '点踩' }}
                      </el-button>
                    </template>
                    <div class="fb-title">哪里不满意？（可不选直接关闭）</div>
                    <div
                      v-for="r in FEEDBACK_REASONS"
                      :key="r"
                      class="fb-reason"
                      @click="submitFeedback(m, 'unlike', r)"
                    >
                      {{ r }}
                    </div>
                  </el-popover>
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="chat-input" @paste="onPaste">
        <!-- 待发送附件 -->
        <div v-if="attachments.length" class="pending-row">
          <div v-for="a in attachments" :key="a.id" class="pending-item">
            <img v-if="a.url" :src="a.url" :alt="a.name" />
            <div class="pi-meta">
              <span class="pi-name">{{ a.name }}</span>
              <span class="tiny muted">{{ formatSize(a.size) }}</span>
              <span v-if="a.stage === 'FAILED'" class="tiny bad-text">上传失败</span>
              <span v-else-if="(a.progress ?? 0) < 100" class="tiny muted">上传中 {{ a.progress }}%</span>
              <span v-else class="tiny muted">待发送（发送后由 VL 生成描述）</span>
            </div>
            <el-button text size="small" :icon="Delete" @click="removeAttachment(a.id)" />
          </div>
        </div>

        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入你的问题，Enter 发送，Shift+Enter 换行；可直接粘贴图片"
          :disabled="sending"
          @keydown.enter.exact.prevent="send"
        />
        <div class="input-toolbar">
          <div class="it-left">
            <el-button size="small" :icon="Picture" :disabled="sending || attachments.length >= MAX_IMAGES" @click="pickFiles">
              图片（{{ attachments.length }}/{{ MAX_IMAGES }}）
            </el-button>
            <input ref="fileInput" type="file" accept="image/*" multiple hidden @change="onFilePicked" />
            <span class="tip">支持引用溯源 · 图谱检索 · 单张 ≤ 10MB</span>
          </div>
          <el-button type="primary" :icon="Promotion" :loading="sending" @click="send">发送</el-button>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.chat-page {
  height: 100%;
  display: flex;
  background: #eef1f6;
}
.chat-side {
  width: 240px;
  background: #fff;
  border-right: 1px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}
.side-head {
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.session-item {
  padding: 10px 16px;
  cursor: pointer;
  border-left: 3px solid transparent;
}
.session-item:hover {
  background: var(--el-fill-color-light, #f5f7fa);
}
.session-item.active {
  background: var(--el-color-primary-light-9);
  border-left-color: var(--el-color-primary);
}
.si-title {
  font-size: 13px;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.si-meta {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-top: 2px;
  display: flex;
  gap: 10px;
}
.si-model {
  font-size: 11px;
  color: var(--el-color-primary);
  margin-top: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* 模型切换条 */
.model-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 20px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-lighter);
  flex-wrap: wrap;
}
.mb-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
  font-weight: 600;
}
.mb-tip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.mb-tip .el-icon {
  color: var(--el-color-primary);
}
.mb-warn {
  font-size: 12px;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  padding: 1px 8px;
  border-radius: 4px;
}
.opt-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.opt-caps em {
  font-style: normal;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light, #f5f7fa);
  padding: 0 5px;
  border-radius: 3px;
  margin-left: 4px;
}

.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px 28px;
}
.msg {
  display: flex;
  gap: 12px;
  margin-bottom: 22px;
}
.msg.user {
  flex-direction: row-reverse;
}
.avatar-box {
  flex: none;
}
.avatar {
  background: var(--brand-grad);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}
.msg.user .avatar {
  background: var(--el-color-primary);
}
.bubble-wrap {
  max-width: 76%;
  min-width: 0;
}
.bubble {
  border-radius: 12px;
  padding: 12px 16px;
  line-height: 1.7;
  font-size: 14px;
}
.user-bubble {
  background: var(--el-color-primary);
  color: #fff;
  border-top-right-radius: 4px;
}
.ai-bubble {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-top-left-radius: 4px;
  box-shadow: var(--shadow-s);
}
.ai-content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--font);
  font-size: 14px;
}
.cursor-blink {
  animation: blink 0.9s infinite;
  color: var(--el-color-primary);
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

/* 用户消息缩略图 */
.thumb-row {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.thumb {
  position: relative;
  width: 92px;
  height: 92px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  background: #f5f7fa;
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.thumb-ph {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-placeholder);
  font-size: 20px;
}
.thumb-badge {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  font-size: 11px;
  text-align: center;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  padding: 1px 0;
}
.thumb-badge.bad {
  background: var(--el-color-danger);
}

/* 图片理解进度 */
.attach-status {
  margin-bottom: 10px;
}
.as-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--el-text-color-regular);
  padding: 7px 10px;
  background: var(--el-color-primary-light-9);
  border-radius: 6px;
}
.as-line .el-icon {
  color: var(--el-color-primary);
}
.describing {
  color: #3a6ea5;
}
.spinner {
  width: 12px;
  height: 12px;
  border: 2px solid var(--el-color-primary-light-5);
  border-top-color: var(--el-color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  flex: none;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.as-done {
  margin-bottom: 6px;
}
.degraded-tag {
  font-size: 11px;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  padding: 0 6px;
  border-radius: 3px;
}
.as-link {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-color-primary);
  cursor: pointer;
}
.desc-pop .desc-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.7;
  font-family: var(--font-mono);
  background: var(--el-fill-color-lighter, #fafafa);
  padding: 10px;
  border-radius: 6px;
  max-height: 260px;
  overflow: auto;
}

/* 消息标注 */
.msg-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.mf-model {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.mf-vision {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 12px;
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
  padding: 0 7px;
  border-radius: 3px;
}
/* 右侧动作组。⚠️ margin-left:auto 只加在**容器**上 —— 逐个 el-button 都加会把
   相邻两个按钮推到行的两端（本批加反馈按钮时踩到），这条替换掉了原来的做法 */
.mf-actions {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
/* 点踩原因弹层 */
.fb-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.fb-reason {
  padding: 5px 8px;
  font-size: 13px;
  border-radius: 4px;
  cursor: pointer;
}
.fb-reason:hover {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}

/* 引用 / 检索详情 */
.cit-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}
.cit-tag {
  cursor: pointer;
}
.trace-panel {
  margin-top: 12px;
  padding: 12px 14px;
  background: var(--el-fill-color-lighter, #fafafa);
  border-radius: 8px;
  border: 1px dashed var(--el-border-color);
}
.tp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.tp-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.tp-stages {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.tp-stage {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.tp-name {
  width: 72px;
  flex: none;
  color: var(--el-text-color-secondary);
}
.tp-bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--el-border-color-lighter);
  overflow: hidden;
}
.tp-fill {
  height: 100%;
  background: var(--el-color-primary);
  border-radius: 3px;
  transition: width 0.4s;
}
.tp-cost {
  width: 52px;
  text-align: right;
  font-family: var(--font-mono);
  color: var(--el-text-color-secondary);
}
.tp-cites {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.tp-cite {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.tp-cite-title {
  color: var(--el-text-color-regular);
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tp-cite-score {
  color: var(--el-text-color-placeholder);
}

/* 输入区 */
.chat-input {
  padding: 12px 20px 16px;
  background: #fff;
  border-top: 1px solid var(--el-border-color-lighter);
}
.pending-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 10px;
}
.pending-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  background: var(--el-fill-color-lighter, #fafafa);
  border-radius: 8px;
}
.pending-item img {
  width: 40px;
  height: 40px;
  border-radius: 6px;
  object-fit: cover;
  flex: none;
}
.pi-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}
.pi-name {
  font-size: 12.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bad-text {
  color: var(--el-color-danger);
}
.input-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.it-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.tip {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>
