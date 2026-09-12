<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Check, Search, Plus, Close, Collection, Warning, Right, Back, InfoFilled, Download, UserFilled, Clock } from '@element-plus/icons-vue'
import { sysApi } from '@/api'

// ==================== 类型 ====================
interface MatrixRole {
  id: number
  code: string
  name: string
  builtin: boolean
}
interface MatrixItem {
  key: string
  label: string
  group: string
}
/** 档位：MANAGE / WRITE / READ / NONE */
type Level = 'MANAGE' | 'WRITE' | 'READ' | 'NONE'
interface MatrixRow extends MatrixItem {
  /** 每角色档位，索引对应 roles */
  levels: Level[]
  origin: Level[]
}

interface KbGrantRow {
  kbId: number
  name: string
  department: string
  docCount: number
  updatedAt: string
  roles: { code: string; name: string }[]
  level: 'MANAGE' | 'UPLOAD' | 'READ'
}

const LEVEL_META: Record<Level, { label: string; cls: string }> = {
  MANAGE: { label: '管理', cls: 'lv-manage' },
  WRITE: { label: '读写', cls: 'lv-write' },
  READ: { label: '只读', cls: 'lv-read' },
  NONE: { label: '—', cls: 'lv-none' },
}

// ==================== 状态 ====================
const activeTab = ref<'matrix' | 'kb' | 'user'>('matrix')

const roles = ref<MatrixRole[]>([])
const items = ref<MatrixRow[]>([])
const dirty = ref(false)
const pendingChanges = ref<{ roleId: number; roleName: string; key: string; label: string; before: Level; after: Level }[]>([])
const matrixLoading = ref(false)
const matrixSavedAt = ref('2026-09-11 14:02')
const matrixSavedBy = ref('林工')

const stats = reactive({ users: 128, kbs: 8, changes7d: 12 })

// 权限粒度筛选
const levelFilter = ref<'all' | Level>('all')
const filteredItems = computed(() => {
  if (levelFilter.value === 'all') return items.value
  return items.value.filter((it) => it.levels.some((lv) => lv === levelFilter.value))
})

// 当前矩阵修改的粒度按钮
const GRANULARITY = [
  { label: '管理', value: 'MANAGE' as Level },
  { label: '读写', value: 'WRITE' as Level },
  { label: '只读', value: 'READ' as Level },
  { label: '无', value: 'NONE' as Level },
]

const groupRows = computed(() => {
  const groups: { name: string; rows: MatrixRow[] }[] = []
  for (const it of filteredItems.value) {
    const g = groups.find((x) => x.name === it.group)
    if (g) g.rows.push(it)
    else groups.push({ name: it.group, rows: [it] })
  }
  return groups
})

async function loadMatrix() {
  matrixLoading.value = true
  try {
    const res = await sysApi.grantMatrix()
    roles.value = res.roles ?? []
    const levels: Record<string, string[]> = res.levels ?? {}
    items.value = (res.items ?? []).map((it) => ({
      ...it,
      levels: roles.value.map((r) => (levels[it.key]?.[r.id - 1] as Level) ?? 'NONE'),
      origin: [],
    }))
    // origin 深拷贝
    items.value.forEach((it) => (it.origin = [...it.levels]))
    dirty.value = false
    pendingChanges.value = []
    matrixSavedAt.value = res.updatedAt ?? matrixSavedAt.value
    matrixSavedBy.value = res.updatedBy ?? matrixSavedBy.value
  } finally {
    matrixLoading.value = false
  }
}

function clickCell(row: MatrixRow, roleIdx: number) {
  const role = roles.value[roleIdx]!
  // 超级管理员列锁定（避免误操作导致系统不可用）
  if (role.builtin && role.code === 'SUPER_ADMIN') {
    ElMessage.warning('超级管理员列已锁定，避免误操作导致系统不可用')
    return
  }
  const order: Level[] = ['MANAGE', 'WRITE', 'READ', 'NONE']
  const cur = row.levels[roleIdx]!
  const next = order[(order.indexOf(cur) + 1) % order.length]!
  row.levels[roleIdx] = next
  dirty.value = true
  pendingChanges.value.push({
    roleId: role.id,
    roleName: role.name,
    key: row.key,
    label: row.label,
    before: cur,
    after: next,
  })
}

