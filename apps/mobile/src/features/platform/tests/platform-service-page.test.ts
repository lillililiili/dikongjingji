import { describe, expect, it } from 'vitest'

import servicePageSource from '../../../pages/service/index.vue?raw'
import inspectionPortalSource from '../../inspection/presentation/portal/pages/InspectionPortalPage.vue?raw'
import previewSource from '../application/platform-preview.ts?raw'
import serviceFixtureSource from '../data/development/platform-service.fixture.ts?raw'
import channelContentSource from '../presentation/service/PlatformServiceChannelContent.vue?raw'
import shellSource from '../presentation/service/PlatformServicePage.vue?raw'
import appHeaderSource from '../../../app-shell/components/AppHeader.vue?raw'
import platformChannelSource from '../domain/platform-channel.ts?raw'
import gitIgnoreSource from '../../../../../../.gitignore?raw'

const productionEnvSources = import.meta.glob('../../../../.env.production', {
  eager: true,
  import: 'default',
  query: '?raw',
})

describe('platform service page integration', () => {
  it('defaults to aerial photography and switches approved channels in place', () => {
    expect(servicePageSource).toContain('DEFAULT_PLATFORM_CHANNEL')
    expect(servicePageSource).toContain('activeChannel')
    expect(servicePageSource).toContain('selectChannel')
    expect(servicePageSource).toContain('PlatformServicePage')
    expect(servicePageSource).toContain('PlatformServiceChannelContent')
    expect(servicePageSource).toContain('<InspectionPortalPage')
    expect(servicePageSource).toMatch(/<InspectionPortalPage[\s\S]*?\bembedded\b/)
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

  it('keeps inspection search on the real catalog route and gates other preview actions', () => {
    expect(servicePageSource).toContain('buildPortalSearchUrl')
    expect(servicePageSource).toContain('openInspectionSearch')
    expect(servicePageSource).toContain("activeChannel === 'INSPECTION'")
    expect(servicePageSource).toContain("handleIntent({ type: 'SEARCH', channel: activeChannel.value })")
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
  it('uses a shared safe-area shell and the root tab bar', () => {
    expect(shellSource).toContain('<RootAppTabBar active="SERVICE"')
    expect(shellSource).toContain('var(--status-bar-height)')
    expect(shellSource).toContain('var(--app-root-tabbar-content-offset)')
    expect(shellSource).toContain('PLATFORM_SERVICE_CHANNELS')
    expect(shellSource).toContain('grid-template-columns: repeat(5, minmax(0, 1fr))')
    expect(platformChannelSource).toContain("label: '飞行服务'")
    expect(platformChannelSource).not.toContain('我要飞无人机')
  })

  it('switches channels only from horizontal gestures in the content area', () => {
    expect(shellSource).toContain('resolvePlatformSwipeTarget')
    expect(shellSource).toContain('@touchstart="beginContentSwipe"')
    expect(shellSource).toContain('@touchend="finishContentSwipe"')
    expect(shellSource).toContain('@touchcancel="cancelContentSwipe"')
    expect(shellSource).toContain("emit('selectChannel', targetChannel)")
    expect(shellSource).not.toContain('@touchstart.prevent')
    expect(shellSource).not.toContain('@touchstart.stop')
  })

  it('emits city selection and hides the unavailable message action in public builds', () => {
    expect(shellSource).toContain('location: []')
    expect(shellSource).toContain("@location=\"emit('location')\"")
    expect(shellSource).toContain('showMessage?: boolean')
    expect(shellSource).toContain(':show-message="showMessage"')
    expect(appHeaderSource).toContain('v-if="showMessage !== false"')
    expect(servicePageSource).toContain('handleLocation')
    expect(servicePageSource).toContain('城市切换功能暂未开放')
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
    expect(serviceFixtureSource).toContain("image('flight-service-hero-v3.png')")
    expect(serviceFixtureSource).toContain("image('robot-service-hero-v3.png')")
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

describe('existing inspection route compatibility', () => {
  it('supports embedded use without changing the standalone default', () => {
    expect(inspectionPortalSource).toContain('embedded?: boolean')
    expect(inspectionPortalSource).toContain('embedded: false')
    expect(inspectionPortalSource).toContain('v-if="!props.embedded"')
    expect(inspectionPortalSource).toContain(
      "{ 'inspection-portal--embedded': props.embedded }",
    )
  })
})
