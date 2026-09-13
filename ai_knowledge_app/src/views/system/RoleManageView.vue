<script setup lang="ts">
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Download, Search, Refresh, Key, EditPen, Delete, Lock, UserFilled, Rank } from '@element-plus/icons-vue'
import { sysApi } from '@/api'
import type { PermissionNode } from '@/types'

/** 角色行（接口返回已聚合成可读字段） */
interface RoleRow {
  id: number
  name: string
  code: string
  description: string
  builtin: boolean
  dataScope: 'ALL' | 'DEPT_AND_CHILD' | 'DEPT' | 'SELF' | 'CUSTOM'
  dataScopeLabel: string
  memberCount: number
  permissionCount: number
  enabled: boolean
  createdAt: string
}

const DATA_SCOPE_LABELS: { label: string; value: string }[] = [
  { label: '全部数据 · ALL', value: 'ALL' },
  { label: '本部门及以下 · DEPT_AND_CHILD', value: 'DEPT_AND_CHILD' },
  { label: '本部门 · DEPT', value: 'DEPT' },
  { label: '仅本人 · SELF', value: 'SELF' },
  { label: '自定义 · CUSTOM', value: 'CUSTOM' },
]

const list = ref<RoleRow[]>([])
const loading = ref(false)
const keyword = ref('')
const typeFilter = ref('all')
const statusFilter = ref('all')

const stats = reactive({ total: 5, builtin: 3, authorized: 128, permPoints: 38 })

// ---------------- 弹窗 ----------------
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const submitting = ref(false)

const form = reactive({
  name: '',
  code: '',
  description: '',
  dataScope: 'DEPT_AND_CHILD' as 'ALL' | 'DEPT_AND_CHILD' | 'DEPT' | 'SELF' | 'CUSTOM',
  enabled: true,
})

// 权限树
const permTree = ref<PermissionNode[]>([])
const permTreeRef = ref<{ setCheckedKeys: (keys: (string | number)[]) => void; getCheckedKeys: (leafOnly?: boolean) => (string | number)[]; getHalfCheckedKeys: () => (string | number)[] } | null>(null)
const checkedPerms = ref<string[]>([])
const expandAll = ref(true)

function flattenLeafIds(nodes: PermissionNode[]): string[] {
  const ids: string[] = []
  for (const n of nodes) {
    if (n.children) ids.push(...flattenLeafIds(n.children))
    else if (n.code) ids.push(n.id)
  }
  return ids
}

function leafCodeById(id: string): string | undefined {
  const walk = (nodes: PermissionNode[]): string | undefined => {
    for (const n of nodes) {
      if (n.children) {
        const hit = walk(n.children)
        if (hit) return hit
      } else if (n.id === id) return n.code
    }
    return undefined
  }
  return walk(permTree.value)
}

interface TreeDataItem {
  id: string
  label: string
  code?: string
  children?: TreeDataItem[]
}

function toTreeData(nodes: PermissionNode[]): TreeDataItem[] {
  return nodes.map((n) => ({
    id: n.id,
    label: n.label,
    code: n.code,
    children: n.children ? toTreeData(n.children) : undefined,
  }))
}

async function syncTreeChecked(ids: string[]) {
  await nextTick()
  permTreeRef.value?.setCheckedKeys(ids)
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, { name: '', code: '', description: '', dataScope: 'DEPT_AND_CHILD', enabled: true })
  checkedPerms.value = []
  dialogVisible.value = true
  void syncTreeChecked([])
}

function openEdit(row: RoleRow) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    code: row.code,
    description: row.description,
    dataScope: row.dataScope,
    enabled: row.enabled,
  })
  checkedPerms.value = []
  dialogVisible.value = true
  // 异步拉取已选权限并回显到树
  sysApi.rolePermissions(row.id).then((res) => {
    const codes = res.codes ?? []
    const ids = flattenLeafIds(permTree.value).filter((id) => codes.includes(leafCodeById(id) ?? ''))
    checkedPerms.value = ids
    void syncTreeChecked(ids)
  })
}

