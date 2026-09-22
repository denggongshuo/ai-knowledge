<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Download, Refresh, Files, FolderOpened, Clock, Document } from '@element-plus/icons-vue'
import { docApi, kbApi } from '@/api'
import type { KbDocRow, DownloadTask, DownloadRecord, StorageQuota } from '@/types'

type DocRow = KbDocRow & { kbId: number; kbName: string }

const activeTab = ref('files')
const loading = ref(false)

const docs = ref<DocRow[]>([])
const total = ref(0)
const selected = ref<DocRow[]>([])
const keyword = ref('')
const kbFilter = ref('all')
const typeFilter = ref('all')
const kbOptions = ref<{ id: number; name: string }[]>([])

const tasks = ref<DownloadTask[]>([])
const records = ref<DownloadRecord[]>([])
const recordTotal = ref(0)
const quota = ref<StorageQuota | null>(null)

const PAGE_SIZE = 10
const recordPage = ref(1)

/** 下载任务四态配置 */
const TASK_STATUS: Record<DownloadTask['status'], { text: string; type: 'primary' | 'success' | 'danger' | 'warning' }> = {
  PACKING: { text: '打包中', type: 'primary' },
  DONE: { text: '已完成', type: 'success' },
  EXPIRED: { text: '链接已过期', type: 'warning' },
  FAILED: { text: '失败', type: 'danger' },
}

/** 下载权限说明（必须与 04/05 页口径一致） */
const PERMISSION_ROWS = [
  { role: '普通用户', scope: '仅本人', download: '只读 · 仅已授权知识库' },
  { role: '知识库管理员', scope: '本部门及以下', download: '可上传 · 本部门及以下的知识库' },
  { role: '审计员', scope: '全部数据', download: '只读 · 全部知识库' },
  { role: '超级管理员', scope: '全部数据', download: '可管理 · 全部知识库' },
]

const MAX_BATCH_COUNT = 50
const MAX_BATCH_SIZE = 2 * 1024 * 1024 * 1024 // 2 GB

onMounted(async () => {
  loading.value = true
  try {
    const [kbs, q] = await Promise.all([kbApi.list(), kbApi.storageQuota()])
    kbOptions.value = ((kbs.list as unknown as { id: number; name: string }[]) ?? []).map((x) => ({ id: x.id, name: x.name }))
    quota.value = q
    await Promise.all([loadDocs(), loadTasks(), loadRecords()])
    // 进页面时可能已经有在打包的任务（比如上次离开时提交的）
    startPolling()
  } catch {
    // ⚠️ **文件列表**走的是 `GET /doc/all`（跨库列表，`T-612`）—— 它后端尚未实现，
    //    会以 404 抛出。**不该让它把整页拖垮**：下载任务与下载记录这两块是本批
    //    （`S7-M06-E`）的能力，必须在文件列表拿不到数据时**仍然可用**。
    //    （`request.ts` 的 `warnUnimplemented` 已经为这个接口留了一条降噪日志。）
  } finally {
    loading.value = false
  }
})

async function loadDocs() {
  const res = await docApi.all({
    keyword: keyword.value || undefined,
    kbId: kbFilter.value,
    type: typeFilter.value,
  })
  docs.value = res.list
  total.value = res.total
}
async function loadTasks() {
  const res = await docApi.downloadTasks()
  tasks.value = res.list
}
async function loadRecords() {
  const res = await docApi.downloadRecords({ page: recordPage.value, size: PAGE_SIZE })
  records.value = res.list
  recordTotal.value = res.total
}

/**
 * 本地判定「链接已过期」。
 *
 * 🔴 后端**不会**下发 `EXPIRED`：它每次读都现签一个新地址（对象名由 userId+taskId 推出，
 * 任务结果不落库也照样算得出），所以"读到的这一刻"地址必然有效。
 * 能过期的只有**已经拿到手、还没用完**的那个地址 —— 那是客户端才知道的事，只能在这边判。
 */
