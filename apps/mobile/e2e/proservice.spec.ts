import { mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type APIRequestContext, type Page, type Route } from '@playwright/test'

import { PLANNING, boardUrl } from './support/board-compare'
import {
  API,
  BUYER,
  apiLogin,
  databaseAvailable,
  psql,
  type Session,
} from './support/trade-fixtures'

// 四类专业服务的目录、详情和服务商主页（F2，计划 §23）：专业服务频道的航拍 / 测绘 / 设备维护 / 技术支持页签读真实目录，
// “查看全部服务”和搜索框进服务目录（类型、综合 / 价格排序、筛选、关键词、分页、城市未开通），服务详情（价格、各类型段落、
// 服务商行），服务商主页（服务范围、可服务城市），“发布需求”带上类型并预选“仅邀请这家”。真实后端；只有分页（开发库每类
// 只有 4 项）用桩数据。发布需求的用例前后把 dev-customer 的需求从数据库删掉。
const CITY = '370500'
const PROVIDER_ID = '22222222-2222-2222-2222-222222222222'
const AERIAL_PARK = 'f2100000-0000-4000-8000-000000000001'
const SURVEY_MODEL = 'f2100000-0000-4000-8000-000000000005'
const MAINT_GENERAL = 'f2100000-0000-4000-8000-000000000009'
const INSPECTION_SOLAR = '66666666-6666-6666-6666-666666666666'
const visualOutput = resolve(PLANNING, 'proservice/visual')
const compareOutput = resolve(PLANNING, 'style-compare/proservice')

const buyerId = () => `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

function cleanupDemands(): void {
  psql(`
    BEGIN;
    CREATE TEMP TABLE e2e_demands ON COMMIT DROP AS SELECT id FROM demand WHERE account_id = ${buyerId()};
    DELETE FROM demand_event WHERE demand_id IN (SELECT id FROM e2e_demands);
    DELETE FROM demand_quote WHERE demand_id IN (SELECT id FROM e2e_demands);
    DELETE FROM demand_invitation WHERE demand_id IN (SELECT id FROM e2e_demands);
    DELETE FROM demand_deliverable WHERE demand_id IN (SELECT id FROM e2e_demands);
    DELETE FROM demand WHERE id IN (SELECT id FROM e2e_demands);
    DELETE FROM notification_message WHERE recipient_id = ${buyerId()}
      AND (category = 'DEMAND_QUOTE' OR target_type = 'DEMAND');
    COMMIT;
  `)
}

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, session.token)
}

async function listDemands(request: APIRequestContext, session: Session) {
  const response = await request.get(`${API}/api/v1/demands?page=1&size=50`, { headers: session.headers })
  expect(response.ok()).toBe(true)
  return (await response.json()).items as Array<{
    demandId: string
    type: string
    status: string
    quoteScope: string
    invitedProviders: Array<{ providerId: string }>
  }>
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

async function tallShot(page: Page, path: string): Promise<void> {
  const viewport = page.viewportSize() as { width: number; height: number }
  await page.evaluate(() => window.scrollTo(0, 0))
  const height = await page.evaluate(() => document.documentElement.scrollHeight)
  await page.setViewportSize({ width: viewport.width, height: Math.max(viewport.height, height) })
  await page.waitForTimeout(250)
  await page.screenshot({ path })
  await page.setViewportSize(viewport)
}

const cards = (page: Page) => page.locator('[data-testid^="pro-service-card-"]')
const titles = (page: Page) => page.locator('.pro-card__title')

async function openServiceChannel(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await page.getByTestId('home-channel-service').click()
  await expect(page.getByTestId('inspection-portal')).toBeVisible()
}

async function openCatalog(page: Page, query: string): Promise<void> {
  await page.goto(`/?n=${Date.now()}#/pages/proservice/catalog?${query}`)
  await expect(page.getByTestId('pro-catalog')).toBeVisible()
}