function setRowGranularity(row: MatrixRow, level: Level) {
  roles.value.forEach((r, i) => {
    if (r.builtin && r.code === 'SUPER_ADMIN') return // 超管列保持锁定
    const cur = row.levels[i]!
    if (cur !== level) {
      row.levels[i] = level
      dirty.value = true
      pendingChanges.value.push({
        roleId: r.id,
        roleName: r.name,
        key: row.key,
        label: row.label,
        before: cur,
        after: level,
      })
    }
  })
}

function resetMatrix() {
  items.value.forEach((it) => {
    it.levels = [...it.origin]
  })
  dirty.value = false
  pendingChanges.value = []
  ElMessage.info('已还原为最近保存的授权')
}

async function saveMatrix() {
  if (pendingChanges.value.length === 0) {
    dirty.value = false
    return
  }
  try {
    await ElMessageBox.confirm(
      `共 ${pendingChanges.value.length} 项变更，保存后立即生效，已登录用户需刷新页面才能获取最新权限。\n\n` +
        pendingChanges.value
          .slice(0, 8)
          .map((c) => `· ${c.roleName}：${c.label} ${LEVEL_META[c.before]?.label ?? '—'} → ${LEVEL_META[c.after]?.label}`)
          .join('\n') +
        (pendingChanges.value.length > 8 ? `\n...等 ${pendingChanges.value.length} 项` : ''),
      '保存授权变更',
      { type: 'warning', confirmButtonText: '保存授权', cancelButtonText: '继续编辑', dangerouslyUseHTMLString: false },
    )
  } catch {
    return
  }
  const changes = pendingChanges.value.map((c) => ({ roleId: c.roleId, permKey: c.key, level: c.after }))
  await sysApi.saveMatrix(changes)
  ElMessage.success(`已保存 ${changes.length} 项授权变更，并写入审计日志`)
  dirty.value = false
  pendingChanges.value = []
  matrixSavedAt.value = new Date().toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
  matrixSavedBy.value = '林工'
}

// ==================== 知识库授权 ====================
const kbGrants = ref<KbGrantRow[]>([])
const kbTotal = ref(0)
const kbPage = ref(1)
const kbPageSize = ref(8)
const kbKeyword = ref('')
const kbLoading = ref(false)
const grantDrawer = ref(false)
const grantSaving = ref(false)
const grantForm = reactive({
  kbId: 0,
  name: '',
  department: '',
  docCount: 0,
  roleIds: [] as number[],
  level: 'READ' as 'READ' | 'UPLOAD' | 'MANAGE',
  extraUsers: [] as { userId: number; name: string }[],
})
const allRolesForGrant = ref<{ id: number; name: string; memberCount: number }[]>([])
const extraSearch = ref('')
const searchResult = ref<{ userId: number; name: string; username: string }[]>([])

const GRANT_LEVELS: { value: 'READ' | 'UPLOAD' | 'MANAGE'; label: string; desc: string }[] = [
  { value: 'READ', label: '只读', desc: '可问答 / 预览 / 下载' },
  { value: 'UPLOAD', label: '可上传', desc: '只读 + 上传文档' },
  { value: 'MANAGE', label: '可管理', desc: '上传 + 删除 + 重建索引' },
]

const levelTagType: Record<string, 'success' | 'warning' | 'info'> = {
  READ: 'info',
  UPLOAD: 'success',
  MANAGE: 'warning',
}

async function loadKbGrants() {
  kbLoading.value = true
  try {
    const res = await sysApi.kbGrants({ page: kbPage.value, pageSize: kbPageSize.value })
    kbGrants.value = (res.list as unknown as KbGrantRow[]) ?? []
    kbTotal.value = res.total ?? 0
  } finally {
    kbLoading.value = false
  }
}

