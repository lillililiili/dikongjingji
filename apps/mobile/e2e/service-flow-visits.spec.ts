import { expect, test, type Page, type Route } from '@playwright/test'

const ORDER_ID = '60000000-0000-4000-8000-000000000001'
const PLAN_ID = '50000000-0000-4000-8000-000000000002'
const RESERVATION_ID = '40000000-0000-4000-8000-000000000001'
const FIRST_VISIT = '61000000-0000-4000-8000-000000000001'
const SECOND_VISIT = '61000000-0000-4000-8000-000000000002'
const NOW = new Date('2026-11-03T09:00:00+08:00')
const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': '*',
}

type Answer = { status?: number; body: unknown }

test.use({ timezoneId: 'Asia/Shanghai' })

function plan(overrides: Record<string, unknown> = {}) {
  return {
    id: PLAN_ID,
    planNo: 'FA2',
    reservationId: RESERVATION_ID,
    providerId: 'p1',
    provider: { providerId: 'p1', name: '鹰眼智能巡检', certificationLabel: '资质已审核' },
    customerParty: { organizationName: '东营港务物流', operatorName: '王女士' },
    status: 'PUBLISHED',
    planVersion: 1,
    validUntil: '2026-11-05T18:00:00+08:00',
    serviceDate: '2026-11-06',
    visits: [{ visitNo: 1, plannedDate: '2026-11-06' }],
    summary: null,
    scopeSummary: '约 42 万㎡',
    serviceMethods: [{ code: 'DRONE', label: '无人机巡检' }],
    deliveryTerms: { reportDueHours: 48, mediaRetentionDays: 90 },
    changeNote: null,
    planItems: [{
      code: 'DRONE',
      name: '无人机巡检',
      quantity: 6,
      unit: '次',
      unitPriceMinor: 180_000,
      lineAmountMinor: 1_080_000,
    }],
    deliverables: [{ code: 'REPORT', label: '巡检报告' }],
    totalAmountMinor: 1_080_000,
    currency: 'CNY',
    paymentNodes: [],
    publishedAt: '2026-11-01T08:30:00+08:00',
    confirmedAt: null,
    confirmedBy: null,
    serviceOrderId: null,
    version: 2,
    ...overrides,
  }
}

function reservation(overrides: Record<string, unknown> = {}) {
  return {
    id: RESERVATION_ID,
    reservationNo: 'RS1',
    customerId: 'c1',
    serviceId: 's1',
    serviceTitle: '物流园区季度安防巡检',
    serviceCoverUrl: null,
    cityCode: '370500',
    status: 'PLAN_PUBLISHED',
    location: { address: '东营港物流园', longitude: null, latitude: null },
    lineLengthMeters: null,
    preferredServiceDate: '2026-11-06',
    preferredTimeSlotCode: 'MORNING',
    inspectionItems: [],
    note: null,
    contact: { name: '王女士', mobile: '13800000000' },
    attachments: [],
    submittedAt: '2026-10-28T09:00:00+08:00',
    currentPlanId: PLAN_ID,
    version: 3,
    ...overrides,
  }
}

function visit(visitNo: number, id: string, status: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    serviceOrderId: ORDER_ID,
    visitNo,
    plannedDate: `2026-11-0${visitNo + 1}`,
    plannedStartAt: null,
    status,
    progress: [],
    startedAt: null,
    deliveredAt: null,
    acceptedAt: null,
    version: 4,
    ...overrides,
  }
}

function order(visits: unknown[], overrides: Record<string, unknown> = {}) {
  return {
    id: ORDER_ID,
    serviceOrderNo: 'FW1',
    reservationId: RESERVATION_ID,
    planId: PLAN_ID,
    customerId: 'c1',
    providerId: 'p1',
    status: 'IN_PROGRESS',
    paymentMode: 'NONE',
    paymentNodes: [],
    provider: { providerId: 'p1', name: '鹰眼智能巡检', certificationLabel: '资质已审核' },
    customerParty: { organizationName: '东营港务物流', operatorName: '王女士' },
    serviceSnapshot: {
      serviceId: 's1',
      serviceTitle: '物流园区季度安防巡检',
      serviceCoverUrl: null,
      cityCode: '370500',
      location: { address: '东营港物流园', longitude: null, latitude: null },
      lineLengthMeters: null,
      serviceDate: '2026-11-02',
      inspectionItems: [],
    },
    assignments: [],
    progress: [],
    visits,
    createdAt: '2026-11-01T09:30:00+08:00',
    deliveredAt: null,
    acceptedAt: null,
    version: 2,
    ...overrides,
  }
}

