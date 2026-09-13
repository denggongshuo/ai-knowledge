import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { ApiError } from './api/request'
import '@/assets/styles/index.css'
import 'element-plus/dist/index.css'

const app = createApp(App)

// 全量注册图标（原型阶段求稳，后续可切按需导入）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

/**
 * 统一兜底（T-3C2）：**只**把「后端未实现」的接口（HTTP 404 未映射路由）降级，
 * 其余错误照旧响亮地打出来 —— 否则真缺陷会被静默淹没。
 *
 * 未实现接口在 request 层已打印一条可识别的 `[未实现接口]` 提示（每接口一次），
 * 这里只负责不让它变成 `Unhandled error during execution of mounted hook`。
 */
app.config.errorHandler = (err, _instance, info) => {
  if (err instanceof ApiError && err.notImplemented) return
  console.error(`[未处理异常] ${info}`, err)
}
// 兜住 Vue 生命周期之外（未被 errorHandler 接管）的 promise 拒绝
window.addEventListener('unhandledrejection', (e) => {
  if (e.reason instanceof ApiError && e.reason.notImplemented) {
    e.preventDefault() // 未实现接口不算「未捕获异常」；提示已在 request 层给过
  }
  // 其余不拦截：保留浏览器默认的 Uncaught (in promise) 输出，不静默
})

app.mount('#app')