import { describe, expect, it } from 'vitest'
import homeRouteSource from '../../../pages/home/index.vue?raw'
import homePageSource from '../presentation/platform/home/PlatformHomePage.vue?raw'
import {
  resolvePlatformHomeState,
  type PlatformHomeContent,
} from '../presentation/platform/home/platform-home'
import * as homeModel from '../presentation/platform/home/platform-home'
import { DEVELOPMENT_PORTAL_CONTENT } from '../data/fixtures/inspection-portal.fixture'

const content = (): PlatformHomeContent => ({
  hero: {
    imageUrl: '/static/images/inspection/platform/home-hero.png',
    title: '低空服务',
    highlight: '触手可及',
    description: '无人机 · 机器人，让生活更美好',
    highlights: ['多元服务', '专业保障', '便捷响应'],
    actionLabel: '浏览精选服务',
  },
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
  it('returns only inspection portal content supplied by the city', () => {
    const empty = homeModel.createPlatformHomeContentFromPortal({
      ...DEVELOPMENT_PORTAL_CONTENT, hotServices: [], hero: null,
    })
    expect(empty).toEqual({ featuredServices: [], activities: [] })
    expect(homeRouteSource).toContain('createPlatformHomeContentFromPortal(portal)')
  })

  it('resolves the city through the shared rule that protects a hand-picked city', () => {
    expect(homeRouteSource).toContain('uni.getLocation({')
    expect(homeRouteSource).toContain("type: 'gcj02'")
    expect(homeRouteSource).toContain('geocode: true')
    expect(homeRouteSource).toContain('resolveLocatedCityName')
    expect(homeRouteSource).toContain('platformCityApi.load()')
    expect(homeRouteSource).toContain('await resolveCurrentCity({')
    expect(homeRouteSource).not.toContain('loadCurrentByCityName')
  })

  it('keeps the home usable when native location lookup fails', () => {
    expect(homeRouteSource).toContain('restoreHomeCity(previousCode, defaultCity(), () => platformCityApi.load())')
    expect(homeRouteSource).not.toContain('isNativeAppRuntime')
    expect(homeRouteSource).not.toContain('无法获取当前位置，请开启定位权限后重试')
    expect(homeRouteSource).toContain('首页内容加载失败，请稍后重试')
  })

  it('never reaches M01 or the catalog without a city', () => {
    expect(homeRouteSource).toContain('v-if="serviceChannelOpened && currentCityStore.currentCity"')
    expect(homeRouteSource).toMatch(
      /if \(!cityCode\) \{\s*void citySheet\.open\(\)/,
    )
  })

  it('supports loading, ready, empty and error without fixtures', () => {
    expect(resolvePlatformHomeState({ status: 'loading' })).toEqual({ kind: 'loading' })
    expect(resolvePlatformHomeState({ status: 'ready', content: content() }).kind).toBe('ready')
    expect(resolvePlatformHomeState({
      status: 'ready',
      content: {
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

    expect(state.content.featuredServices[0]).toMatchObject({
      quoteLabel: '¥699起',
      rating: 4.9,
      soldCountText: '已售8560+',
    })
    expect(state.content.enterpriseSolution?.availability).toBe('UNAVAILABLE')
  })
})

describe('platform home component contract', () => {
  it('shows approved raster assets and the code-built unavailable dialog', () => {
    expect(homePageSource).toContain('defineProps')
    expect(homePageSource).toContain('defineEmits')
    expect(homePageSource).toContain("import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'")
    expect(homePageSource).toContain('<HomeChannelEntries')
    expect(homePageSource).toContain('<PlatformUnavailableDialog')
    expect(homePageSource).toContain('resolvePlatformAvailabilityAction')
    expect(homePageSource).not.toContain('platform-home__unavailable')
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

  it('renders the three approved hero trust points with library icons', () => {
    expect(homePageSource).toContain('<PlatformHeroBanner')
    expect(homePageSource).toContain(':highlights="props.state.content.hero.highlights"')
    expect(homePageSource).toContain("@action=\"emit('search')\"")
    expect(homePageSource).not.toContain('platform-home__hero-action')
  })

  it('contains narrow-width protection and safe-area spacing', () => {
    expect(homePageSource).toContain('var(--app-page-horizontal-gutter)')
    expect(homeRouteSource).toContain('var(--app-root-tabbar-content-offset)')
    expect(homePageSource).toContain('@media (min-width:420px)')
    expect(homePageSource).toContain('font-family:system-ui,-apple-system,BlinkMacSystemFont,"PingFang SC","Noto Sans CJK SC","Microsoft YaHei",sans-serif')
    expect(homePageSource).toContain('button{margin:0;padding:0;border:0;background:transparent;font-family:inherit')
    expect(homePageSource).toContain('.platform-home__featured-image-wrap{position:relative;height:108px')
  })
})
