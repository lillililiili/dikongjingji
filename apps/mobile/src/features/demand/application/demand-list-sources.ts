import type { MergedKeys } from '@/shared/list/merged-pages'

import type { RentalInquiry, RentalInquiryStatus } from '../../rental/domain/rental'
import type { Demand, DemandStatus } from '../domain/demand'
import type { DemandListFilter } from '../navigation/demand-routes'

/**
 * 我的 → 需求与报价 shows the account's demands and its rental inquiries (询价单, F3 §24.1 rule 10)
 * in one list, newest change first. Both servers sort by their last change, so the two paged lists
 * are merged on the client (shared/list/merged-pages.ts).
 */
export type DemandListItem =
  | { kind: 'DEMAND'; id: string; updatedAt: string; demand: Demand }
  | { kind: 'RENTAL'; id: string; updatedAt: string; inquiry: RentalInquiry }

export type DemandListSource =
  | { kind: 'DEMAND'; status: DemandStatus | null }
  | { kind: 'RENTAL'; status: RentalInquiryStatus | null }

// Each status chip of the board, for each kind of record. A rental inquiry has no draft; its
// "quoted" chip also holds expired quotes, its "closed" chip the declined ones.
export function demandListSources(filter: DemandListFilter): DemandListSource[] {
  switch (filter) {
    case 'ALL': return [{ kind: 'DEMAND', status: null }, { kind: 'RENTAL', status: null }]
    case 'OPEN': return [{ kind: 'DEMAND', status: 'OPEN' }, { kind: 'RENTAL', status: 'PENDING' }]
    case 'QUOTED': return [
      { kind: 'DEMAND', status: 'QUOTED' },
      { kind: 'RENTAL', status: 'QUOTED' },
      { kind: 'RENTAL', status: 'EXPIRED' },
    ]
    case 'CHOSEN': return [{ kind: 'DEMAND', status: 'CHOSEN' }, { kind: 'RENTAL', status: 'CONFIRMED' }]
    case 'CLOSED': return [
      { kind: 'DEMAND', status: 'CLOSED' },
      { kind: 'RENTAL', status: 'DECLINED' },
      { kind: 'RENTAL', status: 'CLOSED' },
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

export const DEMAND_LIST_KEYS: MergedKeys<DemandListItem> = {
  idOf: (item) => item.id,
  timeOf: (item) => item.updatedAt,
}
