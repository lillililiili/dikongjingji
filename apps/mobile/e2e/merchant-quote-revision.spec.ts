import { randomUUID } from 'node:crypto'
import { expect, test, type APIResponse, type Page } from '@playwright/test'
import { openSignedIn, randomPhone, signUpByApi } from './support/account-fixtures'
import { testAdmin } from './support/test-stack'
import { addAddressByApi, API, apiLogin, CITY, devPassword, fakeWechatPays, MERCHANT, offerId, openPaymentNo, psql } from './support/trade-fixtures'

test.use({ trace: 'off', viewport: { width: 390, height: 844 } })
test.describe.configure({ timeout: 90_000 })

async function json(response: APIResponse) {
  const body = await response.json()
  expect(response.ok(), `${response.status()} ${new URL(response.url()).pathname} ${body?.code ?? ''}`).toBe(true)
  return body
}

async function signIn(page: Page, path: string, login: string) {
  await page.setViewportSize({ width: 1366, height: 950 })
  await page.goto(testAdmin + path)
  await page.locator('input[autocomplete="username"]').fill(login)
  await page.locator('input[autocomplete="current-password"]').fill(devPassword())
  await page.locator('form').getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(url => url.pathname === path)
}

async function confirm(page: Page, label: string) {
  await page.locator('.el-message-box').getByRole('button', { name: label, exact: true }).click()
}

const deadline = () => new Date(Date.now() + 36 * 3_600_000).toISOString()

