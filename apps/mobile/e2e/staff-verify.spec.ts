import { mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Browser, type Page, type Route } from '@playwright/test'

import { purgeFreeEnrollments } from './support/activity-fixtures'
import { API, BUYER, apiLogin, databaseAvailable, psql, type Session } from './support/trade-fixtures'

// 核销工作台（S3-3，默认决定 D8；契约 contracts/openapi-checkin-workbench.yaml）：App 内“工作模式”只做扫码 / 输码核销和
// 本人核销记录。画板 StaffVerify、StaffRecords、StaffException 和 Mine 的“工作模式 · 核销员 · 仅授权可见”。
//
// 前三条走真实后端：
// - 入口只给活动主办 / 承办或场馆运营机构的操作员（dev-organizer 是“东营低空与机器人体验馆”的操作员，它承办活动、运营场馆）；
//   客户 dev-customer 和不办活动也不运营场馆的服务商 dev-provider 看不到。
// - dev-customer 报名免费的“无人机开放体验日”再取消，dev-organizer 在浏览器里手动输入这张凭证：被拦下（已取消），去异常处理、
//   记录拦截，核销记录里有这一条。不依赖今天有没有正在核销的场次。
// - 没有这张凭证、格式不对：页面说明原因，服务端不记录。
// 第四条用接口桩把三张画板的样子（核销成功、场次不符、按场次分组的记录、改签放行）画出来，和画板 390 宽并排对照，
// 因为真实的“正在核销”场次要看跑测试的时刻。
// 跑完删掉 dev-organizer 在工作台上的记录和这次取消的报名（有本地开发数据库容器时）。
const OPERATOR = 'dev-organizer'
const OPEN_DAY_ID = 'a2000000-0000-4000-8000-000000000005'
const OPEN_DAY_SESSION = 'a3000000-0000-4000-8000-000000000502'
const visualOutput = resolve(__dirname, '../../../../../.planning/staff-verify/visual')
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare/staff')
const boardDir = resolve(__dirname, '../../../../../.planning/prototype/v2-20261114')

let buyer: Session
let organizer: Session
let provider: Session

const operatorId = `(SELECT id FROM identity_user WHERE login_name = '${OPERATOR}')`

/** 工作台记录只由这个页面产生；dev-organizer 的全部删掉（表还没建时什么也不做）。 */
function purgeOperatorRecords(): void {
  psql(`
    DO $$
    BEGIN
      IF to_regclass('activity_checkin_record') IS NOT NULL THEN
        DELETE FROM activity_checkin_record WHERE operator_id = ${operatorId};
      END IF;
      IF to_regclass('venue_checkin_record') IS NOT NULL THEN
        DELETE FROM venue_checkin_record WHERE operator_id = ${operatorId};
      END IF;
    END $$;
  `)
}

