import type { MergedKeys } from '@/shared/list/merged-pages'

import type { MallInquiry, MallInquiryStatus } from '../../mall/domain/inquiry'
import type { RentalInquiry, RentalInquiryStatus } from '../../rental/domain/rental'
import type { Demand, DemandStatus } from '../domain/demand'
import type { DemandListFilter } from '../navigation/demand-routes'

/**
 * 我的 → 需求与报价 shows the account's demands, its rental inquiries (询价单, F3 §24.1 rule 10) and
 * its goods inquiries (询价单, F4 §25.1 rule 7) in one list, newest change first. Every server sorts
 * by the last change, so the paged lists are merged on the client (shared/list/merged-pages.ts).
 */
export type DemandListItem =
  | { kind: 'DEMAND'; id: string; updatedAt: string; demand: Demand }
  | { kind: 'RENTAL'; id: string; updatedAt: string; inquiry: RentalInquiry }
  | { kind: 'MALL'; id: string; updatedAt: string; inquiry: MallInquiry }

export type DemandListSource =
  | { kind: 'DEMAND'; status: DemandStatus | null }
  | { kind: 'RENTAL'; status: RentalInquiryStatus | null }
  | { kind: 'MALL'; status: MallInquiryStatus | null }

// Each status chip of the board, for each kind of record. Inquiries have no draft; a rental
// inquiry's "quoted" chip also holds expired quotes, its "closed" chip the declined ones. A goods
// inquiry's statuses are the chips' own.
export function demandListSources(filter: DemandListFilter): DemandListSource[] {
  switch (filter) {
    case 'ALL': return [{ kind: 'DEMAND', status: null }, { kind: 'RENTAL', status: null }, { kind: 'MALL', status: null }]
    case 'OPEN': return [
      { kind: 'DEMAND', status: 'OPEN' },
      { kind: 'RENTAL', status: 'PENDING' },
      { kind: 'MALL', status: 'OPEN' },
    ]
    case 'QUOTED': return [
      { kind: 'DEMAND', status: 'QUOTED' },
      { kind: 'RENTAL', status: 'QUOTED' },
      { kind: 'RENTAL', status: 'EXPIRED' },
      { kind: 'MALL', status: 'QUOTED' },
    ]
    case 'CHOSEN': return [
      { kind: 'DEMAND', status: 'CHOSEN' },
      { kind: 'RENTAL', status: 'CONFIRMED' },
      { kind: 'MALL', status: 'CHOSEN' },
    ]
    case 'CLOSED': return [
      { kind: 'DEMAND', status: 'CLOSED' },
      { kind: 'RENTAL', status: 'DECLINED' },
      { kind: 'RENTAL', status: 'CLOSED' },
      { kind: 'MALL', status: 'CLOSED' },
    ]
    case 'DRAFT': return [{ kind: 'DEMAND', status: 'DRAFT' }]
  }
}

// Ids carry the kind: a demand and an inquiry never collide.
export function demandListItem(demand: Demand): DemandListItem {
  return { kind: 'DEMAND', id: `demand:${demand.demandId}`, updatedAt: demand.updatedAt, demand }
}

export function rentalListItem(inquiry: RentalInquiry): DemandListItem {
  return { kind: 'RENTAL', id: `rental:${inquiry.inquiryId}`, updatedAt: inquiry.updatedAt, inquiry }
}

export function mallInquiryListItem(inquiry: MallInquiry): DemandListItem {
  return { kind: 'MALL', id: `mall:${inquiry.inquiryId}`, updatedAt: inquiry.updatedAt, inquiry }
}

export const DEMAND_LIST_KEYS: MergedKeys<DemandListItem> = {
  idOf: (item) => item.id,
  timeOf: (item) => item.updatedAt,
}

// The board's underline tabs (DemandList): which kind of record.
export type DemandKindTab = 'ALL' | 'DEMAND' | 'INQUIRY' | 'DRAFT'

/**
 * The numbers behind the tabs (全部 7 / 需求单 3 / 询价单 2 / 草稿 2): demand sheets are every
 * demand but the drafts (demand-counts), inquiries are the rental and goods inquiry lists' totals
 * together, and 全部 is all three. A total that could not be read leaves every number out rather
 * than showing a wrong one.
 */
export function demandTabCounts(
  demands: { total: number; draft: number } | null,
  rentalInquiries: number | null,
  mallInquiries: number | null,
): Record<DemandKindTab, number> | null {
  if (!demands || rentalInquiries === null || mallInquiries === null) return null
  const sheets = Math.max(demands.total - demands.draft, 0)
  const inquiries = rentalInquiries + mallInquiries
  return { ALL: sheets + inquiries + demands.draft, DEMAND: sheets, INQUIRY: inquiries, DRAFT: demands.draft }
}
