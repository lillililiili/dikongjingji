import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page, type Route } from '@playwright/test'

// 商城频道与商品详情（只读）：类目、价格排序、分页、在售商家。
// 数据来自 dev 环境的示例商品（backend/.../db/development/mall）：东营有 12 件，另有 1 件只在济南、1 件已下架。
const visualOutput = resolve(__dirname, '../../../.planning/mall/visual')
const KIT_ID = 'c2000000-0000-4000-8000-000000000101'
const DOG_ID = 'c2000000-0000-4000-8000-000000000401'
const SURVEY_ID = 'c2000000-0000-4000-8000-000000000203'
const JINAN_ONLY_ID = 'c2000000-0000-4000-8000-000000000901'
const OFFLINE_ID = 'c2000000-0000-4000-8000-000000000902'

async function openHome(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-page')).toBeVisible()
  await expect(page.getByTestId('home-city')).toContainText('东营')
}

async function openMallChannel(page: Page): Promise<void> {
  await openHome(page)
  await page.getByTestId('home-channel-mall').click()
  await expect(page.getByTestId('mall-channel')).toBeVisible()
  await expect(page.locator('.mall-card').first()).toBeVisible()
}

async function openProduct(page: Page, productId: string, cityCode = '370500'): Promise<void> {
  await page.goto(
    `/?product=${productId}-${cityCode}#/pages/mall/product?productId=${productId}&cityCode=${cityCode}`,
  )
}

async function scrollToBottom(page: Page): Promise<void> {
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
}

