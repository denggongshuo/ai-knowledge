<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  Refresh,
  Download,
  ChatDotRound,
  ZoomIn,
  ZoomOut,
  Document,
  Grid,
  Lock,
  InfoFilled,
  TopRight,
  Picture,
} from '@element-plus/icons-vue'
import { docApi } from '@/api'
import type { DocMeta, ChunkInfo, ChunkStat, ParentChunkRelation, TocItem, IndexTask } from '@/types'
import { goBack } from '@/utils/nav'

const route = useRoute()
const router = useRouter()
const docId = computed(() => String(route.params.id ?? '1'))

const loading = ref(false)
const activeTab = ref('preview')
const viewMode = ref<'origin' | 'parsed'>('origin')
const zoom = ref(100)
const currentPage = ref(3)
const pageCount = ref(24)

const meta = ref<DocMeta | null>(null)
const chunks = ref<ChunkInfo[]>([])
const stat = ref<ChunkStat | null>(null)
const toc = ref<TocItem[]>([])
const tasks = ref<IndexTask[]>([])
const relation = ref<ParentChunkRelation | null>(null)

const chunkFilter = ref<'all' | 'text' | 'table' | 'image' | 'summary'>('all')
const selectedChunkId = ref<number | null>(null)
const selectedTocId = ref('h3')

const chunkEls = ref<Record<number, HTMLElement>>({})
const paperEl = ref<HTMLElement>()

/** 原文段落（模拟解析后的文档内容，绑定 chunkIndex 以支持双向定位） */
const paragraphs: { id: string; chunkIndex: number; level: number; title?: string; html: string }[] = [
  { id: 'p0', chunkIndex: 46, level: 0, title: '3. 接口设计', html: '所有对外接口统一以 <code>/api</code> 为前缀，采用 JSON 作为主要数据交换格式，字符集固定为 UTF-8。接口通过 <code>Authorization: Bearer &lt;accessToken&gt;</code> 请求头完成身份传递。' },
  { id: 'p1', chunkIndex: 47, level: 0, html: '' },
  { id: 'p2', chunkIndex: 48, level: 0, html: '' },
  { id: 'p3', chunkIndex: 49, level: 0, title: '3.2 错误码规范', html: '统一响应体为 <code>{ code, message, data, traceId }</code>。其中 code 为 0 表示成功，非 0 表示业务失败；HTTP 状态码仅用于表达传输层语义，业务错误一律返回 200。' },
]

const paragraphText: Record<number, string> = {
  46: '所有对外接口统一以 /api 为前缀，采用 JSON 作为主要数据交换格式，字符集固定为 UTF-8。',
  47: '客户端首次登录成功后，服务端签发一对令牌：访问令牌有效期 2 小时，刷新令牌有效期 7 天。当访问令牌过期时，客户端应使用刷新令牌换取新的访问令牌，而不是重新走登录流程。',
  48: '访问令牌存放于内存或 sessionStorage，避免 XSS 窃取；刷新令牌通过 HttpOnly Cookie 下发，前端不可读取。',
  49: '统一响应体为 { code, message, data, traceId }，code 为 0 表示成功，非 0 表示业务失败。',
}

const BLOCK_META: Record<string, { group: 'text' | 'table' | 'summary' | 'image'; label: string }> = {
  PARAGRAPH: { group: 'text', label: '正文' },
  HEADING: { group: 'text', label: '正文' },
  CODE: { group: 'text', label: '正文' },
  LIST: { group: 'text', label: '正文' },
  TABLE: { group: 'table', label: '表格' },
  SUMMARY: { group: 'summary', label: '摘要' },
  IMAGE: { group: 'image', label: '图片' },
}
function groupOf(t: string) {
  return BLOCK_META[t]?.group ?? 'text'
}
function labelOf(t: string) {
  return BLOCK_META[t]?.label ?? '正文'
}

/** 块引用号：表格 T-xx / 摘要 D-SUM / 图片 IMG-xx / 正文 #n */
function refOf(c: { blockType: string; index: number }) {
  const g = groupOf(c.blockType)
  if (g === 'table') return 'T-' + String(c.index).padStart(2, '0')
  if (g === 'summary') return 'D-SUM'
  if (g === 'image') return 'IMG-' + String(c.index).padStart(2, '0')
  return '#' + c.index
}