async function searchCatalog(page: Page, keyword: string): Promise<void> {
  const input = page.getByTestId('pro-catalog-search').locator('input')
  await input.fill(keyword)
  await input.press('Enter')
}

test.describe('专业服务目录、详情和服务商主页', () => {
  test('PRO-001 频道页签读真实服务：前三项、查看全部服务进目录，搜索框带关键词进目录', async ({ page }) => {
    await openServiceChannel(page)
    await page.getByTestId('service-type-aerial').click()
    await expect(page.getByTestId('home-search-input')).toContainText('搜索航拍服务、场景或关键词')
    const entry = page.getByTestId('service-entry-list')
    await expect(entry.locator('[data-testid^="pro-service-card-"]')).toHaveCount(3)
    await expect(entry.locator('.pro-card__title')).toHaveText(['园区与工程航拍', '能源设施航拍', '文旅风光航拍'])
    await expect(page.getByTestId(`pro-service-card-${AERIAL_PARK}`)).toContainText('¥1,280')
    await expect(page.getByTestId(`pro-service-card-${AERIAL_PARK}`)).toContainText('示例')
    await expect(page.getByTestId('service-entry-aerial')).toBeVisible()

    await page.getByTestId('service-entry-all').click()
    await expect(page).toHaveURL(new RegExp(`proservice/catalog\\?type=AERIAL&cityCode=${CITY}`))
    await expect(cards(page)).toHaveCount(4)
    await expect(page.getByTestId('pro-catalog-no-more')).toHaveText('没有更多服务了')
    await expect(page.getByTestId('pro-catalog-city')).toContainText('东营')
    await page.getByTestId('pro-catalog-back').click()

    // Back on the channel, still on aerial: the home search box goes to the aerial catalog.
    await expect(page.getByTestId('service-type-aerial')).toHaveClass(/service-channel__type--active/)
    const input = page.getByTestId('home-search-input').locator('input')
    await input.fill('文旅')
    await input.press('Enter')
    await expect(page).toHaveURL(/proservice\/catalog\?type=AERIAL&cityCode=370500&keyword=/)
    await expect(page.getByTestId('pro-catalog-search').locator('input')).toHaveValue('文旅')
    await expect(titles(page)).toHaveText(['文旅风光航拍'])
    await page.getByTestId('pro-catalog-back').click()

    // Support is filed as TECH_SUPPORT; a card opens its service page.
    await page.getByTestId('service-type-support').click()
    await expect(page.getByTestId('service-entry-list').locator('.pro-card__title').first()).toHaveText('设备与作业技术支持')
    await page.getByTestId('service-entry-list').locator('[data-testid^="pro-service-card-"]').first().click()
    await expect(page.getByTestId('pro-detail')).toHaveAttribute('data-state', 'ready')
    await expect(page.locator('.pro-detail__section-title')).toContainText(['支持内容', '支持方式', '响应说明'])
  })

  test('PRO-002 目录：价格升降序、筛选（成果类型、报价方式）、关键词、清空条件、切换类型', async ({ page }) => {
    const requests: string[] = []
    page.on('request', (sent) => {
      if (sent.url().includes('/api/v1/pro-services?')) requests.push(sent.url())
    })
    await openCatalog(page, `type=AERIAL&cityCode=${CITY}`)
    await expect(titles(page)).toHaveText(['园区与工程航拍', '能源设施航拍', '文旅风光航拍', '活动现场航拍'])

    // 价格: low to high, then high to low; 按项目报价 stays last both ways.
    await page.getByTestId('pro-catalog-sort-price').click()
    await expect(page.getByTestId('pro-catalog')).toHaveAttribute('data-sort', 'PRICE_ASC')
    await expect(titles(page)).toHaveText(['活动现场航拍', '园区与工程航拍', '文旅风光航拍', '能源设施航拍'])
    await page.getByTestId('pro-catalog-sort-price').click()
    await expect(page.getByTestId('pro-catalog')).toHaveAttribute('data-sort', 'PRICE_DESC')
    await expect(titles(page)).toHaveText(['文旅风光航拍', '园区与工程航拍', '活动现场航拍', '能源设施航拍'])
    expect(requests.some((url) => url.includes('sort=PRICE_DESC'))).toBe(true)
    await page.getByTestId('pro-catalog-sort-default').click()
    await expect(titles(page).first()).toHaveText('园区与工程航拍')

    // 筛选: the group comes from the server (成果类型 for aerial); nothing applies before 查看结果.
    await page.getByTestId('pro-catalog-filter').click()
    await expect(page.getByTestId('pro-filter-sheet')).toContainText('成果类型')
    await page.getByTestId('pro-filter-facet-photos').click()
    await page.getByTestId('pro-filter-close').click()
    await expect(page.getByTestId('pro-catalog-filter-dot')).toHaveCount(0)
    await page.getByTestId('pro-catalog-filter').click()
    await expect(page.getByTestId('pro-filter-facet-photos')).toHaveAttribute('aria-pressed', 'false')
    await page.getByTestId('pro-filter-facet-photos').click()
    await page.getByTestId('pro-filter-apply').click()
    await expect(titles(page)).toHaveText(['园区与工程航拍', '能源设施航拍', '活动现场航拍'])
    await expect(page.getByTestId('pro-catalog-filter-dot')).toBeVisible()
    expect(requests.at(-1)).toContain('deliverables=PHOTOS')

    await page.getByTestId('pro-catalog-filter').click()
    await page.getByTestId('pro-filter-price-project').click()
    await page.getByTestId('pro-filter-apply').click()
    await expect(titles(page)).toHaveText(['能源设施航拍'])
    await expect(page.getByTestId(`pro-service-card-${'f2100000-0000-4000-8000-000000000002'}`)).toContainText('按项目报价')

    await page.getByTestId('pro-catalog-filter').click()
    await page.getByTestId('pro-filter-reset').click()
    await expect(cards(page)).toHaveCount(4)
    await expect(page.getByTestId('pro-catalog-filter-dot')).toHaveCount(0)

    // A keyword with no match: the empty state, and 清空条件 brings everything back.
    await searchCatalog(page, '没有这种服务')
    await expect(page.getByTestId('pro-catalog-empty')).toContainText('没有找到相关服务')
    await page.getByTestId('pro-catalog-clear-all').click()
    await expect(cards(page)).toHaveCount(4)
    await expect(page.getByTestId('pro-catalog-search').locator('input')).toHaveValue('')

    // Another type: its own services, placeholder and filter group; the old filters go with the type.
    await page.getByTestId('pro-catalog-filter').click()
    await page.getByTestId('pro-filter-facet-video_footage').click()
    await page.getByTestId('pro-filter-apply').click()
    await page.getByTestId('pro-catalog-type-maintenance').click()
    await expect(page.getByTestId('pro-catalog-type-maintenance')).toHaveAttribute('aria-pressed', 'true')
    await expect(titles(page)).toHaveText(['无人机与机器人维护', '无人机校准与固件升级', '编程机器人保养', '巡检机器狗检修'])
    await expect(page.getByTestId('pro-catalog-filter-dot')).toHaveCount(0)
    await expect(page.getByTestId('pro-catalog-search').locator('.uni-input-placeholder'))
      .toHaveText('搜索设备维护服务、场景或关键词')
    await page.getByTestId('pro-catalog-filter').click()
    await expect(page.getByTestId('pro-filter-sheet')).toContainText('服务方式')
    await page.getByTestId('pro-filter-facet-on_site').click()
    await page.getByTestId('pro-filter-apply').click()
    await expect(titles(page)).toHaveText(['无人机与机器人维护', '巡检机器狗检修'])

    // 发布需求 at the bottom takes the open type.
    await page.getByTestId('pro-catalog-publish').click()
    await expect(page).toHaveURL(/demand\/publish\?type=MAINTENANCE|login\?returnUrl=.*demand.*publish.*MAINTENANCE/)
  })

  test('PRO-003 分页：滚到底读下一页，最后显示没有更多（桩数据）', async ({ page }) => {
    const pages: string[] = []
    await page.route('**/api/v1/pro-services?**', async (route: Route) => {
      const url = new URL(route.request().url())
      const number = Number(url.searchParams.get('page'))
      pages.push(String(number))
      // The real first page gives the shape; the development data has only four services.
      url.searchParams.set('page', '1')
      const response = await route.fetch({ url: url.toString() })
      const body = await response.json()
      const template = body.items[0]
      const count = number === 1 ? 20 : 5
      const items = Array.from({ length: count }, (_, index) => ({
        ...template,
        serviceId: `f2900000-0000-4000-8000-${String((number - 1) * 20 + index + 1).padStart(12, '0')}`,
        title: `分页服务 ${(number - 1) * 20 + index + 1}`,
      }))
      await route.fulfill({ json: { ...body, page: number, total: 25, hasMore: number === 1, items } })
    })
    await openCatalog(page, `type=AERIAL&cityCode=${CITY}`)
    await expect(cards(page)).toHaveCount(20)
    await expect(page.getByTestId('pro-catalog-no-more')).toHaveCount(0)
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    await expect(cards(page)).toHaveCount(25)
    await expect(page.getByTestId('pro-catalog-no-more')).toBeVisible()
    expect(pages).toEqual(['1', '2'])
  })

  test('PRO-004 城市未开通：说明并可切换城市，换到东营后列出服务', async ({ page }) => {
    await openCatalog(page, 'type=AERIAL&cityCode=370700')
    await expect(page.getByTestId('pro-catalog-closed')).toContainText('潍坊还没有开通航拍服务')
    await expect(cards(page)).toHaveCount(0)
    await expect(page.getByTestId('pro-catalog-city')).toContainText('潍坊')
    await assertNoHorizontalOverflow(page)

    await page.getByTestId('pro-catalog-closed-city').click()
    await expect(page.locator('.city-sheet')).toBeVisible()
    await page.locator('.city-sheet__city', { hasText: '济南市' }).first().click()
    await expect(page.locator('.city-sheet')).toHaveCount(0)
    await expect(page.getByTestId('pro-catalog-city')).toContainText('济南')
    // Jinan has two of the four aerial services.
    await expect(cards(page)).toHaveCount(2)
    await expect(page.getByTestId('pro-catalog-closed')).toHaveCount(0)
  })

  test('PRO-005 详情不在本城或不存在、服务商不存在：说明原因，给出出路', async ({ page }) => {
    await page.goto(`/#/pages/proservice/detail?serviceId=${AERIAL_PARK}&cityCode=370700`)
    await expect(page.getByTestId('pro-detail-unavailable')).toContainText('当前城市暂无这项服务，或服务已下架')
    await page.getByTestId('pro-detail-recover').click()
    await expect(page.getByTestId('home-page')).toBeVisible()
    await expect(page.getByTestId('home-channel-service')).toHaveClass(/home-header__channel--active/)

    await page.goto(`/?n=2#/pages/proservice/detail?serviceId=00000000-0000-4000-8000-000000000000&cityCode=${CITY}`)
    await expect(page.getByTestId('pro-detail-recover')).toHaveText('看看其他服务')
    await expect(page.getByTestId('pro-detail-publish')).toHaveCount(0)

    await page.goto(`/?n=3#/pages/provider/detail?providerId=00000000-0000-4000-8000-000000000000&cityCode=${CITY}`)
    await expect(page.getByTestId('provider-unavailable')).toContainText('没有找到这家服务商')
    await expect(page.getByTestId('provider-publish')).toHaveCount(0)

    // A network failure offers a retry instead.
    await page.route('**/api/v1/pro-services/**', (route) => route.abort())
    await page.goto(`/?n=4#/pages/proservice/detail?serviceId=${AERIAL_PARK}&cityCode=${CITY}`)
    await expect(page.getByTestId('pro-detail-recover')).toHaveText('重新加载')
    await page.unroute('**/api/v1/pro-services/**')
    await page.getByTestId('pro-detail-recover').click()
    await expect(page.getByTestId('pro-detail')).toHaveAttribute('data-state', 'ready')
  })
})

