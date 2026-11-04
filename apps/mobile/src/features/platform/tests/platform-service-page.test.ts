import { describe, expect, it } from 'vitest'

import homeRouteSource from '../../../pages/home/index.vue?raw'
import inspectionRouteSource from '../../../pages/service/inspection/index.vue?raw'
import serviceRouteSource from '../../../pages/service/index.vue?raw'
import previewSource from '../application/platform-preview.ts?raw'
import serviceFixtureSource from '../data/development/platform-service.fixture.ts?raw'
import channelContentSource from '../presentation/service/PlatformServiceChannelContent.vue?raw'
import servicePageSource from '../presentation/service/PlatformServiceChannel.vue?raw'
import platformChannelSource from '../domain/platform-channel.ts?raw'
import gitIgnoreSource from '../../../../../../.gitignore?raw'

const productionEnvSources = import.meta.glob('../../../../.env.production', {
  eager: true,
  import: 'default',
  query: '?raw',
})

describe('platform service page integration', () => {
  it('defaults to inspection and switches approved service types in place', () => {
    expect(servicePageSource).toContain('DEFAULT_PLATFORM_CHANNEL')
    expect(servicePageSource).toContain('activeChannel')
    expect(servicePageSource).toContain('selectChannel')
    expect(servicePageSource).toContain('PlatformServiceChannelContent')
    expect(servicePageSource).toContain('<InspectionPortalPage')
    expect(servicePageSource).not.toContain(
      "uni.navigateTo({ url: '/pages/service/inspection/index' })",
    )
  })

  it('uses a side-effect-free static development import so App-Plus does not split code', () => {
    expect(previewSource).toContain(
      "export const PLATFORM_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'",
    )
    expect(servicePageSource).toContain('PLATFORM_PREVIEW_ENABLED')
    expect(servicePageSource).not.toContain('import.meta.env.DEV')
    expect(servicePageSource).toContain(
      "import { developmentPlatformContentRepository } from '@/features/platform/data/development/development-platform-content-repository'",
    )
    expect(servicePageSource).not.toContain('await import(')
    expect(servicePageSource).not.toContain("import('@/features/platform/data/development")
  })

  it('preserves channel scroll while loading static preview content', () => {
    expect(servicePageSource).toContain('onPageScroll')
    expect(servicePageSource).toContain('uni.pageScrollTo')
  })

  it('commits a closed public production preview gate', () => {
    const productionConfigLines = Object.values(productionEnvSources)
      .join('')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#'))

    expect(productionConfigLines).toEqual(['VITE_PLATFORM_PREVIEW_ENABLED=false'])
    expect(gitIgnoreSource).toContain('!apps/mobile/.env.production')
  })

  it('keeps search on the real catalog route and gates other channels', () => {
    expect(homeRouteSource).toContain('buildPortalSearchUrl')
    expect(homeRouteSource).toContain('isHomeChannelSearchable(activeChannel.value)')
    expect(homeRouteSource).toContain("handleIntent({ type: 'HOME_SEARCH', channel: activeChannel.value })")
  })

  it('keeps unfinished service types out of public builds even when swiped to', () => {
    expect(servicePageSource).toContain('resolveVisiblePlatformChannels(PLATFORM_PREVIEW_ENABLED)')
    expect(servicePageSource).toContain('if (!visibleChannels.includes(channel)) return')
  })

  it('hands the search box to the open service type and resets it when leaving', () => {
    expect(servicePageSource).toContain("emit('searchPlaceholderChange'")
    expect(servicePageSource).toContain('channelContent.value?.searchPlaceholder')
    expect(servicePageSource).toMatch(
      /watch\(\(\) => props\.active, \(active\) => \{\s*if \(active \|\| activeChannel\.value === DEFAULT_PLATFORM_CHANNEL\) return/,
    )
    expect(homeRouteSource).toContain('@search-placeholder-change="serviceSearchPlaceholder = $event"')
  })

  it('records page scroll only while the channel is on screen', () => {
    expect(servicePageSource).toMatch(
      /onPageScroll\(\(\{ scrollTop \}\) => \{\s*if \(props\.active\) currentScrollTop\.value = scrollTop/,
    )
  })

  it('accepts only the latest channel content response before restoring scroll', () => {
    expect(servicePageSource).toContain('channelRequestVersion')
    expect(servicePageSource).toContain('const requestedChannel = activeChannel.value')
    expect(servicePageSource).toContain(
      'requestVersion !== channelRequestVersion || requestedChannel !== activeChannel.value',
    )
    expect(servicePageSource).toContain('if (!loaded) return')
  })
})

describe('platform service visual contract', () => {
  it('lists the plan C service types and leaves the shell to the home page', () => {
    expect(servicePageSource).toContain('PLATFORM_SERVICE_CHANNELS')
    expect(servicePageSource).not.toContain('AppHeader')
    expect(servicePageSource).not.toContain('RootAppTabBar')
    expect(platformChannelSource).toContain("label: '设备维护'")
    expect(platformChannelSource).not.toContain('飞行服务')
    expect(platformChannelSource).not.toContain('我要巡检')
  })

  it('redirects the legacy service routes to the home professional-service channel', () => {
    for (const source of [serviceRouteSource, inspectionRouteSource]) {
      expect(source).toContain("openHomeChannel('SERVICE')")
      expect(source).not.toContain('InspectionPortalPage')
    }
  })

  it('switches channels only from horizontal gestures in the content area', () => {
    expect(servicePageSource).toContain('resolvePlatformSwipeTarget')
    expect(servicePageSource).toContain('@touchstart="beginContentSwipe"')
    expect(servicePageSource).toContain('@touchend="finishContentSwipe"')
    expect(servicePageSource).toContain('@touchcancel="cancelContentSwipe"')
    expect(servicePageSource).toContain('void selectChannel(targetChannel)')
    expect(servicePageSource).not.toContain('@touchstart.prevent')
    expect(servicePageSource).not.toContain('@touchstart.stop')
  })

  it('keeps all preview metrics outside the reusable channel component', () => {
    expect(channelContentSource).toContain('defineProps')
    expect(channelContentSource).toContain(':image-url="content.hero.imageUrl"')
    expect(channelContentSource).toContain('v-if="service.rating !== undefined"')
    expect(channelContentSource).toContain('v-if="service.salesLabel"')
    expect(channelContentSource).not.toContain('fixture')
    expect(channelContentSource).not.toContain('¥1299')
    expect(channelContentSource).not.toContain('860+')
  })

  it('reuses the shared compact banner with distinct channel imagery', () => {
    expect(channelContentSource).toContain('<PlatformHeroBanner')
    expect(channelContentSource).toContain(':highlights="content.hero.highlights"')
    expect(channelContentSource).not.toContain('service-channel__hero-action')
    expect(serviceFixtureSource).toContain("image('aerial-service-hero-v3.png')")
  })

  it('uses readable two-column guarantees and horizontally scrollable service cards', () => {
    expect(channelContentSource).toContain(
      'grid-template-columns: repeat(2, minmax(0, 1fr))',
    )
    expect(channelContentSource).toContain('grid-auto-flow: column')
    expect(channelContentSource).toContain('grid-auto-columns: calc((100vw - 49px) / 2)')
    expect(channelContentSource).toContain('font-size: 12px')
    expect(channelContentSource).not.toContain(
      'grid-template-columns: repeat(4, minmax(0, 1fr));\n  gap: 8px;\n}\n\n.service-channel__capability',
    )
  })
})
