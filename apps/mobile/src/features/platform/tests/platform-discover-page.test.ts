import { describe, expect, it } from 'vitest'

import pages from '../../../pages.json'
import discoverRouteSource from '../../../pages/discover/index.vue?raw'
import discoverFixtureSource from '../data/development/platform-discover.fixture.ts?raw'
import discoverPageSource from '../presentation/discover/PlatformDiscoverPage.vue?raw'

describe('platform discover page', () => {
  it('replaces the placeholder with a custom-navigation preview page', () => {
    expect(discoverRouteSource).toContain('PlatformDiscoverPage')
    expect(discoverRouteSource).toContain('PLATFORM_PREVIEW_ENABLED')
    expect(discoverRouteSource).not.toContain('import.meta.env.DEV')
    expect(discoverRouteSource).toContain('createPlatformFeatureGateway')
    expect(
      pages.pages.find((page) => page.path === 'pages/discover/index')?.style
        .navigationStyle,
    ).toBe('custom')
  })

  it('shows the root bar in both the preview and the closed public state', () => {
    expect(discoverRouteSource).toMatch(
      /发现频道尚未开通<\/text>\s*<\/view>\s*<RootAppTabBar active="DISCOVER" \/>/,
    )
  })

  it('leaves messages to the bottom tab', () => {
    expect(discoverPageSource).toContain(':show-message="false"')
    expect(discoverPageSource).not.toContain('@message=')
  })

  it('uses a side-effect-free static development import so App-Plus does not split code', () => {
    expect(discoverRouteSource).toContain(
      "import { DEVELOPMENT_PLATFORM_DISCOVER_CONTENT } from '@/features/platform/data/development/platform-discover.fixture'",
    )
    expect(discoverRouteSource).not.toContain('await import(')
    expect(discoverRouteSource).not.toContain("import('@/features/platform/data/development")
  })

  it('renders typed content and routes actions through feature intents', () => {
    expect(discoverPageSource).toContain('defineProps')
    expect(discoverPageSource).toContain('content.activities')
    expect(discoverPageSource).toContain('content.stories')
    expect(discoverPageSource).toContain('content.communities')
    expect(discoverPageSource).toContain("type: 'OPEN_ITEM'")
    expect(discoverPageSource).not.toContain('fixture')
    expect(discoverPageSource).not.toContain('¥128')
    expect(discoverPageSource).not.toContain('2.3万')
  })

  it('uses the status-bar variable at the top and keeps the bottom safe area', () => {
    expect(discoverPageSource).toContain('var(--status-bar-height)')
    expect(discoverPageSource).toContain('var(--app-root-tabbar-content-offset)')
  })

  it('uses supported icons, a distinct banner and readable horizontal cards', () => {
    expect(discoverPageSource).toContain("icon: 'folder-add-filled'")
    expect(discoverPageSource).not.toContain('briefcase-filled')
    expect(discoverFixtureSource).toContain("image('discover-hero-v2.png')")
    expect(discoverFixtureSource).not.toContain("image('home-hero.png')")
    expect(discoverPageSource).toContain('grid-auto-flow: column')
    expect(discoverPageSource).toContain('grid-auto-columns: calc((100vw - 49px) / 2)')
    expect(discoverPageSource).toContain('grid-template-columns: repeat(2, minmax(0, 1fr))')
    expect(discoverPageSource).not.toContain('font-size: 8px')
  })

  it('reuses the shared banner with discover-specific points and action', () => {
    expect(discoverPageSource).toContain('<PlatformHeroBanner')
    expect(discoverPageSource).toContain(':highlights="content.hero.highlights"')
    expect(discoverPageSource).toContain("type: 'OPEN_LOCATION'")
    expect(discoverPageSource).not.toContain('discover-page__hero-action')
    expect(discoverFixtureSource).toContain(
      "highlights: ['本地活动', '精选攻略', '兴趣社群']",
    )
    expect(discoverFixtureSource).toContain("title: '发现身边'")
    expect(discoverFixtureSource).toContain("highlight: '低空新体验'")
    expect(discoverFixtureSource).toContain("actionLabel: '发现附近精彩'")
  })
})
