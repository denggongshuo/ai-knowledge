import { defineStore } from 'pinia'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

/**
 * 标签项
 *
 * `fullPath` 同时承担两个职责：标签去重键 + keep-alive 缓存键的来源。
 * 之所以用 fullPath 而不是 path：/doc/:id 与 /kb/:id 这类动态路由，
 * 同一个组件对应多个实例，必须按实际参数分开缓存，否则看完文档 A 再看文档 B，
 * 切回 A 时拿到的是 B 的实例。
 */
export interface TabItem {
  /** 路由 fullPath（含参数与查询），标签唯一键 */
  fullPath: string
  /** 路由 path 模板，如 /doc/:id */
  path: string
  title: string
  group?: string
  /** 常驻标签：始终存在、不可关闭 */
  affix: boolean
}

/** 非常驻标签的最大数量，超出后淘汰最早打开的（常驻标签不参与淘汰） */
export const MAX_TABS = 10

const STORAGE_KEY = 'ak_tabs'

/** 带 id 参数的页面在标签上补 #id，避免「文档详情 ×3」这种无法区分的标签 */
function buildTitle(route: RouteLocationNormalizedLoaded): string {
  const base = String(route.meta.title ?? route.name ?? '未命名')
  const id = route.params.id
  if (id === undefined) return base
  return `${base} #${Array.isArray(id) ? (id[0] ?? '') : id}`
}

