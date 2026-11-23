import { resolve } from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import {
  FIRST_VISIT,
  ORDER_ID,
  PLAN_ID,
  RESERVATION_ID,
  answerApi,
  delivery,
  order,
  plan,
  reservation,
  visit,
  type Answer,
} from './support/service-flow-fixtures'

// S4-1 巡检付款节点（docs/redesign/08 §5 D9、C3、B2；boards PlanConfirm, PlanExpired, CashierFirst,
// CashierFinal, CashierOneFirst, CashierOneFinal, FulfillmentLater, OrderService）. The API is stubbed
// like service-flow-visits.spec.ts; the dev build has the trade preview on, so payment shows.
// Screenshots for the board comparison go to .planning/inspection-payment/visual (390 wide).

test.use({ timezoneId: 'Asia/Shanghai' })

const visualOutput = resolve(__dirname, '../../../.planning/inspection-payment/visual')
const DOWN = 558_000
const FEE = 217_000
const DOWN_TRADE = '70000000-0000-4000-8000-000000000001'
const FEE_TRADE = '70000000-0000-4000-8000-000000000002'
const SIX = [1, 2, 3, 4, 5, 6].map((n) => `6100000${n}-0000-4000-8000-00000000000${n}`)

function nodes(overrides: Record<number, Record<string, unknown>> = {}) {
  return [
    { nodeNo: 1, nodeType: 'DOWN_PAYMENT', visitNo: null, amountMinor: DOWN, payable: false, paidAt: null, tradeOrderId: null, refund: null, ...overrides[1] },
    ...[1, 2, 3, 4, 5, 6].map((visitNo) => ({
      nodeNo: visitNo + 1,
      nodeType: 'PER_VISIT',
      visitNo,
      amountMinor: FEE,
      payable: false,
      paidAt: null,
      tradeOrderId: null,
      refund: null,
      ...overrides[visitNo + 1],
    })),
  ]
}

const planNodes = nodes().map(({ nodeNo, nodeType, visitNo, amountMinor }) => ({ nodeNo, nodeType, visitNo, amountMinor }))

function nodePlan(overrides: Record<string, unknown> = {}) {
  return plan({
    planVersion: 2,
    totalAmountMinor: 1_860_000,
    planItems: [
      { code: 'DRONE', name: '无人机巡检', quantity: 6, unit: '次', unitPriceMinor: 180_000, lineAmountMinor: 1_080_000 },
      { code: 'DOG', name: '机器狗夜巡', quantity: 6, unit: '次', unitPriceMinor: 110_000, lineAmountMinor: 660_000 },
      { code: 'REPORT', name: '报告与隐患跟踪', quantity: 1, unit: '项', unitPriceMinor: 120_000, lineAmountMinor: 120_000 },
    ],
    visits: ['2026-11-04', '2026-11-16', '2026-12-01', '2026-12-15', '2027-01-04', '2027-01-18']
      .map((plannedDate, index) => ({ visitNo: index + 1, plannedDate })),
    serviceMethods: [{ code: 'DRONE', label: '无人机巡检' }, { code: 'DOG', label: '机器狗夜巡' }],
    paymentNodes: planNodes,
    ...overrides,
  })
}

function tradeOrder(orderId: string, skuName: string, amount: number, status = 'PENDING_PAYMENT', extra: Record<string, unknown> = {}) {
  return {
    orderId,
    orderNo: 'IO2026110300000001',
    source: 'INSPECTION',
    sourceRef: `attempt-${orderId}`,
    status,
    merchant: { organizationId: 'p1', name: '鹰眼智能巡检', kind: null },
    lines: [{
      lineId: 'l1', productId: ORDER_ID, offerId: `attempt-${orderId}`,
      productName: '物流园区季度安防巡检 · 方案 v2', skuName, coverImage: null,
      unitAmountMinor: amount, quantity: 1, subtotalMinor: amount,
    }],
    delivery: null,
    amounts: { goodsMinor: amount, shippingMinor: 0, payableMinor: amount, paidMinor: status === 'PAID' ? amount : 0, refundedMinor: 0 },
    buyerNote: null,
    createdAt: '2026-11-03T09:00:00+08:00',
    payBefore: '2026-11-03T09:15:00+08:00',
    paidAt: status === 'PAID' ? '2026-11-03T09:02:00+08:00' : null,
    completedAt: null,
    closedAt: null,
    closeReason: null,
    refunds: [],
    cancellable: status === 'PENDING_PAYMENT',
    version: 1,
    ...extra,
  }
}

