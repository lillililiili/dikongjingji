import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import pages from '../../pages.json'
import tabBarSource from '../components/RootAppTabBar.vue?raw'
import { useHomeChannelStore } from '../state/home-channel'
import { resolveHomeCarouselSlides, stepHomeCarousel } from './home-carousel'
import {
  DEFAULT_HOME_CHANNEL,
  HOME_CHANNELS,
  isHomeChannelSearchable,
  isHomeEntryTargetVisible,
  resolveHomeChannelCards,
  resolveHomeKingEntries,
  resolveHomeSearchPlaceholder,
  resolveVisibleHomeChannels,
} from './home-channels'
import { openHomeChannel } from './home-entry'
import { ROOT_TABS, formatRootTabBadge } from './root-tabs'

const vueSources = import.meta.glob('../../**/*.vue', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

describe('root tabs', () => {
  it('keeps the plan C order and labels', () => {
    expect(ROOT_TABS.map((tab) => tab.code)).toEqual([
      'HOME',
      'DISCOVER',
      'MESSAGE',
      'CART',
      'MINE',
    ])
    expect(ROOT_TABS.map((tab) => tab.label)).toEqual([
      '首页',
      '发现',
      '消息',
      '购物车',
      '我的',
    ])
  })

  it('registers the same five pages in the native tab bar that switchTab relies on', () => {
    expect(pages.tabBar.list.map((item) => `/${item.pagePath}`)).toEqual(
      ROOT_TABS.map((tab) => tab.path),
    )
    expect(pages.tabBar.list.map((item) => item.text)).toEqual(
      ROOT_TABS.map((tab) => tab.label),
    )
    expect(pages.tabBar.selectedColor).toBe('#ff6a00')

    const registeredPaths = pages.pages.map((page) => `/${page.path}`)
    for (const tab of ROOT_TABS) {
      expect(registeredPaths).toContain(tab.path)
    }
  })

  it('caps the badge at 99+ and hides zero or missing counts', () => {
    expect(formatRootTabBadge(undefined)).toBe('')
    expect(formatRootTabBadge(0)).toBe('')
    expect(formatRootTabBadge(3)).toBe('3')
    expect(formatRootTabBadge(99)).toBe('99')
    expect(formatRootTabBadge(100)).toBe('99+')
  })
})

describe('root tab bar', () => {
  it('uses five equal 60px columns and the approved colors', () => {
    expect(tabBarSource).toContain('grid-template-columns: repeat(5, minmax(0, 1fr))')
    expect(tabBarSource).toContain('height: calc(60px + env(safe-area-inset-bottom))')
    expect(tabBarSource).toContain("ACTIVE_TAB_COLOR = '#ff6a00'")
    expect(tabBarSource).toContain("INACTIVE_TAB_COLOR = '#738096'")
    expect(tabBarSource).toContain(':size="24"')
    expect(tabBarSource).toContain('border-top: 1px solid #e7ecf4')
  })

  it('hides the native bar that uni.switchTab still needs', () => {
    expect(tabBarSource).toMatch(
      /onShow\(\(\) => \{\s*uni\.hideTabBar\(\{ animation: false, fail: \(\) => undefined \}\)/,
    )
  })

  it('reports a repeated tap on the active tab instead of switching', () => {
    expect(tabBarSource).toContain("emit('reselect')")
  })

  it('appears only on the five root pages', () => {
    const mountedOn = Object.entries(vueSources)
      .filter(([, source]) => source.includes('<RootAppTabBar'))
      .map(([path]) => path.replace('../../', ''))
      .sort()

    expect(mountedOn).toEqual([
      'pages/cart/index.vue',
      'pages/discover/index.vue',
      'pages/home/index.vue',
      'pages/message/index.vue',
      'pages/mine/index.vue',
    ])
  })
})

describe('home channels', () => {
  it('lists the five resident channels in the approved order', () => {
    expect(HOME_CHANNELS.map((channel) => channel.label)).toEqual([
      '推荐',
      '专业服务',
      '赛事研学',
      '体验',
      '商城',
    ])
    expect(DEFAULT_HOME_CHANNEL).toBe('RECOMMEND')
  })

  it('shows unfinished channels only in preview builds', () => {
    expect(resolveVisibleHomeChannels(true)).toHaveLength(5)
    expect(resolveVisibleHomeChannels(false).map((channel) => channel.code))
      .toEqual(['RECOMMEND', 'SERVICE'])
  })

  it('swaps the search placeholder with the channel', () => {
    expect(resolveHomeSearchPlaceholder('RECOMMEND', true)).toBe('搜索设备、赛事研学或服务')
    expect(resolveHomeSearchPlaceholder('SERVICE', true)).toBe('搜索巡检服务、场景或关键词')
    expect(resolveHomeSearchPlaceholder('ACTIVITY', true)).toBe('搜索赛事、培训、研学')
    expect(resolveHomeSearchPlaceholder('EXPERIENCE', true)).toBe('搜索体验项目或场馆')
    expect(resolveHomeSearchPlaceholder('MALL', true)).toBe('搜索设备、配件或课程')
  })

  it('does not promise device or activity search in public builds', () => {
    expect(resolveHomeSearchPlaceholder('RECOMMEND', false))
      .toBe('搜索巡检服务、场景或关键词')
  })

  it('sends only the recommend and service channels to the inspection catalog', () => {
    expect(isHomeChannelSearchable('RECOMMEND')).toBe(true)
    expect(isHomeChannelSearchable('SERVICE')).toBe(true)
    expect(isHomeChannelSearchable('ACTIVITY')).toBe(false)
  })

  it('leaves one full-width card when only professional services are open', () => {
    expect(resolveHomeChannelCards(true).map((card) => card.title))
      .toEqual(['找专业服务', '去体验'])
    expect(resolveHomeChannelCards(false)).toEqual([
      { channel: 'SERVICE', title: '找专业服务', subtitle: '巡检', icon: 'service' },
    ])
  })

  it('puts the play games where professional services used to be', () => {
    expect(resolveHomeKingEntries(true).map((entry) => entry.label)).toEqual([
      '无人机',
      '机器人',
      '趣玩挑战',
      '设备租赁',
      '培训课程',
      '赛事报名',
      '研学体验',
      '线下场馆',
    ])
    expect(resolveHomeKingEntries(true).find((entry) => entry.code === 'games')?.target)
      .toEqual({ type: 'PAGE', url: '/pages/play/index', publiclyAvailable: false })
  })

  it('sends the product entries to the mall channel', () => {
    const targets = Object.fromEntries(
      resolveHomeKingEntries(true).map((entry) => [entry.code, entry.target]),
    )
    expect(targets.drone).toEqual({ type: 'CHANNEL', channel: 'MALL', mallCategory: 'DRONE' })
    expect(targets.robot).toEqual({ type: 'CHANNEL', channel: 'MALL', mallCategory: 'ROBOT' })
    expect(targets.rental).toEqual({ type: 'CHANNEL', channel: 'MALL', mallCategory: 'RENTAL' })
    expect(targets.course).toEqual({ type: 'CHANNEL', channel: 'MALL', mallCategory: 'COURSE' })
    expect(targets.contest).toEqual({ type: 'CHANNEL', channel: 'ACTIVITY' })
    expect(targets.venue).toEqual({ type: 'CHANNEL', channel: 'EXPERIENCE' })
  })

  it('leaves no king entry in public builds, where the big card is the way in', () => {
    expect(resolveHomeKingEntries(false)).toEqual([])
    expect(isHomeEntryTargetVisible({ type: 'CHANNEL', channel: 'SERVICE' }, false)).toBe(true)
    expect(isHomeEntryTargetVisible({ type: 'CHANNEL', channel: 'MALL' }, false)).toBe(false)
    expect(isHomeEntryTargetVisible(
      { type: 'PAGE', url: '/pages/play/index', publiclyAvailable: true },
      false,
    )).toBe(true)
  })
})

describe('home carousel', () => {
  it('shows the four operator picks of the revised prototype in preview builds', () => {
    const slides = resolveHomeCarouselSlides(true)
    expect(slides.map((slide) => slide.id))
      .toEqual(['contest', 'study-tour', 'games', 'experience'])
    expect(slides.map((slide) => slide.actionLabel)).toEqual([
      '查看赛事 · 去报名',
      '查看研学 · 去报名',
      '开始趣玩挑战',
      '探索体验 · 去预约',
    ])
    expect(slides[2].target).toMatchObject({ type: 'PAGE', url: '/pages/play/index' })
    expect(slides[0].target).toEqual({ type: 'CHANNEL', channel: 'ACTIVITY' })
  })

  it('keeps sample slides out of public builds', () => {
    expect(resolveHomeCarouselSlides(false)).toEqual([])
  })

  it('wraps around in both directions', () => {
    expect(stepHomeCarousel(0, 1, 4)).toBe(1)
    expect(stepHomeCarousel(3, 1, 4)).toBe(0)
    expect(stepHomeCarousel(0, -1, 4)).toBe(3)
    expect(stepHomeCarousel(0, 1, 0)).toBe(0)
  })
})

describe('home channel entry', () => {
  const switchTab = vi.fn()

  beforeEach(() => {
    setActivePinia(createPinia())
    switchTab.mockReset()
    vi.stubGlobal('uni', { switchTab })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('starts on the recommend channel', () => {
    expect(useHomeChannelStore().activeChannel).toBe('RECOMMEND')
  })

  it('selects the channel before switching because switchTab drops query parameters', () => {
    openHomeChannel('SERVICE')

    expect(useHomeChannelStore().activeChannel).toBe('SERVICE')
    expect(switchTab).toHaveBeenCalledOnce()
    expect(switchTab).toHaveBeenCalledWith({ url: '/pages/home/index' })
  })
})
