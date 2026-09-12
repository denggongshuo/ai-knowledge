<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Search, Calendar, Warning, UserFilled, Close, Document, CopyDocument, Right } from '@element-plus/icons-vue'
import { sysApi } from '@/api'

// ---------------- 类型 ----------------
interface AuditRow {
  id: number
  time: string
  operator: string
  operatorName: string
  module: string
  action: string
  riskLevel: 'HIGH' | 'NORMAL'
  target: string
  ip: string
  result: 'SUCCESS' | 'FAILED'
  failReason?: string
}

interface AuditDetail extends AuditRow {
  logNo: string
  traceId: string
  apiPath: string
  costMs: number
  userAgent: string
  affectedUsers: number
  changes?: { label: string; before: string; after: string }[]
  roleName?: string
  note?: string
}

// ---------------- 统计卡 ----------------
const stats = reactive({ today: 247, highRisk: 12, activeUsers: 34, failed: 3 })

// ---------------- 筛选 ----------------
const list = ref<AuditRow[]>([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const moduleFilter = ref('all')
const resultFilter = ref('all')
// 快捷筛选
const quick = ref<'all' | 'high' | 'failed' | 'grant' | 'export' | 'model'>('all')

const dateRange = ref<[string, string] | null>(['2026-09-10', '2026-09-11'])
const shortcuts = [
  { text: '今日', value: () => { const d = new Date(); return [d, d] } },
  { text: '近 7 天', value: () => { const end = new Date(); const start = new Date(end.getTime() - 6 * 86400000); return [start, end] } },
  { text: '本月', value: () => { const d = new Date(); const start = new Date(d.getFullYear(), d.getMonth(), 1); return [start, d] } },
]

const MODULES = ['认证', '用户', '授权', '知识库', '文档', '问答', '系统']

async function loadLogs() {
  loading.value = true
  try {
    const params: Record<string, string | number | undefined> = {
      page: page.value,
      size: pageSize.value,
      keyword: keyword.value || undefined,
      module: moduleFilter.value === 'all' ? undefined : moduleFilter.value,
      result: resultFilter.value === 'all' ? undefined : resultFilter.value,
    }
    if (quick.value === 'high') params.riskLevel = 'HIGH'
    if (quick.value === 'failed') params.result = 'FAILED'
    if (quick.value === 'grant') params.module = '授权'
    if (quick.value === 'export') params.module = '问答'
    if (quick.value === 'model') params.module = '系统'

    const res = await sysApi.auditLogs(params)
    list.value = (res.list as unknown as AuditRow[]) ?? []
    total.value = res.total ?? 0
  } finally {
    loading.value = false
  }
}

function applyQuick(q: 'all' | 'high' | 'failed' | 'grant' | 'export' | 'model') {
  quick.value = q
  page.value = 1
  loadLogs()
}

function search() {
  page.value = 1
  loadLogs()
}

// ---------------- 详情抽屉 ----------------
const drawerVisible = ref(false)
const detail = ref<AuditDetail | null>(null)
const detailLoading = ref(false)

async function openDetail(row: AuditRow) {
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = (await sysApi.auditLogDetail(row.id)) as unknown as AuditDetail
  } finally {
    detailLoading.value = false
  }
}

function copyTrace() {
  if (!detail.value) return
  navigator.clipboard?.writeText(detail.value.traceId).then(
    () => ElMessage.success('已复制 traceId'),
    () => ElMessage.info(`traceId: ${detail.value?.traceId}`),
  )
}

function exportLogs() {
  ElMessage.success('已提交导出任务，完成后将通过通知中心提醒（本次导出本身也已计入审计日志）')
}

// 变更统计
const changeSummary = computed(() => {
  const changes = detail.value?.changes ?? []
  const diffCount = changes.filter((c) => c.before !== c.after).length
  return { total: changes.length, diff: diffCount }
})

const MODULE_TAG: Record<string, string> = {
  认证: 'info',
  用户: '',
  授权: 'warning',
  知识库: 'success',
  文档: '',
  问答: 'primary',
  系统: 'danger',
}

onMounted(async () => {
  const res = await sysApi.auditStats()
  Object.assign(stats, res ?? {})
  loadLogs()
})
</script>

<template>
  <div class="page sys-audit-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">审计日志</h2>
        <p class="page-desc">记录所有关键操作，不可编辑、不可删除，用于事后追溯与合规核查</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Download" @click="exportLogs">导出日志</el-button>
      </div>
    </div>

    <!-- 统计卡 -->
    <div class="stat-grid">
      <div class="stat-card">
        <el-icon class="si"><Document /></el-icon>
        <div class="sv">{{ stats.today }}</div>
        <div class="sl">今日操作</div>
      </div>
      <div class="stat-card">
        <el-icon class="si danger"><Warning /></el-icon>
        <div class="sv">{{ stats.highRisk }}</div>
        <div class="sl">高危操作</div>
      </div>
      <div class="stat-card">
        <el-icon class="si ok"><UserFilled /></el-icon>
        <div class="sv">{{ stats.activeUsers }}</div>
        <div class="sl">活跃操作人</div>
      </div>
      <div class="stat-card">
        <el-icon class="si off"><Close /></el-icon>
        <div class="sv">{{ stats.failed }}</div>
        <div class="sl">失败操作</div>
      </div>
    </div>

    <!-- 日志列表 -->
    <div class="card">
      <div class="card-head">
        <span class="card-title">操作记录</span>
        <span class="sub">保留 180 天</span>
        <div class="head-right">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="~"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :shortcuts="shortcuts"
            value-format="YYYY-MM-DD"
            size="default"
            style="width: 240px"
            @change="search"
          />
          <el-select v-model="moduleFilter" style="width: 120px" @change="search">
            <el-option label="全部模块" value="all" />
            <el-option v-for="m in MODULES" :key="m" :label="m" :value="m" />
          </el-select>
          <el-select v-model="resultFilter" style="width: 116px" @change="search">
            <el-option label="全部结果" value="all" />
            <el-option label="成功" value="SUCCESS" />
            <el-option label="失败" value="FAILED" />
          </el-select>
        </div>
      </div>

      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="操作人 / 对象 / IP" :prefix-icon="Search" clearable style="width: 220px" @change="search" @clear="search" />
        <span class="quick-label">快捷筛选：</span>
        <div class="chips">
          <span class="chip" :class="{ active: quick === 'high' }" @click="applyQuick('high')">仅高危操作</span>
          <span class="chip" :class="{ active: quick === 'failed' }" @click="applyQuick('failed')">仅失败</span>
          <span class="chip" :class="{ active: quick === 'grant' }" @click="applyQuick('grant')">权限变更</span>
          <span class="chip" :class="{ active: quick === 'export' }" @click="applyQuick('export')">数据导出</span>
          <span class="chip" :class="{ active: quick === 'model' }" @click="applyQuick('model')">模型变更</span>
        </div>
      </div>

      <el-table v-loading="loading" :data="list" row-key="id">
        <el-table-column label="时间" width="140">
          <template #default="{ row }"><span class="mono muted">{{ row.time }}</span></template>
        </el-table-column>
        <el-table-column label="操作人" min-width="130">
          <template #default="{ row }">
            <b>{{ row.operatorName }}</b>
            <span class="tiny muted"> · {{ row.operator }}</span>
          </template>
        </el-table-column>
        <el-table-column label="模块" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="(MODULE_TAG[row.module] as 'info' | 'warning' | 'success' | 'primary' | 'danger') || 'info'">
              {{ row.module }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="180">
          <template #default="{ row }">
            <el-tag v-if="row.riskLevel === 'HIGH'" size="small" type="danger" effect="dark" class="risk-tag">高危</el-tag>
            <span>{{ row.action }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="target" label="对象" min-width="170" show-overflow-tooltip />
        <el-table-column label="来源 IP" width="120">
          <template #default="{ row }"><span class="mono">{{ row.ip }}</span></template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small" effect="light" round>
              {{ row.result === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="详情" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-foot">
        <span>共 {{ total }} 条</span>
        <span class="tag-plain">按时间倒序</span>
        <div class="foot-right">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, prev, pager, next, sizes"
            background
            small
            @change="loadLogs"
          />
        </div>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawerVisible" size="520px" :show-close="true" destroy-on-close>
      <div v-loading="detailLoading" class="drawer-body">
        <template v-if="detail">
          <div class="drawer-head">
            <div class="head-tags">
              <el-tag v-if="detail.riskLevel === 'HIGH'" type="danger" size="small" effect="dark">高危操作</el-tag>
              <span class="mono log-no">{{ detail.logNo }}</span>
            </div>
            <h3 class="drawer-title">{{ detail.action }} · {{ detail.target }}</h3>
          </div>

          <!-- 基础信息 -->
          <div class="meta-box">
            <div class="meta-row"><span class="meta-k">操作人</span><span>{{ detail.operatorName }}（{{ detail.operator }}）</span></div>
            <div class="meta-row"><span class="meta-k">操作时间</span><span class="mono">{{ detail.time }}</span></div>
            <div class="meta-row"><span class="meta-k">来源 IP</span><span class="mono">{{ detail.ip }}（内网）</span></div>
            <div class="meta-row"><span class="meta-k">客户端</span><span class="mono">{{ detail.userAgent }}</span></div>
            <div class="meta-row">
              <span class="meta-k">结果</span>
              <el-tag :type="detail.result === 'SUCCESS' ? 'success' : 'danger'" size="small">{{ detail.result === 'SUCCESS' ? '成功' : `失败：${detail.failReason ?? ''}` }}</el-tag>
            </div>
          </div>

          <!-- 变更明细 -->
          <template v-if="detail.changes && detail.changes.length">
            <div class="section-label">变更明细</div>
            <div class="diff-table">
              <div class="diff-head">
                <span>变更项</span><span style="width: 90px">变更前</span><span style="width: 90px">变更后</span>
              </div>
              <div v-for="(c, i) in detail.changes" :key="i" class="diff-row">
                <span class="diff-label">{{ c.label }}</span>
                <span class="diff-cell">
                  <span v-if="c.before !== '—'" class="level-tag read">{{ c.before }}</span>
                  <span v-else class="none-tag">—</span>
                </span>
                <span class="diff-cell">
                  <span v-if="c.after !== '—'" class="level-tag write">{{ c.after }}</span>
                  <span v-else class="none-tag">—</span>
                </span>
              </div>
            </div>
            <div class="diff-summary">共 {{ changeSummary.total }} 项，其中 <b>{{ changeSummary.diff }} 项发生变更</b>，{{ changeSummary.total - changeSummary.diff }} 项保持不变</div>
          </template>

          <div class="divider-line"></div>

          <!-- 技术信息 -->
          <div class="section-label">技术信息</div>
          <dl class="tech-list">
            <dt>traceId</dt><dd class="mono ellipsis">{{ detail.traceId }}</dd>
            <dt>接口</dt><dd class="mono ellipsis">{{ detail.apiPath }}</dd>
            <dt>耗时</dt><dd class="mono">{{ detail.costMs }} ms</dd>
            <template v-if="detail.affectedUsers">
              <dt>影响用户</dt><dd>{{ detail.affectedUsers }} 人{{ detail.roleName ? `（${detail.roleName}下全部成员）` : '' }}</dd>
            </template>
          </dl>

          <div v-if="detail.note" class="risk-note">
            <el-icon><Warning /></el-icon>
            <span class="tiny">{{ detail.note }}</span>
          </div>

          <div class="drawer-foot">
            <el-button :icon="CopyDocument" @click="copyTrace">复制 traceId</el-button>
            <el-button
              v-if="detail.roleName"
              type="primary"
              :icon="Right"
              @click="ElMessage.info('已跳转到该角色当前权限配置（演示）')"
            >查看该角色当前权限</el-button>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.sys-audit-page {
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

/* 统计 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
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
.stat-card .si.danger {
  background: #fef0f0;
  color: var(--el-color-danger);
}
.stat-card .si.ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.stat-card .si.off {
  background: var(--el-color-info-light-9);
  color: var(--el-color-info);
}
.stat-card .sv {
  font-size: 22px;
  font-weight: 600;
}
.stat-card .sl {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 卡片 */
.card {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  flex-wrap: wrap;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
}
.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.head-right {
  margin-left: auto;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

/* 筛选条 */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  flex-wrap: wrap;
}
.quick-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 4px;
}
.chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.chip {
  font-size: 12px;
  color: var(--el-text-color-regular);
  background: var(--el-bg-color-page);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  padding: 3px 12px;
  cursor: pointer;
  transition: all 0.15s;
}
.chip:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.chip.active {
  background: var(--el-color-primary);
  border-color: var(--el-color-primary);
  color: #fff;
}

/* 表格 */
.mono {
  font-family: var(--font-mono);
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.tiny {
  font-size: 12px;
}
.risk-tag {
  margin-right: 5px;
}
.table-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.foot-right {
  margin-left: auto;
}
.tag-plain {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-color-info-light-9);
  padding: 1px 8px;
  border-radius: 4px;
}

/* 抽屉 */
.drawer-body {
  padding: 4px 6px;
  min-height: 200px;
}
.drawer-head {
  margin-bottom: 16px;
}
.head-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.log-no {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.drawer-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0;
}
.meta-box {
  background: var(--el-bg-color-page);
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 18px;
}
.meta-row {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12.5px;
  padding: 4px 0;
}
.meta-k {
  width: 66px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.section-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  letter-spacing: 0.4px;
  margin-bottom: 10px;
}
.diff-table {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 9px;
}
.diff-head,
.diff-row {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  font-size: 12.5px;
}
.diff-head {
  background: var(--el-bg-color-page);
  color: var(--el-text-color-secondary);
  font-weight: 500;
}
.diff-head span:first-child,
.diff-row span:first-child {
  flex: 1;
}
.diff-row {
  border-top: 1px solid var(--el-border-color-lighter);
}
.diff-label {
  flex: 1;
}
.diff-cell {
  width: 90px;
  text-align: center;
}
.level-tag {
  display: inline-block;
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 4px;
}
.level-tag.read {
  background: var(--el-color-info-light-9);
  color: var(--el-text-color-secondary);
}
.level-tag.write {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.none-tag {
  color: var(--el-text-color-placeholder);
}
.diff-summary {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.divider-line {
  height: 1px;
  background: var(--el-border-color-lighter);
  margin: 18px 0;
}
.tech-list {
  display: grid;
  grid-template-columns: 78px minmax(0, 1fr);
  gap: 9px 8px;
  margin: 0;
  font-size: 12.5px;
}
.tech-list dt {
  color: var(--el-text-color-secondary);
}
.tech-list dd {
  margin: 0;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.risk-note {
  display: flex;
  gap: 9px;
  padding: 11px 13px;
  background: var(--el-color-warning-light-9);
  border-radius: 8px;
  color: #8a6d3b;
  margin-top: 16px;
}
.risk-note .el-icon {
  color: var(--el-color-warning);
  flex: none;
  margin-top: 2px;
}
.risk-note .tiny {
  line-height: 1.7;
}
.drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 22px;
}
</style>