test.describe('从详情和服务商主页发布需求', () => {
  let buyer: Session

  test.beforeAll(async ({ request }) => {
    test.skip(!databaseAvailable(), '开发数据库容器没有运行')
    const session = await apiLogin(request, BUYER)
    test.skip(!session, '本地后端没有开发账号（不是 dev 配置）')
    buyer = session as Session
  })

  test.beforeEach(() => cleanupDemands())
  test.afterAll(() => {
    if (databaseAvailable()) cleanupDemands()
  })

  test('PRO-101 详情 → 服务商主页 → 巡检详情的“查看服务商”回到主页；发布需求预选类型和“仅邀请这家”，提交成仅邀请', async ({ page, request }) => {
    await signIn(page, buyer)
    await page.goto(`/#/pages/proservice/detail?serviceId=${AERIAL_PARK}&cityCode=${CITY}`)
    await expect(page.getByTestId('pro-detail')).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('pro-detail-title')).toHaveText('园区与工程航拍')
    await expect(page.getByTestId('pro-detail-sample')).toHaveText('示例')
    await expect(page.getByTestId('pro-detail-price')).toHaveText(/¥1,280\s*\/次起/)
    await expect(page.locator('.pro-detail__section-title'))
      .toHaveText(['价格与取消', '拍摄内容', '成果格式', '服务流程', '服务保障'])
    await expect(page.getByTestId('pro-detail-provider')).toContainText('云翼科技')
    await expect(page.getByTestId('pro-detail-provider')).toContainText('可服务城市 济南、东营')
    // The board's 首付 / 尾款 line is not shown: paying for these services is not open.
    await expect(page.getByTestId('pro-detail-steps')).not.toContainText('首付')
    await expect(page.getByTestId('pro-detail-steps')).toContainText('提交需求不产生费用')
    // 客服（G1）：进这家服务商的会话，带一张这项服务的卡片待发送。会话接口用桩，不在开发库里留会话。
    const opened: unknown[] = []
    const fakeConversation = {
      conversationId: '6f1b2c3d-1111-4222-8333-444455556666',
      counterpart: { kind: 'PROVIDER', organizationId: '22222222-2222-2222-2222-222222222222', name: '云翼科技' },
      lastMessage: null,
      unreadCount: 0,
      createdAt: '2026-11-19T02:00:00Z',
    }
    await page.route('**/api/v1/conversations', (route) => {
      opened.push(route.request().postDataJSON())
      return route.fulfill({ status: 201, json: fakeConversation })
    })
    await page.route('**/api/v1/conversations/*/messages?*', (route) =>
      route.fulfill({ json: { conversation: fakeConversation, items: [], hasMore: false, nextBefore: null } }))
    await page.route('**/api/v1/conversations/*/read', (route) => route.fulfill({ json: fakeConversation }))
    await page.getByTestId('pro-detail-customer-service').click()
    await expect(page.getByTestId('support-chat')).toHaveAttribute('data-state', 'ready')
    expect(opened).toEqual([{ counterpartKind: 'PROVIDER', organizationId: '22222222-2222-2222-2222-222222222222' }])
    await expect(page.getByTestId('support-chat-title')).toHaveText('云翼科技')
    await expect(page.getByTestId('support-chat-pending-card')).toContainText('园区与工程航拍')
    await page.unrouteAll({ behavior: 'ignoreErrors' })
    await page.getByTestId('support-chat-back').click()
    await expect(page.getByTestId('pro-detail')).toHaveAttribute('data-state', 'ready')

    await page.getByTestId('pro-detail-open-provider').click()
    await expect(page.getByTestId('provider-page')).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('provider-name')).toHaveText('云翼科技')
    // R6-4: 云翼科技 has a qualification review time in the dev seed, so the tag reads 资质已审核.
    await expect(page.getByTestId('provider-certification')).toHaveText('资质已审核')
    await expect(page.getByTestId('provider-tab-scope')).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByTestId(`provider-service-${INSPECTION_SOLAR}`)).toContainText('光伏电站巡检服务')
    await expect(page.getByTestId(`provider-service-${AERIAL_PARK}`)).toContainText('园区与工程航拍')
    await page.getByTestId('provider-tab-cities').click()
    await expect(page.getByTestId('provider-cities').locator('.provider-page__city-name')).toHaveText(['济南', '东营'])
    await expect(page.getByTestId('provider-city-370500')).toContainText('当前城市')
    await page.getByTestId('provider-tab-scope').click()

    // A service of another type opens its own page with that type's sections.
    await page.getByTestId(`provider-service-${SURVEY_MODEL}`).click()
    await expect(page.getByTestId('pro-detail-title')).toHaveText('无人机航测与三维建模')
    await expect(page.locator('.pro-detail__section-title')).toContainText(['作业范围', '精度说明', '交付成果'])
    await page.getByTestId('pro-detail-back').click()

    // An inspection service opens M03, whose 查看服务商 leads back to this page.
    await page.getByTestId(`provider-service-${INSPECTION_SOLAR}`).click()
    await expect(page.getByTestId('inspection-detail')).toBeVisible()
    await page.getByTestId('detail-open-provider').click()
    await expect(page).toHaveURL(new RegExp(`provider/detail\\?providerId=${PROVIDER_ID}&cityCode=${CITY}`))
    await expect(page.getByTestId('provider-name')).toHaveText('云翼科技')
    // From the provider page: inspection (it has inspection services), only this provider invited.
    await page.getByTestId('provider-publish').click()
    await expect(page.getByTestId('demand-type-value')).toHaveText('巡检')
    await expect(page.getByTestId('demand-scope-invite')).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('demand-scope-invite-sub')).toHaveText('已选 1 家 · 云翼科技')

    // From the aerial service page: aerial, only this provider invited; submit it.
    await page.goto(`/?n=2#/pages/proservice/detail?serviceId=${AERIAL_PARK}&cityCode=${CITY}`)
    await page.getByTestId('pro-detail-publish').click()
    await expect(page).toHaveURL(new RegExp(`demand/publish\\?type=AERIAL&providerId=${PROVIDER_ID}`))
    await expect(page.getByTestId('demand-type-value')).toHaveText('航拍')
    await expect(page.getByTestId('demand-scope-invite')).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('demand-scope-invite-sub')).toHaveText('已选 1 家 · 云翼科技')
    await page.getByTestId('demand-address').locator('input').fill('东营经济技术开发区')
    await page.getByTestId('demand-scope').locator('input').fill('园区全景 3 个点位')
    await page.getByTestId('demand-submit').click()
    await expect(page.getByTestId('demand-list')).toBeVisible()
    const [demand] = await listDemands(request, buyer)
    expect(demand).toMatchObject({ type: 'AERIAL', status: 'OPEN', quoteScope: 'INVITE' })
    expect(demand.invitedProviders.map((provider) => provider.providerId)).toEqual([PROVIDER_ID])
  })

  test('PRO-102 预选的服务商不在本城可邀请名单里：说明原因，报价范围留在平台匹配', async ({ page }) => {
    await signIn(page, buyer)
    await page.goto(`/#/pages/demand/publish?type=AERIAL&providerId=00000000-0000-4000-8000-000000000000`)
    await expect(page.getByTestId('demand-notice')).toContainText('这家服务商暂不接东营的航拍需求，报价范围先按平台匹配')
    await expect(page.getByTestId('demand-scope-match')).toHaveAttribute('aria-checked', 'true')
  })
})

