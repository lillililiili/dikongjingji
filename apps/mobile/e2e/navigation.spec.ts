import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page, type Response } from '@playwright/test'

const visualOutput = resolve(
  __dirname,
  '../../../.planning/nav-plan-c/visual',
)
const SERVICES_PATH = '/api/v1/inspection/services'

function servicesResponse(
  params: Record<string, string>,
): (response: Response) => boolean {
  return (response) => {
    const url = new URL(response.url())

    return (
      url.pathname === SERVICES_PATH &&
      Object.entries(params).every(
        ([key, value]) => url.searchParams.get(key) === value,
      )
    )
  }
}

async function openHome(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-page')).toBeVisible()
  await expect(page.getByTestId('home-city')).toContainText('东营')
}

async function enterServiceChannel(page: Page): Promise<void> {
  await openHome(page)
  await page.getByTestId('home-channel-service').click()
  await expect(page.getByTestId('inspection-portal')).toBeVisible()
  await expect(page.getByTestId('portal-category-power')).toBeVisible()
}

async function expectRootBar(page: Page, activeTab: string): Promise<void> {
  const tab = page.getByTestId(`root-tab-${activeTab}`)
  await expect(tab).toBeVisible()
  await expect(tab).toHaveClass(/root-tab-bar__item--active/)
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(
    dimensions.clientWidth + 1,
  )
}

test.describe('底栏', () => {
  test('NAV-001 五个 Tab 顺序固定，切换后选中项跟随', async ({ page }) => {
    await openHome(page)

    await expect(
      page.locator('.root-tab-bar .root-tab-bar__label'),
    ).toHaveText(['首页', '发现', '消息', '购物车', '我的'])
    await expectRootBar(page, 'home')

    await page.getByTestId('root-tab-message').click()
    await expect(page.getByTestId('message-page')).toBeVisible()
    await expectRootBar(page, 'message')

    await page.getByTestId('root-tab-cart').click()
    await expect(page.getByTestId('cart-page')).toBeVisible()
    await expectRootBar(page, 'cart')

    await page.getByTestId('root-tab-discover').click()
    await expect(page.getByTestId('discover-page')).toBeVisible()
    await expectRootBar(page, 'discover')

    await page.getByTestId('root-tab-mine').click()
    await expect(page.getByTestId('mine-page')).toBeVisible()
    await expectRootBar(page, 'mine')

    await page.getByTestId('root-tab-home').click()
    await expectRootBar(page, 'home')
  })

  test('NAV-002 高 60px、五格等宽、选中色正确，原生栏不显示', async ({ page }) => {
    await openHome(page)

    const bar = await page.locator('.root-tab-bar').boundingBox()
    expect(bar?.height).toBe(60)

    const widths = await page
      .locator('.root-tab-bar__item')
      .evaluateAll((items) =>
        items.map((item) => item.getBoundingClientRect().width),
      )
    expect(widths).toHaveLength(5)
    expect(Math.max(...widths) - Math.min(...widths)).toBeLessThan(1)
    expect(Math.min(...widths)).toBeGreaterThanOrEqual(44)

    const itemHeight = await page
      .getByTestId('root-tab-home')
      .evaluate((item) => item.getBoundingClientRect().height)
    expect(itemHeight).toBeGreaterThanOrEqual(44)

    await expect(page.getByTestId('root-tab-home')).toHaveCSS(
      'color',
      'rgb(255, 106, 0)',
    )
    await expect(page.getByTestId('root-tab-mine')).toHaveCSS(
      'color',
      'rgb(138, 143, 151)',
    )
    await expect(page.locator('uni-tabbar .uni-tabbar')).toBeHidden()
  })

  test('NAV-003 在首页再点“首页”滚回顶部', async ({ page }) => {
    await enterServiceChannel(page)
    await page.evaluate(() => window.scrollTo(0, 200))
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(200)

    await page.getByTestId('root-tab-home').click()

    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
  })

  test('NAV-004 二级页不显示底栏', async ({ page }) => {
    await enterServiceChannel(page)
    await page.getByTestId('portal-view-all').click()
    await expect(page.getByTestId('inspection-catalog')).toBeVisible()

    await expect(page.locator('.root-tab-bar:visible')).toHaveCount(0)
    await expect(page.locator('uni-tabbar .uni-tabbar')).toBeHidden()

    await page
      .locator('[data-testid^="catalog-service-"]')
      .first()
      .click()
    await expect(page).toHaveURL(/pages\/service\/inspection\/detail/)
    await expect(page.locator('.root-tab-bar:visible')).toHaveCount(0)
  })
})