export const useTabsStore = defineStore('tabs', {
  state: () => ({
    /** 当前打开的标签（含常驻标签，常驻始终排在前面） */
    tabs: [] as TabItem[],
    /** 当前激活标签的 fullPath */
    active: '',
    /**
     * 缓存版本号
     *
     * keep-alive 的缓存按 vnode key 存储，关闭标签时仅从列表移除并不会销毁已缓存的实例，
     * 于是「关掉再打开」会看到陈旧数据。这里在关闭/刷新时自增版本号，
     * 使 key 发生变化 → 重新访问时必然重新挂载。
     * （旧实例由 keep-alive 的 :max 上限按 LRU 淘汰，无需手工销毁）
     */
    epochs: {} as Record<string, number>,
    /** 常驻标签定义，由 BasicLayout 从路由表 meta.affix 注入 */
    affixTabs: [] as TabItem[],
  }),

  getters: {
    /** keep-alive 的缓存上限：常驻标签不参与淘汰，故额外预留 */
    maxCached: (s) => MAX_TABS + s.affixTabs.length,
    /** keep-alive 的组件 key = fullPath + 版本号 */
    keyOf: (s) => (fullPath: string) => `${fullPath}~${s.epochs[fullPath] ?? 0}`,
    /** 可关闭标签数量（用于「关闭全部」等菜单项的禁用判断） */
    closableCount: (s) => s.tabs.filter((t) => !t.affix).length,
  },

  actions: {
    /** 注入常驻标签，并恢复上次会话（刷新浏览器后标签不丢） */
    init(affixTabs: TabItem[]) {
      this.affixTabs = affixTabs
      const restored = this.load()
      const affixPaths = new Set(affixTabs.map((t) => t.fullPath))
      const others = restored.filter((t) => !affixPaths.has(t.fullPath)).slice(0, MAX_TABS)
      this.tabs = [...affixTabs.map((t) => ({ ...t })), ...others]
      this.active = ''
    },

    /** 路由变化时记录标签（由 BasicLayout 监听） */
    ensure(route: RouteLocationNormalizedLoaded) {
      if (route.meta.public || !route.meta.title) return

      // 常驻路由：始终复用同一个标签，不新建
      const affix = this.affixTabs.find((t) => t.path === route.path)
      if (affix) {
        this.active = affix.fullPath
        return
      }

      const exists = this.tabs.find((t) => t.fullPath === route.fullPath)
      if (exists) {
        exists.title = buildTitle(route)
        this.active = route.fullPath
        this.persist()
        return
      }

      this.tabs.push({
        fullPath: route.fullPath,
        path: route.path,
        title: buildTitle(route),
        group: typeof route.meta.group === 'string' ? route.meta.group : undefined,
        affix: false,
      })
      this.active = route.fullPath
      this.trim()
      this.persist()
    },

    /** 移除第 idx 个标签并使其缓存失效 */
    drop(idx: number) {
      const tab = this.tabs[idx]
      if (!tab) return
      this.tabs.splice(idx, 1)
      this.epochs[tab.fullPath] = (this.epochs[tab.fullPath] ?? 0) + 1
    },

    /** 超出上限时淘汰最早打开的非常驻标签（跳过当前激活标签） */
    trim() {
      const others = this.tabs.filter((t) => !t.affix).length
      if (others <= MAX_TABS) return
      let overflow = others - MAX_TABS
      for (let i = 0; i < this.tabs.length && overflow > 0; i++) {
        const tab = this.tabs[i]
        if (!tab || tab.affix || tab.fullPath === this.active) continue
        this.drop(i)
        i--
        overflow--
      }
    },

    /** 关闭标签；若关的是当前激活标签，返回应跳转的目标路径 */
    close(fullPath: string): string | null {
      const idx = this.tabs.findIndex((t) => t.fullPath === fullPath)
      if (idx < 0 || this.tabs[idx]?.affix) return null

      const wasActive = this.active === fullPath
      this.drop(idx)

      if (!wasActive) {
        this.persist()
        return null
      }
      // 优先取右侧邻居，没有则取左侧邻居，最后兜底到常驻标签
      const next = this.tabs[idx] ?? this.tabs[idx - 1] ?? this.tabs[this.tabs.length - 1]
      const target = next?.fullPath ?? '/chat'
      this.active = target
      this.persist()
      return target
    },

    /** 关闭除指定标签外的其他标签（常驻标签保留） */
    closeOthers(fullPath: string) {
      for (let i = this.tabs.length - 1; i >= 0; i--) {
        const tab = this.tabs[i]
        if (!tab || tab.affix || tab.fullPath === fullPath) continue
        this.drop(i)
      }
      this.active = fullPath
      this.persist()
    },

    /** 关闭指定标签右侧的所有标签（常驻标签保留） */
    closeRight(fullPath: string) {
      const idx = this.tabs.findIndex((t) => t.fullPath === fullPath)
      if (idx < 0) return
      for (let i = this.tabs.length - 1; i > idx; i--) {
        const tab = this.tabs[i]
        if (!tab || tab.affix) continue
        this.drop(i)
      }
      this.active = fullPath
      this.persist()
    },

    /** 关闭全部非常驻标签，返回应跳转的目标路径 */
    closeAll(): string {
      for (let i = this.tabs.length - 1; i >= 0; i--) {
        const tab = this.tabs[i]
        if (!tab || tab.affix) continue
        this.drop(i)
      }
      const target = this.tabs[0]?.fullPath ?? '/chat'
      this.active = target
      this.persist()
      return target
    },

    /** 刷新标签：自增版本号使缓存失效，下次渲染即重新挂载 */
    refresh(fullPath: string) {
      this.epochs[fullPath] = (this.epochs[fullPath] ?? 0) + 1
    },

    persist() {
      try {
        sessionStorage.setItem(
          STORAGE_KEY,
          JSON.stringify({ tabs: this.tabs.filter((t) => !t.affix), active: this.active }),
        )
      } catch {
        /* 隐私模式 / 存储超限时忽略，不影响使用 */
      }
    },

    load(): TabItem[] {
      try {
        const raw = sessionStorage.getItem(STORAGE_KEY)
        if (!raw) return []
        const parsed = JSON.parse(raw) as { tabs?: TabItem[] }
        return Array.isArray(parsed.tabs) ? parsed.tabs : []
      } catch {
        return []
      }
    },

    /** 退出登录时清空（避免下一个账号看到上一个账号的标签） */
    clear() {
      this.tabs = []
      this.active = ''
      this.epochs = {}
      try {
        sessionStorage.removeItem(STORAGE_KEY)
      } catch {
        /* 忽略 */
      }
    },
  },
})
