import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page, type Route } from '@playwright/test'

// M02 巡检服务目录：分类、搜索、筛选、排序、距离、分页都由服务端完成（计划 §10）。
// 数据来自 dev 环境的 8 个示例服务：电力 4 个（含变电站、配电网、电缆）、光伏、油气、热力、园区各 1 个；
// 设备、定期没有服务；电缆通道巡检没有服务中心点；潍坊没有开通。
const visualOutput = resolve(__dirname, '../../../.planning/catalog-search/visual')
const SERVICES_PATH = '/api/v1/inspection/services'
const CATALOG_ROUTE = '/#/pages/service/inspection/catalog'
// 园区综合巡检服务的服务中心点，按距离排序时它排第一。
const NEAR_PARK = { latitude: 37.45, longitude: 118.66 }

const cards = (page: Page) => page.locator('.inspection-card')
const titles = (page: Page) => page.locator('.inspection-card__title')

function collectListRequests(page: Page): URL[] {
  const requests: URL[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname === SERVICES_PATH) requests.push(url)
  })
  return requests
}

async function openCatalog(page: Page, query = ''): Promise<void> {
  await page.goto(`/?catalog=${encodeURIComponent(query)}${CATALOG_ROUTE}?cityCode=370500${query}`)
  await expect(page.getByTestId('inspection-catalog')).toBeVisible()
  await expect(page.getByTestId('catalog-category-power')).toBeVisible()
}

// Loads the rest of the list. When the first page is taller than the screen, bringing the
// button into view already reaches the bottom and the page loads by itself; then there is no
// button left to tap. Both ways end with the "no more" line.
async function loadEverything(page: Page): Promise<void> {
  const more = page.getByTestId('catalog-more')
  await expect(async () => {
    if (await more.isVisible()) await more.click({ timeout: 1000 })
    await expect(page.getByTestId('catalog-list-more'))
      .toContainText('没有更多服务了', { timeout: 1000 })
  }).toPass({ timeout: 10_000 })
}

async function search(page: Page, keyword: string): Promise<void> {
  const input = page.getByTestId('catalog-search-input').locator('input')
  await input.fill(keyword)
  await input.press('Enter')
}

