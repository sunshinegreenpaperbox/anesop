import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * ANESOP 前端单元测试（S0-11）。
 *
 * - root 固定为 apps/web，保证从仓库根执行 `pnpm test:unit` 时路径与覆盖率口径稳定；
 * - jsdom 提供浏览器 DOM，Vue Test Utils 挂载组件；
 * - v8 覆盖率阈值与 AGENTS.md 一致：行 ≥70%、分支 ≥55%；
 * - 排除契约生成物、环境声明与入口装配文件（无业务逻辑）。
 */
export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    include: ['src/**/__tests__/**/*.spec.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'json-summary'],
      reportsDirectory: './coverage',
      include: ['src/**/*.{ts,vue}'],
      exclude: ['src/api/generated/**', 'src/env.d.ts', 'src/main.ts', 'src/**/__tests__/**'],
      thresholds: {
        lines: 70,
        branches: 55,
      },
    },
  },
})
