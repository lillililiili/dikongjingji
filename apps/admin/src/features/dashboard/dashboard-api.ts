import type { ApiClient } from '@/core/http/api-client'

import type { OrderSource } from '../lookup/lookup-api'

// 契约 contracts/openapi-ops-dashboard.yaml 1.1.0：运营端首页“经营概览”，平台运营和平台客服能看。全是汇总数，金额是分。

export type DashboardDays = 7 | 30 | 90
export const DASHBOARD_DAYS: readonly DashboardDays[] = [7, 30, 90]

export interface Dashboard {
  generatedAt: string
  days: number
  fromDate: string
  toDate: string
  customers: { total: number, newInPeriod: number, newPreviousPeriod: number, activeInPeriod: number,
    enterprises: number }
  organizations: { providers: number, qualificationReviewed: number, newInPeriod: number,
    collectingMerchants: number, sellingMerchants: number }
  trade: { netPaidMinor: number, paidMinor: number, refundedMinor: number, paidOrders: number,
    payingCustomers: number, averageOrderMinor: number, previousNetPaidMinor: number, previousPaidOrders: number,
    previousPayingCustomers: number, lifetimeNetPaidMinor: number }
  funnel: { submitted: number, paid: number, kept: number }
  engagement: { checkinPeople: number, activityEnrollments: number, venueBookings: number,
    inspectionReservations: number }
  supply: { openCities: number, products: number, inspectionServices: number, proServices: number,
    activities: number, venues: number, courses: number }
  daily: Array<{ date: string, netPaidMinor: number, paidOrders: number, newCustomers: number }>
  bySource: Array<{ source: OrderSource, netPaidMinor: number, paidOrders: number }>
  byCity: Array<{ cityCode: string, cityName: string, netPaidMinor: number, paidOrders: number }>
  todo: { qualificationReviews: number, merchantProfileReviews: number, providerProfileReviews: number,
    pendingFeedback: number, conversationsAwaitingReply: number, refundFailedOrders: number }
}

export function createDashboardApi(http: ApiClient) {
  return {
    dashboard(days: DashboardDays, signal?: AbortSignal): Promise<Dashboard> {
      return http.get<Dashboard>('/api/v1/ops/dashboard', { days }, signal)
    },
  }
}

export type DashboardApi = ReturnType<typeof createDashboardApi>

const ONE_DECIMAL = new Intl.NumberFormat('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const TWO_DECIMALS = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** 分 → 看板上的大数：不到 1 万元显示到元（¥8,642），1 万元起“¥1,090.3万”，1 亿元起“¥1.09亿”。 */
export function formatBigMoney(minor: number): string {
  const yuan = Math.round(minor / 100)
  const sign = yuan < 0 ? '-' : ''
  const abs = Math.abs(yuan)
  if (abs >= 100_000_000) return `${sign}¥${TWO_DECIMALS.format(abs / 100_000_000)}亿`
  if (abs >= 10_000) return `${sign}¥${ONE_DECIMAL.format(abs / 10_000)}万`
  return `${sign}¥${abs.toLocaleString('en-US')}`
}

export function formatCount(value: number): string {
  return value.toLocaleString('en-US')
}

/** 环比：上期为 0 时没法算，返回 null（页面上不显示箭头）。保留一位小数。 */
export function changeRatio(current: number, previous: number): number | null {
  if (previous <= 0) return null
  return Math.round(((current - previous) / previous) * 1000) / 10
}

/** 百分比，保留一位小数；分母为 0 时给 null。 */
export function percent(part: number, whole: number): number | null {
  if (whole <= 0) return null
  return Math.round((part / whole) * 1000) / 10
}

/** 坐标轴的整齐刻度上限：把最大值向上取到 1、2、2.5、5 乘 10 的幂。 */
export function niceCeiling(value: number): number {
  if (value <= 0) return 1
  const power = 10 ** Math.floor(Math.log10(value))
  const step = [1, 2, 2.5, 5, 10].find((candidate) => candidate * power >= value) ?? 10
  return step * power
}
