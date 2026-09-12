<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Delete, WarningFilled, InfoFilled, Document, FolderOpened } from '@element-plus/icons-vue'
import { recycleApi, kbApi } from '@/api'
import type { RecycleItem, RecycleType } from '@/types'

const router = useRouter()

const loading = ref(false)
const tab = ref<RecycleType>('DOC')
const docs = ref<RecycleItem[]>([])
const kbs = ref<RecycleItem[]>([])
const selected = ref<RecycleItem[]>([])
const keepDays = ref(30)
const kbOptions = ref<{ id: number; name: string }[]>([])

// 恢复文档时选择目标库（原库已删除）
const targetVisible = ref(false)
const targetKbId = ref<number>()
const restoring = ref<RecycleItem | null>(null)

const current = computed(() => (tab.value === 'DOC' ? docs.value : kbs.value))

onMounted(async () => {
  loading.value = true
  try {
    const [d, k, kbsRes] = await Promise.all([recycleApi.items({ type: 'DOC' }), recycleApi.items({ type: 'KB' }), kbApi.list()])
    docs.value = d.list
    kbs.value = k.list
    keepDays.value = d.keepDays
    kbOptions.value = ((kbsRes.list as unknown as { id: number; name: string }[]) ?? []).map((x) => ({ id: x.id, name: x.name }))
  } finally {
    loading.value = false
  }
})

/** 剩余天数三档色阶：≥20 绿 / 16~19 橙 / <16 红 */
function remainClass(d: number) {
  if (d >= 20) return 'green'
  if (d >= 16) return 'orange'
  return 'red'
}
function sizeText(b: number) {
  if (b >= 1024 * 1024 * 1024) return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`
  if (b >= 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`
  return `${(b / 1024).toFixed(0)} KB`
}

async function loadAll() {
  const [d, k] = await Promise.all([recycleApi.items({ type: 'DOC' }), recycleApi.items({ type: 'KB' })])
  docs.value = d.list
  kbs.value = k.list
  selected.value = []
}

// ---------------- 恢复 ----------------
async function restoreOne(item: RecycleItem) {
  // 原知识库已删除的文档：需选择恢复到哪个知识库
  if (item.type === 'DOC' && item.originDeleted) {
    restoring.value = item
    targetKbId.value = undefined
    targetVisible.value = true
    return
  }
  await doRestore([item])
}

async function doRestore(items: RecycleItem[]) {
  const isKb = items.some((i) => i.type === 'KB')
  const docCount = items.reduce((s, i) => s + (i.type === 'KB' ? (i.docCount ?? 0) : 1), 0)
  const msg = isKb
    ? `恢复知识库会**连带恢复其下全部文档**（本次共影响 ${docCount} 篇文档）。恢复后需重建索引才能参与检索。确认恢复？`
    : `恢复后需重建索引才能参与检索。${items.some((i) => i.hasNewer) ? '注意：部分条目已有更新版本，恢复旧版本可能造成版本混乱。' : ''}确认恢复 ${items.length} 篇文档？`

  await ElMessageBox.confirm(msg.replace(/\*\*/g, ''), isKb ? '恢复知识库' : '恢复文档', { type: 'warning' })
  const res = await recycleApi.restore({ ids: items.map((i) => i.id), targetKbId: targetKbId.value })
  ElMessage.success(`已恢复 ${res.restored} 项（影响 ${res.affectedDocs} 篇文档），请重建索引以参与检索`)
  targetVisible.value = false
  await loadAll()
}

async function confirmTarget() {
  if (!targetKbId.value) return ElMessage.warning('请选择目标知识库')
  if (restoring.value) await doRestore([restoring.value])
}

async function batchRestore() {
  if (!selected.value.length) return ElMessage.warning('请先选择条目')
  await doRestore(selected.value)
}

// ---------------- 危险操作 ----------------
async function purgeOne(item: RecycleItem) {
  await ElMessageBox.prompt(
    `此操作不可恢复，将同时清理向量数据。请输入${item.type === 'KB' ? '知识库' : '文件'}名称「${item.name}」以确认。`,
    '彻底删除',
    {
      type: 'error',
      inputPlaceholder: item.name,
      inputValidator: (v: string) => v === item.name || '名称不匹配',
      confirmButtonText: '彻底删除',
      confirmButtonClass: 'el-button--danger',
    },
  )
  const res = await recycleApi.remove([item.id])
  ElMessage.success(`已彻底删除（清理 ${res.deleted} 项）`)
  await loadAll()
}

async function batchPurge() {
  if (!selected.value.length) return ElMessage.warning('请先选择条目')
  await ElMessageBox.confirm(`确认彻底删除选中的 ${selected.value.length} 项？此操作不可恢复，将同时清理向量数据。`, '批量彻底删除', {
    type: 'error',
    confirmButtonText: '彻底删除',
    confirmButtonClass: 'el-button--danger',
  })
  const res = await recycleApi.remove(selected.value.map((i) => i.id))
  ElMessage.success(`已彻底删除 ${res.deleted} 项`)
  await loadAll()
}

async function clearAll() {
  await ElMessageBox.prompt('最高危操作：将清空回收站全部内容，且同时清理向量数据，清理后无法恢复。请输入 DELETE 以确认。', '清空回收站', {
    type: 'error',
    inputPlaceholder: 'DELETE',
    inputValidator: (v: string) => v === 'DELETE' || '请输入 DELETE',
    confirmButtonText: '清空回收站',
    confirmButtonClass: 'el-button--danger',
  })
  const res = await recycleApi.clearAll()
  ElMessage.success(`回收站已清空（清理 ${res.cleared} 项）`)
  await loadAll()
}
</script>