function sixVisits(statuses: string[], extra: Record<number, Record<string, unknown>> = {}) {
  return statuses.map((status, index) => visit(index + 1, SIX[index], status, {
    plannedDate: ['2026-11-04', '2026-11-16', '2026-12-08', '2026-12-15', '2027-01-04', '2027-01-18'][index],
    deliveredAt: status === 'DELIVERED' || status === 'ACCEPTED' ? '2026-11-03T10:00:00+08:00' : null,
    acceptedAt: status === 'ACCEPTED' ? '2026-11-03T16:20:00+08:00' : null,
    startedAt: status === 'PENDING_SCHEDULE' || status === 'SCHEDULED' ? null : '2026-11-02T09:00:00+08:00',
    ...extra[index + 1],
  }))
}

async function shoot(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: resolve(visualOutput, `${name}-390.png`), fullPage: true })
}

test.describe('plan confirmation with payment nodes (boards PlanConfirm, PlanExpired)', () => {
  test('shows the nodes, confirms, then pays the down payment on the cashier', async ({ page }) => {
    let confirmed = false
    const confirmedOrder = order(sixVisits(Array(6).fill('PENDING_SCHEDULE')), {
      status: 'AWAITING_PAYMENT',
      paymentMode: 'NODES',
      paymentNodes: nodes({ 1: { payable: true } }),
      cancellable: true,
      paymentRecords: [],
    })
    const payments: string[] = []
    await answerApi(page, (path, method): Answer | null => {
      if (path.endsWith('/confirm') && method === 'POST') {
        confirmed = true
        return { body: confirmedOrder }
      }
      if (path.endsWith('/payment-nodes/1/payment') && method === 'POST') {
        payments.push(path)
        return { body: { nodeNo: 1, tradeOrderId: DOWN_TRADE, payBefore: '2026-11-03T09:15:00+08:00', paid: false } }
      }
      if (path === `/api/v1/trade/orders/${DOWN_TRADE}`) return { body: tradeOrder(DOWN_TRADE, '首付 30%', DOWN) }
      // Once the payment is opened the node carries its trade order.
      if (path === `/api/v1/inspection/service-orders/${ORDER_ID}`) {
        return { body: payments.length
          ? { ...confirmedOrder, paymentNodes: nodes({ 1: { payable: true, tradeOrderId: DOWN_TRADE } }) }
          : confirmedOrder }
      }
      if (path.endsWith('/plans')) return { body: [] }
      if (path.includes('/plans/')) {
        return { body: confirmed
          ? nodePlan({ status: 'CONFIRMED', serviceOrderId: ORDER_ID, confirmedAt: '2026-11-03T09:00:00+08:00', confirmedBy: { subjectId: 'c1', displayName: '王女士' } })
          : nodePlan() }
      }
      if (path.includes('/reservations/')) return { body: reservation() }
      return null
    })
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    const rows = page.locator('.plan-payment__row')
    await expect(rows).toHaveCount(2)
    await expect(rows.nth(0)).toContainText('首付 30%')
    await expect(rows.nth(0)).toContainText('确认方案后支付')
    await expect(rows.nth(0)).toContainText('¥5,580')
    await expect(rows.nth(1)).toContainText('按次结算 70%')
    await expect(rows.nth(1)).toContainText('每次验收后支付 ¥2,170，共 6 次')
    await expect(rows.nth(1)).toContainText('¥13,020')
    await shoot(page, 'plan-confirm')

    await page.locator('uni-button.plan-confirmation__agreement').click()
    await page.locator('uni-button.plan-confirmation__confirm').click()
    const pay = page.getByTestId('plan-pay-down')
    await expect(pay).toHaveText('支付首付 ¥5,580')
    await expect(rows.nth(0)).toContainText('待支付')
    await shoot(page, 'plan-confirmed')

    await pay.click()
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥5,580')
    await expect(page.getByTestId('cashier-countdown')).toHaveText('付款节点 1 · 首付 30%（方案 v2 已确认）')
    await expect(page.getByTestId('cashier-inspection-order')).toHaveText('物流园区季度安防巡检 · 方案 v2')
    await expect(page.getByTestId('cashier-payee')).toHaveText('鹰眼智能巡检')
    await expect(page.getByTestId('cashier-inspection-payer')).toHaveText('东营港务物流（企业身份）')
    await expect(page.getByTestId('cashier-inspection-node')).toHaveText('首付 30% ¥5,580 · 其余按次结算')
    await expect(page.getByTestId('cashier-pay')).toHaveText('确认支付 ¥5,580')
    expect(payments).toHaveLength(1)
    await shoot(page, 'cashier-first')
  })

  test('refuses to confirm when the provider cannot collect, saying why', async ({ page }) => {
    await answerApi(page, (path, method): Answer | null => {
      if (path.endsWith('/confirm') && method === 'POST') {
        return { status: 409, body: { code: 'INS_PROVIDER_PAYMENT_UNAVAILABLE', message: '服务商暂未开通在线收款，暂不能确认方案或付款，请联系服务商', traceId: 't' } }
      }
      return planAnswers(nodePlan())(path)
    })
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)
    await page.locator('uni-button.plan-confirmation__agreement').click()
    await page.locator('uni-button.plan-confirmation__confirm').click()
    await expect(page.locator('.plan-confirmation__error')).toHaveText('服务商暂未开通在线收款，暂不能确认方案或付款，请联系服务商')
  })

  test('an expired one-visit plan takes no payment', async ({ page }) => {
    await answerApi(page, planAnswers(plan({
      validUntil: '2026-11-02T18:00:00+08:00',
      totalAmountMinor: 300_000,
      paymentNodes: [
        { nodeNo: 1, nodeType: 'DOWN_PAYMENT', visitNo: null, amountMinor: 90_000 },
        { nodeNo: 2, nodeType: 'PER_VISIT', visitNo: 1, amountMinor: 210_000 },
      ],
    })))
    await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)

    const rows = page.locator('.plan-payment__row')
    await expect(rows.nth(0)).toContainText('首付 30%')
    await expect(rows.nth(1)).toContainText('验收后付款 70%')
    await expect(rows.nth(0)).toContainText('方案过期后不可支付')
    await expect(rows.nth(1)).toContainText('方案过期后不可支付')
    await shoot(page, 'plan-expired')
  })
})