function displayStatus(t: DownloadTask): DownloadTask['status'] {
  if (t.status !== 'DONE' || !t.expiresAt) return t.status
  // `yyyy-MM-dd HH:mm:ss` 在部分浏览器里 Date 解析不出来，把日期分隔符换成 `/`
  // （它认得的那种形态）。这是**展示级**的降级：判不出来就按 DONE 显示，不做假判断。
  const at = new Date(t.expiresAt.replace(/-/g, '/')).getTime()
  if (Number.isNaN(at)) return t.status
  return at < Date.now() ? 'EXPIRED' : 'DONE'
}

function sizeText(b: number) {
  if (b >= 1024 * 1024 * 1024) return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`
  if (b >= 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`
  return `${(b / 1024).toFixed(0)} KB`
}

const selectedSize = computed(() => selected.value.reduce((s, d) => s + d.size, 0))

// ---------------- 下载 ----------------
async function downloadOne(d: DocRow) {
  try {
    const res = await docApi.downloadUrl(d.id)
    ElMessage.success('已生成下载链接（预签名 URL · 有效期 10 分钟）')
    window.open(res.url, '_blank')
    loadRecords()
  } catch {
    ElMessage.error('下载失败')
  }
}

async function batchDownload() {
  if (!selected.value.length) return ElMessage.warning('请先选择要下载的文档')
  if (selected.value.length > MAX_BATCH_COUNT) {
    return ElMessage.warning(`单次最多 ${MAX_BATCH_COUNT} 个文件，请分批下载（当前 ${selected.value.length} 个）`)
  }
  if (selectedSize.value > MAX_BATCH_SIZE) {
    return ElMessage.warning(`单次最多 2 GB，当前约 ${sizeText(selectedSize.value)}，请分批下载`)
  }
  await ElMessageBox.confirm(
    `将异步打包 ${selected.value.length} 个文件（共 ${sizeText(selectedSize.value)}），完成后通过通知中心提醒。确认继续？`,
    '批量下载',
    { type: 'info' },
  )
  const res = await docApi.batchDownload(selected.value.map((d) => d.id))
  ElMessage.success(`打包任务已创建（${res.taskId}），可在「下载任务」中查看进度`)
  selected.value = []
  activeTab.value = 'tasks'
  await loadTasks()
  startPolling()
}

/**
 * 重新生成下载地址（需求 `06` §5.8「预签名 URL 过期后给出重新生成入口，不是死路」）。
 *
 * 🔴 它是**真的重新拉一次任务详情**，不是前端伪造一个新 URL：
 * 后端每次读都现签，所以"重签"在这个系统里就等于"重读"。
 * ⚠️ 之前这里是一段 `setTimeout` 造的假象（把本地 status 改成 DONE、expiresAt 写死一句文案），
 * 那会让用户以为拿到了地址，点下去却是 404。
 */
async function regenerate(t: DownloadTask) {
  try {
    const fresh = await docApi.downloadTask(t.taskId)
    Object.assign(t, fresh)
    if (fresh.status === 'DONE' && fresh.fileUrl) {
      ElMessage.success('已重新生成下载链接（有效期 10 分钟）')
    } else if (fresh.status === 'DONE') {
      ElMessage.warning('任务已完成，但暂时取不到下载地址，请稍后重试')
    } else {
      ElMessage.info(`任务当前状态：${TASK_STATUS[fresh.status].text}`)
    }
  } catch {
    ElMessage.error('重新获取下载地址失败')
  }
}

/** 打开打包产物。地址过期时不硬跳（那只会得到一个 404 页面），提示去重新生成。 */
function openTaskFile(t: DownloadTask) {
  if (displayStatus(t) !== 'DONE' || !t.fileUrl) {
    ElMessage.warning('下载地址已不可用，请点「重新生成」获取新地址')
    return
  }
  window.open(t.fileUrl, '_blank')
}

async function exportRecords() {
  try {
    const res = await docApi.exportList()
    ElMessage.success(res.note || `导出任务已创建（${res.taskId}），完成后将通过通知中心提醒`)
  } catch {
    ElMessage.error('导出任务创建失败，请稍后重试')
  }
}

