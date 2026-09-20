<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useTabsStore } from '@/stores/tabs'
import TagsView from './TagsView.vue'
import { userApi, noticeApi } from '@/api'
import { ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  Bell,
  Reading,
  Upload,
  View,
  Download,
  DataAnalysis,
  Filter,
  Grid,
  User,
  UserFilled,
  Lock,
  Key,
  MagicStick,
  Document,
  Search,
  ArrowDown,
  Delete,
} from '@element-plus/icons-vue'

/**
 * 导航分组：对齐原型 NAV_ORDER / GROUPS
 */
interface NavItem {
  path: string
  title: string
  icon: Component
  badge?: string
  match?: string[]
}
interface NavGroup {
  name: string
  items: NavItem[]
}

const NAV_GROUPS: NavGroup[] = [
  {
    name: '工作台',
    items: [
      { path: '/chat', title: '知识问答', icon: ChatDotRound, badge: 'AI' },
      // ⚠️ `badge: '5'` 已销（`S6-M11`）—— 那是原型里的演示值，被硬编码进了导航配置：
      // 它**恒显示 5**，与真实未读数无关，看着像真数据。现在由 `navBadge()` 取
      // `GET /notices/unread-count` 的真数（为 0 时不显示角标）。
      { path: '/notice', title: '消息通知', icon: Bell },
    ],
  },
  {
    name: '知识管理',
    items: [
      { path: '/kb', title: '知识库管理', icon: Reading },
      { path: '/kb/:id/upload', title: '文档上传', icon: Upload, match: ['/kb/', '/doc/'] },
      { path: '/doc/download', title: '文档下载', icon: Download },
      { path: '/recycle', title: '回收站', icon: Delete },
    ],
  },
  {
    name: '运营分析',
    items: [
      // ✅ `badge: '23'`（原型演示值）在 `S6-M11` 销掉、**在 `S8-M10-A` 接了真数** ——
      // 真数 = 未命中清单的待处理数（`/notices/pending` 的 `miss` 那一项），
      // 与 11 页统计卡、`/ops/qa/overview` 的 `pending` **同源**（`G6-13`/`G8-4`）。
      { path: '/ops/qa', title: '问答运营', icon: DataAnalysis },
      { path: '/ops/retrieval', title: '检索质量分析', icon: Filter },
      { path: '/ops/usage', title: '使用统计', icon: Grid },
    ],
  },
  {
    name: '系统管理',
    items: [
      { path: '/profile', title: '个人信息', icon: User },
      { path: '/system/user', title: '用户管理', icon: UserFilled },
      { path: '/system/role', title: '角色管理', icon: Lock },
      { path: '/system/grant', title: '授权管理', icon: Key },
      { path: '/system/model', title: '模型配置', icon: MagicStick },
      { path: '/system/audit', title: '审计日志', icon: Document },
    ],
  },
]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const tabs = useTabsStore()

/**
 * 标签栏初始化
 * 常驻标签（affix）由路由表 `meta.affix` 声明，不在 store 里再维护一份清单，
 * 避免路由和标签两处定义不一致。
 */
tabs.init(
  router
    .getRoutes()
    .filter((r) => r.meta.affix === true)
    .map((r) => ({
      fullPath: r.path,
      path: r.path,
      title: String(r.meta.title ?? r.path),
      group: typeof r.meta.group === 'string' ? r.meta.group : undefined,
      affix: true,
    })),
)

// 路由变化 → 记录标签并置为激活
watch(
  () => route.fullPath,
  () => tabs.ensure(route),
  { immediate: true },
)

// 刷新后 token 已恢复，但 user 在内存中丢失 → 拉取 profile 补全
const unreadCount = ref(0)

/**
 * 「问答运营」的待办数（未命中清单里待处理 / 已认领的那些）。
 *
 * <p>⚠️ 它**只在有 `ops:qa:view` 时才显示**。理由：`/ops/qa/*` 挂了权限码，
 * 普通用户点进去是 403 —— 给一个进不去的页面标"有 3 件事等你"，比不标更糟。
 * （导航项本身还没做权限过滤，那是另一件事；这里至少不让角标说谎。）
 */
const opsPending = ref(0)

