import { mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import { purgeFreeEnrollments } from './support/activity-fixtures'
import { databaseAvailable } from './support/trade-fixtures'

// 免费活动报名：参与人、填写报名、报名凭证、我的报名、取消。
// 用 dev 环境的示例活动“无人机开放体验日”（免费、不限年龄）和开发账号 dev-customer，全部走真实后端；
// 只有“已签到”“已过期”两种凭证样子用接口桩，因为签到要等到场次当天。
// 每条用例开始前把这个账号名下待参加的报名取消、把子女删掉，再把这个账号的免费报名（连同凭证、记录）和删掉的子女
// 从数据库清掉（有本地开发数据库容器时），所以可以反复跑，也不会在“已取消”里越积越多。
const API = 'http://127.0.0.1:18420'
const visualOutput = resolve(__dirname, '../../../.planning/activity-enroll/visual')
const OPEN_DAY_ID = 'a2000000-0000-4000-8000-000000000005'
const OPEN_DAY_SESSION = 'a3000000-0000-4000-8000-000000000502'
const INQUIRY_ID = 'a2000000-0000-4000-8000-000000000004'
const INQUIRY_SESSION = 'a3000000-0000-4000-8000-000000000401'
const CITY = '370500'
const ENROLL_URL = `/#/pages/activity/enroll?activityId=${OPEN_DAY_ID}&sessionId=${OPEN_DAY_SESSION}&cityCode=${CITY}`

function devPassword(): string {
  const seed = readFileSync(resolve(
    __dirname,
    '../../../backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql',
  ), 'utf8')
  return /crypt\('([^']+)'/.exec(seed)?.[1] ?? ''
}

interface Session {
  token: string
  headers: { Authorization: string }
}

async function apiLogin(request: APIRequestContext): Promise<Session | null> {
  const login = await request.post(`${API}/api/v1/auth/login`, {
    data: { loginName: 'dev-customer', password: devPassword() },
  })
  if (!login.ok()) return null
  const { accessToken } = await login.json()
  return { token: accessToken, headers: { Authorization: `Bearer ${accessToken}` } }
}

async function resetAccount(request: APIRequestContext, session: Session): Promise<void> {
  const upcoming = await (await request.get(
    `${API}/api/v1/activity-enrollments?status=UPCOMING&size=50`, { headers: session.headers })).json()
  for (const enrollment of upcoming.items) {
    await request.post(`${API}/api/v1/activity-enrollments/${enrollment.enrollmentId}/cancel`, {
      headers: session.headers,
      data: {},
    })
  }
  const people = await (await request.get(
    `${API}/api/v1/activity-participants`, { headers: session.headers })).json()
  for (const person of people.items) {
    if (person.relation === 'CHILD') {
      await request.delete(`${API}/api/v1/activity-participants/${person.participantId}`, {
        headers: session.headers,
      })
    }
  }
}

async function selfId(request: APIRequestContext, session: Session): Promise<string> {
  const people = await (await request.get(
    `${API}/api/v1/activity-participants`, { headers: session.headers })).json()
  return people.items.find((person: { relation: string }) => person.relation === 'SELF').participantId
}

async function addChild(
  request: APIRequestContext,
  session: Session,
  name: string,
  yearsOld: number,
): Promise<string> {
  const birthMonth = `${new Date().getFullYear() - yearsOld}-01`
  const created = await request.post(`${API}/api/v1/activity-participants`, {
    headers: session.headers,
    data: { name, birthMonth, guardianConfirmed: true },
  })
  expect(created.status()).toBe(201)
  return (await created.json()).participantId
}

