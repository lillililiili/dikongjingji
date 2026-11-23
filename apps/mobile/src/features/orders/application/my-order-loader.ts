import { loadMoreMerged, type MergedSource } from '../../../shared/list/merged-pages'

import type { Enrollment, EnrollmentStatus } from '../../activity/domain/enrollment'
import type { VenueBooking, VenueBookingBucket } from '../../experience/domain/venue-booking'
import type {
  InspectionOrderFilter,
  InspectionOrderSummary,
} from '../../inspection/domain/service-flow/order-center'
import type { RentalOrder, RentalOrderStatus } from '../../rental/domain/rental'
import type { TradeOrder, TradeOrderTab } from '../../trade/domain/trade-order'
import {
  MY_ORDER_KEYS,
  enrollItem,
  goodsItem,
  inspectionItem,
  isCompleteSource,
  rentalItem,
  venueItem,
  type MyOrderItem,
  type MyOrderSource,
} from './my-order-sources'

interface Page<T> {
  items: T[]
  hasMore: boolean
}

// The existing list calls, as each module's API client offers them. Inspection orders read the
// access token through the inspection client itself.
export interface MyOrderApis {
  goods(token: string, tab: TradeOrderTab, page: number, size: number): Promise<Page<TradeOrder>>
  rental(token: string, status: RentalOrderStatus | null, page: number, size: number): Promise<Page<RentalOrder>>
  inspection(stage: InspectionOrderFilter, page: number, size: number): Promise<Page<InspectionOrderSummary>>
  enroll(token: string, bucket: EnrollmentStatus, page: number, size: number): Promise<Page<Enrollment>>
  venue(token: string, bucket: VenueBookingBucket, page: number, size: number): Promise<Page<VenueBooking>>
}

// A paged source is read this many at a time; a complete one with the servers' largest page.
export const MY_ORDER_PAGE_SIZE = 10
export const COMPLETE_PAGE_SIZE = 50
// A complete source stops here (1,000 records) even if the server has more.
export const COMPLETE_MAX_PAGES = 20

async function fetchOne(apis: MyOrderApis, token: string, source: MyOrderSource, page: number, size: number): Promise<Page<MyOrderItem>> {
  switch (source.kind) {
    case 'GOODS': {
      const loaded = await apis.goods(token, source.tab, page, size)
      return { items: loaded.items.map(goodsItem), hasMore: loaded.hasMore }
    }
    case 'RENTAL': {
      const loaded = await apis.rental(token, source.status, page, size)
      return { items: loaded.items.map(rentalItem), hasMore: loaded.hasMore }
    }
    case 'INSPECTION': {
      const loaded = await apis.inspection(source.stage, page, size)
      return { items: loaded.items.map(inspectionItem), hasMore: loaded.hasMore }
    }
    case 'ENROLL': {
      const loaded = await apis.enroll(token, source.bucket, page, size)
      return { items: loaded.items.map(enrollItem), hasMore: loaded.hasMore }
    }
    case 'VENUE': {
      const loaded = await apis.venue(token, source.bucket, page, size)
      return { items: loaded.items.map(venueItem), hasMore: loaded.hasMore }
    }
  }
}

// Every page of a complete source, then nothing more to load.
async function fetchComplete(apis: MyOrderApis, token: string, source: MyOrderSource): Promise<Page<MyOrderItem>> {
  const items: MyOrderItem[] = []
  for (let page = 1; page <= COMPLETE_MAX_PAGES; page += 1) {
    const loaded = await fetchOne(apis, token, source, page, COMPLETE_PAGE_SIZE)
    items.push(...loaded.items)
    if (!loaded.hasMore || loaded.items.length === 0) break
  }
  return { items, hasMore: false }
}

/**
 * Every order of the given lists, each read to its end (at most 1,000 records a list), for 订单搜索
 * (board OrderSearch), which looks through all of them on this device: there is no order search on
 * the server (plan 06 R6-1: no new aggregate API).
 */
export async function loadEveryMyOrder(apis: MyOrderApis, token: string, sources: MyOrderSource[]): Promise<MyOrderItem[]> {
  const pages = await Promise.all(sources.map((source) => fetchComplete(apis, token, source)))
  const seen = new Set<string>()
  return pages.flatMap((page) => page.items).filter((item) => {
    if (seen.has(item.id)) return false
    seen.add(item.id)
    return true
  })
}

export function fetchMyOrderPage(apis: MyOrderApis, token: string, source: MyOrderSource, page: number): Promise<Page<MyOrderItem>> {
  return isCompleteSource(source)
    ? fetchComplete(apis, token, source)
    : fetchOne(apis, token, source, page, MY_ORDER_PAGE_SIZE)
}

// The first page of every source (all of a complete one), read together.
export async function loadFirstMyOrderPages(
  apis: MyOrderApis,
  token: string,
  sources: MyOrderSource[],
): Promise<MergedSource<MyOrderItem>[]> {
  const pages = await Promise.all(sources.map((source) => fetchMyOrderPage(apis, token, source, 1)))
  return pages.map((page) => ({ items: page.items, hasMore: page.hasMore && page.items.length > 0, page: 1 }))
}

export function loadMoreMyOrders(
  apis: MyOrderApis,
  token: string,
  sources: MyOrderSource[],
  loaded: MergedSource<MyOrderItem>[],
): Promise<MergedSource<MyOrderItem>[]> {
  return loadMoreMerged(loaded, MY_ORDER_KEYS, (index, page) => fetchMyOrderPage(apis, token, sources[index], page))
}