test('需求改价保留有效期，选定后确认方案、付款、服务商开工交付，客户验收结单', async ({ page, context, request }) => {
  test.setTimeout(180_000)
  const buyer = await signUpByApi(request, randomPhone())
  const provider = await apiLogin(request, 'dev-provider')
  expect(provider).toBeTruthy()
  const created = await json(await request.post(API + '/api/v1/demands', {
    headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() },
    data: { submit: true, type: 'AERIAL', cityCode: CITY, address: '东营验收园区', scope: '园区航拍',
      deliverables: ['VIDEO_FOOTAGE'] },
  }))
  const path = '/api/v1/provider/demands/' + created.demandId + '/quote'
  const validUntil = deadline()
  await json(await request.put(API + path, { headers: provider!.headers, data: {
    amountMinor: 123400, method: '现场拍摄', schedule: '下周一进场', durationDays: 1, deliverables: '成片', validUntil,
  } }))
  await signIn(page, '/workbench/demands/' + created.demandId, 'dev-provider')
  await page.getByRole('button', { name: '改价', exact: true }).click()
  const form = page.getByRole('dialog')
  await form.getByPlaceholder('比如 12800').fill('1258')
  await form.getByRole('button', { name: '提交改价' }).click()
  const revised = page.waitForResponse(r => r.url().endsWith(path) && r.request().method() === 'PUT')
  await confirm(page, '提交改价')
  const response = await revised
  expect(response.ok()).toBe(true)
  const { myQuote } = await response.json()
  expect(new Date(myQuote.validUntil).toISOString()).toBe(validUntil)
  const mobile = await context.newPage()
  await openSignedIn(mobile, buyer.token, '/pages/demand/quotes?demandId=' + created.demandId)
  await expect(mobile.getByTestId('demand-quote-price')).toContainText(/1,?258/)
  const current = await json(await request.get(API + '/api/v1/demands/' + created.demandId, { headers: buyer.headers }))
  const chosen = await json(await request.post(API + '/api/v1/demands/' + created.demandId + '/choice', {
    headers: { ...buyer.headers, 'If-Match': `"${current.version}"` }, data: { quoteId: myQuote.quoteId, quoteVersion: myQuote.version },
  }))
  expect(chosen.serviceOrderId).toBeTruthy()
  await page.goto(testAdmin + '/workbench/service-orders/' + chosen.serviceOrderId)
  await page.getByRole('button', { name: '出方案', exact: true }).click()
  await form.locator('.el-form-item').filter({ hasText: '交付约定' }).locator('input').fill('交付园区全景航拍成片')
  await form.getByRole('button', { name: '出方案' }).click()
  await confirm(page, '出方案')
  await expect(page.getByRole('dialog', { name: '出方案', exact: true })).toBeHidden()
  await openSignedIn(mobile, buyer.token, '/pages/service-order/plan?orderId=' + chosen.serviceOrderId)
  await expect(mobile.getByTestId('service-order-plan-terms')).toContainText('交付园区全景航拍成片')
  await mobile.getByTestId('service-order-plan-agree').click()
  await mobile.getByTestId('service-order-plan-confirm').click()
  const payment = mobile.waitForResponse(r => r.url().endsWith('/service-orders/' + chosen.serviceOrderId + '/payments'))
  await mobile.getByTestId('service-order-plan-pay').click()
  const start = await payment
  expect(start.ok()).toBe(true)
  const { tradeOrderId } = await start.json()
  await expect(mobile.getByTestId('cashier-head')).toBeVisible()
  await json(await request.post(API + '/api/v1/trade/orders/' + tradeOrderId + '/payments', {
    headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() }, data: { channel: 'WECHAT_APP' },
  }))
  await fakeWechatPays(request, openPaymentNo(tradeOrderId))
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await page.getByRole('button', { name: '标记开工', exact: true }).click()
  await confirm(page, '标记开工')
  const file = Buffer.from('%PDF-1.4\naerial survey report\n%%EOF')
  await page.locator('input[type=file]').setInputFiles({ name: 'aerial-report.pdf', mimeType: 'application/pdf', buffer: file })
  await expect(page.getByRole('cell', { name: 'aerial-report.pdf' })).toBeVisible()
  await page.getByRole('button', { name: '提交交付', exact: true }).click()
  const delivery = page.getByRole('dialog', { name: '提交交付（第 1 次）' })
  const description = '园区全景航拍已完成，报告见附件。'
  await delivery.getByPlaceholder(/做了什么/).fill(description)
  await delivery.getByRole('button', { name: '提交交付', exact: true }).click()
  await confirm(page, '提交交付')
  await expect(delivery).toBeHidden()
  await openSignedIn(mobile, buyer.token, '/pages/service-order/delivery?orderId=' + chosen.serviceOrderId)
  await expect(mobile.getByTestId('service-order-delivery-note')).toContainText(description)
  await expect(mobile.getByTestId('service-order-delivery-files')).toContainText('aerial-report.pdf')
  const download = mobile.waitForResponse(r => r.url().endsWith('/download-intents') && r.request().method() === 'POST')
  await mobile.locator('[data-testid^="service-order-download-"]').click()
  const intent = await download
  expect(intent.ok()).toBe(true)
  const temporary = await intent.json()
  expect(temporary.url).toMatch(/^\/api\/v1\/service-order-files\//)
  expect(await (await request.get(API + temporary.url)).body()).toEqual(file)
  await mobile.getByTestId('service-order-accept').click()
  await mobile.getByTestId('service-order-accept-sheet-go').click()
  await expect(mobile.getByTestId('service-order-delivery-accepted')).toContainText('第 1 次已验收')
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.locator('.el-tag').filter({ hasText: /^已完成$/ }).first()).toBeVisible()
})