async function answerPositionPrompt(page: Page, allow: boolean): Promise<void> {
  const modal = page.locator('uni-modal')
  await expect(modal).toContainText('允许使用你的位置？')
  await expect(modal).toContainText('用于按距离排序附近的巡检服务')
  await modal.locator(allow ? '.uni-modal__btn_primary' : '.uni-modal__btn_default').click()
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

test.describe('M02 分类与分页', () => {
  test('CAT-201 分类由服务端下发，每页 5 条，点“加载更多”取下一页', async ({ page }) => {
    const requests = collectListRequests(page)
    await openCatalog(page)

    await expect(page.locator('.catalog-categories__chip')).toHaveText([
      '全部', '电力', '光伏', '油气', '热力', '园区', '设备', '定期',
    ])
    await expect(page.getByTestId('catalog-category-all')).toHaveAttribute('aria-pressed', 'true')
    await expect(cards(page)).toHaveCount(5)
    expect(requests.at(-1)?.searchParams.get('size')).toBe('5')
    expect(requests.at(-1)?.searchParams.get('page')).toBe('1')
    expect(requests.at(-1)?.searchParams.has('categoryCode')).toBe(false)

    await page.getByTestId('catalog-more').click()

    await expect(cards(page)).toHaveCount(8)
    await expect(page.getByTestId('catalog-list-more')).toContainText('没有更多服务了')
    expect(requests.at(-1)?.searchParams.get('page')).toBe('2')
    expect(new Set(await titles(page).allTextContents()).size).toBe(8)
  })

  test('CAT-202 屏幕装不下一页时，滚到底自动取下一页', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 560 })
    await openCatalog(page)
    await expect(cards(page)).toHaveCount(5)

    const scroller = page.locator('.inspection-catalog__scroll .uni-scroll-view').last()
    await scroller.hover()
    // Slow steps on purpose: uni-app drops a second trigger inside 200ms.
    for (let step = 0; step < 12 && await cards(page).count() < 8; step += 1) {
      await page.mouse.wheel(0, 240)
      await page.waitForTimeout(280)
    }

    await expect(cards(page)).toHaveCount(8)
  })

  test('CAT-203 切分类回到第一页；一级分类包含它的下级', async ({ page }) => {
    const requests = collectListRequests(page)
    await openCatalog(page)
    await loadEverything(page)

    await page.getByTestId('catalog-category-power').click()
    await expect(titles(page)).toHaveText([
      '电力输电线路无人机巡检',
      '变电站智能巡检',
      '配电网无人机巡检',
      '电缆通道巡检',
    ])
    expect(requests.at(-1)?.searchParams.get('categoryCode')).toBe('POWER')
    expect(requests.at(-1)?.searchParams.get('page')).toBe('1')

    await page.getByTestId('catalog-category-photovoltaic').click()
    await expect(titles(page)).toHaveText(['光伏电站巡检服务'])
  })

  test('CAT-204 没有服务的分类给空态，“清空条件”回到全部', async ({ page }) => {
    await openCatalog(page)
    await page.getByTestId('catalog-category-equipment').click()

    await expect(page.getByTestId('catalog-empty')).toContainText('没有找到相关服务')
    await expect(page.getByTestId('catalog-city-closed')).toHaveCount(0)

    await page.getByTestId('catalog-clear').click()
    await expect(page.getByTestId('catalog-category-all')).toHaveAttribute('aria-pressed', 'true')
    await expect(cards(page)).toHaveCount(5)
  })

  test('CAT-205 从 M01 点“油气”场景，目录直接选中油气', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')
    await page.getByTestId('home-channel-service').click()
    await page.getByTestId('portal-category-oil_gas').click()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expect(page.getByTestId('catalog-category-oil_gas')).toHaveAttribute('aria-pressed', 'true')
    await expect(titles(page)).toHaveText(['燃气管道巡检服务'])

    await page.getByTestId('catalog-back').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
  })

  test('CAT-206 下一页失败可以点重试，已加载的卡片不丢', async ({ page }) => {
    let failing = true
    await page.route(`**${SERVICES_PATH}?**page=2**`, (route: Route) =>
      failing ? route.abort('internetdisconnected') : route.continue())
    await openCatalog(page)

    // As in loadEverything: bringing the button into view can already ask for the next page, and
    // then the retry line replaces it before the tap lands. Either way the request fails once.
    const more = page.getByTestId('catalog-more')
    await expect(async () => {
      if (await more.isVisible()) await more.click({ timeout: 1000 })
      await expect(page.getByTestId('catalog-more-retry'))
        .toContainText('加载失败，点此重试', { timeout: 1000 })
    }).toPass({ timeout: 10_000 })
    await expect(cards(page)).toHaveCount(5)

    failing = false
    await page.getByTestId('catalog-more-retry').click()
    await expect(cards(page)).toHaveCount(8)
  })

  test('CAT-207 快速连点两个分类，只显示最后一次的结果', async ({ page }) => {
    await openCatalog(page)
    // Hold the 电力 answer back until after 光伏 has been answered.
    await page.route(`**${SERVICES_PATH}?**categoryCode=POWER**`, async (route: Route) => {
      await new Promise((done) => setTimeout(done, 900))
      await route.continue()
    })

    await page.getByTestId('catalog-category-power').click()
    await page.getByTestId('catalog-category-photovoltaic').click()

    await expect(titles(page)).toHaveText(['光伏电站巡检服务'])
    await page.waitForTimeout(1200)
    await expect(titles(page)).toHaveText(['光伏电站巡检服务'])
    await expect(page.getByTestId('catalog-category-photovoltaic'))
      .toHaveAttribute('aria-pressed', 'true')
  })
})

