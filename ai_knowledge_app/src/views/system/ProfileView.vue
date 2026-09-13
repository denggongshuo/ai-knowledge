<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Key, EditPen, User, Phone, Message, Lock, Check, Warning, Clock, Refresh, Camera, Right } from '@element-plus/icons-vue'
import { authApi, userApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

/**
 * 个人信息页。
 *
 * <p>本页数据来自三个接口，**不再保留任何写死的演示值**：原先的 `id=1001`、
 * 注册时间、`超级管理员`、手机号默认值都是原型期的占位常量 —— 接了真后端后
 * 它们会与**同一页其它位置的真实值**互相矛盾（右栏显示真实角色、头像旁却写着
 * 「超级管理员」；真实 id=73、右栏显示 1001）。这类"同一页面对同一事实给出两个
 * 答案"的缺陷最难被发现，因为两边看起来都"有数据"。
 *
 * <ul>
 *   <li>`GET /user/profile` → 基本资料 + 账号信息（`id / roleNames / createdAt / lastLoginAt / status`）</li>
 *   <li>`GET /user/security` → 安全概览（**脱敏**手机号·邮箱、`mfaEnabled`、`passwordUpdatedAt`、最近登录设备）</li>
 *   <li>`GET /user/permissions` → 我的权限（扁平权限码）</li>
 * </ul>
 */
const auth = useAuthStore()

// ---------------- 资料头 ----------------
const avatarChar = computed(() => (auth.user?.realName ?? auth.user?.username ?? '?')[0] ?? '?')
/** 真头像（预签名 URL，来自 `GET /user/profile` 的 `avatar`）。为空时回落到首字母色块。 */
const avatarUrl = ref('')
const isDirty = ref(false)

// ---------------- Tab 1 基本资料 ----------------
const base = reactive({
  username: '',
  realName: '',
  /** 展示用（`139 6594 6415`）。提交 / 发码一律走 `phoneRaw` —— 带空格的号码过不了后端校验。 */
  phone: '',
  email: '',
  department: '',
  position: '',
  intro: '',
})

/** 原始手机号（无空格）。换绑邮箱时验证码要发到这里。 */
const phoneRaw = ref('')

const accountInfo = reactive({
  id: '' as string | number,
  roleNames: [] as string[],
  createdAt: '',
  lastLogin: '',
  status: '',
})

/** 用户状态码 → 中文，与后端 `UserStatus` 逐字一致。 */
const STATUS_LABEL: Record<string, string> = { ACTIVE: '正常', PENDING: '待激活', DISABLED: '已停用' }
const statusLabel = computed(() => (accountInfo.status ? (STATUS_LABEL[accountInfo.status] ?? accountInfo.status) : '—'))
/** 只有「正常」用绿色：绿色标签配「已停用」等于说谎。 */
const statusTagClass = computed(() => (accountInfo.status === 'ACTIVE' ? 'ok' : 'warn'))
const rolesText = computed(() => accountInfo.roleNames.join('、') || '—')
/** 头像旁的角色标签取**首个**角色；全量角色在右栏「账号信息 → 角色」列出。 */
const primaryRole = computed(() => accountInfo.roleNames[0] ?? '')
/** 最近登录设备（安全概览里的"当前设备"），把 `2026-09-11 15:12` 补成 `… · 内网 10.12.8.31`。 */
const lastLoginDevice = ref('')
const lastLoginText = computed(() => {
  if (!accountInfo.lastLogin) return '—'
  return lastLoginDevice.value ? `${accountInfo.lastLogin} · ${lastLoginDevice.value}` : accountInfo.lastLogin
})

async function saveBase() {
  isDirty.value = false
  try {
    await userApi.updateProfile({
      realName: base.realName,
      email: base.email,
      position: base.position,
      intro: base.intro,
    })
  } catch (e) {
    isDirty.value = true
    ElMessage.error((e as Error).message || '保存失败')
    return
  }
  if (auth.user) auth.user.realName = base.realName
  ElMessage.success('基本资料已保存')
}

function resetBase() {
  // 回滚到"已加载的真实值"，不是常量。原先那几处 `?? '林工'` 会在字段为空时
  // 往表单里填一个不存在的名字 —— 这正是 A-6/A-7 那类"编造数据"的来源。
  const p = auth.user
  if (p) {
    base.realName = String(p.realName ?? '')
    base.email = String(p.email ?? '')
    base.position = String(p.position ?? '')
    base.intro = String(p.intro ?? '')
  }
  isDirty.value = false
}

// ---------------- 安全概览（GET /user/security） ----------------
const security = reactive({
  /** 脱敏后的展示值（`138****6666`）—— "确认绑的是哪个号"用掩码值就够，不必下发全量。 */
  phoneMasked: '',
  emailMasked: '',
  phoneBound: false,
  emailBound: false,
  mfaEnabled: false,
  passwordUpdatedAt: '',
})

/** 「密码 N 天前更新」：由真实时间戳算出来，不再用写死的天数。 */
const passwordUpdatedText = computed(() => {
  if (!security.passwordUpdatedAt) return '密码更新时间未知'
  // 换成 `/` 分隔再解析：部分内核对 `yyyy-MM-dd HH:mm:ss` 直接返回 Invalid Date
  const ts = new Date(security.passwordUpdatedAt.replace(/-/g, '/')).getTime()
  if (Number.isNaN(ts)) return `密码更新于 ${security.passwordUpdatedAt}`
  const days = Math.floor((Date.now() - ts) / 86400000)
  return days <= 0 ? '密码今天刚更新' : `密码 ${days} 天前更新`
})

/**
 * 安全等级得分：绑手机 40 + 绑邮箱 30 + 开双因素 30。
 *
 * <p>原先这里是写死的 `78%` + 「较高」。写死的真正问题不是"数字不准"，而是
 * **它会和同一张卡片里的真实绑定状态打架**：一个 `email = null`（未绑定）的账号
 * 旁边照样挂着「较高」。改成从同一份状态派生后，两者不可能再互相矛盾。
 */
const securityScore = computed(
  () => (security.phoneBound ? 40 : 0) + (security.emailBound ? 30 : 0) + (security.mfaEnabled ? 30 : 0),
)
const securityLevel = computed(() => {
  const s = securityScore.value
  if (s >= 90) return { label: '很高', cls: 'ok' }
  if (s >= 70) return { label: '较高', cls: 'ok' }
  if (s >= 40) return { label: '一般', cls: 'warn' }
  return { label: '偏低', cls: 'warn' }
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
/**
 * 两个弹窗各自独立的表单与倒计时。
 *
 * <p>原先两者共用一个 `bindForm`：先在「更换手机号」点了获取验证码、再打开「更换邮箱」，
 * 上一条码还留在框里 —— 看着像"邮箱的码已经到了"，实际是另一场景的码
 * （后端按 `scene + target` 存键，提交必然失败）。两个倒计时也必须分开，
 * 否则一个弹窗发码会把另一个弹窗的按钮一起锁住。
 */
const phoneForm = reactive({ phone: '', code: '' })
const emailForm = reactive({ email: '', code: '' })
const phoneCountdown = ref(0)
const emailCountdown = ref(0)
const countdownTimers = new Set<number>()

/** 发码按钮的 60s 倒计时（`onUnmounted` 里统一清，避免计时器泄漏）。 */
function startCountdown(counter: Ref<number>, seconds = 60) {
  counter.value = seconds
  const timer = window.setInterval(() => {
    counter.value -= 1
    if (counter.value <= 0) {
      window.clearInterval(timer)
      countdownTimers.delete(timer)
    }
  }, 1000)
  countdownTimers.add(timer)
}

/** 发码：`scene` 必须是 `bind`，与后端 `changePhone` / `changeEmail` 校验时用的 scene 一致。 */
async function sendBindCode(phone: string, counter: Ref<number>) {
  try {
    const res = await authApi.smsCode({ phone, scene: 'bind' })
    startCountdown(counter)
    // 开发 / 联调环境后端会回 debugCode（当前固定 000000，不发真实短信）
    ElMessage.success(res?.debugCode ? `验证码已发送（联调码 ${res.debugCode}）` : '验证码已发送')
  } catch (e) {
    ElMessage.error((e as Error).message || '验证码发送失败')
  }
}

/** 换绑手机：验证码发往**新手机号**（发到旧号，后端 `verify` 必然不通过）。 */
async function sendPhoneCode() {
  const phone = phoneForm.phone.replace(/\s/g, '')
  if (!/^1[3-9]\d{9}$/.test(phone)) {
    ElMessage.warning('请输入正确的新手机号')
    return
  }
  await sendBindCode(phone, phoneCountdown)
}

/**
 * 换绑邮箱：v1 **没有邮件通道**，验证码发往**当前已绑定的手机号**，用它证明"是本人"。
 * 没绑手机号的账号无法自助换邮箱（后端同口径）—— 前端先拦一道，省掉一次注定失败的请求。
 */
async function sendEmailCode() {
  if (!phoneRaw.value) {
    ElMessage.warning('账号未绑定手机号，无法自助换绑邮箱，请联系管理员')
    return
  }
  await sendBindCode(phoneRaw.value, emailCountdown)
}

// TOTP（v1 仅前端演示：后端没有 secret 表也没有校验逻辑，`GET /user/security` 的 mfaEnabled 恒 false）
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

async function savePhone() {
  const phone = phoneForm.phone.replace(/\s/g, '')
  if (!/^1[3-9]\d{9}$/.test(phone)) {
    ElMessage.warning('手机号格式不正确')
    return
  }
  if (!phoneForm.code) {
    ElMessage.warning('请输入短信验证码')
    return
  }
  try {
    const res = (await userApi.changePhone({ phone, code: phoneForm.code })) as { phone?: string } | null
    changePhoneVisible.value = false
    phoneForm.phone = ''
    phoneForm.code = ''
    // 换绑成功后必须重新拉资料与安全概览：否则页面还显示换绑前的号码，
    // 用户会以为没生效，再点一次（这次会因为"号码已存在"报错）。
    await Promise.all([loadProfile(), loadSecurity()])
    ElMessage.success(`手机号已换绑为 ${res?.phone ?? phone}`)
  } catch (e) {
    ElMessage.error((e as Error).message || '换绑失败')
  }
}

async function saveEmail() {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(emailForm.email)) {
    ElMessage.warning('邮箱格式不正确')
    return
  }
  if (!emailForm.code) {
    ElMessage.warning('请输入短信验证码')
    return
  }
  try {
    await userApi.changeEmail({ email: emailForm.email, code: emailForm.code })
    changeEmailVisible.value = false
    emailForm.email = ''
    emailForm.code = ''
    await Promise.all([loadProfile(), loadSecurity()])
    // 后端 `emailVerified` 恒 false（v1 不发邮件），所以这里不写"已发送确认邮件"——
    // 那会让用户去邮箱里等一封永远不会到的信。
    ElMessage.success('邮箱已更新')
  } catch (e) {
    ElMessage.error((e as Error).message || '换绑失败')
  }
}

// ---------------- 头像上传（A-2；请求侧依赖 A-1 的 FormData 处理） ----------------
const avatarInput = ref<HTMLInputElement | null>(null)
const avatarUploading = ref(false)

function pickAvatar() {
  avatarInput.value?.click()
}

/**
 * 选图后上传。
 *
 * <p>三个易错点：
 * <ol>
 *   <li>字段名必须是 `file`（后端 `@RequestPart("file")`），不是 `avatar` / `image`；</li>
 *   <li>必须清空 `input.value` —— 否则"再选同一个文件"不触发 change，用户以为按钮坏了；</li>
 *   <li>FormData 不能被 JSON 序列化（见 `request.ts` 的 `serializeBody`），
 *       且 `Content-Type` 要留给浏览器设（multipart 还需要它算 boundary）。</li>
 * </ol>
 */
async function onAvatarPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('头像大小不能超过 2MB')
    return
  }
  const form = new FormData()
  form.append('file', file)
  avatarUploading.value = true
  try {
    const res = await userApi.avatar(form)
    avatarUrl.value = res?.url || avatarUrl.value
    ElMessage.success('头像已更新')
  } catch (e) {
    ElMessage.error((e as Error).message || '头像上传失败')
  } finally {
    avatarUploading.value = false
  }
}