test('租赁改价保留有效期，后台登记交接和归还后，客户在 App 确认至合同结束', async ({ page, context, request }) => {
  const buyer = await signUpByApi(request, randomPhone())
  const merchant = await apiLogin(request, MERCHANT)
  expect(merchant).toBeTruthy()
  const addressId = await addAddressByApi(request, buyer)
  const { inquiryId } = await json(await request.post(API + '/api/v1/rental-inquiries', {
    headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() },
    data: { offerId: offerId('401', '1'), quantity: 1, days: 2, startDate: deadline().slice(0, 10),
      handoverMode: 'DELIVERY', returnMode: 'COLLECT', addressId },
  }))
  const path = '/api/v1/provider/rental-inquiries/' + inquiryId + '/quote'
  const validUntil = deadline()
  await json(await request.put(API + path, { headers: merchant!.headers, data: {
    dailyRentMinor: 10000, days: 2, quantity: 1, fees: [], scheduleAvailable: true, validUntil, terms: '按时归还',
  } }))
  await signIn(page, '/workbench/rental-inquiries/' + inquiryId, MERCHANT)
  await page.getByRole('button', { name: '改价', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByPlaceholder('比如 380').fill('125')
  await dialog.getByRole('button', { name: '提交改价' }).click()
  const revised = page.waitForResponse(r => r.url().endsWith(path) && r.request().method() === 'PUT')
  await confirm(page, '提交改价')
  const response = await revised
  expect(response.ok()).toBe(true)
  const { quote, version } = await response.json()
  expect(new Date(quote.validUntil).toISOString()).toBe(validUntil)
  const mobile = await context.newPage()
  await openSignedIn(mobile, buyer.token, '/pages/rental/quote?inquiryId=' + inquiryId)
  await expect(mobile.getByTestId('rental-quote-total')).toContainText('250')
  await expect(mobile.getByTestId('rental-quote-terms')).toContainText('按时归还')
  const order = await json(await request.post(API + '/api/v1/rental-inquiries/' + inquiryId + '/confirm', {
    headers: { ...buyer.headers, 'If-Match': `"${version}"` }, data: { quoteVersion: quote.version, agreed: true },
  }))
  await json(await request.post(API + '/api/v1/trade/orders/' + order.tradeOrderId + '/payments', {
    headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() }, data: { channel: 'WECHAT_APP' },
  }))
  await fakeWechatPays(request, openPaymentNo(order.tradeOrderId))
  await page.goto(testAdmin + '/workbench/rental-orders/' + order.rentalOrderId)
  await openSignedIn(mobile, buyer.token, '/pages/rental/order?rentalOrderId=' + order.rentalOrderId)
  const steps = [['交接', '设备齐全，双方清点交付', 'IN_USE'], ['归还', '设备归还完好', 'ENDED']] as const
  for (const [label, note, after] of steps) {
    await page.getByRole('button', { name: '刷新', exact: true }).click()
    await page.getByRole('button', { name: '录入' + label, exact: true }).click()
    const record = page.getByRole('dialog', { name: '录入' + label + '记录', exact: true })
    await record.getByPlaceholder(/设备编号、配件/).fill(note)
    await record.getByRole('button', { name: '提交' }).click()
    await confirm(page, '提交')
    await expect(record).toBeHidden()
    await mobile.reload()
    await mobile.getByTestId('rental-order-action').click()
    await expect(mobile.getByTestId('rental-confirm-rows')).toContainText(note)
    await mobile.getByTestId('rental-confirm-go').click()
    await expect(mobile.getByTestId('rental-order')).toHaveAttribute('data-status', after)
  }
})

test('商家报价、修改商品单价，App 买家比较后选定最新报价', async ({ page, context, request }) => {
  const buyer = await signUpByApi(request, randomPhone())
  const selected = offerId('203', '1')
  const { inquiryId } = await json(await request.post(API + '/api/v1/mall/inquiries', {
    headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() }, data: {
      productId: 'c2000000-0000-4000-8000-000000000203', cityCode: CITY, requirement: '测绘无人机',
      quantity: 2, expectedDate: null, offerIds: [selected] },
  }))
  await signIn(page, '/workbench/mall-inquiries/' + inquiryId, MERCHANT)
  const dialog = page.getByRole('dialog')
  await page.getByRole('button', { name: '报价', exact: true }).click()
  await dialog.getByPlaceholder('单位：元').fill('698')
  await dialog.getByPlaceholder(/M350/).fill('测绘版 RTK')
  await dialog.getByPlaceholder(/付款后几天交付/).fill('2')
  await dialog.getByRole('button', { name: '报价', exact: true }).click()
  await confirm(page, '确认报价')
  await page.getByRole('button', { name: '改价', exact: true }).click()
  await expect(dialog.getByPlaceholder('单位：元')).toHaveValue('698.00')
  await dialog.getByPlaceholder('单位：元').fill('599')
  await dialog.getByRole('button', { name: '改价', exact: true }).click()
  await confirm(page, '确认改价')
  await expect(page.getByRole('dialog', { name: /^改价：/ })).toBeHidden()
  const mobile = await context.newPage()
  await openSignedIn(mobile, buyer.token, '/pages/mall/inquiry-quotes?inquiryId=' + inquiryId)
  const card = mobile.getByTestId('inquiry-quote-' + selected)
  await expect(card.getByTestId('inquiry-quote-total')).toContainText(/1,?198/)
  await card.getByTestId('inquiry-quote-choose').click()
  await mobile.getByTestId('inquiry-confirm-go').click()
  await expect(mobile.getByTestId('inquiry-quotes')).toHaveAttribute('data-status', 'CHOSEN')
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByText(/客户选中了你们的报价/)).toBeVisible()
})