function delivery(visitId: string, overrides: Record<string, unknown> = {}) {
  return {
    id: `delivery-${visitId}`,
    serviceOrderId: ORDER_ID,
    visitId,
    status: 'PUBLISHED',
    towerCount: null,
    inspectedLengthMeters: null,
    metrics: [{ code: 'COVERED_AREA', label: '巡检覆盖', value: 42, unit: '万㎡' }],
    issueCount: 0,
    issueGroupBy: 'CATEGORY',
    issueSummary: [],
    files: [{
      fileId: 'f1',
      type: 'INSPECTION_REPORT',
      displayName: '巡检报告',
      pointCount: null,
      itemCount: null,
      contentType: 'application/pdf',
      sizeBytes: 1024,
      status: 'READY',
    }],
    publishedAt: '2026-11-03T08:00:00+08:00',
    acceptedAt: null,
    acceptedByName: null,
    version: 1,
    ...overrides,
  }
}

async function answerApi(
  page: Page,
  answer: (path: string, method: string) => Answer | null,
): Promise<void> {
  await page.clock.setFixedTime(NOW)
  await page.addInitScript(() => localStorage.setItem('inspection.accessToken', 'e2e-token'))
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: CORS })
      return
    }
    const result = answer(new URL(request.url()).pathname, request.method())
    await route.fulfill({
      status: result?.status ?? (result ? 200 : 404),
      headers: CORS,
      json: result?.body ?? { code: 'COMMON_NOT_FOUND', message: '资源不存在', traceId: 't' },
    })
  })
}

function planAnswers(currentPlan: unknown, currentReservation: unknown = reservation()) {
  return (path: string): Answer | null => {
    if (path.endsWith('/plans')) return { body: [] }
    if (path.includes('/plans/')) return { body: currentPlan }
    if (path.includes('/reservations/')) return { body: currentReservation }
    return null
  }
}

