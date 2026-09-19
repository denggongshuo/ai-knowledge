/**
 * **全站路由清单的唯一事实源** —— `render-check.mjs` 与 `console-check.mjs` 共用这一份。
 *
 * <h3>为什么必须共用（`#227` 的根因）</h3>
 * 它们原本是**两份手抄的拷贝**，而 `console-check.mjs` 的注释还写着「与 `render-check.mjs`
 * 同一份路由清单」—— **那句话是靠注释维持的**，没有任何机器检查。
 *
 * 抄出来的第二份**没有 `expect`**，于是 `console-check` 只能断言"控制台没有 error"。
 * 而**页面整页挂掉 = 没有 error** ⇒ 那是一条**恒绿**的判据。
 * 这与 `#149`（两份登记表靠机器对账补偿）是同一形态：**单一事实源 > 靠人同步第二份**。
 *
 * <h3>`expect` 怎么选</h3>
 * 用**页面主体特征**（`.page-title` / `.err-title` / 认证卡 / 聊天页类名），
 * **不用通用文案** —— 否则会被侧边栏菜单文案误命中（`/chat` 那条曾经的教训：
 * `class="[a-z-]*chat[a-z-]*"` 会命中导航栏的类名，于是它"通过"得毫无意义）。
 *
 * <h3>⚠️ 有些 `expect` 是**数据依赖**的 —— 所以有 `mockData` 这个标记</h3>
 * `/kb/1` 期望「产品技术文档库」、`/doc/1` 与 `/doc/1/preview` 期望
 * 「AI 知识库平台白皮书 V3」—— 那些标题**只存在于 mock 数据里**。
 *
 * ⇒ 跑到**真后端模式**的 dev server 上时它们会红，**而红的不是代码**。
 * 两个脚本对此的处理不同，**这正是需要标记而不是硬编码的原因**：
 * - `render-check.mjs` 本来就是 **mock 模式**的判据 ⇒ 照常断言（那些标题就是它要守的东西）；
 * - `console-check.mjs` 本来就跑**真后端** ⇒ 真后端模式下**跳过这几条的 `expect`**
 *   （仍然断言"挂载了"），并把跳过这件事**打印出来**（不静默）。
 */

/**
 * 每页给一条**精确断言**（正则在整页 HTML 上匹配）。
 *
 * - `public: true` 表示无需登录态，直接访问
 * - `expect` 必须出现的页面主体特征；缺失即判定渲染失败
 * - `mockData: true` 表示**这条 `expect` 依赖 mock 数据**，真后端模式下不该拿它判红
 */
export const ROUTES = [
  { path: '/login', public: true, expect: /class="auth-card"/, label: '登录页' },
  { path: '/register', public: true, expect: /class="auth-card"/, label: '注册页' },
  { path: '/forgot-password', public: true, expect: /class="auth-card"/, label: '忘记密码' },
  { path: '/chat', expect: /class="[a-z-]*chat[a-z-]*"/, label: '知识问答' },
  { path: '/notice', expect: /class="page-title"[^>]*>消息通知/, label: '消息通知' },
  { path: '/kb', expect: /class="page-title"[^>]*>知识库管理/, label: '知识库管理' },
  { path: '/kb/create', expect: /class="page-title"[^>]*>创建知识库/, label: '创建知识库' },
  { path: '/kb/1', expect: /class="page-title"[^>]*>产品技术文档库/, label: '知识库设置', mockData: true },
  { path: '/kb/1/upload', expect: /class="page-title"[^>]*>文档上传/, label: '文档上传' },
  { path: '/doc/1', expect: /class="page-title"[^>]*>AI 知识库平台白皮书 V3/, label: '文档详情', mockData: true },
  { path: '/doc/1/preview', expect: /class="page-title"[^>]*>AI 知识库平台白皮书 V3/, label: '文档预览', mockData: true },
  { path: '/doc/download', expect: /class="page-title"[^>]*>文档下载/, label: '文档下载' },
  { path: '/recycle', expect: /class="page-title"[^>]*>回收站/, label: '回收站' },
  { path: '/ops/qa', expect: /class="page-title"[^>]*>问答运营/, label: '问答运营' },
  { path: '/ops/retrieval', expect: /class="page-title"[^>]*>检索质量分析/, label: '检索质量分析' },
  { path: '/ops/usage', expect: /class="page-title"[^>]*>使用统计/, label: '使用统计' },
  { path: '/profile', expect: /class="page-title"[^>]*>个人信息/, label: '个人信息' },
  { path: '/system/user', expect: /class="page-title"[^>]*>用户管理/, label: '用户管理' },
  { path: '/system/role', expect: /class="page-title"[^>]*>角色管理/, label: '角色管理' },
  { path: '/system/grant', expect: /class="page-title"[^>]*>授权管理/, label: '授权管理' },
  { path: '/system/model', expect: /class="page-title"[^>]*>模型配置/, label: '模型配置' },
  { path: '/system/audit', expect: /class="page-title"[^>]*>审计日志/, label: '审计日志' },
  { path: '/403', expect: /class="err-title"[^>]*>你没有访问该页面的权限/, label: '403 无权访问' },
  { path: '/500', expect: /class="err-title"[^>]*>服务端发生异常/, label: '500 服务异常' },
  { path: '/no-such-page', expect: /class="err-title"[^>]*>页面地址可能已失效/, label: '404 页面不存在' },
]

/**
 * **不在布局壳内的路由**（全屏页）—— 它们不该出现标签栏。
 *
 * 壳内必须有 `.tags-view` 且至少 2 个常驻标签；壳外必须没有。
 * 后者混进标签栏说明 keep-alive 挂错了位置。
 */
export const NO_SHELL = new Set(['/login', '/register', '/forgot-password', '/403', '/500', '/no-such-page'])
