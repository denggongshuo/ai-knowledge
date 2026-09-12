<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Key, Refresh, ChatDotRound, Checked, Check, CircleCheckFilled } from '@element-plus/icons-vue'
import { authApi } from '@/api'

const router = useRouter()

/** 1 验证身份 → 2 设置新密码 → 3 完成 */
const step = ref(1)
const resetToken = ref('')

const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  captchaCode: '',
  phone: '',
  smsCode: '',
  password: '',
  confirmPassword: '',
})

const captchaImg = ref('')
const captchaId = ref('')

function loadCaptcha() {
  authApi.captcha().then((data) => {
    captchaId.value = data.captchaId
    captchaImg.value = data.imageBase64
  })
}

const smsSending = ref(false)
const smsCountdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

function sendSms() {
  if (!form.username) {
    ElMessage.warning('请先输入用户名')
    return
  }
  smsSending.value = true
  authApi
    .smsCode({ phone: form.phone || '13888886666', scene: 'reset-password' })
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
}

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

const validateConfirm = (_r: unknown, v: string, cb: (e?: Error) => void) => {
  if (v !== form.password) cb(new Error('两次输入的密码不一致'))
  else cb()
}

const rulesStep1 = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  captchaCode: [
    { required: true, message: '请输入图形验证码', trigger: 'blur' },
    { len: 4, message: '4 位字符', trigger: 'blur' },
  ],
  smsCode: [
    { required: true, message: '请输入短信验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '6 位数字', trigger: 'blur' },
  ],
}

const rulesStep2 = {
  password: [
    { required: true, message: '请设置新密码', trigger: 'blur' },
    { min: 8, max: 32, message: '密码长度 8-32 位', trigger: 'blur' },
    {
      validator: (_r: unknown, v: string, cb: (e?: Error) => void) => {
        if (v && !/[a-zA-Z]/.test(v)) return cb(new Error('密码需包含字母'))
        if (v && !/\d/.test(v)) return cb(new Error('密码需包含数字'))
        cb()
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [{ required: true, message: '请再次输入密码', trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }],
}

async function onVerify() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    const res = await authApi.forgotVerify({
      username: form.username,
      phone: '13888886666',
      smsCode: form.smsCode,
    })
    resetToken.value = res.token
    form.phone = res.maskedPhone
    step.value = 2
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '验证失败')
  } finally {
    loading.value = false
  }
}

async function onReset() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await authApi.forgotReset({
      token: resetToken.value,
      password: form.password,
    })
    step.value = 3
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重置失败')
  } finally {
    loading.value = false
  }
}