/** 图片块标签类型（紫色 = primary 的浅色，用自定义 class 呈现） */
function tagTypeOf(t: string): 'warning' | 'primary' | 'info' {
  const g = groupOf(t)
  if (g === 'table') return 'warning'
  if (g === 'summary') return 'primary'
  if (g === 'image') return 'primary'
  return 'info'
}

onMounted(async () => {
  loading.value = true
  try {
    const [m, c, t, ts] = await Promise.all([
      docApi.meta(docId.value),
      docApi.chunks(docId.value),
      docApi.toc(docId.value),
      docApi.indexTasks(docId.value),
    ])
    meta.value = m
    chunks.value = c.list
    stat.value = c.stat
    toc.value = t.list
    tasks.value = ts.list
    pageCount.value = m.pageCount
    // 父子块关系（默认给出，选中表格块时展示）
    relation.value = await docApi.parentChunk(docId.value, 0)
    selectedChunkId.value = chunks.value[0]?.id ?? null
  } finally {
    loading.value = false
  }
})

const visibleChunks = computed(() =>
  chunkFilter.value === 'all' ? chunks.value : chunks.value.filter((c) => groupOf(c.blockType) === chunkFilter.value),
)

function sizeText(b: number) {
  return b / 1024 / 1024 >= 1 ? `${(b / 1024 / 1024).toFixed(1)} MB` : `${(b / 1024).toFixed(0)} KB`
}