test.describe('fees after acceptance (board FulfillmentLater)', () => {
  const later = order(sixVisits(['ACCEPTED', 'ACCEPTED', 'DELIVERED', 'IN_PROGRESS', 'SCHEDULED', 'PENDING_SCHEDULE'], {
    3: { originalPlannedDate: '2026-12-01' },
  }), {
    status: 'IN_PROGRESS',
    paymentMode: 'NODES',
    paymentNodes: nodes({ 1: { paidAt: '2026-11-01T09:40:00+08:00' }, 2: { paidAt: '2026-11-03T17:00:00+08:00' }, 3: { payable: true } }),
    paymentRecords: [],
  })

  test('marks each accepted visit, reminds of the due fee and pays it', async ({ page }) => {
    let opened = false
    await answerApi(page, (path, method): Answer | null => {
      if (path.endsWith('/payment-nodes/3/payment') && method === 'POST') {
        opened = true
        return { body: { nodeNo: 3, tradeOrderId: FEE_TRADE, payBefore: '2026-11-03T09:15:00+08:00', paid: false } }
      }
      if (path === `/api/v1/trade/orders/${FEE_TRADE}`) return { body: tradeOrder(FEE_TRADE, '第 2/6 次服务费', FEE) }
      if (path === `/api/v1/inspection/service-orders/${ORDER_ID}`) {
        return { body: opened
          ? { ...later, paymentRecords: [{ attemptId: 'a3', nodeNo: 3, nodeType: 'PER_VISIT', visitNo: 2, tradeOrderId: FEE_TRADE, amountMinor: FEE, status: 'OPEN', payBefore: '2026-11-03T09:15:00+08:00', createdAt: '2026-11-03T09:00:00+08:00' }] }
          : later }
      }
      if (path.includes('/plans/')) return { body: nodePlan({ status: 'CONFIRMED', serviceOrderId: ORDER_ID }) }
      return null
    })
    await page.goto(`/#/pages/service/inspection/fulfillment?serviceOrderId=${ORDER_ID}`)

    await expect(page.getByTestId('visit-fee-1')).toHaveText('服务费已支付')
    await expect(page.getByTestId('visit-fee-2')).toHaveText('服务费 ¥2,170 待支付')
    await expect(page.getByTestId('visit-row-3')).toContainText('原定 12/01，服务商已改期')
    await expect(page.getByTestId('fulfillment-due')).toContainText('第 2 次服务费 ¥2,170 待支付')
    await expect(page.getByTestId('fulfillment-due')).toContainText('不影响后续巡检，全部结清后服务单才算完成')
    await shoot(page, 'fulfillment-later')

    await page.getByTestId('fulfillment-pay').click()
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥2,170')
    await expect(page.getByTestId('cashier-countdown')).toHaveText('按次结算 · 第 2/6 次服务费（第 2 次已验收）')
    await expect(page.getByTestId('cashier-inspection-node')).toHaveText('首付已付 · 第 2/6 次 ¥2,170')
    await shoot(page, 'cashier-final')
  })

  test('the delivery page pays this visit after acceptance, then says it is paid', async ({ page }) => {
    let paid = false
    const accepted = () => order(sixVisits(['ACCEPTED', 'PENDING_SCHEDULE', 'PENDING_SCHEDULE', 'PENDING_SCHEDULE', 'PENDING_SCHEDULE', 'PENDING_SCHEDULE']), {
      paymentMode: 'NODES',
      paymentNodes: nodes({ 1: { paidAt: '2026-11-01T09:40:00+08:00' }, 2: paid ? { paidAt: '2026-11-03T17:00:00+08:00' } : { payable: true } }),
    })
    await answerApi(page, (path): Answer | null => {
      if (path.endsWith('/delivery')) return { body: delivery(SIX[0], { status: 'ACCEPTED', acceptedAt: '2026-11-03T16:20:00+08:00', acceptedByName: '王女士' }) }
      if (path === `/api/v1/inspection/service-orders/${ORDER_ID}`) return { body: accepted() }
      if (path.includes('/plans/')) return { body: nodePlan({ status: 'CONFIRMED', serviceOrderId: ORDER_ID }) }
      return null
    })
    await page.goto(`/#/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}&visitId=${SIX[0]}`)

    await expect(page.getByTestId('delivery-pay')).toHaveText('支付第 1 次服务费 ¥2,170')
    await expect(page.getByTestId('delivery-fee')).toContainText('按次结算：每次验收后支付 ¥2,170，共 6 次')
    await shoot(page, 'delivery-fee-due')

    paid = true
    await page.reload()
    await expect(page.getByTestId('delivery-fee-paid')).toHaveText('第 1 次服务费 ¥2,170 已支付')
    await expect(page.getByTestId('delivery-pay')).toHaveCount(0)
  })

  test('a returned delivery waits for the provider and cannot be accepted', async ({ page }) => {
    await answerApi(page, (path): Answer | null => {
      if (path.endsWith('/delivery')) return { body: delivery(FIRST_VISIT, { status: 'RETURNED' }) }
      if (path === `/api/v1/inspection/service-orders/${ORDER_ID}`) {
        return { body: order([visit(1, FIRST_VISIT, 'REWORK', { rework: { id: 'w', visitId: FIRST_VISIT, deliveryRevision: 1, reason: '第 3 段线路没有拍到', requestedAt: '2026-11-03T08:00:00+08:00', resolvedAt: null } })]) }
      }
      if (path.includes('/plans/')) return { body: plan({ status: 'CONFIRMED', serviceOrderId: ORDER_ID }) }
      return null
    })
    await page.goto(`/#/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}&visitId=${FIRST_VISIT}`)

    await expect(page.locator('.delivery__chip')).toHaveText('整改中')
    await expect(page.getByTestId('delivery-returned')).toContainText('已退回整改：第 3 段线路没有拍到')
    await expect(page.locator('uni-button.delivery__accept')).toHaveCount(0)
  })
})

