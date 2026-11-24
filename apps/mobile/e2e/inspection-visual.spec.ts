import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import {
  FIRST_VISIT,
  ORDER_ID,
  PLAN_ID,
  RESERVATION_ID,
  SECOND_VISIT,
  answerApi,
  delivery,
  order,
  plan,
  planAnswers,
  visit,
} from './support/service-flow-fixtures'

// 巡检各页（M01–M08）在 320 / 375 / 430 下的截图和排版检查。
// 样式依据 2026-11-14 原型的浅色主题（计划 §12）；M01–M04 读真实后端，M05–M08 用固定的桩数据。
const visualOutput = resolve(__dirname, '../../../.planning/inspection-theme/visual')
const SERVICE_ID = '33333333-3333-3333-3333-333333333333'
const WIDTHS = [320, 375, 430]

test.use({ timezoneId: 'Asia/Shanghai' })

function orderRow(stage: string, index: number, extra: Record<string, unknown> = {}) {
  return {
    reservationId: `4000000${index}-0000-4000-8000-000000000001`,
    reservationNo: `YY20261101000${index}`,
    serviceId: SERVICE_ID,
    serviceTitle: ['电力输电线路无人机巡检', '光伏电站巡检服务', '园区综合巡检服务'][index % 3],
    cityCode: '370500',
    locationSummary: '东营市东营区示范线路',
    preferredServiceDate: '2026-11-08',
    submittedAt: '2026-11-01T08:00:00Z',
    stage,
    reservationStatus: 'SUBMITTED',
    currency: 'CNY',
    ...extra,
  }
}

