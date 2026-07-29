import { describe, expect, it } from 'vitest'
import homePageSource from '../presentation/platform/home/PlatformHomePage.vue?raw'
import {
  resolvePlatformHomeState,
  type PlatformHomeContent,
} from '../presentation/platform/home/platform-home'

const content = (): PlatformHomeContent => ({
  cityName: '济南',
  weatherText: '多云 26℃',
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
      iconType: 'checkbox-filled',
      iconColor: '#16a34a',
      iconBackground: '#dcfce7',
      availability: 'AVAILABLE',
    },
    {
      code: 'AERIAL_TOUR',
      title: '低空旅游',
      subtitle: '空中观光体验',
      iconType: 'paperplane-filled',
      iconColor: '#06b6d4',
      iconBackground: '#cffafe',
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
})

describe('platform home presentation state', () => {
  it('supports loading, ready, empty and error without fixtures', () => {
    expect(resolvePlatformHomeState({ status: 'loading' })).toEqual({ kind: 'loading' })
    expect(resolvePlatformHomeState({ status: 'ready', content: content() }).kind).toBe('ready')
    expect(resolvePlatformHomeState({
      status: 'ready',
      content: {
        cityName: '济南',
        weatherText: '多云 26℃',
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

    expect(state.content.weatherText).toBe('多云 26℃')
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
  it('is a pure prop/event presentation component with visible metrics and owned icons', () => {
    expect(homePageSource).toContain('defineProps')
    expect(homePageSource).toContain('defineEmits')
    expect(homePageSource).toContain("import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'")
    expect(homePageSource).toContain(':type="entry.iconType"')
    expect(homePageSource).toContain("entry.availability === 'UNAVAILABLE'")
    expect(homePageSource).toContain('暂未开放')
    expect(homePageSource).not.toContain('v-if="props.state.content.weatherText"')
    expect(homePageSource).not.toContain('v-if="props.state.content.unreadCount')
    expect(homePageSource).not.toContain('platform-home__search-icon::after')
    expect(homePageSource).not.toContain('fixture')
    expect(homePageSource).not.toContain('uni.request')
    expect(homePageSource).not.toContain('navigateTo')
  })

  it('contains narrow-width protection and safe-area spacing', () => {
    expect(homePageSource).toContain('grid-template-columns:repeat(4,minmax(0,1fr))')
    expect(homePageSource).toContain('overflow-wrap:break-word')
    expect(homePageSource).toContain('env(safe-area-inset-bottom)')
    expect(homePageSource).toContain('@media (min-width:420px)')
  })
})
