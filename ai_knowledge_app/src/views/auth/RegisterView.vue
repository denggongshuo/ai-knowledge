<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, ChatDotRound, Checked, Document } from '@element-plus/icons-vue'
import { authApi } from '@/api'

const router = useRouter()

const formRef = ref()
const loading = ref(false)

// 用户名实时可用性
const usernameState = ref<'idle' | 'checking' | 'ok' | 'taken'>('idle')
const usernameReason = ref('')

// 密码强度
const form = reactive({
  username: '',
  phone: '',
  smsCode: '',
  password: '',
  confirmPassword: '',
  realName: '',
  agree: false,
})

const smsSending = ref(false)
const smsCountdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

function checkUsername() {
  const v = form.username.trim()
  if (!v || v.length < 4) {
    usernameState.value = 'idle'
    return
  }
  usernameState.value = 'checking'
  authApi
    .checkUsername(v)
    .then((data) => {
      usernameState.value = data.available ? 'ok' : 'taken'
      usernameReason.value = data.reason
    })
    .catch(() => (usernameState.value = 'idle'))
}

function sendSms() {
  if (!/^1\d{10}$/.test(form.phone.replace(/\s/g, ''))) {
    ElMessage.warning('请先输入正确的手机号')
    return
  }
  const checking = authApi.checkPhone(form.phone.replace(/\s/g, ''))
  checking.then((data) => {
    if (!data.available) {
      ElMessage.warning(data.reason)
      return
    }
    smsSending.value = true
    authApi
      .smsCode({ phone: form.phone, scene: 'register' })
      .then((d) => {
        ElMessage.success(d.debugCode ? `验证码已发送（调试码：${d.debugCode}）` : '验证码已发送')
        smsCountdown.value = 60
        timer && clearInterval(timer)
        timer = setInterval(() => {
          smsCountdown.value--
          if (smsCountdown.value <= 0 && timer) clearInterval(timer)
        }, 1000)
      })
      .finally(() => (smsSending.value = false))
  })
}

/** 密码强度 0-3 */
const pwStrength = computed(() => {
  const p = form.password
  if (!p) return 0
  let s = 0
  if (/[a-z]/.test(p) && /[A-Z]/.test(p)) s++
  if (/\d/.test(p)) s++
  if (/[^a-zA-Z0-9]/.test(p)) s++
  if (p.length >= 8) s++
  return s
})
const pwStrengthText = computed(() => ['', '弱', '中', '强', '很强'][pwStrength.value] || '')

