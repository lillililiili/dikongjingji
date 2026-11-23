import { expect, test, type Page } from '@playwright/test'

import { compareFolder, compareWithBoard, shootApp } from './support/board-compare'
import { API, CITY } from './support/trade-fixtures'

// 商家主页（S5-2，画板 Merchant 的商家帧）与商品详情的交易主体（S4-4，画板 PdFull）。真实后端的开发种子：
// “示例机器人商家”有一版审核通过的商家资料（R__dev_trade_return_address_and_profile.sql），
// “桌面六轴机械臂 教学版”标为不支持七天无理由。页面上每个数字都和公开接口对照，不写死。
const MERCHANT_ID = 'c1000000-0000-4000-8000-000000000001'
const KIT_ID = 'c2000000-0000-4000-8000-000000000101'
const ARM_ID = 'c2000000-0000-4000-8000-000000000102'
const folder = compareFolder('aftersale')

interface MerchantPageDto {
  name: string
  kind: string | null
  qualificationReviewed: boolean
  productCount: number
  activityCount: number
  contentCount: number
  activities: Array<{ activityId: string }>
  contents: Array<{ contentId: string; kind: string }>
}

async function openProduct(page: Page, productId: string): Promise<void> {
  await page.goto(`/?product=${productId}#/pages/mall/product?productId=${productId}&cityCode=${CITY}`)
  await expect(page.getByTestId('mall-product-parties')).toBeVisible()
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

test.describe('商家主页与交易主体', () => {
  test.describe.configure({ mode: 'serial' })

  let merchant: MerchantPageDto

  test.beforeEach(async ({ request }) => {
    const read = await request.get(`${API}/api/v1/mall/merchants/${MERCHANT_ID}?cityCode=${CITY}`).catch(() => null)
    test.skip(!read || !read.ok(), '本地后端没有商家主页接口（openapi-mall-merchant 1.0.0）')
    merchant = await (read as NonNullable<typeof read>).json()
  })

  test('MER-001 商品详情：交易主体五行、售后与保障按七天无理由显示、查看商家主页与资质', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await openProduct(page, KIT_ID)
    const parties = page.getByTestId('mall-product-parties')
    await expect(parties).toContainText('销售方')
    await expect(parties).toContainText('收款方')
    await expect(parties.getByTestId('trade-party-invoice')).toHaveText('示例机器人商家')
    await expect(parties).toContainText('发货履约')
    await expect(parties.getByTestId('trade-party-aftersale')).toHaveText('示例机器人商家 · 厂家保修')
    await expect(page.getByTestId('mall-product-after-7 天无理由退货')).toContainText('未拆封，按销售方规则执行')
    await expect(page.getByTestId('mall-product-after-保修')).toContainText('示例机器人商家 · 厂家保修')
    await expect(page.getByTestId('mall-product-merchant')).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await shootApp(page, folder, 'PdFull')

    await page.getByTestId('mall-product-merchant-link').click()
    await expect(page).toHaveURL(new RegExp(`pages/mall/merchant\\?merchantId=${MERCHANT_ID}&cityCode=${CITY}`))
    await expect(page.getByTestId('mall-merchant-name')).toHaveText(merchant.name)

    // 不支持七天无理由的商品不写这一条。
    await openProduct(page, ARM_ID)
    await expect(page.getByTestId('mall-product-after-sales')).toBeVisible()
    await expect(page.getByTestId('mall-product-after-7 天无理由退货')).toHaveCount(0)
  })

  test('MER-002 商家主页：头部、信息行、在售 / 活动 / 内容 / 资质四个页签；底栏“商家”也进来', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await openProduct(page, KIT_ID)
    await page.getByTestId('mall-product-merchant').click()
    await expect(page.getByTestId('mall-merchant')).toHaveAttribute('data-state', 'ready')

    await expect(page.getByTestId('mall-merchant-glyph')).toHaveText(merchant.name.slice(0, 1))
    await expect(page.getByTestId('mall-merchant-reviewed')).toHaveCount(merchant.qualificationReviewed ? 1 : 0)
    if (merchant.kind === 'FACTORY_DIRECT') await expect(page.getByTestId('mall-merchant-kind')).toHaveText('厂家直营')
    await expect(page.getByTestId('mall-merchant-info-entity')).toHaveText('示例机器人科技有限公司（示例）')
    await expect(page.getByTestId('mall-merchant-info-cities')).toContainText('东营')
    await expect(page.getByTestId('mall-merchant-info-cities')).toContainText('全国发货')
    await expect(page.getByTestId('mall-merchant-info-until')).toHaveText('至 2027-10-31')

    await expect(page.getByTestId('mall-merchant-tab-goods')).toHaveText(`在售 ${merchant.productCount}`)
    await expect(page.getByTestId('mall-merchant-tab-acts')).toHaveText(`活动 ${merchant.activityCount}`)
    await expect(page.getByTestId('mall-merchant-tab-posts')).toHaveText(`内容 ${merchant.contentCount}`)
    await expect(page.getByTestId('mall-merchant-tab-qual')).toHaveText('资质')
    await expect(page.getByTestId(`mall-merchant-product-${KIT_ID}`)).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await shootApp(page, folder, 'Merchant')

    // 在售卡进商品详情。
    await page.getByTestId(`mall-merchant-product-${KIT_ID}`).click()
    await expect(page).toHaveURL(new RegExp(`pages/mall/product\\?productId=${KIT_ID}&cityCode=${CITY}`))
    await page.goBack()
    await expect(page.getByTestId('mall-merchant')).toHaveAttribute('data-state', 'ready')

    await page.getByTestId('mall-merchant-tab-acts').click()
    await expect(page.getByTestId('mall-merchant-acts')).toBeVisible()
    await expect(page.locator('[data-testid^="mall-merchant-activity-"]')).toHaveCount(merchant.activities.length)
    await shootApp(page, folder, 'Merchant-活动')
    if (merchant.activities[0]) {
      await page.getByTestId(`mall-merchant-activity-${merchant.activities[0].activityId}`).click()
      await expect(page).toHaveURL(/pages\/activity\/detail\?activityId=/)
      await page.goBack()
      await page.getByTestId('mall-merchant-tab-acts').click()
    }

    await page.getByTestId('mall-merchant-tab-posts').click()
    await expect(page.locator('[data-testid^="mall-merchant-content-"]')).toHaveCount(merchant.contents.length)
    await shootApp(page, folder, 'Merchant-内容')
    const video = merchant.contents.find((content) => content.kind === 'VIDEO')
    if (video) {
      await page.getByTestId(`mall-merchant-content-${video.contentId}`).click()
      await expect(page).toHaveURL(/pages\/content\/video\?videoId=/)
      await page.goBack()
    }

    await page.getByTestId('mall-merchant-tab-qual').click()
    await expect(page.getByTestId('mall-merchant-qual-entity')).toContainText('示例机器人科技有限公司（示例）')
    await expect(page.getByTestId('mall-merchant-qual-license')).toContainText(merchant.qualificationReviewed ? '已审核' : '未审核')
    await expect(page.getByTestId('mall-merchant-qual-until')).toContainText('至 2027-10-31')
    await expect(page.getByTestId('mall-merchant-qual-category')).toContainText('教育机器人')
    await shootApp(page, folder, 'Merchant-资质')

    for (const width of [320, 430]) {
      await page.setViewportSize({ width, height: 844 })
      await assertNoHorizontalOverflow(page)
    }
  })

  test('MER-003 没有这家商家时说明并能返回', async ({ page }) => {
    await page.goto(`/#/pages/mall/merchant?merchantId=00000000-0000-4000-8000-000000000000&cityCode=${CITY}`)
    await expect(page.getByTestId('mall-merchant-missing')).toBeVisible()
  })

  test('MER-009 与画板同宽（390）并排：PdFull、Merchant（商家帧）', async ({ page }) => {
    for (const pair of [
      { board: 'PdFull', app: 'PdFull' },
      // 画板默认就是商家帧（另一帧是活动主办机构）。
      { board: 'Merchant', app: 'Merchant' },
    ]) {
      await compareWithBoard(page, folder, pair)
    }
  })
})
