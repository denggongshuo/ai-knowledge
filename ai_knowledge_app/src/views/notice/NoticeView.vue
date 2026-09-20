<script setup lang="ts">
import { ref, computed, onMounted, type Component } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Check,
  Setting,
  InfoFilled,
  WarningFilled,
  Upload,
  Message,
  Lock,
} from '@element-plus/icons-vue'
import { noticeApi } from '@/api'
import type { NoticeItem, NoticeCounts, NoticeSetting, PendingItem } from '@/types'

const router = useRouter()

type Cat = 'all' | 'unread' | 'UPLOAD' | 'ALERT' | 'PERMISSION' | 'SYSTEM' | 'pending'

const TYPE_META: Record<string, { label: string; icon: Component; cls: string }> = {
  UPLOAD: { label: '上传与解析', icon: Upload, cls: 'primary' },
  ALERT: { label: '系统告警', icon: WarningFilled, cls: 'danger' },
  PERMISSION: { label: '权限变更', icon: Lock, cls: 'warning' },
  SYSTEM: { label: '系统消息', icon: Message, cls: 'info' },
}

const loading = ref(false)
const cat = ref<Cat>('all')
const notices = ref<NoticeItem[]>([])
const counts = ref<NoticeCounts | null>(null)
const pendingItems = ref<PendingItem[]>([])
const pendingTotal = ref(0)
const settings = ref<NoticeSetting[]>([])
const settingVisible = ref(false)
const savingSettings = ref(false)
const visibleCount = ref(10)

const CATS = computed(() => [
  { key: 'all' as Cat, label: '全部', count: counts.value?.all ?? 0 },
  { key: 'unread' as Cat, label: '未读', count: counts.value?.unread ?? 0, dot: true },
  { key: 'UPLOAD' as Cat, label: '上传与解析', count: counts.value?.UPLOAD ?? 0 },
  { key: 'ALERT' as Cat, label: '系统告警', count: counts.value?.ALERT ?? 0 },
  { key: 'PERMISSION' as Cat, label: '权限变更', count: counts.value?.PERMISSION ?? 0 },
  { key: 'SYSTEM' as Cat, label: '系统消息', count: counts.value?.SYSTEM ?? 0 },
])

onMounted(async () => {
  loading.value = true
  try {
    const [res, p, s] = await Promise.all([noticeApi.list(), noticeApi.pending(), noticeApi.settings()])
    notices.value = res.list
    counts.value = res.counts
    pendingItems.value = p.list
    pendingTotal.value = p.total
    settings.value = s.list
  } finally {
    loading.value = false
  }
})

/** 类型元信息（模板中避免用 any 索引 Record） */
function typeMeta(type: string) {
  return TYPE_META[type] ?? { label: '通知', icon: Message, cls: 'info' }
}
/** cls → el-tag 的 type */
function tagTypeOf(cls: string): 'primary' | 'danger' | 'warning' | 'info' {
  if (cls === 'primary') return 'primary'
  if (cls === 'danger') return 'danger'
  if (cls === 'warning') return 'warning'
  return 'info'
}

async function selectCat(key: Cat) {
  cat.value = key
  visibleCount.value = 10
  if (key === 'pending') return
  loading.value = true
  try {
    const query: { type?: string; read?: string } = {}
    if (key === 'unread') query.read = 'unread'
    else if (key !== 'all') query.type = key
    const res = await noticeApi.list(query)
    notices.value = res.list
    counts.value = res.counts
  } finally {
    loading.value = false
  }
}

/** 按时间分组（今天 / 昨天 / 更早） */
const grouped = computed(() => {
  const shown = notices.value.slice(0, visibleCount.value)
  const groups: { name: string; items: NoticeItem[] }[] = []
  for (const g of ['今天', '昨天', '更早']) {
    const items = shown.filter((n) => n.group === g)
    if (items.length) groups.push({ name: g, items })
  }
  return groups
})
const hasMore = computed(() => notices.value.length > visibleCount.value)

