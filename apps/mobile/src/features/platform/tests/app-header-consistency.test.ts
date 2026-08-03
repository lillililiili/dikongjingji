import { describe, expect, it } from 'vitest'

import appSource from '../../../App.vue?raw'
import rootTabBarSource from '../../../app-shell/components/RootAppTabBar.vue?raw'
import homePageSource from '../../inspection/presentation/platform/home/PlatformHomePage.vue?raw'
import minePageSource from '../../inspection/presentation/platform/mine/PlatformMinePage.vue?raw'
import discoverPageSource from '../presentation/discover/PlatformDiscoverPage.vue?raw'
import mallPageSource from '../presentation/mall/PlatformMallPage.vue?raw'
import servicePageSource from '../presentation/service/PlatformServicePage.vue?raw'
import heroBannerSource from '../../../shared/presentation/PlatformHeroBanner.vue?raw'

const appHeaderModules = import.meta.glob('../../../app-shell/components/AppHeader.vue', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>
const appHeaderSource = Object.values(appHeaderModules)[0] ?? ''

const ROOT_PAGE_SOURCES = [
  homePageSource,
  servicePageSource,
  discoverPageSource,
  mallPageSource,
]

describe('root page AppHeader consistency', () => {
  it('renders the shared AppHeader on four public root pages and omits it on Mine', () => {
    for (const source of ROOT_PAGE_SOURCES) {
      expect(source).toContain("AppHeader")
      expect(source).toContain('<AppHeader')
    }

    expect(homePageSource).not.toContain('platform-home__top')
    expect(servicePageSource).not.toContain('platform-service-header')
    expect(discoverPageSource).not.toContain('PlatformPageHeader')
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

  it('uses one primary Banner height token on Home, Discover, and Mall', () => {
    expect(appSource).toContain('--app-primary-banner-height: 248rpx')
    expect(heroBannerSource).toMatch(/height:\s*var\(--app-primary-banner-height\)/)

    for (const source of [homePageSource, discoverPageSource, mallPageSource]) {
      expect(source).toContain('<PlatformHeroBanner')
    }
  })

  it('keeps the fixed tab bar clear of content and marks selection without relying on color alone', () => {
    expect(appSource).toContain('--app-root-tabbar-content-offset')
    expect(rootTabBarSource).toContain('.root-tab-bar__item--active')
    expect(rootTabBarSource).toContain('background: #fff2e8')
    expect(rootTabBarSource).toContain('font-weight: 800')
    expect(rootTabBarSource).not.toContain('shopping-bag-active.png')
  })
})