test.describe('M05 plan confirmation', () => {
  test('hides payment nodes while the platform has them off', async ({ page }) => {
    await answerApi(page, planAnswers(plan()))
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    await expect(page.locator('.plan-version__valid')).toHaveText('报价有效期至 11/05 18:00')
    await expect(page.locator('.plan-payment')).toHaveCount(0)
  })

  test('only allows confirming after the customer has checked the plan', async ({ page }) => {
    await answerApi(page, planAnswers(plan()))
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    const confirm = page.locator('uni-button.plan-confirmation__confirm')
    await expect(confirm).toHaveAttribute('disabled', /.*/)
    await page.locator('uni-button.plan-confirmation__agreement').click()
    await expect(confirm).not.toHaveAttribute('disabled', /.*/)
  })

  test('offers an update instead of confirming once the quote has expired', async ({ page }) => {
    await answerApi(page, planAnswers(plan({ validUntil: '2026-11-02T18:00:00+08:00' })))
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    await expect(page.locator('.plan-version__chip')).toHaveText('已过期')
    await expect(page.locator('.plan-confirmation__agreement')).toHaveCount(0)
    await expect(page.locator('uni-button.plan-confirmation__confirm')).toHaveCount(0)
    await expect(page.locator('uni-button.plan-confirmation__primary')).toHaveText('申请更新方案')
    await expect(page.locator('uni-button.plan-confirmation__cancel')).toBeVisible()
  })

  test('shows a cancelled reservation as cancelled, with no way to confirm it', async ({ page }) => {
    await answerApi(page, planAnswers(plan(), reservation({ status: 'CANCELLED' })))
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    await expect(page.locator('.plan-version__chip')).toHaveText('已取消')
    await expect(page.locator('.plan-confirmation__status-title')).toHaveText('订单已取消')
    await expect(page.locator('uni-button.plan-confirmation__cancel')).toHaveCount(0)
    await expect(page.locator('uni-button.plan-confirmation__confirm')).toHaveCount(0)
  })

  test('moves to the version in force when the provider replaced the plan meanwhile', async ({ page }) => {
    const replacedBy = '50000000-0000-4000-8000-000000000003'
    let replaced = false
    await answerApi(page, (path, method) => {
      if (path.endsWith('/confirm') && method === 'POST') {
        replaced = true
        return { status: 422, body: { code: 'INS_PLAN_NOT_PUBLISHED', message: '方案不可确认', traceId: 't' } }
      }
      if (path.endsWith('/plans')) return { body: [] }
      if (path.endsWith(replacedBy)) return { body: plan({ id: replacedBy, planVersion: 2 }) }
      if (path.includes('/plans/')) return { body: plan({ status: replaced ? 'WITHDRAWN' : 'PUBLISHED' }) }
      if (path.includes('/reservations/')) {
        return { body: reservation({ currentPlanId: replaced ? replacedBy : PLAN_ID }) }
      }
      return null
    })
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    await page.locator('uni-button.plan-confirmation__agreement').click()
    await page.locator('uni-button.plan-confirmation__confirm').click()

    await expect(page.locator('.plan-version__title')).toHaveText('方案 v2')
    await expect(page.locator('.plan-confirmation__error')).toHaveText('方案已更新，请重新核对')
    await expect(page.locator('uni-button.plan-confirmation__confirm')).toHaveAttribute('disabled', /.*/)
  })

  test('a tick does not carry over to a plan the customer has not checked', async ({ page }) => {
    const replacedBy = '50000000-0000-4000-8000-000000000003'
    let replaced = false
    await answerApi(page, (path, method) => {
      if (path.endsWith('/cancel') && method === 'POST') {
        replaced = true
        return { status: 412, body: { code: 'INS_VERSION_CONFLICT', message: '版本已变化', traceId: 't' } }
      }
      if (path.endsWith('/plans')) return { body: [] }
      if (path.endsWith(replacedBy)) return { body: plan({ id: replacedBy, planVersion: 2 }) }
      if (path.includes('/plans/')) return { body: plan({ status: replaced ? 'WITHDRAWN' : 'PUBLISHED' }) }
      if (path.includes('/reservations/')) {
        return { body: reservation({ currentPlanId: replaced ? replacedBy : PLAN_ID }) }
      }
      return null
    })
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    await page.locator('uni-button.plan-confirmation__agreement').click()
    await page.locator('uni-button.plan-confirmation__cancel').click()
    await page.locator('.uni-modal__btn_primary').click()

    await expect(page.locator('.plan-version__title')).toHaveText('方案 v2')
    await expect(page.locator('uni-button.plan-confirmation__confirm')).toHaveAttribute('disabled', /.*/)
  })

  test('shows the confirmed state when it was confirmed on another device', async ({ page }) => {
    let confirmedElsewhere = false
    await answerApi(page, (path, method) => {
      if (path.endsWith('/confirm') && method === 'POST') {
        confirmedElsewhere = true
        return { status: 409, body: { code: 'INS_PLAN_ALREADY_CONFIRMED', message: '方案已确认', traceId: 't' } }
      }
      return planAnswers(plan(confirmedElsewhere
        ? { status: 'CONFIRMED', serviceOrderId: ORDER_ID, confirmedAt: '2026-11-03T08:50:00+08:00' }
        : {}))(path)
    })
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    await page.locator('uni-button.plan-confirmation__agreement').click()
    await page.locator('uni-button.plan-confirmation__confirm').click()

    await expect(page.locator('.plan-version__chip')).toHaveText('已确认')
    await expect(page.locator('uni-button.plan-confirmation__primary')).toHaveText('查看服务安排')
  })

  test('offers a retry when the plan cannot be read', async ({ page }) => {
    let attempts = 0
    await answerApi(page, (path) => {
      if (path.includes('/plans/')) {
        attempts += 1
        if (attempts === 1) {
          return { status: 500, body: { code: 'COMMON_INTERNAL_ERROR', message: '服务异常', traceId: 't' } }
        }
      }
      return planAnswers(plan())(path)
    })
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    await page.locator('uni-button.plan-confirmation__retry').click()

    await expect(page.locator('.plan-version__title')).toHaveText('方案 v1')
  })

  test('opening a replaced version goes to the one in force', async ({ page }) => {
    const replacedBy = '50000000-0000-4000-8000-000000000003'
    await answerApi(page, (path) => {
      if (path.endsWith('/plans')) return { body: [] }
      if (path.endsWith(replacedBy)) return { body: plan({ id: replacedBy, planVersion: 2 }) }
      if (path.includes('/plans/')) return { body: plan({ status: 'WITHDRAWN' }) }
      if (path.includes('/reservations/')) return { body: reservation({ currentPlanId: replacedBy }) }
      return null
    })
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    await expect(page.locator('.plan-version__title')).toHaveText('方案 v2')
    await expect(page.locator('.plan-version__chip')).toHaveText('待确认')
  })

  test('still opens when the version list fails', async ({ page }) => {
    await answerApi(page, (path) => {
      if (path.endsWith('/plans')) return { status: 500, body: { code: 'COMMON_INTERNAL_ERROR', message: '服务异常', traceId: 't' } }
      return planAnswers(plan())(path)
    })
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    await expect(page.locator('.plan-version__title')).toHaveText('方案 v1')
    await expect(page.locator('.plan-version__toggle')).toHaveCount(0)
  })
})

