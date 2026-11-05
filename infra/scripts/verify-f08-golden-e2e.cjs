const { createHash, randomUUID } = require('node:crypto')
const { mkdirSync } = require('node:fs')
const { resolve } = require('node:path')

const playwrightModule = process.env.INSPECTION_PLAYWRIGHT_MODULE
if (!playwrightModule) {
  throw new Error('Set INSPECTION_PLAYWRIGHT_MODULE to an installed Playwright module directory.')
}
const { chromium } = require(playwrightModule)

const appUrl = process.env.INSPECTION_APP_URL || 'http://127.0.0.1:4173'
const apiUrl = process.env.INSPECTION_API_URL || 'http://127.0.0.1:8080'
const browserExecutable = process.env.INSPECTION_BROWSER_EXECUTABLE
  || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const evidenceDir = resolve(process.env.INSPECTION_E2E_EVIDENCE_DIR || 'tmp/e2e/f08')
const providerId = '22222222-2222-2222-2222-222222222222'

for (const value of [appUrl, apiUrl]) {
  const url = new URL(value)
  if (!['127.0.0.1', 'localhost'].includes(url.hostname)) {
    throw new Error('F08 golden E2E is intentionally limited to loopback development services.')
  }
}

function headers(token, extra = {}) {
  return {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    'X-Request-Id': randomUUID(),
    ...extra,
  }
}