async function assertLayout(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

// A label squeezed into a narrow column breaks into one character per line: it is then
// much taller than wide. AGENTS.md §7 forbids that.
async function assertNotStacked(page: Page, selector: string): Promise<void> {
  const stacked = await page.locator(selector).evaluateAll((labels) => labels
    .map((label) => ({ text: label.textContent?.trim() ?? '', box: label.getBoundingClientRect() }))
    .filter(({ text, box }) => text.length >= 3 && box.height > box.width)
    .map(({ text }) => text))
  expect(stacked).toEqual([])
}

interface Screen {
  name: string
  open(page: Page): Promise<void>
  check?(page: Page): Promise<void>
}

for (const scenario of [
  { name: '输电', id: SERVICE_ID, lengthFields: 1 },
  { name: '光伏', id: '66666666-6666-6666-6666-666666666666', lengthFields: 0 },
]) {
  test('预约按服务类目收集线路长度 · ' + scenario.name, async ({ page }) => {
    await page.goto('/#/pages/service/inspection/booking?serviceId=' + scenario.id + '&cityCode=370500')
    await expect(page.locator('.booking-row--time')).toBeVisible()
    const lengthRow = page.locator('.booking-row').filter({ has: page.getByText('线路长度', { exact: true }) })
    await expect(lengthRow.locator('input')).toHaveCount(scenario.lengthFields)
  })
}

const screens: Screen[] = [
  {
    name: 'm01-portal',
    async open(page) {
      await page.goto('/#/pages/home/index')
      await page.getByTestId('home-channel-service').click()
      await expect(page.getByTestId('portal-category-power')).toBeVisible()
      await expect(page.locator('.hot-service-card').first()).toBeVisible()
    },
    async check(page) {
      await expect(page.getByTestId('portal-guarantees')).toContainText('巡检保障')
      await assertNotStacked(page, '.category-grid__name')
    },
  },
  {
    name: 'm02-catalog',
    async open(page) {
      await page.goto('/#/pages/service/inspection/catalog?cityCode=370500')
      await expect(page.locator('.inspection-card').first()).toBeVisible()
    },
    async check(page) {
      await assertNotStacked(page, '.inspection-card__title')
      // The page is exactly one screen high: the list scrolls, the page does not.
      const overflow = await page.evaluate(() =>
        document.documentElement.scrollHeight - window.innerHeight)
      expect(overflow).toBeLessThanOrEqual(1)
    },
  },
  {
    name: 'm03-detail',
    async open(page) {
      await page.goto(`/#/pages/service/inspection/detail?serviceId=${SERVICE_ID}&cityCode=370500`)
      await expect(page.locator('.service-detail-hero__title')).toBeVisible()
    },
    async check(page) {
      await expect(page.locator('.service-detail-hero__unit')).toHaveText('/公里起')
      await assertNotStacked(page, '.inspection-scope__name')
    },
  },
  {
    name: 'm04-booking',
    async open(page) {
      await page.goto(`/#/pages/service/inspection/booking?serviceId=${SERVICE_ID}&cityCode=370500`)
      await expect(page.locator('.booking-row--time')).toBeVisible()
    },
    async check(page) {
      await assertNotStacked(page, '.booking-item text')
      await assertNotStacked(page, '.booking-row__label')
    },
  },
  {
    name: 'm04-submitted',
    async open(page) {
      await answerApi(page, planAnswers(plan()))
      await page.goto(`/#/pages/service/inspection/booking-submitted?reservationId=${RESERVATION_ID}`)
      // The fixture reservation already has a published plan, which the page now says (board InspBooked).
      await expect(page.locator('.booking-submitted__title')).toHaveText('方案已发布')
    },
  },
  {
    name: 'm05-plan',
    async open(page) {
      await answerApi(page, planAnswers(plan()))
      await page.goto(`/#/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)
      await expect(page.locator('.plan-version__valid')).toBeVisible()
    },
  },
  {
    name: 'm06-fulfillment',
    async open(page) {
      await answerApi(page, (path) => {
        if (path.includes('/plans/')) return { body: plan() }
        if (path.includes('/service-orders/')) {
          return { body: order([visit(1, FIRST_VISIT, 'DELIVERED'), visit(2, SECOND_VISIT, 'SCHEDULED')]) }
        }
        return null
      })
      await page.goto(`/#/pages/service/inspection/fulfillment?serviceOrderId=${ORDER_ID}`)
      await expect(page.locator('.fulfillment-summary__status-label')).toBeVisible()
    },
  },
  {
    name: 'm07-delivery',
    async open(page) {
      await answerApi(page, (path) => {
        if (path.endsWith('/delivery')) return { body: delivery(path.split('/visits/')[1].split('/')[0]) }
        if (path.includes('/plans/')) return { body: plan() }
        if (path.includes('/service-orders/')) {
          return { body: order([visit(1, FIRST_VISIT, 'DELIVERED'), visit(2, SECOND_VISIT, 'SCHEDULED')]) }
        }
        return null
      })
      await page.goto(`/#/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}&visitId=${FIRST_VISIT}`)
      await expect(page.locator('.delivery__title')).toBeVisible()
    },
  },
  {
    name: 'm08-orders',
    async open(page) {
      await page.addInitScript(() => localStorage.setItem('inspection.accessToken', 'visual'))
      await page.route('**/api/v1/inspection/reservations?*', (route) => route.fulfill({
        json: {
          items: [
            orderRow('AWAITING_CONFIRMATION', 1, { planId: PLAN_ID }),
            orderRow('IN_PROGRESS', 2, { serviceOrderId: ORDER_ID }),
            orderRow('AWAITING_ACCEPTANCE', 3, { serviceOrderId: ORDER_ID }),
          ],
          page: 1,
          size: 20,
          total: 3,
          hasMore: false,
          stageCounts: {
            AWAITING_PLAN: 0,
            AWAITING_CONFIRMATION: 1,
            IN_PROGRESS: 1,
            AWAITING_ACCEPTANCE: 1,
            COMPLETED: 0,
          },
        },
      }))
      await page.goto('/#/pages/mine/inspection-orders?stage=ALL')
      await expect(page.locator('.inspection-order-card').first()).toBeVisible()
    },
    async check(page) {
      await assertNotStacked(page, '.inspection-orders__filters button text:first-child')
    },
  },
]

for (const screen of screens) {
  test(`VIS-INSP ${screen.name} 在 320/375/430 不溢出、不竖排并留存截图`, async ({ browser }) => {
    mkdirSync(visualOutput, { recursive: true })
    for (const width of WIDTHS) {
      // A fresh page per width: route stubs and the fixed clock are set before the first load.
      const page = await browser.newPage({ viewport: { width, height: 844 } })
      await screen.open(page)
      await page.waitForTimeout(400)
      await assertLayout(page)
      await screen.check?.(page)
      await page.screenshot({ path: resolve(visualOutput, `${screen.name}-${width}.png`) })
      await page.close()
    }
  })
}
