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

  // S4-4（巡检目录 1.4.0）：卡片第二行是服务商名（画板 HomeSvc），开发数据里电力巡检的服务商是云翼科技。
  test('HOT-103 热门服务卡第二行是服务商名', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')
    await page.getByTestId('home-channel-service').click()

    const firstCard = page.locator('.hot-service-card').first()
    await expect(firstCard).toBeVisible()
    await expect(firstCard.getByTestId('hot-service-provider')).toHaveText('云翼科技')
    // 分类名不再占这一行。
    await expect(firstCard).not.toContainText('电力 ')
  })

  test('HOT-102 从详情返回，回到专业服务频道', async ({ page }) => {
    await openHotService(page)
    await expect(page.getByTestId('inspection-detail')).toBeVisible()
    await page.getByTestId('inspection-detail-back').click()

    await expect(page.locator('.hot-service-card').first()).toBeVisible()
    await expect(page.getByTestId('home-channel-service')).toHaveClass(/active/)
  })
})