/** 未读角标的轮询间隔（需求 `09` §2.6：「建议 30s 轮询**或** SSE 推送」，二选一取前者）。 */
const UNREAD_POLL_MS = 30_000
let unreadTimer: ReturnType<typeof setInterval> | undefined

function refreshUnread() {
  noticeApi.unreadCount().then((r) => (unreadCount.value = r.count)).catch(() => {
    /* 角标拉取失败不阻塞渲染（保持上一次的值比归零更诚实） */
  })
  // 「问答运营」的待办角标（`G8-4`）。**与未读数同一跳**取，不另起一条轮询 ——
  // 两条轮询会各自漂移，而它们表达的是同一件事（"有多少事等我"）。
  noticeApi.pending().then((r) => {
    const miss = r.list.find((p) => p.key === 'miss')
    opsPending.value = miss ? miss.count : 0
  }).catch(() => {
    /* 同上：保持上一次的值 */
  })
}

onMounted(() => {
  if (!auth.user && auth.isLoggedIn) {
    userApi.profile().then((data) => {
      auth.user = data as unknown as typeof auth.user
    }).catch(() => {
      /* profile 拉取失败不阻塞渲染 */
    })
  }
  // 顶栏铃铛未读角标。⚠️ 必须**清定时器** —— 不清的话每次进出布局都会叠一个，
  // 页面开着越久请求越密（这是"看着一切正常"的泄漏，与 `#159` 那一族同形）。
  refreshUnread()
  unreadTimer = setInterval(refreshUnread, UNREAD_POLL_MS)
})

onUnmounted(() => {
  if (unreadTimer) clearInterval(unreadTimer)
})

/**
 * 导航项右侧的角标。
 *
 * <p>静态角标（`'AI'`）照旧由配置给；**消息通知**那个取真实未读数，
 * 为 0 时返回 `undefined`（`v-if` 随之不渲染）—— 与"角标恒亮着"是两回事。
 */
function navBadge(item: NavItem): string | number | undefined {
  if (item.path === '/notice') {
    return unreadCount.value > 0 ? unreadCount.value : undefined
  }
  if (item.path === '/ops/qa') {
    return canHandleOps() && opsPending.value > 0 ? opsPending.value : undefined
  }
  return item.badge
}

/**
 * 当前账号能不能看运营页。
 *
 * <p>`*` 是超管通配（`SUPER_ADMIN` 拿的就是它，不是逐条权限码）——
 * 少了这一条，超管反而看不到角标，而现象是"角标只有在管理员账号上不出现"。
 */
function canHandleOps(): boolean {
  const perms = auth.user?.permissions ?? []
  return perms.includes('*') || perms.includes('ops:qa:view')
}

const activePath = computed(() => {
  const p = route.path
  // 动态路由高亮父菜单
  if (p.startsWith('/kb')) return '/kb'
  if (p.startsWith('/doc')) return '/kb/:id/upload'
  return p
})

function isActive(item: { path: string; match?: string[] }) {
  if (item.match?.some((m) => route.path.startsWith(m))) return true
  return activePath.value === item.path || route.path === item.path
}

function navigate(item: { path: string }) {
  // 动态路由示例路径，点击跳转到真实页面时由守卫兜底
  router.push(item.path === '/kb/:id/upload' ? '/kb/1/upload' : item.path)
}

async function onLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
  } catch {
    return
  }
  await auth.logoutRemote()
  tabs.clear()
  router.push('/login')
}

const keyword = ref('')
</script>