// ---------------- 打包进度：**真轮询**，不是模拟推进 ----------------
//
// 🔴 之前这里有一段 `setInterval` 在本地给 percent 加 3、加到 100 就把状态改成 DONE ——
// 那是**凭空的进度**：真实任务可能早就失败了，页面上却在稳稳地走到 100%。
// 现在只做一件事：任务里还有 PACKING 时，每 3 秒**拉一次真实任务列表**。
let pollTimer: ReturnType<typeof setInterval> | undefined
const POLL_MS = 3000

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (!tasks.value.some((t) => t.status === 'PACKING')) {
      stopPolling()
      return
    }
    try {
      await loadTasks()
    } catch {
      // 轮询失败**不弹错**（网络抖一下不该刷一屏红字），下一轮再试
    }
  }, POLL_MS)
}

onBeforeUnmount(stopPolling)
</script>

<template>
  <div v-loading="loading" class="page download-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">文档下载</h2>
        <p class="page-desc">跨知识库统一查找并下载原始文档 · 当前可见文档共 {{ total.toLocaleString() }} 篇</p>
      </div>
      <div class="head-actions">
        <el-button :icon="FolderOpened">从文件夹导入</el-button>
      </div>
    </div>

    <div class="layout">
      <!-- 主区 -->
      <el-card shadow="never">
        <el-tabs v-model="activeTab">
          <!-- ============ 文件列表 ============ -->
          <el-tab-pane name="files">
            <template #label><span class="tab-l"><el-icon><Files /></el-icon>文件列表</span></template>

            <div class="filter-bar">
              <el-input v-model="keyword" placeholder="文件名 / 上传人" :prefix-icon="Search" clearable style="width: 220px" @keyup.enter="loadDocs" @clear="loadDocs" />
              <el-select v-model="kbFilter" placeholder="全部知识库" style="width: 180px" @change="loadDocs">
                <el-option label="全部知识库" value="all" />
                <el-option v-for="k in kbOptions" :key="k.id" :label="k.name" :value="String(k.id)" />
              </el-select>
              <el-select v-model="typeFilter" placeholder="全部类型" style="width: 130px" @change="loadDocs">
                <el-option label="全部类型" value="all" />
                <el-option v-for="t in ['PDF', 'DOCX', 'XLSX', 'PPTX', 'MD', 'TXT']" :key="t" :label="t" :value="t" />
              </el-select>
              <el-button :icon="Refresh" @click="loadDocs">刷新</el-button>
              <div class="flex-spacer" />
              <el-button v-if="selected.length" type="primary" :icon="Download" @click="batchDownload">
                批量下载（{{ selected.length }}）
              </el-button>
            </div>

            <el-table :data="docs" row-key="id" @selection-change="(v: DocRow[]) => (selected = v)">
              <el-table-column type="selection" width="40" />
              <el-table-column label="文件名" min-width="260" show-overflow-tooltip>
                <template #default="{ row }">
                  <div class="doc-name">
                    <span class="dot-type" :class="'ft-' + row.type.toLowerCase()">{{ row.type }}</span>
                    <div class="doc-line">
                      <b class="ellipsis">{{ row.title }}</b>
                      <span class="tiny muted">分片 {{ row.chunkCount }}</span>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="kbName" label="所属知识库" width="160" show-overflow-tooltip />
              <el-table-column label="大小" width="100">
                <template #default="{ row }"><span class="mono">{{ sizeText(row.size) }}</span></template>
              </el-table-column>
              <el-table-column prop="owner" label="上传人" width="100" />
              <el-table-column label="上传时间" width="150">
                <template #default="{ row }"><span class="muted tiny">{{ row.uploadedAt }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" text type="primary" :icon="Download" @click="downloadOne(row)">下载</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="selected.length" class="sel-hint">
              已选 {{ selected.length }} 个 · 共 {{ sizeText(selectedSize) }}（单次上限 50 个 / 2 GB）
            </div>
          </el-tab-pane>

          <!-- ============ 下载任务 ============ -->
          <el-tab-pane name="tasks">
            <template #label><span class="tab-l"><el-icon><Download /></el-icon>下载任务<span class="tab-num">{{ tasks.length }}</span></span></template>

            <div v-for="t in tasks" :key="t.taskId" class="task-card" :class="displayStatus(t).toLowerCase()">
              <div class="tc-head">
                <div class="row-inline">
                  <el-icon><Document /></el-icon>
                  <b>{{ t.name }}</b>
                  <span class="tiny muted">
                    （{{ t.count }} 个文件<span v-if="t.size > 0"> · {{ sizeText(t.size) }}</span>）
                  </span>
                </div>
                <el-tag size="small" :type="TASK_STATUS[displayStatus(t)].type">{{ TASK_STATUS[displayStatus(t)].text }}</el-tag>
              </div>

              <el-progress
                v-if="displayStatus(t) === 'PACKING'"
                :percentage="t.percent"
                :stroke-width="6"
                :show-text="false"
                class="tc-progress"
              />
              <div class="tc-sub">
                <template v-if="displayStatus(t) === 'PACKING'">打包中 · {{ t.percent }}%</template>
                <template v-else-if="displayStatus(t) === 'DONE'">已完成 · {{ sizeText(t.size) }} · 链接有效期 10 分钟</template>
                <template v-else-if="displayStatus(t) === 'EXPIRED'">下载链接已过期，点「重新生成」获取新地址</template>
                <template v-else>{{ t.errorMsg }}</template>
                <span class="tiny muted">{{ t.createdAt }}</span>
              </div>

              <div class="tc-actions">
                <el-button v-if="displayStatus(t) === 'DONE'" size="small" type="primary" :icon="Download" @click="openTaskFile(t)">
                  重新下载
                </el-button>
                <el-button v-if="displayStatus(t) === 'EXPIRED'" size="small" :icon="Refresh" @click="regenerate(t)">重新生成</el-button>
                <el-button v-if="displayStatus(t) === 'FAILED'" size="small" :icon="Refresh" @click="regenerate(t)">重试</el-button>
              </div>
            </div>
            <el-empty v-if="!tasks.length" description="暂无下载任务" :image-size="80" />
          </el-tab-pane>

          <!-- ============ 下载记录 ============ -->
          <el-tab-pane name="records">
            <template #label><span class="tab-l"><el-icon><Clock /></el-icon>下载记录</span></template>

            <div class="filter-bar">
              <div class="flex-spacer" />
              <el-button :icon="Download" @click="exportRecords">导出记录</el-button>
            </div>

            <el-table :data="records" border>
              <el-table-column label="文件 / 批次" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">
                  <b>{{ row.docTitle }}</b>
                </template>
              </el-table-column>
              <el-table-column prop="kbName" label="知识库" width="160" />
              <el-table-column label="类型" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.type === 'BATCH' ? 'warning' : 'info'">
                    {{ row.type === 'BATCH' ? '批量' : '单文件' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="大小" width="100">
                <template #default="{ row }"><span class="mono">{{ sizeText(row.size) }}</span></template>
              </el-table-column>
              <el-table-column prop="operator" label="操作人" width="110" />
              <el-table-column prop="ip" label="IP" width="130">
                <template #default="{ row }"><span class="mono tiny">{{ row.ip }}</span></template>
              </el-table-column>
              <el-table-column prop="at" label="下载时间" width="160" />
            </el-table>
            <div class="pager">
              <el-pagination
                v-model:current-page="recordPage"
                :page-size="PAGE_SIZE"
                :total="recordTotal"
                layout="prev, pager, next, total"
                @current-change="loadRecords"
              />
            </div>
            <div class="hint mt-8">
              下载记录与审计日志是**两份**留痕：这一份是我自己的下载历史（可导出），
              审计日志记录的是「谁在何时做了什么」（在审计页查看）。
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-card>

      <!-- 右侧辅助区 -->
      <div class="side">
        <el-card shadow="never" class="side-card">
          <template #header><h3>存储用量</h3></template>
          <div v-if="quota" class="quota">
            <div class="quota-main">
              <span class="qm-val">{{ quota.used }}</span>
              <span class="qm-label">已用存储</span>
            </div>
            <div class="quota-grid">
              <div class="qg-item"><b>{{ quota.kbCount }}</b><span>知识库</span></div>
              <div class="qg-item"><b>{{ quota.docCount.toLocaleString() }}</b><span>文档</span></div>
              <div class="qg-item"><b>{{ quota.chunkCount.toLocaleString() }}</b><span>向量分片</span></div>
              <div class="qg-item"><b>{{ (quota.monthTokens / 10000).toFixed(1) }} 万</b><span>本月索引 tokens</span></div>
            </div>
          </div>
        </el-card>

        <el-card shadow="never" class="side-card">
          <template #header><h3>下载权限说明</h3></template>
          <table class="perm-table">
            <thead>
              <tr><th>角色</th><th>下载范围</th></tr>
            </thead>
            <tbody>
              <tr v-for="r in PERMISSION_ROWS" :key="r.role">
                <td>
                  <b>{{ r.role }}</b>
                  <div class="tiny muted">{{ r.scope }}</div>
                </td>
                <td class="tiny">{{ r.download }}</td>
              </tr>
            </tbody>
          </table>
          <div class="hint mt-8">下载前必须二次校验知识库权限，不能只依赖前端按钮隐藏</div>
        </el-card>

        <el-card shadow="never" class="side-card">
          <template #header>
            <div class="card-head-row">
              <h3>最近任务</h3>
              <el-button size="small" text type="primary" @click="activeTab = 'tasks'">查看全部</el-button>
            </div>
          </template>
          <div v-for="t in tasks.slice(0, 3)" :key="t.taskId" class="mini-task">
            <div class="row-between">
              <span class="tiny ellipsis">{{ t.name }}</span>
              <el-tag size="small" :type="TASK_STATUS[displayStatus(t)].type">{{ TASK_STATUS[displayStatus(t)].text }}</el-tag>
            </div>
          </div>
        </el-card>

        <el-alert type="info" :closable="false" show-icon>
          <template #title>
            <span class="alert-text">
              单文件下载走对象存储<b>预签名 URL</b>（有效期 10 分钟），不经过业务服务器；打包下载为异步任务。
            </span>
          </template>
        </el-alert>
      </div>
    </div>
  </div>
</template>

<style scoped>
.download-page {
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
.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
}
.side {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.side-card :deep(.el-card__header) {
  padding: 12px 16px;
}
.side-card h3 {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}
.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
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
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.flex-spacer {
  flex: 1;
}
.row-inline {
  display: flex;
  align-items: center;
  gap: 6px;
}
.row-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
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
.mt-8 {
  margin-top: 8px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.alert-text {
  font-size: 12px;
  line-height: 1.6;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-name {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
.doc-line {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.dot-type {
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  padding: 1px 5px;
  border-radius: 3px;
  flex: none;
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
.sel-hint {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

/* 任务卡 */
.task-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 12px;
}
.task-card.packing {
  border-color: var(--el-color-primary-light-5);
  background: #f7fbff;
}
.task-card.done {
  border-color: var(--el-color-success-light-9);
}
.task-card.expired {
  border-color: #fde2e2;
  background: #fffafa;
}
.task-card.failed {
  border-color: #fde2e2;
  background: #fffafa;
}
.tc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.tc-head b {
  font-size: 13.5px;
}
.tc-progress {
  margin-bottom: 8px;
}
.tc-sub {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tc-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}

/* 用量 */
.quota-main {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 12px;
}
.qm-val {
  font-size: 24px;
  font-weight: 600;
  font-family: var(--font-mono);
}
.qm-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.quota-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.qg-item {
  display: flex;
  flex-direction: column;
  padding: 8px 10px;
  background: #f7f9fc;
  border-radius: 6px;
}
.qg-item b {
  font-size: 14px;
  font-family: var(--font-mono);
}
.qg-item span {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

/* 权限表 */
.perm-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.perm-table th {
  text-align: left;
  color: var(--el-text-color-secondary);
  font-weight: 500;
  padding: 6px 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.perm-table td {
  padding: 8px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  vertical-align: top;
}
.perm-table tr:last-child td {
  border-bottom: none;
}
.perm-table b {
  font-size: 12.5px;
}

.mini-task {
  padding: 7px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.mini-task:last-child {
  border-bottom: none;
}
</style>