test.describe('the result of a visit fee (board CashierFinal) and a one-visit order (CashierOneFinal)', () => {
  test('says what is left and offers the order or the next visits', async ({ page }) => {
    const paidOrder = order(sixVisits(['ACCEPTED', 'PENDING_SCHEDULE', 'PENDING_SCHEDULE', 'PENDING_SCHEDULE', 'PENDING_SCHEDULE', 'PENDING_SCHEDULE']), {
      paymentMode: 'NODES',
      paymentNodes: nodes({ 1: { paidAt: '2026-11-01T09:40:00+08:00' }, 2: { paidAt: '2026-11-03T09:02:00+08:00', tradeOrderId: FEE_TRADE } }),
      paymentRecords: [{ attemptId: 'a2', nodeNo: 2, nodeType: 'PER_VISIT', visitNo: 1, tradeOrderId: FEE_TRADE, amountMinor: FEE, status: 'PAID', payBefore: '2026-11-03T09:15:00+08:00', createdAt: '2026-11-03T09:00:00+08:00', paidAt: '2026-11-03T09:02:00+08:00' }],
    })
    await answerApi(page, (path): Answer | null => {
      if (path.startsWith(`/api/v1/trade/orders/${FEE_TRADE}`)) return { body: tradeOrder(FEE_TRADE, '第 1/6 次服务费', FEE, 'PAID') }
      if (path === `/api/v1/inspection/service-orders/${ORDER_ID}`) return { body: paidOrder }
      return null
    })
    await page.goto(`/#/pages/trade/pay-result?orderId=${FEE_TRADE}`)

    await expect(page.getByTestId('pay-result-paid-title')).toHaveText('第 1 次服务费已支付')
    await expect(page.getByTestId('pay-result-paid-note')).toHaveText('其余 5 次在每次验收后支付')
    await expect(page.getByTestId('pay-result-next')).toHaveText('返回服务订单')
    await expect(page.getByTestId('pay-result-other')).toHaveText('查看后续巡检进度')
    await expect(page.getByTestId('pay-result-inspection-payer')).toHaveText('东营港务物流（企业身份）')
    await shoot(page, 'pay-result-final')
  })
})