test.describe('M06 fulfillment', () => {
  test('shows the arranged crew only once there is one, and no acceptance without a delivery', async ({ page }) => {
    await answerApi(page, (path) => {
      if (path.includes('/plans/')) return { body: plan() }
      if (path.includes('/service-orders/')) return { body: order([visit(1, FIRST_VISIT, 'PENDING_SCHEDULE')], { status: 'ARRANGING' }) }
      return null
    })
    await page.goto(`/#/pages/service/inspection/fulfillment?serviceOrderId=${ORDER_ID}`)

    await expect(page.getByText('服务商正在安排人员和设备')).toBeVisible()
    await expect(page.locator('.fulfillment-summary__status-label')).toHaveText('待履约')
    await expect(page.locator('.fulfillment__footer')).toHaveCount(0)
  })

  test('offers a retry when the order cannot be read', async ({ page }) => {
    let attempts = 0
    await answerApi(page, (path) => {
      if (path.includes('/plans/')) return { body: plan() }
      if (!path.includes('/service-orders/')) return null
      attempts += 1
      return attempts === 1
        ? { status: 500, body: { code: 'COMMON_INTERNAL_ERROR', message: '服务异常', traceId: 't' } }
        : { body: order([visit(1, FIRST_VISIT, 'SCHEDULED')]) }
    })
    await page.goto(`/#/pages/service/inspection/fulfillment?serviceOrderId=${ORDER_ID}`)

    await page.locator('uni-button.fulfillment__retry').click()
    await expect(page.locator('.fulfillment-summary')).toBeVisible()
  })
})

