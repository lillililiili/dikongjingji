import { describe, expect, it } from 'vitest'
import minePageSource from '../presentation/platform/mine/PlatformMinePage.vue?raw'
import {
  createLatestRequestCoordinator,
  requestLatestInspectionOrders,
} from '../application/service-flow/inspection-order-navigation'
import type { InspectionOrderPage } from '../domain/service-flow/order-center'
import {
  INSPECTION_STATUS_ITEMS,
  isPlatformAmountLabel,
  resolveAuthenticatedMineUser,
  resolvePlatformMineState,
  type PlatformMineContent,
} from '../presentation/platform/mine/platform-mine'

const content = (): PlatformMineContent => ({
  user: {
    displayName: '飞行探索者',
    avatarUrl: '/static/images/inspection/platform/robot-avatar.png',
    levelName: 'Lv.3',
    locationText: '济南市历下区',
    certificationLabel: '已认证企业用户',
  },
  statusCounts: {
    AWAITING_PLAN: 2,
    AWAITING_CONFIRMATION: 1,
    IN_PROGRESS: 1,
    AWAITING_ACCEPTANCE: 1,
    COMPLETED: 8,
  },
  recentOrders: [
    {
      id: 'inspection-order-1',
      title: '电力巡检',
      status: 'IN_PROGRESS',
      imageUrl: '/static/images/inspection/platform/industrial-inspection-robot.png',
      locationText: '济南市历下区奥体中心',
      scheduledAtText: '2026-07-31 09:30',
      orderNumber: 'DI202607310930001',
      amount: { label: '报价', text: '¥699' },
    },
  ],
  featureEntries: [
    { code: 'PROJECTS', title: '我的项目', valueText: '3个项目', imageUrl: '/static/images/inspection/platform/mine-feature-projects-v2.png', availability: 'AVAILABLE' },
    { code: 'EQUIPMENT', title: '我的设备', valueText: '2台设备', imageUrl: '/static/images/inspection/platform/mine-feature-equipment-v2.png', availability: 'UNAVAILABLE' },
  ],
})

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
  it('shows a neutral signed-in identity only while an access token is valid', () => {
    expect(resolveAuthenticatedMineUser(true, '济南市')).toEqual({
      displayName: '已登录用户',
      levelName: 'Lv.0',
      locationText: '济南市',
      certificationLabel: '身份待完善',
    })
    expect(resolveAuthenticatedMineUser(false, '济南市')).toBeUndefined()
  })

  it('does not request authenticated orders without a token and preserves ready Mine content on failure', async () => {
    const coordinator = createLatestRequestCoordinator()
    let calls = 0
    const list = async (): Promise<InspectionOrderPage> => {
      calls++
      throw new Error('should not be requested')
    }

    const unauthenticated = await requestLatestInspectionOrders(
      coordinator,
      () => null,
      list,
    )

    expect(unauthenticated).toEqual({ status: 'unauthenticated' })
    expect(calls).toBe(0)
    expect(resolvePlatformMineState({ status: 'ready', content: content() }).kind).toBe('ready')
    const failed = await requestLatestInspectionOrders(
      coordinator,
      () => 'customer-token',
      async () => { throw new Error('network down') },
    )
    expect(failed.status).toBe('error')
    expect(resolvePlatformMineState({ status: 'ready', content: content() }).kind).toBe('ready')
  })

  it('requests three real orders and discards an older response after token removal', async () => {
    const coordinator = createLatestRequestCoordinator()
    let resolveFirst: ((value: InspectionOrderPage) => void) | undefined
    const first = requestLatestInspectionOrders(
      coordinator,
      () => 'customer-token',
      (input) => {
        expect(input).toEqual({ stage: 'ALL', page: 1, size: 3 })
        return new Promise((resolve) => { resolveFirst = resolve })
      },
    )
    const unauthenticated = await requestLatestInspectionOrders(
      coordinator,
      () => null,
      async () => orderPage(),
    )
    resolveFirst?.(orderPage())

    expect(unauthenticated).toEqual({ status: 'unauthenticated' })
    expect(await first).toEqual({ status: 'discarded' })

    const loaded = await requestLatestInspectionOrders(
      coordinator,
      () => 'customer-token',
      async () => orderPage(),
    )
    expect(loaded).toEqual({ status: 'success', value: orderPage() })
  })

  it('supports loading, ready, empty and error states', () => {
    expect(resolvePlatformMineState({ status: 'loading' })).toEqual({ kind: 'loading' })
    expect(resolvePlatformMineState({ status: 'ready', content: content() }).kind).toBe('ready')
    expect(resolvePlatformMineState({
      status: 'ready',
      content: {
        statusCounts: {
          AWAITING_PLAN: 0,
          AWAITING_CONFIRMATION: 0,
          IN_PROGRESS: 0,
          AWAITING_ACCEPTANCE: 0,
          COMPLETED: 0,
        },
        recentOrders: [],
        featureEntries: [],
      },
    })).toEqual({ kind: 'empty' })
    expect(resolvePlatformMineState({ status: 'error' })).toEqual({
      kind: 'error',
      message: '个人中心加载失败，请稍后重试',
    })
  })

  it('uses the five approved inspection statuses in order', () => {
    expect(INSPECTION_STATUS_ITEMS.map((item) => item.label)).toEqual([
      '待方案',
      '待确认',
      '进行中',
      '待验收',
      '已完成',
    ])
  })

  it('only permits quote and payable amount labels', () => {
    expect(isPlatformAmountLabel('报价')).toBe(true)
    expect(isPlatformAmountLabel('应付')).toBe(true)
    expect(isPlatformAmountLabel('实付')).toBe(false)
    expect(isPlatformAmountLabel('退款')).toBe(false)
  })

  it('keeps certification, counters, feature values and order amount visible', () => {
    const state = resolvePlatformMineState({ status: 'ready', content: content() })
    expect(state.kind).toBe('ready')
    if (state.kind !== 'ready') return

    expect(state.content.user?.certificationLabel).toBe('已认证企业用户')
    expect(state.content.statusCounts?.COMPLETED).toBe(8)
    expect(state.content.featureEntries[0]?.valueText).toBe('3个项目')
    expect(state.content.recentOrders[0]?.amount).toEqual({ label: '报价', text: '¥699' })
  })
})

