<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Key, EditPen, User, Phone, Message, Lock, Check, Warning, Clock, Refresh, Camera, Right } from '@element-plus/icons-vue'
import { userApi, sysApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// ---------------- 资料头 ----------------
const avatarChar = computed(() => (auth.user?.realName ?? auth.user?.username ?? '林')[0] ?? '林')
const isDirty = ref(false)

// ---------------- Tab 1 基本资料 ----------------
const base = reactive({
  username: 'lin.gong',
  realName: '林工',
  phone: '138 8888 6666',
  email: 'lin.gong@corp.com',
  department: '研发中心 / 平台架构组',
  position: '高级后端工程师',
  intro: '专注 Java 后端与 AI 应用工程化，负责知识库检索链路与权限体系设计。',
})

const accountInfo = reactive({
  id: 1001,
  roles: '超级管理员、审计员',
  createdAt: '2026-03-12 10:24',
  lastLogin: '2026-09-11 17:40 · 内网 10.8.3.21',
  status: '正常' as string,
})

async function saveBase() {
  isDirty.value = false
  await userApi.updateProfile({
    realName: base.realName,
    email: base.email,
    position: base.position,
    intro: base.intro,
  })
  if (auth.user) auth.user.realName = base.realName
  ElMessage.success('基本资料已保存')
}

function resetBase() {
  const p = auth.user
  if (p) {
    base.realName = p.realName ?? '林工'
    base.email = (p.email as string) ?? ''
    base.position = (p.position as string) ?? ''
    base.intro = (p.intro as string) ?? ''
  }
  isDirty.value = false
}

// ---------------- 安全等级 ----------------
const security = reactive({
  phoneBound: true,
  emailBound: true,
  mfaEnabled: false,
  passwordUpdatedDaysAgo: 62,
})

// ---------------- Tab 2 安全设置 ----------------
const activeTab = ref('base')
const securityTab = ref('password')

const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
async function changePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('请填写原密码与新密码')
    return
  }
  if (pwdForm.newPassword.length < 8) {
    ElMessage.warning('新密码长度至少 8 位')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  try {
    await ElMessageBox.confirm('修改密码后需重新登录，确认继续？', '修改密码', { type: 'warning' })
  } catch {
    return
  }
  await userApi.changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
  ElMessage.success('密码修改成功，即将重新登录')
  setTimeout(() => auth.logout(), 600)
}

// 手机 / 邮箱换绑
const changePhoneVisible = ref(false)
const changeEmailVisible = ref(false)
const token2FAVisible = ref(false)
const bindForm = reactive({ phone: '', email: '', code: '' })

// TOTP
const totpSecret = 'JBSWY3DPEHPK3PXP'
const totpQR = computed(() => `otpauth://totp/AI%20KB:${base.username}?secret=${totpSecret}&issuer=AI%20KB`)
const totpCode = ref('')

function enableMFA() {
  if (totpCode.value.length !== 6) {
    ElMessage.warning('请输入 6 位校验码')
    return
  }
  security.mfaEnabled = true
  token2FAVisible.value = false
  ElMessage.success('双因素认证（TOTP）已开启')
}

function savePhone() {
  if (!/^1\d{10}$/.test(bindForm.phone.replace(/\s/g, ''))) {
    ElMessage.warning('手机号格式不正确')
    return
  }
  changePhoneVisible.value = false
  ElMessage.success('已修改绑定手机号')
}

function saveEmail() {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(bindForm.email)) {
    ElMessage.warning('邮箱格式不正确')
    return
  }
  changeEmailVisible.value = false
  ElMessage.success('已发送确认邮件，验证后生效')
}

// ---------------- Tab 3 我的权限 ----------------
const permCodes = ref<string[]>([])
const permGroups = computed(() => {
  const groups: { name: string; codes: string[] }[] = []
  for (const code of permCodes.value) {
    const domain = code.split(':')[0] ?? code
    let g = groups.find((x) => x.name === domain)
    if (!g) {
      g = { name: domain, codes: [] }
      groups.push(g)
    }
    g.codes.push(code)
  }
  return groups
})

const domainName: Record<string, string> = {
  qa: '知识问答',
  kb: '知识库管理',
  doc: '文档',
  user: '用户管理',
  role: '角色管理',
  grant: '授权管理',
  audit: '审计日志',
  system: '系统配置',
}

// ---------------- Tab 4 登录日志 ----------------
const loginLogs = ref<{ id: number; method: string; ip: string; device: string; result: string; time: string }[]>([])
const logsLoading = ref(false)

