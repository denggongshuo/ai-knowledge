<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus,
  Upload,
  Download,
  Search,
  User,
  Refresh,
  Key,
  Promotion,
  SwitchButton,
  MoreFilled,
  OfficeBuilding,
  UserFilled,
  CircleCheck,
  CircleClose,
  Clock,
  Message,
} from '@element-plus/icons-vue'
import { sysApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

/** 部门树节点（来自接口） */
interface DeptRow {
  id: number
  name: string
  parentId: number | null
  userCount: number
  children?: DeptRow[]
}

/** 用户行（接口返回） */
interface UserRow {
  id: number
  username: string
  realName: string
  phone: string
  email?: string
  department: string
  deptId: number
  roles: string[]
  roleNames: string[]
  status: 'ACTIVE' | 'PENDING' | 'DISABLED'
  createdAt: string
  lastLoginAt?: string
  mustChangePassword?: boolean
  hasBusinessData?: boolean
}

interface RoleBrief {
  id: number
  name: string
  code: string
  memberCount: number
  enabled?: boolean
}

const auth = useAuthStore()

// ---------------- 统计 ----------------
const stats = reactive({ total: 128, active: 121, pending: 5, disabled: 2 })

// ---------------- 部门树 ----------------
const deptTree = ref<DeptRow[]>([])
const currentDept = ref<number | 'all'>(0)
const treeData = computed(() => {
  const all = { id: 0, name: '全部用户', parentId: null as number | null, userCount: stats.total, children: deptTree.value }
  return [all]
})

// ---------------- 用户列表 ----------------
const list = ref<UserRow[]>([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const roleFilter = ref('all')
const statusFilter = ref('all')
const roles = ref<RoleBrief[]>([])
const selected = ref<UserRow[]>([])

async function loadUsers() {
  loading.value = true
  try {
    const res = await sysApi.users({
      deptId: currentDept.value === 0 ? 'all' : currentDept.value,
      keyword: keyword.value || undefined,
      roleId: roleFilter.value,
      status: statusFilter.value,
      page: page.value,
      size: pageSize.value,
    })
    list.value = (res.list as unknown as UserRow[]) ?? []
    total.value = res.total ?? 0
  } finally {
    loading.value = false
  }
}

function selectDept(id: number) {
  currentDept.value = id
  page.value = 1
  selected.value = []
  loadUsers()
}

function search() {
  page.value = 1
  loadUsers()
}

// ---------------- 名称/头像 ----------------
const AVATAR_GRADS = [
  'linear-gradient(135deg,#409eff,#2f6fe4)',
  'linear-gradient(135deg,#67c23a,#3fb27f)',
  'linear-gradient(135deg,#e6a23c,#f0a020)',
  'linear-gradient(135deg,#6f7cff,#9b6bff)',
  'linear-gradient(135deg,#909399,#6b6e73)',
]
function avatarBg(u: UserRow) {
  return AVATAR_GRADS[u.id % AVATAR_GRADS.length]!
}
function avatarChar(u: UserRow) {
  return u.realName ? u.realName[0]! : '?'
}

const STATUS_TAG: Record<string, { type: 'success' | 'warning' | 'info'; label: string }> = {
  ACTIVE: { type: 'success', label: '正常' },
  PENDING: { type: 'warning', label: '待激活' },
  DISABLED: { type: 'info', label: '已禁用' },
}

// ---------------- 新建 / 编辑弹窗 ----------------
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const submitting = ref(false)
const form = reactive({
  name: '',
  username: '',
  phone: '',
  email: '',
  deptId: 11,
  roleIds: [] as string[],
  inviteMode: 'INVITE' as 'INVITE' | 'PASSWORD',
  initialPassword: '',
  inviteDays: 7,
})

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, {
    name: '',
    username: '',
    phone: '',
    email: '',
    deptId: 11,
    roleIds: ['USER'],
    inviteMode: 'INVITE',
    initialPassword: '',
    inviteDays: 7,
  })
  dialogVisible.value = true
}

function openEdit(row: UserRow) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, {
    name: row.realName,
    username: row.username,
    phone: row.phone,
    email: row.email ?? '',
    deptId: row.deptId || 0,
    roleIds: [...row.roles],
    inviteMode: 'INVITE',
    initialPassword: '',
    inviteDays: 7,
  })
  dialogVisible.value = true
}

