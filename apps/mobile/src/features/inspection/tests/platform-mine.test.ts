import { describe, expect, it } from 'vitest'
import minePageSource from '../presentation/platform/mine/PlatformMinePage.vue?raw'
import {
  INSPECTION_STATUS_ITEMS,
  isPlatformAmountLabel,
  resolvePlatformMineState,
  type PlatformMineContent,
} from '../presentation/platform/mine/platform-mine'

const content = (): PlatformMineContent => ({
  user: {
    displayName: '飞行探索者',
    avatarUrl: '/static/images/inspection/platform/robot-avatar.png',
  },
  statusCounts: {},
  recentOrders: [
    {
      id: 'inspection-order-1',
      title: '电力巡检',
      status: 'IN_PROGRESS',
      imageUrl: '/static/images/inspection/platform/industrial-inspection-robot.png',
      amount: { label: '报价', text: '待服务商确认' },
    },
  ],
  featureEntries: [
    { code: 'PROJECTS', title: '我的项目', availability: 'AVAILABLE' },
    { code: 'EQUIPMENT', title: '我的设备', availability: 'UNAVAILABLE' },
  ],
})

describe('platform mine presentation state', () => {
  it('supports loading, ready, empty and error states', () => {
    expect(resolvePlatformMineState({ status: 'loading' })).toEqual({ kind: 'loading' })
    expect(resolvePlatformMineState({ status: 'ready', content: content() }).kind).toBe('ready')
    expect(resolvePlatformMineState({
      status: 'ready',
      content: { statusCounts: {}, recentOrders: [], featureEntries: [] },
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
})

describe('platform mine component contract', () => {
  it('guards optional user data and marks unavailable features', () => {
    expect(minePageSource).toContain('defineProps')
    expect(minePageSource).toContain('defineEmits')
    expect(minePageSource).toContain('v-if="content.user?.avatarUrl"')
    expect(minePageSource).toContain('v-if="content.user?.certificationLabel"')
    expect(minePageSource).toContain('暂未开放')
    expect(minePageSource).toContain("entry.availability === 'UNAVAILABLE'")
    expect(minePageSource).not.toContain('fixture')
    expect(minePageSource).not.toContain('uni.request')
    expect(minePageSource).not.toContain('navigateTo')
  })

  it('contains no payment, refund or unsupported workflow state', () => {
    expect(minePageSource).not.toContain('待付款')
    expect(minePageSource).not.toContain('退款')
    expect(minePageSource).not.toContain('立即支付')
    expect(minePageSource).toContain('env(safe-area-inset-bottom)')
    expect(minePageSource).toContain('grid-template-columns:repeat(5,minmax(0,1fr))')
    expect(minePageSource).toContain('@media (min-width:420px)')
  })
})
