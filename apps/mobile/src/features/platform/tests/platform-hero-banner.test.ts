import { describe, expect, it } from 'vitest'

import appSource from '../../../App.vue?raw'
import bannerSource from '../../../shared/presentation/PlatformHeroBanner.vue?raw'

describe('PlatformHeroBanner', () => {
  it('renders one shared structure with three trust points', () => {
    expect(bannerSource).toContain('platform-hero-banner__title-row')
    expect(bannerSource).toContain('props.highlights')
    expect(bannerSource).toContain('type="checkbox-filled"')
    expect(bannerSource).toContain("emit('action')")
  })

  it('uses shared banner and pill CTA tokens', () => {
    expect(appSource).toContain('--app-primary-banner-height: 248rpx')
    expect(appSource).toContain('--app-hero-action-height: 46rpx')
    expect(appSource).toContain('--app-hero-action-radius: 999px')
    expect(bannerSource).toContain('var(--app-hero-action-height)')
    expect(bannerSource).toContain('var(--app-hero-action-radius)')
  })

  it('pins every Banner action to the left edge below the copy', () => {
    expect(bannerSource).toMatch(
      /\.platform-hero-banner__action\s*\{[\s\S]*?align-self:\s*flex-start;/,
    )
    expect(bannerSource).toMatch(
      /\.platform-hero-banner__action\s*\{[\s\S]*?margin-left:\s*0;/,
    )
    expect(bannerSource).toMatch(
      /\.platform-hero-banner__action\s*\{[\s\S]*?margin-right:\s*0;/,
    )
  })
})