test.describe('a one-visit order (boards CashierOneFirst, CashierOneFinal)', () => {
  const ONE_DOWN = 180_000
  const ONE_REST = 420_000
  const single = (paid: boolean) => order([visit(1, FIRST_VISIT, paid ? 'ACCEPTED' : 'PENDING_SCHEDULE')], {
    status: paid ? 'ACCEPTED' : 'AWAITING_PAYMENT',
    paymentMode: 'NODES',
    customerParty: { organizationName: null, operatorName: '王女士' },
    paymentNodes: paid
      ? [
          { nodeNo: 1, nodeType: 'DOWN_PAYMENT', visitNo: null, amountMinor: ONE_DOWN, payable: false, paidAt: '2026-11-04T11:10:00+08:00', tradeOrderId: DOWN_TRADE },
          { nodeNo: 2, nodeType: 'PER_VISIT', visitNo: 1, amountMinor: ONE_REST, payable: false, paidAt: '2026-11-07T15:32:00+08:00', tradeOrderId: FEE_TRADE },
        ]
      : [
          { nodeNo: 1, nodeType: 'DOWN_PAYMENT', visitNo: null, amountMinor: ONE_DOWN, payable: true, paidAt: null, tradeOrderId: DOWN_TRADE },
          { nodeNo: 2, nodeType: 'PER_VISIT', visitNo: 1, amountMinor: ONE_REST, payable: false, paidAt: null, tradeOrderId: null },
        ],
  })

  test('pays 30% first, then the rest after acceptance, and then the order is settled', async ({ page }) => {
    let paid = false
    await answerApi(page, (path): Answer | null => {
      if (path === `/api/v1/trade/orders/${DOWN_TRADE}`) {
        return { body: { ...tradeOrder(DOWN_TRADE, '首付 30%', ONE_DOWN), lines: [{ ...tradeOrder(DOWN_TRADE, '首付 30%', ONE_DOWN).lines[0], productName: '电力线路巡检 · 方案 v1' }] } }
      }
      if (path.startsWith(`/api/v1/trade/orders/${FEE_TRADE}`)) {
        return { body: { ...tradeOrder(FEE_TRADE, '验收后付款 70%', ONE_REST, 'PAID'), lines: [{ ...tradeOrder(FEE_TRADE, '验收后付款 70%', ONE_REST).lines[0], productName: '电力线路巡检 · 方案 v1' }] } }
      }
      if (path === `/api/v1/inspection/service-orders/${ORDER_ID}`) return { body: single(paid) }
      return null
    })
    await page.goto(`/#/pages/trade/cashier?orderId=${DOWN_TRADE}`)
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥1,800')
    await expect(page.getByTestId('cashier-countdown')).toHaveText('付款节点 1 · 首付 30%（方案 v1 已确认）')
    await expect(page.getByTestId('cashier-inspection-payer')).toHaveText('王女士（个人身份）')
    await expect(page.getByTestId('cashier-inspection-node')).toHaveText('首付 ¥1,800 · 验收后付 ¥4,200')
    await shoot(page, 'cashier-one-first')

    paid = true
    await page.goto(`/#/pages/trade/pay-result?orderId=${FEE_TRADE}`)
    await expect(page.getByTestId('pay-result-paid-title')).toHaveText('验收款已支付')
    await expect(page.getByTestId('pay-result-paid-note')).toHaveText('全部款项已结清，服务单已完成')
    await expect(page.getByTestId('pay-result-next')).toHaveText('查看服务进度')
    await shoot(page, 'pay-result-one-final')
  })
})