test('机构修改直播预告，App 预告页展示新的讲解主题', async ({ page, context, request }) => {
  const merchant = await apiLogin(request, MERCHANT)
  expect(merchant).toBeTruthy()
  const title = '验收直播 ' + randomUUID().slice(0, 8)
  const { room } = await json(await request.post(API + '/api/v1/live-host/rooms', {
    headers: { ...merchant!.headers, 'Idempotency-Key': randomUUID() },
    data: { title, cityCode: CITY, scheduledAt: deadline(), speakerName: '讲师', topics: ['直播主题'] },
  }))
  await signIn(page, '/workbench/live-rooms/' + room.roomId, MERCHANT)
  await page.getByRole('button', { name: '加一项' }).click()
  await page.getByPlaceholder('对象 ID 或分享链接').fill('c2000000-0000-4000-8000-000000000203')
  await page.getByPlaceholder('标题（≤40 字）').fill('测试测绘设备')
  const replay = page.getByPlaceholder(/合作平台的 https 回看链接/)
  await replay.fill('https://example.com/replay')
  await page.getByPlaceholder(/平台名/).fill('验收平台')
  await page.getByRole('button', { name: '改预告', exact: true }).click()
  const dialog = page.getByRole('dialog')
  const topic = '改为讲解实地巡检 ' + randomUUID().slice(0, 8)
  await dialog.getByPlaceholder('一行一条，最多 5 条，每条 ≤40 字').fill(topic)
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await confirm(page, '保存')
  await expect(page.getByRole('dialog', { name: '改预告', exact: true })).toBeHidden()
  await expect(page.getByPlaceholder('标题（≤40 字）')).toHaveValue('测试测绘设备')
  await page.getByRole('button', { name: '保存讲解清单', exact: true }).click()
  await confirm(page, '确认')
  await expect(page.getByRole('button', { name: '1. 商品 · 测试测绘设备' })).toBeVisible()
  await expect(replay).toHaveValue('https://example.com/replay')
  const mobile = await context.newPage()
  await mobile.goto('/#/pages/live/notice?roomId=' + room.roomId + '&cityCode=' + CITY)
  await expect(mobile.getByText(topic, { exact: true })).toBeVisible()
})