async function submit() {
  if (!form.name.trim() || !form.username.trim() || !form.phone.trim()) {
    ElMessage.warning('请填写姓名、用户名与手机号')
    return
  }
  if (!/^[0-9]{11}$/.test(form.phone.replace(/\s/g, ''))) {
    ElMessage.warning('手机号格式不正确')
    return
  }
  if (form.roleIds.length === 0) {
    ElMessage.warning('请至少选择一个角色')
    return
  }
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await sysApi.createUser({
        name: form.name.trim(),
        username: form.username.trim().toLowerCase(),
        phone: form.phone.trim(),
        email: form.email.trim() || undefined,
        deptId: form.deptId,
        roleIds: form.roleIds,
        inviteMode: form.inviteMode,
        inviteDays: form.inviteDays,
      })
      ElMessage.success(`已创建用户「${form.name}」，账号状态为待激活`)
    } else {
      await sysApi.updateUser(editingId.value!, {
        realName: form.name.trim(),
        phone: form.phone.trim(),
        email: form.email.trim() || undefined,
        deptId: form.deptId,
        roleIds: form.roleIds,
      })
      ElMessage.success('已保存修改')
    }
    dialogVisible.value = false
    loadUsers()
  } finally {
    submitting.value = false
  }
}

// ---------------- 状态流转 ----------------
const ACTIVE_SUPER_IDS = computed(() => {
  // 预防自毁：最后一个启用中的超管不可停用
  return [1, 2]
})

async function toggleStatus(row: UserRow) {
  const toDisable = row.status === 'ACTIVE'
  if (toDisable) {
    if (row.id === auth.user?.id) {
      ElMessage.warning('不能停用当前登录账号')
      return
    }
    if (row.roles.includes('SUPER_ADMIN') && ACTIVE_SUPER_IDS.value.length <= 1) {
      ElMessage.warning('系统至少保留一个启用的超级管理员')
      return
    }
    try {
      await ElMessageBox.confirm(`确认停用「${row.realName}」？停用后可恢复，期间其无法访问任何知识库。`, '停用账号', {
        type: 'warning',
        confirmButtonText: '停用',
      })
    } catch {
      return
    }
  }
  const next = toDisable ? 'DISABLED' : 'ACTIVE'
  await sysApi.patchUserStatus(row.id, next)
  row.status = next
  ElMessage.success(toDisable ? '已停用该账号' : '已启用该账号')
}

async function resetPassword(row: UserRow) {
  try {
    await ElMessageBox.confirm(
      `将为「${row.realName}」重置为随机初始密码，并强制其下次登录修改、现有登录态失效。`,
      '重置密码',
      { type: 'warning', confirmButtonText: '确认重置' },
    )
  } catch {
    return
  }
  await sysApi.resetPassword(row.id)
  ElMessage.success('密码已重置，已通知用户修改')
}

async function resendInvite(row: UserRow) {
  await sysApi.resendInvite(row.id)
  ElMessage.success(`已重新向「${row.realName}」发送邀请（7 天内有效）`)
}

async function removeUser(row: UserRow) {
  if (row.id === auth.user?.id) {
    ElMessage.warning('不能删除当前登录账号')
    return
  }
  if (row.hasBusinessData) {
    ElMessage.warning('该账号存在知识库或文档业务数据，禁止删除，请改为停用')
    return
  }
  try {
    await ElMessageBox.confirm(`确认删除用户「${row.realName}」？删除不可恢复。`, '删除用户', {
      type: 'error',
      confirmButtonText: '确认删除',
    })
  } catch {
    return
  }
  ElMessage.success('（演示环境）用户已删除，列表已刷新')
  loadUsers()
}

// ---------------- 批量 ----------------
const batchDialog = ref(false)
const batchRole = ref('')
async function batchAssignRole() {
  if (selected.value.length === 0) return
  if (!batchRole.value) {
    ElMessage.warning('请选择角色')
    return
  }
  for (const u of selected.value) {
    const roleIds = u.roles.includes(batchRole.value) ? u.roles : [...u.roles, batchRole.value]
    await sysApi.updateUser(u.id, { roleIds })
  }
  batchDialog.value = false
  ElMessage.success(`已为 ${selected.value.length} 名用户分配角色「${roles.value.find((r) => r.code === batchRole.value)?.name ?? batchRole.value}」`)
  loadUsers()
}

