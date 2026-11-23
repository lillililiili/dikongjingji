import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page, type Route } from '@playwright/test'

import {
  BUYER,
  apiLogin,
  databaseAvailable,
  offerId,
  purgeBuyerOrders,
  submitOrderByApi,
  type Session,
} from './support/trade-fixtures'

// 全局搜索（计划 08 S5-1，画板 SearchPre、MallSearch、OrderSearch）。真实后端：GET /api/v1/search 读东营的开发种子
// （商品“行业测绘无人机 RTK 版”、四类专业服务里的测绘服务等）；无结果、纠错和“到商城里筛选”用接口桩建前置条件。
// 订单搜索用接口给 dev-customer 建一张商品订单，用例前后删掉。
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare/s5-search')
const KIT_FACTORY = offerId('101', '1')
const KIT_PRICE = 129900

async function openHome(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-page')).toBeVisible()
  await expect(page.getByTestId('home-city')).toContainText('东营')
}

async function typeKeyword(page: Page, keyword: string): Promise<void> {
  const input = page.getByTestId('search-pre-input').locator('input')
  await input.fill(keyword)
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

function emptyGroups(scope: string) {
  const group = { total: 0, items: [] }
  const all = scope === 'ALL'
  return {
    products: all || scope === 'PRODUCT' ? group : null,
    services: all || scope === 'SERVICE' ? group : null,
    activities: all || scope === 'ACTIVITY' ? group : null,
    contents: all || scope === 'CONTENT' ? group : null,
    venues: all || scope === 'VENUE' ? group : null,
  }
}

// Answers every search with nothing found (and a correction for 巡简).
async function stubEmptySearch(page: Page): Promise<void> {
  await page.route('**/api/v1/search?**', async (route: Route) => {
    const url = new URL(route.request().url())
    const keyword = url.searchParams.get('keyword') ?? ''
    const scope = url.searchParams.get('scope') ?? 'ALL'
    await route.fulfill({
      json: {
        cityCode: url.searchParams.get('cityCode'),
        keyword,
        scope,
        correction: keyword === '巡简' ? { keyword: '巡检' } : null,
        ...emptyGroups(scope),
      },
    })
  })
}

test.describe('搜索前置页与结果页', () => {
  test('SRCH-001 推荐频道的搜索框进前置页；联想、搜索、结果分组、查看全部、最近搜索', async ({ page }) => {
    await page.addInitScript(() => localStorage.removeItem('search.recent-words'))
    await openHome(page)

    await page.getByTestId('home-search-input').click()
    await expect(page).toHaveURL(/pages\/search\/index$/)
    await expect(page.getByTestId('search-scope-all')).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByTestId('search-pre-city')).toHaveText('东营 · 结果按本城可用范围显示')
    await expect(page.getByTestId('search-recent-none')).toHaveText('还没有搜索记录')
    await expect(page.locator('[data-testid^="search-category-"]')).toHaveCount(8)

    // 联想：直达详情的几条，首行是“搜索“测绘””。
    await typeKeyword(page, '测绘')
    await expect(page.getByTestId('search-pre-go')).toContainText('搜索“测绘”')
    const suggestions = page.getByTestId('search-suggestions')
    await expect(suggestions).toContainText('行业测绘无人机')
    await expect(suggestions.locator('.search-highlight__hit').first()).toHaveText('测绘')

    if (process.env.SEARCH_VISUAL) {
      mkdirSync(compareOutput, { recursive: true })
      await page.screenshot({ path: resolve(compareOutput, 'search-pre-typing-390.png') })
    }

    await page.getByTestId('search-pre-submit').click()
    await expect(page).toHaveURL(/pages\/search\/results\?keyword=.*&scope=ALL/)
    await expect(page.getByTestId('search-results-keyword')).toHaveText('测绘')
    const products = page.getByTestId('search-group-product')
    await expect(products).toContainText('商品 ·')
    await expect(products).toContainText('行业测绘无人机')
    const services = page.getByTestId('search-group-service')
    await expect(services).toContainText('测绘')
    await expect(services.locator('[data-testid$="-demand"]').first()).toHaveText('提需求')
    await assertNoHorizontalOverflow(page)

    if (process.env.SEARCH_VISUAL) await page.screenshot({ path: resolve(compareOutput, 'search-results-390.png'), fullPage: true })

    // 查看全部 → 服务页签；其他组不再显示。
    const showAll = page.getByTestId('search-group-service-all')
    if (await showAll.count()) {
      await showAll.click()
    } else {
      await page.getByTestId('search-scope-service').click()
    }
    await expect(page.getByTestId('search-scope-service')).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByTestId('search-group-product')).toHaveCount(0)
    await expect(page.getByTestId('search-group-service')).toBeVisible()
    await expect(page.getByTestId('search-results-end')).toBeVisible()

    // 点一条服务进它自己的详情页。
    await page.getByTestId('search-group-service').locator('[data-testid^="search-row-"]').first().click()
    await expect(page).toHaveURL(/pages\/(proservice|service\/inspection)\/detail\?serviceId=/)
    await page.goBack()

    // 返回到前置页，“测绘”记在最近搜索里；清空后显示“还没有搜索记录”。
    await page.getByTestId('search-results-back').click()
    await expect(page).toHaveURL(/pages\/search\/index/)
    await page.getByTestId('search-pre-clear').click()
    await expect(page.getByTestId('search-recent-word')).toHaveText(['测绘'])
    await page.getByTestId('search-recent-clear').click()
    await expect(page.getByTestId('search-recent-none')).toBeVisible()
  })

  test('SRCH-002 频道进前置页落到对应范围；专业服务频道的搜索框仍直达巡检目录', async ({ page }) => {
    await openHome(page)

    await page.getByTestId('home-channel-mall').click()
    await expect(page.getByTestId('mall-channel')).toBeVisible()
    await page.getByTestId('home-search-input').click()
    await expect(page).toHaveURL(/pages\/search\/index\?scope=PRODUCT/)
    await expect(page.getByTestId('search-scope-product')).toHaveAttribute('aria-selected', 'true')
    await page.getByTestId('search-pre-back').click()

    await page.getByTestId('home-channel-activity').click()
    await page.getByTestId('home-search-submit').click()
    await expect(page).toHaveURL(/pages\/search\/index\?scope=ACTIVITY/)
    await page.getByTestId('search-pre-back').click()

    await page.getByTestId('home-channel-service').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    const input = page.getByTestId('home-search-input').locator('input')
    await input.fill('电力')
    await input.press('Enter')
    await expect(page).toHaveURL(/pages\/service\/inspection\/catalog\?.*keyword=/)
  })

  test('SRCH-003 输入常见错字出现纠错，点它改成正确的词', async ({ page }) => {
    await stubEmptySearch(page)
    await page.goto('/#/pages/search/index')
    await typeKeyword(page, '巡简')
    await expect(page.getByTestId('search-pre-fix')).toContainText('你是不是要搜：巡检')
    await page.getByTestId('search-pre-fix').click()
    await expect(page.getByTestId('search-pre-input').locator('input')).toHaveValue('巡检')
    await expect(page.getByTestId('search-pre-go')).toContainText('搜索“巡检”')
  })

  test('SRCH-004 没有结果：综合给发布采购需求和联系客服，单一范围说没有相关场馆', async ({ page }) => {
    await stubEmptySearch(page)
    await page.goto(`/#/pages/search/results?keyword=${encodeURIComponent('激光雷达模块')}&scope=ALL`)
    const empty = page.getByTestId('search-results-empty')
    await expect(empty).toContainText('没有找到“激光雷达模块”')
    await expect(empty).toContainText('换个关键词试试，或者直接把需求发给平台，由商家报价。')
    await expect(page.getByTestId('search-results-demand')).toHaveText('发布采购需求')
    await expect(page.getByTestId('search-results-support')).toHaveText('联系客服')

    await page.getByTestId('search-scope-venue').click()
    await expect(page.getByTestId('search-results-scope-empty')).toHaveText('没有找到相关场馆')

    // 搜索失败可以重试。
    await page.unroute('**/api/v1/search?**')
    await page.route('**/api/v1/search?**', (route) => route.fulfill({ status: 503, json: { code: 'COMMON_UNAVAILABLE', message: 'down', traceId: 't' } }))
    await page.getByTestId('search-scope-content').click()
    await expect(page.getByTestId('search-results-error')).toBeVisible()
    await page.unroute('**/api/v1/search?**')
    await stubEmptySearch(page)
    await page.getByTestId('search-results-retry').click()
    await expect(page.getByTestId('search-results-scope-empty')).toHaveText('没有找到相关内容')
  })

  test('SRCH-005 商品页签到商城频道里带关键词筛选、排序（保留频道内搜索）', async ({ page }) => {
    await openHome(page)
    await page.getByTestId('home-channel-mall').click()
    await page.getByTestId('home-search-input').click()
    await typeKeyword(page, '无人机')
    await page.getByTestId('search-pre-input').locator('input').press('Enter')
    await expect(page).toHaveURL(/pages\/search\/results\?keyword=.*&scope=PRODUCT/)
    await expect(page.getByTestId('search-group-product')).toBeVisible()
    await page.getByTestId('search-group-product-channel').click()
    await expect(page.getByTestId('mall-channel')).toBeVisible()
    await expect(page.getByTestId('home-channel-mall')).toHaveClass(/home-header__channel--active/)
    await expect(page.getByTestId('mall-chip-keyword')).toContainText('“无人机”')
  })

  test('SRCH-006 发现页和视频流的搜索图标进前置页', async ({ page }) => {
    await page.goto('/#/pages/discover/index')
    await expect(page.getByTestId('content-discover')).toBeVisible()
    await page.getByTestId('discover-search').click()
    await expect(page).toHaveURL(/pages\/search\/index$/)
    await expect(page.getByTestId('search-scope-all')).toHaveAttribute('aria-selected', 'true')
  })
})