test('巡检方案修订、排班、现场进度，按已确认方案上传 PDF 后由客户在 App 验收', async ({ page, context, request }) => {
  test.setTimeout(180_000)
  const buyer = await signUpByApi(request, randomPhone())
  const provider = await apiLogin(request, 'dev-provider')
  expect(provider).toBeTruthy()
  const serviceId = '33333333-3333-3333-3333-333333333333'
  const startDate = new Date(Date.now() + 10 * 86_400_000).toISOString().slice(0, 10)
  const days: Array<{ serviceDate: string, slots: Array<{ slotCode: string, availability: string }> }> =
    await json(await request.get(API + '/api/v1/inspection/services/' + serviceId + '/available-time-slots', {
      headers: buyer.headers, params: { cityCode: CITY, startDate, days: 10 },
    }))
  const available = days.flatMap(day => day.slots.filter(slot => slot.availability === 'AVAILABLE')
    .map(slot => ({ preferredServiceDate: day.serviceDate, preferredTimeSlotCode: slot.slotCode })))[0]
  if (!available) throw new Error('隔离测试城市未来十天没有可预约的巡检时段')
  const { preferredServiceDate, preferredTimeSlotCode } = available
  const reservation = await json(await request.post(API + '/api/v1/inspection/reservations', {
    headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() },
    data: { serviceId, cityCode: CITY,
      location: { address: '东营巡检园区', longitude: 118.5, latitude: 37.4 }, lineLengthMeters: 500,
      preferredServiceDate, preferredTimeSlotCode, inspectionItemCodes: ['POWER_LINE'],
      attachmentFileIds: [], contact: { name: '验收员', mobile: '13800000000' }, agreementVersion: '2026-07', agreementAccepted: true },
  }))
  const path = '/api/v1/provider/inspection/reservations/' + reservation.id + '/plans'
  const draft = { providerId: reservation.providerId, validUntil: deadline(), visits: [{ plannedDate: preferredServiceDate }],
    summary: '园区专项巡检', scopeSummary: '检查配电设备', serviceMethods: [{ code: 'THERMAL', label: '红外巡检' }],
    deliveryTerms: { reportDueHours: 25, mediaRetentionDays: 91 }, currency: 'CNY',
    planItems: [{ code: 'THERMAL', name: '设备热成像', quantity: 2, unit: '组', unitPriceMinor: 10250 }],
    deliverables: [{ code: 'REPORT', name: '设备温度报告', fileType: 'INSPECTION_REPORT' }] }
  const first = await json(await request.post(API + path, { headers: {
    ...provider!.headers, 'Idempotency-Key': randomUUID(), 'If-Match': `"${reservation.version}"`,
  }, data: draft }))
  await signIn(page, '/workbench/inspection/' + reservation.id, 'dev-provider')
  await page.getByRole('button', { name: '再发一版方案' }).click()
  const dialog = page.getByRole('dialog', { name: '再发一版方案', exact: true })
  await expect(dialog.getByPlaceholder('单价（元）')).toHaveValue('102.50')
  const note = '复核报价无变化，补充上门说明'
  await dialog.getByPlaceholder('这一版改了什么，客户会看到').fill(note)
  await dialog.getByRole('button', { name: '发布方案' }).click()
  const published = page.waitForResponse(r => r.url().endsWith(path) && r.request().method() === 'POST')
  await confirm(page, '发布')
  const revised = await published
  expect(revised.ok()).toBe(true)
  const plan = await revised.json()
  for (const field of ['validUntil', 'visits', 'summary', 'scopeSummary', 'serviceMethods', 'deliveryTerms', 'planItems', 'deliverables', 'totalAmountMinor']) {
    expect(plan[field], field).toEqual(first[field])
  }
  expect(plan.planVersion).toBe(2)
  const mobile = await context.newPage()
  await openSignedIn(mobile, buyer.token, '/pages/service/inspection/plan-confirmation?planId=' + first.id)
  await expect(mobile.getByText(note, { exact: false }).first()).toBeVisible()
  const order = await json(await request.post(API + '/api/v1/inspection/plans/' + plan.id + '/confirm', {
    headers: { ...buyer.headers, 'If-Match': `"${plan.version}"`, 'Idempotency-Key': randomUUID() },
    data: { agreementVersion: 'inspection-plan-v2', agreementAccepted: true },
  }))
  const orderPath = API + '/api/v1/inspection/service-orders/' + order.id
  async function payNode(node: number) {
    const { tradeOrderId } = await json(await request.post(orderPath + '/payment-nodes/' + node + '/payment', { headers: buyer.headers }))
    await json(await request.post(API + '/api/v1/trade/orders/' + tradeOrderId + '/payments', {
      headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() }, data: { channel: 'WECHAT_APP' },
    }))
    await fakeWechatPays(request, openPaymentNo(tradeOrderId))
  }
  await payNode(1)
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await page.getByRole('button', { name: '安排人员和设备' }).click()
  const team = page.getByRole('dialog', { name: '安排人员和设备' })
  await team.getByPlaceholder('姓名或班组').fill('巡检测试班组')
  await team.getByPlaceholder(/设备名称/).fill('热成像设备')
  await team.getByRole('button', { name: '保存', exact: true }).click()
  await confirm(page, '保存')
  await expect(team).toBeHidden()
  for (const action of ['排期', '开始巡检', '记一条进度', '现场完成']) {
    await page.getByRole('button', { name: action, exact: true }).click()
    const title = '第 1 次巡检：' + action
    const step = page.locator('.el-dialog').filter({ has: page.getByText(title, { exact: true }) })
    if (action === '记一条进度') await step.getByPlaceholder(/已完成一半/).fill('配电设备已复核')
    await step.getByRole('button', { name: '确认', exact: true }).click()
    await confirm(page, '确认')
    await expect(step).toBeHidden()
  }
  await page.getByRole('button', { name: '上传交付并发布' }).click()
  const delivery = page.getByRole('dialog', { name: '第 1 次巡检：上传交付并发布' })
  await expect(delivery.locator('input[type=file]')).toHaveCount(1)
  await delivery.locator('input[type=file][accept=".pdf"]').setInputFiles({
    name: '温度报告.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\ninspection report\n%%EOF'),
  })
  await delivery.locator('.el-form-item').filter({ hasText: '巡检结果' }).getByRole('spinbutton').first().fill('500')
  await delivery.getByRole('button', { name: '上传并发布', exact: true }).click()
  const publishDelivery = page.waitForResponse(r => r.url().endsWith('/delivery') && r.request().method() === 'POST')
  await confirm(page, '上传并发布')
  expect((await publishDelivery).ok()).toBe(true)
  await openSignedIn(mobile, buyer.token, '/pages/service/inspection/delivery?serviceOrderId=' + order.id + '&visitId=' + order.visits[0].id)
  await mobile.locator('.delivery__accept').click()
  await mobile.locator('.acceptance-sheet__confirm').click()
  await expect(mobile.getByTestId('delivery-pay')).toBeVisible()
  await payNode(2)
  await expect.poll(async () => (await json(await request.get(orderPath, { headers: buyer.headers }))).status).toBe('ACCEPTED')
})