async function loadLoginLogs() {
  logsLoading.value = true
  try {
    const res = await userApi.loginLogs({ page: 1, pageSize: 8 })
    loginLogs.value = (res as unknown as { list: typeof loginLogs.value }).list ?? []
  } finally {
    logsLoading.value = false
  }
}

function onTabChange(name: string | number) {
  if (name === 'logs') loadLoginLogs()
}

onMounted(async () => {
  try {
    const res = await userApi.profile()
    const p = res as unknown as Record<string, unknown>
    base.username = String(p.username ?? 'lin.gong')
    base.realName = String(p.realName ?? '林工')
    base.phone = String(p.phone ?? '').replace(/(\d{3})\d{4}(\d{4})/, '$1 $2')
    base.email = String((p.email as string) ?? '')
    base.department = String((p.department as string) ?? '')
    base.position = String((p.position as string) ?? '')
    base.intro = String((p.intro as string) ?? '')
    accountInfo.roles = String((p.roleNames as string[])?.join('、') ?? '')
    if (auth.user) {
      auth.user.realName = base.realName
      auth.user.username = base.username
    }
  } catch {
    /* 保持默认值 */
  }
  // 权限码
  try {
    const permRes = await userApi.permissions()
    permCodes.value = permRes.codes ?? []
  } catch {
    /* 忽略 */
  }
})
</script>