test.describe('M02 搜索与筛选', () => {
  test('CAT-211 搜索由服务端匹配名称、摘要和巡检项目，% 不当通配符', async ({ page }) => {
    const requests = collectListRequests(page)
    await openCatalog(page)

    await search(page, '  热成像  ')
    await expect(titles(page)).toHaveText(['热力管网巡检服务'])
    expect(requests.at(-1)?.searchParams.get('keyword')).toBe('热成像')

    await search(page, '管')
    await expect(titles(page)).toHaveText(['燃气管道巡检服务', '热力管网巡检服务'])

    await search(page, '%')
    await expect(page.getByTestId('catalog-empty')).toBeVisible()

    // On H5 the box reports a keystroke that follows another within 100ms late, and that late
    // report puts the text back if the box was cleared in between. With a fast local server the
    // two searches above fit inside that window; no finger is that fast, so wait it out.
    await page.waitForTimeout(150)
    await page.getByTestId('catalog-clear').click()
    await expect(cards(page)).toHaveCount(5)
    await expect(page.getByTestId('catalog-search-input').locator('input')).toHaveValue('')
  })

  test('CAT-212 搜索和分类同时生效', async ({ page }) => {
    await openCatalog(page)
    await search(page, '管')
    await page.getByTestId('catalog-category-thermal').click()

    await expect(titles(page)).toHaveText(['热力管网巡检服务'])
  })

  test('CAT-213 筛选项来自服务端；点“查看结果”才生效，“重置”全部取消', async ({ page }) => {
    const requests = collectListRequests(page)
    await openCatalog(page)

    await page.getByTestId('catalog-filter-open').click()
    await expect(page.locator('.catalog-sheet__group').first().locator('.catalog-sheet__option'))
      .toHaveText(['不限', '无人机', '智能设备'])
    await page.getByTestId('catalog-filter-method-robot').click()
    const before = requests.length
    await page.getByTestId('catalog-filter-close').click()
    await expect(cards(page)).toHaveCount(5)
    expect(requests.length).toBe(before)

    await page.getByTestId('catalog-filter-open').click()
    await page.getByTestId('catalog-filter-method-robot').click()
    await page.getByTestId('catalog-filter-apply').click()
    await expect(titles(page)).toHaveText([
      '变电站智能巡检',
      '电缆通道巡检',
      '热力管网巡检服务',
      '园区综合巡检服务',
    ])
    expect(requests.at(-1)?.searchParams.getAll('serviceMethods')).toEqual(['ROBOT'])

    await page.getByTestId('catalog-filter-open').click()
    await page.getByTestId('catalog-filter-price-project_quote').click()
    await page.getByTestId('catalog-filter-apply').click()
    await expect(titles(page)).toHaveText(['园区综合巡检服务'])
    await expect(page.locator('.inspection-card__price-value')).toHaveText('按项目报价')

    await page.getByTestId('catalog-filter-open').click()
    await page.getByTestId('catalog-filter-reset').click()
    await page.getByTestId('catalog-filter-close').click()
    await expect(cards(page)).toHaveCount(5)
    expect(requests.at(-1)?.searchParams.has('serviceMethods')).toBe(false)
    expect(requests.at(-1)?.searchParams.has('priceModes')).toBe(false)
  })

  test('CAT-214 “明确价格”排除按项目报价，“包含巡检报告”只留有报告的', async ({ page }) => {
    const requests = collectListRequests(page)
    await openCatalog(page)

    await page.getByTestId('catalog-filter-open').click()
    await page.getByTestId('catalog-filter-price-fixed').click()
    await page.getByTestId('catalog-filter-report').click()
    await page.getByTestId('catalog-filter-apply').click()

    await expect(titles(page)).toHaveText([
      '电力输电线路无人机巡检',
      '光伏电站巡检服务',
      '燃气管道巡检服务',
      '热力管网巡检服务',
    ])
    expect(requests.at(-1)?.searchParams.getAll('priceModes'))
      .toEqual(['STARTING_PRICE', 'PER_DISTANCE', 'PER_VISIT'])
    expect(requests.at(-1)?.searchParams.get('includesReport')).toBe('true')
  })
})

