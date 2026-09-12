<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { WarningFilled, Search, Refresh, ChatDotRound, Reading, Delete, InfoFilled, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { goBack, hasAppHistory } from '@/utils/nav'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 当前错误码：403 已登录但无权限 / 404 路由或资源不存在 / 500 服务端异常 */
const code = computed(() => {
  if (route.name === 'Forbidden' || route.path === '/403') return 403
  if (route.name === 'ServerError' || route.path === '/500') return 500
  return 404
})

const traceId = `tr_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
const copied = ref(false)

const META = {
  403: {
    label: '无权访问',
    code: '403',
    icon: WarningFilled,
    cls: 'warning',
    title: '你没有访问该页面的权限',
  },
  404: {
    label: '页面不存在',
    code: '404',
    icon: Search,
    cls: 'info',
    title: '页面地址可能已失效',
  },
  500: {
    label: '服务异常',
    code: '500',
    icon: Refresh,
    cls: 'danger',
    title: '服务端发生异常，请稍后重试',
  },
} as const

const meta = computed(() => META[code.value])

/** 403：直接说明缺的是哪个权限、当前是什么角色（用户才知道该找谁解决） */
const MISSING_PERM: Record<string, { perm: string; label: string }> = {
  '/system/grant': { perm: 'grant:matrix', label: '角色权限矩阵' },
  '/system/role': { perm: 'role:list', label: '查看角色' },
  '/system/user': { perm: 'user:list', label: '查看用户' },
  '/system/audit': { perm: 'audit:view', label: '查看审计日志' },
  '/system/model': { perm: 'system:model', label: '模型配置' },
}
const missing = computed(
  () => MISSING_PERM[route.query.from as string] ?? { perm: 'grant:matrix', label: '角色权限矩阵' },
)

/** 404 的三类来源，文案侧重点不同 */
const notFoundSource = computed(() => {
  const s = route.query.src
  if (s === 'kb') return { text: '该知识库可能已被删除', action: '去回收站找回' }
  if (s === 'doc') return { text: '该文档可能已被删除', action: '去回收站找回' }
  return { text: '页面地址可能已失效', action: '' }
})

/** 是否存在应用内可返回的历史（比 history.length 准确，见 utils/nav） */
const canGoBack = computed(() => hasAppHistory())

function goBackOrHome() {
  // 返回上一页降级：无应用内历史时（如直接打开链接）回工作台
  goBack(router, '/chat')
}

function copyTrace() {
  navigator.clipboard?.writeText(traceId).then(
    () => {
      copied.value = true
      ElMessage.success('traceId 已复制，反馈问题时粘贴即可')
      setTimeout(() => (copied.value = false), 2000)
    },
    () => ElMessage.warning('复制失败，请手动选择复制'),
  )
}

const roleName = computed(() => auth.user?.roleNames?.join(' / ') || auth.user?.roles?.join(' / ') || '普通用户')
const account = computed(() => `${auth.displayName || '未登录'}（${auth.username || '—'}）`)
</script>

<template>
  <div class="error-page" :class="meta.cls">
    <div class="err-inner">
      <div class="err-icon"><el-icon :size="34"><component :is="meta.icon" /></el-icon></div>
      <div class="err-code mono">{{ meta.code }}</div>
      <h1 class="err-title">{{ META[code].title }}</h1>

      <!-- 403：说明为什么 + 排查信息 -->
      <template v-if="code === 403">
        <p class="err-desc">
          你当前的角色<b>「{{ roleName }}」</b>缺少功能权限
          <code class="mono">{{ missing.perm }}</code>（{{ missing.label }}）。
          权限模型为「功能矩阵 ∩ 数据范围 ∩ 知识库授权」，请联系管理员为该角色开通，或申请单独授权。
        </p>

        <div class="trace-card">
          <div class="tc-title"><el-icon><Lock /></el-icon>排查信息</div>
          <div class="tc-row"><span>当前账号</span><b class="mono">{{ account }}</b></div>
          <div class="tc-row"><span>当前角色</span><b>{{ roleName }}</b></div>
          <div class="tc-row"><span>缺少权限</span><b class="mono warn">{{ missing.perm }} · {{ missing.label }}</b></div>
          <div class="tc-row"><span>请求路径</span><b class="mono">{{ route.query.from || route.path }}</b></div>
          <div class="tc-row">
            <span>traceId</span>
            <b class="mono trace" @click="copyTrace">{{ traceId }} <span class="copy-tip">{{ copied ? '已复制' : '点击复制' }}</span></b>
          </div>
        </div>

        <div class="err-actions">
          <el-button type="primary" @click="router.push('/profile')">查看我的权限</el-button>
          <el-button @click="router.push('/chat')">回到工作台</el-button>
        </div>
        <p class="err-foot">该访问尝试已记入审计日志（谁在什么时候试图访问什么被拒绝）。</p>
      </template>

      <!-- 404：给出下一步，不让用户卡死 -->
      <template v-else-if="code === 404">
        <p class="err-desc">
          {{ notFoundSource.text }}。你可以从下面的入口继续，或返回上一页。
        </p>

        <div class="quick-links">
          <div class="ql-item" @click="router.push('/chat')">
            <el-icon :size="18"><ChatDotRound /></el-icon>
            <div><b>去知识问答提问</b><span class="hint">/chat</span></div>
          </div>
          <div class="ql-item" @click="router.push('/kb')">
            <el-icon :size="18"><Reading /></el-icon>
            <div><b>浏览知识库列表</b><span class="hint">/kb</span></div>
          </div>
          <div class="ql-item" @click="router.push('/recycle')">
            <el-icon :size="18"><Delete /></el-icon>
            <div><b>去回收站找回已删除的内容</b><span class="hint">/recycle</span></div>
          </div>
        </div>

        <div class="err-actions">
          <el-button type="primary" @click="goBackOrHome">{{ canGoBack ? '返回上一页' : '回到工作台' }}</el-button>
          <el-button @click="router.push('/chat')">回到工作台</el-button>
        </div>
      </template>

      <!-- 500 -->
      <template v-else>
        <p class="err-desc">服务端处理请求时发生异常，我们已记录本次错误。请稍后重试，若持续出现请把下方 traceId 提供给管理员。</p>
        <div class="trace-card">
          <div class="tc-title"><el-icon><InfoFilled /></el-icon>排查信息</div>
          <div class="tc-row"><span>当前账号</span><b class="mono">{{ account }}</b></div>
          <div class="tc-row"><span>请求路径</span><b class="mono">{{ route.fullPath }}</b></div>
          <div class="tc-row">
            <span>traceId</span>
            <b class="mono trace" @click="copyTrace">{{ traceId }} <span class="copy-tip">{{ copied ? '已复制' : '点击复制' }}</span></b>
          </div>
        </div>
        <div class="err-actions">
          <el-button type="primary" :icon="Refresh" @click="router.go(0)">重试</el-button>
          <el-button @click="router.push('/chat')">回到工作台</el-button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
/* 错误页不带侧边栏：保持全屏干净，属于异常态 */
.error-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  background: #eef1f6;
}
.err-inner {
  width: 100%;
  max-width: 620px;
  background: #fff;
  border-radius: 16px;
  padding: 44px 48px;
  box-shadow: var(--shadow-l);
  text-align: center;
}
.err-icon {
  width: 68px;
  height: 68px;
  border-radius: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 18px;
}
.error-page.warning .err-icon {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.error-page.info .err-icon {
  background: var(--el-color-info-light-9);
  color: var(--el-color-info);
}
.error-page.danger .err-icon {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.err-code {
  font-size: 52px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 2px;
}
.error-page.warning .err-code {
  color: var(--el-color-warning);
}
.error-page.info .err-code {
  color: var(--el-color-info);
}
.error-page.danger .err-code {
  color: var(--el-color-danger);
}
.err-title {
  font-size: 19px;
  font-weight: 600;
  margin: 12px 0 10px;
}
.err-desc {
  font-size: 13.5px;
  color: var(--el-text-color-secondary);
  line-height: 1.85;
  margin: 0 0 20px;
}
.err-desc b {
  color: var(--el-text-color-regular);
}
.err-desc code {
  font-family: var(--font-mono);
  font-size: 12px;
  background: #f4f6fa;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 0 4px;
  color: #476582;
}
.trace-card {
  text-align: left;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 20px;
  background: #fafbfd;
}
.tc-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  margin-bottom: 10px;
}
.tc-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 0;
  font-size: 12.5px;
}
.tc-row span {
  color: var(--el-text-color-secondary);
  flex: none;
}
.tc-row b {
  font-weight: 500;
  text-align: right;
  word-break: break-all;
}
.tc-row b.warn {
  color: var(--el-color-warning);
}
.tc-row .trace {
  cursor: pointer;
  color: var(--el-color-primary);
}
.copy-tip {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-left: 6px;
}
.err-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}
.err-foot {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin: 18px 0 0;
}
.quick-links {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 22px;
}
.ql-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  cursor: pointer;
  text-align: left;
  color: var(--el-color-primary);
  transition: all 0.16s;
}
.ql-item:hover {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}
.ql-item b {
  display: block;
  font-size: 12.5px;
  color: var(--el-text-color-primary);
}
.hint {
  display: block;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-top: 2px;
}
</style>