async function requestJson(path, { token, method = 'GET', body, extraHeaders } = {}) {
  const response = await fetch(`${apiUrl}${path}`, {
    method,
    headers: headers(token, {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(extraHeaders || {}),
    }),
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (!response.ok) {
    throw new Error(`${method} ${path} failed with ${response.status}: ${await response.text()}`)
  }
  return response.status === 204 ? null : response.json()
}

async function login(loginName) {
  return requestJson('/api/v1/auth/login', {
    method: 'POST',
    body: { loginName, password: 'test-password' },
  })
}

function planBody({ visitDates, changeNote = null, withNightPatrol = false }) {
  return {
    providerId,
    validUntil: new Date(Date.now() + 2 * 86400000).toISOString(),
    visits: visitDates.map((plannedDate) => ({ plannedDate })),
    summary: '无人机采集、专业复核并交付巡检报告',
    scopeSummary: '线路约 12 公里',
    serviceMethods: [{ code: 'DRONE', label: '无人机巡检' }],
    deliveryTerms: { reportDueHours: 48, mediaRetentionDays: 90 },
    changeNote,
    planItems: [
      {
        code: 'POWER_LINE',
        name: '输电线路巡检',
        quantity: 12,
        unit: '公里',
        unitPriceMinor: 69900,
      },
      ...(withNightPatrol
        ? [{ code: 'NIGHT', name: '夜间复巡', quantity: 1, unit: '项', unitPriceMinor: 120000 }]
        : []),
    ],
    deliverables: [
      { code: 'HD_MEDIA', name: '高清影像' },
      { code: 'DEFECT_LIST', name: '缺陷清单' },
      { code: 'POINT_MARKS', name: '点位标记' },
      { code: 'REPORT', name: '巡检报告' },
    ],
    currency: 'CNY',
  }
}

async function publishPlan(reservationId, providerToken, reservationVersion, body) {
  return requestJson(`/api/v1/provider/inspection/reservations/${reservationId}/plans`, {
    token: providerToken,
    method: 'POST',
    extraHeaders: {
      'Idempotency-Key': randomUUID(),
      'If-Match': `"${reservationVersion}"`,
    },
    body,
  })
}

function readOrder(serviceOrderId, token) {
  return requestJson(`/api/v1/inspection/service-orders/${serviceOrderId}`, { token })
}

async function arrangeOrder(serviceOrderId, providerToken) {
  await requestJson(`/api/v1/provider/inspection/service-orders/${serviceOrderId}/arrangement`, {
    token: providerToken,
    method: 'PUT',
    extraHeaders: {
      'Idempotency-Key': randomUUID(),
      'If-Match': '"0"',
    },
    body: {
      assignments: [
        {
          type: 'TEAM_MEMBER',
          resourceId: '77777777-7777-4777-8777-777777777701',
          displayName: '东营巡检作业组',
          roleLabel: '项目负责人',
          contactMobile: '13812346621',
        },
        {
          type: 'TEAM_MEMBER',
          resourceId: '77777777-7777-4777-8777-777777777703',
          displayName: '飞手甲',
          roleLabel: '持证飞手',
        },
        {
          type: 'DEVICE',
          resourceId: '77777777-7777-4777-8777-777777777702',
          displayName: 'M350 RTK + H30T',
          roleLabel: null,
        },
      ],
    },
  })
}

function visitRoute(serviceOrderId, visit) {
  return `/api/v1/provider/inspection/service-orders/${serviceOrderId}/visits/${visit.id}`
}

async function scheduleVisit(serviceOrderId, visit, providerToken) {
  return requestJson(`${visitRoute(serviceOrderId, visit)}/schedule`, {
    token: providerToken,
    method: 'PUT',
    extraHeaders: {
      'Idempotency-Key': randomUUID(),
      'If-Match': `"${visit.version}"`,
    },
    body: { plannedStartAt: new Date(Date.now() + 86400000).toISOString() },
  })
}

async function appendProgress(serviceOrderId, visit, providerToken, eventCode, message) {
  return requestJson(`${visitRoute(serviceOrderId, visit)}/progress-events`, {
    token: providerToken,
    method: 'POST',
    extraHeaders: {
      'Idempotency-Key': randomUUID(),
      'If-Match': `"${visit.version}"`,
    },
    body: { eventCode, occurredAt: new Date().toISOString(), message },
  })
}

async function uploadDeliveryFile(visitId, providerToken, file) {
  const sha256 = createHash('sha256').update(file.content).digest('hex')
  const intent = await requestJson('/api/v1/inspection/file-objects/upload-intents', {
    token: providerToken,
    method: 'POST',
    extraHeaders: { 'Idempotency-Key': randomUUID() },
    body: {
      purpose: 'DELIVERY_ARTIFACT',
      ownerId: visitId,
      deliveryFileType: file.type,
      originalName: file.name,
      contentType: file.contentType,
      sizeBytes: file.content.length,
      sha256,
    },
  })
  const upload = await fetch(intent.uploadUrl, {
    method: 'PUT',
    headers: {
      ...(intent.requiredHeaders || {}),
      'Content-Type': file.contentType,
    },
    body: file.content,
  })
  if (!upload.ok) throw new Error(`MinIO upload failed with ${upload.status}`)

  const completed = await requestJson(`/api/v1/inspection/file-objects/${intent.fileId}/complete`, {
    token: providerToken,
    method: 'POST',
    extraHeaders: { 'Idempotency-Key': randomUUID() },
    body: {
      etag: upload.headers.get('etag') || `f08-${intent.fileId}`,
      sizeBytes: file.content.length,
      sha256,
    },
  })
  if (completed.status !== 'READY') {
    throw new Error(`${file.name} did not become READY`)
  }
  return {
    fileId: intent.fileId,
    type: file.type,
    displayName: file.name,
    ...(file.pointCount === undefined ? {} : { pointCount: file.pointCount }),
    ...(file.itemCount === undefined ? {} : { itemCount: file.itemCount }),
  }
}

async function publishDelivery(serviceOrderId, visit, providerToken) {
  const definitions = [
    {
      type: 'IMAGE_ARCHIVE',
      name: '现场影像.zip',
      contentType: 'application/zip',
      content: Buffer.from(`PK\u0003\u0004F08 inspection images ${visit.id}`),
      itemCount: 36,
    },
    {
      type: 'DEFECT_LIST',
      name: '缺陷清单.xlsx',
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      content: Buffer.from(`PK\u0003\u0004F08 defect workbook ${visit.id}`),
    },
    {
      type: 'POINT_KML',
      name: '巡检点位.kml',
      contentType: 'application/vnd.google-earth.kml+xml',
      content: Buffer.from(`<?xml version="1.0" encoding="UTF-8"?><kml><Document><name>${visit.id}</name></Document></kml>`),
      pointCount: 24,
    },
    {
      type: 'INSPECTION_REPORT',
      name: '巡检报告.pdf',
      contentType: 'application/pdf',
      content: Buffer.from(`%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n% ${visit.id}\n%%EOF`),
    },
  ]
  const files = []
  for (const definition of definitions) {
    files.push(await uploadDeliveryFile(visit.id, providerToken, definition))
  }

  return requestJson(`${visitRoute(serviceOrderId, visit)}/delivery`, {
    token: providerToken,
    method: 'POST',
    extraHeaders: {
      'Idempotency-Key': randomUUID(),
      'If-Match': `"${visit.version}"`,
    },
    body: {
      towerCount: 24,
      inspectedLengthMeters: 12000,
      metrics: [{ code: 'COVERED_AREA', label: '巡检覆盖', value: 42, unit: '万㎡' }],
      issueCount: 3,
      issueGroupBy: 'CATEGORY',
      issueSummary: [
        { groupCode: 'GENERAL', groupName: '一般隐患', count: 2 },
        { groupCode: 'WATCH', groupName: '关注项', count: 1 },
      ],
      files,
    },
  })
}

// One visit from schedule to published delivery, always against the latest version.
async function completeVisit(serviceOrderId, visit, providerToken) {
  const scheduled = await scheduleVisit(serviceOrderId, visit, providerToken)
  const started = await appendProgress(serviceOrderId, scheduled, providerToken, 'SERVICE_STARTED', '现场巡检已开始')
  const progressed = await appendProgress(serviceOrderId, started, providerToken, 'SERVICE_PROGRESS', '已完成东区线路巡检')
  const completed = await appendProgress(serviceOrderId, progressed, providerToken, 'SERVICE_COMPLETED', '现场巡检已完成')
  return publishDelivery(serviceOrderId, completed, providerToken)
}

function isoDateAfterDays(days) {
  return new Date(Date.now() + days * 86400000).toISOString().slice(0, 10)
}

async function createReservationByApi(token) {
  return requestJson('/api/v1/inspection/reservations', {
    token,
    method: 'POST',
    extraHeaders: { 'Idempotency-Key': randomUUID() },
    body: {
      serviceId: '33333333-3333-3333-3333-333333333333',
      cityCode: '370500',
      location: { address: '东营市垦利区黄河路 88 号', longitude: 118.6747, latitude: 37.4348 },
      lineLengthMeters: 12000,
      preferredServiceDate: isoDateAfterDays(8 + Math.floor(Math.random() * 20)),
      preferredTimeSlotCode: 'MORNING',
      inspectionItemCodes: ['POWER_LINE'],
      attachmentFileIds: [],
      note: null,
      contact: { name: '王先生', mobile: '13800138000' },
      agreementVersion: '2026-07',
      agreementAccepted: true,
    },
  })
}

async function expectText(page, text) {
  await page.getByText(text, { exact: true }).first().waitFor()
}

async function acceptCurrentVisit(page, visitNo) {
  await page.locator('uni-button.delivery__accept').click()
  await page.locator('.acceptance-sheet__panel').waitFor()
  await page.locator('uni-button.acceptance-sheet__confirm').click()
  await expectText(page, `第 ${visitNo} 次已验收`)
}

async function openFromOrderCenter(page, stage, title) {
  await page.goto(`${appUrl}/#/pages/mine/inspection-orders?stage=${stage}`, { waitUntil: 'networkidle' })
  // A hash change alone brings back the kept page with its old stage.
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('uni-button.inspection-order-card').filter({ hasText: title }).first().click()
}

// Six visits through the real pages: version compare on the plan, acceptance visit by
// visit while the order stays in progress, and the order center routing to the pending visit.
async function runSixVisitOrder(page, customer, provider) {
  const visitDates = [1, 15, 29, 43, 57, 71].map(isoDateAfterDays)
  const reservation = await createReservationByApi(customer.accessToken)
  await publishPlan(reservation.id, provider.accessToken, 0, planBody({ visitDates }))
  const plan = await publishPlan(reservation.id, provider.accessToken, 1, planBody({
    visitDates,
    changeNote: '增加夜间复巡',
    withNightPatrol: true,
  }))

  await page.goto(`${appUrl}/#/pages/mine/index`, { waitUntil: 'networkidle' })
  await openFromOrderCenter(page, 'AWAITING_CONFIRMATION', reservation.serviceTitle)
  await page.waitForURL(/plan-confirmation\?planId=/)
  await expectText(page, '方案 v2')
  await page.getByText('v2 新增', { exact: true }).waitFor()
  await page.locator('uni-button.plan-version__toggle').click()
  await expectText(page, '方案 v1')
  await page.locator('uni-button.plan-confirmation__agreement').click()
  await page.locator('uni-button.plan-confirmation__confirm').click()
  await page.waitForURL(/fulfillment\?serviceOrderId=/)
  await expectText(page, '6 次中已完成 0 次')
  const serviceOrderId = decodeURIComponent(new URL(page.url()).hash.match(/serviceOrderId=([^&]+)/)?.[1] || '')
  if (!serviceOrderId) throw new Error('Service order ID is missing from the six-visit route.')

  await arrangeOrder(serviceOrderId, provider.accessToken)
  let order = await readOrder(serviceOrderId, customer.accessToken)
  await completeVisit(serviceOrderId, order.visits[0], provider.accessToken)
  await page.reload({ waitUntil: 'networkidle' })
  await expectText(page, '6 次中已完成 1 次')
  await expectText(page, '第 1 次报告待验收')
  await page.locator('uni-button.fulfillment__accept').click()
  await page.waitForURL(/delivery\?serviceOrderId=.*visitId=/)
  await expectText(page, '交付与验收')
  await acceptCurrentVisit(page, 1)

  await page.locator('uni-button.delivery__back').click()
  await page.waitForURL(/fulfillment\?serviceOrderId=/)
  await page.locator('.fulfillment__footer').waitFor({ state: 'detached' })
  order = await readOrder(serviceOrderId, customer.accessToken)
  if (order.status !== 'IN_PROGRESS') {
    throw new Error(`After visit 1 the order must stay IN_PROGRESS, received ${order.status}`)
  }

  await completeVisit(serviceOrderId, order.visits[1], provider.accessToken)
  await openFromOrderCenter(page, 'AWAITING_ACCEPTANCE', reservation.serviceTitle)
  await page.waitForURL(new RegExp(`delivery\\?serviceOrderId=${serviceOrderId}&visitId=${order.visits[1].id}`))
  await expectText(page, '待验收')
  await acceptCurrentVisit(page, 2)

  for (const index of [2, 3, 4, 5]) {
    order = await readOrder(serviceOrderId, customer.accessToken)
    await completeVisit(serviceOrderId, order.visits[index], provider.accessToken)
  }
  order = await readOrder(serviceOrderId, customer.accessToken)
  if (order.status !== 'DELIVERED') {
    throw new Error(`After the last delivery the order must be DELIVERED, received ${order.status}`)
  }
  for (const index of [2, 3, 4, 5]) {
    await openFromOrderCenter(page, 'AWAITING_ACCEPTANCE', reservation.serviceTitle)
    await acceptCurrentVisit(page, index + 1)
  }

  const finalOrder = await readOrder(serviceOrderId, customer.accessToken)
  if (finalOrder.status !== 'ACCEPTED') {
    throw new Error(`Expected ACCEPTED six-visit order, received ${finalOrder.status}`)
  }
  await openFromOrderCenter(page, 'COMPLETED', reservation.serviceTitle)
  await page.waitForURL(/fulfillment\?serviceOrderId=/)
  await expectText(page, '6 次中已完成 6 次')
  await expectText(page, '已完成')
  await page.screenshot({
    path: resolve(evidenceDir, 'golden-path-six-visits.png'),
    fullPage: true,
  })
  return { reservationId: reservation.id, planId: plan.id, serviceOrderId, finalStatus: finalOrder.status }
}

async function main() {
  mkdirSync(evidenceDir, { recursive: true })
  const [customer, provider] = await Promise.all([
    login('smoke-customer'),
    login('smoke-provider'),
  ])
  const browser = await chromium.launch({
    executablePath: browserExecutable,
    headless: true,
  })
  const page = await browser.newPage({ viewport: { width: 390, height: 737 } })
  const consoleErrors = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => consoleErrors.push(error.message))
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, customer.accessToken)

  let locationAdapterApplied = false
  await page.route('**/pages-service-inspection-booking.*.js', async (route) => {
    const response = await route.fetch()
    const source = await response.text()
    const chooseLocationCall = /[A-Za-z_$][\w$]*\(\{success:e=>\{try\{const t=function\(e\)/
    const replacement = '(({success,complete})=>{success({name:"东营市垦利区黄河路 88 号",address:"东营市垦利区黄河路 88 号",longitude:118.6747,latitude:37.4348});complete?.()})({success:e=>{try{const t=function(e)'
    const patchedSource = source.replace(chooseLocationCall, replacement)
    locationAdapterApplied = patchedSource !== source
    await route.fulfill({ response, body: patchedSource })
  })

  try {
    await page.goto(appUrl, { waitUntil: 'networkidle' })
    await page.getByTestId('home-channel-service').click()
    await page.getByTestId('portal-view-all').click()
    await page.locator('article.inspection-card').first().click()
    // The catalog stays in the DOM under the detail page and has the same button text.
    await page.waitForURL(/inspection\/detail\?/)
    // The button stays disabled until the detail has loaded, and a click then does nothing.
    await page.locator('uni-button.service-detail-bottom-bar__primary:not([disabled])').click()
    await page.locator('.booking-section').first().waitFor()

    if (!locationAdapterApplied) {
      throw new Error('The H5 chooseLocation test adapter was not applied to the booking chunk.')
    }
    await page.locator('.booking-row__location').click()
    await page.getByText('东营市垦利区黄河路 88 号', { exact: true }).waitFor()
    const bookingInputs = page.locator('.booking-row__input input')
    await bookingInputs.nth(0).fill('12')
    await page.getByText('期望时间', { exact: true }).click()
    await page.locator('.inspection-time-sheet__panel').waitFor()
    // Today's slots may already be past the booking lead time, so the run starts from tomorrow.
    const tomorrow = page.locator(
      '.inspection-time-sheet__date:not(.inspection-time-sheet__date--more)',
    ).nth(1)
    await tomorrow.waitFor()
    await tomorrow.click()
    const morningSlot = page.locator('.inspection-time-sheet__slot')
      .filter({ hasText: '09:00–12:00' })
    await morningSlot.waitFor()
    if (await morningSlot.count() !== 1) {
      throw new Error(`Expected one MORNING slot, received sheet: ${await page.locator('.inspection-time-sheet__panel').innerText()}`)
    }
    await morningSlot.click()
    await page.locator('.inspection-time-sheet__confirm').click()
    await page.getByText(/月\d+日 09:00–12:00/).waitFor()
    mkdirSync(resolve('artifacts/inspection-time-selection'), { recursive: true })
    await page.screenshot({
      path: 'artifacts/inspection-time-selection/round-1-390.png',
      fullPage: true,
    })
    await page.locator('uni-button.booking-item').filter({
      hasText: '线路本体巡检',
    }).click()
    await bookingInputs.nth(1).fill('王先生')
    await bookingInputs.nth(2).fill('13800138000')
    await page.locator('.booking-agreement uni-checkbox').click()
    await page.locator('uni-button.inspection-booking__submit').click()
    await Promise.race([
      page.waitForURL(/booking-submitted\?reservationId=/),
      page.locator('.booking-submit-error').waitFor({ state: 'visible' }).then(async () => {
        throw new Error(`M004 submission failed: ${await page.locator('.booking-submit-error').innerText()}`)
      }),
    ])
    await page.getByText('订单已提交', { exact: true }).waitFor()
    const reservationId = new URL(page.url()).hash.match(/reservationId=([^&]+)/)?.[1]
    if (!reservationId) throw new Error('Reservation ID is missing from M005 route.')

    const plan = await publishPlan(decodeURIComponent(reservationId), provider.accessToken, 0, planBody({
      visitDates: [isoDateAfterDays(1)],
    }))
    await page.reload({ waitUntil: 'networkidle' })
    await page.locator('.booking-submitted__footer uni-button').last().click()
    await page.getByText('方案确认', { exact: true }).first().waitFor()
    await page.locator('uni-button.plan-confirmation__agreement').click()
    await page.locator('uni-button.plan-confirmation__confirm').click()
    await page.waitForURL(/fulfillment\?serviceOrderId=/)
    await page.getByText('服务履约', { exact: true }).first().waitFor()
    const serviceOrderId = new URL(page.url()).hash.match(/serviceOrderId=([^&]+)/)?.[1]
    if (!serviceOrderId) throw new Error('Service order ID is missing from M007 route.')

    await arrangeOrder(decodeURIComponent(serviceOrderId), provider.accessToken)
    const arrangedOrder = await readOrder(decodeURIComponent(serviceOrderId), customer.accessToken)
    const delivery = await completeVisit(
      decodeURIComponent(serviceOrderId),
      arrangedOrder.visits[0],
      provider.accessToken,
    )
    await page.reload({ waitUntil: 'networkidle' })
    await page.locator('uni-button.fulfillment__accept').click()
    await page.getByText('交付与验收', { exact: true }).first().waitFor()
    await acceptCurrentVisit(page, 1)

    const persistedOrder = await readOrder(decodeURIComponent(serviceOrderId), customer.accessToken)
    if (persistedOrder.status !== 'ACCEPTED') {
      throw new Error(`Expected ACCEPTED order, received ${persistedOrder.status}`)
    }
    await page.screenshot({
      path: resolve(evidenceDir, 'golden-path-accepted.png'),
      fullPage: true,
    })
    const finalRoute = new URL(page.url()).hash
    const sixVisits = await runSixVisitOrder(page, customer, provider)
    if (consoleErrors.length) {
      throw new Error(`Browser console errors: ${consoleErrors.join(' | ')}`)
    }
    process.stdout.write(`${JSON.stringify({
      PublicEntry: true,
      ReservationId: decodeURIComponent(reservationId),
      PlanId: plan.id,
      ServiceOrderId: decodeURIComponent(serviceOrderId),
      DeliveryId: delivery.id,
      DeliveryFileCount: delivery.files.length,
      FinalStatus: persistedOrder.status,
      FinalRoute: finalRoute,
      SixVisits: sixVisits,
      ConsoleErrors: consoleErrors.length,
    })}\n`)
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
