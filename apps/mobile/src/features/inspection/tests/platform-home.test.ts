import { describe, expect, it } from 'vitest'
import homePageSource from '../presentation/platform/home/PlatformHomePage.vue?raw'
import {
  resolvePlatformHomeState,
  type PlatformHomeContent,
} from '../presentation/platform/home/platform-home'
import * as homeModel from '../presentation/platform/home/platform-home'

const content = (): PlatformHomeContent => ({
  cityName: '济南',
  weather: {
    available: true,
    text: '多云 26℃',
    iconUrl: '/static/images/inspection/platform/weather-partly-cloudy-v2.png',
    conditionCode: 'PARTLY_CLOUDY',
    observedAt: '2026-07-29T02:30:00Z',
  },
  unreadCount: 3,
  hero: {
    imageUrl: '/static/images/inspection/platform/home-hero.png',
    title: '低空服务',
    highlight: '触手可及',
    description: '无人机 · 机器人，让生活更美好',
  },
  serviceEntries: [
    {
      code: 'INSPECTION',
      title: '巡检服务',
      subtitle: '专业高效安全',
      imageUrl: '/static/images/inspection/platform/home-inspection-service-v2.png',
      availability: 'AVAILABLE',
    },
    {
      code: 'AERIAL_TOUR',
      title: '低空旅游',
      subtitle: '空中观光体验',
      imageUrl: '/static/images/inspection/platform/home-low-altitude-tour-v2.png',
      availability: 'UNAVAILABLE',
    },
  ],
  featuredServices: [{
    id: 'inspection-1',
    title: '热力巡检',
    subtitle: '红外检测',
    imageUrl: '/static/images/inspection/platform/industrial-inspection-robot.png',
    availability: 'AVAILABLE',
    quoteLabel: '¥699起',
    rating: 4.9,
    soldCountText: '已售8560+',
  }],
  activities: [],
  enterpriseSolution: {
    title: '企业解决方案',
    description: '降本增效 · 安全可靠 · 定制服务',
    imageUrl: '/static/images/inspection/platform/enterprise-solution.png',
    availability: 'UNAVAILABLE',
    benefits: [
      { title: '行业解决方案', description: '多行业覆盖', iconType: 'map-filled', color: '#4e8cff' },
      { title: '数据智能分析', description: '洞察更精准', iconType: 'list', color: '#23b26f' },
      { title: '一站式服务', description: '省心更高效', iconType: 'wallet-filled', color: '#ff8a1f' },
    ],
  },
})

describe('platform home presentation state', () => {
  it('supports loading, ready, empty and error without fixtures', () => {
    expect(resolvePlatformHomeState({ status: 'loading' })).toEqual({ kind: 'loading' })
    expect(resolvePlatformHomeState({ status: 'ready', content: content() }).kind).toBe('ready')
    expect(resolvePlatformHomeState({
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
        serviceEntries: [],
        featuredServices: [],
        activities: [],
      },
    })).toEqual({ kind: 'empty' })
    expect(resolvePlatformHomeState({ status: 'error', message: '网络连接失败' })).toEqual({
      kind: 'error',
      message: '网络连接失败',
    })
  })

  it('keeps visible public metrics and unavailable entries explicit', () => {
    const state = resolvePlatformHomeState({ status: 'ready', content: content() })
    expect(state.kind).toBe('ready')
    if (state.kind !== 'ready') return

    expect(state.content.weather.text).toBe('多云 26℃')
    expect(state.content.unreadCount).toBe(3)
    expect(state.content.featuredServices[0]).toMatchObject({
      quoteLabel: '¥699起',
      rating: 4.9,
      soldCountText: '已售8560+',
    })
    expect(state.content.serviceEntries[1]?.availability).toBe('UNAVAILABLE')
  })
})

describe('platform home component contract', () => {
  it('shows approved raster assets and the code-built unavailable dialog', () => {
    expect(homePageSource).toContain('defineProps')
    expect(homePageSource).toContain('defineEmits')
    expect(homePageSource).toContain("import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'")
    expect(homePageSource).toContain(':src="entry.imageUrl"')
    expect(homePageSource).not.toContain(':type="entry.iconType"')
    expect(homePageSource).toContain(':src="props.state.content.weather.iconUrl"')
    expect(homePageSource).toContain('<PlatformUnavailableDialog')
    expect(homePageSource).toContain('resolvePlatformAvailabilityAction')
    expect(homePageSource).not.toContain('platform-home__unavailable')
    expect(homePageSource).not.toContain('v-if="props.state.content.weather')
    expect(homePageSource).not.toContain('v-if="props.state.content.unreadCount')
    expect(homePageSource).not.toContain('platform-home__search-icon::after')
    expect(homePageSource).not.toContain('fixture')
    expect(homePageSource).not.toContain('uni.request')
    expect(homePageSource).not.toContain('navigateTo')
  })

  it('routes unavailable entries to the prompt instead of their original event', () => {
    const resolveAction = Reflect.get(homeModel, 'resolvePlatformAvailabilityAction')
    expect(typeof resolveAction).toBe('function')
    if (typeof resolveAction !== 'function') return

    expect(resolveAction('AVAILABLE')).toBe('PROCEED')
    expect(resolveAction('UNAVAILABLE')).toBe('SHOW_UNAVAILABLE')
  })

  it('uses the structured enterprise solution hierarchy from the approved reference', () => {
    expect(homePageSource).toContain('enterpriseSolution.benefits')
    expect(homePageSource).toContain('platform-home__enterprise-benefit-description')
    expect(homePageSource).toContain('platform-home__enterprise-illustration')
  })

  it('contains narrow-width protection and safe-area spacing', () => {
    expect(homePageSource).toContain('grid-template-columns:repeat(4,minmax(0,1fr))')
    expect(homePageSource).toContain('overflow-wrap:break-word')
    expect(homePageSource).toContain('env(safe-area-inset-bottom)')
    expect(homePageSource).toContain('@media (min-width:420px)')
    expect(homePageSource).toContain('font-family:system-ui,-apple-system,BlinkMacSystemFont,"PingFang SC","Noto Sans CJK SC","Microsoft YaHei",sans-serif')
    expect(homePageSource).toContain('button{margin:0;padding:0;border:0;background:transparent;font-family:inherit')
    expect(homePageSource).toContain('.platform-home__entry-title{display:block;margin-top:5rpx;overflow-wrap:break-word;font-size:24rpx;font-weight:700')
    expect(homePageSource).toContain('.platform-home__entry-subtitle{display:block;margin-top:2rpx;overflow:hidden;color:#747c87;font-size:18rpx')
  })
})
