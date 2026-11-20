import type { Page } from '@playwright/test'

/**
 * Opens the inspection order center (M08) on top of 我的, the stack its old 全部 › entry made.
 * In the preview build 全部 › now opens the merged 我的订单 (R6-1), so the M08 back tests set up
 * the same stack through the app's own router (AGENTS §15 allows a deep link for single-page
 * back tests; the entry itself is covered by the mine and service-flow specs).
 */
export async function openInspectionOrdersOverMine(page: Page): Promise<void> {
  await page.evaluate(() => {
    const app = globalThis as unknown as { uni: { navigateTo(options: { url: string }): void } }
    app.uni.navigateTo({ url: '/pages/mine/inspection-orders?stage=ALL' })
  })
}