test.describe('M02 排序与距离', () => {
  test('CAT-221 价格升序、降序由服务端排，按项目报价始终在最后', async ({ page }) => {
    await openCatalog(page)

    await page.getByTestId('catalog-sort-price').click()
    await expect(page.getByTestId('catalog-sort-price')).toHaveAttribute('data-sort', 'PRICE_ASC')
    await expect(titles(page).first()).toHaveText('配电网无人机巡检')
    await loadEverything(page)
    await expect(titles(page).last()).toHaveText('园区综合巡检服务')

    await page.getByTestId('catalog-sort-price').click()
    await expect(page.getByTestId('catalog-sort-price')).toHaveAttribute('data-sort', 'PRICE_DESC')
    await expect(titles(page).first()).toHaveText('热力管网巡检服务')
    await loadEverything(page)
    await expect(titles(page).last()).toHaveText('园区综合巡检服务')
  })

  test('CAT-222 允许定位后按距离排，卡片多一行距离；没有中心点的排最后', async ({ browser }) => {
    const context = await browser.newContext({
      geolocation: NEAR_PARK,
      permissions: ['geolocation'],
      viewport: { width: 375, height: 844 },
    })
    const page = await context.newPage()
    const requests = collectListRequests(page)
    await openCatalog(page)
    await expect(page.locator('.inspection-card__distance')).toHaveCount(0)

    await page.getByTestId('catalog-sort-distance').click()
    await answerPositionPrompt(page, true)

    await expect(page.getByTestId('catalog-sort-distance')).toHaveAttribute('aria-pressed', 'true')
    await expect(titles(page).first()).toHaveText('园区综合巡检服务')
    await expect(cards(page).first().locator('.inspection-card__distance')).toHaveText('距你 10 米')
    await expect(cards(page).nth(1).locator('.inspection-card__distance')).toContainText('公里')
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('DISTANCE_ASC')
    expect(requests.at(-1)?.searchParams.get('latitude')).toBe('37.45')
    expect(requests.at(-1)?.searchParams.get('longitude')).toBe('118.66')

    await loadEverything(page)
    await expect(titles(page).last()).toHaveText('电缆通道巡检')
    await expect(cards(page).last().locator('.inspection-card__distance')).toHaveCount(0)

    // Another sort drops the distance line and the position from the request.
    await page.getByTestId('catalog-sort-comprehensive').click()
    await expect(titles(page).first()).toHaveText('电力输电线路无人机巡检')
    await expect(page.locator('.inspection-card__distance')).toHaveCount(0)
    expect(requests.at(-1)?.searchParams.has('latitude')).toBe(false)

    // Allowed once: no second question.
    await page.getByTestId('catalog-sort-distance').click()
    await expect(titles(page).first()).toHaveText('园区综合巡检服务')
    await expect(page.locator('uni-modal')).toBeHidden()

    await page.setViewportSize({ width: 375, height: 844 })
    mkdirSync(visualOutput, { recursive: true })
    await page.screenshot({ path: resolve(visualOutput, 'catalog-distance-375.png') })
    await context.close()
  })

  test('CAT-223 不允许定位就回到综合排序并说明原因，再点会再问', async ({ page }) => {
    const requests = collectListRequests(page)
    await openCatalog(page)
    await page.getByTestId('catalog-sort-price').click()
    await expect(titles(page).first()).toHaveText('配电网无人机巡检')

    await page.getByTestId('catalog-sort-distance').click()
    await answerPositionPrompt(page, false)

    await expect(page.getByTestId('catalog-distance-note')).toHaveText('未开启定位，已按综合排序')
    await expect(page.getByTestId('catalog-sort-comprehensive'))
      .toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('catalog-sort-distance')).toHaveAttribute('aria-pressed', 'false')
    await expect(titles(page).first()).toHaveText('电力输电线路无人机巡检')
    expect(requests.every((url) => !url.searchParams.has('latitude'))).toBe(true)

    await page.getByTestId('catalog-sort-distance').click()
    await answerPositionPrompt(page, false)
    await expect(page.getByTestId('catalog-distance-note')).toBeVisible()

    // Picking another sort takes the note away: it no longer describes the list.
    await page.getByTestId('catalog-sort-price').click()
    await expect(page.getByTestId('catalog-distance-note')).toHaveCount(0)
  })

  test('CAT-224 页面里点了允许但系统拒绝定位，同样回到综合排序', async ({ browser }) => {
    const context = await browser.newContext({ permissions: [] })
    const page = await context.newPage()
    const requests = collectListRequests(page)
    await openCatalog(page)

    await page.getByTestId('catalog-sort-distance').click()
    await answerPositionPrompt(page, true)

    await expect(page.getByTestId('catalog-distance-note')).toHaveText('未开启定位，已按综合排序')
    await expect(page.getByTestId('catalog-sort-comprehensive'))
      .toHaveAttribute('aria-pressed', 'true')
    await expect(cards(page)).toHaveCount(5)
    expect(requests.every((url) => url.searchParams.get('sort') !== 'DISTANCE_ASC')).toBe(true)
    await context.close()
  })

  test('CAT-225 链接里带的距离排序不生效，也不会一进页面就问定位', async ({ page }) => {
    const requests = collectListRequests(page)
    await openCatalog(page, '&sort=DISTANCE_ASC')

    await expect(cards(page)).toHaveCount(5)
    await expect(page.locator('uni-modal')).toBeHidden()
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('COMPREHENSIVE')
  })
})