<template>
  <el-container class="app-shell">
    <!-- 侧边栏 224px -->
    <el-aside width="224px" class="app-aside">
      <div class="logo">
        <div class="logo-mark">
          <el-icon :size="18" color="#fff"><MagicStick /></el-icon>
        </div>
        <span>AI 知识库</span>
      </div>
      <el-scrollbar class="nav-scroll">
        <el-menu :default-active="activePath" router class="nav-menu" :collapse="false">
          <template v-for="g in NAV_GROUPS" :key="g.name">
            <div class="nav-group">{{ g.name }}</div>
            <el-menu-item
              v-for="item in g.items"
              :key="item.path"
              :index="item.path"
              :class="{ 'is-active-real': isActive(item) }"
              @click="navigate(item)"
            >
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.title }}</span>
              <span v-if="navBadge(item) !== undefined" class="nav-badge" :class="{ warn: g.name !== '工作台' }">{{ navBadge(item) }}</span>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
      <div class="aside-foot">
        <div class="aside-user">
          <el-avatar :size="28" class="avatar">{{ auth.displayName.slice(0, 1) }}</el-avatar>
          <div class="u-info">
            <div class="u-name">{{ auth.displayName || '未登录' }}</div>
            <div class="u-role">{{ auth.user?.roleNames?.join(' / ') || auth.user?.roles?.join(' / ') }}</div>
          </div>
        </div>
      </div>
    </el-aside>

    <!-- 顶栏 56px -->
    <el-container>
      <el-header class="app-header" height="56px">
        <div class="h-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>{{ route.meta.group || '' }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ route.meta.title || '' }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="h-right">
          <el-input
            v-model="keyword"
            placeholder="跨库检索文档 / 标题 / 内容"
            class="global-search"
            :prefix-icon="Search"
            clearable
          />
          <el-badge :value="unreadCount" :max="99" :hidden="unreadCount === 0" class="bell-badge">
            <el-button circle text @click="router.push('/notice')"><el-icon :size="18"><Bell /></el-icon></el-button>
          </el-badge>
          <el-dropdown trigger="click">
            <span class="user-chip">
              <el-avatar :size="26" class="avatar">{{ auth.displayName.slice(0, 1) }}</el-avatar>
              <span class="tc">{{ auth.displayName || auth.username }}</span>
              <el-icon :size="12" class="text-secondary"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/profile')">个人信息</el-dropdown-item>
                <el-dropdown-item divided @click="onLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="app-main">
        <div class="main-col">
          <TagsView />

          <div class="main-view">
            <!--
              缓存策略：不使用 include 白名单 —— 页面组件全部是 <script setup>，没有显式 name，
              include 会静默失效且很难排查。改用「fullPath + 版本号」作为 key，
              由 :max 做 LRU 淘汰；需要不缓存的页面在路由上声明 meta.noCache。
            -->
            <router-view v-slot="{ Component, route: r }">
              <keep-alive :max="tabs.maxCached">
                <component v-if="!r.meta.noCache" :is="Component" :key="tabs.keyOf(r.fullPath)" />
              </keep-alive>
              <component v-if="r.meta.noCache" :is="Component" :key="`nc-${r.fullPath}`" />
            </router-view>
          </div>
        </div>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-shell {
  height: 100vh;
}
.app-aside {
  background: #fff;
  border-right: 1px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
}
.logo {
  height: var(--topbar-h);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.logo-mark {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: var(--brand-grad);
  display: flex;
  align-items: center;
  justify-content: center;
}
.nav-scroll {
  flex: 1;
}
.nav-menu {
  border-right: none;
  --el-menu-item-height: 44px;
  padding: 6px 8px;
}
.nav-group {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  padding: 12px 12px 4px;
  letter-spacing: 0.5px;
}
.nav-badge {
  margin-left: auto;
  min-width: 18px;
  height: 18px;
  border-radius: 9px;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
  padding: 0 5px;
  font-weight: 500;
}
.nav-badge.warn {
  background: var(--el-color-warning);
}
.aside-foot {
  padding: 12px 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.aside-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 4px;
  border-radius: 8px;
  cursor: pointer;
}
.aside-user:hover {
  background: var(--el-fill-color-light, #f5f7fa);
}
.u-info {
  min-width: 0;
}
.u-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.u-role {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.avatar {
  background: var(--brand-grad);
  color: #fff;
  font-weight: 600;
}
.app-header {
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-lighter);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px !important;
}
.h-right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.global-search {
  width: 260px;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}
.tc {
  font-size: 13px;
  color: var(--el-text-color-primary);
}
.app-main {
  background: #eef1f6;
  padding: 0;
  overflow: hidden;
}
/*
 * 高度链：.app-main（flex 子项，高度确定）
 *   → .main-col  撑满，纵向 flex
 *     → TagsView 固定 36px
 *     → .main-view flex:1 + min-height:0 → 高度确定
 * 这样页面里的 `height: 100%` 仍然成立，19 个业务页面无需任何改动。
 */
.main-col {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.main-view {
  flex: 1;
  min-height: 0;
}
.bell-badge {
  display: inline-flex;
  align-items: center;
}
</style>