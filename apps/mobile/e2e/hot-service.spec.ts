import { expect, test, type Page } from '@playwright/test'

const POWER_SERVICE_ID = '33333333-3333-3333-3333-333333333333'

async function openHotService(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-city')).toContainText('东营')
  await page.getByTestId('home-channel-service').click()
  await page.locator('.hot-service-card').first().click()
}

test.describe('M01 热门巡检服务', () => {
  test('HOT-101 点卡片进入服务详情，只带服务和城市', async ({ page }) => {
    await openHotService(page)

    await expect(page.getByTestId('inspection-detail')).toBeVisible()
    await expect(page).toHaveURL(
      new RegExp(`/detail\\?serviceId=${POWER_SERVICE_ID}&cityCode=370500$`),
    )
  })

  test('HOT-102 从详情返回，回到专业服务频道', async ({ page }) => {
    await openHotService(page)
    await expect(page.getByTestId('inspection-detail')).toBeVisible()
    await page.locator('[aria-label^="返回巡检服务"]').click()

    await expect(page.locator('.hot-service-card').first()).toBeVisible()
    await expect(page.getByTestId('home-channel-service')).toHaveClass(/active/)
  })
})
