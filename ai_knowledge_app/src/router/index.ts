import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const BasicLayout = () => import('@/layout/BasicLayout.vue')
const ErrorView = () => import('@/views/error/ErrorView.vue')

declare module 'vue-router' {
  interface RouteMeta {
    /** 无需登录，且不进入布局壳 */
    public?: boolean
    title?: string
    group?: string
    /** 不显示在侧边栏菜单 */
    hidden?: boolean
    /** 常驻标签：进入系统后始终存在、不可关闭（标签栏由 meta 驱动） */
    affix?: boolean
    /** 不参与 keep-alive 缓存，每次进入重新挂载 */
    noCache?: boolean
  }
}

export const routes: RouteRecordRaw[] = [
  // ============ 认证（无壳） ============
  { path: '/login', name: 'Login', component: () => import('@/views/auth/LoginView.vue'), meta: { public: true, title: '登录' } },
  { path: '/register', name: 'Register', component: () => import('@/views/auth/RegisterView.vue'), meta: { public: true, title: '注册' } },
  { path: '/forgot-password', name: 'ForgotPassword', component: () => import('@/views/auth/ForgotPasswordView.vue'), meta: { public: true, title: '忘记密码' } },

  // ============ 主框架（带壳） ============
  {
    path: '/',
    component: BasicLayout,
    redirect: '/chat',
    children: [
      // 工作台
      { path: 'chat', name: 'Chat', component: () => import('@/views/chat/ChatView.vue'), meta: { title: '知识问答', group: '工作台', affix: true } },
      { path: 'notice', name: 'Notice', component: () => import('@/views/notice/NoticeView.vue'), meta: { title: '消息通知', group: '工作台' } },
      // 知识管理
      { path: 'kb', name: 'KbList', component: () => import('@/views/kb/KbListView.vue'), meta: { title: '知识库管理', group: '知识管理', affix: true } },
      { path: 'kb/create', name: 'KbCreate', component: () => import('@/views/kb/KbCreateView.vue'), meta: { title: '创建知识库', group: '知识管理', hidden: true } },
      { path: 'kb/:id', name: 'KbDetail', component: () => import('@/views/kb/KbDetailView.vue'), meta: { title: '知识库设置', group: '知识管理', hidden: true } },
      { path: 'kb/:id/upload', name: 'KbUpload', component: () => import('@/views/kb/KbUploadView.vue'), meta: { title: '文档上传', group: '知识管理', hidden: true } },
      { path: 'doc/download', name: 'DocDownload', component: () => import('@/views/doc/DocDownloadView.vue'), meta: { title: '文档下载', group: '知识管理' } },
      { path: 'doc/:id', name: 'DocDetail', component: () => import('@/views/doc/DocDetailView.vue'), meta: { title: '文档详情', group: '知识管理', hidden: true } },
      { path: 'doc/:id/preview', name: 'DocPreview', component: () => import('@/views/doc/DocPreviewView.vue'), meta: { title: '文档预览', group: '知识管理', hidden: true } },
      { path: 'recycle', name: 'Recycle', component: () => import('@/views/recycle/RecycleView.vue'), meta: { title: '回收站', group: '知识管理' } },
      // 运营分析
      { path: 'ops/qa', name: 'OpsQa', component: () => import('@/views/ops/OpsQaView.vue'), meta: { title: '问答运营', group: '运营分析' } },
      { path: 'ops/retrieval', name: 'OpsRetrieval', component: () => import('@/views/ops/OpsRetrievalView.vue'), meta: { title: '检索质量分析', group: '运营分析' } },
      { path: 'ops/usage', name: 'OpsUsage', component: () => import('@/views/ops/OpsUsageView.vue'), meta: { title: '使用统计', group: '运营分析' } },
      // 系统管理
      { path: 'profile', name: 'Profile', component: () => import('@/views/system/ProfileView.vue'), meta: { title: '个人信息', group: '系统管理' } },
      { path: 'system/user', name: 'SysUser', component: () => import('@/views/system/UserManageView.vue'), meta: { title: '用户管理', group: '系统管理' } },
      { path: 'system/role', name: 'SysRole', component: () => import('@/views/system/RoleManageView.vue'), meta: { title: '角色管理', group: '系统管理' } },
      { path: 'system/grant', name: 'SysGrant', component: () => import('@/views/system/GrantManageView.vue'), meta: { title: '授权管理', group: '系统管理' } },
      { path: 'system/model', name: 'SysModel', component: () => import('@/views/system/ModelConfigView.vue'), meta: { title: '模型配置', group: '系统管理' } },
      { path: 'system/audit', name: 'SysAudit', component: () => import('@/views/system/AuditLogView.vue'), meta: { title: '审计日志', group: '系统管理' } },
    ],
  },
  // 错误页（无壳全屏；404 用 replace 跳转避免历史堆积）
  { path: '/403', name: 'Forbidden', component: ErrorView, meta: { public: true, title: '无权访问' } },
  { path: '/500', name: 'ServerError', component: ErrorView, meta: { public: true, title: '服务异常' } },
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: ErrorView, meta: { public: true, title: '页面不存在' } },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  document.title = to.meta.title ? `${String(to.meta.title)} · AI 知识库` : 'AI 知识库'
  if (!to.meta.public && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && auth.isLoggedIn) {
    return { path: '/chat' }
  }
})

export default router