test.describe('M07 delivery and acceptance', () => {
  function deliveryAnswers(state: { accepted: string[] }) {
    return (path: string, method: string): Answer | null => {
      if (path.endsWith('/acceptance') && method === 'POST') {
        const visitId = path.split('/visits/')[1].split('/')[0]
        state.accepted.push(visitId)
        return {
          body: {
            id: 'a1',
            serviceOrderId: ORDER_ID,
            visitId,
            deliveryId: `delivery-${visitId}`,
            acceptedBy: 'c1',
            acceptedByName: '王女士',
            acceptedAt: '2026-11-03T09:00:00+08:00',
            visitStatus: 'ACCEPTED',
            orderStatus: 'IN_PROGRESS',
          },
        }
      }
      if (path.endsWith('/delivery')) {
        const visitId = path.split('/visits/')[1].split('/')[0]
        return {
          body: delivery(visitId, state.accepted.includes(visitId)
            ? { status: 'ACCEPTED', acceptedAt: '2026-11-03T09:00:00+08:00', acceptedByName: '王女士' }
            : {}),
        }
      }
      if (path.includes('/plans/')) return { body: plan() }
      if (path.includes('/service-orders/')) {
        return {
          body: order([FIRST_VISIT, SECOND_VISIT].map((id, index) => visit(
            index + 1,
            id,
            state.accepted.includes(id) ? 'ACCEPTED' : 'DELIVERED',
          ))),
        }
      }
      return null
    }
  }

  test('a link without a visit opens the pending one and stays on it after acceptance', async ({ page }) => {
    const state = { accepted: [] as string[] }
    await answerApi(page, deliveryAnswers(state))
    await page.goto(`/#/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}`)

    await expect(page.locator('.delivery__title')).toHaveText(/第 1 次巡检/)
    await page.locator('uni-button.delivery__accept').click()
    await page.locator('uni-button.acceptance-sheet__confirm').click()

    await expect(page.locator('.delivery__accepted-title')).toHaveText('第 1 次已验收')
    await expect(page.locator('.delivery__accepted-text')).toHaveText('11/03 09:00 · 王女士 · 验收记录已留存')
    await expect(page.locator('.delivery__title')).toHaveText(/第 1 次巡检/)
    await expect(page.locator('.delivery__footer')).toHaveCount(0)
    expect(state.accepted).toEqual([FIRST_VISIT])
  })

  test('opens without the plan, which only supplies the retention note', async ({ page }) => {
    await answerApi(page, (path, method) => {
      if (path.includes('/plans/')) {
        return { status: 500, body: { code: 'COMMON_INTERNAL_ERROR', message: '服务异常', traceId: 't' } }
      }
      return deliveryAnswers({ accepted: [] })(path, method)
    })
    await page.goto(`/#/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}&visitId=${FIRST_VISIT}`)

    await expect(page.locator('.delivery__title')).toHaveText(/第 1 次巡检/)
    await expect(page.locator('uni-button.delivery__accept')).toBeVisible()
    await expect(page.getByText('按交付约定保存')).toHaveCount(0)
  })

  test('reports no failure when the acceptance landed but its response was lost', async ({ page }) => {
    const state = { accepted: [] as string[] }
    await answerApi(page, (path, method) => {
      if (path.endsWith('/acceptance') && method === 'POST') {
        state.accepted.push(FIRST_VISIT)
        return { status: 500, body: { code: 'COMMON_INTERNAL_ERROR', message: '网络连接失败', traceId: 't' } }
      }
      return deliveryAnswers(state)(path, method)
    })
    await page.goto(`/#/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}&visitId=${FIRST_VISIT}`)

    await page.locator('uni-button.delivery__accept').click()
    await page.locator('uni-button.acceptance-sheet__confirm').click()

    await expect(page.locator('.delivery__accepted-title')).toHaveText('第 1 次已验收')
    await expect(page.locator('.delivery__error')).toHaveCount(0)
  })

  test('closing the sheet accepts nothing', async ({ page }) => {
    const state = { accepted: [] as string[] }
    await answerApi(page, deliveryAnswers(state))
    await page.goto(`/#/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}&visitId=${SECOND_VISIT}`)

    await page.locator('uni-button.delivery__accept').click()
    await expect(page.locator('.acceptance-sheet__title')).toHaveText('确认第 2 次验收')
    await page.locator('uni-button.acceptance-sheet__cancel').click()

    await expect(page.locator('.acceptance-sheet__panel')).toHaveCount(0)
    expect(state.accepted).toEqual([])
  })

  test('says so when the link points at a visit of another order', async ({ page }) => {
    await answerApi(page, deliveryAnswers({ accepted: [] }))
    await page.goto(`/#/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}&visitId=61000000-0000-4000-8000-000000000009`)

    await expect(page.getByText('未找到报告交付记录')).toBeVisible()
    await expect(page.locator('.delivery__footer')).toHaveCount(0)
  })

  test('shows when the download link stops working, from what the server returned', async ({ page }) => {
    await answerApi(page, (path, method) => {
      if (path.endsWith('/download-intent') && method === 'POST') {
        return {
          body: {
            method: 'GET',
            downloadUrl: 'http://127.0.0.1:1/never',
            suggestedFileName: '巡检报告.pdf',
            expiresAt: '2026-11-03T09:10:00+08:00',
          },
        }
      }
      return deliveryAnswers({ accepted: [] })(path, method)
    })
    await page.goto(`/#/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}&visitId=${FIRST_VISIT}`)

    await page.locator('.delivery-files__row uni-button').filter({ hasText: /^下载$/ }).click()

    await expect(page.locator('.delivery-files__row .delivery-files__link'))
      .toContainText('下载链接已生成，09:10 前有效')
  })
})
