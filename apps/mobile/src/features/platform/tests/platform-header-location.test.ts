import { describe, expect, it } from 'vitest'

import discoverRouteSource from '../../../pages/discover/index.vue?raw'
import mallRouteSource from '../../../pages/mall/index.vue?raw'
import { createPlatformFeatureGateway } from '../application/platform-feature-gateway'
import type { PlatformFeatureIntent } from '../domain/platform-feature-intent'
import discoverPageSource from '../presentation/discover/PlatformDiscoverPage.vue?raw'
import mallPageSource from '../presentation/mall/PlatformMallPage.vue?raw'
import headerSource from '../../../app-shell/components/AppHeader.vue?raw'

function eventExpression(source: string, pattern: RegExp): string {
  const match = source.match(pattern)
  if (!match?.[1]) throw new Error('Expected the component template to declare the event handler.')
  return match[1]
}

function runEmitExpression(expression: string): unknown[][] {
  const emitted: unknown[][] = []
  const handler = new Function(
    'emit',
    `return () => (${expression})`,
  ) as (emit: (...args: unknown[]) => void) => () => void

  handler((...args: unknown[]) => emitted.push(args))()
  return emitted
}

const locationFeedbackGateway = createPlatformFeatureGateway({
  SEARCH: 'NEXT_PHASE',
  OPEN_MESSAGE_CENTER: 'NEXT_PHASE',
  OPEN_ITEM: 'NEXT_PHASE',
  OPEN_LOCATION: 'NEXT_PHASE',
})

describe('platform header location behavior', () => {
  it('executes the city-button handler and emits location', () => {
    const expression = eventExpression(
      headerSource,
      /class="app-header__city"[\s\S]*?@click="([^"]+)"/,
    )

    expect(runEmitExpression(expression)).toEqual([['location']])
  })

  it.each([
    ['discover', discoverPageSource],
    ['mall', mallPageSource],
  ] as const)('executes the %s AppHeader listener and emits OPEN_LOCATION', (
    _name,
    pageSource,
  ) => {
    const expression = eventExpression(
      pageSource,
      /<AppHeader[\s\S]*?@location="([^"]+)"/,
    )
    const emitted = runEmitExpression(expression)

    expect(emitted).toEqual([['intent', { type: 'OPEN_LOCATION' }]])
    const intent = emitted[0]?.[1] as PlatformFeatureIntent
    expect(locationFeedbackGateway.resolve(intent).action).toBe('SHOW_NEXT_PHASE')
  })

  it('wires emitted intents to the route feedback handlers in Discover and Mall', () => {
    for (const source of [discoverRouteSource, mallRouteSource]) {
      expect(source).toContain("OPEN_LOCATION: 'NEXT_PHASE'")
      expect(source).toContain('@intent="handleIntent"')
    }
  })

  it('shows the current city weather or its unknown icon below every shared city header', () => {
    expect(headerSource).toContain('weather: PlatformWeatherPresentation')
    expect(headerSource).toMatch(
      /<view class="app-header__weather">\s*<text v-if="weather\.available">\{\{ weather\.text \}\}<\/text>\s*<image :src="weather\.iconUrl"/,
    )
    expect(headerSource).toContain('width: 18px')
    expect(headerSource).toContain('height: 18px')

    for (const source of [discoverPageSource, mallPageSource]) {
      expect(source).toContain(':weather="weather"')
    }

    for (const source of [discoverRouteSource, mallRouteSource]) {
      expect(source).toContain('createCurrentCityWeatherLoader')
      expect(source).toContain('weatherLoader.load(currentCityStore.currentCity)')
      expect(source).toContain(':weather="weather"')
    }
  })

  it('keeps the shared header close to the Discover and Mall hero without scaling content', () => {
    expect(discoverPageSource).toMatch(
      /<AppHeader[\s\S]*?\/>\s*<PlatformHeroBanner/,
    )
    expect(mallPageSource).toMatch(
      /<AppHeader[\s\S]*?\/>\s*<PlatformHeroBanner/,
    )

    for (const source of [discoverPageSource, mallPageSource]) {
      expect(source).not.toMatch(/transform:\s*scale/)
      expect(source).not.toMatch(/font-stretch:/)
    }
  })
})