test.describe('首页频道', () => {
  test('NAV-005 九个频道按序显示，冷启动停在推荐', async ({ page }) => {
    await openHome(page)

    await expect(page.locator('.home-header__channel')).toHaveText([
      '推荐',
      '专业服务',
      '活动',
      '体验',
      '无人机',
      '机器人',
      '配件',
      '租赁',
      '课程',
    ])
    await expect(page.getByTestId('home-channel-recommend')).toHaveClass(
      /home-header__channel--active/,
    )
  })

  test('NAV-006 切频道换占位词和内容，底栏停在首页', async ({ page }) => {
    await openHome(page)
    const input = page.getByTestId('home-search-input').locator('input')
    const placeholder = page.getByTestId('home-search-input')
    await expect(placeholder).toContainText('搜索设备、活动或服务')

    await page.getByTestId('home-channel-service').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect(placeholder).toContainText('搜索巡检服务、场景或关键词')
    await expectRootBar(page, 'home')

    await page.getByTestId('home-channel-activity').click()
    await expect(page.getByTestId('home-channel-placeholder')).toContainText('活动')
    await expect(page.getByTestId('inspection-portal')).toBeHidden()
    await expect(placeholder).toContainText('搜索赛事、培训、研学')
    await expectRootBar(page, 'home')

    await input.fill('测试')
    await page.getByTestId('home-channel-robot').click()
    await expect(input).toHaveValue('')
    await expect(placeholder).toContainText('搜索机器人型号或品牌')
  })

  test('NAV-007 选中靠前的频道时频道行不跳动', async ({ page }) => {
    await openHome(page)

    await page.getByTestId('home-channel-service').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()

    await expect(page.getByTestId('home-channel-recommend')).toBeInViewport()
  })

  test('NAV-008 切到其他 Tab 再回来，频道保持不变', async ({ page }) => {
    await enterServiceChannel(page)

    await page.getByTestId('root-tab-mine').click()
    await expect(page.getByTestId('mine-page')).toBeVisible()
    await page.getByTestId('root-tab-home').click()

    await expect(page.getByTestId('home-channel-service')).toHaveClass(
      /home-header__channel--active/,
    )
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
  })

  test('NAV-009 未闭环入口给出明确提示且不跳转', async ({ page }) => {
    await openHome(page)
    await page.getByTestId('home-channel-activity').click()

    const url = page.url()
    await page.getByTestId('home-search-submit').click()
    await expect(page.locator('.uni-sample-toast')).toContainText('下一阶段接入')
    expect(page.url()).toBe(url)

    await page.getByTestId('home-scan').click()
    await expect(page.locator('.uni-sample-toast')).toContainText('下一阶段接入')
    expect(page.url()).toBe(url)
  })

  test('NAV-010 从首页切换城市后两个频道都按新城市加载', async ({ page }) => {
    await enterServiceChannel(page)
    await page.getByTestId('home-city').click()
    await expect(page.locator('.city-sheet')).toBeVisible()

    const reloaded = page.waitForResponse(
      servicesResponse({ cityCode: '370100' }),
    )
    await page.locator('.city-sheet__city', { hasText: '济南市' }).first().click()
    await reloaded

    await expect(page.locator('.city-sheet')).toHaveCount(0)
    await expect(page.getByTestId('home-city')).toContainText('济南')

    await page.getByTestId('root-tab-mine').click()
    await page.getByTestId('root-tab-home').click()
    await expect(page.getByTestId('home-city')).toContainText('济南')
  })
})

