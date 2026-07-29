import { describe, expect, it } from 'vitest'
import servicePageSource from '../presentation/platform/service/PlatformServicePage.vue?raw'
import {
  resolvePlatformServiceState,
  type PlatformServiceContent,
} from '../presentation/platform/service/platform-service'

const content = (): PlatformServiceContent => ({
  cityName: '济南',
  weatherText: '多云 26℃',
  unreadCount: 3,
  activeChannelCode: 'ROBOT',
  channels: [
    { code: 'INSPECTION', label: '我要巡检', availability: 'AVAILABLE' },
    { code: 'ROBOT', label: '机器人服务', availability: 'AVAILABLE' },
    { code: 'MORE', label: '更多服务', availability: 'UNAVAILABLE' },
  ],
  hero: {
    imageUrl: '/static/images/inspection/platform/service-hero.png',
    title: '机器人服务',
    highlight: '让生活更智能',
    description: '专业机器人服务，更安全、更高效',
  },
  scenarios: [
    {
      code: 'INDUSTRIAL_INSPECTION',
      title: '巡检机器人',
      subtitle: '电力 · 石化 · 园区',
      iconType: 'auth-filled',
      iconColor: '#16a34a',
      iconBackground: '#dcfce7',
      availability: 'AVAILABLE',
    },
    {
      code: 'MORE',
      title: '更多场景',
      subtitle: '定制化解决方案',
      iconType: 'more-filled',
      iconColor: '#64748b',
      iconBackground: '#e2e8f0',
      availability: 'UNAVAILABLE',
    },
  ],
  capabilities: [],
  recommendedServices: [{
    id: 'inspection-robot',
    title: '电力巡检服务',
    subtitle: '线路巡检｜隐患识别',
    imageUrl: '/static/images/inspection/platform/industrial-inspection-robot.png',
    availability: 'AVAILABLE',
    quoteLabel: '¥699起',
    rating: 4.9,
    soldCountText: '已售8560+',
    actionLabel: '立即预约',
  }],
})

describe('platform service presentation state', () => {
  it('supports loading, ready, empty and error states', () => {
    expect(resolvePlatformServiceState({ status: 'loading' })).toEqual({ kind: 'loading' })
    expect(resolvePlatformServiceState({ status: 'ready', content: content() }).kind).toBe('ready')
    expect(resolvePlatformServiceState({
      status: 'ready',
      content: {
        cityName: '济南',
        weatherText: '多云 26℃',
        unreadCount: 0,
        activeChannelCode: 'ROBOT',
        channels: [],
        scenarios: [],
        capabilities: [],
        recommendedServices: [],
      },
    })).toEqual({ kind: 'empty' })
    expect(resolvePlatformServiceState({ status: 'error' })).toEqual({
      kind: 'error',
      message: '服务频道加载失败，请稍后重试',
    })
  })

  it('keeps weather, unread, rating, sales and prices visible from content', () => {
    const state = resolvePlatformServiceState({ status: 'ready', content: content() })
    expect(state.kind).toBe('ready')
    if (state.kind !== 'ready') return

    expect(state.content.weatherText).toBe('多云 26℃')
    expect(state.content.unreadCount).toBe(3)
    expect(state.content.recommendedServices[0]).toMatchObject({
      quoteLabel: '¥699起',
      rating: 4.9,
      soldCountText: '已售8560+',
      actionLabel: '立即预约',
    })
    expect(state.content.scenarios[1]?.availability).toBe('UNAVAILABLE')
  })
})

describe('platform service component contract', () => {
  it('uses props/events, visible metrics and owned icons without navigation or fixtures', () => {
    expect(servicePageSource).toContain('defineProps')
    expect(servicePageSource).toContain('defineEmits')
    expect(servicePageSource).toContain("import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'")
    expect(servicePageSource).toContain(':type="scenario.iconType"')
    expect(servicePageSource).toContain('暂未开放')
    expect(servicePageSource).toContain("availability === 'UNAVAILABLE'")
    expect(servicePageSource).not.toContain('v-if="service.quoteLabel"')
    expect(servicePageSource).not.toContain('v-if="service.rating"')
    expect(servicePageSource).not.toContain('v-if="service.soldCountText"')
    expect(servicePageSource).not.toContain('platform-service__search-icon::after')
    expect(servicePageSource).toContain('platform-service__service-action')
    expect(servicePageSource).not.toContain('fixture')
    expect(servicePageSource).not.toContain('uni.request')
    expect(servicePageSource).not.toContain('navigateTo')
    expect(servicePageSource).not.toContain('立即支付')
  })

  it('protects dense mobile grids at 375 to 430 pixels', () => {
    expect(servicePageSource).toContain('grid-template-columns:repeat(4,minmax(0,1fr))')
    expect(servicePageSource).toContain('overflow-wrap:break-word')
    expect(servicePageSource).toContain('env(safe-area-inset-bottom)')
    expect(servicePageSource).toContain('@media (min-width:420px)')
  })
})
