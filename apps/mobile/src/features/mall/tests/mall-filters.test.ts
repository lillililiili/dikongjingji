import { describe, expect, it } from 'vitest'

import {
  EMPTY_MALL_FILTERS,
  countMallFilters,
  describeMallFilters,
  isMallPriceRangeValid,
  parseMallPriceInput,
  removeMallFilterChip,
  toggleMallFilter,
} from '../application/mall-filters'

describe('mall filters', () => {
  it('toggles one option without touching the shared empty value', () => {
    const picked = toggleMallFilter(EMPTY_MALL_FILTERS, 'saleModes', 'RENT')
    expect(picked.saleModes).toEqual(['RENT'])
    expect(EMPTY_MALL_FILTERS.saleModes).toEqual([])
    expect(toggleMallFilter(picked, 'saleModes', 'RENT').saleModes).toEqual([])
  })

  it('describes the chosen options in sheet order, then the price range', () => {
    const filters = {
      saleModes: ['RENT' as const, 'BUY' as const],
      experienceBadges: ['VENUE_DISPLAY' as const],
      merchantKinds: [],
      minPriceYuan: 100,
      maxPriceYuan: 500,
    }
    expect(describeMallFilters(filters)).toEqual([
      { key: 'experienceBadges:VENUE_DISPLAY', label: '体验馆可看' },
      { key: 'saleModes:BUY', label: '直接购买' },
      { key: 'saleModes:RENT', label: '租赁' },
      { key: 'price', label: '¥100–500' },
    ])
    expect(countMallFilters(filters)).toBe(4)
    expect(countMallFilters(EMPTY_MALL_FILTERS)).toBe(0)
  })

  it('labels a one-sided price range', () => {
    expect(describeMallFilters({ ...EMPTY_MALL_FILTERS, minPriceYuan: 300 }))
      .toEqual([{ key: 'price', label: '¥300 以上' }])
    expect(describeMallFilters({ ...EMPTY_MALL_FILTERS, maxPriceYuan: 300 }))
      .toEqual([{ key: 'price', label: '¥300 以下' }])
  })

  it('removes exactly the chip that was tapped', () => {
    const filters = {
      ...EMPTY_MALL_FILTERS,
      saleModes: ['BUY' as const, 'RENT' as const],
      minPriceYuan: 100,
      maxPriceYuan: null,
    }
    expect(removeMallFilterChip(filters, 'saleModes:BUY').saleModes).toEqual(['RENT'])
    expect(removeMallFilterChip(filters, 'price').minPriceYuan).toBeNull()
    expect(removeMallFilterChip(filters, 'price').saleModes).toEqual(['BUY', 'RENT'])
    expect(removeMallFilterChip(filters, 'saleModes:INQUIRY')).toBe(filters)
    expect(removeMallFilterChip(filters, 'unknown:BUY')).toBe(filters)
  })

  it('reads whole yuan only and treats anything else as no bound', () => {
    expect(parseMallPriceInput(' 1200 ')).toBe(1200)
    expect(parseMallPriceInput('0')).toBe(0)
    expect(parseMallPriceInput('')).toBeNull()
    expect(parseMallPriceInput('12.5')).toBeNull()
    expect(parseMallPriceInput('-3')).toBeNull()
    expect(parseMallPriceInput('abc')).toBeNull()
  })

  it('rejects a range whose lower bound is above the upper bound', () => {
    expect(isMallPriceRangeValid({ ...EMPTY_MALL_FILTERS, minPriceYuan: 5, maxPriceYuan: 5 })).toBe(true)
    expect(isMallPriceRangeValid({ ...EMPTY_MALL_FILTERS, minPriceYuan: 6, maxPriceYuan: 5 })).toBe(false)
    expect(isMallPriceRangeValid({ ...EMPTY_MALL_FILTERS, minPriceYuan: 6 })).toBe(true)
  })
})