test.describe('巡检入口', () => {
  test('GOLD-001 首页频道“专业服务” → 巡检 → 场景 → 目录 → 返回', async ({
    page,
  }) => {
    await openHome(page)

    const portalLoaded = page.waitForResponse(
      servicesResponse({ cityCode: '370500' }),
    )
    await page.getByTestId('home-channel-service').click()
    expect((await portalLoaded).status()).toBe(200)

    await expect(page.getByTestId('service-type-inspection')).toHaveClass(
      /service-channel__type--active/,
    )
    await expect(page.getByTestId('inspection-portal')).toBeVisible()

    const catalogLoaded = page.waitForResponse(
      servicesResponse({ cityCode: '370500', categoryCode: 'POWER' }),
    )
    await page.getByTestId('portal-category-power').click()
    expect((await catalogLoaded).status()).toBe(200)

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expect(
      page.locator('[data-testid^="catalog-service-"]').first(),
    ).toBeVisible()

    await page.getByTestId('catalog-back').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect(page.getByTestId('home-channel-service')).toHaveClass(
      /home-header__channel--active/,
    )
    await expectRootBar(page, 'home')
  })

  test('GOLD-002 推荐频道金刚位“专业服务” → 巡检 → 搜索 → 目录', async ({
    page,
  }) => {
    await openHome(page)
    await page.getByTestId('home-king-service').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect(page.getByTestId('home-channel-service')).toHaveClass(
      /home-header__channel--active/,
    )

    const input = page.getByTestId('home-search-input').locator('input')
    await input.fill(' 电力 ')
    await input.press('Enter')

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expect(page).toHaveURL(/cityCode=370500/)
    await expect(
      page.getByTestId('catalog-search-input').locator('input'),
    ).toHaveValue('电力')
  })

  test('GOLD-003 推荐频道大卡“找专业服务”直达频道', async ({ page }) => {
    await openHome(page)
    await page.getByTestId('home-card-service').click()

    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect(page.getByTestId('home-channel-service')).toHaveClass(
      /home-header__channel--active/,
    )
  })

  test('NAV-011 从目录返回后保留频道内滚动位置', async ({ page }) => {
    await enterServiceChannel(page)
    await expect(page.getByTestId('portal-view-all')).toBeVisible()
    await page.evaluate(() => window.scrollTo(0, 300))
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(300)

    await page.getByTestId('portal-view-all').click()
    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await page.getByTestId('catalog-back').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()

    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(300)
  })

  test('NAV-012 切频道不串滚动位置', async ({ page }) => {
    await enterServiceChannel(page)
    await page.evaluate(() => window.scrollTo(0, 300))
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(300)

    await page.getByTestId('home-channel-activity').click()
    await expect(page.getByTestId('home-channel-placeholder')).toBeVisible()
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)

    await page.getByTestId('home-channel-service').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(300)
  })

  test('NAV-013 频道来回切换不重复请求 M01 数据', async ({ page }) => {
    await enterServiceChannel(page)
    let reloads = 0
    page.on('request', (request) => {
      if (new URL(request.url()).pathname === SERVICES_PATH) reloads += 1
    })

    await page.getByTestId('home-channel-activity').click()
    await page.getByTestId('home-channel-service').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await page.waitForTimeout(300)

    expect(reloads).toBe(0)
  })

  test('NAV-014 M01 首次加载失败后可重试', async ({ page }) => {
    await openHome(page)
    let offline = true
    await page.route(`**${SERVICES_PATH}?**`, async (route) => {
      if (offline) {
        await route.abort('internetdisconnected')
        return
      }
      await route.continue()
    })

    await page.getByTestId('home-channel-service').click()
    await expect(page.getByTestId('portal-retry')).toBeVisible()

    offline = false
    await page.getByTestId('portal-retry').click()
    await expect(page.getByTestId('portal-category-power')).toBeVisible()
  })
})

test.describe('推荐频道', () => {
  test('NAV-018 Banner 按钮不带走未提交的搜索词', async ({ page }) => {
    await openHome(page)
    await page.getByTestId('home-search-input').locator('input').fill('未提交')

    await page.locator('.platform-hero-banner__action').click()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expect(
      page.getByTestId('catalog-search-input').locator('input'),
    ).toHaveValue('')
  })

  test('NAV-020 金刚区在隐藏状态挂载后切回推荐仍可翻页和点击', async ({ page }) => {
    await page.goto('/#/pages/service/index')
    await expect(page.getByTestId('inspection-portal')).toBeVisible()

    await page.getByTestId('home-channel-recommend').click()
    await expect(page.getByTestId('home-king-service')).toBeInViewport()
    await expect(page.locator('.home-entries__dot')).toHaveCount(2)

    await page.getByTestId('home-king-service').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
  })

  test('NAV-019 内容加载失败时入口仍然可用', async ({ page }) => {
    await page.route(`**${SERVICES_PATH}?**`, (route) =>
      route.abort('internetdisconnected'),
    )
    await page.goto('/#/pages/home/index')

    await expect(page.getByText('首页内容加载失败，请稍后重试')).toBeVisible()
    await expect(page.getByTestId('home-king-service')).toBeVisible()
    await expect(page.getByTestId('home-card-service')).toBeVisible()
  })
})

