import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 本地开发：前端统一请求 `/api/v1/...`，由 Vite 代理到 Spring Boot（8080）。
 * 后端未配置 servlet context-path，因此 rewrite 去掉 `/api` 前缀，避免 404；
 * 生产由 Nginx 把 `/api` 反代到 API，保持同一前端代码。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: false,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    target: 'es2022',
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          'element-plus': ['element-plus'],
        },
      },
    },
  },
})