<template>
  <div class="page sys-profile-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">个人信息</h2>
        <p class="page-desc">维护你的账号资料、登录凭证与安全设置</p>
      </div>
    </div>

    <!-- 资料头卡 -->
    <div class="card profile-card">
      <div class="profile-head">
        <div class="avatar-wrap">
          <div class="avatar-big" :style="{ background: 'var(--brand-grad)' }">{{ avatarChar }}</div>
          <div class="avatar-edit" title="更换头像">
            <el-icon><Camera /></el-icon>
          </div>
        </div>
        <div class="profile-info">
          <h3>
            {{ base.realName }}
            <span class="tag-status ok">在职</span>
            <span class="tag-status role">超级管理员</span>
          </h3>
          <div class="profile-tags">
            <span class="plain-tag"><el-icon><User /></el-icon>{{ base.department }}</span>
            <span class="plain-tag"><el-icon><Message /></el-icon>{{ base.email }}</span>
            <span class="plain-tag"><el-icon><Phone /></el-icon>{{ base.phone }}</span>
          </div>
          <p class="profile-desc">{{ base.intro }}</p>
        </div>
        <div class="profile-actions">
          <el-button :icon="Key" @click="activeTab = 'security'">修改密码</el-button>
          <el-button type="primary" :icon="EditPen" @click="activeTab = 'base'">编辑资料</el-button>
        </div>
      </div>

      <!-- Tabs -->
      <el-tabs v-model="activeTab" class="profile-tabs" @tab-change="onTabChange">
        <!-- ========== 基本资料 ========== -->
        <el-tab-pane label="基本资料" name="base">
          <div class="tab-grid">
            <div>
              <div class="form-grid2">
                <div class="field">
                  <label>用户名</label>
                  <el-input :model-value="base.username" disabled class="readonly">
                    <template #prefix><el-icon><User /></el-icon></template>
                    <template #suffix><el-icon><Lock /></el-icon></template>
                  </el-input>
                  <div class="hint">用户名创建后不可修改</div>
                </div>
                <div class="field">
                  <label>真实姓名 <span class="req">*</span></label>
                  <el-input v-model="base.realName" maxlength="20" @input="isDirty = true" />
                </div>
                <div class="field">
                  <label>手机号 <span class="req">*</span></label>
                  <div class="input-affix">
                    <el-input :model-value="base.phone" disabled class="readonly">
                      <template #prefix><el-icon><Phone /></el-icon></template>
                    </el-input>
                    <el-button link type="primary" @click="changePhoneVisible = true">更换</el-button>
                  </div>
                </div>
                <div class="field">
                  <label>邮箱</label>
                  <el-input v-model="base.email" placeholder="用于接收系统通知" @input="isDirty = true">
                    <template #prefix><el-icon><Message /></el-icon></template>
                  </el-input>
                </div>
                <div class="field">
                  <label>所属部门</label>
                  <el-input :model-value="base.department" disabled class="readonly">
                    <template #prefix><el-icon><OfficeBuilding /></el-icon></template>
                  </el-input>
                  <div class="hint">部门由管理员维护</div>
                </div>
                <div class="field">
                  <label>职位</label>
                  <el-input v-model="base.position" maxlength="30" placeholder="如：高级后端工程师" @input="isDirty = true" />
                </div>
              </div>
              <div class="field">
                <label>个人简介</label>
                <el-input v-model="base.intro" type="textarea" :rows="3" maxlength="200" show-word-limit @input="isDirty = true" />
              </div>
              <div class="save-row">
                <el-button type="primary" :disabled="!isDirty" @click="saveBase">保存修改</el-button>
                <el-button :disabled="!isDirty" @click="resetBase">重置</el-button>
              </div>
            </div>

            <!-- 右栏 -->
            <div class="side-col">
              <div class="mini-card">
                <div class="mini-head">账号信息</div>
                <dl class="kv">
                  <dt>用户 ID</dt><dd class="mono">{{ accountInfo.id }}</dd>
                  <dt>角色</dt><dd>{{ accountInfo.roles }}</dd>
                  <dt>注册时间</dt><dd>{{ accountInfo.createdAt }}</dd>
                  <dt>最近登录</dt><dd>{{ accountInfo.lastLogin }}</dd>
                  <dt>账号状态</dt><dd><span class="tag-status ok">{{ accountInfo.status }}</span></dd>
                </dl>
              </div>
              <div class="mini-card">
                <div class="mini-head">
                  安全等级
                  <span class="tag-status ok">较高</span>
                </div>
                <div class="safe-bar"><i :style="{ width: '78%' }"></i></div>
                <div class="safe-list">
                  <div class="safe-item">
                    <span class="yes"><el-icon><Check /></el-icon> 已绑定手机号</span>
                    <el-button link type="primary" size="small" @click="changePhoneVisible = true">修改</el-button>
                  </div>
                  <div class="safe-item">
                    <span class="yes"><el-icon><Check /></el-icon> 已绑定邮箱</span>
                    <el-button link type="primary" size="small" @click="changeEmailVisible = true">修改</el-button>
                  </div>
                  <div class="safe-item">
                    <span class="no"><el-icon><Warning /></el-icon> {{ security.mfaEnabled ? '已开启双因素认证' : '未开启双因素认证' }}</span>
                    <el-button v-if="!security.mfaEnabled" link type="primary" size="small" @click="token2FAVisible = true">去开启</el-button>
                  </div>
                  <div class="safe-item">
                    <span class="muted"><el-icon><Clock /></el-icon> 密码 {{ security.passwordUpdatedDaysAgo }} 天前更新</span>
                    <el-button link type="primary" size="small" @click="activeTab = 'security'">修改</el-button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- ========== 安全设置 ========== -->
        <el-tab-pane label="安全设置" name="security">
          <div class="sec-layout">
            <div class="sec-nav">
              <div class="sec-item" :class="{ active: securityTab === 'password' }" @click="securityTab = 'password'">
                <el-icon><Lock /></el-icon> 修改密码
              </div>
              <div class="sec-item" :class="{ active: securityTab === 'bind' }" @click="securityTab = 'bind'">
                <el-icon><Message /></el-icon> 绑定手机 / 邮箱
              </div>
              <div class="sec-item" :class="{ active: securityTab === 'mfa' }" @click="securityTab = 'mfa'">
                <el-icon><Checked /></el-icon> 双因素认证（TOTP）
              </div>
            </div>

            <div class="sec-panel">
              <!-- 修改密码 -->
              <div v-if="securityTab === 'password'" class="sec-panel-inner">
                <h4>修改密码</h4>
                <p class="sec-desc">建议每 90 天更新一次密码，新密码需与旧密码不同</p>
                <el-form label-width="88px" style="max-width: 420px">
                  <el-form-item label="原密码">
                    <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入当前密码" />
                  </el-form-item>
                  <el-form-item label="新密码">
                    <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="8-20 位，包含字母与数字" />
                  </el-form-item>
                  <el-form-item label="确认新密码">
                    <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
                  </el-form-item>
                  <el-form-item>
                    <el-button type="primary" :icon="Key" @click="changePassword">确认修改</el-button>
                  </el-form-item>
                </el-form>
              </div>

              <!-- 绑定手机 / 邮箱 -->
              <div v-if="securityTab === 'bind'" class="sec-panel-inner">
                <h4>绑定手机 / 邮箱</h4>
                <div class="bind-card">
                  <div class="bind-row">
                    <div class="bind-info">
                      <b>手机号</b>
                      <span class="muted">{{ base.phone }}</span>
                      <span class="tag-status ok">已绑定</span>
                    </div>
                    <el-button link type="primary" @click="changePhoneVisible = true">更换手机号</el-button>
                  </div>
                  <div class="bind-row">
                    <div class="bind-info">
                      <b>邮箱</b>
                      <span class="muted">{{ base.email || '未绑定' }}</span>
                      <span v-if="base.email" class="tag-status ok">已绑定</span>
                      <span v-else class="tag-status warn">未绑定</span>
                    </div>
                    <el-button link type="primary" @click="changeEmailVisible = true">{{ base.email ? '更换邮箱' : '绑定邮箱' }}</el-button>
                  </div>
                </div>
              </div>

              <!-- 双因素认证 -->
              <div v-if="securityTab === 'mfa'" class="sec-panel-inner">
                <h4>双因素认证（TOTP）</h4>
                <p class="sec-desc">开启后，登录时除密码外还需输入 6 位动态校验码，有效提升账号安全性</p>
                <div class="mfa-box">
                  <div class="mfa-qr">
                    <div class="qr-placeholder">TOTP<br />QR</div>
                    <div class="qr-tip">使用「身份宝 / Google Authenticator」扫描二维码</div>
                  </div>
                  <div class="mfa-secret">
                    <div class="secret-label">密钥（手动输入）</div>
                    <code class="secret-code">{{ totpSecret }}</code>
                    <div class="secret-hint">配置后输入 6 位校验码验证：</div>
                    <div class="mfa-verify">
                      <el-input v-model="totpCode" maxlength="6" placeholder="6 位校验码" style="width: 160px" class="mono" />
                      <el-button type="primary" :disabled="security.mfaEnabled" @click="enableMFA">
                        {{ security.mfaEnabled ? '已开启' : '验证并开启' }}
                      </el-button>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- ========== 我的权限 ========== -->
        <el-tab-pane :label="`我的权限（${permCodes.length}）`" name="perms">
          <div class="perm-page">
            <div class="perm-head">
              <p class="sec-desc">以下为当前账号全部<b>原子权限码</b>（各角色权限点的并集，只读）。超级管理员拥有全部 38 个权限点。</p>
            </div>
            <div v-for="g in permGroups" :key="g.name" class="perm-group">
              <div class="perm-group-name">{{ domainName[g.name] ?? g.name }}</div>
              <div class="perm-codes">
                <el-tag v-for="c in g.codes" :key="c" size="small" effect="plain" class="perm-tag">{{ c }}</el-tag>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- ========== 登录日志 ========== -->
        <el-tab-pane label="登录日志" name="logs">
          <el-table v-loading="logsLoading" :data="loginLogs" style="width: 100%">
            <el-table-column prop="time" label="时间" min-width="150">
              <template #default="{ row }"><span class="mono">{{ row.time }}</span></template>
            </el-table-column>
            <el-table-column prop="method" label="方式" width="110" />
            <el-table-column prop="device" label="设备" min-width="150" />
            <el-table-column prop="ip" label="IP" width="130">
              <template #default="{ row }"><span class="mono">{{ row.ip }}</span></template>
            </el-table-column>
            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small" effect="light" round>
                  {{ row.result === 'SUCCESS' ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 更换手机号 -->
    <el-dialog v-model="changePhoneVisible" title="更换手机号" width="400px">
      <el-form label-position="top">
        <el-form-item label="新手机号">
          <el-input v-model="bindForm.phone" placeholder="输入新的手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="短信验证码">
          <div class="code-row">
            <el-input v-model="bindForm.code" placeholder="验证码" maxlength="6" style="flex: 1" />
            <el-button>获取验证码</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="changePhoneVisible = false">取消</el-button>
        <el-button type="primary" @click="savePhone">确认更换</el-button>
      </template>
    </el-dialog>

    <!-- 更换邮箱 -->
    <el-dialog v-model="changeEmailVisible" title="更换邮箱" width="400px">
      <el-form label-position="top">
        <el-form-item label="新邮箱">
          <el-input v-model="bindForm.email" placeholder="输入新的邮箱地址" />
        </el-form-item>
        <el-form-item label="邮箱验证码">
          <div class="code-row">
            <el-input v-model="bindForm.code" placeholder="验证码" maxlength="6" style="flex: 1" />
            <el-button>获取验证码</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="changeEmailVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEmail">发送确认邮件</el-button>
      </template>
    </el-dialog>

    <!-- 开启 TOTP -->
    <el-dialog v-model="token2FAVisible" title="开启双因素认证" width="440px">
      <div class="totp-dialog">
        <p class="sec-desc">使用认证器 App 扫描下方二维码，或手动输入密钥</p>
        <div class="mfa-box">
          <div class="mfa-qr">
            <div class="qr-placeholder">TOTP<br />QR</div>
          </div>
          <div class="mfa-secret">
            <code class="secret-code">{{ totpSecret }}</code>
            <div class="mfa-verify">
              <el-input v-model="totpCode" maxlength="6" placeholder="6 位校验码" style="width: 160px" class="mono" />
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="token2FAVisible = false">取消</el-button>
        <el-button type="primary" @click="enableMFA">验证并开启</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sys-profile-page {
  height: 100%;
  overflow-y: auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

/* 头卡 */
.card {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}
.profile-card {
  padding: 0 4px 4px;
}
.profile-head {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 20px 22px 6px;
}
.avatar-wrap {
  position: relative;
  flex: none;
}
.avatar-big {
  width: 88px;
  height: 88px;
  border-radius: 50%;
  color: #fff;
  font-size: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.avatar-edit {
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  color: var(--el-color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: var(--shadow-s);
}
.profile-info {
  flex: 1;
  min-width: 0;
}
.profile-info h3 {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 18px;
  margin: 0 0 8px;
}
.tag-status {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 4px;
  line-height: 1.6;
}
.tag-status.ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.tag-status.role {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.tag-status.warn {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.profile-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}
.plain-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  background: var(--el-color-info-light-9);
  padding: 2px 8px;
  border-radius: 4px;
}
.profile-desc {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.profile-actions {
  flex: none;
  display: flex;
  gap: 8px;
}
.profile-tabs {
  padding: 0 18px;
}
:deep(.profile-tabs .el-tabs__header) {
  margin-bottom: 12px;
}

/* 基本资料 */
.tab-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
}
.form-grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.field {
  margin-bottom: 16px;
}
.field label {
  display: block;
  font-size: 13px;
  color: var(--el-text-color-regular);
  margin-bottom: 6px;
}
.req {
  color: var(--el-color-danger);
}
.readonly :deep(.el-input__inner) {
  background: var(--el-bg-color-page);
  color: var(--el-text-color-secondary);
  cursor: not-allowed;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.input-affix {
  position: relative;
}
.input-affix .el-button {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 2;
  font-size: 12px;
}
.save-row {
  display: flex;
  gap: 8px;
  padding-top: 2px;
}

/* 右栏 */
.side-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.mini-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 14px 16px;
}
.mini-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 12px;
}
.kv {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 9px 8px;
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
}
.kv dt {
  color: var(--el-text-color-secondary);
}
.kv dd {
  margin: 0;
  color: var(--el-text-color-primary);
}
.safe-bar {
  height: 6px;
  border-radius: 3px;
  background: var(--el-border-color-lighter);
  margin-bottom: 13px;
  overflow: hidden;
}
.safe-bar i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: var(--el-color-success);
}
.safe-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.safe-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
}
.safe-item span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.safe-item .yes {
  color: var(--el-color-success);
}
.safe-item .no {
  color: var(--el-color-warning);
}
.safe-item .muted {
  color: var(--el-text-color-secondary);
}

/* 安全设置 */
.sec-layout {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  gap: 20px;
}
.sec-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.sec-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: var(--el-text-color-regular);
  transition: all 0.15s;
}
.sec-item:hover {
  background: var(--el-bg-color-page);
}
.sec-item.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 500;
}
.sec-panel {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 20px 22px;
  min-height: 320px;
}
.sec-panel-inner h4 {
  margin: 0 0 6px;
  font-size: 15px;
}
.sec-desc {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  margin: 0 0 18px;
}
.bind-card {
  display: flex;
  flex-direction: column;
  max-width: 520px;
}
.bind-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.bind-info {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.bind-info b {
  min-width: 70px;
}
.mfa-box {
  display: flex;
  gap: 20px;
  align-items: flex-start;
  max-width: 520px;
}
.mfa-qr {
  text-align: center;
}
.qr-placeholder {
  width: 130px;
  height: 130px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  background: var(--el-bg-color-page);
  line-height: 1.6;
}
.qr-tip {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
  max-width: 140px;
}
.mfa-secret {
  flex: 1;
  min-width: 0;
}
.secret-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.secret-code {
  display: inline-block;
  font-family: var(--font-mono);
  font-size: 13px;
  background: var(--el-bg-color-page);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px 12px;
  letter-spacing: 1px;
  margin-bottom: 12px;
}
.secret-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.mfa-verify {
  display: flex;
  gap: 8px;
  align-items: center;
}
.totp-dialog .mfa-box {
  max-width: none;
}

/* 我的权限 */
.perm-page {
  padding-bottom: 8px;
}
.perm-head {
  margin-bottom: 14px;
}
.perm-group {
  margin-bottom: 14px;
}
.perm-group-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  margin-bottom: 8px;
  padding-left: 8px;
  border-left: 3px solid var(--el-color-primary);
}
.perm-codes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.perm-tag {
  font-family: var(--font-mono);
}
.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
</style>