// ---------------- 联动 ----------------
function selectToc(item: TocItem) {
  selectedTocId.value = item.id
  if (item.id === 'h3') currentPage.value = 3
}
/** 点原文段落 → 右栏滚动并高亮对应分片 */
async function selectParagraph(p: { id: string; chunkIndex: number }) {
  chunkFilter.value = 'all'
  selectedChunkId.value = p.chunkIndex
  await nextTick()
  chunkEls.value[p.chunkIndex]?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
/** 点分片卡片 → 中栏滚动到对应位置并闪一下 */
async function selectChunk(c: ChunkInfo) {
  selectedChunkId.value = c.id
  await nextTick()
  const el = paperEl.value?.querySelector(`[data-chunk="${c.id}"]`)
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  el?.classList.add('flash')
  setTimeout(() => el?.classList.remove('flash'), 900)
}

function setChunkRef(id: number, el: unknown) {
  if (el instanceof HTMLElement) chunkEls.value[id] = el
}

function zoomIn() {
  zoom.value = Math.min(200, zoom.value + 10)
}
function zoomOut() {
  zoom.value = Math.max(60, zoom.value - 10)
}

// ---------------- 操作 ----------------
async function download() {
  try {
    const res = await docApi.downloadUrl(docId.value)
    ElMessage.success('已生成下载链接（有效期 10 分钟）')
    window.open(res.url, '_blank')
  } catch {
    ElMessage.error('下载失败')
  }
}
async function reindex() {
  await docApi.reindex(docId.value)
  ElMessage.success('已发起重建索引')
}
function askDoc() {
  router.push({ path: '/chat', query: { docId: docId.value } })
}
function openNewWindow() {
  ElMessage.info('已在新窗口打开原文（演示）')
}
</script>

<template>
  <div v-loading="loading" class="page preview-page">
    <!-- 页头 -->
    <div v-if="meta" class="page-head">
      <div class="head-left">
        <el-button text :icon="ArrowLeft" @click="goBack(router, `/doc/${docId}`)">返回</el-button>
        <div>
          <h2 class="page-title">{{ meta.title }}</h2>
          <div class="head-tags">
            <el-tag size="small" type="success">已就绪</el-tag>
            <el-tag size="small" type="info">{{ meta.type }} · {{ sizeText(meta.size) }}</el-tag>
            <el-tag size="small" type="info">{{ meta.pageCount }} 页 · {{ meta.chunkCount }} 个分片</el-tag>
            <el-tag v-if="meta.tableParsed < meta.tableTotal" size="small" type="danger">
              {{ meta.tableTotal - meta.tableParsed }} 处表格未识别
            </el-tag>
            <span class="muted">{{ meta.kbName }} / {{ meta.department }}</span>
          </div>
        </div>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="reindex">重建索引</el-button>
        <el-button :icon="Download" @click="download">下载</el-button>
        <el-button type="primary" :icon="ChatDotRound" @click="askDoc">基于本文档提问</el-button>
      </div>
    </div>

    <el-card v-if="meta" shadow="never" class="mt-12">
      <el-tabs v-model="activeTab">
        <!-- ==================== 原文预览 ==================== -->
        <el-tab-pane name="preview">
          <template #label><span class="tab-l"><el-icon><Document /></el-icon>原文预览</span></template>

          <div class="three-col">
            <!-- 左：目录 + 缩略图 -->
            <div class="col col-left">
              <div class="col-title">文档目录</div>
              <div
                v-for="item in toc"
                :key="item.id"
                class="toc-item"
                :class="{ active: selectedTocId === item.id, lv2: item.level === 2 }"
                @click="selectToc(item)"
              >
                {{ item.label }}
              </div>

              <div class="divider" />
              <div class="col-title">页面缩略图</div>
              <div class="thumbs">
                <div
                  v-for="p in Math.min(6, pageCount)"
                  :key="p"
                  class="thumb"
                  :class="{ active: currentPage === p }"
                  @click="currentPage = p"
                >
                  <i v-if="currentPage === p" class="thumb-mark" />
                  <span class="thumb-no">{{ p }}</span>
                </div>
              </div>
            </div>

            <!-- 中：原文 -->
            <div class="col col-mid">
              <div class="mid-toolbar">
                <el-radio-group v-model="viewMode" size="small">
                  <el-radio-button value="origin">原文</el-radio-button>
                  <el-radio-button value="parsed">解析文本</el-radio-button>
                </el-radio-group>
                <div class="row-inline">
                  <el-button size="small" :icon="ZoomOut" @click="zoomOut" />
                  <span class="mono zoom-val">{{ zoom }}%</span>
                  <el-button size="small" :icon="ZoomIn" @click="zoomIn" />
                  <span class="tiny muted mono ml-8">第 {{ currentPage }} / {{ pageCount }} 页</span>
                  <el-button size="small" :icon="TopRight" @click="openNewWindow">新窗口打开</el-button>
                </div>
              </div>

              <div ref="paperEl" class="paper-wrap">
                <div class="paper" :style="{ transform: `scale(${zoom / 100})` }">
                  <template v-if="viewMode === 'origin'">
                    <div
                      v-for="p in paragraphs"
                      :key="p.id"
                      :data-chunk="p.chunkIndex"
                      class="para"
                      :class="{ active: selectedChunkId === p.chunkIndex }"
                      @click="selectParagraph(p)"
                    >
                      <h3 v-if="p.title">{{ p.title }}</h3>
                      <p v-html="p.html || paragraphText[p.chunkIndex]" />
                    </div>
                  </template>
                  <template v-else>
                    <div class="parsed-note">
                      <el-icon><InfoFilled /></el-icon>
                      下方为后端切分后的纯文本，用于验证解析质量（与分片视图一一对应）
                    </div>
                    <div
                      v-for="c in chunks"
                      :key="c.id"
                      :data-chunk="c.id"
                      class="para parsed"
                      :class="{ active: selectedChunkId === c.id, table: groupOf(c.blockType) === 'table', image: groupOf(c.blockType) === 'image' }"
                      @click="selectChunk(c)"
                    >
                      <span class="parsed-ref mono">{{ refOf(c) }}</span>
                      <span>{{ c.content }}</span>
                    </div>
                  </template>
                </div>
              </div>
            </div>

            <!-- 右：分片详情 -->
            <div class="col col-right">
              <div class="row-between mb-10">
                <div class="col-title" style="margin: 0">分片详情</div>
                <el-tag size="small" type="info">共 {{ stat?.total ?? meta.chunkCount }} 块</el-tag>
              </div>

              <div class="chip-row mb-12">
                <span class="chip" :class="{ active: chunkFilter === 'all' }" @click="chunkFilter = 'all'">全部 {{ stat?.total }}</span>
                <span class="chip" :class="{ active: chunkFilter === 'text' }" @click="chunkFilter = 'text'">正文 {{ stat?.text }}</span>
                <span class="chip table-chip" :class="{ active: chunkFilter === 'table' }" @click="chunkFilter = 'table'">表格 {{ stat?.table }}</span>
                <span class="chip image-chip" :class="{ active: chunkFilter === 'image' }" @click="chunkFilter = 'image'">图片 {{ stat?.image ?? 0 }}</span>
                <span class="chip" :class="{ active: chunkFilter === 'summary' }" @click="chunkFilter = 'summary'">摘要 {{ stat?.summary }}</span>
              </div>

              <div
                v-for="c in visibleChunks"
                :key="c.id"
                :ref="(el) => setChunkRef(c.id, el)"
                class="chunk-item"
                :class="{ active: selectedChunkId === c.id, 'table-item': groupOf(c.blockType) === 'table', 'sum-item': groupOf(c.blockType) === 'summary', 'image-item': groupOf(c.blockType) === 'image' }"
                @click="selectChunk(c)"
              >
                <div class="ch-head">
                  <el-tag size="small" :type="tagTypeOf(c.blockType)">
                    <el-icon v-if="groupOf(c.blockType) === 'table'"><Grid /></el-icon>
                    <el-icon v-else-if="groupOf(c.blockType) === 'image'"><Picture /></el-icon>
                    {{ labelOf(c.blockType) }}
                  </el-tag>
                  <span class="tiny mono muted">
                    {{ refOf(c) }}
                    <template v-if="groupOf(c.blockType) !== 'table'"> · {{ c.tokens }} tokens</template>
                  </span>
                  <el-tag v-if="groupOf(c.blockType) === 'table' || groupOf(c.blockType) === 'image'" size="small" type="info" class="ml-auto">
                    <el-icon><Lock /></el-icon>原子块
                  </el-tag>
                  <el-tag v-else size="small" type="success" class="ml-auto">已索引</el-tag>
                </div>
                <div class="tiny muted chunk-path">{{ c.titlePath }}</div>
                <div v-if="groupOf(c.blockType) === 'image'" class="img-thumb">
                  <div class="img-ph"><el-icon><Picture /></el-icon></div>
                  <span class="tiny muted">VL 描述（只读展示，前端不作为检索输入）</span>
                </div>
                <div class="chunk-body">{{ c.content }}</div>
                <div v-if="groupOf(c.blockType) === 'table'" class="tiny muted mt-4">
                  未参与切分 · 已拆出 {{ c.childrenCount ?? 8 }} 个子块用于检索
                </div>
                <div v-else-if="groupOf(c.blockType) === 'image'" class="tiny muted mt-4">
                  图片整体原子化（不做图像切分）· 描述 + 检索摘要均由当前 VL 模型一次调用产出
                </div>
              </div>

              <!-- 父子块关系 -->
              <div v-if="relation" class="relation-box">
                <div class="rel-title">父子块关系</div>
                <div class="row-inline">
                  <el-tag size="small" type="warning">命中</el-tag>
                  <span class="tiny">子块 {{ relation.child.ref }}</span>
                </div>
                <div class="rel-content child">{{ relation.child.content }}</div>
                <div class="rel-arrow">↓ parent_id</div>
                <div class="row-inline">
                  <el-tag size="small">父块</el-tag>
                  <span class="tiny">{{ relation.parent.title }}</span>
                </div>
                <div class="rel-content parent">
                  {{ relation.parent.rows }} 行 × {{ relation.parent.cols }} 列全部返回，含表头与表标题（实际返回 {{ relation.parent.returnedRows }} 行）
                </div>
                <div class="rel-foot">
                  检索命中的是<b>子块</b>（精度高），喂给大模型的是<b>整个父块表格</b>（上下文全）。
                </div>
              </div>

              <div class="divider" />
              <div class="col-title">文档元信息</div>
              <dl class="kv">
                <dt>文档 ID</dt><dd class="mono">{{ meta.id }}</dd>
                <dt>所属库</dt><dd>{{ meta.kbName }}</dd>
                <dt>页数 / 分片</dt><dd class="mono">{{ meta.pageCount }} / {{ meta.chunkCount }}</dd>
                <dt>其中表格</dt><dd class="mono">{{ meta.tableTotal }} 张 · {{ stat?.tableSubBlocks }} 个子块</dd>
                <dt>解析质量</dt>
                <dd>
                  <el-tag v-if="meta.tableParsed < meta.tableTotal" size="small" type="danger">
                    {{ meta.tableTotal - meta.tableParsed }} 处表格未识别（第 12 页）
                  </el-tag>
                  <el-tag v-else size="small" type="success">表格全部识别</el-tag>
                </dd>
                <dt>存储桶</dt><dd class="mono ellipsis">{{ meta.storagePath }}</dd>
                <dt>MD5</dt><dd class="mono ellipsis">{{ meta.md5.slice(0, 8) }}…{{ meta.md5.slice(-4) }}</dd>
                <dt>上传人</dt><dd>{{ meta.owner }}</dd>
                <dt>最近索引</dt><dd class="mono">{{ meta.lastIndexAt }}</dd>
                <dt>被引用</dt><dd>{{ meta.citations }} 次问答引用</dd>
              </dl>

              <div class="tip-box">
                <el-icon><InfoFilled /></el-icon>
                点击左侧原文中的高亮段落，可定位到对应的分片；反之亦然。
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- ==================== 分片视图 ==================== -->
        <el-tab-pane name="chunks">
          <template #label><span class="tab-l"><el-icon><Grid /></el-icon>分片视图<span class="tab-num">{{ stat?.total }}</span></span></template>
          <div class="chip-row mb-12">
            <span class="chip" :class="{ active: chunkFilter === 'all' }" @click="chunkFilter = 'all'">全部 {{ stat?.total }}</span>
            <span class="chip" :class="{ active: chunkFilter === 'text' }" @click="chunkFilter = 'text'">正文 {{ stat?.text }}</span>
            <span class="chip table-chip" :class="{ active: chunkFilter === 'table' }" @click="chunkFilter = 'table'">表格 {{ stat?.table }}</span>
            <span class="chip image-chip" :class="{ active: chunkFilter === 'image' }" @click="chunkFilter = 'image'">图片 {{ stat?.image ?? 0 }}</span>
            <span class="chip" :class="{ active: chunkFilter === 'summary' }" @click="chunkFilter = 'summary'">摘要 {{ stat?.summary }}</span>
          </div>
          <div class="chunk-grid">
            <div
              v-for="c in visibleChunks"
              :key="c.id"
              class="chunk-item"
              :class="{ active: selectedChunkId === c.id, 'table-item': groupOf(c.blockType) === 'table', 'sum-item': groupOf(c.blockType) === 'summary', 'image-item': groupOf(c.blockType) === 'image' }"
              @click="selectChunk(c)"
            >
              <div class="ch-head">
                <el-tag size="small" :type="tagTypeOf(c.blockType)">
                  <el-icon v-if="groupOf(c.blockType) === 'table'"><Grid /></el-icon>
                  <el-icon v-else-if="groupOf(c.blockType) === 'image'"><Picture /></el-icon>
                  {{ labelOf(c.blockType) }}
                </el-tag>
                <span class="tiny mono muted">{{ refOf(c) }}</span>
                <el-tag v-if="groupOf(c.blockType) === 'table' || groupOf(c.blockType) === 'image'" size="small" type="info" class="ml-auto">
                  <el-icon><Lock /></el-icon>原子块
                </el-tag>
                <el-tag v-else size="small" type="success" class="ml-auto">已索引</el-tag>
              </div>
              <div class="tiny muted chunk-path">{{ c.titlePath }}</div>
              <div v-if="groupOf(c.blockType) === 'image'" class="img-thumb">
                <div class="img-ph"><el-icon><Picture /></el-icon></div>
                <span class="tiny muted">VL 描述（只读展示，前端不作为检索输入）</span>
              </div>
              <div class="chunk-body">{{ c.content }}</div>
              <div class="tiny mono muted mt-4">{{ c.tokens }} tokens</div>
            </div>
          </div>
          <div class="hint mt-8">
            chips 数量合计 = 总片数（{{ stat?.text }} + {{ stat?.table }} + {{ stat?.image ?? 0 }} + {{ stat?.summary }} = {{ stat?.total }}）。表格块与图片块均为原子块，不参与切分——这是排查多模态检索问题的第一现场。
          </div>
        </el-tab-pane>

        <!-- ==================== 向量化记录 ==================== -->
        <el-tab-pane name="tasks">
          <template #label><span class="tab-l"><el-icon><Refresh /></el-icon>向量化记录</span></template>
          <el-timeline>
            <el-timeline-item
              v-for="t in tasks"
              :key="t.id"
              :timestamp="t.at"
              :type="t.status === 'SUCCESS' ? 'success' : t.status === 'RUNNING' ? 'primary' : t.status === 'FAILED' ? 'danger' : 'info'"
              placement="top"
            >
              <div class="tl-title">{{ t.stage }}<el-tag size="small" type="info" class="ml-6">{{ t.version }}</el-tag></div>
              <div class="tiny muted">
                <template v-if="t.durationSec">耗时 {{ t.durationSec }} 秒 · {{ t.chunkCount }} 个分片</template>
                <template v-else>{{ t.detail }}</template>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-alert v-if="meta.tableParsed < meta.tableTotal" type="warning" :closable="false" show-icon class="mt-12">
            <template #title>
              <span class="alert-text">存在未识别的表格，可通过「重建索引」重新执行解析与表格识别（这是修复未识别表格的主要入口）</span>
            </template>
          </el-alert>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.preview-page {
  height: 100%;
  overflow-y: auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.page-title {
  font-size: 18px;
  margin: 0;
}
.head-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  flex-wrap: wrap;
}
.head-actions {
  display: flex;
  gap: 8px;
  flex: none;
}
.mt-12 {
  margin-top: 12px;
}
.mt-8 {
  margin-top: 8px;
}
.mt-4 {
  margin-top: 4px;
}
.mb-10 {
  margin-bottom: 10px;
}
.mb-12 {
  margin-bottom: 12px;
}
.ml-6 {
  margin-left: 6px;
}
.ml-8 {
  margin-left: 8px;
}
.ml-auto {
  margin-left: auto;
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
  background: var(--el-color-info-light-9);
  color: var(--el-text-color-secondary);
}
.row-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.row-inline {
  display: flex;
  align-items: center;
  gap: 6px;
}

/* ---------- 三栏 ---------- */
.three-col {
  display: grid;
  grid-template-columns: 216px minmax(0, 1fr) 316px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
}
.col {
  max-height: 660px;
  overflow: auto;
}
.col-left {
  border-right: 1px solid var(--el-border-color-lighter);
  padding: 14px 10px;
}
.col-mid {
  background: #f0f2f5;
  padding: 14px;
}
.col-right {
  border-left: 1px solid var(--el-border-color-lighter);
  padding: 14px 12px;
}
.col-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  letter-spacing: 0.4px;
  padding: 0 8px 8px;
}
.toc-item {
  padding: 6px 8px;
  font-size: 13px;
  border-radius: 5px;
  cursor: pointer;
  color: var(--el-text-color-regular);
}
.toc-item:hover {
  background: var(--el-color-primary-light-9);
}
.toc-item.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 500;
}
.toc-item.lv2 {
  padding-left: 20px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.divider {
  height: 1px;
  background: var(--el-border-color-lighter);
  margin: 12px 8px;
}
.thumbs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  padding: 0 8px;
}
.thumb {
  aspect-ratio: 3/4;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 5px;
  background: linear-gradient(170deg, #fff, #f4f7fc);
  position: relative;
  cursor: pointer;
  overflow: hidden;
}
.thumb.active {
  border-color: var(--el-color-primary);
}
.thumb-mark {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: var(--el-color-primary);
}
.thumb-no {
  position: absolute;
  bottom: 3px;
  right: 5px;
  font-size: 10px;
  color: var(--el-text-color-placeholder);
}

/* 中栏 */
.mid-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.zoom-val {
  font-size: 12px;
  padding: 0 4px;
}
.paper-wrap {
  overflow: auto;
}
.paper {
  background: #fff;
  border-radius: 6px;
  padding: 26px 30px;
  box-shadow: var(--shadow);
  transform-origin: top center;
  transition: transform 0.16s;
}
.paper h3 {
  font-size: 16px;
  margin: 18px 0 8px;
}
.paper h3:first-child {
  margin-top: 0;
}
.paper p {
  font-size: 13.5px;
  line-height: 2;
  color: var(--el-text-color-regular);
  margin: 0 0 8px;
}
.paper code {
  font-family: var(--font-mono);
  font-size: 12px;
  background: #f4f6fa;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 0 4px;
  color: #476582;
}
.para {
  cursor: pointer;
  border-radius: 6px;
  padding: 6px 8px;
  margin: 0 -8px;
  transition: background 0.18s;
}
.para:hover {
  background: var(--el-color-primary-light-9);
}
.para.active {
  background: #fff8e6;
  box-shadow: 0 0 0 1px #f0d9b5 inset;
}
.para.flash {
  animation: flash 0.9s ease;
}
@keyframes flash {
  0%,
  100% {
    background: transparent;
  }
  30% {
    background: #ffe9b8;
  }
}
.parsed {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--el-text-color-regular);
  padding: 8px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.parsed.table {
  background: #fffbf5;
}
.parsed.image {
  background: #f5f9ff;
}
.parsed-ref {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  flex: none;
  padding-top: 3px;
}
.parsed-note {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #3a6ea5;
  background: #f5f9ff;
  border-radius: 6px;
  padding: 8px 10px;
  margin-bottom: 12px;
}

/* 右栏 */
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.chip {
  font-size: 12px;
  padding: 2px 9px;
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
  cursor: pointer;
  color: var(--el-text-color-regular);
  background: #fff;
  transition: all 0.15s;
}
.chip:hover {
  border-color: var(--el-color-primary-light-5);
}
.chip.active {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.chip.table-chip {
  border-color: #f0d9b5;
  color: #854f0b;
  background: #fffbf5;
}
.chip.table-chip.active {
  border-color: #e6a23c;
  background: #fdf6ec;
}
.chip.image-chip {
  border-color: #cddff5;
  color: #2b6cb0;
  background: #f5f9ff;
}
.chip.image-chip.active {
  border-color: var(--el-color-primary);
  background: #ecf4ff;
}
.chunk-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 11px;
  margin-bottom: 9px;
  cursor: pointer;
  transition: all 0.16s;
  background: #fff;
}
.chunk-item:hover {
  border-color: var(--el-color-primary-light-5);
}
.chunk-item.active {
  border-color: var(--el-color-primary-light-3);
  background: #f7fbff;
}
.chunk-item.table-item {
  border-color: #f0d9b5;
  background: #fffbf5;
}
.chunk-item.sum-item {
  border-color: var(--el-color-primary-light-7);
  background: #f7fbff;
}
.chunk-item.image-item {
  border-color: #cddff5;
  background: #f5f9ff;
}
.img-thumb {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 6px 0 4px;
}
.img-ph {
  width: 56px;
  height: 42px;
  border-radius: 6px;
  border: 1px dashed #b9cee6;
  background: #eef4fc;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #7ba2cf;
  font-size: 18px;
  flex-shrink: 0;
}
.ch-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 5px;
}
.chunk-path {
  margin-bottom: 5px;
  line-height: 1.5;
}
.chunk-body {
  font-size: 12.5px;
  color: var(--el-text-color-regular);
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.relation-box {
  border: 1px solid #f0d9b5;
  border-radius: 8px;
  padding: 11px 12px;
  margin-top: 10px;
  background: #fffbf5;
}
.rel-title {
  font-size: 12.5px;
  font-weight: 600;
  color: #854f0b;
  margin-bottom: 10px;
}
.rel-content {
  font-size: 12px;
  line-height: 1.6;
  margin: 5px 0 5px 10px;
  padding-left: 10px;
  color: var(--el-text-color-secondary);
}
.rel-content.child {
  border-left: 2px solid #f0d9b5;
}
.rel-content.parent {
  border-left: 2px solid var(--el-color-primary-light-5);
}
.rel-arrow {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin: 2px 0 5px 4px;
}
.rel-foot {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.65;
  margin-top: 11px;
  padding-top: 10px;
  border-top: 1px dashed #f0d9b5;
}
.rel-foot b {
  color: #854f0b;
}
.kv {
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 9px 8px;
  font-size: 12.5px;
  margin: 0;
}
.kv dt {
  color: var(--el-text-color-secondary);
}
.kv dd {
  margin: 0;
  min-width: 0;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tip-box {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
  padding: 10px 12px;
  background: #f5f9ff;
  border-radius: 8px;
  font-size: 12px;
  color: #3a6ea5;
  line-height: 1.6;
}

/* 分片视图网格 */
.chunk-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.chunk-grid .chunk-item {
  margin-bottom: 0;
}

/* 时间线 */
.tl-title {
  font-size: 13px;
  font-weight: 500;
}
</style>