test.describe('旧路由', () => {
  for (const route of [
    '/pages/service/index',
    '/pages/service/inspection/index',
  ]) {
    test(`NAV-015 ${route} 重定向到首页“专业服务”频道`, async ({ page }) => {
      await page.goto(`/#${route}`)

      await expect(page.getByTestId('home-page')).toBeVisible()
      await expect(page.getByTestId('home-channel-service')).toHaveClass(
        /home-header__channel--active/,
      )
      await expect(page.getByTestId('inspection-portal')).toBeVisible()
      await expectRootBar(page, 'home')
    })
  }

  test('NAV-016 深链进入目录后返回落到首页频道', async ({ page }) => {
    await page.goto(
      '/#/pages/service/inspection/catalog?cityCode=370500&categoryCode=POWER',
    )
    await expect(page.getByTestId('inspection-catalog')).toBeVisible()

    await page.getByTestId('catalog-back').click()

    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect(page.getByTestId('home-channel-service')).toHaveClass(
      /home-header__channel--active/,
    )
  })

  test('NAV-021 定位未完成时先等城市，不把 M01 和目录带进错误态', async ({ page }) => {
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'geolocation', {
        value: {
          getCurrentPosition: (
            _success: unknown,
            failure: (error: { code: number; message: string }) => void,
          ) => {
            setTimeout(() => failure({ code: 1, message: 'denied' }), 2000)
          },
        },
      })
    })
    await page.goto('/#/pages/service/index')

    await expect(page.getByTestId('home-city-pending')).toBeVisible()
    await expect(page.getByTestId('portal-retry')).toHaveCount(0)
    await page.getByTestId('home-search-submit').click()
    await expect(page.locator('.city-sheet')).toBeVisible()
    await page.locator('.city-sheet__close').click()

    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect(page.getByTestId('portal-category-power')).toBeVisible()
    await expect(page.getByTestId('home-city')).toContainText('东营')
  })
})

test.describe('专业服务子类型', () => {
  test('NAV-022 非巡检类型接管搜索框，离开频道后回到巡检', async ({ page }) => {
    await enterServiceChannel(page)
    const placeholder = page.getByTestId('home-search-input')

    await page.getByTestId('service-type-aerial').click()
    await expect(placeholder).toContainText('搜索航拍服务或场景')
    const url = page.url()
    await page.getByTestId('home-search-submit').click()
    await expect(page.locator('.uni-sample-toast')).toContainText('下一阶段接入')
    expect(page.url()).toBe(url)

    await page.getByTestId('service-type-survey').click()
    await expect(page.getByTestId('service-channel-notice')).toBeVisible()
    await expect(placeholder).toContainText('搜索测绘服务')

    await page.getByTestId('home-channel-recommend').click()
    await expect(placeholder).toContainText('搜索设备、活动或服务')

    await page.getByTestId('home-card-service').click()
    await expect(page.getByTestId('service-type-inspection')).toHaveClass(
      /service-channel__type--active/,
    )
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect(placeholder).toContainText('搜索巡检服务、场景或关键词')
  })
})

test.describe('M02 回归', () => {
  test('REG-001 目录的筛选与城市面板不受底栏移除影响', async ({ page }) => {
    await enterServiceChannel(page)
    await page.getByTestId('portal-category-power').click()
    await expect(page.getByTestId('inspection-catalog')).toBeVisible()

    await page.getByTestId('catalog-filter-open').click()
    await expect(page.getByTestId('catalog-filter-close')).toBeVisible()
    await page.getByTestId('catalog-filter-close').click()

    await page.getByTestId('catalog-city-select').click()
    await expect(page.locator('.city-sheet')).toBeVisible()
  })

  test('REG-002 目录最后一张卡片不被遮挡', async ({ page }) => {
    await enterServiceChannel(page)
    await page.getByTestId('portal-category-power').click()
    const lastCard = page.locator('[data-testid^="catalog-service-"]').last()
    await expect(lastCard).toBeVisible()
    await lastCard.scrollIntoViewIfNeeded()

    await expect(lastCard).toBeInViewport({ ratio: 1 })
  })
})

test('VIS-001 320/375/430 视口无横向溢出并留存截图', async ({ page }) => {
  mkdirSync(visualOutput, { recursive: true })

  for (const width of [320, 375, 430]) {
    await page.setViewportSize({ width, height: 844 })
    await openHome(page)
    await page.getByTestId('home-channel-recommend').click()
    await expect(page.getByTestId('home-king-service')).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({
      path: resolve(visualOutput, `home-recommend-${width}.png`),
    })

    await page.getByTestId('home-channel-service').click()
    await expect(page.getByTestId('portal-category-power')).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({
      path: resolve(visualOutput, `home-service-${width}.png`),
    })

    await page.getByTestId('portal-category-power').click()
    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expect(
      page.locator('[data-testid^="catalog-service-"]').first(),
    ).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({
      path: resolve(visualOutput, `catalog-${width}.png`),
    })
  }
})