<template>
  <div class="page recycle-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">回收站</h2>
        <p class="page-desc">保留期 {{ keepDays }} 天 · 到期由系统自动清理且无法恢复 · <b>恢复后需重建索引</b>才能参与检索</p>
      </div>
      <div class="head-actions">
        <el-button type="danger" plain :icon="Delete" @click="clearAll">清空回收站</el-button>
      </div>
    </div>

    <el-alert type="warning" :closable="false" show-icon class="mb-12">
      <template #title>
        <span class="alert-text">
          误删是高频事故。删除的代价比普通业务系统高得多——不只是记录消失，<b>向量索引也要重算</b>。
          因此删除时不立即清理向量，等超期彻底删除时才清理，避免恢复后重新向量化。
        </span>
      </template>
    </el-alert>

    <el-card shadow="never">
      <el-tabs v-model="tab" @tab-change="selected = []">
        <el-tab-pane name="DOC">
          <template #label><span class="tab-l"><el-icon><Document /></el-icon>文档<span class="tab-num">{{ docs.length }}</span></span></template>
        </el-tab-pane>
        <el-tab-pane name="KB">
          <template #label><span class="tab-l"><el-icon><FolderOpened /></el-icon>知识库<span class="tab-num">{{ kbs.length }}</span></span></template>
        </el-tab-pane>
      </el-tabs>

      <div v-if="selected.length" class="batch-bar">
        <span>已选 {{ selected.length }} 项</span>
        <el-button size="small" type="primary" :icon="Refresh" @click="batchRestore">批量恢复</el-button>
        <el-button size="small" type="danger" :icon="Delete" @click="batchPurge">批量彻底删除</el-button>
      </div>

      <el-table v-loading="loading" :data="current" row-key="id" @selection-change="(v: RecycleItem[]) => (selected = v)">
        <el-table-column type="selection" width="40" />
        <el-table-column :label="tab === 'DOC' ? '文件名' : '知识库名称'" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="name-cell">
              <b>{{ row.name }}</b>
              <el-tag v-if="row.originDeleted" size="small" type="warning">原知识库已删除</el-tag>
              <el-tag v-if="row.hasNewer" size="small" type="danger">已有更新版本</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="原所属知识库" min-width="160">
          <template #default="{ row }">
            <span :class="{ 'muted-italic': row.originDeleted }">{{ row.origin }}</span>
            <span v-if="row.type === 'KB' && row.docCount" class="tiny muted">（含 {{ row.docCount }} 篇文档）</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }"><span class="mono">{{ sizeText(row.size) }}</span></template>
        </el-table-column>
        <el-table-column prop="deletedBy" label="删除人" width="100" />
        <el-table-column label="删除时间" width="150">
          <template #default="{ row }"><span class="tiny muted">{{ row.deletedAt }}</span></template>
        </el-table-column>
        <el-table-column label="剩余保留" width="120" sortable :sort-method="(a: RecycleItem, b: RecycleItem) => a.remainDays - b.remainDays">
          <template #default="{ row }">
            <el-tag size="small" :type="remainClass(row.remainDays) === 'green' ? 'success' : remainClass(row.remainDays) === 'orange' ? 'warning' : 'danger'">
              {{ row.remainDays }} 天
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" :icon="Refresh" @click="restoreOne(row)">恢复</el-button>
            <el-button size="small" text type="danger" :icon="Delete" @click="purgeOne(row)">彻底删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <el-empty :description="tab === 'DOC' ? '回收站里没有文档' : '回收站里没有知识库'" :image-size="90" />
            <div class="hint">删除的{{ tab === 'DOC' ? '文档' : '知识库' }}会出现在这里，保留 {{ keepDays }} 天，期间可随时恢复。</div>
            <el-button type="primary" plain class="mt-12" @click="router.push('/kb')">返回知识库管理</el-button>
          </div>
        </template>
      </el-table>

      <div class="foot-note">
        <el-icon><InfoFilled /></el-icon>
        <span>
          剩余天数 = {{ keepDays }} − （今天 − 删除日）；<b>绿 ≥20 天 / 橙 16~19 天 / 红 &lt;16 天</b>三档表达紧迫度。
          彻底删除与清空回收站都会记入审计日志。
        </span>
      </div>
    </el-card>

    <!-- 恢复文档：选择目标知识库 -->
    <el-dialog v-model="targetVisible" title="选择恢复到的知识库" width="480px">
      <el-alert type="warning" :closable="false" show-icon class="mb-12">
        <template #title>
          <span class="alert-text">原知识库「{{ restoring?.origin }}」已删除，请选择恢复到哪个知识库。恢复后需重建索引。</span>
        </template>
      </el-alert>
      <el-select v-model="targetKbId" placeholder="选择目标知识库" style="width: 100%">
        <el-option v-for="k in kbOptions" :key="k.id" :label="k.name" :value="k.id" />
      </el-select>
      <template #footer>
        <el-button @click="targetVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmTarget">确认恢复</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.recycle-page {
  height: 100%;
  overflow-y: auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
}
.page-desc {
  margin: 0;
}
.page-desc b {
  color: var(--el-color-warning);
}
.head-actions {
  display: flex;
  gap: 10px;
  flex: none;
}
.mb-12 {
  margin-bottom: 12px;
}
.mt-12 {
  margin-top: 12px;
}
.alert-text {
  font-size: 12.5px;
  line-height: 1.6;
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
.name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.muted-italic {
  color: var(--el-text-color-placeholder);
  font-style: italic;
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
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.empty-state {
  padding: 30px 0;
}
.foot-note {
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
</style>
