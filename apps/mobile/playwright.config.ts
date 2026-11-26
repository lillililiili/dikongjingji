import { defineConfig, devices } from '@playwright/test'

import { testApi, testMobile as baseURL } from './e2e/support/test-stack'

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
    command: 'npm run dev:h5 -- --host 127.0.0.1 --port ' + (new URL(baseURL).port || '80') + ' --strictPort',
    env: { VITE_INSPECTION_API_BASE_URL: testApi },
    url: baseURL,
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
