import { defineConfig, devices } from '@playwright/test'

const baseURL =
  process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5182'

export default defineConfig({
  testDir: './e2e',
  outputDir: '../../.planning/nav-plan-c/playwright-output',
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  reporter: [['line']],
  use: {
    ...devices['Desktop Chrome'],
    baseURL,
    channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome',
    viewport: { width: 390, height: 844 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run dev:h5 -- --host 127.0.0.1 --port 5182 --strictPort',
    url: baseURL,
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
