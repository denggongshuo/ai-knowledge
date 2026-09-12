<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import {
  UploadFilled,
  FolderOpened,
  Collection,
  Refresh,
  Search,
  WarningFilled,
  View,
  Delete,
  InfoFilled,
  Setting,
  Grid,
  Lock,
  Picture,
} from '@element-plus/icons-vue'
import { kbApi, docApi } from '@/api'
import type { UploadItem, KbDocRow, ParseIssue, ParseConfigView } from '@/types'

const route = useRoute()
const router = useRouter()
const kbId = computed(() => String(route.params.id ?? '1'))

const kbName = ref('加载中…')
const queue = ref<UploadItem[]>([])
const docs = ref<KbDocRow[]>([])
const total = ref(0)
const issues = ref<ParseIssue[]>([])
const parseConfig = ref<ParseConfigView | null>(null)
const loading = ref(false)
const selected = ref<KbDocRow[]>([])
const keyword = ref('')
const statusFilter = ref('')
const uploadRef = ref()
const folderInput = ref<HTMLInputElement>()

const ACCEPT = ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'md']
const acceptStr = ACCEPT.map((e) => `.${e}`).join(',')
const MAX_SIZE = 100 * 1024 * 1024
const MAX_COUNT = 20

const timers = new Set<ReturnType<typeof setInterval>>()
let failedOnce = false