test.describe('专业服务页面视觉', () => {
  test('PRO-201 320 / 375 / 430 不溢出，390 宽与画板对照', async ({ page }) => {
    mkdirSync(visualOutput, { recursive: true })
    mkdirSync(compareOutput, { recursive: true })

    let loads = 0
    for (const width of [375, 320, 430, 390]) {
      await page.setViewportSize({ width, height: 844 })
      // A new document each time: a hash change alone can leave the previous page of the same route.
      const at = (hash: string) => `/?w=${width}&n=${++loads}${hash}`
      const shot = (name: string) => width === 390
        ? resolve(compareOutput, `${name}-app.png`)
        : resolve(visualOutput, `${name}-${width}.png`)

      await page.goto(at(`#/pages/proservice/catalog?type=AERIAL&cityCode=${CITY}`))
      await expect(cards(page)).toHaveCount(4)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('SvcCatalog') })

      await page.goto(at(`#/pages/proservice/detail?serviceId=${AERIAL_PARK}&cityCode=${CITY}`))
      await expect(page.getByTestId('pro-detail')).toHaveAttribute('data-state', 'ready')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('SvcAerial'))

      await page.goto(at(`#/pages/proservice/detail?serviceId=${MAINT_GENERAL}&cityCode=${CITY}`))
      await expect(page.getByTestId('pro-detail-section-badges')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('SvcMaint'))

      await page.goto(at(`#/pages/provider/detail?providerId=${PROVIDER_ID}&cityCode=${CITY}`))
      await expect(page.getByTestId('provider-page')).toHaveAttribute('data-state', 'ready')
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('ProviderPage') })
      if (width === 390) continue

      await page.goto(at(`#/pages/proservice/detail?serviceId=${SURVEY_MODEL}&cityCode=${CITY}`))
      await expect(page.getByTestId('pro-detail')).toHaveAttribute('data-state', 'ready')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('SvcSurvey'))

      await page.goto(at(`#/pages/proservice/catalog?type=TECH_SUPPORT&cityCode=370700`))
      await expect(page.getByTestId('pro-catalog-closed')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('catalog-closed') })

      await page.goto(at('#/pages/home/index'))
      await page.getByTestId('home-channel-service').click()
      await page.getByTestId('service-type-maintenance').click()
      await expect(page.getByTestId('service-entry-list').locator('[data-testid^="pro-service-card-"]')).toHaveCount(3)
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('channel-maintenance'))
    }

    // The boards at 390 next to the App at 390.
    for (const board of ['SvcCatalog', 'SvcAerial', 'SvcMaint', 'ProviderPage']) {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(boardUrl(board))
      const frame = page.locator('#root > div').first()
      await expect(frame).toBeVisible()
      const height = Math.ceil((await frame.boundingBox())?.height ?? 844) + 60
      await page.setViewportSize({ width: 390, height })
      await page.waitForTimeout(300)
      await frame.screenshot({ path: resolve(compareOutput, `${board}-board.png`) })

      const image = (name: string) => `data:image/png;base64,${readFileSync(resolve(compareOutput, name)).toString('base64')}`
      await page.setViewportSize({ width: 820, height: 900 })
      await page.setContent(`
        <body style="margin:0;background:#dfe5ee;font:13px sans-serif">
          <div style="display:flex;gap:20px;padding:10px;align-items:flex-start">
            <figure style="margin:0;width:390px"><figcaption>画板 ${board}</figcaption>
              <img style="width:390px;display:block" src="${image(`${board}-board.png`)}"></figure>
            <figure style="margin:0;width:390px"><figcaption>App 390 宽</figcaption>
              <img style="width:390px;display:block" src="${image(`${board}-app.png`)}"></figure>
          </div>
        </body>`)
      await page.waitForTimeout(200)
      await page.screenshot({ path: resolve(compareOutput, `${board}-compare.png`), fullPage: true })
    }
  })
})
