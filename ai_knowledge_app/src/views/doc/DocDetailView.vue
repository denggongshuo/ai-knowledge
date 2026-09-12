<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  View,
  Download,
  Refresh,
  Delete,
  Plus,
  InfoFilled,
  Collection,
} from '@element-plus/icons-vue'
import { docApi, kbApi } from '@/api'
import type { DocMeta, DocVersion, IndexTask, ChunkStat, SecretLevel } from '@/types'
import { goBack } from '@/utils/nav'

const route = useRoute()
const router = useRouter()
const docId = computed(() => String(route.params.id ?? '1'))

const SECRET_LABEL: Record<SecretLevel, string> = { PUBLIC: '公开', INTERNAL: '内部', CONFIDENTIAL: '机密', SECRET: '绝密' }
const SECRET_ORDER: SecretLevel[] = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET']

const loading = ref(false)
const activeTab = ref('attr')
const meta = ref<DocMeta | null>(null)
const versions = ref<DocVersion[]>([])
const indexTasks = ref<IndexTask[]>([])
const chunkStat = ref<ChunkStat | null>(null)
const kbOptions = ref<{ id: number; name: string }[]>([])

// 可编辑项
const secretLevel = ref<SecretLevel>('INTERNAL')
const targetKbId = ref<number>(1)
const tags = ref<string[]>([])
const newTag = ref('')

onMounted(async () => {
  loading.value = true
  try {
    const [m, v, t, c, kbs] = await Promise.all([
      docApi.meta(docId.value),
      docApi.versionList(docId.value),
      docApi.indexTasks(docId.value),
      docApi.chunkStat(docId.value),
      kbApi.list(),
    ])
    meta.value = m
    versions.value = v.list
    indexTasks.value = t.list
    chunkStat.value = c
    kbOptions.value = ((kbs.list as unknown as { id: number; name: string }[]) ?? []).map((x) => ({ id: x.id, name: x.name }))
    secretLevel.value = m.securityLevel
    targetKbId.value = m.kbId
    tags.value = [...m.tags]
  } finally {
    loading.value = false
  }
})

/** 密级只能提高，不能低于知识库密级 */
const secretOptions = computed(() => {
  const min = meta.value?.kbSecurityLevel ?? 'INTERNAL'
  const minIdx = SECRET_ORDER.indexOf(min)
  return SECRET_ORDER.slice(minIdx)
})

const kbChanged = computed(() => meta.value != null && targetKbId.value !== meta.value.kbId)
const attrDirty = computed(
  () => meta.value != null && (secretLevel.value !== meta.value.securityLevel || kbChanged.value || tags.value.join() !== meta.value.tags.join()),
)

function addTag() {
  const v = newTag.value.trim()
  if (!v) return
  if (tags.value.includes(v)) return ElMessage.warning('标签已存在')
  if (tags.value.length >= 8) return ElMessage.warning('建议控制在 3~8 个，过多会稀释过滤效果')
  tags.value.push(v)
  newTag.value = ''
}
function removeTag(t: string) {
  tags.value = tags.value.filter((x) => x !== t)
}

function sizeText(b: number) {
  return b / 1024 / 1024 >= 1 ? `${(b / 1024 / 1024).toFixed(1)} MB` : `${(b / 1024).toFixed(0)} KB`
}
function qualityOf(m: DocMeta) {
  const parts: string[] = []
  let danger = false
  if (m.tableTotal) {
    parts.push(`表格 ${m.tableParsed}/${m.tableTotal}`)
    if (m.tableParsed < m.tableTotal) danger = true
  }
  if (m.imageTotal) {
    parts.push(`图片 ${m.imageParsed ?? 0}/${m.imageTotal}`)
    if ((m.imageParsed ?? 0) < m.imageTotal) danger = true
  }
  if (!parts.length) return { text: '无表格 / 图片', danger: false }
  return { text: parts.join(' · '), danger }
}