test.describe('service order detail (board OrderService)', () => {
  test('shows where the order stands, its nodes and paid records, and leads to acceptance', async ({ page }) => {
    const detail = order(sixVisits(['DELIVERED', 'SCHEDULED', 'PENDING_SCHEDULE', 'PENDING_SCHEDULE', 'PENDING_SCHEDULE', 'PENDING_SCHEDULE']), {
      paymentMode: 'NODES',
      paymentNodes: nodes({ 1: { paidAt: '2026-11-01T09:40:00+08:00', tradeOrderId: DOWN_TRADE } }),
      paymentRecords: [{ attemptId: 'a1', nodeNo: 1, nodeType: 'DOWN_PAYMENT', visitNo: null, tradeOrderId: DOWN_TRADE, amountMinor: DOWN, status: 'PAID', payBefore: '2026-11-01T09:45:00+08:00', createdAt: '2026-11-01T09:31:00+08:00', paidAt: '2026-11-01T09:40:00+08:00' }],
    })
    await answerApi(page, (path): Answer | null => {
      if (path.endsWith('/delivery')) return { body: delivery(SIX[0], { issueCount: 3 }) }
      if (path === `/api/v1/inspection/service-orders/${ORDER_ID}`) return { body: detail }
      if (path.includes('/plans/')) {
        return { body: nodePlan({ status: 'CONFIRMED', serviceOrderId: ORDER_ID, confirmedAt: '2026-11-01T09:30:00+08:00', deliverables: [{ code: 'R', label: '巡检报告' }, { code: 'D', label: '隐患清单' }, { code: 'M', label: '影像存档' }] }) }
      }
      return null
    })
    await page.goto(`/#/pages/mine/inspection-order?serviceOrderId=${ORDER_ID}`)

    await expect(page.getByTestId('order-detail-headline')).toHaveText('履约中 · 第 1 次待验收')
    const payment = page.getByTestId('order-detail-payment')
    await expect(payment).toContainText('合同金额 ¥18,600')
    await expect(payment).toContainText('已支付 · 11/01')
    await expect(payment).toContainText('第 1 次待验收')
    await expect(payment).toContainText('每次验收后支付 ¥2,170 · 已付 0/6')
    await expect(page.getByTestId('order-detail-records')).toContainText('首付 ¥5,580')
    await expect(page.getByTestId('order-detail-action')).toHaveText('去验收')
    await shoot(page, 'order-service')

    await page.getByTestId('order-detail-action').click()
    await expect(page.locator('.delivery__header').filter({ visible: true })).toBeVisible()
  })
})