// uni-app H5 does not look at scroll events for 350ms after it reports the bottom. This test changes
// the sort inside that window, so the framework never sees the page leave the bottom and would not
// report it a second time. A person cannot be that fast; the test waits the lock out and moves the
// page by one pixel so one "not at the bottom" event is seen.
async function leaveBottomThenScrollToBottom(page: Page): Promise<void> {
  await page.waitForTimeout(400)
  await page.evaluate(() => window.scrollTo(0, 1))
  await page.waitForTimeout(100)
  await scrollToBottom(page)
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

test.describe('商城频道', () => {
  test('MALL-001 首页频道“商城”列出本城在售商品，滚到底加载下一页', async ({ page }) => {
    await openMallChannel(page)

    await expect(page.getByTestId('home-channel-placeholder')).toHaveCount(0)
    await expect(page.getByTestId('mall-category-all')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.mall-channel__category')).toHaveText([
      '全部',
      '无人机',
      '机器人',
      '配件',
      '租赁',
      '课程',
    ])
    await expect(page.locator('.mall-card')).toHaveCount(10)
    await expect(page.getByTestId('home-search-input')).toContainText('搜索设备、配件或课程')
    await expect(page.getByTestId('root-tab-home')).toHaveClass(/root-tab-bar__item--active/)

    await scrollToBottom(page)
    await expect(page.locator('.mall-card')).toHaveCount(12)
    await expect(page.getByTestId('mall-list-more')).toContainText('没有更多商品了')
    await expect(page.getByText('仅济南在售的示例机器人')).toHaveCount(0)
    await expect(page.getByText('已下架的示例无人机')).toHaveCount(0)
  })

  test('MALL-002 卡片按交易方式显示价格、商家数和主按钮', async ({ page }) => {
    await openMallChannel(page)

    const kit = page.getByTestId(`mall-product-${KIT_ID}`)
    await expect(kit.locator('.mall-card__amount')).toHaveText('¥1,299')
    await expect(kit.locator('.mall-card__unit')).toHaveText(['起'])
    await expect(kit.locator('.mall-card__sellers')).toHaveText('3 家在售')
    await expect(kit.locator('.mall-card__badge')).toHaveText('可线上体验')
    await expect(kit.locator('.mall-card__action')).toHaveText('立即购买')

    const dog = page.getByTestId(`mall-product-${DOG_ID}`)
    await expect(dog).toHaveAttribute('data-sale-mode', 'RENT')
    await expect(dog.locator('.mall-card__amount')).toHaveText('¥380')
    await expect(dog.locator('.mall-card__unit')).toHaveText(['/天', '起'])
    await expect(dog.locator('.mall-card__sellers')).toHaveText('租赁 · 1 家')
    await expect(dog.locator('.mall-card__rent')).toHaveText('租赁')
    await expect(dog.locator('.mall-card__action')).toHaveText('联系询价')

    const survey = page.getByTestId(`mall-product-${SURVEY_ID}`)
    await expect(survey.locator('.mall-card__amount')).toHaveText('询价')
    await expect(survey.locator('.mall-card__unit')).toHaveCount(0)
    await expect(survey.locator('.mall-card__sellers')).toHaveText('2 家可询价')
  })

  test('MALL-003 类目页签筛选；课程类目为空时能回到全部', async ({ page }) => {
    await openMallChannel(page)

    await page.getByTestId('mall-category-robot').click()
    await expect(page.locator('.mall-card__name')).toHaveText([
      '编程机器人探索套装',
      '桌面六轴机械臂 教学版',
    ])

    await page.getByTestId('mall-category-rental').click()
    await expect(page.locator('.mall-card')).toHaveCount(4)
    await expect(page.locator('.mall-card__rent')).toHaveCount(4)

    await page.getByTestId('mall-category-course').click()
    await expect(page.getByTestId('mall-list-empty')).toContainText('暂无课程商品')
    await page.getByTestId('mall-show-all').click()
    await expect(page.getByTestId('mall-category-all')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.mall-card')).toHaveCount(10)
  })

  test('MALL-004 价格可升序、降序，询价商品始终排最后', async ({ page }) => {
    await openMallChannel(page)

    await page.getByTestId('mall-sort-price').click()
    await expect(page.getByTestId('mall-sort-price')).toHaveAttribute('data-sort', 'PRICE_ASC')
    await expect(page.locator('.mall-card__name').first()).toHaveText('4K 航拍无人机 含飞手')
    await scrollToBottom(page)
    await expect(page.locator('.mall-card')).toHaveCount(12)
    await expect(page.locator('.mall-card__name').last()).toHaveText('行业测绘无人机')

    await page.getByTestId('mall-sort-price').click()
    await expect(page.getByTestId('mall-sort-price')).toHaveAttribute('data-sort', 'PRICE_DESC')
    await expect(page.locator('.mall-card__name').first()).toHaveText('桌面六轴机械臂 教学版')
    await leaveBottomThenScrollToBottom(page)
    await expect(page.locator('.mall-card')).toHaveCount(12)
    await expect(page.locator('.mall-card__name').last()).toHaveText('行业测绘无人机')

    await page.getByTestId('mall-sort-default').click()
    await expect(page.locator('.mall-card__name').first()).toHaveText('编程机器人探索套装')
  })

  test('MALL-005 金刚位“机器人”“培训课程”直接进到商城的对应类目', async ({ page }) => {
    await openHome(page)
    await page.getByTestId('home-king-robot').click()

    await expect(page.getByTestId('home-channel-mall')).toHaveClass(/home-header__channel--active/)
    await expect(page.getByTestId('mall-category-robot')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.mall-card')).toHaveCount(2)

    await page.getByTestId('home-channel-recommend').click()
    await page.getByTestId('home-king-course').click()
    await expect(page.getByTestId('mall-category-course')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('mall-list-empty')).toContainText('暂无课程商品')

    // Asking for the same category again still selects it after the user moved to another tab.
    await page.getByTestId('mall-category-all').click()
    await page.getByTestId('home-channel-recommend').click()
    await page.getByTestId('home-king-course').click()
    await expect(page.getByTestId('mall-category-course')).toHaveAttribute('aria-pressed', 'true')
  })

  test('MALL-006 列表加载失败可重试', async ({ page }) => {
    let failing = true
    await page.route('**/api/v1/mall/products?**', (route: Route) =>
      failing ? route.abort('internetdisconnected') : route.continue())

    await openHome(page)
    await page.getByTestId('home-channel-mall').click()
    await expect(page.getByTestId('mall-list-error')).toContainText('商品加载失败')

    failing = false
    await page.getByTestId('mall-list-retry').click()
    await expect(page.locator('.mall-card')).toHaveCount(10)
  })
})

test.describe('商品详情', () => {
  test('MALL-101 列表 → 详情 → 换商家 → 价格和销售方跟着变；购买入口给出提示', async ({ page }) => {
    await openMallChannel(page)
    await page.getByTestId('mall-category-robot').click()
    await page.getByTestId(`mall-product-${KIT_ID}`).click()

    await expect(page.getByTestId('mall-product-name')).toHaveText('编程机器人探索套装')
    await expect(page).toHaveURL(new RegExp(`productId=${KIT_ID}.*cityCode=370500`))
    await expect(page.getByTestId('mall-product-price')).toHaveText('¥1,299')
    await expect(page.getByTestId('mall-product-shipping')).toHaveText('东营可买 · 包邮 · 48 小时内发货')
    await expect(page.getByTestId('mall-product-seller')).toContainText('示例机器人商家（厂家直营）')
    await expect(page.locator('.root-tab-bar')).toHaveCount(0)

    const offers = page.locator('.mall-product__offer')
    await expect(offers).toHaveCount(3)
    await expect(offers.nth(0)).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.mall-product__offer-price')).toHaveText(['¥1,299', '¥1,350', '¥1,380'])

    await offers.nth(1).click()
    await expect(page.getByTestId('mall-product-price')).toHaveText('¥1,350')
    await expect(page.getByTestId('mall-product-seller')).toContainText('示例教育装备代理（授权经销）')
    await expect(page.getByTestId('mall-product-shipping')).toHaveText('东营可买 · 包邮 · 交期 7 天')

    await expect(page.getByTestId('mall-product-specs')).toContainText('6 项')
    await expect(page.getByTestId('mall-product-specs')).toContainText('图形化 / Python')
    await expect(page.getByTestId('mall-product-pack')).toContainText('4 件')

    const url = page.url()
    await page.getByTestId('mall-product-cart').click()
    await expect(page.locator('.uni-sample-toast')).toContainText('购物车即将开放')
    await expect(page.getByTestId('mall-product-primary')).toHaveText('立即购买')
    expect(page.url()).toBe(url)

    await page.getByTestId('mall-product-back').click()
    await expect(page.getByTestId('mall-channel')).toBeVisible()
    await expect(page.getByTestId('mall-category-robot')).toHaveAttribute('aria-pressed', 'true')
  })

  test('MALL-102 租赁和询价商品只有“联系询价”，不能加购物车', async ({ page }) => {
    await openProduct(page, DOG_ID)
    await expect(page.getByTestId('mall-product-name')).toHaveText('巡检机器狗')
    await expect(page.getByTestId('mall-product-price')).toHaveText('¥380/天')
    await expect(page.getByTestId('mall-product')).toContainText('可租商家')
    await expect(page.getByTestId('mall-product-cart')).toHaveCount(0)
    await expect(page.getByTestId('mall-product-primary')).toHaveText('联系询价')

    await page.getByTestId('mall-product-primary').click()
    await expect(page.locator('.uni-sample-toast')).toContainText('询价功能即将开放')

    await openProduct(page, SURVEY_ID)
    await expect(page.getByTestId('mall-product-name')).toHaveText('行业测绘无人机')
    await expect(page.getByTestId('mall-product-price')).toHaveText('询价')
    await expect(page.locator('.mall-product__offer')).toHaveCount(2)
    await expect(page.getByTestId('mall-product-cart')).toHaveCount(0)
    await expect(page.getByTestId('mall-product-primary')).toHaveText('联系询价')
  })

  test('MALL-103 本城不卖、已下架、缺参数的链接都落到“商品不存在”，并能回商城', async ({ page }) => {
    for (const [productId, cityCode] of [
      [JINAN_ONLY_ID, '370500'],
      [OFFLINE_ID, '370500'],
      ['not-a-uuid', '370500'],
    ]) {
      await openProduct(page, productId, cityCode)
      await expect(page.getByTestId('mall-product-missing')).toContainText('商品不存在或本城暂不销售')
      await expect(page.getByTestId('mall-product-primary')).toHaveCount(0)
    }

    // The same product opens in the city that does sell it.
    await openProduct(page, JINAN_ONLY_ID, '370100')
    await expect(page.getByTestId('mall-product-name')).toHaveText('仅济南在售的示例机器人')

    await openProduct(page, OFFLINE_ID)
    await page.getByTestId('mall-product-to-channel').click()
    await expect(page.getByTestId('mall-channel')).toBeVisible()
  })

  test('MALL-104 详情加载失败可重试', async ({ page }) => {
    let failing = true
    await page.route(`**/api/v1/mall/products/${KIT_ID}?**`, (route: Route) =>
      failing ? route.abort('internetdisconnected') : route.continue())

    await openProduct(page, KIT_ID)
    await expect(page.getByTestId('mall-product-error')).toContainText('商品加载失败')

    failing = false
    await page.getByTestId('mall-product-retry').click()
    await expect(page.getByTestId('mall-product-name')).toHaveText('编程机器人探索套装')
  })
})

test('VIS-MALL 商城频道与商品详情在 320/375/430 无横向溢出并留存截图', async ({ page }) => {
  mkdirSync(visualOutput, { recursive: true })

  for (const width of [320, 375, 430]) {
    await page.setViewportSize({ width, height: 844 })
    await openMallChannel(page)
    await page.getByTestId('mall-category-all').click()
    await expect(page.locator('.mall-card').first()).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `mall-channel-${width}.png`) })

    await page.getByTestId(`mall-product-${KIT_ID}`).click()
    await expect(page.getByTestId('mall-product-name')).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `mall-product-${width}.png`), fullPage: true })
    await page.getByTestId('mall-product-back').click()
    await expect(page.getByTestId('mall-channel')).toBeVisible()
  }
})
