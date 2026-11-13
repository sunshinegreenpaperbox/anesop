import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
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
const require = createRequire(import.meta.url)
// async-validator 同时提供 CJS（dist-node，main）与 ESM（dist-web，module）入口；
// Vitest 的 SSR 解析走 Node main，Element Plus 的默认导入会拿到 CJS 命名空间而非构造函数，
// 表现为表单校验永远返回 true。这里显式指向浏览器入口，与 Vite 生产构建保持一致。
const asyncValidatorEsm = join(
  dirname(require.resolve('element-plus/package.json')),
  '..',
  'async-validator',
  'dist-web',
  'index.js',
)

export default defineConfig({
  resolve: {
    alias: {
      'async-validator': asyncValidatorEsm,
    },
  },
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    // 宿主机并行代理/CI 负载高时，Element Plus 首屏 mount 可能超过 Vitest 默认 5s；
    // 仅放宽超时，不改变任何断言（2026-11-13 M2-M1-01 验证环境 load average≈12）。
    testTimeout: 15_000,
    hookTimeout: 15_000,
    setupFiles: ['src/__tests__/setup.ts'],
    server: {
      deps: {
        // 让 Vite 处理 Element Plus，使上面的 async-validator 别名生效。
        inline: [/element-plus/, /async-validator/],
        interopDefault: true,
      },
    },
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