async function enrollByApi(
  request: APIRequestContext,
  session: Session,
  participantIds: string[],
): Promise<{ enrollmentId: string; tickets: Array<{ ticketId: string; ticketCode: string }> }> {
  const created = await request.post(`${API}/api/v1/activity-enrollments`, {
    headers: { ...session.headers, 'Idempotency-Key': crypto.randomUUID() },
    data: { activityId: OPEN_DAY_ID, sessionId: OPEN_DAY_SESSION, participantIds, noticeAgreed: true },
  })
  expect(created.status()).toBe(201)
  return created.json()
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

// uni-button shows as enabled to Playwright even when disabled, so the attribute is checked.
const submitOff = (page: Page) => page.locator('[data-testid="activity-enroll-submit"][disabled]')
const submitOn = (page: Page) => page.locator('[data-testid="activity-enroll-submit"]:not([disabled])')

test.describe('免费活动报名', () => {
  test.describe.configure({ mode: 'serial' })

  let session: Session

  test.beforeEach(async ({ request }) => {
    const signedIn = await apiLogin(request)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    session = signedIn as Session
    await resetAccount(request, session)
    if (databaseAvailable()) purgeFreeEnrollments()
  })

  test.afterAll(async ({ request }) => {
    const signedIn = await apiLogin(request)
    if (signedIn) await resetAccount(request, signedIn)
    if (databaseAvailable()) purgeFreeEnrollments()
  })

  test('ENR-001 游客从首页进入开放体验日，登录后给本人报名，在我的报名里取消', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')
    await page.getByTestId('home-channel-activity').click()
    await page.getByTestId(`activity-card-${OPEN_DAY_ID}`).click()
    await expect(page.getByTestId('activity-detail-title')).toHaveText('无人机开放体验日')
    await page.getByTestId(`activity-session-${OPEN_DAY_SESSION}`).click()
    await page.locator('[data-testid="activity-detail-primary"]:not([disabled])').click()

    // 没登录：先到登录页，登录后回到同一张报名表。
    await expect(page.locator('.inspection-login__form')).toBeVisible()
    await page.locator('.inspection-login__input input').nth(0).fill('dev-customer')
    await page.locator('.inspection-login__input input').nth(1).fill(devPassword())
    await page.locator('.inspection-login__submit').click()

    await expect(page.getByTestId('activity-enroll-title')).toHaveText('无人机开放体验日')
    const sessionButton = page.getByTestId(`activity-enroll-session-${OPEN_DAY_SESSION}`)
    await expect(sessionButton).toHaveAttribute('aria-pressed', 'true')
    const self = page.locator('.activity-enroll__person').first()
    await expect(self).toContainText('本人')
    await expect(self).toContainText('可报名')
    await expect(page.getByTestId('activity-enroll-count')).toHaveText('请选择参与人')
    await expect(submitOff(page)).toHaveCount(1)

    await self.click()
    await expect(self).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('activity-enroll-count')).toHaveText('已选 1 人')
    // 只选了本人，同意语不带“监护人”。
    await expect(page.locator('.activity-enroll__agree-text')).toContainText('我已阅读并同意')
    await expect(submitOff(page)).toHaveCount(1)
    await page.getByTestId('activity-enroll-agree').click()
    await submitOn(page).click()

    await expect(page.getByTestId('ticket-status-title')).toHaveText('报名成功 · 待参加')
    await expect(page.getByTestId('ticket-activity')).toHaveText('无人机开放体验日')
    await expect(page.getByTestId('ticket-code')).toHaveText(/^ACT-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$/)
    await expect(page.getByTestId('ticket-sign')).toHaveText('待签到 · 13:30 起')
    await expect(page.getByTestId('ticket-note')).toContainText('前可取消')
    const qrSource = await page.getByTestId('ticket-qr').locator('img').getAttribute('src')
    expect(qrSource).toContain('data:image/svg+xml')
    await expect(page.getByTestId('ticket-stamp')).toHaveCount(0)

    // 凭证替换了报名表，返回落到活动详情。
    await page.getByTestId('ticket-back').click()
    await expect(page.getByTestId('activity-detail-title')).toHaveText('无人机开放体验日')

    // 我的 → 活动报名 → 待参加里有这一张。
    await page.goto('/#/pages/mine/index')
    await page.getByTestId('mine-link-activity_enrollments').click()
    const card = page.locator('.enrollments__card')
    await expect(card).toHaveCount(1)
    await expect(card).toContainText('待参加')
    await expect(card).toContainText('无人机开放体验日')
    await expect(card).toContainText('参与人：王女士')
    await card.locator('.enrollments__action--primary').click()

    await expect(page.getByTestId('ticket-status-title')).toHaveText('报名成功 · 待参加')
    await page.locator('[data-testid="ticket-cancel"]:not([disabled])').click()
    await expect(page.getByTestId('ticket-cancel-sheet')).toBeVisible()
    // 只有一张有效凭证时没有“全部取消”。
    await expect(page.getByTestId('ticket-cancel-all')).toHaveCount(0)
    await page.getByTestId('ticket-cancel-one').click()
    await expect(page.getByTestId('ticket-status-title')).toHaveText('报名已取消')
    await expect(page.getByTestId('ticket-stamp')).toHaveText('已取消')
    await expect(page.getByTestId('ticket-cancel')).toHaveCount(0)

    await page.getByTestId('ticket-back').click()
    await expect(page.getByTestId('enrollments-empty')).toBeVisible()
    await page.getByTestId('enrollments-tab-cancelled').click()
    await expect(page.locator('.enrollments__card').first()).toContainText('已取消')
  })

  test('ENR-002 参与人：添加子女、重复添加被拒、照片授权、改名、删除', async ({ page }) => {
    await signIn(page, session)
    await page.goto('/#/pages/mine/index')
    await page.getByTestId('mine-link-activity_participants').click()

    await expect(page.getByTestId('participants-self')).toContainText('王女士')
    await expect(page.getByTestId('participants-self')).toContainText('本人')
    await expect(page.getByTestId('participants-children-title')).toHaveText('子女 · 0 人')
    await expect(page.getByTestId('participants-no-children')).toBeVisible()

    const saveOff = page.locator('[data-testid="participant-save"][disabled]')
    const saveOn = page.locator('[data-testid="participant-save"]:not([disabled])')
    const addChildFromSheet = async (name: string) => {
      await page.getByTestId('participants-add').click()
      await expect(page.getByTestId('participant-sheet')).toBeVisible()
      await page.getByTestId('participant-name').locator('input').fill(name)
      // 出生年月用选择器，直接确认取默认值（本月）。
      await page.getByTestId('participant-birth').click()
      await page.locator('.uni-picker-action-confirm').click()
      await expect(page.getByTestId('participant-birth')).toContainText('年')
    }

    await addChildFromSheet('王小安')
    // 没勾监护人确认不能保存。
    await expect(saveOff).toHaveCount(1)
    await page.getByTestId('participant-consent').click()
    await saveOn.click()
    await expect(page.getByTestId('participants-toast')).toContainText('已添加 王小安')
    await expect(page.getByTestId('participants-children-title')).toHaveText('子女 · 1 人')
    const child = page.locator('.participants__card--child')
    await expect(child).toContainText('王小安')
    await expect(child).toContainText('未成年 · 0 岁')
    await expect(child).toContainText('监护人确认')

    // 同名同出生年月再加一次：服务端拒绝，面板里说明原因。
    await addChildFromSheet('王小安')
    await page.getByTestId('participant-consent').click()
    await saveOn.click()
    await expect(page.getByTestId('participant-sheet-error')).toContainText('已在列表中')
    await page.locator('.participants__cancel').click()
    await expect(page.getByTestId('participant-sheet')).toHaveCount(0)
    await expect(child).toHaveCount(1)

    // 照片授权默认关，开关跟着服务端记录走。
    const photo = child.locator('.participants__switch')
    await expect(photo).toHaveAttribute('aria-checked', 'false')
    await expect(child).toContainText('未授权')
    await photo.click()
    await expect(photo).toHaveAttribute('aria-checked', 'true')
    await expect(child).toContainText('已授权')

    await child.locator('.participants__edit').click()
    await page.getByTestId('participant-name').locator('input').fill('王小安安')
    await saveOn.click()
    await expect(page.getByTestId('participants-toast')).toContainText('已保存')
    await expect(child).toContainText('王小安安')

    // 刷新后仍在：数据来自服务端。
    await page.reload()
    await expect(page.locator('.participants__card--child')).toContainText('王小安安')
    await expect(page.locator('.participants__switch')).toHaveAttribute('aria-checked', 'true')

    await page.locator('.participants__card--child .participants__edit').click()
    await page.getByTestId('participant-delete').click()
    await page.getByTestId('participant-delete-confirm').click()
    await expect(page.getByTestId('participants-toast')).toContainText('已删除 王小安安')
    await expect(page.getByTestId('participants-children-title')).toHaveText('子女 · 0 人')
    // 本人没有删除入口。
    await page.getByTestId('participants-self').locator('.participants__edit').click()
    await expect(page.getByTestId('participant-sheet')).toContainText('编辑本人信息')
    await expect(page.getByTestId('participant-delete')).toHaveCount(0)
  })

  test('ENR-003 监护人给本人和孩子一起报名，一人一码，可以只取消其中一张', async ({ page, request }) => {
    await addChild(request, session, '王小禾', 11)
    await signIn(page, session)
    await page.goto(ENROLL_URL)

    const rows = page.locator('.activity-enroll__person')
    await expect(rows).toHaveCount(2)
    await expect(rows.nth(1)).toContainText('王小禾')
    await expect(rows.nth(1)).toContainText('子女')
    await expect(rows.nth(1)).toContainText('岁')
    await rows.nth(0).click()
    await rows.nth(1).click()
    await expect(page.getByTestId('activity-enroll-count')).toHaveText('已选 2 人')
    // 报名里有孩子，同意语以监护人身份说。
    await expect(page.locator('.activity-enroll__agree-text')).toContainText('我是参与人监护人，已阅读并同意')
    // 协议正文还没有（G1 起打开协议页，正文是占位）；回来后勾选不变。
    await page.getByTestId('activity-enroll-notice').click()
    await expect(page.getByTestId('agreement-title')).toHaveText('报名须知与照片使用说明')
    await expect(page.getByTestId('agreement-placeholder').first()).toHaveText('[协议正文待确认]')
    await page.getByTestId('agreement-back').click()
    await expect(page.getByTestId('activity-enroll-count')).toHaveText('已选 2 人')
    await page.getByTestId('activity-enroll-agree').click()
    await submitOn(page).click()

    // 凭证上本人在前，其余按姓名。
    const people = page.locator('.ticket__person')
    await expect(people).toHaveText(['王女士（示例）', '王小禾'])
    await expect(people.nth(0)).toHaveAttribute('aria-pressed', 'true')
    const firstCode = await page.getByTestId('ticket-code').textContent()
    await people.nth(1).click()
    await expect(page.getByTestId('ticket-code')).not.toHaveText(firstCode ?? '')
    await expect(page.locator('.ticket__who-meta')).toContainText('岁 · 一人一码')

    // 两张都有效时可以全部取消；这里只取消孩子这一张。
    await page.locator('[data-testid="ticket-cancel"]:not([disabled])').click()
    await expect(page.getByTestId('ticket-cancel-all')).toHaveText('2 人全部取消')
    await page.getByTestId('ticket-cancel-one').click()
    await expect(page.getByTestId('ticket-status-title')).toHaveText('报名已取消')
    await people.nth(0).click()
    await expect(page.getByTestId('ticket-status-title')).toHaveText('报名成功 · 待参加')

    await page.goto('/#/pages/mine/activity-enrollments')
    const card = page.locator('.enrollments__card')
    await expect(card).toHaveCount(1)
    await expect(card).toContainText('待参加')
    await expect(card.locator('.enrollments__who')).toHaveText('参与人：王女士（示例）')

    // 列表里取消整张报名单。
    await card.locator('.enrollments__action').first().click()
    await page.locator('.uni-modal__btn_primary').click()
    await expect(page.getByTestId('enrollments-empty')).toBeVisible()
  })

  test('ENR-004 已经报过这一场的人不能再选，换一场可以', async ({ page, request }) => {
    await enrollByApi(request, session, [await selfId(request, session)])
    await signIn(page, session)
    await page.goto(ENROLL_URL)

    const self = page.locator('.activity-enroll__person').first()
    await expect(self).toContainText('已报名本场')
    await expect(self).toHaveAttribute('aria-disabled', 'true')
    // Playwright 不点 aria-disabled 的元素，这里强制点一下，确认点了也选不上。
    await self.click({ force: true })
    await expect(self).toHaveAttribute('aria-checked', 'false')
    await expect(page.getByTestId('activity-enroll-count')).toHaveText('请选择参与人')

    // 能不能报按场次算：换到另一场，本人又可以选。
    const other = page.locator('.activity-enroll__session:not(.activity-enroll__session--off):not(.activity-enroll__session--on)').first()
    await other.click()
    await expect(self).toContainText('可报名')
    await expect(self).toHaveAttribute('aria-disabled', 'false')
  })

  // 收费（按人）活动从 E09 起可以在线报名付款，见 activity-paid.spec.ts。
  test('ENR-005 询价活动不能在线报名：详情页给提示，直接打开报名表也进不去', async ({ page }) => {
    await signIn(page, session)
    await page.goto(`/#/pages/activity/detail?activityId=${INQUIRY_ID}&cityCode=${CITY}`)
    await page.getByTestId(`activity-session-${INQUIRY_SESSION}`).click()
    await page.locator('[data-testid="activity-detail-primary"]:not([disabled])').click()
    await expect(page.locator('uni-toast')).toContainText('询价活动暂不支持在线报名')
    await expect(page.getByTestId('activity-detail-title')).toBeVisible()

    await page.goto(`/#/pages/activity/enroll?activityId=${INQUIRY_ID}&sessionId=${INQUIRY_SESSION}&cityCode=${CITY}`)
    await expect(page.getByTestId('activity-enroll-unsupported')).toBeVisible()
    await expect(page.getByTestId('activity-enroll-submit')).toHaveCount(0)
  })

  test('ENR-006 没登录直接打开报名相关页面，都先到登录页', async ({ page }) => {
    for (const url of [
      ENROLL_URL,
      '/#/pages/activity/participants',
      '/#/pages/mine/activity-enrollments',
      '/#/pages/activity/ticket?enrollmentId=00000000-0000-4000-8000-000000000000',
    ]) {
      await page.goto(url)
      await expect(page.locator('.inspection-login__form')).toBeVisible()
    }
  })

  test('ENR-007 别人的或不存在的报名单打不开', async ({ page }) => {
    await signIn(page, session)
    await page.goto('/#/pages/activity/ticket?enrollmentId=00000000-0000-4000-8000-000000000000')
    await expect(page.getByTestId('ticket-missing')).toBeVisible()
    await expect(page.getByTestId('ticket-code')).toHaveCount(0)
  })

  test('ENR-008 已签到和已过期的凭证（接口桩）', async ({ page, request }) => {
    const created = await enrollByApi(request, session, [await selfId(request, session)])
    const real = await (await request.get(
      `${API}/api/v1/activity-enrollments/${created.enrollmentId}`, { headers: session.headers })).json()
    await signIn(page, session)

    const serve = async (patch: (body: typeof real) => void) => {
      await page.unroute('**/api/v1/activity-enrollments/*')
      await page.route('**/api/v1/activity-enrollments/*', async (route) => {
        const body = JSON.parse(JSON.stringify(real))
        patch(body)
        await route.fulfill({ json: body })
      })
      await page.goto(`/#/pages/activity/ticket?enrollmentId=${created.enrollmentId}`)
      // 同一个地址第二次打开不会重新加载，这里明确重读一次。
      await page.reload()
    }

    await serve((body) => {
      body.cancellable = false
      body.tickets[0].status = 'CHECKED_IN'
      body.tickets[0].checkedInAt = '2026-11-29T05:42:00Z'
    })
    await expect(page.getByTestId('ticket-status-title')).toHaveText('已签到')
    await expect(page.getByTestId('ticket-sign')).toHaveText('已签到 · 13:42')
    await expect(page.getByTestId('ticket-stamp')).toHaveText('已核销')
    await expect(page.locator('.ticket__hint')).toHaveText('已于 11/29 13:42 核销，不能重复使用')
    await expect(page.getByTestId('ticket-cancel')).toHaveCount(0)
    await expect(page.getByTestId('ticket-open-activity')).toBeVisible()

    await serve((body) => {
      body.status = 'FINISHED'
      body.cancellable = false
      body.tickets[0].status = 'EXPIRED'
    })
    await expect(page.getByTestId('ticket-status-title')).toHaveText('已过期')
    await expect(page.getByTestId('ticket-sign')).toHaveText('未签到')
    await expect(page.getByTestId('ticket-stamp')).toHaveText('已过期')
    await expect(page.locator('.ticket--grey')).toHaveCount(1)
  })

  test('ENR-009 四个页面在 320 / 375 / 430 宽不横向溢出，并留截图', async ({ page, request }) => {
    const childId = await addChild(request, session, '王小禾', 11)
    await addChild(request, session, '王小川', 9)
    await request.put(`${API}/api/v1/activity-participants/${childId}/photo-use`, {
      headers: session.headers,
      data: { authorized: true },
    })
    const created = await enrollByApi(request, session, [childId])
    await signIn(page, session)
    mkdirSync(visualOutput, { recursive: true })

    for (const width of [375, 320, 430]) {
      await page.setViewportSize({ width, height: 844 })

      await page.goto(`/?w=${width}${ENROLL_URL.slice(1)}`)
      await expect(page.locator('.activity-enroll__person')).toHaveCount(3)
      await page.locator('.activity-enroll__person').nth(2).click()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `enroll-${width}.png`), fullPage: true })

      await page.goto(`/?w=${width}#/pages/activity/participants`)
      await expect(page.locator('.participants__card--child')).toHaveCount(2)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `participants-${width}.png`), fullPage: true })

      await page.goto(`/?w=${width}#/pages/activity/ticket?enrollmentId=${created.enrollmentId}`)
      await expect(page.getByTestId('ticket-code')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `ticket-${width}.png`), fullPage: true })

      await page.goto(`/?w=${width}#/pages/mine/activity-enrollments`)
      await expect(page.locator('.enrollments__card')).toHaveCount(1)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `enrollments-${width}.png`), fullPage: true })
    }
  })
})
