<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Key, Refresh, ChatDotRound, Aim, Lock as LockIcon, Document } from '@element-plus/icons-vue'
import { authApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 登录方式：password | sms */
const mode = ref<'password' | 'sms'>('password')

const formRef = ref()
const loading = ref(false)
/** 图形验证码是否出现（失败 1 次后强制） */
const needCaptcha = ref(false)
const captchaImg = ref('')
const captchaId = ref('')

const form = reactive({
  account: '',
  password: '',
  phone: '',
  smsCode: '',
  captchaCode: '',
  remember: true,
})

function loadCaptcha() {
  authApi.captcha().then((data) => {
    captchaId.value = data.captchaId
    captchaImg.value = data.imageBase64
  })
}

onMounted(() => {
  if (needCaptcha.value) loadCaptcha()
})

// 切模式后重置验证码状态
watch(mode, () => {
  needCaptcha.value = false
  form.captchaCode = ''
})

const rules = {
  account: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    {
      validator: (_r: unknown, v: string, cb: (e?: Error) => void) => {
        if (!v) return cb(new Error('请输入账号'))
        const ok = /^[a-zA-Z0-9._-]{4,32}$/.test(v) || /^1\d{10}$/.test(v) || /.+@.+\..+/.test(v)
        ok ? cb() : cb(new Error('用户名 4-32 位 / 手机号 11 位 / 正确邮箱格式'))
      },
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6-32 位', trigger: 'blur' },
  ],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '请输入 11 位手机号', trigger: 'blur' },
  ],
  smsCode: [
    { required: true, message: '请输入短信验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '6 位数字验证码', trigger: 'blur' },
  ],
  captchaCode: [
    { required: true, message: '请输入图形验证码', trigger: 'blur' },
    { len: 4, message: '4 位字符', trigger: 'blur' },
  ],
}

/** 发送短信验证码（验证码登录模式） */
const smsSending = ref(false)
const smsCountdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

function sendSms() {
  if (!/^1\d{10}$/.test(form.phone)) {
    ElMessage.warning('请先输入正确的手机号')
    return
  }
  smsSending.value = true
  authApi
    .smsCode({ phone: form.phone, scene: 'login' })
    .then((data) => {
      ElMessage.success(data.debugCode ? `验证码已发送（调试码：${data.debugCode}）` : '验证码已发送')
      smsCountdown.value = 60
      timer && clearInterval(timer)
      timer = setInterval(() => {
        smsCountdown.value--
        if (smsCountdown.value <= 0 && timer) clearInterval(timer)
      }, 1000)
    })
    .finally(() => (smsSending.value = false))
}