// ---------------- 操作 ----------------
function preview() {
  router.push(`/doc/${docId.value}/preview`)
}
async function download() {
  try {
    const res = await docApi.downloadUrl(docId.value)
    ElMessage.success('已生成下载链接（有效期 10 分钟）')
    window.open(res.url, '_blank')
  } catch {
    ElMessage.error('下载链接生成失败')
  }
}
async function reindex() {
  await docApi.reindex(docId.value)
  ElMessage.success('已发起重新索引，用于修复解析失败的文档')
}
async function saveAttr() {
  if (!meta.value) return
  if (!attrDirty.value) return ElMessage.info('没有需要保存的修改')
  if (kbChanged.value) {
    await ElMessageBox.confirm(
      `移动知识库后需重建索引：将把「${meta.value.title}」从「${meta.value.kbName}」移动到「${kbOptions.value.find((k) => k.id === targetKbId.value)?.name}」。确认继续？`,
      '移动知识库',
      { type: 'warning' },
    )
  }
  await docApi.saveAttributes(docId.value, { secretLevel: secretLevel.value, kbId: targetKbId.value, tags: tags.value })
  meta.value.securityLevel = secretLevel.value
  meta.value.tags = [...tags.value]
  if (kbChanged.value) {
    meta.value.kbId = targetKbId.value
    meta.value.kbName = kbOptions.value.find((k) => k.id === targetKbId.value)?.name ?? meta.value.kbName
  }
  ElMessage.success(kbChanged.value ? '属性已保存，正在重建索引' : '属性已保存，立即生效')
}
function discard() {
  if (!meta.value) return
  secretLevel.value = meta.value.securityLevel
  targetKbId.value = meta.value.kbId
  tags.value = [...meta.value.tags]
  ElMessage.info('已放弃修改')
}

async function rollback(v: DocVersion) {
  await ElMessageBox.confirm(
    `回滚到 ${v.version} 会将历史版本重新向量化并覆盖当前索引，但「历史问答的引用不会改变」。确认回滚？`,
    '版本回滚',
    { type: 'warning' },
  )
  await docApi.rollback(docId.value, v.version)
  ElMessage.success(`已回滚到 ${v.version}（保留一条新版本记录，可追溯）`)
}

function compareVersion(v: DocVersion) {
  ElMessage.info(`版本对比：${v.version} ↔ v3（v1 仅做元数据对比，文本 diff 接口已预留）`)
}

async function removeDoc() {
  try {
    await ElMessageBox.confirm('将移入回收站，30 天内可恢复（恢复后需重建索引）。', '删除文档', {
      distinguishCancelAndClose: true,
      confirmButtonText: '移入回收站',
      cancelButtonText: '彻底删除',
      type: 'warning',
    })
    await docApi.remove(docId.value)
    ElMessage.success('已移入回收站')
    router.push('/kb')
  } catch (action) {
    if (String(action) !== 'cancel') return
    // 彻底删除：二次确认输入文件名
    if (!meta.value) return
    try {
      await ElMessageBox.prompt(`此操作不可恢复，向量数据将被一并清理。请输入文件名「${meta.value.title}」以确认。`, '彻底删除', {
        type: 'error',
        inputPlaceholder: meta.value.title,
        inputValidator: (val: string) => val === meta.value?.title || '文件名不匹配',
        confirmButtonText: '彻底删除',
        confirmButtonClass: 'el-button--danger',
      })
      ElMessage.success('已彻底删除')
      router.push('/kb')
    } catch {
      /* 取消 */
    }
  }
}
</script>