function backToLogin() {
  router.push('/login')
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
        <h1>找回你的<br />账号访问权限</h1>
        <p>通过用户名与已绑定的手机号验证身份，验证通过后即可重新设置密码。整个过程不需要联系管理员。</p>
      </div>
      <div class="auth-feats">
        <div class="feat"><span class="fi"><el-icon><Checked /></el-icon></span>短信验证码校验，5 分钟内有效</div>
        <div class="feat"><span class="fi"><el-icon><Lock /></el-icon></span>重置后所有已登录设备立即失效</div>
        <div class="feat"><span class="fi"><el-icon><Key /></el-icon></span>忘记手机号需联系管理员重置</div>
      </div>
      <div class="auth-foot">© 2026 AI Knowledge Base · v1.0.0</div>
    </div>

    <div class="auth-right">
      <div class="auth-card" style="max-width: 432px">
        <!-- ================= 完成 ================= -->
        <template v-if="step === 3">
          <h2>密码重置成功</h2>
          <div class="lead">你的登录密码已更新</div>
          <div class="done-box">
            <el-icon :size="56" color="#67c23a"><CircleCheckFilled /></el-icon>
            <p>所有已登录设备已强制下线，请使用新密码重新登录。</p>
            <p class="text-secondary" style="font-size: 13px">本次操作已记入审计日志（FR-PERM-04）</p>
          </div>
          <el-button type="primary" size="large" class="login-btn" @click="backToLogin">返回登录</el-button>
        </template>

        <!-- ================= 设置新密码 ================= -->
        <template v-else-if="step === 2">
          <h2>设置新密码</h2>
          <div class="lead">身份验证已通过（{{ form.phone }}），请设置新的登录密码</div>
          <div class="steps">
            <div class="step done"><span class="n"><el-icon :size="13"><Check /></el-icon></span><span class="t">验证身份</span></div>
            <span class="step-line done"></span>
            <div class="step active"><span class="n">2</span><span class="t">设置新密码</span></div>
            <span class="step-line"></span>
            <div class="step"><span class="n">3</span><span class="t">完成</span></div>
          </div>

          <el-form ref="formRef" :model="form" :rules="rulesStep2" label-position="top" size="large" style="margin-top: 26px">
            <el-form-item label="新密码" prop="password">
              <el-input v-model="form.password" type="password" placeholder="8-32 位，含字母与数字" :prefix-icon="Lock" show-password />
            </el-form-item>
            <div v-if="form.password" class="pw-hint">
              <div class="pw-meter">
                <i v-for="i in 4" :key="i" :class="i <= pwStrength ? (pwStrength >= 3 ? 's' : 'w') : ''"></i>
              </div>
              <span class="text-secondary">强度：{{ ['', '弱', '中', '强', '很强'][pwStrength] }} · 建议 12 位以上组合</span>
            </div>
            <el-form-item label="确认新密码" prop="confirmPassword">
              <el-input v-model="form.confirmPassword" type="password" placeholder="再次输入新密码" :prefix-icon="Lock" show-password />
            </el-form-item>
            <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="onReset">确认重置</el-button>
          </el-form>
          <div class="auth-alt">
            <button class="btn-text" @click="step = 1">上一步</button>
            <span class="muted">·</span>
            <button class="btn-text" @click="backToLogin">返回登录</button>
          </div>
        </template>

        <!-- ================= 验证身份 ================= -->
        <template v-else>
          <h2>忘记密码</h2>
          <div class="lead">已绑定手机号即可自助重置，无需联系管理员</div>
          <div class="steps">
            <div class="step active"><span class="n">1</span><span class="t">验证身份</span></div>
            <span class="step-line"></span>
            <div class="step"><span class="n">2</span><span class="t">设置新密码</span></div>
            <span class="step-line"></span>
            <div class="step"><span class="n">3</span><span class="t">完成</span></div>
          </div>

          <el-form ref="formRef" :model="form" :rules="rulesStep1" label-position="top" size="large" style="margin-top: 26px">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" placeholder="注册时填写的登录账号" :prefix-icon="User" @keyup.enter="onVerify" />
              <div class="hint">注册时填写的登录账号，不是姓名</div>
            </el-form-item>

            <el-form-item label="图形验证码" prop="captchaCode">
              <div class="captcha-row">
                <el-input v-model="form.captchaCode" placeholder="请输入右侧 4 位字符" :prefix-icon="Key" maxlength="4" @keyup.enter="onVerify" />
                <img v-if="captchaImg" :src="captchaImg" alt="验证码" class="captcha-img" title="点击刷新" @click="loadCaptcha" />
                <el-button :icon="Refresh" title="刷新验证码" @click="loadCaptcha" />
              </div>
            </el-form-item>

            <el-form-item label="短信验证码" prop="smsCode">
              <div class="sms-row">
                <el-input v-model="form.smsCode" placeholder="6 位数字" :prefix-icon="Lock" maxlength="6" @keyup.enter="onVerify" />
                <el-button :loading="smsSending" :disabled="smsCountdown > 0" @click="sendSms">
                  {{ smsCountdown > 0 ? `${smsCountdown}s 后重发` : '获取验证码' }}
                </el-button>
              </div>
              <div class="hint">验证码将发送至绑定手机号</div>
            </el-form-item>

            <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="onVerify">下一步</el-button>
          </el-form>

          <div class="auth-alt">
            <button class="btn-text" @click="backToLogin">返回登录</button>
            <span class="muted">·</span>
            <span class="text-secondary">手机号已停用？</span>
            <button class="btn-text" @click="ElMessage.info('请联系管理员重置，管理员可在用户管理页操作')">联系管理员</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
  line-height: 1.5;
}
.captcha-row,
.sms-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.captcha-row .el-input,
.sms-row .el-input {
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
.steps {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
.step {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-placeholder);
}
.step .n {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  border: 1.5px solid var(--el-border-color);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}
.step.active {
  color: var(--el-color-primary);
}
.step.active .n {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.step.done {
  color: var(--el-color-success);
}
.step.done .n {
  border-color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}
.step-line {
  flex: 1;
  height: 1.5px;
  background: var(--el-border-color-lighter);
  margin: 0 14px;
  min-width: 28px;
}
.step-line.done {
  background: var(--el-color-success);
}
.step .t {
  font-size: 13px;
}
.pw-hint {
  margin: 0 0 18px;
}
.pw-meter {
  display: flex;
  gap: 5px;
  margin: 8px 0 4px;
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
.login-btn {
  width: 100%;
  letter-spacing: 2px;
  margin-bottom: 12px;
}
.done-box {
  text-align: center;
  padding: 28px 16px 20px;
  margin-bottom: 20px;
}
.done-box p {
  margin: 14px 0 0;
  color: var(--el-text-color-regular);
  line-height: 1.8;
}
.auth-alt {
  text-align: center;
  font-size: 13px;
  margin-top: 6px;
}
.muted {
  color: var(--el-text-color-placeholder);
  margin: 0 6px;
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