async function batchDisable() {
  if (selected.value.length === 0) return
  if (selected.value.some((u) => u.id === auth.user?.id)) {
    ElMessage.warning('所选账号包含当前登录账号，已取消操作')
    return
  }
  try {
    await ElMessageBox.confirm(`确认停用选中的 ${selected.value.length} 名用户？`, '批量停用', {
      type: 'warning',
      confirmButtonText: '批量停用',
    })
  } catch {
    return
  }
  for (const u of selected.value) {
    if (u.status === 'ACTIVE') await sysApi.patchUserStatus(u.id, 'DISABLED')
  }
  ElMessage.success(`已停用 ${selected.value.filter((u) => u.status === 'ACTIVE').length} 名用户`)
  loadUsers()
}

function handleSelectionChange(rows: UserRow[]) {
  selected.value = rows
}

// ---------------- 导入导出（演示占位） ----------------
function importUsers() {
  ElMessage.info('支持 Excel 模板批量导入，含逐行校验报告（演示环境暂未开启）')
}
function exportUsers() {
  ElMessage.success(`已导出当前筛选结果 ${list.value.length} 条（演示）`)
}

onMounted(async () => {
  const [depts, roleRes] = await Promise.all([sysApi.deptTree(), sysApi.roles({})])
  deptTree.value = (depts as unknown as DeptRow[]) ?? []
  roles.value = ((roleRes.list as unknown as RoleBrief[]) ?? []).filter((r) => r.enabled !== false)
  // 演示账号状态清零（对齐字典 121/5/2）
  stats.total = 128
  stats.active = 121
  stats.pending = 5
  stats.disabled = 2
  loadUsers()
})
</script>

