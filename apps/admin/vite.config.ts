/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 开发：npm run dev，端口 17430；/api 代理到本机后端 18420（和 App 开发版共用同一个后端）。
// 生产构建是纯静态文件，部署时由 Nginx 把 /api 转到后端（同源，不需要 CORS）。
const backend = process.env.ADMIN_API_TARGET ?? 'http://127.0.0.1:18420'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 17430,
    strictPort: true,
    proxy: {
      '/api': {
        target: backend,
        changeOrigin: false,
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 17431,
    strictPort: true,
  },
  build: {
    chunkSizeWarningLimit: 1600,
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    restoreMocks: true,
    // Element Plus 和它用的 async-validator 交给 Vite 处理（按 ESM 解析），不然 Node 按 CJS 加载 async-validator，
    // 表单校验在测试里一直停在“校验中”。
    server: {
      deps: {
        inline: ['element-plus', 'async-validator'],
      },
    },
  },
})