async function markRead(item: NoticeItem) {
  if (item.read) return
  const res = await noticeApi.read(item.id, true)
  item.read = true
  if (counts.value) counts.value.unread = res.unread
}
async function markAllRead() {
  // 已读可逆，不需要二次确认
  const res = await noticeApi.readAll()
  notices.value.forEach((n) => (n.read = true))
  if (counts.value) counts.value.unread = res.unread
  ElMessage.success(`已将 ${res.updated} 条通知标为已读`)
}
function mute(item: NoticeItem) {
  ElMessage.success('本周不再提醒此类通知')
  void item
}
function goAction(to?: string) {
  if (to) router.push(to)
}
async function saveSettings() {
  savingSettings.value = true
  try {
    const res = await noticeApi.saveSettings(settings.value.map((s) => ({ type: s.type, enabled: s.enabled })))
    ElMessage.success(res.note)
    settingVisible.value = false
  } finally {
    savingSettings.value = false
  }
}
</script>

<template>
  <div class="page notice-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">消息通知</h2>
        <p class="page-desc">异步任务（上传 / 解析 / 向量化 / 重建 / 导出）的结果出口 · 每条通知都带一个可执行动作</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Check" @click="markAllRead">全部标为已读</el-button>
        <el-button :icon="Setting" @click="settingVisible = true">通知设置</el-button>
      </div>
    </div>

    <div class="notice-layout">
      <!-- 左：分类导航 -->
      <div class="cat-side">
        <div
          v-for="c in CATS"
          :key="c.key"
          class="cat-item"
          :class="{ active: cat === c.key }"
          @click="selectCat(c.key)"
        >
          <span>{{ c.label }}</span>
          <span v-if="c.count" class="cat-count" :class="{ dot: c.dot }">{{ c.count }}</span>
        </div>
        <div class="cat-divider" />
        <div class="cat-item warn" :class="{ active: cat === 'pending' }" @click="selectCat('pending')">
          <span class="row-inline"><el-icon><WarningFilled /></el-icon>待我处理</span>
          <span class="cat-count warn-badge">{{ pendingTotal }}</span>
        </div>
      </div>

      <!-- 右：列表 -->
      <el-card v-loading="loading" shadow="never" class="list-card">
        <!-- 待我处理 -->
        <template v-if="cat === 'pending'">
          <div class="sec-title"><el-icon><InfoFilled /></el-icon>需要人工介入的聚合</div>
          <div v-for="p in pendingItems" :key="p.key" class="pending-card">
            <div class="pc-main">
              <div class="pc-head">
                <b>{{ p.label }}</b>
                <el-tag size="small" type="danger">{{ p.count }} 条</el-tag>
              </div>
              <div class="hint">{{ p.desc }}</div>
            </div>
            <el-button type="primary" @click="goAction(p.to)">{{ p.btnText }}</el-button>
          </div>
          <el-alert type="info" :closable="false" show-icon class="mt-12">
            <template #title>
              <span class="alert-text">「待我处理」与「问答运营」页的待处理数**同源**（同一张未命中清单），配合侧边栏角标形成待办感知。</span>
            </template>
          </el-alert>
        </template>

        <!-- 通知列表 -->
        <template v-else>
          <div v-if="!notices.length" class="empty-wrap">
            <el-empty description="暂无通知" :image-size="90" />
          </div>

          <div v-for="g in grouped" :key="g.name" class="group">
            <div class="group-title">{{ g.name }}</div>

            <div
              v-for="n in g.items"
              :key="n.id"
              class="notice-item"
              :class="{ unread: !n.read }"
            >
              <!-- 未读用左侧蓝点，不整行高亮 -->
              <span class="unread-dot" :class="{ on: !n.read }" />
              <div class="ni-icon" :class="typeMeta(n.type).cls">
                <el-icon><component :is="typeMeta(n.type).icon" /></el-icon>
              </div>

              <div class="ni-main">
                <div class="ni-head">
                  <el-tag size="small" :type="tagTypeOf(typeMeta(n.type).cls)">
                    {{ typeMeta(n.type).label }}
                  </el-tag>
                  <b class="ni-title">{{ n.title }}</b>
                  <span class="ni-time mono">{{ n.time }}</span>
                </div>
                <div class="ni-content">{{ n.content }}</div>
                <div class="ni-actions">
                  <el-button
                    v-for="(a, i) in n.actions"
                    :key="i"
                    size="small"
                    :type="a.primary ? 'primary' : 'default'"
                    :plain="!a.primary"
                    @click="goAction(a.to)"
                  >
                    {{ a.label }}
                  </el-button>
                  <el-button v-if="!n.read" size="small" text @click="markRead(n)">标为已读</el-button>
                  <el-button v-if="n.muteable" size="small" text @click="mute(n)">不再提醒</el-button>
                </div>
              </div>
            </div>
          </div>

          <div v-if="hasMore" class="load-more">
            <el-button text type="primary" @click="visibleCount += 10">加载更多（剩余 {{ notices.length - visibleCount }} 条）</el-button>
          </div>
        </template>
      </el-card>
    </div>

    <!-- 通知设置 -->
    <el-drawer v-model="settingVisible" title="通知设置" size="420px">
      <div class="hint mb-12">按类型开关；「系统告警」为强制开启，不可关闭。</div>
      <div v-for="s in settings" :key="s.type" class="setting-row">
        <div class="sr-main">
          <b>{{ s.label }}</b>
          <el-tag v-if="s.forced" size="small" type="danger" class="ml-6">强制开启</el-tag>
          <div class="hint">{{ s.hint }}</div>
        </div>
        <el-switch v-model="s.enabled" :disabled="s.forced" />
      </div>
      <template #footer>
        <el-button @click="settingVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingSettings" @click="saveSettings">保存设置</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.notice-page {
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
.notice-layout {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.cat-side {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 8px;
}
.cat-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 7px;
  font-size: 13px;
  cursor: pointer;
  color: var(--el-text-color-regular);
}
.cat-item:hover {
  background: var(--el-color-primary-light-9);
}
.cat-item.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 500;
}
.cat-item.warn.active {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.cat-count {
  min-width: 20px;
  height: 18px;
  border-radius: 9px;
  background: var(--el-color-info-light-9);
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 18px;
  text-align: center;
  padding: 0 6px;
}
.cat-count.dot {
  background: var(--el-color-primary);
  color: #fff;
}
.cat-count.warn-badge {
  background: var(--el-color-warning);
  color: #fff;
}
.cat-divider {
  height: 1px;
  background: var(--el-border-color-lighter);
  margin: 8px 6px;
}
.row-inline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.list-card {
  min-height: 420px;
}
.sec-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13.5px;
  font-weight: 600;
  margin-bottom: 12px;
}
.pending-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 16px 18px;
  margin-bottom: 12px;
}
.pending-card:first-of-type {
  border-color: #f0d9b5;
  background: #fffbf5;
}
.pc-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.pc-head b {
  font-size: 14px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.mt-12 {
  margin-top: 12px;
}
.mb-12 {
  margin-bottom: 12px;
}
.ml-6 {
  margin-left: 6px;
}
.alert-text {
  font-size: 12.5px;
}
.group {
  margin-bottom: 18px;
}
.group-title {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  letter-spacing: 0.5px;
  margin-bottom: 8px;
  padding-left: 2px;
}
.notice-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 14px 14px 6px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  margin-bottom: 10px;
  transition: border-color 0.16s;
}
.notice-item:hover {
  border-color: var(--el-color-primary-light-5);
}
.unread-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-top: 15px;
  flex: none;
  background: transparent;
}
.unread-dot.on {
  background: var(--el-color-primary);
}
.ni-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  font-size: 17px;
}
.ni-icon.primary {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.ni-icon.danger {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.ni-icon.warning {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.ni-icon.info {
  background: var(--el-color-info-light-9);
  color: var(--el-color-info);
}
.ni-main {
  flex: 1;
  min-width: 0;
}
.ni-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ni-title {
  font-size: 13.5px;
}
.notice-item:not(.unread) .ni-title {
  color: var(--el-text-color-regular);
  font-weight: 500;
}
.ni-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.mono {
  font-family: var(--font-mono);
}
.ni-content {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  margin: 6px 0 10px;
}
.ni-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.load-more {
  text-align: center;
  padding: 8px 0;
}
.empty-wrap {
  padding: 40px 0;
}
.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.sr-main b {
  font-size: 13.5px;
}
</style>