test.describe('订单搜索', () => {
  test.describe.configure({ mode: 'serial' })

  let buyer: Session

  test.beforeEach(async ({ request }) => {
    const signedIn = await apiLogin(request, BUYER)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    test.skip(!databaseAvailable(), '没有本地开发数据库容器，无法清理测试数据')
    buyer = signedIn as Session
    purgeBuyerOrders()
  })

  test.afterAll(() => {
    if (databaseAvailable()) purgeBuyerOrders()
  })

  test('ORDS-001 我的订单的搜索图标进订单搜索；按商品名、订单号后几位搜；搜不到时给出口', async ({ page, request }) => {
    const order = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    await page.addInitScript((token) => {
      localStorage.setItem('inspection.accessToken', token)
      localStorage.removeItem('orders.recent-search-words')
    }, buyer.token)

    await page.goto('/#/pages/mine/orders')
    await expect(page.getByTestId('my-orders-head')).toContainText('我的订单')
    await page.getByTestId('my-orders-search').click()
    await expect(page).toHaveURL(/pages\/mine\/order-search$/)
    await expect(page.getByTestId('order-search-recent-none')).toHaveText('还没有搜索记录')
    await expect(page.getByTestId('order-search')).toContainText('可以这样搜')

    const input = page.getByTestId('order-search-input').locator('input')
    await input.fill('编程机器人')
    await expect(page.getByTestId('order-search-count')).toContainText('“编程机器人” · 找到')
    const hit = page.getByTestId(`order-search-hit-goods-${order.orderId}`)
    await expect(hit).toContainText('编程机器人探索套装')
    await expect(hit.locator('.search-highlight__hit').first()).toHaveText('编程机器人')
    await page.getByTestId('order-search-submit').click()

    // 订单号不分大小写，可只输后几位。
    const orderNo = (await hit.locator('.order-search__no').innerText()).replace('订单号', '').trim()
    await input.fill(orderNo.slice(-6).toLowerCase())
    await expect(hit).toBeVisible()

    await input.fill('激光雷达模块')
    await expect(page.getByTestId('order-search-empty')).toContainText('没有找到“激光雷达模块”')
    await expect(page.getByTestId('order-search-all')).toHaveText('查看全部订单')

    await page.getByTestId('order-search-clear').click()
    await expect(page.getByTestId('order-search-recent-word')).toHaveText(['编程机器人'])

    await page.getByTestId('order-search-recent-word').click()
    await hit.click()
    await expect(page).toHaveURL(new RegExp(`pages/mine/goods-order\\?orderId=${order.orderId}`))
  })
})
