import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import { mockPlugin } from './src/mock'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'
  // T-D03：mock 开关的**唯一读取点**（原先 mock/index.ts 自己读 process.env，那读不到 .env 文件）。
  const useMock = env.VITE_USE_MOCK !== 'false'

  return {
    plugins: [vue(), vueDevTools(), mockPlugin(useMock)],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy: {
        // 后端就绪后：VITE_USE_MOCK=false npm run dev 即转发真实后端
        '/api': { target: proxyTarget, changeOrigin: true },
      },
    },
  }
})