test('机构为已签到的活动参与人录入并更正成绩，客户 App 读取最终结果', async ({ page, context, request }) => {
  const buyer = await signUpByApi(request, randomPhone())
  const provider = await apiLogin(request, 'dev-provider')
  expect(provider).toBeTruthy()
  const activityId = randomUUID()
  const sessionId = randomUUID()
  // 专用场次允许测试控制“报名开放、签到、活动结束”三个时点。
  psql(`INSERT INTO activity (id, organizer_id, type, title, city_code, venue_name, venue_address, price_mode, status)
    SELECT '${activityId}', default_organization_id, 'OPEN_DAY', '成绩录入验收', '370500', '体验馆', '东营园区', 'FREE', 'PUBLISHED'
    FROM identity_user WHERE login_name='dev-provider';
    INSERT INTO activity_session (id, activity_id, starts_at, ends_at, check_in_opens_at, enrollment_closes_at, capacity)
    VALUES ('${sessionId}', '${activityId}', now()+interval '20 minutes', now()+interval '1 hour',
      now()-interval '10 minutes', now()+interval '15 minutes', 3);`)
  const people = await json(await request.get(API + '/api/v1/activity-participants', { headers: buyer.headers }))
  const enrollment = await json(await request.post(API + '/api/v1/activity-enrollments', {
    headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() }, data: {
      activityId, sessionId, participantIds: [people.items[0].participantId], noticeAgreed: true },
  }))
  const ticket = enrollment.tickets[0]
  await json(await request.post(API + '/api/v1/activity-checkins', { headers: provider!.headers, data: { ticketCode: ticket.ticketCode } }))
  psql(`UPDATE activity_session SET starts_at=now()-interval '2 hours', ends_at=now()-interval '1 hour',
    check_in_opens_at=now()-interval '3 hours', enrollment_closes_at=now()-interval '2 hours' WHERE id='${sessionId}';`)
  await signIn(page, '/workbench/activity-results/' + sessionId, 'dev-provider')
  await expect(page.getByRole('button', { name: '录入结果' })).toBeVisible()
  await context.setOffline(true)
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  const retry = page.getByRole('button', { name: '重新加载' })
  await expect(retry).toBeVisible()
  await expect(page.getByRole('button', { name: '录入结果' })).toHaveCount(0)
  await context.setOffline(false)
  await retry.click()
  await page.getByRole('button', { name: '录入结果' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByPlaceholder(/完成任务/).fill('4/5')
  await dialog.getByRole('button', { name: '录入', exact: true }).click()
  await page.getByRole('button', { name: '修改结果' }).click()
  await expect(dialog.getByPlaceholder(/完成任务/)).toHaveValue('4/5')
  await dialog.getByPlaceholder(/完成任务/).fill('5/5')
  await dialog.getByPlaceholder(/完赛证书/).fill('已发放完赛证书')
  await dialog.getByRole('button', { name: '保存修改' }).click()
  await expect(dialog).toBeHidden()
  const mobile = await context.newPage()
  await openSignedIn(mobile, buyer.token, '/pages/activity/result?enrollmentId=' + enrollment.enrollmentId)
  await expect(mobile.getByTestId('activity-result-record-' + ticket.ticketId)).toContainText('5/5')
  await expect(mobile.getByTestId('activity-result-certificate')).toContainText('已发放完赛证书')
})