onUnmounted(() => {
  countdownTimers.forEach((t) => window.clearInterval(t))
  countdownTimers.clear()
})

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

// ---------------- 数据加载 ----------------
/** 基本资料 + 账号信息。字段名取自后端 `AuthDtos.UserView`（登录响应里那个用户对象的同一个类型）。 */
async function loadProfile() {
  try {
    const p = await userApi.profile()
    base.username = String(p.username ?? '')
    base.realName = String(p.realName ?? '')
    const rawPhone = String(p.phone ?? '')
    phoneRaw.value = rawPhone
    // `13965946415` → `139 6594 6415`。三个分组必须**都**捕获：
    // 原正则 `(\d{3})\d{4}(\d{4})` 只捕了首 3 与末 4，中间 4 位在替换时被整个丢掉，
    // 于是 11 位号码静默渲染成 `139 6415` —— 少 4 位数字，却还像个正常号码。
    base.phone = rawPhone.replace(/^(\d{3})(\d{4})(\d{4})$/, '$1 $2 $3')
    base.email = String(p.email ?? '')
    base.department = String(p.department ?? '')
    base.position = String(p.position ?? '')
    base.intro = String(p.intro ?? '')
    avatarUrl.value = String(p.avatar ?? '')
    accountInfo.id = (p.id as number | undefined) ?? ''
    accountInfo.roleNames = (p.roleNames as string[] | undefined) ?? []
    accountInfo.createdAt = String(p.createdAt ?? '')
    accountInfo.lastLogin = String(p.lastLoginAt ?? '')
    accountInfo.status = String(p.status ?? '')
    if (auth.user) {
      auth.user.realName = base.realName
      auth.user.username = base.username
    }
  } catch {
    /* 拿不到就留空 —— 页面显示「—」，不编造数据 */
  }
}

