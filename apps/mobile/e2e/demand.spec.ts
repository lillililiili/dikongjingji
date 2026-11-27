import { randomUUID } from 'node:crypto'
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

// 发布需求、服务商报价、客户比选与选定后入口。多家报价的视觉比较使用固定夹具，
// 两家真实服务商之间的报价、转预约及履约由 merchant-quote-revision.spec.ts 覆盖。
// 每条用例前后把 dev-customer 的需求、报价、事件和“需求与报价”站内信从数据库删掉。
const PROVIDER = 'dev-provider'
const PROVIDER_ID = '22222222-2222-2222-2222-222222222222'
const CITY = '370500'
const visualOutput = resolve(PLANNING, 'demand/visual')
const compareOutput = resolve(PLANNING, 'style-compare/demand')

const buyerId = () => `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

function cleanup(): void {
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
  await page.waitForTimeout(200)
  await page.screenshot({ path })
  await page.setViewportSize(viewport)
}

// YYYY-MM-DD in Beijing, `days` from today.
function beijingDay(days: number): string {
  return new Date(Date.now() + 8 * 3_600_000 + days * 86_400_000).toISOString().slice(0, 10)
}

interface DemandSnapshot {
  demandId: string
  status: string
  type: string
  quoteScope: string
  address: string | null
  dueDate: string | null
  version: number
  invitedProviders: Array<{ providerId: string }>
  chosen: { quoteId: string } | null
}

const COMPLETE = {
  type: 'INSPECTION',
  cityCode: CITY,
  address: '东营经济技术开发区',
  scope: '约 1.2 平方公里',
  budgetMinMinor: 1_000_000,
  budgetMaxMinor: 2_000_000,
  deliverables: ['INSPECTION_REPORT', 'ORTHOPHOTO', 'HAZARD_LIST'],
}

async function createDemand(
  request: APIRequestContext,
  session: Session,
  body: Record<string, unknown>,
  submit: boolean,
): Promise<DemandSnapshot> {
  const response = await request.post(`${API}/api/v1/demands`, {
    headers: { ...session.headers, 'Idempotency-Key': randomUUID() },
    data: { ...body, submit },
  })
  expect(response.status(), await response.text()).toBe(201)
  return response.json()
}

async function readDemand(request: APIRequestContext, session: Session, demandId: string): Promise<DemandSnapshot> {
  const response = await request.get(`${API}/api/v1/demands/${demandId}`, { headers: session.headers })
  expect(response.ok()).toBe(true)
  return response.json()
}

async function listDemands(request: APIRequestContext, session: Session): Promise<DemandSnapshot[]> {
  const response = await request.get(`${API}/api/v1/demands?page=1&size=50`, { headers: session.headers })
  expect(response.ok()).toBe(true)
  return (await response.json()).items
}

async function providerQuotes(
  request: APIRequestContext,
  provider: Session,
  demandId: string,
  amountYuan: number,
  durationDays = 90,
): Promise<{ quoteId: string; version: number }> {
  const response = await request.put(`${API}/api/v1/provider/demands/${demandId}/quote`, {
    headers: provider.headers,
    data: {
      amountMinor: amountYuan * 100,
      method: '无人机 + 机器狗',
      schedule: '共 6 次 · 首次进场按约定',
      durationDays,
      deliverables: '巡检报告、隐患清单',
      validUntil: new Date(Date.now() + 7 * 86_400_000).toISOString(),
    },
  })
  expect(response.status(), await response.text()).toBe(200)
  const body = await response.json()
  return { quoteId: body.myQuote.quoteId, version: body.myQuote.version }
}

// Quotes in the demand's city, made up for the screens that need more than the one development provider.
function stubQuote(number: number, name: string, amountYuan: number, durationDays: number, version = 1) {
  return {
    quoteId: `9d000000-0000-4000-8000-00000000000${number}`,
    provider: { providerId: `9e000000-0000-4000-8000-00000000000${number}`, name, certificationLabel: '企业认证' },
    version,
    status: 'ACTIVE',
    amountMinor: amountYuan * 100,
    method: ['无人机 + 机器狗', '无人机', '机器狗 + 人工复核'][number - 1],
    schedule: `共 6 次 · 11/2${number} 首次进场`,
    durationDays,
    deliverables: ['巡检报告、隐患清单、影像存档', '巡检报告、影像存档', '报告、隐患闭环跟踪'][number - 1],
    validUntil: new Date(Date.now() + 5 * 86_400_000).toISOString(),
    submittedAt: new Date(Date.now() - (4 - number) * 3_600_000).toISOString(),
    revisedAt: new Date(Date.now() - (4 - number) * 3_600_000).toISOString(),
    choosable: true,
  }
}

const STUB_QUOTES = [
  stubQuote(1, '鹰眼智能巡检', 18_600, 90, 2),
  stubQuote(2, '黄河口飞服', 15_200, 92),
  stubQuote(3, '东营安巡科技', 21_000, 60),
]

async function stubThreeQuotes(page: Page, demandId: string, sorts: string[] = []): Promise<void> {
  await page.route(`**/api/v1/demands/${demandId}/quotes?**`, (route: Route) => {
    const sort = new URL(route.request().url()).searchParams.get('sort') ?? 'ALL'
    sorts.push(sort)
    const items = [...STUB_QUOTES]
    if (sort === 'PRICE') items.sort((left, right) => left.amountMinor - right.amountMinor)
    if (sort === 'DURATION') items.sort((left, right) => left.durationDays - right.durationDays)
    return route.fulfill({ json: { demandId, demandStatus: 'QUOTED', demandVersion: 3, sort, items } })
  })
}

const card = (page: Page, demandId: string) => page.getByTestId(`demand-card-${demandId}`)
const notice = (page: Page) => page.getByTestId('demand-notice')

test.describe('需求发布与报价比选', () => {
  let buyer: Session
  let provider: Session

  test.beforeAll(async ({ request }) => {
    test.skip(!databaseAvailable(), '开发数据库容器没有运行')
    const buyerSession = await apiLogin(request, BUYER)
    const providerSession = await apiLogin(request, PROVIDER)
    test.skip(!buyerSession || !providerSession, '本地后端没有开发账号（不是 dev 配置）')
    buyer = buyerSession as Session
    provider = providerSession as Session
  })

  test.beforeEach(() => cleanup())
  test.afterAll(() => {
    if (databaseAvailable()) cleanup()
  })

  test('DEM-001 入口：航拍“发布需求”、巡检“发布巡检需求”带上类型，“我的”里有需求与报价', async ({ page }) => {
    await signIn(page, buyer)
    await page.goto('/#/pages/home/index')
    await page.getByTestId('home-channel-service').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()

    await page.getByTestId('portal-request-action').click()
    await expect(page.getByTestId('demand-publish')).toBeVisible()
    await expect(page.getByTestId('demand-type-value')).toHaveText('巡检')
    await expect(page.getByTestId('demand-deliverable-inspection_report')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('demand-publisher')).toHaveText('以 东营示例物流公司 身份提交')
    await page.getByTestId('demand-back').click()

    await page.getByTestId('service-type-aerial').click()
    await page.getByTestId('service-entry-publish').click()
    await expect(page.getByTestId('demand-type-value')).toHaveText('航拍')
    await expect(page.getByTestId('demand-scope').locator('.uni-input-placeholder')).toHaveText('拍摄区域或点位')
    await expect(page.getByTestId('demand-deliverable-video_footage')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('demand-deliverable-panorama')).toHaveAttribute('aria-pressed', 'false')
    await page.getByTestId('demand-back').click()

    await page.getByTestId('root-tab-mine').click()
    await page.getByTestId('mine-link-demands').click()
    await expect(page.getByTestId('demand-list')).toBeVisible()
    await expect(page.getByTestId('demand-list-empty')).toContainText('没有符合条件的记录')
    await page.getByTestId('demand-list-empty-publish').click()
    await expect(page.getByTestId('demand-type-value')).toHaveText('巡检')
  })

  test('DEM-002 发布：必填项提示 → 存草稿 → 草稿里继续填写、换类型 → 提交后进需求与报价', async ({ page, request }) => {
    await signIn(page, buyer)
    await page.goto('/#/pages/demand/publish?type=INSPECTION')
    await expect(page.getByTestId('demand-city')).toContainText('东营')

    // Nothing filled: the first missing field is named and nothing is sent.
    const posts: string[] = []
    page.on('request', (sent) => {
      if (sent.method() !== 'GET' && sent.url().includes('/api/v1/demands')) posts.push(`${sent.method()} ${sent.url()}`)
    })
    await page.getByTestId('demand-submit').click()
    await expect(notice(page)).toContainText('请填写服务地点')
    expect(posts).toEqual([])

    await page.getByTestId('demand-address').locator('input').fill('东营经济技术开发区')
    await page.getByTestId('demand-budget').click()
    await page.getByTestId('demand-budget-option-1000000-2000000').click()
    await expect(page.getByTestId('demand-budget-value')).toHaveText('1 万–2 万元')
    await page.getByTestId('demand-save-draft').click()
    await expect(notice(page)).toContainText('已存为草稿')
    await expect.poll(async () => (await listDemands(request, buyer)).map((item) => item.status)).toEqual(['DRAFT'])

    await page.getByTestId('demand-notice-link').click()
    await expect(page.getByTestId('demand-list')).toBeVisible()
    await expect(page.getByTestId('demand-filter-draft')).toHaveAttribute('aria-pressed', 'true')
    const [draft] = await listDemands(request, buyer)
    await expect(card(page, draft.demandId)).toContainText('草稿')
    await expect(card(page, draft.demandId).getByTestId('demand-card-meta')).toHaveText('巡检 · 还差作业范围')

    await page.getByTestId(`demand-card-edit-${draft.demandId}`).click()
    await expect(page.getByTestId('demand-head').locator('.app-head__title')).toHaveText('继续填写需求')
    await expect(page.getByTestId('demand-address').locator('input')).toHaveValue('东营经济技术开发区')
    await expect(page.getByTestId('demand-budget-value')).toHaveText('1 万–2 万元')

    // Switching the type puts back that type's deliverables.
    await page.getByTestId('demand-type').click()
    await page.getByTestId('demand-type-option-survey').click()
    await expect(page.getByTestId('demand-type-value')).toHaveText('测绘')
    await expect(page.getByTestId('demand-deliverable-orthophoto')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('demand-deliverable-model_3d')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('demand-deliverable-point_cloud')).toHaveAttribute('aria-pressed', 'false')
    await page.getByTestId('demand-deliverable-point_cloud').click()
    await page.getByTestId('demand-scope').locator('input').fill('约 3 平方公里')
    await page.getByTestId('demand-due').click()
    await page.getByTestId(`demand-due-option-${beijingDay(14)}`).click()
    await page.getByTestId('demand-submit').click()

    await expect(page.getByTestId('demand-list')).toBeVisible()
    const submitted = await readDemand(request, buyer, draft.demandId)
    expect(submitted).toMatchObject({ status: 'OPEN', type: 'SURVEY', dueDate: beijingDay(14) })
    expect((submitted as unknown as { deliverables: Array<{ code: string }> }).deliverables.map((item) => item.code))
      .toEqual(['ORTHOPHOTO', 'MODEL_3D', 'POINT_CLOUD'])
    await expect(card(page, draft.demandId).getByTestId('demand-card-status')).toHaveText('待报价')
    await expect(card(page, draft.demandId).getByTestId('demand-card-line')).toContainText('平台匹配中 · 报价截止')
    // One demand: the draft went in, no second one was made.
    expect((await listDemands(request, buyer)).length).toBe(1)
  })

  test('DEM-003 服务端拒绝的必填项（422）标在对应字段上，改好后能提交', async ({ page, request }) => {
    // A draft from an earlier day whose expected date has passed.
    const draft = await createDemand(request, buyer, { ...COMPLETE, dueDate: beijingDay(-1) }, false)
    await signIn(page, buyer)
    await page.goto(`/#/pages/demand/publish?demandId=${draft.demandId}`)
    await expect(page.getByTestId('demand-due-value')).not.toHaveText('时间可协商')

    const submit = page.waitForResponse((response) => response.url().endsWith('/submit'))
    await page.getByTestId('demand-submit').click()
    expect((await submit).status()).toBe(422)
    await expect(notice(page)).toContainText('期望完成日期已过，请重新选择')
    await expect(page.locator('.demand-publish__label--invalid')).toHaveText('期望完成')

    await page.getByTestId('demand-due').click()
    await page.getByTestId('demand-due-option-none').click()
    await expect(page.getByTestId('demand-due-value')).toHaveText('时间可协商')
    await expect(page.locator('.demand-publish__label--invalid')).toHaveCount(0)
    await page.getByTestId('demand-submit').click()
    await expect(page.getByTestId('demand-list')).toBeVisible()
    expect(await readDemand(request, buyer, draft.demandId)).toMatchObject({ status: 'OPEN', dueDate: null })
  })

  test('DEM-004 仅邀请：服务商弹层多选，没选时拦下，选好后提交成仅邀请', async ({ page, request }) => {
    await signIn(page, buyer)
    await page.goto('/#/pages/demand/publish?type=MAINTENANCE')
    await expect(page.getByTestId('demand-scope').locator('.uni-input-placeholder')).toHaveText('设备型号与数量')
    await page.getByTestId('demand-address').locator('input').fill('东营港区 3 号库')
    await page.getByTestId('demand-scope').locator('input').fill('巡检机器狗 2 台')

    await page.getByTestId('demand-scope-invite').click()
    await expect(page.getByTestId('demand-sheet')).toHaveAttribute('data-sheet', 'invite')
    await expect(page.getByTestId(`demand-provider-${PROVIDER_ID}`)).toContainText('云翼科技')
    await page.getByTestId('demand-providers-done').click()
    await expect(page.getByTestId('demand-scope-invite-sub')).toHaveText('还没有选择服务商')
    await page.getByTestId('demand-submit').click()
    await expect(notice(page)).toContainText('请邀请 1–3 家服务这个城市的服务商')

    await page.getByTestId('demand-scope-invite').click()
    await page.getByTestId(`demand-provider-${PROVIDER_ID}`).click()
    await expect(page.getByTestId('demand-providers-done')).toHaveText('确定（已选 1 家）')
    await page.getByTestId('demand-providers-done').click()
    await expect(page.getByTestId('demand-scope-invite-sub')).toHaveText('已选 1 家 · 云翼科技')
    await page.getByTestId('demand-submit').click()

    await expect(page.getByTestId('demand-list')).toBeVisible()
    const [sent] = await listDemands(request, buyer)
    expect(sent).toMatchObject({ status: 'OPEN', type: 'MAINTENANCE', quoteScope: 'INVITE' })
    expect(sent.invitedProviders.map((item) => item.providerId)).toEqual([PROVIDER_ID])
    await expect(card(page, sent.demandId).getByTestId('demand-card-line')).toContainText('已邀请 1 家服务商')
  })

  test('DEM-005 从消息进入比选，排序、比较、选定后可以填写巡检预约', async ({ page, request }) => {
    const demand = await createDemand(request, buyer, COMPLETE, true)
    const quote = await providerQuotes(request, provider, demand.demandId, 18_600)
    await signIn(page, buyer)

    // The provider's quote leaves the publisher a message; it opens this demand's quotes.
    await page.goto('/#/pages/message/index')
    const message = page.locator('.message-center__item', { hasText: '需求与报价' }).first()
    await expect(message).toBeVisible()
    await message.click()
    await expect(page.getByTestId('demand-quotes')).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`pages/demand/quotes\\?demandId=${demand.demandId}`))

    const quoteCard = page.getByTestId(`demand-quote-${quote.quoteId}`)
    await expect(quoteCard).toContainText('云翼科技')
    await expect(quoteCard.getByTestId('demand-quote-price')).toHaveText('¥18,600')
    await expect(quoteCard).toContainText('90 天')
    await expect(page.getByTestId('demand-summary')).toContainText('1 家已报价')
    await expect(page.getByTestId('demand-header-badge')).toHaveText('企业 · 东营示例物流公司')

    const sorted = page.waitForRequest((sent) => sent.url().includes('/quotes?sort=PRICE'))
    await page.getByTestId('demand-sort-price').click()
    await sorted
    await expect(page.getByTestId('demand-sort-price')).toHaveAttribute('aria-pressed', 'true')

    await page.getByTestId('demand-compare').click()
    await expect(page.getByTestId('demand-compare-table')).toContainText('¥18,600')
    await expect(page.getByTestId('demand-compare-table')).toContainText('v1')
    await page.getByTestId('demand-back').click()

    await page.getByTestId(`demand-quote-choose-${quote.quoteId}`).click()
    await expect(page.getByTestId('demand-confirm')).toContainText('选择 云翼科技 的报价？')
    await page.getByTestId('demand-confirm-go').click()

    await expect(page.getByTestId('demand-quotes')).toHaveAttribute('data-status', 'CHOSEN')
    await expect(page.getByTestId('demand-chosen-name')).toHaveText('云翼科技 v1')
    await expect(page.getByTestId('demand-inspection-booking')).toHaveText('填写巡检预约')
    await expect(quoteCard.getByTestId('demand-quote-tag')).toHaveText('已选中')
    await expect(page.locator('[data-testid^="demand-quote-choose-"]')).toHaveCount(0)
    expect(await readDemand(request, buyer, demand.demandId)).toMatchObject({ status: 'CHOSEN', chosen: { quoteId: quote.quoteId } })

    await page.goto('/#/pages/mine/demands?status=CHOSEN')
    await expect(card(page, demand.demandId).getByTestId('demand-card-line')).toHaveText('已选定 云翼科技 · 方案 v1 · ¥18,600')
    await expect(card(page, demand.demandId).getByText('填写巡检预约', { exact: true })).toBeVisible()
  })

  test('DEM-006 过期的选择：报价改了版本或需求版本变了，选择被拒后重新读取并说明', async ({ page, request }) => {
    const demand = await createDemand(request, buyer, COMPLETE, true)
    const first = await providerQuotes(request, provider, demand.demandId, 18_600)
    await signIn(page, buyer)
    await page.goto(`/#/pages/demand/quotes?demandId=${demand.demandId}`)
    await expect(page.getByTestId(`demand-quote-detail-${first.quoteId}`)).toHaveText('查看方案 v1')

    // The provider revises while the page is open: the choice of v1 is refused.
    const revised = await providerQuotes(request, provider, demand.demandId, 16_800)
    expect(revised.version).toBe(2)
    await page.getByTestId(`demand-quote-choose-${first.quoteId}`).click()
    const refused = page.waitForResponse((response) => response.url().endsWith('/choice'))
    await page.getByTestId('demand-confirm-go').click()
    expect([409, 412]).toContain((await refused).status())
    await expect(page.getByTestId('demand-quotes-notice')).toContainText('已刷新')
    await expect(page.getByTestId(`demand-quote-detail-${first.quoteId}`)).toHaveText('查看方案 v2')
    await expect(page.getByTestId(`demand-quote-${first.quoteId}`).getByTestId('demand-quote-price')).toHaveText('¥16,800')
    expect((await readDemand(request, buyer, demand.demandId)).status).toBe('QUOTED')

    // A 412 (the demand changed under the page) reads everything again too.
    await page.route('**/api/v1/demands/*/choice', (route: Route) => route.fulfill({
      status: 412,
      json: { code: 'DEMAND_VERSION_CONFLICT', message: '需求已变化', traceId: 'e2e' },
    }))
    const reads: string[] = []
    page.on('request', (sent) => { if (sent.url().includes('/quotes?sort=')) reads.push(sent.url()) })
    await page.getByTestId(`demand-quote-choose-${first.quoteId}`).click()
    await page.getByTestId('demand-confirm-go').click()
    await expect(page.getByTestId('demand-quotes-notice')).toContainText('需求有更新，已刷新')
    expect(reads.length).toBeGreaterThan(0)
    await expect(page.getByTestId('demand-confirm')).toHaveCount(0)
    expect((await readDemand(request, buyer, demand.demandId)).status).toBe('QUOTED')
  })

  test('DEM-007 多家报价：综合 / 总价 / 交付周期按服务端顺序显示，对比明细并排三家', async ({ page, request }) => {
    const demand = await createDemand(request, buyer, COMPLETE, true)
    const sorts: string[] = []
    await stubThreeQuotes(page, demand.demandId, sorts)
    await signIn(page, buyer)
    await page.goto(`/#/pages/demand/quotes?demandId=${demand.demandId}`)

    const names = page.locator('.demand-quotes__name')
    await expect(names).toHaveText(['鹰眼智能巡检', '黄河口飞服', '东营安巡科技'])
    await page.getByTestId('demand-sort-price').click()
    await expect(names).toHaveText(['黄河口飞服', '鹰眼智能巡检', '东营安巡科技'])
    await page.getByTestId('demand-sort-duration').click()
    await expect(names).toHaveText(['东营安巡科技', '鹰眼智能巡检', '黄河口飞服'])
    expect(sorts).toEqual(['ALL', 'PRICE', 'DURATION'])

    await page.getByTestId('demand-compare').click()
    const table = page.getByTestId('demand-compare-table')
    await expect(table.locator('.demand-compare__head-name')).toHaveText(['鹰眼智能巡检', '黄河口飞服', '东营安巡科技'])
    await expect(table.locator('.demand-compare__cell--price')).toHaveText(['¥18,600', '¥15,200', '¥21,000'])
    await expect(page.getByTestId('demand-compare-choose')).toHaveText('确认选择 鹰眼智能巡检')
    await page.getByTestId(`demand-compare-head-${STUB_QUOTES[1].quoteId}`).click()
    await expect(page.getByTestId('demand-compare-choose')).toHaveText('确认选择 黄河口飞服')
    await assertNoHorizontalOverflow(page)
  })

  test('DEM-008 取消待报价的需求、删除草稿；已关闭的可以重新发布', async ({ page, request }) => {
    const open = await createDemand(request, buyer, { ...COMPLETE, address: '取消用的地点' }, true)
    const draft = await createDemand(request, buyer, { type: 'AERIAL' }, false)
    await signIn(page, buyer)
    await page.goto('/#/pages/mine/demands')
    await expect(card(page, open.demandId)).toBeVisible()
    await expect(card(page, draft.demandId).getByTestId('demand-card-meta')).toHaveText('航拍 · 还差地点、作业范围、交付物')

    await page.getByTestId(`demand-card-cancel-${open.demandId}`).click()
    await expect(page.getByTestId('demand-confirm')).toContainText('确认取消这份需求？')
    await page.getByTestId('demand-confirm-keep').click()
    await expect(page.getByTestId('demand-confirm')).toHaveCount(0)
    await page.getByTestId(`demand-card-cancel-${open.demandId}`).click()
    await page.getByTestId('demand-confirm-go').click()
    await expect(card(page, open.demandId).getByTestId('demand-card-status')).toHaveText('已关闭')
    await expect(card(page, open.demandId).getByTestId('demand-card-line')).toHaveText('你已取消这份需求')
    expect(await readDemand(request, buyer, open.demandId)).toMatchObject({ status: 'CLOSED' })

    await page.getByTestId('demand-filter-draft').click()
    await page.getByTestId(`demand-card-delete-${draft.demandId}`).click()
    await expect(page.getByTestId('demand-confirm')).toContainText('确认删除这份草稿？')
    await page.getByTestId('demand-confirm-go').click()
    await expect(page.getByTestId('demand-list-empty')).toContainText('还没有草稿')
    expect((await listDemands(request, buyer)).map((item) => item.demandId)).toEqual([open.demandId])

    // The status chips sit under 全部 / 需求单 / 询价单, not under 草稿 (DemandList board).
    await page.getByTestId('demand-tab-demand').click()
    await page.getByTestId('demand-filter-closed').click()
    await page.getByTestId(`demand-card-republish-${open.demandId}`).click()
    await expect(page.getByTestId('demand-head').locator('.app-head__title')).toHaveText('发布服务需求')
    await expect(page.getByTestId('demand-address').locator('input')).toHaveValue('取消用的地点')
    await expect(page.getByTestId('demand-type-value')).toHaveText('巡检')
  })

  test('DEM-009 已提交的需求只读：看得到内容，可以取消；等报价时比选页说明截止时间', async ({ page, request }) => {
    const demand = await createDemand(request, buyer, { ...COMPLETE, note: '园区内有两处高压线，需要避开' }, true)
    await signIn(page, buyer)
    await page.goto(`/#/pages/demand/publish?demandId=${demand.demandId}`)
    await expect(page.getByTestId('demand-head').locator('.app-head__title')).toHaveText('需求详情')
    await expect(page.getByTestId('demand-view-status')).toHaveText('待报价')
    await expect(page.getByTestId('demand-publish')).toContainText('园区内有两处高压线')
    await expect(page.getByTestId('demand-submit')).toHaveCount(0)

    await page.getByTestId('demand-view-quotes').click()
    await expect(page.getByTestId('demand-quotes-empty')).toContainText('还没有服务商报价')
    await expect(page.getByTestId('demand-quotes-empty')).toContainText('报价截止')
    await page.getByTestId('demand-back').click()

    await page.getByTestId('demand-view-cancel').click()
    await page.getByTestId('demand-confirm-go').click()
    await expect(page.getByTestId('demand-view-status')).toHaveText('已关闭')
    await expect(page.getByTestId('demand-view-republish')).toBeVisible()
  })

  test('DEM-010 发布、需求与报价、报价比选、对比明细在 320 / 375 / 430 宽不横向溢出，390 宽和画板并排', async ({ page, request }) => {
    test.setTimeout(180_000)
    const draft = await createDemand(request, buyer, { type: 'SURVEY', address: '黄河口湿地', cityCode: CITY }, false)
    const open = await createDemand(request, buyer, { ...COMPLETE, quoteScope: 'INVITE', invitedProviderIds: [PROVIDER_ID] }, true)
    const quoted = await createDemand(request, buyer, { ...COMPLETE, type: 'AERIAL', deliverables: ['VIDEO_FOOTAGE'], address: '东营港区', scope: '码头堆场' }, true)
    await providerQuotes(request, provider, quoted.demandId, 12_800, 30)
    const chosen = await createDemand(request, buyer, { ...COMPLETE, address: '物流园区', scope: '约 42 万㎡' }, true)
    const chosenQuote = await providerQuotes(request, provider, chosen.demandId, 18_600)
    const chosenRead = await readDemand(request, buyer, chosen.demandId)
    const choice = await request.post(`${API}/api/v1/demands/${chosen.demandId}/choice`, {
      headers: { ...buyer.headers, 'If-Match': `"${chosenRead.version}"` },
      data: { quoteId: chosenQuote.quoteId, quoteVersion: chosenQuote.version },
    })
    expect(choice.status()).toBe(200)
    expect(draft.status).toBe('DRAFT')
    expect(open.status).toBe('OPEN')

    const multi = await createDemand(request, buyer, { ...COMPLETE, address: '物流园区', scope: '约 42 万㎡ · 每月 2 次' }, true)
    await stubThreeQuotes(page, multi.demandId)
    await signIn(page, buyer)
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

      await page.goto(at(`#/pages/demand/publish?demandId=${draft.demandId}`))
      await expect(page.getByTestId('demand-address').locator('input')).toHaveValue('黄河口湿地')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('Demand'))

      await page.goto(at('#/pages/mine/demands'))
      await expect(page.locator('.demand-list__card')).toHaveCount(5)
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('DemandList'))

      await page.goto(at(`#/pages/demand/quotes?demandId=${multi.demandId}`))
      await expect(page.locator('.demand-quotes__card')).toHaveCount(3)
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('Quote'))

      await page.goto(at(`#/pages/demand/compare?demandId=${multi.demandId}`))
      await expect(page.locator('.demand-compare__head')).toHaveCount(3)
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('PlanCompare'))
      if (width === 390) continue

      await page.goto(at(`#/pages/demand/quotes?demandId=${chosen.demandId}`))
      await expect(page.getByTestId('demand-inspection-booking')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('quotes-chosen'))

      await page.goto(at(`#/pages/demand/publish?demandId=${open.demandId}`))
      await expect(page.getByTestId('demand-view-status')).toHaveText('待报价')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('demand-view'))

      await page.goto(at('#/pages/demand/publish?type=INSPECTION'))
      await page.getByTestId('demand-scope-invite').click()
      await page.getByTestId(`demand-provider-${PROVIDER_ID}`).click()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('invite-sheet') })
    }

    // The boards at 390 next to the App at 390.
    for (const board of ['Demand', 'DemandList', 'Quote', 'PlanCompare']) {
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
