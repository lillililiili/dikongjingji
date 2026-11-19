import { expect, test } from '@playwright/test'

// Runs against a production H5 build, where unfinished channels must stay hidden.
test.skip(
  !process.env.E2E_PUBLIC_BUILD,
  'Set E2E_PUBLIC_BUILD=1 and serve a production H5 build to run these cases.',
)

test.describe('公开构建', () => {
  test('PUB-001 只露出已闭环的频道和入口', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-page')).toBeVisible()

    await expect(page.locator('.home-header__channel')).toHaveText([
      '推荐',
      '专业服务',
    ])
    await expect(page.getByTestId('home-scan')).toHaveCount(0)
    await expect(page.getByTestId('home-card-experience')).toHaveCount(0)
    await expect(page.locator('.home-entries__card')).toHaveCount(1)
    // No king entry is open to the public yet, so the whole block and the sample carousel stay away.
    await expect(page.locator('.home-entries__king')).toHaveCount(0)
    await expect(page.getByTestId('home-carousel')).toHaveCount(0)
    // The blocks that read the activity, mall and venue channels stay away with those channels;
    // the public home keeps its inspection section.
    await expect(page.getByTestId('home-recommend')).toHaveCount(0)
    await expect(page.locator('.platform-home__section-title', { hasText: '精选服务' })).toBeVisible()
    await expect(page.getByTestId('home-search-input')).toContainText(
      '搜索巡检服务、场景或关键词',
    )
  })

  test('PUB-002 底栏五项完整，专业服务只有巡检', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(
      page.locator('.root-tab-bar .root-tab-bar__label'),
    ).toHaveText(['首页', '发现', '消息', '购物车', '我的'])

    await page.getByTestId('home-card-service').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect(page.locator('.service-channel__type')).toHaveText(['巡检'])
  })

  test('PUB-003 旧商城链接回到首页，不停在没有出口的页面', async ({ page }) => {
    await page.goto('/#/pages/mall/index')

    await expect(page.getByTestId('home-page')).toBeVisible()
    await expect(page.getByTestId('home-channel-recommend')).toHaveClass(
      /home-header__channel--active/,
    )
  })

  test('PUB-004 发现页未开通时底栏仍在', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await page.getByTestId('root-tab-discover').click()

    await expect(page.getByText('发现频道尚未开通')).toBeVisible()
    await expect(page.getByTestId('root-tab-discover')).toHaveClass(
      /root-tab-bar__item--active/,
    )
    await expect(page.locator('uni-tabbar .uni-tabbar')).toBeHidden()
  })

  // Was NAV-018. Needs the back end: the banner comes from the portal content of the city.
  test('PUB-006 Banner 按钮不带走未提交的搜索词', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')
    await page.getByTestId('home-search-input').locator('input').fill('未提交')

    await page.locator('.platform-hero-banner__action').click()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expect(
      page.getByTestId('catalog-search-input').locator('input'),
    ).toHaveValue('')
  })

  test('PUB-005 推荐频道搜索进入巡检目录', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')

    const input = page.getByTestId('home-search-input').locator('input')
    await input.fill('电力')
    await page.getByTestId('home-search-submit').click()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expect(
      page.getByTestId('catalog-search-input').locator('input'),
    ).toHaveValue('电力')
  })

  test('PUB-007 “我的”里没有活动报名和参与人管理，收费前的报名入口也不露出', async ({ page }) => {
    await page.goto('/#/pages/mine/index')
    await expect(page.getByTestId('mine-login')).toBeVisible()
    await expect(page.getByTestId('mine-joins')).toHaveCount(0)
    await expect(page.getByTestId('mine-link-activity_enrollments')).toHaveCount(0)
    await expect(page.getByTestId('mine-link-activity_participants')).toHaveCount(0)
  })

  test('PUB-008 购物车仍未开通：底栏“购物车”只有说明，没有角标', async ({ page }) => {
    await page.goto('/#/pages/cart/index')
    await expect(page.getByTestId('cart-unavailable')).toContainText('购物车功能尚未开通')
    await expect(page.getByTestId('cart')).toHaveCount(0)
    await expect(page.locator('.root-tab-bar__item', { hasText: '购物车' }).locator('.root-tab-bar__badge')).toHaveCount(0)
  })

  test('PUB-009 商品订单、收银台、支付结果未开通：页面只有说明，“我的”里没有商品订单入口', async ({ page }) => {
    await page.goto('/#/pages/mine/index')
    await expect(page.locator('.platform-mine__status-card')).toBeVisible()
    await expect(page.getByTestId('mine-goods-orders')).toHaveCount(0)
    for (const route of [
      '/pages/mine/goods-orders',
      '/pages/mine/goods-order?orderId=00000000-0000-4000-8000-000000000001',
      '/pages/trade/cashier?orderId=00000000-0000-4000-8000-000000000001',
      '/pages/trade/pay-result?orderId=00000000-0000-4000-8000-000000000001',
    ]) {
      await page.goto(`/?r=${encodeURIComponent(route)}#${route}`)
      await expect(page.getByTestId('trade-unavailable')).toContainText('商品订单功能尚未开通')
      await expect(page.getByTestId('goods-orders')).toHaveCount(0)
      await expect(page.getByTestId('cashier')).toHaveCount(0)
    }
  })

  test('PUB-011 需求发布未开通：专业服务没有发布入口，“我的”里没有需求与报价，需求页面只有说明', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await page.getByTestId('home-card-service').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect(page.getByTestId('portal-request')).toHaveCount(0)
    await expect(page.getByTestId('service-entry-publish')).toHaveCount(0)

    await page.goto('/#/pages/mine/index')
    await expect(page.locator('.platform-mine__status-card')).toBeVisible()
    await expect(page.getByTestId('mine-link-demands')).toHaveCount(0)
    for (const route of [
      '/pages/demand/publish?type=INSPECTION',
      '/pages/demand/quotes?demandId=00000000-0000-4000-8000-000000000001',
      '/pages/demand/compare?demandId=00000000-0000-4000-8000-000000000001',
      '/pages/mine/demands',
    ]) {
      await page.goto(`/?r=${encodeURIComponent(route)}#${route}`)
      await expect(page.getByTestId('demand-unavailable')).toContainText('需求发布功能尚未开通')
      await expect(page.getByTestId('demand-publish')).toHaveCount(0)
      await expect(page.getByTestId('demand-list')).toHaveCount(0)
      await expect(page.getByTestId('demand-quotes')).toHaveCount(0)
    }
  })

  test('PUB-010 到馆预约未开通：“我的”里没有入口，确认页、核销码、我的到馆预约只有说明', async ({ page }) => {
    await page.goto('/#/pages/mine/index')
    await expect(page.locator('.platform-mine__status-card')).toBeVisible()
    await expect(page.getByTestId('mine-link-venue_bookings')).toHaveCount(0)
    for (const route of [
      '/pages/venue/book?venueId=b2000000-0000-4000-8000-000000000001&cityCode=370500&projectId=b4000000-0000-4000-8000-000000000101&sessionId=00000000-0000-4000-8000-000000000001&date=2026-11-20',
      '/pages/venue/booking?bookingId=00000000-0000-4000-8000-000000000001',
      '/pages/mine/venue-bookings',
    ]) {
      await page.goto(`/?r=${encodeURIComponent(route)}#${route}`)
      await expect(page.getByTestId('venue-booking-unavailable')).toContainText('到馆预约功能尚未开通')
      await expect(page.getByTestId('venue-book')).toHaveCount(0)
      await expect(page.getByTestId('venue-booking')).toHaveCount(0)
      await expect(page.getByTestId('venue-bookings')).toHaveCount(0)
    }
  })
})
