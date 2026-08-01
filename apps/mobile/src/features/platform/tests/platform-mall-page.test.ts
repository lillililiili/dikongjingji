import { describe, expect, it } from 'vitest'

import pages from '../../../pages.json'
import mallRouteSource from '../../../pages/mall/index.vue?raw'
import mallFixtureSource from '../data/development/platform-mall.fixture.ts?raw'
import mallPageSource from '../presentation/mall/PlatformMallPage.vue?raw'

describe('platform mall page', () => {
  it('replaces the placeholder with a custom-navigation preview page', () => {
    expect(mallRouteSource).toContain('PlatformMallPage')
    expect(mallRouteSource).toContain('PLATFORM_PREVIEW_ENABLED')
    expect(mallRouteSource).not.toContain('import.meta.env.DEV')
    expect(mallRouteSource).toContain('createPlatformFeatureGateway')
    expect(
      pages.pages.find((page) => page.path === 'pages/mall/index')?.style
        .navigationStyle,
    ).toBe('custom')
  })

  it('keeps the native root tab bar visible when platform preview is disabled', () => {
    expect(mallRouteSource).toMatch(
      /if \(PLATFORM_PREVIEW_ENABLED\) \{\s*uni\.hideTabBar\(\{ animation: false \}\)\s*\} else \{\s*uni\.showTabBar\(/,
    )
  })

  it('uses a side-effect-free static development import so App-Plus does not split code', () => {
    expect(mallRouteSource).toContain(
      "import { DEVELOPMENT_PLATFORM_MALL_CONTENT } from '@/features/platform/data/development/platform-mall.fixture'",
    )
    expect(mallRouteSource).not.toContain('await import(')
    expect(mallRouteSource).not.toContain("import('@/features/platform/data/development")
  })

  it('renders typed products without embedding development metrics', () => {
    expect(mallPageSource).toContain('defineProps')
    expect(mallPageSource).toContain('RootAppTabBar active="MALL"')
    expect(mallPageSource).toContain('content.categories')
    expect(mallPageSource).toContain('content.products')
    expect(mallPageSource).toContain('content.rentals')
    expect(mallPageSource).toContain('content.solutions')
    expect(mallPageSource).toContain("type: 'OPEN_ITEM'")
    expect(mallPageSource).not.toContain('fixture')
    expect(mallPageSource).not.toContain('¥69999')
    expect(mallPageSource).not.toContain('已售 3200+')
  })

  it('uses the status-bar variable at the top and keeps the bottom safe area', () => {
    expect(mallPageSource).toContain('var(--status-bar-height)')
    expect(mallPageSource).toContain('var(--app-root-tabbar-content-offset)')
  })

  it('uses a distinct banner and roomy cards instead of compressing five items into one row', () => {
    expect(mallFixtureSource).toContain("image('mall-hero-v2.png')")
    expect(mallFixtureSource).not.toContain("image('home-hero.png')")
    expect(mallPageSource).toContain('grid-template-columns: repeat(2, minmax(0, 1fr))')
    expect(mallPageSource).toContain('grid-auto-flow: column')
    expect(mallPageSource).toContain('grid-auto-columns: calc((100vw - 49px) / 2)')
    expect(mallPageSource).not.toContain('font-size: 7px')
    expect(mallPageSource).not.toContain('font-size: 8px')
  })

  it('reuses the shared banner with mall-specific points and action', () => {
    expect(mallPageSource).toContain('<PlatformHeroBanner')
    expect(mallPageSource).toContain(':highlights="content.hero.highlights"')
    expect(mallPageSource).toContain("itemId: 'mall-hero'")
    expect(mallPageSource).not.toContain('mall-page__hero-action')
    expect(mallFixtureSource).toContain(
      "highlights: ['正品保障', '专业选型', '售后无忧']",
    )
    expect(mallFixtureSource).toContain("actionLabel: '查看精选商品'")
  })
})
