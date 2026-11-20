import { describe, expect, it } from 'vitest'
import minePageSource from '../presentation/platform/mine/PlatformMinePage.vue?raw'
import mineHostSource from '../../../pages/mine/index.vue?raw'
import {
  createLatestRequestCoordinator,
  requestLatestInspectionOrders,
} from '../application/service-flow/inspection-order-navigation'
import type { InspectionOrderPage } from '../domain/service-flow/order-center'
import {
  INSPECTION_STATUS_ITEMS,
  MINE_ORDER_STAGES,
  formatMineBadge,
  formatMineCity,
  isPlatformAmountLabel,
  resolveAuthenticatedMineUser,
} from '../presentation/platform/mine/platform-mine'

const orderPage = (): InspectionOrderPage => ({
  items: [],
  page: 1,
  size: 3,
  total: 0,
  hasMore: false,
  stageCounts: {
    AWAITING_PLAN: 1,
    AWAITING_CONFIRMATION: 2,
    IN_PROGRESS: 3,
    AWAITING_ACCEPTANCE: 4,
    COMPLETED: 5,
  },
})

describe('platform mine presentation state', () => {
  it('shows the real profile once it is read and never invents a level or certification', () => {
    expect(resolveAuthenticatedMineUser(true, '济南市')).toEqual({
      displayName: '已登录',
      locationText: '济南市',
    })
    expect(resolveAuthenticatedMineUser(true, '济南市', {
      displayName: ' 示例客户 ',
      organizationName: '示例单位',
    })).toEqual({
      displayName: '示例客户',
      locationText: '济南市',
      certificationLabel: '示例单位',
    })
    expect(resolveAuthenticatedMineUser(true, undefined, {
      displayName: '',
      organizationName: null,
    })).toEqual({
      displayName: '已登录',
      locationText: '未设置所在地区',
    })
    expect(resolveAuthenticatedMineUser(false, '济南市', {
      displayName: '示例客户',
      organizationName: '示例单位',
    })).toBeUndefined()
  })

  it('writes the city as the board does', () => {
    expect(formatMineCity('东营市')).toBe('常用城市 东营')
    expect(formatMineCity('未设置所在地区')).toBe('未设置常用城市')
    expect(formatMineBadge(3)).toBe('3')
    expect(formatMineBadge(120)).toBe('99+')
  })

  it('does not request authenticated orders without a token', async () => {
    const coordinator = createLatestRequestCoordinator()
    let calls = 0
    const unauthenticated = await requestLatestInspectionOrders(coordinator, () => null, async () => {
      calls++
      return orderPage()
    })
    expect(unauthenticated).toEqual({ status: 'unauthenticated' })
    expect(calls).toBe(0)
    const failed = await requestLatestInspectionOrders(
      coordinator,
      () => 'customer-token',
      async () => { throw new Error('network down') },
    )
    expect(failed.status).toBe('error')
  })

  it('uses the five order stages of the board, across businesses', () => {
    expect(MINE_ORDER_STAGES.map((item) => item.label)).toEqual([
      '待付款',
      '待发货/履约',
      '待参加',
      '待验收',
      '退款/售后',
    ])
  })

  it('keeps the inspection labels the order center still uses', () => {
    expect(INSPECTION_STATUS_ITEMS.map((item) => item.label)).toEqual(['待方案', '待确认', '进行中', '待验收', '已完成'])
    expect(isPlatformAmountLabel('报价')).toBe(true)
    expect(isPlatformAmountLabel('实付')).toBe(false)
  })
})

describe('platform mine component contract', () => {
  it('follows the boards Mine and MineGuest', () => {
    // The old recent-orders card and the four raster tiles are gone.
    expect(minePageSource).not.toContain('近期巡检订单')
    expect(minePageSource).not.toContain('我的项目')
    expect(minePageSource).not.toContain('mine-feature-')
    expect(minePageSource).not.toContain('uni-icons')
    // No headset at the top; 联系客服 is a row.
    expect(minePageSource).not.toContain('mine-customer-service')
    expect(minePageSource).toContain('<IconTile')
    expect(minePageSource).toContain('登录后查看你的订单和服务进度')
    expect(minePageSource).toContain('var(--app-root-tabbar-content-offset)')
    expect(minePageSource).toContain('grid-template-columns: repeat(5, minmax(0, 1fr))')
    expect(minePageSource).not.toContain('navigateTo')
    expect(minePageSource).not.toContain('uni.request')
  })

  it('reads every number from an existing API and renames 到馆预约', () => {
    expect(mineHostSource).toContain("title: '体验预约'")
    expect(mineHostSource).not.toContain("title: '到馆预约'")
    expect(mineHostSource).toContain("tradeApi.list(token, tab, 1, 1)")
    expect(mineHostSource).toContain("enrollmentApi.loadEnrollments(token, 'UPCOMING', 1, 10)")
    expect(mineHostSource).toContain('stageCounts.AWAITING_ACCEPTANCE')
    expect(mineHostSource).not.toContain('立即支付')
  })
})
