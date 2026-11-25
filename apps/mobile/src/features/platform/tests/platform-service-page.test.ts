import { describe, expect, it } from 'vitest'

import homeRouteSource from '../../../pages/home/index.vue?raw'
import inspectionRouteSource from '../../../pages/service/inspection/index.vue?raw'
import serviceRouteSource from '../../../pages/service/index.vue?raw'
import previewSource from '../application/platform-preview.ts?raw'
import serviceContentSource from '../domain/platform-service-content.ts?raw'
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

  it('reads the four types from the catalog API, not from development fixtures', () => {
    expect(previewSource).toContain(
      'export const PLATFORM_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__',
    )
    expect(servicePageSource).toContain('PLATFORM_PREVIEW_ENABLED')
    expect(servicePageSource).not.toContain('import.meta.env.DEV')
    expect(servicePageSource).toContain('proServiceApi.listServices(')
    expect(servicePageSource).toContain('PLATFORM_SERVICE_PREVIEW_SIZE')
    expect(servicePageSource).not.toContain('data/development')
    expect(servicePageSource).not.toContain('fixture')
    expect(servicePageSource).not.toContain('await import(')
  })

  it('sends 查看全部服务 and the search box of a type to its catalog with the city', () => {
    expect(servicePageSource).toContain('buildProServiceCatalogUrl({')
    expect(servicePageSource).toContain('cityCode: props.cityCode')
    expect(servicePageSource).toContain('defineExpose({ searchServices })')
    expect(servicePageSource).toContain("if (activeChannel.value === 'INSPECTION') return false")
    expect(homeRouteSource).toContain("serviceChannel.value?.searchServices(keyword.value)")
    expect(homeRouteSource).toContain(':city-code="currentCityStore.currentCity.code"')
  })

  it('preserves channel scroll while loading a type', () => {
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
    expect(servicePageSource).toContain('channelContent.value?.searchPlaceholder ?? null')
    expect(servicePageSource).toContain('activeServiceType: activeChannel')
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

  it('keeps the copy of each service type outside the reusable entry component', () => {
    expect(channelContentSource).toContain('defineProps')
    expect(channelContentSource).toContain('v-for="guarantee in content.guarantees"')
    expect(channelContentSource).toContain('v-for="(step, index) in content.processSteps"')
    expect(channelContentSource).not.toContain('fixture')
    expect(channelContentSource).not.toContain('持证飞手')
  })

  // Prices come from the server's cards only; the channel itself writes no figure, rating or sales.
  it('shows no price, rating or sales figure of its own', () => {
    for (const source of [channelContentSource, serviceContentSource]) {
      expect(source).not.toContain('¥')
      expect(source).not.toContain('rating')
      expect(source).not.toContain('已售')
    }
  })

  it('follows the prototype entry: guarantee line, the type\'s services, request strip, service flow', () => {
    expect(channelContentSource).not.toContain('<PlatformHeroBanner')
    expect(channelContentSource).toContain('<ProServiceCard')
    expect(channelContentSource).toContain("emit('intent', { type: 'PUBLISH_DEMAND', channel: content.code })")
    expect(channelContentSource).toContain("emit('intent', { type: 'SEARCH', channel: content.code })")
    expect(channelContentSource).toContain('closedCityText(list.page.cityName, list.page.type)')
    expect(serviceContentSource).toContain("guarantees: ['资质审核', '服务记录', '报价留档']")
    for (const code of ['AERIAL', 'SURVEY', 'MAINTENANCE', 'SUPPORT']) {
      expect(serviceContentSource).toContain(`code: '${code}'`)
    }
    // Support is filed as TECH_SUPPORT in the catalog and the demand form.
    expect(serviceContentSource).toContain("serviceType: 'TECH_SUPPORT'")
  })

})