const filteredKbGrants = computed(() => {
  const kw = kbKeyword.value.trim().toLowerCase()
  if (!kw) return kbGrants.value
  return kbGrants.value.filter((k) => k.name.toLowerCase().includes(kw))
})

async function openGrantDrawer(row: KbGrantRow) {
  Object.assign(grantForm, {
    kbId: row.kbId,
    name: row.name,
    department: row.department,
    docCount: row.docCount,
    roleIds: [],
    level: row.level || 'READ',
    extraUsers: [],
  })
  grantDrawer.value = true
  const [detail, rolesRes] = await Promise.all([
    sysApi.kbGrantDetail(row.kbId),
    sysApi.roles({}),
  ])
  grantForm.roleIds = (detail.roles as { roleId: number; granted: boolean }[])
    .filter((r) => r.granted)
    .map((r) => r.roleId)
  grantForm.extraUsers = (detail.extraUsers as { userId: number; name: string }[]) ?? []
  allRolesForGrant.value = (rolesRes.list as unknown as { id: number; name: string; memberCount: number }[]) ?? []
}

function searchExtraUsers() {
  const kw = extraSearch.value.trim().toLowerCase()
  if (!kw) {
    searchResult.value = []
    return
  }
  searchResult.value = [
    { userId: 101, name: '张伟', username: 'zhang.wei' },
    { userId: 104, name: '李娜', username: 'li.na' },
    { userId: 105, name: '王芳', username: 'wang.fang' },
  ]
    .filter((u) => u.name.toLowerCase().includes(kw) || u.username.toLowerCase().includes(kw))
    .filter((u) => !grantForm.extraUsers.some((e) => e.userId === u.userId))
}

function addExtraUser(u: { userId: number; name: string }) {
  grantForm.extraUsers.push(u)
  extraSearch.value = ''
  searchResult.value = []
}

async function saveGrant() {
  if (grantForm.roleIds.length === 0 && grantForm.extraUsers.length === 0) {
    ElMessage.warning('请至少配置一个可见角色或额外授权用户')
    return
  }
  grantSaving.value = true
  try {
    await sysApi.saveKbGrant(grantForm.kbId, {
      roleIds: grantForm.roleIds,
      level: grantForm.level,
      extraUserIds: grantForm.extraUsers.map((u) => u.userId),
    })
    ElMessage.success(`已保存「${grantForm.name}」授权，变更写入审计日志`)
    grantDrawer.value = false
    loadKbGrants()
  } finally {
    grantSaving.value = false
  }
}

// ==================== 用户角色分配 ====================
const transferVisible = ref(false)
const transferData = ref<{ key: number; label: string }[]>([])
const transferTarget = ref<number[]>([])
async function openUserRoleTransfer() {
  transferVisible.value = true
  const res = await sysApi.users({ page: 1, size: 200 })
  const rows = (res.list as unknown as { id: number; realName: string; username: string; roles: string[] }[]) ?? []
  transferData.value = rows.map((r) => ({ key: r.id, label: `${r.realName}（${r.username}）` }))
  transferTarget.value = rows.filter((r) => r.roles.includes('KB_ADMIN')).map((r) => r.id)
}

async function saveTransfer() {
  ElMessage.success(`已更新 ${transferTarget.value.length} 名用户为知识库管理员（演示）`)
  transferVisible.value = false
}

onMounted(async () => {
  await Promise.all([loadMatrix(), loadKbGrants()])
})
</script>