const validateConfirm = (_r: unknown, v: string, cb: (e?: Error) => void) => {
  if (v !== form.password) cb(new Error('两次输入的密码不一致'))
  else cb()
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9._-]{4,32}$/, message: '4-32 位字母/数字/下划线/点', trigger: 'blur' },
  ],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '11 位大陆手机号', trigger: 'blur' },
  ],
  smsCode: [
    { required: true, message: '请输入短信验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '6 位数字，5 分钟内有效', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请设置密码', trigger: 'blur' },
    { min: 8, max: 32, message: '密码长度 8-32 位', trigger: 'blur' },
    {
      validator: (_r: unknown, v: string, cb: (e?: Error) => void) => {
        // 至少含字母 + 数字
        if (v && !/[a-zA-Z]/.test(v)) return cb(new Error('密码需包含字母'))
        if (v && !/\d/.test(v)) return cb(new Error('密码需包含数字'))
        cb()
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [{ required: true, message: '请再次输入密码', trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }],
  realName: [{ max: 20, message: '不超过 20 字', trigger: 'blur' }],
}

async function onSubmit() {
  if (!formRef.value) return
  if (!form.agree) {
    ElMessage.warning('请先阅读并同意《服务协议》与《隐私政策》')
    return
  }
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await authApi.register({
      username: form.username,
      phone: form.phone.replace(/\s/g, ''),
      smsCode: form.smsCode,
      password: form.password,
      realName: form.realName || undefined,
    })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-left">
      <div class="brand-row">
        <div class="brand-mark"><el-icon :size="18"><ChatDotRound /></el-icon></div>
        AI 知识库平台
      </div>
      <div class="auth-hero">
        <h1>创建账号，<br />解锁检索问答能力</h1>
        <p>注册后自动获得「普通用户」角色，即可访问你有权限的知识库，发起检索与问答。</p>
      </div>
      <div class="auth-feats">
        <div class="feat"><span class="fi"><el-icon><Checked /></el-icon></span>企业级账号认证，数据与权限隔离</div>
        <div class="feat"><span class="fi"><el-icon><Document /></el-icon></span>注册即用，无需等待管理员审批</div>
        <div class="feat"><span class="fi"><el-icon><User /></el-icon></span>统一用户名体系，支持手机号找回</div>
      </div>
      <div class="auth-foot">© 2026 AI Knowledge Base · v1.0.0</div>
    </div>

    <div class="auth-right">
      <div class="auth-card">
        <h2>创建账号</h2>
        <div class="lead">
          已有账号？
          <el-link type="primary" :underline="false" @click="router.push('/login')">直接登录</el-link>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large">
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              placeholder="字母/数字/下划线/点，4-32 位"
              :prefix-icon="User"
              maxlength="32"
              clearable
              @blur="checkUsername"
              @keyup.enter="onSubmit"
            >
              <template #suffix>
                <span v-if="usernameState === 'ok'" class="ck ok">可用</span>
                <span v-else-if="usernameState === 'taken'" class="ck no">{{ usernameReason }}</span>
                <span v-else-if="usernameState === 'checking'" class="ck">检测中…</span>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="手机号" prop="phone">
            <div class="phone-row">
              <el-input v-model="form.phone" placeholder="11 位手机号" :prefix-icon="User" maxlength="11" @keyup.enter="onSubmit" />
            </div>
          </el-form-item>

          <el-form-item label="短信验证码" prop="smsCode">
            <div class="sms-row">
              <el-input v-model="form.smsCode" placeholder="6 位数字验证码" :prefix-icon="Lock" maxlength="6" @keyup.enter="onSubmit" />
              <el-button :loading="smsSending" :disabled="smsCountdown > 0" @click="sendSms">
                {{ smsCountdown > 0 ? `${smsCountdown}s 后重发` : '获取验证码' }}
              </el-button>
            </div>
          </el-form-item>

          <el-form-item label="设置密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="8-32 位，含字母与数字" :prefix-icon="Lock" show-password @keyup.enter="onSubmit" />
            <template #error>
              <div v-if="form.password" class="pw-hint">
                <div class="pw-meter">
                  <i v-for="i in 4" :key="i" :class="i <= pwStrength ? (pwStrength >= 3 ? 's' : 'w') : ''"></i>
                </div>
                <span class="text-secondary">强度：{{ pwStrengthText }} · 建议包含大小写字母、数字与符号，长度 ≥ 8 位</span>
              </div>
            </template>
          </el-form-item>

          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" placeholder="再次输入密码" :prefix-icon="Lock" show-password @keyup.enter="onSubmit" />
          </el-form-item>

          <el-form-item label="真实姓名" prop="realName">
            <el-input v-model="form.realName" placeholder="选填，用于团队内展示" :prefix-icon="User" maxlength="20" @keyup.enter="onSubmit" />
          </el-form-item>

          <el-checkbox v-model="form.agree" class="agree">
            我已阅读并同意<el-link type="primary" :underline="false">《服务协议》</el-link>与
            <el-link type="primary" :underline="false">《隐私政策》</el-link>
          </el-checkbox>

          <el-button type="primary" size="large" class="reg-btn" :loading="loading" @click="onSubmit">注 册</el-button>
        </el-form>

        <div class="divider">或使用第三方账号注册</div>
        <div class="oauth">
          <el-button plain class="o">企业微信</el-button>
          <el-button plain class="o">钉钉</el-button>
          <el-button plain class="o">统一身份认证</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.phone-row {
  width: 100%;
}
.sms-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.sms-row .el-input {
  flex: 1;
}
.ck {
  font-size: 12px;
  white-space: nowrap;
}
.ck.ok {
  color: var(--el-color-success);
}
.ck.no {
  color: var(--el-color-danger);
}
.pw-hint {
  margin-top: 4px;
}
.pw-meter {
  display: flex;
  gap: 5px;
  margin-bottom: 6px;
}
.pw-meter i {
  flex: 1;
  height: 4px;
  border-radius: 3px;
  background: var(--el-border-color-lighter);
}
.pw-meter i.w {
  background: var(--el-color-warning);
}
.pw-meter i.s {
  background: var(--el-color-success);
}
.agree {
  margin-bottom: 16px;
  font-size: 13px;
  line-height: 1.6;
  align-items: flex-start;
}
.reg-btn {
  width: 100%;
  letter-spacing: 4px;
  margin-bottom: 8px;
}
.oauth {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}
.oauth .o {
  flex: 1;
}
</style>