test.describe('M02 城市', () => {
  test('CAT-231 城市没有开通巡检时说明原因，并能切换城市', async ({ page }) => {
    await openCatalog(page)
    await page.getByTestId('catalog-city-select').click()
    await expect(page.locator('.city-sheet')).toBeVisible()
    await page.locator('.city-sheet__city', { hasText: '潍坊市' }).first().click()

    const closed = page.getByTestId('catalog-city-closed')
    await expect(closed).toContainText('潍坊市暂未开通巡检服务')
    await expect(page.getByTestId('catalog-empty')).toHaveCount(0)
    await expect(page.getByText('巡检服务加载失败')).toHaveCount(0)

    await closed.locator('.catalog-state__action').click()
    await expect(page.locator('.city-sheet')).toBeVisible()
    await page.locator('.city-sheet__city', { hasText: '济南市' }).first().click()

    await expect(cards(page)).toHaveCount(5)
    await expect(page.getByTestId('catalog-city-select')).toContainText('济南市')
  })

  test('CAT-232 切换城市保留分类和搜索词，回到第一页', async ({ page }) => {
    const requests = collectListRequests(page)
    await openCatalog(page)
    await page.getByTestId('catalog-category-power').click()
    await expect(cards(page)).toHaveCount(4)

    await page.getByTestId('catalog-city-select').click()
    await page.locator('.city-sheet__city', { hasText: '济南市' }).first().click()

    await expect(page.getByTestId('catalog-city-select')).toContainText('济南市')
    await expect(cards(page)).toHaveCount(4)
    expect(requests.at(-1)?.searchParams.get('cityCode')).toBe('370100')
    expect(requests.at(-1)?.searchParams.get('categoryCode')).toBe('POWER')
    expect(requests.at(-1)?.searchParams.get('page')).toBe('1')
  })
})

test('VIS-CAT 目录在 320/375/430 无横向溢出并留存截图', async ({ page }) => {
  mkdirSync(visualOutput, { recursive: true })

  for (const width of [320, 375, 430]) {
    await page.setViewportSize({ width, height: 844 })
    // A different query each time: the same URL would keep the page, and with it the note
    // and scroll position left by the previous width.
    await openCatalog(page, `&source=VIS${width}`)
    await expect(cards(page)).toHaveCount(5)
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `catalog-${width}.png`) })

    await page.getByTestId('catalog-filter-open').click()
    await expect(page.locator('.catalog-sheet__panel')).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `catalog-filter-${width}.png`) })
    await page.getByTestId('catalog-filter-close').click()

    await page.getByTestId('catalog-sort-distance').click()
    await answerPositionPrompt(page, false)
    await expect(page.getByTestId('catalog-distance-note')).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `catalog-distance-denied-${width}.png`) })
  }
})
