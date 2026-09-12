import type { Router } from 'vue-router'

/**
 * 是否存在「应用内」可返回的历史记录
 *
 * vue-router 会把上一页的 fullPath 写进 `history.state.back`（首条记录为 null）。
 * 这比 `history.length > 1` 准确 —— 后者会把浏览器里其他站点的历史也算进来。
 */
export function hasAppHistory(): boolean {
  const state = window.history.state as { back?: string | null } | null
  return typeof state?.back === 'string' && state.back.length > 0
}

/**
 * 返回上一页，无应用内历史时退回到指定的兜底路径
 *
 * 为什么不直接用 `router.back()`：
 * ① 从书签 / 站内通知 / 外部链接直达页面时没有「应用内的上一条」，
 *    back 会把用户带出应用或毫无反应；
 * ② 有了标签栏之后，历史里混入了大量标签切换产生的记录，
 *    back 回到的是「浏览器历史的上一条」而不是「逻辑上的上一级」。
 * 所以每个页面都应显式声明自己的兜底去处，而不是把导航交给历史栈。
 */
export function goBack(router: Router, fallback: string) {
  if (hasAppHistory()) router.back()
  else router.push(fallback)
}
