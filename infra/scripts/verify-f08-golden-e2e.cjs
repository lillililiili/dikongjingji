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

async function publishPlan(reservationId, providerToken) {
  const now = new Date()
  const serviceDate = now.toISOString().slice(0, 10)
  return requestJson(`/api/v1/provider/inspection/reservations/${reservationId}/plans`, {
    token: providerToken,
    method: 'POST',
    extraHeaders: {
      'Idempotency-Key': randomUUID(),
      'If-Match': '"0"',
    },
    body: {
      providerId,
      validUntil: new Date(now.getTime() + 2 * 86400000).toISOString(),
      serviceDate,
      summary: '无人机采集、专业复核并交付巡检报告',
      planItems: [{
        code: 'POWER_LINE',
        name: '输电线路巡检',
        quantity: 12,
        unit: '公里',
        unitPriceMinor: 69900,
      }],
      deliverables: [
        { code: 'HD_MEDIA', name: '高清影像' },
        { code: 'DEFECT_LIST', name: '缺陷清单' },
        { code: 'POINT_MARKS', name: '点位标记' },
        { code: 'REPORT', name: '巡检报告' },
      ],
      currency: 'CNY',
    },
  })
}

async function completeServiceOrder(serviceOrderId, providerToken) {
  await requestJson(`/api/v1/provider/inspection/service-orders/${serviceOrderId}/arrangement`, {
    token: providerToken,
    method: 'PUT',
    extraHeaders: {
      'Idempotency-Key': randomUUID(),
      'If-Match': '"0"',
    },
    body: {
      plannedStartAt: new Date(Date.now() + 86400000).toISOString(),
      assignments: [
        {
          type: 'TEAM_MEMBER',
          resourceId: '77777777-7777-4777-8777-777777777701',
          displayName: '东营巡检作业组',
          roleLabel: '项目负责人',
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

  const events = [
    ['SERVICE_STARTED', '现场巡检已开始'],
    ['SERVICE_PROGRESS', '已完成十二公里线路巡检'],
    ['SERVICE_COMPLETED', '现场巡检已完成'],
  ]
  for (let index = 0; index < events.length; index += 1) {
    await requestJson(`/api/v1/provider/inspection/service-orders/${serviceOrderId}/progress-events`, {
      token: providerToken,
      method: 'POST',
      extraHeaders: {
        'Idempotency-Key': randomUUID(),
        'If-Match': `"${index + 1}"`,
      },
      body: {
        eventCode: events[index][0],
        occurredAt: new Date(Date.now() + (index + 1) * 1000).toISOString(),
        message: events[index][1],
      },
    })
  }
}

async function uploadDeliveryFile(serviceOrderId, providerToken, file) {
  const sha256 = createHash('sha256').update(file.content).digest('hex')
  const intent = await requestJson('/api/v1/inspection/file-objects/upload-intents', {
    token: providerToken,
    method: 'POST',
    extraHeaders: { 'Idempotency-Key': randomUUID() },
    body: {
      purpose: 'DELIVERY_ARTIFACT',
      ownerId: serviceOrderId,
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
  }
}

async function publishDelivery(serviceOrderId, providerToken) {
  const definitions = [
    {
      type: 'IMAGE_ARCHIVE',
      name: '现场影像.zip',
      contentType: 'application/zip',
      content: Buffer.from('PK\u0003\u0004F08 inspection images'),
    },
    {
      type: 'DEFECT_LIST',
      name: '缺陷清单.xlsx',
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      content: Buffer.from('PK\u0003\u0004F08 defect workbook'),
    },
    {
      type: 'POINT_KML',
      name: '巡检点位.kml',
      contentType: 'application/vnd.google-earth.kml+xml',
      content: Buffer.from('<?xml version="1.0" encoding="UTF-8"?><kml><Document/></kml>'),
      pointCount: 24,
    },
    {
      type: 'INSPECTION_REPORT',
      name: '巡检报告.pdf',
      contentType: 'application/pdf',
      content: Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%%EOF'),
    },
  ]
  const files = []
  for (const definition of definitions) {
    files.push(await uploadDeliveryFile(serviceOrderId, providerToken, definition))
  }

  return requestJson(`/api/v1/provider/inspection/service-orders/${serviceOrderId}/delivery`, {
    token: providerToken,
    method: 'POST',
    extraHeaders: {
      'Idempotency-Key': randomUUID(),
      'If-Match': '"4"',
    },
    body: {
      towerCount: 24,
      inspectedLengthMeters: 12000,
      issueCount: 3,
      issueSummary: [
        { severityCode: 'GENERAL', severityName: '一般隐患', count: 2 },
        { severityCode: 'WATCH', severityName: '关注项', count: 1 },
      ],
      files,
    },
  })
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
    await page.getByText('立即预约', { exact: true }).last().click()
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

    const plan = await publishPlan(decodeURIComponent(reservationId), provider.accessToken)
    await page.reload({ waitUntil: 'networkidle' })
    await page.locator('.booking-submitted__footer uni-button').last().click()
    await page.getByText('方案确认', { exact: true }).first().waitFor()
    await page.locator('uni-button.plan-confirmation__agreement').click()
    await page.locator('uni-button.plan-confirmation__confirm').click()
    await page.waitForURL(/fulfillment\?serviceOrderId=/)
    await page.getByText('现场巡检', { exact: true }).first().waitFor()
    const serviceOrderId = new URL(page.url()).hash.match(/serviceOrderId=([^&]+)/)?.[1]
    if (!serviceOrderId) throw new Error('Service order ID is missing from M007 route.')

    await completeServiceOrder(decodeURIComponent(serviceOrderId), provider.accessToken)
    const delivery = await publishDelivery(decodeURIComponent(serviceOrderId), provider.accessToken)
    await page.reload({ waitUntil: 'networkidle' })
    await page.locator('.fulfillment__footer uni-button').last().click()
    await page.getByText('报告已交付', { exact: true }).waitFor()
    await page.locator('.delivery__footer uni-button').filter({
      hasText: '确认验收',
    }).click()
    await page.getByText('服务已验收', { exact: true }).waitFor()

    const persistedOrder = await requestJson(`/api/v1/inspection/service-orders/${decodeURIComponent(serviceOrderId)}`, {
      token: customer.accessToken,
    })
    if (persistedOrder.status !== 'ACCEPTED') {
      throw new Error(`Expected ACCEPTED order, received ${persistedOrder.status}`)
    }
    await page.screenshot({
      path: resolve(evidenceDir, 'golden-path-accepted.png'),
      fullPage: true,
    })
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
      FinalRoute: new URL(page.url()).hash,
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