function handlePermCheck(_data: unknown, checkState: { checkedKeys: (string | number)[]; halfCheckedKeys: (string | number)[] }) {
  checkedPerms.value = [...(checkState.checkedKeys as string[]), ...(checkState.halfCheckedKeys as string[])]
}

const permCount = computed(() => {
  const leafIds = flattenLeafIds(permTree.value)
  return leafIds.filter((id) => checkedPerms.value.includes(id)).length
})

function toggleExpandAll() {
  expandAll.value = !expandAll.value
  const nodeMap = (permTreeRef.value as unknown as { store?: { nodesMap?: Record<string, { expanded: boolean }> } } | null)?.store?.nodesMap
  if (nodeMap) {
    for (const key of Object.keys(nodeMap)) nodeMap[key]!.expanded = expandAll.value
  }
}

async function submit() {
  if (!form.name.trim() || !form.code.trim()) {
    ElMessage.warning('请填写角色名称与角色编码')
    return
  }
  if (!/^[A-Z][A-Z0-9_]*$/.test(form.code.trim())) {
    ElMessage.warning('角色编码需大写字母 + 数字/下划线，且以字母开头')
    return
  }
  const codes = flattenLeafIds(permTree.value)
    .filter((id) => checkedPerms.value.includes(id))
    .map((id) => leafCodeById(id) ?? '')
    .filter(Boolean)
  const body = {
    name: form.name.trim(),
    code: form.code.trim(),
    description: form.description.trim(),
    dataScope: form.dataScope,
    enabled: form.enabled,
    permissions: codes,
  }
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await sysApi.createRole(body)
      ElMessage.success(`已创建角色「${form.name}」`)
    } else {
      await sysApi.updateRole(editingId.value!, body)
      ElMessage.success('已保存修改')
    }
    dialogVisible.value = false
    loadRoles()
  } finally {
    submitting.value = false
  }
}

// ---------------- 动作 ----------------
async function toggleRole(row: RoleRow, next: boolean) {
  if (!next && row.code === 'SUPER_ADMIN') {
    ElMessage.warning('超级管理员角色不可停用')
    return
  }
  await sysApi.patchRoleStatus(row.id, next)
  row.enabled = next
  ElMessage.success(next ? '角色已启用' : '角色已停用，其成员登录时权限降级为默认角色')
}