async function signedInPage(browser: Browser, session: Session, width = 390): Promise<Page> {
  const context = await browser.newContext({ viewport: { width, height: 844 } })
  const page = await context.newPage()
  await page.addInitScript((token) => localStorage.setItem('inspection.accessToken', token), session.token)
  return page
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

async function typeCode(page: Page, code: string): Promise<void> {
  if (!(await page.getByTestId('staff-verify-manual').isVisible())) {
    await page.getByTestId('staff-verify-manual-toggle').click()
  }
  await page.getByTestId('staff-verify-code').locator('input').fill(code)
  await expect(page.locator('[data-testid="staff-verify-submit"]:not([disabled])')).toHaveCount(1)
  await page.getByTestId('staff-verify-submit').click()
}

async function openWorkbench(page: Page): Promise<void> {
  await page.goto('/#/pages/staff/verify')
  await expect(page.getByTestId('staff-verify-badge')).toBeVisible()
}

async function myRecords(request: import('@playwright/test').APIRequestContext): Promise<Array<Record<string, unknown>>> {
  const response = await request.get(`${API}/api/v1/checkin-workbench/records`, { headers: organizer.headers })
  expect(response.ok()).toBe(true)
  return (await response.json()).items
}

function beijingToday(): string {
  const shifted = new Date(Date.now() + 8 * 3600_000)
  return shifted.toISOString().slice(0, 10)
}

test.describe('核销工作台（工作模式）', () => {
  test.beforeAll(async ({ request }) => {
    test.skip(!databaseAvailable(), '开发数据库容器没有运行')
    const sessions = await Promise.all([BUYER, OPERATOR, 'dev-provider'].map((name) => apiLogin(request, name)))
    test.skip(sessions.some((session) => !session), '本地后端没有开发账号（不是 dev 配置）')
    ;[buyer, organizer, provider] = sessions as Session[]
    const probe = await request.get(`${API}/api/v1/checkin-workbench`, { headers: organizer.headers })
    test.skip(probe.status() === 404, '后端还没有核销工作台接口（S3-3 之前的包）')
    purgeOperatorRecords()
  })

  test.afterAll(() => {
    if (!databaseAvailable()) return
    purgeOperatorRecords()
    purgeFreeEnrollments({ onlyCancelled: true })
  })

  test('只有活动主办或场馆运营机构的操作员看到“工作模式”', async ({ browser }) => {
    for (const session of [buyer, provider]) {
      const page = await signedInPage(browser, session)
      await page.goto('/#/pages/mine/index')
      await expect(page.getByTestId('mine-link-help')).toBeVisible()
      // 读完账号的几项之后再看：入口不该出现。
      await page.waitForTimeout(1500)
      await expect(page.getByTestId('mine-link-staff_work_mode')).toHaveCount(0)
      await page.context().close()
    }

    const page = await signedInPage(browser, organizer)
    await page.goto('/#/pages/mine/index')
    const entry = page.getByTestId('mine-link-staff_work_mode')
    await expect(entry).toBeVisible()
    await expect(entry).toContainText('工作模式')
    await expect(page.getByTestId('mine-link-value-staff_work_mode')).toHaveText('核销员 · 仅授权可见')
    mkdirSync(compareOutput, { recursive: true })
    await page.screenshot({ path: resolve(compareOutput, 'Mine-app.png'), fullPage: true })

    await entry.click()
    await expect(page.getByTestId('staff-verify-page')).toBeVisible()
    await expect(page.getByTestId('staff-verify-badge')).toContainText('核销员')
    await expect(page.getByTestId('staff-verify-badge')).toContainText('东营低空与机器人体验馆')
    await expect(page.getByTestId('staff-verify-sessions')).toContainText('今日场次')
    // 退出工作模式回到“我的”。
    await page.getByTestId('staff-verify-exit').click()
    await expect(page.getByTestId('mine-page')).toBeVisible()
    await page.context().close()
  })

  test('已取消的凭证被拦下，登记拦截后出现在核销记录里', async ({ browser, request }) => {
    const people = await (await request.get(`${API}/api/v1/activity-participants`, { headers: buyer.headers })).json()
    const self = people.items.find((person: { relation: string }) => person.relation === 'SELF')
    const created = await request.post(`${API}/api/v1/activity-enrollments`, {
      headers: { ...buyer.headers, 'Idempotency-Key': crypto.randomUUID() },
      data: { activityId: OPEN_DAY_ID, sessionId: OPEN_DAY_SESSION, participantIds: [self.participantId], noticeAgreed: true },
    })
    test.skip(created.status() !== 201, `示例活动这一场现在不能报名（${created.status()}）：先刷新开发种子日期`)
    const enrollment = await created.json()
    const code: string = enrollment.tickets[0].ticketCode
    const cancelled = await request.post(`${API}/api/v1/activity-enrollments/${enrollment.enrollmentId}/cancel`, {
      headers: buyer.headers,
      data: {},
    })
    expect(cancelled.ok()).toBe(true)

    const page = await signedInPage(browser, organizer)
    await openWorkbench(page)
    await typeCode(page, code.toLowerCase())
    await expect(page.getByTestId('staff-verify-failed')).toBeVisible()
    await expect(page.getByTestId('staff-verify-failed-line')).toHaveText('凭证已取消：不能核销')

    await page.getByTestId('staff-verify-handle').click()
    await expect(page.getByTestId('staff-exception-page')).toBeVisible()
    await expect(page.getByTestId('staff-exception-title')).toHaveText('凭证已取消，已拦截')
    await expect(page.getByTestId('staff-exception-notice')).toContainText('一律拦截')
    await expect(page.getByTestId('staff-exception-form')).toHaveCount(0)
    await page.getByTestId('staff-exception-block').click()
    await expect(page.getByTestId('staff-exception-done')).toBeVisible()
    await expect(page.getByTestId('staff-exception-done-act')).toHaveText('处理：拦截，不予入场')

    // 服务端：一条已取消的记录，已拦截，凭证只留尾号，持有人只给姓。
    const records = await myRecords(request)
    const stored = records.find((item) => item.codeTail === code.slice(-4) && item.outcome === 'CANCELLED')
    expect(stored).toBeTruthy()
    expect((stored?.resolution as { action: string }).action).toBe('BLOCK')
    expect(String(stored?.holderName)).toMatch(/^.\*\*$/)
    expect(stored?.allowedActions).toEqual([])

    // 再提交一次同样的处理：原样返回，不能换成别的处理方式。
    const again = await request.post(
      `${API}/api/v1/checkin-workbench/records/${stored?.kind}/${stored?.recordId}/resolution`,
      { headers: organizer.headers, data: { action: 'BLOCK' } },
    )
    expect(again.status()).toBe(200)
    const other = await request.post(
      `${API}/api/v1/checkin-workbench/records/${stored?.kind}/${stored?.recordId}/resolution`,
      { headers: organizer.headers, data: { action: 'REJECT' } },
    )
    expect(other.status()).toBe(409)
    expect((await other.json()).code).toBe('CHECKIN_ALREADY_RESOLVED')

    await page.getByTestId('staff-exception-back-to-scan').click()
    await expect(page.getByTestId('staff-verify-page')).toBeVisible()
    await expect(page.getByTestId('staff-verify-failed')).toHaveCount(0)
    await page.getByTestId('staff-verify-records').click()
    await expect(page.getByTestId('staff-records-page')).toBeVisible()
    await expect(page.getByTestId('staff-records-tab-bad')).toHaveText(/异常 [1-9]/)
    await page.getByTestId('staff-records-tab-bad').click()
    const row = page.getByTestId(`staff-record-${stored?.recordId}`)
    await expect(row).toContainText('凭证已取消')
    await expect(row).toContainText('未核销')
    await row.click()
    await expect(page.getByTestId('staff-records-sheet')).toContainText('拦截，不予入场')
    await expect(page.getByTestId('staff-records-sheet')).toContainText('手动输入凭证号')
    await page.getByTestId('staff-records-sheet-close').click()
    await expect(page.getByTestId('staff-records-sheet')).toHaveCount(0)
    await page.context().close()
  })

  test('没有这张凭证、格式不对：页面说明原因，服务端不记录', async ({ browser, request }) => {
    const before = (await myRecords(request)).length
    const page = await signedInPage(browser, organizer)
    await openWorkbench(page)

    await typeCode(page, 'ACT-0000-0000-0000')
    await expect(page.getByTestId('staff-verify-failed-line')).toHaveText('没有这张凭证，请核对凭证号')
    await expect(page.getByTestId('staff-verify-handle')).toHaveCount(0)
    await typeCode(page, '12345')
    await expect(page.getByTestId('staff-verify-failed-line')).toHaveText('凭证号格式不对，请核对后重新输入')
    // 浏览器里没有相机扫码：按钮打开手动输入并说明。
    await page.getByTestId('staff-verify-manual-toggle').click()
    await page.getByTestId('staff-verify-scan').click()
    await expect(page.getByTestId('staff-verify-manual')).toBeVisible()
    await expect(page.getByTestId('staff-verify-scan-hint')).toContainText('手动输入凭证号')

    expect((await myRecords(request)).length).toBe(before)
    // 客户账号调工作台接口是 403。
    const denied = await request.get(`${API}/api/v1/checkin-workbench`, { headers: buyer.headers })
    expect(denied.status()).toBe(403)
    await page.context().close()
  })

  test('画板对照：核销成功、场次不符、核销记录、改签放行（接口桩）', async ({ browser }) => {
    const day = beijingToday()
    const at = (hhmm: string) => new Date(`${day}T${hhmm}:00+08:00`).toISOString()
    const ref = (id: string, title: string, hhmm: string, kind = 'VENUE') =>
      ({ kind, sessionId: id, title, label: null, startsAt: at(hhmm) })
    const S1000 = 'b6000000-0000-4000-8000-000000001000'
    const S1400 = 'b6000000-0000-4000-8000-000000001400'
    const S1530 = 'b6000000-0000-4000-8000-000000001530'
    const session = (id: string, title: string, hhmm: string, end: string, phase: string, expected: number, done: number) => ({
      kind: 'VENUE', sessionId: id, title, placeName: '示例低空与机器人体验馆', label: null,
      startsAt: at(hhmm), endsAt: at(end), checkInOpensAt: at(hhmm), checkInClosesAt: at(end),
      phase, expectedPeople: expected, checkedInPeople: done,
    })
    const workbench = (checked1400: number) => ({
      date: day,
      kinds: ['VENUE'],
      sessions: [
        session(S1000, '模拟飞行', '10:00', '11:00', 'ENDED', 6, 6),
        session(S1400, '机器人避障闯关', '14:00', '15:00', 'OPEN', 8, checked1400),
        session(S1530, '机器人闯关', '15:30', '16:30', 'UPCOMING', 2, 0),
      ],
    })
    let n = 0
    const record = (who: string, people: number, tail: string, hhmm: string, extra: Record<string, unknown> = {}) => ({
      kind: 'VENUE',
      recordId: `b8000000-0000-4000-8000-${String(++n).padStart(12, '0')}`,
      outcome: 'CHECKED_IN',
      method: 'SCAN',
      occurredAt: at(hhmm),
      codeTail: tail,
      holderName: who,
      people,
      amountMinor: 3900 * people,
      ticketSession: ref(S1400, '机器人避障闯关', '14:00'),
      currentSession: ref(S1400, '机器人避障闯关', '14:00'),
      firstCheckedInAt: null,
      allowedActions: [],
      resolution: null,
      ...extra,
    })
    const wang = record('王**', 1, '0314', '14:05')
    const mismatch = record('孙**', 1, '0815', '14:08', {
      outcome: 'SESSION_MISMATCH', ticketSession: ref(S1530, '机器人闯关', '15:30'), allowedActions: ['RELEASE', 'REJECT'],
    })
    const earlier = [record('李**', 1, '3762', '14:02'), record('赵**', 2, '6410', '14:01', { method: 'MANUAL' })]
    const morning = [record('钱**', 2, '7731', '10:03', { ticketSession: ref(S1000, '模拟飞行', '10:00'), currentSession: ref(S1000, '模拟飞行', '10:00') }),
      record('吴**', 1, '2946', '09:58', { ticketSession: ref(S1000, '模拟飞行', '10:00'), currentSession: ref(S1000, '模拟飞行', '10:00') })]
    const repeated = record('李**', 1, '3762', '14:06', {
      outcome: 'REPEATED', firstCheckedInAt: at('14:02'), allowedActions: [],
      resolution: { action: 'BLOCK', identityChecked: false, note: null, resolvedAt: at('14:06'), admittedSession: null },
    })
    let recordsNow: unknown[] = earlier.concat(morning)
    let checked = 3
    let verifyReply: { status: number; body: unknown } = { status: 200, body: wang }

    const page = await signedInPage(browser, organizer)
    await page.route('**/api/v1/checkin-workbench**', async (route: Route) => {
      const url = new URL(route.request().url())
      const json = (status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
      if (url.pathname.endsWith('/verifications')) {
        const reply = verifyReply
        if (reply.status === 200) {
          checked += 1
          recordsNow = [reply.body].concat(recordsNow)
        } else {
          recordsNow = [mismatch].concat(recordsNow)
        }
        return json(reply.status, reply.body)
      }
      if (url.pathname.endsWith('/resolution')) {
        const settled = { ...mismatch, allowedActions: [], resolution: {
          action: 'RELEASE', identityChecked: true, note: '现场核对', resolvedAt: at('14:09'), admittedSession: ref(S1400, '机器人避障闯关', '14:00') } }
        recordsNow = recordsNow.map((item) => ((item as { recordId: string }).recordId === mismatch.recordId ? settled : item))
        return json(200, settled)
      }
      if (url.pathname.includes('/records/')) return json(200, mismatch)
      if (url.pathname.endsWith('/records')) return json(200, { date: day, items: recordsNow })
      return json(200, workbench(checked))
    })
    mkdirSync(compareOutput, { recursive: true })
    mkdirSync(visualOutput, { recursive: true })

    // StaffVerify：核销成功。
    await openWorkbench(page)
    await expect(page.getByTestId(`staff-session-VENUE:${S1400}`)).toHaveAttribute('aria-pressed', 'true')
    await typeCode(page, 'VEN-7K2M-9QXD-0314')
    await expect(page.getByTestId('staff-verify-ok')).toContainText('王** · 机器人避障闯关 14:00 场')
    await expect(page.getByTestId('staff-verify-ok')).toContainText('1 人 · 已支付 ¥39')
    await expect(page.getByTestId('staff-verify-recent-row')).toHaveCount(3)
    await page.screenshot({ path: resolve(compareOutput, 'StaffVerify-app.png'), fullPage: true })

    // 场次不符。
    verifyReply = { status: 409, body: { code: 'CHECKIN_SESSION_MISMATCH', message: '凭证不属于当前核销场次', traceId: 't', details: { record: mismatch } } }
    await typeCode(page, 'VEN-7K2M-9QXD-0815')
    await expect(page.getByTestId('staff-verify-failed-line')).toHaveText('场次不符：凭证为 15:30 机器人闯关场')
    await page.screenshot({ path: resolve(visualOutput, 'verify-mismatch-390.png'), fullPage: true })

    // StaffException：改签并核销，要先勾选已人工核验身份。
    await page.getByTestId('staff-verify-handle').click()
    await expect(page.getByTestId('staff-exception-title')).toHaveText('场次不符，需人工核验')
    await expect(page.getByTestId('staff-exception-fact')).toHaveText('凭证为 15:30 场，当前核销 14:00 场')
    await page.getByTestId('staff-exception-option-RELEASE').click()
    await expect(page.getByTestId('staff-exception-confirm')).toHaveAttribute('disabled', /.*/)
    await page.getByTestId('staff-exception-identity').click()
    await page.screenshot({ path: resolve(compareOutput, 'StaffException-app.png'), fullPage: true })
    await expect(page.locator('[data-testid="staff-exception-confirm"]:not([disabled])')).toHaveCount(1)
    await page.getByTestId('staff-exception-confirm').click()
    await expect(page.getByTestId('staff-exception-done-act')).toHaveText('处理：改签并核销（登记放行）')
    await page.screenshot({ path: resolve(visualOutput, 'exception-done-390.png'), fullPage: true })

    // StaffRecords：按场次分组，异常行和详情。
    recordsNow = recordsNow.concat([repeated])
    await page.goto('/#/pages/staff/records')
    await expect(page.getByTestId('staff-records-group')).toHaveCount(3)
    await expect(page.getByTestId('staff-records-summary')).toContainText('今日已核销')
    await page.screenshot({ path: resolve(compareOutput, 'StaffRecords-app.png'), fullPage: true })
    await page.getByTestId('staff-records-search').locator('input').fill('3762')
    await expect(page.locator('[data-testid^="staff-record-b8"]')).toHaveCount(2)
    await page.getByTestId(`staff-record-${repeated.recordId}`).click()
    await expect(page.getByTestId('staff-records-sheet')).toContainText('重复核销（该凭证已于 14:02 核销）')
    await page.screenshot({ path: resolve(visualOutput, 'records-sheet-390.png') })

    // 三个页面在 320 / 375 / 430 都不横向溢出。
    for (const width of [320, 375, 430]) {
      await page.setViewportSize({ width, height: 844 })
      for (const path of ['/#/pages/staff/verify', '/#/pages/staff/records', `/#/pages/staff/exception?kind=VENUE&recordId=${mismatch.recordId}`]) {
        await page.goto(path)
        await page.waitForTimeout(600)
        await assertNoHorizontalOverflow(page)
      }
      await page.goto('/#/pages/staff/verify')
      await expect(page.getByTestId('staff-verify-badge')).toBeVisible()
      await page.screenshot({ path: resolve(visualOutput, `verify-${width}.png`), fullPage: true })
    }

    // 画板 390 宽和 App 390 宽并排。
    for (const board of ['StaffVerify', 'StaffRecords', 'StaffException', 'Mine']) {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(`file://${resolve(boardDir, `${board}.html`)}`)
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
    await page.context().close()
  })
})
