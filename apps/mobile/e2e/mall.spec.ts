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

// 搜索和筛选（计划 §15.2）。首页搜索框在商城频道里搜商城；筛选在页内面板里选。
test.describe('商城搜索与筛选', () => {
  const names = (page: Page) => page.locator('.mall-card__name')

  async function searchFromHome(page: Page, keyword: string): Promise<void> {
    const input = page.getByTestId('home-search-input').locator('input')
    await input.fill(keyword)
    await input.press('Enter')
  }

  test('MALL-201 首页搜索框在商城频道里按名称和简介搜，标签可单独移除', async ({ page }) => {
    await openMallChannel(page)
    const requests: URL[] = []
    page.on('request', (request) => {
      const url = new URL(request.url())
      if (url.pathname === '/api/v1/mall/products') requests.push(url)
    })

    await searchFromHome(page, '  无人机  ')
    await expect(page.getByTestId('mall-chip-keyword')).toContainText('“无人机”')
    await expect(names(page)).toHaveCount(7)
    expect(requests.at(-1)?.searchParams.get('keyword')).toBe('无人机')

    // 类目和搜索同时生效。
    await page.getByTestId('mall-category-rental').click()
    await expect(names(page)).toHaveText(['4K 航拍无人机 含飞手', '行业测绘无人机 RTK 版'])

    // 移除标签：搜索结束，搜索框跟着清空，类目保留。
    await page.getByTestId('mall-chip-keyword').click()
    await expect(page.getByTestId('mall-chips')).toHaveCount(0)
    await expect(page.getByTestId('home-search-input').locator('input')).toHaveValue('')
    await expect(names(page)).toHaveCount(4)
  })

  test('MALL-202 % 按字面搜；搜不到时说明并能一键清除', async ({ page }) => {
    await openMallChannel(page)

    await searchFromHome(page, '%')
    await expect(page.getByTestId('mall-search-empty')).toContainText('没有找到“%”')

    await page.getByTestId('mall-clear-all').click()
    await expect(names(page)).toHaveCount(10)
    await expect(page.getByTestId('home-search-input').locator('input')).toHaveValue('')
  })

  test('MALL-203 筛选面板：多选、按钮上的件数、应用后出标签和计数', async ({ page }) => {
    await openMallChannel(page)

    await page.getByTestId('mall-filter-open').click()
    await expect(page.getByTestId('mall-filter-sheet')).toBeVisible()
    await page.getByTestId('mall-filter-rent').click()
    await expect(page.getByTestId('mall-filter-rent')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('mall-filter-apply')).toHaveText('查看 4 件')
    await page.getByTestId('mall-filter-venue_display').click()
    await expect(page.getByTestId('mall-filter-apply')).toHaveText('查看 1 件')

    // 关闭不生效。
    await page.getByTestId('mall-filter-close').click()
    await expect(page.getByTestId('mall-chips')).toHaveCount(0)
    await expect(names(page)).toHaveCount(10)

    await page.getByTestId('mall-filter-open').click()
    await page.getByTestId('mall-filter-rent').click()
    await page.getByTestId('mall-filter-venue_display').click()
    await page.getByTestId('mall-filter-apply').click()
    await expect(page.getByTestId('mall-filter-sheet')).toHaveCount(0)
    await expect(names(page)).toHaveText(['巡检机器狗'])
    await expect(page.getByTestId('mall-filter-open')).toContainText('筛选 2')

    await page.getByTestId('mall-chip-experiencebadges-venue_display').click()
    await expect(names(page)).toHaveCount(4)
    await expect(page.getByTestId('mall-filter-open')).toContainText('筛选 1')

    // 再打开时面板里是当前已选的；重置清空。
    await page.getByTestId('mall-filter-open').click()
    await expect(page.getByTestId('mall-filter-rent')).toHaveAttribute('aria-pressed', 'true')
    await page.getByTestId('mall-filter-reset').click()
    await expect(page.getByTestId('mall-filter-rent')).toHaveAttribute('aria-pressed', 'false')
    await page.getByTestId('mall-filter-apply').click()
    await expect(names(page)).toHaveCount(10)
  })

  test('MALL-204 价格区间按最低标价筛，询价商品不出现；最低价高于最高价不能应用', async ({ page }) => {
    await openMallChannel(page)
    await page.getByTestId('mall-filter-open').click()
    const min = page.getByTestId('mall-filter-min-price').locator('input')
    const max = page.getByTestId('mall-filter-max-price').locator('input')

    await min.fill('500')
    await max.fill('100')
    await max.blur()
    await expect(page.getByTestId('mall-filter-range-error')).toBeVisible()
    await page.getByTestId('mall-filter-apply').click()
    await expect(page.getByTestId('mall-filter-sheet')).toBeVisible()

    await min.fill('4000')
    await max.fill('10000')
    await max.blur()
    await expect(page.getByTestId('mall-filter-apply')).toHaveText('查看 2 件')
    await page.getByTestId('mall-filter-apply').click()

    await expect(names(page)).toHaveText(['4K 航拍无人机 标准版', '桌面六轴机械臂 教学版'])
    await expect(page.getByTestId('mall-chip-price')).toContainText('¥4000–10000')
    await page.getByTestId('mall-chip-price').click()
    await expect(names(page)).toHaveCount(10)
  })

  test('MALL-205 切到别的频道，搜索结束，筛选保留', async ({ page }) => {
    await openMallChannel(page)
    await page.getByTestId('mall-filter-open').click()
    await page.getByTestId('mall-filter-rent').click()
    await page.getByTestId('mall-filter-apply').click()
    await searchFromHome(page, '无人机')
    await expect(names(page)).toHaveCount(2)

    await page.getByTestId('home-channel-recommend').click()
    await page.getByTestId('home-channel-mall').click()

    await expect(page.getByTestId('mall-chip-keyword')).toHaveCount(0)
    await expect(page.getByTestId('mall-chip-salemodes-rent')).toBeVisible()
    await expect(names(page)).toHaveCount(4)
  })

  test('MALL-206 筛选面板在 320 宽不溢出，选项不折成竖排', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 640 })
    await openMallChannel(page)
    await page.getByTestId('mall-filter-open').click()
    await expect(page.getByTestId('mall-filter-sheet')).toBeVisible()

    await assertNoHorizontalOverflow(page)
    for (const code of ['online_experience', 'authorized_dealer', 'local_store']) {
      const box = await page.getByTestId(`mall-filter-${code}`).boundingBox()
      expect(box?.height ?? 99).toBeLessThanOrEqual(40)
    }
    await expect(page.getByTestId('mall-filter-apply')).toBeInViewport()
    mkdirSync(visualOutput, { recursive: true })
    await page.screenshot({ path: resolve(visualOutput, 'mall-filter-sheet-320.png') })
  })
})

test.describe('商品详情', () => {
  test('MALL-101 列表 → 详情 → 换商家 → 价格和销售方跟着变；购买入口打开规格与数量', async ({ page }) => {
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

    // 加入购物车和立即购买都先打开“规格与数量”（D 步起是真的，见 mall-cart.spec.ts）。
    const url = page.url()
    await page.getByTestId('mall-product-cart').click()
    await expect(page.getByTestId('mall-buy-sheet')).toBeVisible()
    await expect(page.getByTestId('mall-buy-sheet')).toContainText('示例教育装备代理（授权经销）')
    await page.getByTestId('mall-buy-close').click()
    await expect(page.getByTestId('mall-buy-sheet')).toHaveCount(0)
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