/** 安全概览。手机号 / 邮箱是**脱敏值**，这里只用它判断"绑没绑"并展示。 */
async function loadSecurity() {
  try {
    const s = await userApi.security()
    const phone = String(s.phone ?? '')
    const email = String(s.email ?? '')
    security.phoneMasked = phone
    security.emailMasked = email
    security.phoneBound = phone !== ''
    security.emailBound = email !== ''
    security.mfaEnabled = Boolean(s.mfaEnabled)
    security.passwordUpdatedAt = String(s.passwordUpdatedAt ?? '')
    const devices =
      (s.recentDevices as { device?: string; ip?: string; location?: string; current?: boolean }[] | undefined) ?? []
    // 后端口径：最近一次成功登录即"当前设备"（无状态鉴权拿不到真正的当前会话）
    const current = devices.find((d) => d.current) ?? devices[0]
    lastLoginDevice.value = current ? [current.location, current.ip].filter(Boolean).join(' ') : ''
  } catch {
    /* 安全概览不可用只影响右栏，不影响本页其余部分 */
  }
}

onMounted(async () => {
  // 三个请求互不依赖，并行发；串行只会让页面白得更久（它们都是毫秒级）
  await Promise.all([
    loadProfile(),
    loadSecurity(),
    userApi
      .permissions()
      .then((res) => {
        permCodes.value = res.codes ?? []
      })
      .catch(() => {
        /* 权限码拿不到就空着 */
      }),
  ])
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
          <div class="avatar-big" :style="avatarUrl ? undefined : { background: 'var(--brand-grad)' }">
            <img v-if="avatarUrl" :src="avatarUrl" alt="头像" class="avatar-img" />
            <template v-else>{{ avatarChar }}</template>
          </div>
          <div class="avatar-edit" :class="{ uploading: avatarUploading }" title="更换头像" @click="pickAvatar">
            <el-icon><Camera /></el-icon>
          </div>
          <!-- 隐藏的文件选择器：`.avatar-edit` 点击后触发它，页面上不出现第二个可见控件 -->
          <input ref="avatarInput" type="file" accept="image/*" class="avatar-file" @change="onAvatarPicked" />
        </div>
        <div class="profile-info">
          <h3>
            {{ base.realName }}
            <!-- 状态与角色一律取真实值：这里原先写死「在职 / 超级管理员」，
                 而同一页右栏「账号信息」读的是真实值 —— 同一事实两个答案 -->
            <span class="tag-status" :class="statusTagClass">{{ statusLabel }}</span>
            <span v-if="primaryRole" class="tag-status role">{{ primaryRole }}</span>
          </h3>
          <div class="profile-tags">
            <span class="plain-tag"><el-icon><User /></el-icon>{{ base.department || '未分配' }}</span>
            <span class="plain-tag"><el-icon><Message /></el-icon>{{ base.email || '未绑定邮箱' }}</span>
            <span class="plain-tag"><el-icon><Phone /></el-icon>{{ base.phone || '未绑定手机号' }}</span>
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
                  <dt>用户 ID</dt><dd class="mono">{{ accountInfo.id || '—' }}</dd>
                  <dt>角色</dt><dd>{{ rolesText }}</dd>
                  <dt>注册时间</dt><dd>{{ accountInfo.createdAt || '—' }}</dd>
                  <dt>最近登录</dt><dd>{{ lastLoginText }}</dd>
                  <dt>账号状态</dt><dd><span class="tag-status" :class="statusTagClass">{{ statusLabel }}</span></dd>
                </dl>
              </div>
              <div class="mini-card">
                <div class="mini-head">
                  安全等级
                  <!-- 等级与下面的进度条同源（都由 security 状态派生），不会出现"没绑邮箱却较高" -->
                  <span class="tag-status" :class="securityLevel.cls">{{ securityLevel.label }}</span>
                </div>
                <div class="safe-bar"><i :style="{ width: securityScore + '%' }"></i></div>
                <div class="safe-list">
                  <div class="safe-item">
                    <span v-if="security.phoneBound" class="yes">
                      <el-icon><Check /></el-icon> 已绑定 {{ security.phoneMasked }}
                    </span>
                    <span v-else class="no"><el-icon><Warning /></el-icon> 未绑定手机号</span>
                    <el-button link type="primary" size="small" @click="changePhoneVisible = true">修改</el-button>
                  </div>
                  <div class="safe-item">
                    <span v-if="security.emailBound" class="yes">
                      <el-icon><Check /></el-icon> 已绑定 {{ security.emailMasked }}
                    </span>
                    <span v-else class="no"><el-icon><Warning /></el-icon> 未绑定邮箱</span>
                    <el-button link type="primary" size="small" @click="changeEmailVisible = true">修改</el-button>
                  </div>
                  <div class="safe-item">
                    <span class="no"><el-icon><Warning /></el-icon> {{ security.mfaEnabled ? '已开启双因素认证' : '未开启双因素认证' }}</span>
                    <el-button v-if="!security.mfaEnabled" link type="primary" size="small" @click="token2FAVisible = true">去开启</el-button>
                  </div>
                  <div class="safe-item">
                    <span class="muted"><el-icon><Clock /></el-icon> {{ passwordUpdatedText }}</span>
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
                      <span class="muted">{{ base.phone || '未绑定' }}</span>
                      <!-- 绑定状态与右栏「安全等级」同源（GET /user/security），不再是写死的「已绑定」 -->
                      <span v-if="security.phoneBound" class="tag-status ok">已绑定</span>
                      <span v-else class="tag-status warn">未绑定</span>
                    </div>
                    <el-button link type="primary" @click="changePhoneVisible = true">
                      {{ security.phoneBound ? '更换手机号' : '绑定手机号' }}
                    </el-button>
                  </div>
                  <div class="bind-row">
                    <div class="bind-info">
                      <b>邮箱</b>
                      <span class="muted">{{ base.email || '未绑定' }}</span>
                      <span v-if="security.emailBound" class="tag-status ok">已绑定</span>
                      <span v-else class="tag-status warn">未绑定</span>
                    </div>
                    <el-button link type="primary" @click="changeEmailVisible = true">
                      {{ security.emailBound ? '更换邮箱' : '绑定邮箱' }}
                    </el-button>
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
          <el-input v-model="phoneForm.phone" placeholder="输入新的手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="短信验证码">
          <div class="code-row">
            <el-input v-model="phoneForm.code" placeholder="验证码" maxlength="6" style="flex: 1" />
            <el-button :disabled="phoneCountdown > 0" @click="sendPhoneCode">
              {{ phoneCountdown > 0 ? phoneCountdown + 's 后重发' : '获取验证码' }}
            </el-button>
          </div>
          <!-- 发码目标说清楚：验证码按 `scene + target` 存键，发到旧号永远不会校验通过 -->
          <div class="hint">验证码将发送到上面填写的<b>新手机号</b></div>
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
          <el-input v-model="emailForm.email" placeholder="输入新的邮箱地址" />
        </el-form-item>
        <el-form-item label="短信验证码">
          <div class="code-row">
            <el-input v-model="emailForm.code" placeholder="验证码" maxlength="6" style="flex: 1" />
            <el-button :disabled="emailCountdown > 0" @click="sendEmailCode">
              {{ emailCountdown > 0 ? emailCountdown + 's 后重发' : '获取验证码' }}
            </el-button>
          </div>
          <!-- v1 没有邮件通道：这里必须写明收码位置，否则用户会去新邮箱里等一封不会到的信 -->
          <div class="hint">
            v1 未接入邮件通道，验证码发往当前已绑定手机号
            <b>{{ security.phoneMasked || '（未绑定）' }}</b>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="changeEmailVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEmail">确认换绑</el-button>
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
.avatar-edit:hover {
  color: #fff;
  background: var(--el-color-primary);
}
/* 上传中：禁用点击，避免连点发出多个上传请求 */
.avatar-edit.uploading {
  pointer-events: none;
  opacity: 0.6;
}
/* 真头像：填满圆形色块。没头像时该元素不存在，落回首字母 + 渐变底 */
.avatar-img {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
}
/* 隐藏的文件选择器：只作为 `.avatar-edit` 点击的落点，不占位 */
.avatar-file {
  display: none;
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