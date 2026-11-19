import { describe, expect, it } from 'vitest'

import appSource from '../../../App.vue?raw'
import rootTabBarSource from '../../../app-shell/components/RootAppTabBar.vue?raw'
import homeRouteSource from '../../../pages/home/index.vue?raw'
import homePageSource from '../../inspection/presentation/platform/home/PlatformHomePage.vue?raw'
import minePageSource from '../../inspection/presentation/platform/mine/PlatformMinePage.vue?raw'
import mallPageSource from '../presentation/mall/PlatformMallPage.vue?raw'
import heroBannerSource from '../../../shared/presentation/PlatformHeroBanner.vue?raw'

const appHeaderModules = import.meta.glob('../../../app-shell/components/AppHeader.vue', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>
const appHeaderSource = Object.values(appHeaderModules)[0] ?? ''

// 发现 has its own title row from the board Discover since step H1 (content feature).
const APP_HEADER_PAGE_SOURCES = [
  mallPageSource,
]

describe('root page AppHeader consistency', () => {
  it('renders the shared AppHeader on Mall, the two-layer header on Home, and neither on Mine', () => {
    for (const source of APP_HEADER_PAGE_SOURCES) {
      expect(source).toContain("AppHeader")
      expect(source).toContain('<AppHeader')
    }

    expect(homeRouteSource).toContain('<HomeHeader')
    expect(homeRouteSource).not.toContain('<AppHeader')
    expect(homePageSource).not.toContain('<AppHeader')
    expect(mallPageSource).not.toContain('PlatformPageHeader')
    expect(minePageSource).not.toContain('AppHeader')
    expect(minePageSource).not.toContain('<AppHeader')
  })

  it('owns every measurable header token inside AppHeader', () => {
    for (const testId of [
      'app-header',
      'app-header-city',
      'app-header-search',
      'app-header-message',
    ]) {
      expect(appHeaderSource).toContain(`data-testid="${testId}"`)
    }

    for (const token of [
      '--app-header-height',
      '--app-header-location-width',
      '--app-header-search-height',
      '--app-header-message-width',
      '--app-header-gap',
      '--app-header-search-radius',
      '--app-header-search-border-width',
      '--app-header-search-padding-inline',
    ]) {
      expect(appHeaderSource).toContain(token)
    }

    expect(appHeaderSource).toContain('width: 100%;')
    expect(appHeaderSource).not.toContain('width: 100vw')
    expect(appHeaderSource).not.toContain('margin-left: calc((100% - 100vw) / 2)')
  })

  it('uses one primary Banner height token on Home and Mall', () => {
    expect(appSource).toContain('--app-primary-banner-height: 248rpx')
    expect(heroBannerSource).toMatch(/height:\s*var\(--app-primary-banner-height\)/)

    for (const source of [homePageSource, mallPageSource]) {
      expect(source).toContain('<PlatformHeroBanner')
    }
  })

  it('keeps the fixed tab bar clear of content and marks selection without relying on color alone', () => {
    expect(appSource).toContain('--app-root-tabbar-content-offset')
    expect(rootTabBarSource).toContain('.root-tab-bar__item--active')
    expect(rootTabBarSource).toContain('font-weight: 500')
    expect(rootTabBarSource).not.toContain('shopping-bag-active.png')
  })
})
