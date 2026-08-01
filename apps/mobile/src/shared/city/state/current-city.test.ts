import { describe, expect, it } from 'vitest'

import * as currentCityModel from './current-city'

describe('current location city normalization', () => {
  it('uses the native geocoded city and falls back to a municipality province', () => {
    const resolveLocatedCityName = (
      currentCityModel as typeof currentCityModel & {
        resolveLocatedCityName?: (address: unknown) => string
      }
    ).resolveLocatedCityName

    expect(resolveLocatedCityName).toBeTypeOf('function')
    if (!resolveLocatedCityName) return

    expect(resolveLocatedCityName({
      province: '\u5c71\u4e1c\u7701',
      city: '\u4e1c\u8425\u5e02',
    })).toBe('\u4e1c\u8425\u5e02')
    expect(resolveLocatedCityName({
      province: '\u5317\u4eac\u5e02',
      city: ' ',
    })).toBe('\u5317\u4eac\u5e02')
  })

  it('rejects a geocoded result without a usable city', () => {
    const resolveLocatedCityName = (
      currentCityModel as typeof currentCityModel & {
        resolveLocatedCityName?: (address: unknown) => string
      }
    ).resolveLocatedCityName

    expect(resolveLocatedCityName).toBeTypeOf('function')
    if (!resolveLocatedCityName) return

    expect(() => resolveLocatedCityName({ district: '\u4e1c\u8425\u533a' }))
      .toThrowError('CURRENT_CITY_UNAVAILABLE')
  })
})