describe('platform mine component contract', () => {
  it('uses approved raster assets, visible counters and a unified unavailable dialog', () => {
    expect(minePageSource).toContain('defineProps')
    expect(minePageSource).toContain('defineEmits')
    expect(minePageSource).toContain("import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'")
    expect(minePageSource).toContain('v-if="content.user?.avatarUrl"')
    expect(minePageSource).not.toContain('v-if="content.user?.certificationLabel"')
    expect(minePageSource).toContain(':src="entry.imageUrl"')
    expect(minePageSource).toContain('mine-status-plan-v2.png')
    expect(minePageSource).toContain('mine-status-completed-v2.png')
    expect(minePageSource).not.toContain(':type="entry.iconType"')
    expect(minePageSource).not.toContain('v-if="entry.valueText"')
    expect(minePageSource).not.toContain('v-if="order.amount"')
    expect(minePageSource).not.toContain('platform-mine__headset-icon::')
    expect(minePageSource).toContain('<PlatformUnavailableDialog')
    expect(minePageSource).toContain('resolvePlatformAvailabilityAction')
    expect(minePageSource).not.toContain('platform-mine__feature-unavailable')
    expect(minePageSource).not.toContain('fixture')
    expect(minePageSource).not.toContain('uni.request')
    expect(minePageSource).not.toContain('navigateTo')
    expect(minePageSource).not.toContain('AppHeader')
    expect(minePageSource).not.toContain('<AppHeader')
    expect(minePageSource).not.toContain('searchPlaceholder')
    expect(minePageSource).not.toContain('message: []')
  })

  it('contains no payment, refund or unsupported workflow state', () => {
    expect(minePageSource).not.toContain('待付款')
    expect(minePageSource).not.toContain('退款')
    expect(minePageSource).not.toContain('立即支付')
    expect(minePageSource).toContain('var(--app-root-tabbar-content-offset)')
    expect(minePageSource).toContain('grid-template-columns:repeat(5,minmax(0,1fr))')
    expect(minePageSource).toContain('@media (min-width:420px)')
    expect(minePageSource).toContain('font-family:system-ui,-apple-system,BlinkMacSystemFont,"PingFang SC","Noto Sans CJK SC","Microsoft YaHei",sans-serif')
    expect(minePageSource).toContain('button{margin:0;padding:0;border:0;background:transparent;font-family:inherit')
    expect(minePageSource).toContain('.platform-mine__status-label{margin-top:4rpx;overflow-wrap:break-word;font-size:22rpx;font-weight:600')
    expect(minePageSource).toContain('.platform-mine__status-icon{position:relative;display:flex;width:100rpx;height:60rpx;align-items:center;justify-content:flex-start')
    expect(minePageSource).toContain('.platform-mine__status-image{width:60rpx;height:60rpx')
    expect(minePageSource).toContain('.platform-mine__status-count{position:absolute;right:0;top:0;display:flex;box-sizing:border-box;min-width:36rpx;height:30rpx;align-items:center;justify-content:center;padding:0 6rpx;border:2rpx solid #fff')
    expect(minePageSource).toContain('.platform-mine__feature-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:4rpx')
    expect(minePageSource).toContain('padding:12rpx 14rpx')
    expect(minePageSource).toContain('.platform-mine__feature-title{margin-top:7rpx;overflow-wrap:break-word;font-size:22rpx;font-weight:600')
  })
})
