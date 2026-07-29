import { describe, expect, it } from 'vitest'
import servicePageSource from '../presentation/platform/service/PlatformServicePage.vue?raw'
import {
  resolvePlatformServiceState,
  type PlatformServiceContent,
} from '../presentation/platform/service/platform-service'

const content = (): PlatformServiceContent => ({
  cityName: '济南',
  weather: {
    available: true,
    text: '多云 26℃',
    iconUrl: '/static/images/inspection/platform/weather-partly-cloudy-v2.png',
    conditionCode: 'PARTLY_CLOUDY',
    observedAt: '2026-07-29T02:30:00Z',
  },
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
      imageUrl: '/static/images/inspection/platform/industrial-inspection-robot.png',
      availability: 'AVAILABLE',
    },
    {
      code: 'MORE',
      title: '更多场景',
      subtitle: '定制化解决方案',
      imageUrl: '/static/images/inspection/platform/enterprise-solution.png',
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
        weather: {
          available: false,
          text: '天气暂不可用',
          iconUrl: '/static/images/inspection/platform/weather-unknown.png',
          conditionCode: 'UNKNOWN',
          observedAt: null,
        },
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

    expect(state.content.weather.text).toBe('多云 26℃')
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
  it('uses approved scenario photos, weather asset and an unavailable dialog', () => {
    expect(servicePageSource).toContain('defineProps')
    expect(servicePageSource).toContain('defineEmits')
    expect(servicePageSource).toContain("import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'")
    expect(servicePageSource).toContain(':src="scenario.imageUrl"')
    expect(servicePageSource).not.toContain(':type="scenario.iconType"')
    expect(servicePageSource).toContain(':src="props.state.content.weather.iconUrl"')
    expect(servicePageSource).toContain('<PlatformUnavailableDialog')
    expect(servicePageSource).toContain('resolvePlatformAvailabilityAction')
    expect(servicePageSource).not.toContain('platform-service__unavailable')
    expect(servicePageSource).not.toContain('platform-service__channel-status')
    expect(servicePageSource).toContain('v-if="service.quoteLabel"')
    expect(servicePageSource).toContain('v-if="service.rating !== undefined"')
    expect(servicePageSource).toContain('v-if="service.soldCountText"')
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
    expect(servicePageSource).toContain('font-family:system-ui,-apple-system,BlinkMacSystemFont,"PingFang SC","Noto Sans CJK SC","Microsoft YaHei",sans-serif')
    expect(servicePageSource).toContain('button{margin:0;padding:0;border:0;background:transparent;font-family:inherit')
    expect(servicePageSource).toContain('.platform-service__scenario-image-wrap{width:100%;height:112rpx')
    expect(servicePageSource).toContain('.platform-service__scenario-title{display:block;margin-top:6rpx;overflow-wrap:break-word;font-size:24rpx;font-weight:700')
    expect(servicePageSource).toContain('.platform-service__scenario-subtitle{display:block;margin-top:2rpx;overflow:hidden;color:#737c87;font-size:18rpx')
  })
})