test.describe('order cards (M08 and 我的订单)', () => {
  test('a due node says 去付款 and opens the cashier', async ({ page }) => {
    const summary = {
      reservationId: RESERVATION_ID, reservationNo: 'IR1', serviceId: 's1', serviceTitle: '物流园区季度安防巡检',
      cityCode: '370500', locationSummary: '东营港物流园', preferredServiceDate: '2026-11-04', submittedAt: '2026-10-28T09:00:00+08:00',
      stage: 'IN_PROGRESS', reservationStatus: 'PLAN_PUBLISHED', currentPlanId: PLAN_ID, serviceOrderId: ORDER_ID,
      serviceOrderStatus: 'AWAITING_PAYMENT', quoteAmountMinor: 1_860_000, currency: 'CNY', visitCount: 6, acceptedVisitCount: 0,
      paymentDueNodeNo: 1, paymentDueNodeType: 'DOWN_PAYMENT', paymentDueAmountMinor: DOWN,
    }
    await answerApi(page, (path, method): Answer | null => {
      if (path === '/api/v1/inspection/reservations' && method === 'GET') {
        return { body: { items: [summary], page: 1, size: 20, total: 1, hasMore: false, stageCounts: { AWAITING_PLAN: 0, AWAITING_CONFIRMATION: 0, IN_PROGRESS: 1, AWAITING_ACCEPTANCE: 0, COMPLETED: 0 } } }
      }
      if (path.endsWith('/payment-nodes/1/payment') && method === 'POST') {
        return { body: { nodeNo: 1, tradeOrderId: DOWN_TRADE, payBefore: '2026-11-03T09:15:00+08:00', paid: false } }
      }
      if (path === `/api/v1/trade/orders/${DOWN_TRADE}`) return { body: tradeOrder(DOWN_TRADE, '首付 30%', DOWN) }
      if (path === `/api/v1/inspection/service-orders/${ORDER_ID}`) {
        return { body: order(sixVisits(Array(6).fill('PENDING_SCHEDULE')), { status: 'AWAITING_PAYMENT', paymentMode: 'NODES', paymentNodes: nodes({ 1: { payable: true, tradeOrderId: DOWN_TRADE } }) }) }
      }
      return null
    })
    await page.goto('/#/pages/mine/inspection-orders')

    const card = page.getByTestId(`inspection-order-${RESERVATION_ID}`)
    await expect(card).toContainText('待付首付')
    await card.getByTestId('inspection-order-pay').click()
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥5,580')
  })
})

function planAnswers(currentPlan: unknown) {
  return (path: string): Answer | null => {
    if (path.endsWith('/plans')) return { body: [] }
    if (path.includes('/plans/')) return { body: currentPlan }
    if (path.includes('/reservations/')) return { body: reservation() }
    return null
  }
}
