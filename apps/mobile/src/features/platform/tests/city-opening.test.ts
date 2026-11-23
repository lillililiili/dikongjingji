import { describe, expect, it } from 'vitest'

import homeSource from '../../../pages/home/index.vue?raw'
import { createCityOpeningLoader, resolveCityOpening } from '../application/city-opening'

describe('city opening (board CityClosed)', () => {
  it('counts a city with inspection services, activities or venues as open; products alone do not count', () => {
    expect(resolveCityOpening(null, { inspectionServices: true, activities: 0, venues: 0 })).toBe('OPEN')
    expect(resolveCityOpening(null, { inspectionServices: false, activities: 2, venues: 0 })).toBe('OPEN')
    expect(resolveCityOpening(null, { inspectionServices: false, activities: 0, venues: 1 })).toBe('OPEN')
    expect(resolveCityOpening(null, { inspectionServices: false, activities: 0, venues: 0 })).toBe('PREPARING')
    expect(resolveCityOpening(null, null)).toBe('UNKNOWN')
  })

  it('lets the directory status win once the directory has one', async () => {
    expect(resolveCityOpening('OPEN', { inspectionServices: false, activities: 0, venues: 0 })).toBe('OPEN')
    const loader = createCityOpeningLoader({
      directoryStatus: async () => 'PREPARING',
      localResources: async () => ({ inspectionServices: true, activities: 3, venues: 1 }),
    })
    expect(await loader.load('370700')).toBe('PREPARING')
  })

  it('leaves the home page as it is when nothing can be read', async () => {
    const loader = createCityOpeningLoader({
      directoryStatus: async () => { throw new Error('down') },
      localResources: async () => { throw new Error('down') },
    })
    expect(await loader.load('370700')).toBe('UNKNOWN')
  })

  it('shows the panel and keeps only the nationwide channels in preview builds', () => {
    expect(homeSource).toContain("PLACE_PREVIEW_ENABLED && cityOpening.value === 'PREPARING'")
    expect(homeSource).toContain('<CityClosedPanel')
    expect(homeSource).toContain(':channels="headerChannels"')
  })
})
