import { describe, expect, it } from 'vitest'
import tabBarSource from '../../../app-shell/components/RootAppTabBar.vue?raw'
import headerSource from '../presentation/portal/components/PortalServiceHeader.vue?raw'
import portalPageSource from '../presentation/portal/pages/InspectionPortalPage.vue?raw'

import {
  buildPortalCategoryUrl,
  buildPortalHotServiceUrl,
  buildPortalSearchUrl,
  buildPortalViewAllUrl,
} from '../application/portal/build-portal-navigation'
import {
  EMPTY_PORTAL_CONTENT,
  resolvePortalContent,
} from '../application/portal/resolve-portal-content'
import { DEVELOPMENT_PORTAL_CONTENT } from '../data/fixtures/inspection-portal.fixture'
import type { InspectionPortalContent } from '../domain/inspection-portal-content'

const fixtureContent: InspectionPortalContent = {
  hero: {
    title: '专业巡检',
    accent: '隐患早发现',
    description: '无人机与智能设备，为您提供安全高效的巡检服务',
    highlights: ['专业团队', '安全合规', '报告清晰'],
    imageUrl: '/static/images/inspection/inspection-hero-v1.png',
  },
  categories: [
    {
      code: 'POWER',
      name: '电力巡检',
      subtitle: '线路·杆塔·变电站',
      imageUrl: '/static/images/inspection/category-power.webp',
    },
  ],
  guarantees: [],
  hotServices: [],
  processSteps: [],
}

describe('inspection portal navigation', () => {
  it('trims and limits a portal search keyword before opening the catalog', () => {
    const keyword = `  ${'巡'.repeat(55)}  `

    expect(buildPortalSearchUrl('370500', keyword)).toBe(
      `/pages/service/inspection/catalog?cityCode=370500&keyword=${encodeURIComponent(
        '巡'.repeat(50),
      )}&source=SEARCH`,
    )
  })

  it('opens a normal category with its stable category code', () => {
    expect(buildPortalCategoryUrl('370500', 'POWER')).toBe(
      '/pages/service/inspection/catalog?cityCode=370500&categoryCode=POWER&source=CATEGORY',
    )
  })

  it('opens the filter sheet instead of treating MORE as a business category', () => {
    expect(buildPortalCategoryUrl('370500', 'MORE')).toBe(
      '/pages/service/inspection/catalog?cityCode=370500&openFilter=true&source=CATEGORY',
    )
  })

  it('keeps hot-service and view-all navigation inside the catalog scope', () => {
    expect(buildPortalHotServiceUrl('370500', 'POWER')).toBe(
      '/pages/service/inspection/catalog?cityCode=370500&categoryCode=POWER&source=HOT',
    )
    expect(buildPortalViewAllUrl('370500')).toBe(
      '/pages/service/inspection/catalog?cityCode=370500&source=VIEW_ALL',
    )
  })
})

describe('inspection portal development content boundary', () => {
  it('exposes development content only in the development environment', () => {
    expect(resolvePortalContent('development', fixtureContent)).toBe(
      fixtureContent,
    )
    expect(resolvePortalContent('production', fixtureContent)).toEqual(
      EMPTY_PORTAL_CONTENT,
    )
  })

  it('uses approved image assets for every portal category', () => {
    expect(DEVELOPMENT_PORTAL_CONTENT.categories).toHaveLength(8)
    expect(
      DEVELOPMENT_PORTAL_CONTENT.categories.every(
        (category) =>
          'imageUrl' in category &&
          typeof category.imageUrl === 'string' &&
          category.imageUrl.startsWith('/static/images/inspection/category-'),
      ),
    ).toBe(true)
  })

  it('provides complete decision data for four hot-service cards', () => {
    expect(DEVELOPMENT_PORTAL_CONTENT.hotServices).toHaveLength(4)
    expect(
      DEVELOPMENT_PORTAL_CONTENT.hotServices.every(
        (service) =>
          service.imageUrl?.startsWith(
            '/static/images/inspection/service-',
          ) &&
          typeof service.rating === 'number' &&
          Boolean(service.servedCountLabel),
      ),
    ).toBe(true)
  })
})

describe('inspection portal header visual contract', () => {
  it('keeps the PDF-measured tab proportions and approved header icons', () => {
    expect(headerSource).toContain(
      'grid-template-columns: 148fr 155fr 196fr 190fr 149fr',
    )
    expect(headerSource).toContain('type="smallcircle-filled"')
    expect(headerSource).toContain('size="26"')
    expect(headerSource).toContain('type="chat"')
    expect(headerSource).toContain(
      '/static/images/inspection/category-more.webp',
    )
    expect(headerSource).toContain(
      '.service-tabs__item--active::after',
    )
    expect(headerSource).toMatch(
      /\.service-tabs__item \{[\s\S]*?width: 100%;[\s\S]*?margin: 0;/,
    )
    expect(headerSource).toContain(
      'margin-top: clamp(8px, 1.8vw, 11px)',
    )
    expect(headerSource).toContain(
      'font-size: clamp(11px, 2.55vw, 15px)',
    )
    expect(headerSource).toContain(
      'font-size: clamp(11px, 2.5vw, 15px)',
    )
    expect(headerSource).toContain(
      'font-size: clamp(22px, 4.3vw, 26px) !important',
    )
    expect(headerSource).toMatch(
      /\.portal-header__city \{[\s\S]*?width: 100%;[\s\S]*?margin: 0;/,
    )
    expect(headerSource).toContain(
      'margin-inline: clamp(-9px, -1.5vw, -6px)',
    )
    expect(headerSource).toContain(
      'transform: translateX(clamp(6px, 1.7vw, 10px))',
    )
  })
})

describe('root tab bar visual contract', () => {
  it('uses the approved icon library instead of text-glyph placeholders', () => {
    expect(tabBarSource).toContain(
      "import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'",
    )
    expect(tabBarSource).toContain("HOME: 'home'")
    expect(tabBarSource).toContain("SERVICE: 'smallcircle-filled'")
    expect(tabBarSource).toContain("DISCOVER: 'navigate'")
    expect(tabBarSource).toContain("MINE: 'person'")
    expect(tabBarSource).toContain('v-for="dot in 4"')
    expect(tabBarSource).toContain(
      '/static/icons/root-tabs/shopping-bag.png',
    )
    expect(tabBarSource).toContain('class="root-tab-bar__asset-icon"')
    expect(tabBarSource).toContain('size="30"')
    expect(tabBarSource).toContain('size="14"')
    expect(tabBarSource).toContain(
      'min-height: calc(58px + env(safe-area-inset-bottom))',
    )
    expect(tabBarSource).toMatch(
      /\.root-tab-bar__asset-icon \{[\s\S]*?width: 30px;[\s\S]*?height: 30px;/,
    )
    expect(tabBarSource).not.toContain('{{ TAB_ICONS[tab.code] }}')
    expect(portalPageSource).toContain(
      'calc(clamp(58px, 11.5vw, 68px) + env(safe-area-inset-bottom))',
    )
    expect(portalPageSource).toContain(
      '.inspection-portal :deep(.root-tab-bar__label)',
    )
  })
})