async function deleteRole(row: RoleRow) {
  if (row.builtin) {
    ElMessage.warning('内置角色不可删除')
    return
  }
  if (row.memberCount > 0) {
    ElMessage.warning(`角色「${row.name}」下存在 ${row.memberCount} 名成员，请先转移成员`)
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除角色「${row.name}」？删除后不可恢复，历史授权记录仍保留在审计日志中。`,
      '删除角色',
      { type: 'error', confirmButtonText: '确认删除' },
    )
  } catch {
    return
  }
  await sysApi.deleteRole(row.id)
  ElMessage.success('角色已删除')
  loadRoles()
}

function exportRoles() {
  ElMessage.success(`已导出角色清单（演示）`)
}

function viewMembers(row: RoleRow) {
  ElMessage.info(`角色「${row.name}」当前 ${row.memberCount} 名成员（演示）`)
}

// ---------------- 加载 ----------------
async function loadRoles() {
  loading.value = true
  try {
    const res = await sysApi.roles({
      keyword: keyword.value || undefined,
      type: typeFilter.value,
      status: statusFilter.value,
    })
    list.value = (res.list as unknown as RoleRow[]) ?? []
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  const permRes = await sysApi.permissionTree()
  permTree.value = permRes.tree ?? []
  stats.permPoints = permRes.total ?? 38
  loadRoles()
})
</script>

<template>
  <div class="page sys-role-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">角色管理</h2>
        <p class="page-desc">定义角色、划分数据范围，并决定角色可访问的功能与知识库</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Download" @click="exportRoles">导出角色</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建角色</el-button>
      </div>
    </div>

    <!-- 统计卡 -->
    <div class="stat-grid">
      <div class="stat-card">
        <el-icon class="si"><Lock /></el-icon>
        <div class="sv">{{ stats.total }}</div>
        <div class="sl">角色总数</div>
      </div>
      <div class="stat-card">
        <el-icon class="si p"><Lock /></el-icon>
        <div class="sv">{{ stats.builtin }}</div>
        <div class="sl">内置角色<small>不可删除</small></div>
      </div>
      <div class="stat-card">
        <el-icon class="si ok"><UserFilled /></el-icon>
        <div class="sv">{{ stats.authorized }}</div>
        <div class="sl">已授权用户</div>
      </div>
      <div class="stat-card">
        <el-icon class="si o"><Rank /></el-icon>
        <div class="sv">{{ stats.permPoints }}</div>
        <div class="sl">可分配权限点</div>
      </div>
    </div>

    <!-- 角色列表 -->
    <div class="role-card">
      <div class="card-head">
        <span class="card-title">角色列表</span>
        <div class="head-right">
          <el-input v-model="keyword" placeholder="角色名称 / 编码" :prefix-icon="Search" clearable style="width: 200px" @change="loadRoles" @clear="loadRoles" />
          <el-select v-model="typeFilter" style="width: 130px" @change="loadRoles">
            <el-option label="全部类型" value="all" />
            <el-option label="内置" value="BUILTIN" />
            <el-option label="自定义" value="CUSTOM" />
          </el-select>
          <el-select v-model="statusFilter" style="width: 120px" @change="loadRoles">
            <el-option label="全部状态" value="all" />
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
          <el-button :icon="Refresh" circle @click="loadRoles" />
        </div>
      </div>

      <el-table v-loading="loading" :data="list" row-key="id">
        <el-table-column type="selection" width="42" />
        <el-table-column label="角色名称" min-width="150">
          <template #default="{ row }">
            <span class="role-name">
              <el-icon v-if="row.builtin" class="shield-ico"><Lock /></el-icon>
              <b>{{ row.name }}</b>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="角色编码" min-width="130">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.builtin ? 'warning' : 'info'" size="small" effect="plain">{{ row.builtin ? '内置' : '自定义' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据范围" min-width="130">
          <template #default="{ row }">
            <span class="scope-badge">{{ row.dataScopeLabel }}</span>
          </template>
        </el-table-column>
        <el-table-column label="成员数" width="90">
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="viewMembers(row)">{{ row.memberCount }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="权限点" width="90">
          <template #default="{ row }">
            <span>{{ row.permissionCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :disabled="row.code === 'SUPER_ADMIN'"
              @change="(v: string | number | boolean) => toggleRole(row, Boolean(v))"
            />
            <span class="status-text" :class="{ off: !row.enabled }">{{ row.enabled ? '启用' : '停用' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="130">
          <template #default="{ row }"><span class="muted">{{ row.createdAt }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <div class="ops">
              <el-button link type="primary" size="small" :icon="Key" @click="openEdit(row)">权限</el-button>
              <el-button link type="primary" size="small" :icon="EditPen" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" size="small" :icon="Delete" :disabled="row.builtin" @click="deleteRole(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-foot">
        <span>共 {{ list.length }} 条</span>
        <span class="tiny muted">各角色成员数之和 = 用户总数（角色为必填项）</span>
      </div>
    </div>

    <!-- 数据范围说明 -->
    <div class="scope-card">
      <div class="card-head">
        <span class="card-title">数据范围说明</span>
        <span class="tag-plain">与后端 dataScope 字段一一对应</span>
      </div>
      <div class="scope-badges">
        <span v-for="s in DATA_SCOPE_LABELS" :key="s.value" class="scope-badge">{{ s.label }}</span>
      </div>
      <p class="scope-note">
        数据范围是<b>角色属性</b>，以用户所在部门为<b>比较基准</b>——范围类型由角色定，部门只提供基准点。
        例如角色为「本部门及以下」且用户部门为「平台架构组」，则可检索平台架构组及其下属范围。
      </p>
    </div>

    <!-- 新建 / 编辑角色弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建角色' : '编辑角色'"
      width="680px"
      destroy-on-close
      append-to-body
    >
      <el-form label-position="top">
        <div class="form-grid2">
          <el-form-item label="角色名称" required>
            <el-input v-model="form.name" placeholder="如：知识库审计员" maxlength="20" />
          </el-form-item>
          <el-form-item label="角色编码" required>
            <el-input v-model="form.code" placeholder="如：KB_AUDITOR" class="mono" :disabled="dialogMode === 'edit'" />
            <div class="hint">大写字母 + 下划线，创建后不可修改</div>
          </el-form-item>
        </div>
        <el-form-item label="角色描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="描述该角色职责与边界" />
        </el-form-item>
        <div class="form-grid2">
          <el-form-item label="数据范围" required>
            <el-select v-model="form.dataScope" style="width: 100%">
              <el-option v-for="s in DATA_SCOPE_LABELS" :key="s.value" :label="s.label.split(' · ')[0]!" :value="s.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <div class="switch-row">
              <el-switch v-model="form.enabled" />
              <span class="tiny muted">启用后该角色下的用户可正常登录并继承权限</span>
            </div>
          </el-form-item>
        </div>
        <el-form-item label="功能权限">
          <div class="perm-box">
            <div class="perm-toolbar">
              <span class="tiny muted">勾选父级自动全选子级</span>
              <el-button link type="primary" size="small" @click="toggleExpandAll">{{ expandAll ? '折叠全部' : '展开全部' }}</el-button>
            </div>
            <el-tree
              ref="permTreeRef"
              :data="toTreeData(permTree)"
              show-checkbox
              node-key="id"
              :props="{ label: 'label', children: 'children' }"
              :default-expand-all="true"
              @check="handlePermCheck"
              class="perm-tree"
            />
          </div>
          <div class="hint">已选中 {{ permCount }} 个权限点</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">{{ dialogMode === 'create' ? '确认创建' : '保存修改' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sys-role-page {
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
.stat-card .si.p {
  background: #fdf0f0;
  color: #f56c6c;
}
.stat-card .si.ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.stat-card .si.o {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.stat-card .sv {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
}
.stat-card .sl {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.stat-card .sl small {
  display: block;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

/* 角色卡片 */
.role-card,
.scope-card {
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
.tag-plain {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-color-info-light-9);
  padding: 2px 8px;
  border-radius: 4px;
}
.role-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.shield-ico {
  color: var(--el-color-warning);
  font-size: 15px;
}
.scope-badge {
  display: inline-block;
  font-size: 12px;
  color: var(--el-text-color-regular);
  background: var(--el-color-info-light-9);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 2px 8px;
  white-space: nowrap;
}
.status-text {
  font-size: 12px;
  margin-left: 6px;
  color: var(--el-color-success);
}
.status-text.off {
  color: var(--el-text-color-secondary);
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
}

/* 数据范围说明 */
.scope-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 14px 16px 0;
}
.scope-note {
  margin: 12px 16px 16px;
  font-size: 12.5px;
  color: var(--el-text-color-regular);
  line-height: 1.7;
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
  padding: 10px 13px;
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
.switch-row {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 32px;
  line-height: 1.4;
}
.perm-box {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  width: 100%;
  padding: 6px 8px 8px;
}
.perm-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px 4px;
}
.perm-tree {
  max-height: 260px;
  overflow: auto;
  padding: 4px;
}
:deep(.perm-tree .el-tree-node__content) {
  height: 30px;
}
</style>