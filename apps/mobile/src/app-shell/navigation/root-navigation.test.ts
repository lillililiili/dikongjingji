import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import pages from '../../pages.json'
import tabBarSource from '../components/RootAppTabBar.vue?raw'
import { useHomeChannelStore } from '../state/home-channel'
import {
  DEFAULT_HOME_CHANNEL,
  HOME_CHANNELS,
  isHomeChannelSearchable,
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
    expect(tabBarSource).toContain("INACTIVE_TAB_COLOR = '#8a8f97'")
    expect(tabBarSource).toContain('size="24"')
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
  it('lists the nine resident channels in the approved order', () => {
    expect(HOME_CHANNELS.map((channel) => channel.label)).toEqual([
      '推荐',
      '专业服务',
      '活动',
      '体验',
      '无人机',
      '机器人',
      '配件',
      '租赁',
      '课程',
    ])
    expect(DEFAULT_HOME_CHANNEL).toBe('RECOMMEND')
  })

  it('shows unfinished channels only in preview builds', () => {
    expect(resolveVisibleHomeChannels(true)).toHaveLength(9)
    expect(resolveVisibleHomeChannels(false).map((channel) => channel.code))
      .toEqual(['RECOMMEND', 'SERVICE'])
  })

  it('swaps the search placeholder with the channel', () => {
    expect(resolveHomeSearchPlaceholder('RECOMMEND', true)).toBe('搜索设备、活动或服务')
    expect(resolveHomeSearchPlaceholder('SERVICE', true)).toBe('搜索巡检服务、场景或关键词')
    expect(resolveHomeSearchPlaceholder('ACTIVITY', true)).toBe('搜索赛事、培训、研学')
    expect(resolveHomeSearchPlaceholder('EXPERIENCE', true)).toBe('搜索体验项目或场馆')
    expect(resolveHomeSearchPlaceholder('ROBOT', true)).toBe('搜索机器人型号或品牌')
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
      { channel: 'SERVICE', title: '找专业服务', subtitle: '巡检' },
    ])
  })

  it('moves later entries forward instead of leaving gaps for closed channels', () => {
    expect(resolveHomeKingEntries(true).map((entry) => entry.label)).toEqual([
      '无人机',
      '机器人',
      '专业服务',
      '设备租赁',
      '培训课程',
      '赛事活动',
      '研学体验',
      '线下场馆',
    ])
    expect(resolveHomeKingEntries(false).map((entry) => entry.label))
      .toEqual(['专业服务'])
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