async function onSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    let res: { user?: { realName?: string } }
    if (mode.value === 'password') {
      const payload: Record<string, unknown> = {
        remember: form.remember,
        account: form.account,
        password: form.password,
      }
      if (needCaptcha.value) {
        payload.captchaId = captchaId.value
        payload.captchaCode = form.captchaCode
      }
      res = (await auth.login(payload as never)) as { user?: { realName?: string } }
    } else {
      // 短信登录走独立接口（接口约定 §1 `/auth/login-by-sms`），不再用 `密码+ #sms` 占位 hack
      res = (await auth.loginBySms({ phone: form.phone, smsCode: form.smsCode, remember: form.remember })) as {
        user?: { realName?: string }
      }
    }
    auth.setRemember(form.remember)
    const name = res.user?.realName || '同学'
    ElMessage.success(`欢迎回来，${name}`)
    const redirect = (route.query.redirect as string) || '/chat'
    router.push(redirect)
  } catch (e) {
    // 登录失败 → 强制图形验证码
    needCaptcha.value = true
    loadCaptcha()
    ElMessage.error(e instanceof Error ? e.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <!-- 左：品牌区 -->
    <div class="auth-left">
      <div class="brand-row">
        <div class="brand-mark"><el-icon :size="18"><ChatDotRound /></el-icon></div>
        AI 知识库平台
      </div>
      <div class="auth-hero">
        <h1>把企业文档<br />变成可对话的知识</h1>
        <p>多格式文档解析 · 向量检索 · 大模型问答。上传即入库，提问即得答案，每个回答都能追溯到原文出处。</p>
      </div>
      <div class="auth-feats">
        <div class="feat"><span class="fi"><el-icon><Document /></el-icon></span>支持 PDF / Word / Excel / Markdown 等 9 种格式</div>
        <div class="feat"><span class="fi"><el-icon><Aim /></el-icon></span>混合检索 + 引用溯源，答案有据可查</div>
        <div class="feat"><span class="fi"><el-icon><LockIcon /></el-icon></span>知识库级权限隔离，多租户数据不串门</div>
      </div>
      <div class="auth-foot">© 2026 AI Knowledge Base · v1.0.0</div>
    </div>

    <!-- 右：登录表单 -->
    <div class="auth-right">
      <div class="auth-card">
        <h2>欢迎回来 👋</h2>
        <div class="lead">登录后即可管理知识库并发起问答</div>

        <el-segmented v-model="mode" :options="[
          { label: '密码登录', value: 'password' },
          { label: '验证码登录', value: 'sms' },
        ]" block class="mode-seg" />

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large">
          <!-- 密码登录 -->
          <template v-if="mode === 'password'">
            <el-form-item label="账号" prop="account">
              <el-input v-model="form.account" placeholder="用户名 / 手机号 / 邮箱" :prefix-icon="User" clearable @keyup.enter="onSubmit" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="form.password" type="password" placeholder="请输入密码" :prefix-icon="Lock" show-password @keyup.enter="onSubmit" />
            </el-form-item>
          </template>

          <!-- 验证码登录 -->
          <template v-else>
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="form.phone" placeholder="11 位手机号" :prefix-icon="User" maxlength="11" @keyup.enter="onSubmit" />
            </el-form-item>
            <el-form-item label="短信验证码" prop="smsCode">
              <div class="sms-row">
                <el-input v-model="form.smsCode" placeholder="6 位数字" :prefix-icon="Lock" maxlength="6" @keyup.enter="onSubmit" />
                <el-button :loading="smsSending" :disabled="smsCountdown > 0" @click="sendSms">
                  {{ smsCountdown > 0 ? `${smsCountdown}s 后重发` : '获取验证码' }}
                </el-button>
              </div>
            </el-form-item>
          </template>

          <!-- 图形验证码（失败 1 次后出现） -->
          <el-form-item v-if="needCaptcha" label="图形验证码" prop="captchaCode">
            <div class="captcha-row">
              <el-input v-model="form.captchaCode" placeholder="请输入右侧 4 位字符" :prefix-icon="Key" maxlength="4" @keyup.enter="onSubmit" />
              <img v-if="captchaImg" :src="captchaImg" alt="验证码" class="captcha-img" title="点击刷新" @click="loadCaptcha" />
              <el-button :icon="Refresh" title="刷新验证码" @click="loadCaptcha" />
            </div>
          </el-form-item>

          <div class="row-between">
            <el-checkbox v-model="form.remember">记住我（7 天免登录）</el-checkbox>
            <el-link type="primary" :underline="false" @click="router.push('/forgot-password')">忘记密码？</el-link>
          </div>

          <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="onSubmit">登 录</el-button>
        </el-form>

        <div class="divider">其他登录方式</div>
        <div class="oauth">
          <el-button plain class="o">企业微信</el-button>
          <el-button plain class="o">钉钉</el-button>
          <el-button plain class="o">统一身份认证</el-button>
        </div>

        <div class="auth-alt">
          还没有账号？<button class="btn-text" @click="router.push('/register')">立即注册</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mode-seg {
  margin-bottom: 22px;
}
.sms-row,
.captcha-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.sms-row .el-input,
.captcha-row .el-input {
  flex: 1;
}
.captcha-img {
  width: 108px;
  height: 40px;
  border-radius: 6px;
  border: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
  flex: none;
}
.row-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}
.login-btn {
  width: 100%;
  letter-spacing: 4px;
  margin-bottom: 8px;
}
.oauth {
  display: flex;
  gap: 10px;
  margin-bottom: 22px;
}
.oauth .o {
  flex: 1;
}
.btn-text {
  color: var(--el-color-primary);
  cursor: pointer;
  border: none;
  background: none;
  padding: 0;
  font-size: 13px;
  font-weight: 500;
}
</style>