<template>
  <div class="page sys-grant-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">授权管理</h2>
        <p class="page-desc">按角色分配功能权限，按知识库分配数据权限，两者叠加后生效</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" :disabled="!dirty" @click="resetMatrix">重置为默认</el-button>
        <el-button type="primary" :icon="Check" :disabled="!dirty" @click="saveMatrix">保存授权</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="grant-tabs">
      <!-- ============ Tab 1 角色权限矩阵 ============ -->
      <el-tab-pane label="角色权限矩阵" name="matrix">
        <div class="card">
          <div class="matrix-toolbar">
            <div class="granularity-bar">
              <span class="tiny muted">权限粒度：</span>
              <el-radio-group v-model="levelFilter" size="small">
                <el-radio-button value="all">全部</el-radio-button>
                <el-radio-button v-for="g in GRANULARITY" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
              </el-radio-group>
            </div>
            <div class="matrix-hints">
              <span class="tag-plain">点单元格直接循环切换四档：管理 → 读写 → 只读 → 无</span>
              <span class="tag-plain">共 12 项功能项 × {{ roles.length }} 个角色（由 38 个原子权限码聚合）</span>
            </div>
          </div>

          <div v-loading="matrixLoading" class="matrix-wrap">
            <table class="matrix-table">
              <thead>
                <tr>
                  <th class="col-func">功能权限</th>
                  <th v-for="r in roles" :key="r.id">
                    <div class="role-th">
                      <span>{{ r.name }}</span>
                      <span v-if="r.builtin" class="builtin-mark">内置</span>
                    </div>
                  </th>
                </tr>
              </thead>
              <tbody>
                <template v-for="g in groupRows" :key="g.name">
                  <tr class="group-row"><td colspan="100">{{ g.name }}</td></tr>
                  <tr v-for="row in g.rows" :key="row.key">
                    <td class="col-func">
                      <span class="func-label">{{ row.label }}</span>
                    </td>
                    <td
                      v-for="(r, ri) in roles"
                      :key="r.id"
                      class="cell"
                      @click="clickCell(row, ri)"
                    >
                      <span class="level-chip" :class="[LEVEL_META[row.levels[ri]!]?.cls, { locked: r.builtin && r.code === 'SUPER_ADMIN' }]">
                        {{ LEVEL_META[row.levels[ri]!]?.label }}
                      </span>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>

          <div class="matrix-foot">
            <span class="changed-tip" v-if="pendingChanges.length">
              <el-icon><InfoFilled /></el-icon>
              有 {{ pendingChanges.length }} 项未保存的授权变更
            </span>
            <span v-else>
              <el-icon><Warning style="color: var(--el-color-warning)" /></el-icon>
              修改授权后立即生效，已登录用户需刷新页面才能获取最新权限
            </span>
            <span class="saved-at">最近保存：{{ matrixSavedAt }} · {{ matrixSavedBy }}</span>
          </div>
        </div>

        <!-- 统计卡 -->
        <div class="stat-grid">
          <div class="stat-card">
            <el-icon class="si"><UserFilled /></el-icon>
            <div class="sv">{{ stats.users }}</div>
            <div class="sl">已授权用户</div>
          </div>
          <div class="stat-card">
            <el-icon class="si p"><Collection /></el-icon>
            <div class="sv">{{ stats.kbs }}</div>
            <div class="sl">已配置授权知识库</div>
          </div>
          <div class="stat-card">
            <el-icon class="si o"><Clock /></el-icon>
            <div class="sv">{{ stats.changes7d }}</div>
            <div class="sl">近 7 天授权变更</div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ Tab 2 知识库访问授权 ============ -->
      <el-tab-pane :label="`知识库访问授权（${kbTotal}）`" name="kb">
        <div class="card">
          <div class="card-head">
            <span class="card-title">知识库可见性配置</span>
            <div class="head-right">
              <el-input v-model="kbKeyword" placeholder="知识库名称" :prefix-icon="Search" clearable style="width: 200px" />
            </div>
          </div>
          <div class="card-body">
            <el-table v-loading="kbLoading" :data="filteredKbGrants" row-key="kbId">
              <el-table-column label="知识库" min-width="190">
                <template #default="{ row }">
                  <b class="kb-name"><el-icon class="kb-ico"><Collection /></el-icon>{{ row.name }}</b>
                </template>
              </el-table-column>
              <el-table-column prop="department" label="归属部门" width="110" />
              <el-table-column label="可见角色" min-width="230">
                <template #default="{ row }">
                  <el-tag v-for="r in row.roles" :key="r.code" size="small" effect="plain" class="role-tag">{{ r.name }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="权限级别" width="110">
                <template #default="{ row }">
                  <el-tag :type="levelTagType[row.level]" size="small" effect="light">
                    {{
                      row.level === 'MANAGE' ? '可管理' : row.level === 'UPLOAD' ? '可上传' : '只读'
                    }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="docCount" label="文档数" width="90" />
              <el-table-column prop="updatedAt" label="更新时间" width="150">
                <template #default="{ row }"><span class="muted">{{ row.updatedAt }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openGrantDrawer(row)">调整授权</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ Tab 3 用户角色分配 ============ -->
      <el-tab-pane label="用户角色分配" name="user">
        <div class="card">
          <div class="card-body transfer-body">
            <p class="transfer-lead">
              使用穿梭框将已有用户挂到角色上。角色决定功能权限与数据范围；知识库授权决定可见范围，三者取交集后生效。
            </p>
            <el-button type="primary" :icon="Right" @click="openUserRoleTransfer">打开用户角色分配</el-button>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 调整授权抽屉 ============ -->
    <el-drawer
      v-model="grantDrawer"
      :title="`调整授权 · ${grantForm.name}`"
      size="440px"
      destroy-on-close
    >
      <div class="drawer-sub">{{ grantForm.docCount }} 篇文档 · 归属 {{ grantForm.department }}</div>

      <div class="drawer-field">
        <label class="drawer-label">可见角色 <span class="req">*</span></label>
        <el-checkbox-group v-model="grantForm.roleIds" class="role-check-list">
          <el-checkbox
            v-for="r in allRolesForGrant"
            :key="r.id"
            :value="r.id"
            class="role-check-item"
          >
            <span>{{ r.name }}</span>
            <span class="member-count">{{ r.memberCount }} 人</span>
          </el-checkbox>
        </el-checkbox-group>
      </div>

      <div class="drawer-field">
        <label class="drawer-label">权限级别 <span class="req">*</span></label>
        <el-radio-group v-model="grantForm.level" class="level-radio-list">
          <el-radio v-for="lv in GRANT_LEVELS" :key="lv.value" :value="lv.value" class="level-radio-item" border>
            <div class="level-radio-inner">
              <b>{{ lv.label }}</b>
              <span class="level-desc">{{ lv.desc }}</span>
            </div>
          </el-radio>
        </el-radio-group>
      </div>

      <div class="drawer-field">
        <label class="drawer-label">额外授权用户</label>
        <el-select
          v-model="extraSearch"
          filterable
          remote
          :remote-method="searchExtraUsers"
          placeholder="搜索用户姓名 / 用户名"
          style="width: 100%"
          @change="(v: string) => {
            const hit = searchResult.find((u) => String(u.userId) === String(v) || u.name === v || u.username === v)
            if (hit) { addExtraUser({ userId: hit.userId, name: hit.name }); }
          }"
        >
          <el-option v-for="u in searchResult" :key="u.userId" :label="`${u.name}（${u.username}）`" :value="u.username" />
        </el-select>
        <div class="extra-tags">
          <el-tag v-for="u in grantForm.extraUsers" :key="u.userId" closable size="small" class="extra-tag" @close="grantForm.extraUsers = grantForm.extraUsers.filter((x) => x.userId !== u.userId)">
            {{ u.name }}
          </el-tag>
        </div>
        <div class="hint">额外授权用户不受角色限制，单独放行</div>
      </div>

      <div class="drawer-field">
        <label class="drawer-label">生效范围</label>
        <div class="scope-tags">
          <span class="tag-plain"><el-icon><Search /></el-icon>向量检索</span>
          <span class="tag-plain"><el-icon><Collection /></el-icon>原文预览</span>
          <span class="tag-plain"><el-icon><Download /></el-icon>文件下载</span>
        </div>
      </div>

      <div class="warn-box">
        <el-icon><Warning /></el-icon>
        <span class="tiny">取消授权后，该角色成员在问答中不再检索到本知识库内容，已产生的历史回答不受影响。</span>
      </div>

      <template #footer>
        <el-button @click="grantDrawer = false">取消</el-button>
        <el-button type="primary" :loading="grantSaving" @click="saveGrant">保存授权</el-button>
      </template>
    </el-drawer>

    <!-- 用户角色分配穿梭框 -->
    <el-dialog v-model="transferVisible" title="用户角色分配（知识库管理员）" width="520px">
      <el-transfer
        v-model="transferTarget"
        :data="transferData"
        filterable
        filter-placeholder="搜索用户"
        :titles="['候选用户', '已授权用户']"
        style="justify-content: center"
      />
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" @click="saveTransfer">保存分配</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sys-grant-page {
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
.grant-tabs {
}
.card {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  margin-bottom: 16px;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.card-title {
  font-size: 14px;
  font-weight: 600;
}
.head-right {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.card-body {
  padding: 16px;
}
.tag-plain {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-color-info-light-9);
  padding: 2px 8px;
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* ---- 矩阵 ---- */
.matrix-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  flex-wrap: wrap;
}
.granularity-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.matrix-hints {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.matrix-wrap {
  overflow-x: auto;
  min-height: 320px;
}
.matrix-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.matrix-table th,
.matrix-table td {
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 9px 10px;
  text-align: center;
}
.matrix-table thead th {
  background: var(--el-bg-color-page);
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.col-func {
  text-align: left !important;
  width: 30%;
  color: var(--el-text-color-primary);
}
.func-label {
  font-weight: 500;
}
.role-th {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
}
.builtin-mark {
  font-size: 10px;
  color: var(--el-color-warning);
  border: 1px solid var(--el-color-warning);
  border-radius: 3px;
  padding: 0 3px;
  line-height: 1.4;
}
.group-row td {
  background: #f7f9fc;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
  text-align: left;
  padding: 6px 12px;
  letter-spacing: 0.4px;
}
.cell {
  cursor: pointer;
}
.cell:hover {
  background: var(--el-color-primary-light-9);
}
.level-chip {
  display: inline-block;
  min-width: 44px;
  padding: 2px 10px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
}
.level-chip.locked {
  cursor: not-allowed;
  opacity: 0.9;
}
.lv-manage {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.lv-write {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.lv-read {
  background: var(--el-color-info-light-9);
  color: var(--el-text-color-secondary);
}
.lv-none {
  background: transparent;
  color: var(--el-text-color-placeholder);
}

/* 矩阵底部 */
.matrix-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  color: var(--el-color-warning);
}
.changed-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--el-color-warning);
  font-weight: 500;
}
.saved-at {
  margin-left: auto;
  color: var(--el-text-color-secondary);
}

/* 统计卡 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
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
.stat-card .si.p {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.stat-card .si.o {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.stat-card .sv {
  font-size: 22px;
  font-weight: 600;
}
.stat-card .sl {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 知识库授权 */
.kb-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.kb-ico {
  color: var(--el-color-primary);
}
.role-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

/* 穿梭框 */
.transfer-body {
  text-align: center;
  padding: 32px 16px;
}
.transfer-lead {
  max-width: 560px;
  margin: 0 auto 18px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}

/* 抽屉 */
.drawer-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 18px;
}
.drawer-field {
  margin-bottom: 20px;
}
.drawer-label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 8px;
}
.req {
  color: var(--el-color-danger);
}
.role-check-list {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
  width: 100%;
}
.role-check-item {
  width: 100%;
  margin-right: 0;
  padding: 5px 0;
}
.member-count {
  float: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.level-radio-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.level-radio-item {
  width: 100%;
  height: 46px;
  margin-right: 0;
}
.level-radio-item :deep(.el-radio__label) {
  width: 100%;
}
.level-radio-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 6px;
}
.level-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.extra-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.scope-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.warn-box {
  display: flex;
  gap: 9px;
  padding: 11px 13px;
  background: var(--el-color-warning-light-9);
  border-radius: 8px;
  font-size: 12px;
  color: #8a6d3b;
  line-height: 1.7;
}
.warn-box .el-icon {
  color: var(--el-color-warning);
  margin-top: 2px;
}
</style>