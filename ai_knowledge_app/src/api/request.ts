import type { ApiResponse, Citation } from '@/types'

/**
 * 请求层 —— fetch 封装
 * 统一解包 { code, message, data }，code !== 0 抛错；自动带 Authorization。
 * SSE 流单独走 chatStream()。
 */

const BASE = '/api'

export class ApiError extends Error {
  code: number
  /**
   * true = 后端**未实现**该接口（HTTP 404 未映射路由）。
   *
   * 本项目业务错误一律 `HTTP 200 + 非 0 code`，所以真出现 HTTP 404 只有一种可能：
   * 后端没注册这条路由（前端已 mock、后端尚未实现）。页面据此**降级**而非崩溃（T-3C2）。
   */
  notImplemented: boolean
  constructor(code: number, message: string, notImplemented = false) {
    super(message)
    this.code = code
    this.notImplemented = notImplemented
  }
}

/** 已降噪提示过的未实现接口（**每接口只提示一次**，避免每打开一个页面就刷一条）。 */
const warnedUnimplemented = new Set<string>()

/**
 * 未实现接口的降噪提示：**只降噪、不静默** —— 留一条可识别日志供人工/自检脚本发现，
 * 但不再以「未捕获异常」的形态淹没 console（那是 T-3C2 要消除的噪声）。
 */
function warnUnimplemented(path: string, method?: string) {
  const key = `${method ?? 'GET'} ${path.split('?')[0]}`
  if (warnedUnimplemented.has(key)) return
  warnedUnimplemented.add(key)
  console.warn(`[未实现接口] ${key} → 404（后端尚未实现，页面已降级；此提示每个接口只出现一次）`)
}

function getToken() {
  return localStorage.getItem('ak_token') ?? ''
}

/**
 * 把 body 转成 fetch 能接受的形态。
 *
 * <p><b>FormData 必须原样透传，不能 JSON.stringify</b>：FormData 的字段不可枚举，
 * 序列化出来是 `"{}"`（不报错，静默丢内容）；而 multipart 端点的 `Content-Type`
 * 还得带一个 `boundary` 参数 —— 那是浏览器根据实例算出来的，手写不出来，
 * 所以只能「不设 Content-Type」交给它自己填。
 *
 * <p>影响面：`/user/avatar`、`/users/import`（S2）。判错的后果不是报错，
 * 是上传接口静默发一个空 JSON 出去，然后在后端得到一句很难联想的参数错误。
 */