<template>
  <div class="page sys-user-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-desc">
          维护账号、所属部门与角色。<span class="warn">数据范围由角色决定，所属部门只作为比较基准</span>
        </p>
      </div>
      <div class="head-actions">
        <el-button :icon="Upload" @click="importUsers">批量导入</el-button>
        <el-button :icon="Download" @click="exportUsers">导出</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
      </div>
    </div>

    <!-- 统计卡 -->
    <div class="stat-grid">
      <div class="stat-card">
        <el-icon class="si"><UserFilled /></el-icon>
        <div class="sv">{{ stats.total }}</div>
        <div class="sl">用户总数</div>
      </div>
      <div class="stat-card">
        <el-icon class="si ok"><CircleCheck /></el-icon>
        <div class="sv">{{ stats.active }}</div>
        <div class="sl">正常</div>
      </div>
      <div class="stat-card">
        <el-icon class="si warn"><Clock /></el-icon>
        <div class="sv">{{ stats.pending }}</div>
        <div class="sl">待激活</div>
      </div>
      <div class="stat-card">
        <el-icon class="si off"><CircleClose /></el-icon>
        <div class="sv">{{ stats.disabled }}</div>
        <div class="sl">已禁用</div>
      </div>
    </div>

    <div class="user-layout">
      <!-- 组织架构树 -->
      <div class="dept-card">
        <div class="card-head">
          <span class="card-title">组织架构</span>
          <el-tooltip content="新建部门" placement="top">
            <el-button circle size="small" text type="primary" :icon="Plus" @click="ElMessage.info('部门在本系统内维护（演示）')" />
          </el-tooltip>
        </div>
        <el-tree
          ref="treeRef"
          :data="treeData"
          node-key="id"
          highlight-current
          :expand-on-click-node="false"
          :default-expanded-keys="[0, 1]"
          :current-node-key="currentDept"
          class="dept-tree"
          @node-click="(d: DeptRow) => selectDept(d.id)"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <el-icon v-if="data.id !== 0">
                <OfficeBuilding v-if="data.id !== 0" />
                <UserFilled v-else />
              </el-icon>
              <span class="tree-name">{{ data.name }}</span>
              <span class="tree-count">{{ data.userCount }}</span>
            </span>
          </template>
        </el-tree>
      </div>

      <!-- 用户列表 -->
      <div class="user-card">
        <div class="card-head">
          <span class="card-title">{{ currentDept === 0 ? '全部用户' : '当前部门用户' }}</span>
          <span class="sub">{{ total }} 人</span>
          <div class="head-right">
            <el-input
              v-model="keyword"
              placeholder="姓名 / 用户名 / 手机"
              :prefix-icon="Search"
              clearable
              style="width: 200px"
              @change="search"
              @clear="search"
            />
            <el-select v-model="roleFilter" style="width: 130px" placeholder="全部角色" @change="search">
              <el-option label="全部角色" value="all" />
              <el-option v-for="r in roles" :key="r.code" :label="r.name" :value="r.code" />
            </el-select>
            <el-select v-model="statusFilter" style="width: 120px" placeholder="全部状态" @change="search">
              <el-option label="全部状态" value="all" />
              <el-option label="正常" value="ACTIVE" />
              <el-option label="待激活" value="PENDING" />
              <el-option label="已禁用" value="DISABLED" />
            </el-select>
          </div>
        </div>

        <el-table
          v-loading="loading"
          :data="list"
          row-key="id"
          style="width: 100%"
          @selection-change="handleSelectionChange"
        >
          <el-table-column type="selection" width="40" />
          <el-table-column label="用户" min-width="170">
            <template #default="{ row }">
              <div class="user-cell">
                <div class="avatar" :style="{ background: avatarBg(row) }">{{ avatarChar(row) }}</div>
                <div class="user-meta">
                  <b>{{ row.realName }}</b>
                  <span class="row-email ellipsis">{{ row.email || '—' }}</span>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="username" label="用户名" min-width="110">
            <template #default="{ row }">
              <span class="mono">{{ row.username }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="department" label="所属部门" min-width="150" show-overflow-tooltip />
          <el-table-column label="角色" min-width="140">
            <template #default="{ row }">
              <el-tag v-for="r in row.roleNames" :key="r" size="small" class="role-tag" :type="r === '超级管理员' ? 'warning' : r === '外部访客' ? 'info' : ''" effect="plain">
                {{ r }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="STATUS_TAG[row.status as keyof typeof STATUS_TAG]?.type ?? 'info'" size="small" effect="light" round>
                {{ STATUS_TAG[row.status as keyof typeof STATUS_TAG]?.label ?? row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近登录" width="140">
            <template #default="{ row }">
              <span class="muted">{{ row.lastLoginAt || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <div class="ops">
                <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
                <el-button v-if="row.status === 'PENDING'" link type="primary" size="small" :icon="Promotion" @click="resendInvite(row)">
                  重发邀请
                </el-button>
                <el-button v-else link type="primary" size="small" :icon="Key" @click="resetPassword(row)">重置密码</el-button>
                <el-button
                  v-if="row.status !== 'PENDING'"
                  link
                  size="small"
                  :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
                  :icon="SwitchButton"
                  @click="toggleStatus(row)"
                >
                  {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div class="table-foot">
          <div class="batch-bar">
            <span>已选 {{ selected.length }} 项</span>
            <el-button size="small" :disabled="selected.length === 0" @click="batchDialog = true">批量分配角色</el-button>
            <el-button size="small" :disabled="selected.length === 0" @click="batchDisable">批量停用</el-button>
          </div>
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            background
            small
            @change="loadUsers"
          />
        </div>
      </div>
    </div>

    <!-- 新建 / 编辑用户弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建用户' : '编辑用户'"
      width="660px"
      destroy-on-close
    >
      <el-form label-position="top" size="default">
        <div class="form-grid2">
          <el-form-item label="姓名" required>
            <el-input v-model="form.name" placeholder="真实姓名，2-20 字" maxlength="20" />
          </el-form-item>
          <el-form-item label="用户名" required>
            <el-input v-model="form.username" placeholder="登录账号，全局唯一" class="mono" :disabled="dialogMode === 'edit'">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
            <div class="hint">{{ dialogMode === 'edit' ? '用户名创建后不可修改' : '登录账号，全局唯一，创建后不可修改' }}</div>
          </el-form-item>
          <el-form-item label="手机号" required>
            <el-input v-model="form.phone" placeholder="用于接收验证码" maxlength="11" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="form.email" placeholder="用于接收邀请与通知">
              <template #prefix><el-icon><Message /></el-icon></template>
            </el-input>
          </el-form-item>
        </div>

        <el-form-item label="所属部门" required>
          <el-tree-select
            v-model="form.deptId"
            :data="deptTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            check-strictly
            style="width: 100%"
            placeholder="选择部门（数据范围比较基准）"
          />
          <div class="scope-tip">
            <el-icon><Clock /></el-icon>
            <span>
              <b>数据范围由角色决定</b>（全部 / 本部门及以下 / 本部门 / 仅本人），部门只提供比较基准。
              调整部门会改变基准，进而改变可见范围。
            </span>
          </div>
        </el-form-item>

        <el-form-item label="角色" required>
          <el-checkbox-group v-model="form.roleIds" class="role-check-group">
            <el-checkbox-button v-for="r in roles" :key="r.code" :value="r.code">{{ r.name }}</el-checkbox-button>
          </el-checkbox-group>
          <div class="hint">可多选，权限取并集；数据范围类型也来自角色</div>
        </el-form-item>

        <el-form-item v-if="dialogMode === 'create'" label="账号开通方式">
          <el-radio-group v-model="form.inviteMode">
            <el-radio value="INVITE">发送邀请链接<span class="opt-desc">用户自行设置密码</span></el-radio>
            <el-radio value="PASSWORD">设置初始密码<span class="opt-desc">首次登录强制修改</span></el-radio>
          </el-radio-group>
        </el-form-item>

        <div v-if="dialogMode === 'create'" class="form-grid2">
          <el-form-item label="初始密码">
            <el-input :model-value="form.inviteMode === 'INVITE' ? '不可用' : '请输入 8-20 位密码'" disabled />
          </el-form-item>
          <el-form-item label="邀请有效期">
            <el-select v-model="form.inviteDays" style="width: 100%">
              <el-option :value="3" label="3 天" />
              <el-option :value="7" label="7 天" />
              <el-option :value="14" label="14 天" />
              <el-option :value="30" label="30 天" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <span class="foot-hint">创建后账号状态为「待激活」，激活前无法登录</span>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ dialogMode === 'create' ? '创建并发送邀请' : '保存修改' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 批量分配角色 -->
    <el-dialog v-model="batchDialog" title="批量分配角色" width="420px">
      <p class="batch-tip">已选 {{ selected.length }} 名用户，为其追加以下角色（权限取并集）：</p>
      <el-select v-model="batchRole" placeholder="选择要分配的角色" style="width: 100%">
        <el-option v-for="r in roles" :key="r.code" :label="`${r.name}（${r.memberCount} 人）`" :value="r.code" />
      </el-select>
      <template #footer>
        <el-button @click="batchDialog = false">取消</el-button>
        <el-button type="primary" @click="batchAssignRole">确认分配</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sys-user-page {
  height: 100%;
  overflow-y: auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}
.warn {
  color: var(--el-color-warning);
  font-weight: 600;
}
.head-actions {
  display: flex;
  gap: 8px;
}

/* 统计卡 */
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
.stat-card .si.ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.stat-card .si.warn {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.stat-card .si.off {
  background: var(--el-color-info-light-9);
  color: var(--el-color-info);
}
.stat-card .sv {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
}
.stat-card .sl {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 布局 */
.user-layout {
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.dept-card,
.user-card {
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
}

/* 部门树 */
.dept-tree {
  padding: 8px;
}
:deep(.el-tree-node__content) {
  height: 34px;
  border-radius: 6px;
}
.tree-node {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding-right: 6px;
}
.tree-ico {
  color: var(--el-color-primary);
  font-size: 14px;
  flex: none;
}
.tree-ico.root {
  color: var(--el-color-warning);
}
.tree-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tree-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 表格 */
.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  color: #fff;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.user-meta {
  min-width: 0;
}
.user-meta b {
  display: block;
  font-size: 13px;
}
.row-email {
  display: block;
  max-width: 130px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.role-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ops {
  display: flex;
  align-items: center;
  gap: 2px;
}
.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-top: 1px solid var(--el-border-color-lighter);
  flex-wrap: wrap;
  gap: 8px;
}
.batch-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.batch-bar .el-button + .el-button {
  margin-left: 0;
}

/* 弹窗 */
.form-grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-top: 4px;
}
.scope-tip,
.batch-tip {
  display: flex;
  gap: 8px;
  padding: 9px 11px;
  background: var(--el-color-warning-light-9);
  border-radius: 6px;
  font-size: 12px;
  color: #8a6d3b;
  line-height: 1.65;
  margin-top: 8px;
  width: 100%;
}
.batch-tip {
  background: var(--el-color-primary-light-9);
  color: #3a6ea5;
  margin-bottom: 12px;
  border: none;
  padding: 10px 12px;
}
.role-check-group {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.opt-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 6px;
}
.foot-hint {
  float: left;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 32px;
}
</style>