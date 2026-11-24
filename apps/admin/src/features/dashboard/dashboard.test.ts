import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { useSessionStore } from '@/core/auth/session'
import { recordingClient } from '@/core/http/recording-client'
import { adminRoutes } from '@/core/navigation/router'
import HomePage from '@/features/auth/HomePage.vue'
import { ADMIN_MODULES } from '@/modules'

import {
  changeRatio,
  createDashboardApi,
  formatBigMoney,
  niceCeiling,
  percent,
  type Dashboard,
  type DashboardApi,
  type DashboardDays,
} from './dashboard-api'
import OpsDashboard from './OpsDashboard.vue'

function dashboard(days: number): Dashboard {
  return {
    generatedAt: '2026-11-24T04:00:00Z',
    days,
    fromDate: days === 7 ? '2026-11-18' : '2026-10-26',
    toDate: '2026-11-24',
    customers: { total: 2425, newInPeriod: 659, newPreviousPeriod: 589, activeInPeriod: 1714, enterprises: 5 },
    organizations: { providers: 50, qualificationReviewed: 34, newInPeriod: 18, collectingMerchants: 39,
      sellingMerchants: 6 },
    trade: { netPaidMinor: 358_940_250, paidMinor: 380_650_100, refundedMinor: 21_709_850, paidOrders: 983,
      payingCustomers: 799, averageOrderMinor: 365_147, previousNetPaidMinor: 240_030_290, previousPaidOrders: 738,
      previousPayingCustomers: 0, lifetimeNetPaidMinor: 1_090_327_970 },
    funnel: { submitted: 1133, paid: 983, kept: 925 },
    engagement: { checkinPeople: 0, activityEnrollments: 0, venueBookings: 0, inspectionReservations: 29 },
    supply: { openCities: 1, products: 13, inspectionServices: 8, proServices: 0, activities: 5, venues: 2,
      courses: 5 },
    daily: [
      { date: '2026-11-23', netPaidMinor: 14_686_800, paidOrders: 35, newCustomers: 29 },
      { date: '2026-11-24', netPaidMinor: 14_472_600, paidOrders: 36, newCustomers: 20 },
    ],
    bySource: [{ source: 'MALL', netPaidMinor: 260_772_090, paidOrders: 553 },
      { source: 'VENUE', netPaidMinor: 950_930, paidOrders: 94 }],
    byCity: [{ cityCode: '370500', cityName: '东营市', netPaidMinor: 152_039_030, paidOrders: 380 }],
    todo: { qualificationReviews: 0, merchantProfileReviews: 1, providerProfileReviews: 0, pendingFeedback: 2,
      conversationsAwaitingReply: 0, refundFailedOrders: 3 },
  }
}

function fakeApi(asked: DashboardDays[] = []): DashboardApi {
  return {
    dashboard: async (days) => {
      asked.push(days)
      return dashboard(days)
    },
  }
}

async function mountDashboard(roles: string[], api: DashboardApi = fakeApi()) {
  const session = useSessionStore()
  session.account = { displayName: '运营', organizationName: '平台', organizationType: 'PLATFORM', roles }
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes(ADMIN_MODULES) })
  const wrapper = mount(OpsDashboard, { props: { api }, global: { plugins: [ElementPlus, router] } })
  await flushPromises()
  return wrapper
}

describe('经营概览', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('金额、环比、百分比、刻度的算法', () => {
    expect(formatBigMoney(864_200)).toBe('¥8,642')
    expect(formatBigMoney(358_940_250)).toBe('¥358.9万')
    expect(formatBigMoney(1_090_327_970)).toBe('¥1,090.3万')
    expect(formatBigMoney(12_345_678_900)).toBe('¥1.23亿')
    expect(changeRatio(358, 240)).toBe(49.2)
    expect(changeRatio(5, 0)).toBeNull()
    expect(changeRatio(90, 100)).toBe(-10)
    expect(percent(983, 1133)).toBe(86.8)
    expect(percent(1, 0)).toBeNull()
    expect(niceCeiling(17_600_000)).toBe(20_000_000)
    expect(niceCeiling(36)).toBe(50)
    expect(niceCeiling(0)).toBe(1)
  })

  it('请求按契约发出，只带统计期天数', async () => {
    const { http, requests } = recordingClient(dashboard(7))
    await createDashboardApi(http).dashboard(7)
    expect(requests.map((request) => request.url)).toEqual(['/api/v1/ops/dashboard?days=7'])
  })

  it('显示累计、成交、客户、机构，上期为 0 的不显示环比', async () => {
    const wrapper = await mountDashboard(['PLATFORM_OPERATOR'])

    expect(wrapper.get('[data-testid="dashboard-hero"]').text()).toContain('¥1,090.3万')
    const net = wrapper.get('[data-testid="kpi-net"]').text()
    expect(net).toContain('¥358.9万')
    expect(net).toContain('↑ 49.5%')
    expect(wrapper.get('[data-testid="kpi-orders"]').text()).toContain('983')
    expect(wrapper.get('[data-testid="kpi-buyers"]').text()).not.toContain('较上期')
    expect(wrapper.get('[data-testid="kpi-customers"]').text()).toContain('2,425')
    expect(wrapper.get('[data-testid="kpi-merchants"]').text()).toContain('39')
    expect(wrapper.text()).toContain('86.8%')
    expect(wrapper.text()).toContain('商城')
    expect(wrapper.text()).toContain('东营市')
  })

  it('切换统计期重新请求', async () => {
    const asked: DashboardDays[] = []
    const wrapper = await mountDashboard(['PLATFORM_OPERATOR'], fakeApi(asked))
    const seven = wrapper.findAll('[data-testid="dashboard-days"] input').find((input) =>
      (input.element as HTMLInputElement).value === '7')
    await seven!.setValue(true)
    await flushPromises()
    expect(asked).toEqual([30, 7])
    expect(wrapper.text()).toContain('2026-11-18 至 2026-11-24')
  })

  it('待处理只给进得去的页面加链接', async () => {
    const links = async (roles: string[]) => {
      const wrapper = await mountDashboard(roles)
      return wrapper.get('[data-testid="dashboard-todo"]').findAll('a').map((link) => link.text())
    }
    expect(await links(['PLATFORM_OPERATOR'])).toEqual(['商家资料待审核', '退款异常的订单'])
    expect(await links(['PLATFORM_SUPPORT'])).toEqual(['举报与反馈待处理', '退款异常的订单'])
  })

  it('首页只给平台运营、平台客服放经营概览', async () => {
    const mountHome = async (roles: string[]) => {
      setActivePinia(createPinia())
      const session = useSessionStore()
      session.account = { displayName: '某人', organizationName: null, organizationType: null, roles }
      const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes(ADMIN_MODULES) })
      return mount(HomePage, { global: { plugins: [ElementPlus, router], stubs: { OpsDashboard: true } } })
    }
    expect((await mountHome(['PLATFORM_SUPPORT'])).findComponent(OpsDashboard).exists()).toBe(true)
    expect((await mountHome(['PROVIDER_OPERATOR'])).findComponent(OpsDashboard).exists()).toBe(false)
  })
})