function serializeBody(body: unknown): BodyInit | undefined {
  if (body == null) return undefined
  if (body instanceof FormData) return body
  return JSON.stringify(body)
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const { headers: extraHeaders, body, ...rest } = options
  // 判 `body` 而不是 `headers`：headers 永远是个普通对象（或 Headers），
  // 永远不可能是 FormData。原先写 `headers instanceof FormData` 恒为 false，
  // 于是 FormData 一律被当成 JSON 处理（见 serializeBody 的说明）。
  const isForm = body instanceof FormData
  const headers: Record<string, string> = {
    ...(isForm ? {} : { 'Content-Type': 'application/json' }),
    ...(extraHeaders as Record<string, string> | undefined),
  }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`

  let resp: Response
  try {
    resp = await fetch(`${BASE}${path}`, { ...rest, body, headers })
  } catch {
    throw new ApiError(-1, '网络异常，请检查网络连接')
  }
  const text = await resp.text()
  // 404 = 后端未注册该路由（见 ApiError.notImplemented）。降噪提示一次后**仍抛出**，
  // 由页面显式 catch 或 main.ts 的统一兜底来降级 —— 不在这里吞掉。
  if (resp.status === 404) {
    warnUnimplemented(path, rest.method as string | undefined)
    throw new ApiError(404, '接口尚未实现（后端未注册该路由）', true)
  }
  let parsed: ApiResponse<T>
  try {
    parsed = JSON.parse(text) as ApiResponse<T>
  } catch {
    throw new ApiError(resp.status, text || `请求失败（${resp.status}）`)
  }
  if (parsed.code !== 0) {
    // 401 统一跳登录
    if (parsed.code === 401 || resp.status === 401) {
      localStorage.removeItem('ak_token')
      if (!location.hash.includes('/login')) location.hash = '#/login'
    }
    throw new ApiError(parsed.code, parsed.message)
  }
  return parsed.data
}

export const http = {
  get<T>(path: string, query?: Record<string, string | number | undefined>) {
    const qs = query
      ? '?' +
        Object.entries(query)
          .filter(([, v]) => v !== undefined && v !== '')
          .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
          .join('&')
      : ''
    return request<T>(path + qs, { method: 'GET' })
  },
  post<T>(path: string, body?: unknown) {
    return request<T>(path, { method: 'POST', body: serializeBody(body) })
  },
  put<T>(path: string, body?: unknown) {
    return request<T>(path, { method: 'PUT', body: serializeBody(body) })
  },
  patch<T>(path: string, body?: unknown) {
    return request<T>(path, { method: 'PATCH', body: serializeBody(body) })
  },
  delete<T>(path: string, body?: unknown) {
    return request<T>(path, { method: 'DELETE', body: serializeBody(body) })
  },
  /**
   * 带**真实上传进度**的 multipart 请求（T-500）。
   *
   * <h3>为什么不能用 `request()` / `fetch`</h3>
   * `fetch` 至今**没有上传进度事件** —— `Response` 的 `body` 可读流是**下载**方向，
   * 上传方向要等 streaming request body（`duplex: 'half'`）在全部浏览器落地且
   * 服务端也支持才有。所以这里用 `XMLHttpRequest` 的
   * `upload.onprogress` —— 它是唯一跨浏览器可用的上传进度来源。
   *
   * <p>⚠️ 这正是本批要修的那个缺陷：上一版的上传进度条是 `setInterval` 编出来的
   * （见 `KbUploadView.vue` 的 W1），真实网络下它会"匀速跑到 100%"再卡住，
   * 用户完全看不出卡在哪一段。
   *
   * <h3>不能设 Content-Type</h3>
   * multipart 的 `Content-Type` 要带 `boundary`，那是浏览器按 FormData 实例算的，
   * 手写不出来 —— 显式设了会让后端把 body 当成一个整体字段解析，报"缺少 file 参数"。
   * （同 {@link serializeBody} 的说明，那是 A-1 修的同一个坑。）
   */
  upload<T>(path: string, form: FormData, onProgress?: (percent: number) => void): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      xhr.open('POST', `${BASE}${path}`)
      const token = getToken()
      if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)

      if (onProgress && xhr.upload) {
        xhr.upload.onprogress = (e) => {
          if (e.lengthComputable && e.total > 0) {
            // 不取整到 100：进度到 99 之前都还没"传完"，最后那 1% 留给服务端确认，
            // 否则会出现"进度条满了但还在转"的观感（与 W1 的假进度条同一种不体面）
            onProgress(Math.min(99, Math.round((e.loaded / e.total) * 100)))
          }
        }
      }

      xhr.onload = () => {
        // 与 request() 同一套解包口径：404 = 后端未注册路由（降级而非崩溃）
        if (xhr.status === 404) {
          warnUnimplemented(path, 'POST')
          reject(new ApiError(404, '接口尚未实现（后端未注册该路由）', true))
          return
        }
        let parsed: ApiResponse<T>
        try {
          parsed = JSON.parse(xhr.responseText) as ApiResponse<T>
        } catch {
          reject(new ApiError(xhr.status, xhr.responseText || `请求失败（${xhr.status}）`))
          return
        }
        if (parsed.code !== 0) {
          if (parsed.code === 401 || xhr.status === 401) {
            localStorage.removeItem('ak_token')
            if (!location.hash.includes('/login')) location.hash = '#/login'
          }
          reject(new ApiError(parsed.code, parsed.message))
          return
        }
        // 成功时补一次 100：真实进度停在上面的 99，这里是"服务端已确认收下"
        onProgress?.(100)
        resolve(parsed.data)
      }
      xhr.onerror = () => reject(new ApiError(-1, '网络异常，请检查网络连接'))
      xhr.ontimeout = () => reject(new ApiError(-1, '上传超时，请重试'))
      // 大文件在慢网下会超过浏览器默认的 0（= 不限），显式给一个宽松上限：
      // 100MB 在 1Mbps 上要 13 分钟，取 30 分钟避免"传到一半被前端掐断"
      xhr.timeout = 30 * 60 * 1000
      xhr.send(form)
    })
  },
}

/** SSE 事件回调集合 */
export interface SSEHandlers {
  /** 图片理解进度（V2 新增）：带图片时首包即 DESCRIBING */
  onAttachment?: (data: {
    /** `READING` = 直接读原图（`T-806`）—— **不调 VL**，与 `DESCRIBING` 是两件事 */
    stage: 'DESCRIBING' | 'DONE' | 'READING'
    attachmentId: string
    desc?: string
    searchSummary?: string
    vlModel?: string
    degraded?: boolean
  }) => void
  onRetrieval?: (data: { chunks: unknown[]; trace: unknown; kbCount: number }) => void
  /** 思考过程增量（`T-D07`）。**与 `onDelta` 是两条通道**：前者渲染可折叠思考区，后者渲染正文 */
  onReasoning?: (data: { content: string }) => void
  onDelta?: (data: { content: string }) => void
  onDone?: (data: {
    messageId: string
    modelId?: number
    modelName?: string
    tokenUsage: unknown
    finishReason: string
    usedVision?: boolean
    /** 答案**实际引用**的那几条（`T-804`）—— 按角标号升序。
     *  ⚠️ 与 `onRetrieval` 的 `chunks` 是**两份不同的清单**（后者 = 检索找到的） */
    citations?: Citation[]
  }) => void
  onError?: (message: string) => void
}

/** 问答请求可选参数（V2：模型切换 + 图片附件 + 按需读原图） */
export interface ChatStreamOptions {
  /** 对话模型 id；与当前会话绑定不一致时服务端会开启新会话 */
  modelId?: number | null
  /** 图片附件 id 列表（先经 /chat/attachments 预上传） */
  attachmentIds?: string[]
  /** 本轮是否把原图直接传给对话模型（兜底，仅该轮生效） */
  useVision?: boolean
  /** 是否采集/显示思考过程（`T-D08`）；不传 = 默认开（与后端"只认显式 false"同一条口径） */
  reasoning?: boolean
}

/**
 * SSE 流式问答（chat completions）
 * fetch 流式读取 text/event-stream，按事件分发
 * 路径与《10-接口约定汇总》§9 一致：POST /api/chat/completions
 */
export async function chatStream(message: string, handlers: SSEHandlers, options: ChatStreamOptions = {}): Promise<void> {
  const token = getToken()
  const body: Record<string, unknown> = { message }
  if (options.modelId != null) body.modelId = options.modelId
  if (options.attachmentIds?.length) body.attachmentIds = options.attachmentIds
  if (options.useVision) body.useVision = true
  // 只在**显式关闭**时发 reasoning:false —— 默认开交给后端口径，避免两端各定义一次默认
  if (options.reasoning === false) body.reasoning = false

  const resp = await fetch(`${BASE}/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  })
  if (!resp.ok || !resp.body) {
    handlers.onError?.(`连接失败（${resp.status}）`)
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const dispatch = (chunk: string) => {
    const lines = chunk.split('\n')
    let event = 'message'
    for (const line of lines) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) {
        const payload = line.slice(5).trim()
        if (!payload) continue
        try {
          const data = JSON.parse(payload)
          if (event === 'attachment') handlers.onAttachment?.(data)
          else if (event === 'retrieval') handlers.onRetrieval?.(data)
          else if (event === 'reasoning') handlers.onReasoning?.({ content: data.content ?? '' })
          else if (event === 'delta') handlers.onDelta?.({ content: data.content ?? '' })
          else if (event === 'done') handlers.onDone?.(data)
          else if (event === 'error') handlers.onError?.(data.message ?? '生成出错')
        } catch {
          /* 忽略残缺帧 */
        }
      }
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    // SSE 帧以空行分隔
    let idx: number
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      if (frame.trim()) dispatch(frame)
    }
  }
  if (buffer.trim()) dispatch(buffer)
}

export default http