// ---------------- 加载 ----------------
async function loadMeta() {
  try {
    const cfg = await kbApi.config(kbId.value)
    kbName.value = cfg.name
  } catch {
    kbName.value = '知识库'
  }
}
async function loadDocs() {
  loading.value = true
  try {
    const res = await kbApi.docs(kbId.value, {
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined,
    })
    docs.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
async function loadIssues() {
  try {
    const res = await kbApi.parseIssues(kbId.value)
    issues.value = res.list
  } catch {
    issues.value = []
  }
}
async function loadParseConfig() {
  try {
    parseConfig.value = await kbApi.parseConfig(kbId.value)
  } catch {
    parseConfig.value = null
  }
}

onMounted(() => {
  loadMeta()
  loadDocs()
  loadIssues()
  loadParseConfig()
})
// 页面切走时暂停/清理轮询
onBeforeUnmount(() => {
  timers.forEach((t) => clearInterval(t))
  timers.clear()
})

// ---------------- 文件校验与入队 ----------------
function extOf(name: string) {
  return name.split('.').pop()?.toLowerCase() ?? ''
}

function onFileChange(file: { name: string; size?: number; raw?: File }) {
  if (!file.raw) return
  enqueue([file.raw])
}

function enqueue(files: File[]) {
  const accepted: File[] = []
  for (const f of files) {
    const ext = extOf(f.name)
    if (!ACCEPT.includes(ext)) {
      ElMessage.warning(`「${f.name}」格式不支持，仅支持 ${ACCEPT.join(' / ').toUpperCase()}`)
      continue
    }
    if (f.size > MAX_SIZE) {
      ElMessage.warning(`「${f.name}」超过 100 MB 上限`)
      continue
    }
    if (queue.value.length + accepted.length >= MAX_COUNT) {
      ElMessage.warning(`单次最多 ${MAX_COUNT} 个文件，其余已忽略`)
      break
    }
    accepted.push(f)
  }
  if (!accepted.length) return
  for (const f of accepted) {
    const item: UploadItem = {
      uid: `u_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
      name: f.name,
      size: f.size,
      type: extOf(f.name).toUpperCase(),
      status: 'UPLOADING',
      percent: 0,
      chunkDone: 0,
      chunkTotal: 0,
      speedKbps: 2200 + Math.floor(Math.random() * 1800),
    }
    queue.value.unshift(item)
    void runItem(item)
  }
  uploadRef.value?.clearFiles?.()
}

/** 模拟：上传中 → 解析中 → 已完成 / 失败 */
async function runItem(item: UploadItem) {
  // 1) 上传中
  await new Promise<void>((resolve) => {
    const t = setInterval(() => {
      item.percent = Math.min(100, item.percent + 7 + Math.floor(Math.random() * 9))
      if (item.percent >= 100) {
        clearInterval(t)
        timers.delete(t)
        resolve()
      }
    }, 110)
    timers.add(t)
  })
  if (!queue.value.includes(item)) return

  // 2) 解析中（含 MD5 去重演示）
  try {
    const dup = await kbApi.checkMd5(kbId.value, { name: item.name, md5: simpleHash(item.name, item.size) })
    item.duplicated = dup.duplicated
  } catch {
    /* 忽略 */
  }

  const up = await kbApi.uploadDoc(kbId.value, { name: item.name, size: item.size, md5: simpleHash(item.name, item.size) })
  item.docId = up.docId
  item.chunkTotal = up.chunkTotal
  item.status = 'PARSING'
  item.percent = 0

  // 特定文件模拟「编码失败」，其余成功
  const willFail = !failedOnce && /\.txt$/i.test(item.name)
  if (willFail) failedOnce = true

  await new Promise<void>((resolve) => {
    const t = setInterval(() => {
      const step = Math.max(1, Math.round(item.chunkTotal / 14))
      item.chunkDone = Math.min(item.chunkTotal, item.chunkDone + step)
      item.percent = Math.round((item.chunkDone / item.chunkTotal) * 100)
      if (item.chunkDone >= item.chunkTotal) {
        clearInterval(t)
        timers.delete(t)
        resolve()
      }
    }, 150)
    timers.add(t)
  })
  if (!queue.value.includes(item)) return

  if (willFail) {
    item.status = 'FAILED'
    item.errorMsg = '文件编码为 GBK，无法按 UTF-8 解码。建议转码后重新上传。'
  } else {
    item.status = 'DONE'
    item.percent = 100
  }
  maybeNotify()
}

/** 简易 hash（演示 MD5 去重） */
function simpleHash(name: string, size: number) {
  const s = `${name}::${size}`
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return h.toString(16).padStart(8, '0')
}

/** 整批完成通知（与告警区分） */
function maybeNotify() {
  const pending = queue.value.filter((q) => q.status === 'UPLOADING' || q.status === 'PARSING')
  if (pending.length) return
  const done = queue.value.filter((q) => q.status === 'DONE').length
  const failed = queue.value.filter((q) => q.status === 'FAILED').length
  if (done + failed === 0) return
  ElNotification({
    title: '上传处理完成',
    message: `${done + failed} 个文件已处理，${done} 成功 / ${failed} 失败`,
    type: failed ? 'warning' : 'success',
  })
  if (failed) {
    ElNotification({
      title: '存在解析失败的文件',
      message: '失败原因已在队列中标出，可修正后重试；失败不会产生脏向量数据。',
      type: 'error',
    })
  }
  loadDocs()
  loadIssues()
}

function retry(item: UploadItem) {
  item.status = 'UPLOADING'
  item.percent = 0
  item.chunkDone = 0
  item.errorMsg = undefined
  void runItem(item)
}
function removeItem(item: UploadItem) {
  queue.value = queue.value.filter((q) => q !== item)
}
function clearDone() {
  queue.value = queue.value.filter((q) => q.status !== 'DONE')
}
function pauseAll() {
  timers.forEach((t) => clearInterval(t))
  timers.clear()
  ElMessage.info('已暂停全部进行中的任务（演示）')
}

function onExceed() {
  ElMessage.warning(`单次最多 ${MAX_COUNT} 个文件，请分批上传`)
}

/** 从文件夹导入（webkitdirectory） */
function pickFolder() {
  folderInput.value?.click()
}
function onFolderPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files ? Array.from(input.files) : []
  const real = files.filter((f) => f.size > 0 && !f.name.startsWith('.'))
  if (!real.length) {
    ElMessage.info('未选择有效文件')
    return
  }
  enqueue(real)
  input.value = ''
}

// ---------------- 文档列表操作 ----------------
function statusTag(s: KbDocRow['status']): { text: string; type: 'success' | 'warning' | 'danger' | 'info' } {
  const map: Record<KbDocRow['status'], { text: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
    READY: { text: '已就绪', type: 'success' },
    INDEXING: { text: '索引中', type: 'warning' },
    PARSING: { text: '解析中', type: 'warning' },
    UPLOADED: { text: '已上传', type: 'info' },
    FAILED: { text: '解析失败', type: 'danger' },
  }
  return map[s]
}
function qualityText(d: KbDocRow) {
  const parts: string[] = []
  let danger = false
  if (d.tableTotal > 0) {
    parts.push(`表格 ${d.tableParsed}/${d.tableTotal}`)
    if (d.tableParsed < d.tableTotal) danger = true
  }
  const it = d.imageTotal ?? 0
  if (it > 0) {
    const ip = d.imageParsed ?? 0
    parts.push(`图片 ${ip}/${it}`)
    if (ip < it) danger = true
  }
  if (!parts.length) return { text: '无表格 / 图片', type: 'info' as const }
  return { text: parts.join(' · ') + (danger ? '（需补录）' : ''), type: danger ? ('danger' as const) : ('success' as const) }
}
function sizeText(b: number) {
  const mb = b / 1024 / 1024
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${(b / 1024).toFixed(0)} KB`
}
function previewDisabled(d: KbDocRow) {
  return d.status === 'INDEXING' || d.status === 'PARSING'
}
function preview(d: KbDocRow) {
  if (previewDisabled(d)) return
  router.push(`/doc/${d.id}/preview`)
}
async function reindex(d: KbDocRow) {
  await docApi.reindex(d.id)
  ElMessage.success(`「${d.title}」已发起重建索引`)
}
async function removeDoc(d: KbDocRow) {
  await ElMessageBox.confirm(`确认删除「${d.title}」？将移入回收站，30 天内可恢复。`, '移入回收站', { type: 'warning' })
  await docApi.remove(d.id)
  ElMessage.success('已移入回收站')
  loadDocs()
}
async function batchRemove() {
  if (!selected.value.length) return
  await ElMessageBox.confirm(`确认删除选中的 ${selected.value.length} 篇文档？将移入回收站。`, '批量删除', { type: 'warning' })
  await docApi.batchRemove(selected.value.map((d) => d.id))
  ElMessage.success('已批量移入回收站')
  selected.value = []
  loadDocs()
}
async function batchReindex() {
  if (!selected.value.length) return
  for (const d of selected.value) await docApi.reindex(d.id)
  ElMessage.success(`已对 ${selected.value.length} 篇文档发起重建索引`)
  selected.value = []
}
function gotoIssue() {
  if (issues.value[0]) router.push(`/doc/${issues.value[0].docId}/preview`)
}
</script>

<template>
  <div class="page upload-page">
    <!-- 页头 -->
    <div class="page-head">
      <div>
        <h2 class="page-title">文档上传</h2>
        <p class="page-desc">当前知识库：<b class="kb-name">{{ kbName }}</b> · 支持批量上传，上传后自动解析并构建向量索引</p>
      </div>
      <div class="head-actions">
        <el-button :icon="FolderOpened" @click="pickFolder">从文件夹导入</el-button>
        <el-button type="primary" :icon="Collection" @click="router.push(`/kb/${kbId}`)">返回知识库</el-button>
      </div>
    </div>

    <input ref="folderInput" type="file" webkitdirectory multiple class="hidden-input" @change="onFolderPicked" />

    <div class="split">
      <!-- 左：上传区 -->
      <div>
        <el-card shadow="never">
          <el-upload
            ref="uploadRef"
            drag
            multiple
            :auto-upload="false"
            :show-file-list="false"
            :accept="acceptStr"
            :limit="MAX_COUNT"
            :on-change="onFileChange"
            :on-exceed="onExceed"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">将文件拖到此处，或 <em>点击选择文件</em></div>
            <template #tip>
              <div class="upload-tip">
                单次最多 20 个文件，单个文件 ≤ 100 MB<br />
                支持格式：PDF · DOC · DOCX · XLS · XLSX · PPT · PPTX · TXT · MD
              </div>
            </template>
          </el-upload>
        </el-card>

        <!-- 上传队列 -->
        <el-card shadow="never" class="queue-card">
          <template #header>
            <div class="card-head-row">
              <div class="row-inline">
                <h3>上传队列</h3>
                <el-tag size="small" type="info">共 {{ queue.length }} 个文件</el-tag>
              </div>
              <div class="row-inline">
                <el-button size="small" @click="pauseAll">全部暂停</el-button>
                <el-button size="small" @click="clearDone">清空已完成</el-button>
              </div>
            </div>
          </template>

          <el-empty v-if="!queue.length" description="暂无上传任务，拖入文件开始" :image-size="70" />

          <div
            v-for="item in queue"
            :key="item.uid"
            class="file-row"
            :class="{ 'row-parsing': item.status === 'PARSING', 'row-failed': item.status === 'FAILED' }"
          >
            <div class="file-ic" :class="'ft-' + item.type.toLowerCase()">{{ item.type }}</div>
            <div class="file-main">
              <div class="row-between">
                <div class="ellipsis file-name">{{ item.name }}</div>
                <el-tag v-if="item.status === 'PARSING'" size="small" type="warning">解析中</el-tag>
                <el-tag v-else-if="item.status === 'DONE'" size="small" type="success">已完成</el-tag>
                <el-tag v-else-if="item.status === 'FAILED'" size="small" type="danger">解析失败</el-tag>
                <span v-else class="mono file-pct">{{ item.percent }}%</span>
              </div>

              <el-progress
                class="file-progress"
                :percentage="item.percent"
                :show-text="false"
                :stroke-width="6"
                :status="item.status === 'DONE' ? 'success' : item.status === 'FAILED' ? 'exception' : undefined"
              />

              <div class="file-sub">
                <template v-if="item.status === 'UPLOADING'">
                  上传中 · {{ sizeText((item.size * item.percent) / 100) }} / {{ sizeText(item.size) }} ·
                  {{ item.speedKbps }} KB/s
                </template>
                <template v-else-if="item.status === 'PARSING'">
                  正在切分文本并生成向量 · 已处理 {{ item.chunkDone }} / {{ item.chunkTotal }} 个分片
                </template>
                <template v-else-if="item.status === 'DONE'">
                  共 {{ sizeText(item.size) }} · 生成 {{ item.chunkTotal }} 个分片
                  <el-tag v-if="item.duplicated" size="small" type="warning" class="dup-tag">同名文件已归档为新版本</el-tag>
                </template>
                <template v-else>
                  <span class="fail-text">{{ item.errorMsg }}</span>
                </template>
              </div>
            </div>

            <div class="file-ops">
              <el-button v-if="item.status === 'FAILED'" size="small" @click="retry(item)">重试</el-button>
              <el-button
                v-else-if="item.status === 'DONE'"
                size="small"
                text
                :icon="View"
                @click="item.docId && router.push(`/doc/${item.docId}/preview`)"
              />
              <el-button size="small" text :icon="Delete" @click="removeItem(item)" />
            </div>
          </div>
        </el-card>
      </div>

      <!-- 右：上传须知 -->
      <div>
        <el-card shadow="never">
          <template #header><h3>上传须知</h3></template>
          <ul class="notice-list">
            <li>上传前按 <code class="mono">用户 + 文件 MD5</code> 去重，重复文件将归档为新版本</li>
            <li>扫描版 PDF 需开启 OCR，解析耗时约为普通文档的 3~5 倍</li>
            <li>含表格的扫描件会做表格结构识别，<b>识别失败的表格会在下方标红提示</b>，不会静默丢弃</li>
            <li>Excel 按 Sheet 切分，每行数据作为一个独立片段</li>
            <li>解析失败可直接重试，不会产生脏向量数据</li>
          </ul>
        </el-card>
      </div>
    </div>

    <!-- 解析配置（全宽只读） -->
    <el-card shadow="never" class="mt-16">
      <template #header>
        <div class="card-head-row">
          <h3>解析配置</h3>
          <div class="row-inline">
            <el-tag size="small" type="info">继承知识库「{{ kbName }}」</el-tag>
            <el-button size="small" :icon="Setting" @click="router.push(`/kb/${kbId}`)">前往知识库配置</el-button>
          </div>
        </div>
      </template>

      <div v-if="parseConfig" class="four-col">
        <div class="cfg-col">
          <div class="cfg-head"><el-icon><Grid /></el-icon><b>分片策略</b></div>
          <div v-for="g in parseConfig.split" :key="g.label" class="cfg-row">
            <span class="cfg-label">{{ g.label }}</span>
            <span class="cfg-value" :class="{ locked: g.locked, warn: g.warning }">
              <el-icon v-if="g.locked"><Lock /></el-icon>{{ g.value }}
            </span>
          </div>
        </div>

        <div class="cfg-col">
          <div class="cfg-head"><el-icon><InfoFilled /></el-icon><b>表格处理</b></div>
          <div v-for="g in parseConfig.table" :key="g.label" class="cfg-row">
            <span class="cfg-label">{{ g.label }}</span>
            <span class="cfg-value" :class="{ locked: g.locked }">
              <el-icon v-if="g.locked"><Lock /></el-icon>{{ g.value }}
            </span>
          </div>
        </div>

        <div class="cfg-col">
          <div class="cfg-head"><el-icon><Picture /></el-icon><b>图片处理</b></div>
          <div v-for="g in parseConfig.image" :key="g.label" class="cfg-row">
            <span class="cfg-label">{{ g.label }}</span>
            <span class="cfg-value" :class="{ locked: g.locked, warn: g.warning }">
              <el-icon v-if="g.locked"><Lock /></el-icon>
              <el-tag v-if="g.enabled" size="small" type="success">已开启</el-tag>
              {{ g.value }}
            </span>
          </div>
        </div>

        <div class="cfg-col">
          <div class="cfg-head"><el-icon><InfoFilled /></el-icon><b>索引增强</b></div>
          <div v-for="g in parseConfig.enhance" :key="g.label" class="cfg-row">
            <span class="cfg-label">{{ g.label }}</span>
            <span class="cfg-value">
              <el-tag v-if="g.enabled" size="small" type="success">已开启</el-tag>
              <el-tag v-else size="small" type="info">未开启</el-tag>
              <span class="cfg-note">{{ g.value }}</span>
            </span>
          </div>
        </div>
      </div>

      <div class="cfg-foot">
        <el-icon><InfoFilled /></el-icon>
        解析配置按知识库维度生效，此处只读；如需为单篇文档覆盖，请上传后在文档详情中调整并重建索引。图片由当前 VL 模型生成描述与检索摘要（描述全程在后端内部流转，前端只读展示），图片整体原子化不做切分。
      </div>
    </el-card>

    <!-- 文档列表 -->
    <el-card shadow="never" class="mt-16">
      <template #header>
        <div class="card-head-row">
          <h3>文档列表 <span class="total-note">共 {{ total }} 篇</span></h3>
          <div class="row-inline">
            <el-input v-model="keyword" placeholder="文件名 / 上传人" :prefix-icon="Search" clearable style="width: 200px" @keyup.enter="loadDocs" @clear="loadDocs" />
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="loadDocs">
              <el-option label="已就绪" value="READY" />
              <el-option label="索引中" value="INDEXING" />
              <el-option label="解析中" value="PARSING" />
              <el-option label="解析失败" value="FAILED" />
            </el-select>
            <el-button :icon="Refresh" @click="loadDocs" />
          </div>
        </div>
      </template>

      <!-- 未识别表格告警条 -->
      <div v-if="issues.length" class="issue-bar">
        <el-icon class="issue-icon"><WarningFilled /></el-icon>
        <span class="issue-text">
          <b>{{ issues.length }} 篇文档存在未识别的表格</b>：{{ issues[0]?.docTitle }} 第 {{ issues[0]?.pageNo }} 页。该表格内的内容无法被检索命中，
          建议改用可复制文本的 PDF，或开启 OCR 后重建索引。
        </span>
        <el-button size="small" @click="gotoIssue">查看详情</el-button>
      </div>

      <!-- 批量操作 -->
      <div v-if="selected.length" class="batch-bar">
        <span>已选 {{ selected.length }} 篇</span>
        <el-button size="small" :icon="Refresh" @click="batchReindex">批量重建索引</el-button>
        <el-button size="small" type="danger" :icon="Delete" @click="batchRemove">批量删除</el-button>
      </div>

      <el-table v-loading="loading" :data="docs" row-key="id" @selection-change="(v: KbDocRow[]) => (selected = v)">
        <el-table-column type="selection" width="40" />
        <el-table-column label="文件名" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="doc-name">
              <span class="dot-type" :class="'ft-' + row.type.toLowerCase()">{{ row.type }}</span>
              <b class="ellipsis">{{ row.title }}</b>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="70" />
        <el-table-column label="大小" width="90">
          <template #default="{ row }"><span class="mono">{{ sizeText(row.size) }}</span></template>
        </el-table-column>
        <el-table-column label="分片数" width="80">
          <template #default="{ row }"><span class="mono">{{ row.chunkCount || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status).type">{{ statusTag(row.status).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="解析质量" width="140">
          <template #default="{ row }">
            <el-tag size="small" :type="qualityText(row).type" :effect="qualityText(row).type === 'danger' ? 'light' : 'plain'">
              {{ qualityText(row).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="owner" label="上传人" width="90" />
        <el-table-column label="上传时间" width="140">
          <template #default="{ row }"><span class="muted">{{ row.uploadedAt }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text :icon="View" :disabled="previewDisabled(row)" @click="preview(row)">预览</el-button>
            <el-button size="small" text :icon="Refresh" @click="reindex(row)">重建</el-button>
            <el-button size="small" text type="danger" :icon="Delete" @click="removeDoc(row)" />
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.upload-page {
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
.kb-name {
  color: var(--el-color-primary);
}
.head-actions {
  display: flex;
  gap: 10px;
  flex: none;
}
.hidden-input {
  display: none;
}
.split {
  display: grid;
  grid-template-columns: minmax(0, 2.4fr) minmax(240px, 1fr);
  gap: 16px;
  align-items: start;
}
.mt-16 {
  margin-top: 16px;
}
.row-inline {
  display: flex;
  align-items: center;
  gap: 8px;
}
.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.card-head-row h3 {
  font-size: 15px;
  font-weight: 600;
  margin: 0;
}
.total-note {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  margin-left: 6px;
}
.upload-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  margin-top: 8px;
}

/* 队列 */
.queue-card {
  margin-top: 16px;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  margin-bottom: 10px;
  transition: background 0.2s;
}
.file-row:last-child {
  margin-bottom: 0;
}
.row-parsing {
  background: #fbfdff;
  border-color: var(--el-color-primary-light-8);
}
.row-failed {
  background: #fffafa;
  border-color: #fde2e2;
}
.file-ic {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  flex: none;
  background: #909399;
}
.ft-pdf {
  background: #f56c6c;
}
.ft-doc,
.ft-docx {
  background: #409eff;
}
.ft-xls,
.ft-xlsx {
  background: #67c23a;
}
.ft-ppt,
.ft-pptx {
  background: #e6a23c;
}
.ft-txt,
.ft-md {
  background: #909399;
}
.file-main {
  flex: 1;
  min-width: 0;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-name {
  font-size: 13.5px;
  font-weight: 500;
}
.file-pct {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.file-progress {
  margin-top: 6px;
}
.file-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 5px;
}
.fail-text {
  color: var(--el-color-danger);
}
.dup-tag {
  margin-left: 6px;
}
.file-ops {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: none;
}

/* 上传须知 */
.notice-list {
  margin: 0;
  padding-left: 16px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  line-height: 2;
}
.notice-list b {
  color: var(--el-color-danger);
}

/* 解析配置（四栏：分片 / 表格 / 图片 / 索引增强） */
.four-col {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 22px;
}
.cfg-head {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 10px;
  color: var(--el-color-primary);
}
.cfg-head b {
  font-size: 13.5px;
  color: var(--el-text-color-primary);
  font-weight: 600;
}
.cfg-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.cfg-row:last-child {
  border-bottom: none;
}
.cfg-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.cfg-value {
  font-size: 12px;
  color: var(--el-text-color-primary);
  text-align: right;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.cfg-value.locked {
  color: var(--el-color-success);
}
.cfg-value.warn {
  color: var(--el-color-warning);
}
.cfg-note {
  display: block;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  line-height: 1.5;
  margin-top: 2px;
}
.cfg-foot {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 16px;
  padding: 10px 12px;
  background: #f5f9ff;
  border-radius: 8px;
  font-size: 12px;
  color: #3a6ea5;
  line-height: 1.6;
}

/* 文档列表 */
.issue-bar {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 12px;
  background: #fffafa;
  border: 1px solid #fde2e2;
  border-radius: 8px;
  margin-bottom: 12px;
}
.issue-icon {
  color: var(--el-color-danger);
  flex: none;
}
.issue-text {
  flex: 1;
  font-size: 12.5px;
  color: #a33;
  line-height: 1.6;
}
.batch-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
  margin-bottom: 12px;
  font-size: 13px;
}
.doc-name {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.dot-type {
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  padding: 1px 5px;
  border-radius: 3px;
  flex: none;
}
.muted {
  color: var(--el-text-color-secondary);
}
</style>