<template>
  <div v-loading="loading" class="page doc-detail">
    <div v-if="meta" class="page-head">
      <div class="head-left">
        <el-button text :icon="ArrowLeft" @click="goBack(router, '/kb')">返回</el-button>
        <div class="file-ic" :class="'ft-' + meta.type.toLowerCase()">{{ meta.type }}</div>
        <div>
          <h2 class="page-title">{{ meta.title }}</h2>
          <div class="head-tags">
            <el-tag size="small" type="success">已就绪</el-tag>
            <el-tag size="small" :type="qualityOf(meta).danger ? 'danger' : 'success'">{{ qualityOf(meta).text }}</el-tag>
            <el-tag size="small" type="info">{{ sizeText(meta.size) }} · {{ meta.pageCount }} 页 · {{ meta.chunkCount }} 分片</el-tag>
            <span class="muted">{{ meta.kbName }}</span>
          </div>
        </div>
      </div>
      <div class="head-actions">
        <el-button :icon="View" @click="preview">在线预览</el-button>
        <el-button :icon="Download" @click="download">下载</el-button>
        <el-button :icon="Refresh" @click="reindex">重新索引</el-button>
        <el-button type="danger" plain :icon="Delete" @click="removeDoc">删除</el-button>
      </div>
    </div>

    <el-card v-if="meta" shadow="never" class="mt-12">
      <el-tabs v-model="activeTab">
        <!-- ============ 属性与标签 ============ -->
        <el-tab-pane name="attr" label="属性与标签">
          <div class="attr-grid">
            <div>
              <div class="sec-title">
                <span class="tag-mini">人工维护</span>
                <b>治理属性</b>
                <span class="muted sm">覆盖知识库默认值</span>
              </div>

              <div class="two">
                <el-form-item label="密级">
                  <el-select v-model="secretLevel" style="width: 100%">
                    <el-option v-for="l in secretOptions" :key="l" :label="l === meta.kbSecurityLevel ? `${SECRET_LABEL[l]}（继承知识库）` : SECRET_LABEL[l]" :value="l" />
                  </el-select>
                  <div class="hint">可单独提高，不能低于知识库密级</div>
                </el-form-item>
                <el-form-item label="所属知识库">
                  <el-select v-model="targetKbId" style="width: 100%">
                    <el-option v-for="k in kbOptions" :key="k.id" :label="k.name" :value="k.id" />
                  </el-select>
                  <div class="hint" :class="{ warn: kbChanged }">{{ kbChanged ? '移动后需重建索引' : '移动后需重建索引' }}</div>
                </el-form-item>
              </div>

              <el-form-item label="内容标签">
                <div class="tag-input-row">
                  <el-input v-model="newTag" placeholder="输入后回车添加标签" @keyup.enter="addTag" />
                  <el-button :icon="Plus" @click="addTag">添加</el-button>
                </div>
                <div class="chip-row mt-8">
                  <el-tag v-for="t in tags" :key="t" closable @close="removeTag(t)">{{ t }}</el-tag>
                  <span v-if="!tags.length" class="hint">暂无标签</span>
                </div>
                <div class="hint">标签会参与检索过滤，建议控制在 3~8 个</div>
              </el-form-item>

              <div class="sec-title mt-16"><el-icon><Collection /></el-icon><b>文档信息</b></div>
              <dl class="kv">
                <dt>文档 ID</dt><dd class="mono">{{ meta.id }}</dd>
                <dt>原文件名</dt><dd class="ellipsis">{{ meta.title }}</dd>
                <dt>文件类型</dt><dd>{{ meta.fileDesc }}</dd>
                <dt>文件大小</dt><dd class="mono">{{ sizeText(meta.size) }}</dd>
                <dt>页数 / 分片</dt><dd class="mono">{{ meta.pageCount }} 页 / {{ meta.chunkCount }} 个分片</dd>
                <dt>其中表格</dt><dd class="mono">{{ meta.tableTotal }} 张 · {{ chunkStat?.tableSubBlocks ?? 88 }} 个子块</dd>
                <dt>其中图片</dt><dd class="mono">{{ meta.imageTotal ?? 0 }} 张 · VL 已描述 {{ meta.imageParsed ?? 0 }} 张</dd>
                <dt>解析质量</dt>
                <dd>
                  <el-tag v-if="qualityOf(meta).danger" size="small" type="danger">{{ qualityOf(meta).text }}，建议重建索引</el-tag>
                  <el-tag v-else size="small" type="success">{{ qualityOf(meta).text }}</el-tag>
                </dd>
                <dt>MD5</dt><dd class="mono ellipsis">{{ meta.md5 }}</dd>
                <dt>存储路径</dt><dd class="mono ellipsis">{{ meta.storagePath }}</dd>
                <dt>上传人</dt><dd>{{ meta.owner }}</dd>
                <dt>上传时间</dt><dd class="mono">{{ meta.uploadedAt }}</dd>
                <dt>最近索引</dt><dd class="mono">{{ meta.lastIndexAt }}</dd>
                <dt>被引用</dt><dd>{{ meta.citations }} 次问答引用 · {{ meta.downVotes }} 次点踩</dd>
              </dl>
            </div>

            <div>
              <!-- 版本历史摘要 -->
              <el-card shadow="never" class="side-card">
                <template #header>
                  <div class="card-head-row">
                    <h3>版本历史</h3>
                    <el-button size="small" text type="primary" @click="activeTab = 'version'">查看全部</el-button>
                  </div>
                </template>
                <div
                  v-for="v in versions"
                  :key="v.version"
                  class="ver-item"
                  :class="{ current: v.current }"
                >
                  <div class="row-between">
                    <div class="row-inline">
                      <b>{{ v.version }}</b>
                      <el-tag v-if="v.current" size="small" type="success">当前</el-tag>
                    </div>
                    <span class="tiny mono muted">{{ v.at.slice(5, 16) }}</span>
                  </div>
                  <div class="tiny muted">{{ v.owner }} · {{ sizeText(v.size) }} · {{ v.chunkCount }} 个分片</div>
                </div>
                <div class="hint mt-8">同名上传默认归档为新版本，不覆盖（避免历史问答引用指向新内容）</div>
              </el-card>

              <!-- 索引记录摘要 -->
              <el-card shadow="never" class="side-card">
                <template #header>
                  <div class="card-head-row">
                    <h3>索引记录</h3>
                    <el-button size="small" text type="primary" @click="activeTab = 'index'">查看全部</el-button>
                  </div>
                </template>
                <div v-for="t in indexTasks.slice(0, 3)" :key="t.id" class="task-row">
                  <span class="dot" :class="t.status.toLowerCase()" />
                  <div class="flex-1">
                    <div class="row-between">
                      <span class="txt">{{ t.stage }}</span>
                      <span class="tiny mono muted">{{ t.at.slice(5, 16) }}</span>
                    </div>
                    <div class="tiny muted">
                      <template v-if="t.durationSec">耗时 {{ t.durationSec }} 秒 · {{ t.chunkCount }} 个分片 · {{ t.version }}</template>
                      <template v-else>{{ t.detail }}</template>
                    </div>
                  </div>
                </div>
              </el-card>
            </div>
          </div>
        </el-tab-pane>

        <!-- ============ 版本历史 ============ -->
        <el-tab-pane name="version">
          <template #label>版本历史<span class="tab-num">{{ versions.length }}</span></template>
          <el-table :data="versions" border>
            <el-table-column prop="version" label="版本" width="100">
              <template #default="{ row }">
                <b>{{ row.version }}</b>
                <el-tag v-if="row.current" size="small" type="success" class="ml-6">当前</el-tag>
                <el-tag v-if="row.hasNewer" size="small" type="warning" class="ml-6">已有更新版本</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="110">
              <template #default="{ row }"><span class="mono">{{ sizeText(row.size) }}</span></template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="分片数" width="90" />
            <el-table-column prop="owner" label="上传人" width="100" />
            <el-table-column prop="at" label="上传时间" width="160" />
            <el-table-column prop="note" label="说明" min-width="160" show-overflow-tooltip />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text @click="compareVersion(row)">对比</el-button>
                <el-button v-if="!row.current" size="small" text type="primary" @click="rollback(row)">回滚</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-alert type="warning" :closable="false" show-icon class="mt-12">
            <template #title>
              <span class="alert-text">回滚会将该版本重新向量化并覆盖当前索引，<b>历史问答的引用不会改变</b>；当前版本无回滚按钮</span>
            </template>
          </el-alert>
        </el-tab-pane>

        <!-- ============ 分片统计 ============ -->
        <el-tab-pane name="chunk" label="分片统计">
          <div v-if="chunkStat" class="stat-grid">
            <div class="stat-card">
              <div class="sc-val">{{ chunkStat.total }}</div>
              <div class="sc-label">分片总数</div>
            </div>
            <div class="stat-card">
              <div class="sc-val">{{ chunkStat.text }}</div>
              <div class="sc-label">正文块</div>
            </div>
            <div class="stat-card warn">
              <div class="sc-val">{{ chunkStat.table }}</div>
              <div class="sc-label">表格块（原子）</div>
            </div>
            <div class="stat-card img">
              <div class="sc-val">{{ chunkStat.image ?? 0 }}</div>
              <div class="sc-label">图片块（原子）</div>
            </div>
            <div class="stat-card">
              <div class="sc-val">{{ chunkStat.summary }}</div>
              <div class="sc-label">摘要块</div>
            </div>
            <div class="stat-card">
              <div class="sc-val">{{ chunkStat.avgTokens }}</div>
              <div class="sc-label">平均 tokens</div>
            </div>
            <div class="stat-card">
              <div class="sc-val">{{ chunkStat.titlePathCoverage }}%</div>
              <div class="sc-label">标题路径覆盖</div>
            </div>
          </div>

          <div v-if="chunkStat" class="dist-block">
            <div class="field-title">按块类型分布</div>
            <div v-for="item in [
              { label: '正文', value: chunkStat.text, color: '#909399' },
              { label: '表格', value: chunkStat.table, color: '#e6a23c' },
              { label: '图片', value: chunkStat.image ?? 0, color: '#70b0f0' },
              { label: '摘要', value: chunkStat.summary, color: '#409eff' },
            ]" :key="item.label" class="dist-row">
              <span class="dist-label">{{ item.label }}</span>
              <div class="dist-bar">
                <i :style="{ width: (item.value / chunkStat.total) * 100 + '%', background: item.color }" />
              </div>
              <span class="dist-val mono">{{ item.value }} · {{ ((item.value / chunkStat.total) * 100).toFixed(1) }}%</span>
            </div>
            <div class="hint mt-8">
              表格 12 个原子块共拆出 {{ chunkStat.tableSubBlocks }} 个子块用于检索（父块用于回表）；
              图片块整体原子化（不做图像切分），其 VL 描述与检索摘要均由当前 VL 模型产出。
              被问到的通常是子块或图片描述，喂给大模型的是完整父块。
            </div>
          </div>
        </el-tab-pane>

        <!-- ============ 索引记录 ============ -->
        <el-tab-pane name="index">
          <template #label>索引记录<span class="tab-num">{{ indexTasks.length }}</span></template>
          <div class="timeline">
            <div v-for="t in indexTasks" :key="t.id" class="tl-row">
              <span class="dot" :class="t.status.toLowerCase()" />
              <div class="flex-1">
                <div class="row-between">
                  <span class="tl-title">{{ t.stage }}</span>
                  <span class="tiny mono muted">{{ t.at }}</span>
                </div>
                <div class="tiny muted">
                  <template v-if="t.durationSec">耗时 {{ t.durationSec }} 秒 · {{ t.chunkCount }} 个分片 · {{ t.version }}</template>
                  <template v-else>{{ t.detail }}</template>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>

      <div class="save-bar">
        <span class="sb-left">
          <el-icon><InfoFilled /></el-icon>
          <span class="tiny muted">属性修改后立即生效，无需重建索引；<b>移动知识库除外</b></span>
        </span>
        <div class="sb-actions">
          <el-button :disabled="!attrDirty" @click="discard">取消</el-button>
          <el-button type="primary" :disabled="!attrDirty" @click="saveAttr">保存属性</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.doc-detail {
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
.file-ic {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
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
.mt-16 {
  margin-top: 16px;
}
.mt-8 {
  margin-top: 8px;
}
.ml-6 {
  margin-left: 6px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.sm {
  font-size: 12px;
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
  margin-top: 4px;
}
.hint.warn {
  color: var(--el-color-warning);
}
.alert-text {
  font-size: 12.5px;
  line-height: 1.6;
}
.attr-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 26px;
  align-items: start;
}
.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 18px;
}
.sec-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 12px;
}
.sec-title b {
  font-size: 13.5px;
  font-weight: 600;
}
.tag-mini {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 4px;
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary-dark-2);
}
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tag-input-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.kv {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 11px 10px;
  font-size: 13px;
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
.side-card {
  margin-bottom: 16px;
}
.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-head-row h3 {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}
.ver-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 11px 12px;
  margin-bottom: 9px;
}
.ver-item.current {
  border-color: var(--el-color-primary-light-5);
  background: #f7fbff;
}
.ver-item b {
  font-size: 13px;
}
.row-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.row-inline {
  display: flex;
  align-items: center;
  gap: 7px;
}
.flex-1 {
  flex: 1;
  min-width: 0;
}
.task-row,
.tl-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 13px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
  margin-top: 5px;
  background: var(--el-color-info);
}
.dot.success {
  background: var(--el-color-success);
}
.dot.running {
  background: var(--el-color-primary);
}
.dot.failed {
  background: var(--el-color-danger);
}
.txt,
.tl-title {
  font-size: 12.5px;
  font-weight: 500;
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

/* 分片统计 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}
.stat-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 14px;
  text-align: center;
}
.stat-card.warn {
  border-color: #f0d9b5;
  background: #fffbf5;
}
.stat-card.img {
  border-color: #cddff5;
  background: #f5f9ff;
}
.sc-val {
  font-size: 22px;
  font-weight: 600;
  font-family: var(--font-mono);
}
.sc-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.dist-block {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 16px;
}
.field-title {
  font-size: 13.5px;
  font-weight: 600;
  margin-bottom: 12px;
}
.dist-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.dist-label {
  width: 50px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.dist-bar {
  flex: 1;
  height: 8px;
  background: var(--el-border-color-extra-light);
  border-radius: 4px;
  overflow: hidden;
}
.dist-bar i {
  display: block;
  height: 100%;
  border-radius: 4px;
}
.dist-val {
  width: 130px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: right;
  flex: none;
}

/* 底部保存条 */
.save-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16px -20px -20px;
  padding: 13px 20px;
  border-top: 1px solid var(--el-border-color-lighter);
  background: #fafbfd;
}
.sb-left {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-color-info);
}
.sb-actions {
  display: flex;
  gap: 10px;
}
</style>
