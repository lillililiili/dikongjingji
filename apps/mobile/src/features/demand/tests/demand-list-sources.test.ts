import { describe, expect, it } from 'vitest'

import { mergedItems } from '../../../shared/list/merged-pages'

import { mallInquiryOf } from '../../mall/tests/inquiry-fixtures'
import { rentalInquiry } from '../../rental/tests/rental-fixtures'
import {
  DEMAND_LIST_KEYS,
  demandListItem,
  demandListSources,
  mallInquiryListItem,
  rentalListItem,
} from '../application/demand-list-sources'
import { DEMAND_LIST_FILTERS } from '../navigation/demand-routes'
import { demandOf } from './demand-fixtures'

describe('需求与报价 with rental inquiries (F3)', () => {
  it('asks both kinds of record for every chip except drafts', () => {
    expect(demandListSources('ALL')).toEqual([
      { kind: 'DEMAND', status: null },
      { kind: 'RENTAL', status: null },
      { kind: 'MALL', status: null },
    ])
    expect(demandListSources('QUOTED')).toEqual([
      { kind: 'DEMAND', status: 'QUOTED' },
      { kind: 'RENTAL', status: 'QUOTED' },
      { kind: 'RENTAL', status: 'EXPIRED' },
      { kind: 'MALL', status: 'QUOTED' },
    ])
    expect(demandListSources('CHOSEN')).toContainEqual({ kind: 'RENTAL', status: 'CONFIRMED' })
    expect(demandListSources('CLOSED')).toContainEqual({ kind: 'RENTAL', status: 'DECLINED' })
    expect(demandListSources('DRAFT')).toEqual([{ kind: 'DEMAND', status: 'DRAFT' }])
    for (const filter of DEMAND_LIST_FILTERS) expect(demandListSources(filter).length).toBeGreaterThan(0)
  })

  it('merges demands and inquiries by their last change', () => {
    const demand = demandOf({ demandId: 'd-1', updatedAt: '2026-11-19T03:00:00Z' })
    const older = rentalInquiry({ inquiryId: 'i-1', updatedAt: '2026-11-19T02:00:00Z' })
    const newer = rentalInquiry({ inquiryId: 'i-2', updatedAt: '2026-11-19T04:00:00Z' })
    const items = mergedItems([
      { items: [demandListItem(demand)], hasMore: false, page: 1 },
      { items: [rentalListItem(newer), rentalListItem(older)], hasMore: false, page: 1 },
    ], DEMAND_LIST_KEYS)
    expect(items.map((item) => item.id)).toEqual(['rental:i-2', 'demand:d-1', 'rental:i-1'])
  })

  it('adds goods inquiries (F4) to every chip but drafts, merged by their last change', () => {
    expect(demandListSources('OPEN')).toContainEqual({ kind: 'MALL', status: 'OPEN' })
    expect(demandListSources('CHOSEN')).toContainEqual({ kind: 'MALL', status: 'CHOSEN' })
    expect(demandListSources('CLOSED')).toContainEqual({ kind: 'MALL', status: 'CLOSED' })
    expect(demandListSources('DRAFT').some((source) => source.kind === 'MALL')).toBe(false)
    const demand = demandOf({ demandId: 'd-1', updatedAt: '2026-11-19T03:00:00Z' })
    const rental = rentalInquiry({ inquiryId: 'r-1', updatedAt: '2026-11-19T02:00:00Z' })
    const goods = mallInquiryOf({ inquiryId: 'm-1', updatedAt: '2026-11-19T05:00:00Z' })
    const items = mergedItems([
      { items: [demandListItem(demand)], hasMore: false, page: 1 },
      { items: [rentalListItem(rental)], hasMore: false, page: 1 },
      { items: [mallInquiryListItem(goods)], hasMore: false, page: 1 },
    ], DEMAND_LIST_KEYS)
    expect(items.map((item) => item.id)).toEqual(['mall:m-1', 'demand:d-1', 'rental:r-1'])
  })
})
