import { fileURLToPath } from 'node:url'
import { defineConfig, devices } from '@playwright/test'

/**
 * ANESOP E2E（S0-11）。
 *
 * 从仓库根执行 `pnpm e2e`（等价 `playwright test -c apps/web/playwright.config.ts`）。
 * webServer 用 Vite dev server（端口 5173，strictPort 避免静默换端口导致断言打到旧进程）；
 * 本机重复执行时复用已启动的服务，CI 中每次冷启动。
 */
const repoRoot = fileURLToPath(new URL('../..', import.meta.url))

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'pnpm -C apps/web dev --port 5173 --strictPort --host 127.0.0.1',
    cwd: repoRoot,
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
