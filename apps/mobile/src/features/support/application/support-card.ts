import { buildActivityDetailUrl, buildActivityTicketUrl } from '../../activity/navigation/activity-routes'
import { buildDemandQuotesUrl } from '../../demand/navigation/demand-routes'
import { buildVenueBookingUrl, buildVenueDetailUrl } from '../../experience/navigation/experience-routes'
import {
  buildInspectionDetailUrl,
  buildInspectionFulfillmentUrl,
} from '../../inspection/navigation/inspection-routes'
import { buildMallInquiryQuotesUrl, buildMallProductUrl } from '../../mall/navigation/mall-routes'
import { buildProServiceDetailUrl } from '../../proservice/navigation/proservice-routes'
import { buildRentalOrderUrl, buildRentalQuoteUrl } from '../../rental/navigation/rental-routes'
import { buildGoodsOrderUrl } from '../../trade/navigation/trade-routes'

import type { SupportCard, SupportCardType } from '../domain/support'


export const SUPPORT_CARD_LABELS: Record<SupportCardType, string> = {
  PRODUCT: '商品',
  MALL_ORDER: '商城订单',
  MALL_INQUIRY: '询价单',
  INSPECTION_SERVICE: '巡检服务',
  INSPECTION_ORDER: '巡检服务单',
  PRO_SERVICE: '专业服务',
  ACTIVITY: '活动',
  ACTIVITY_ENROLLMENT: '活动报名',
  VENUE: '场馆',
  VENUE_BOOKING: '到馆预约',
  RENTAL_INQUIRY: '租赁询价单',
  RENTAL_ORDER: '租赁订单',
  DEMAND: '需求',
}

// Line icons of uni-icons for the tile of a card without a picture.
export const SUPPORT_CARD_ICONS: Record<SupportCardType, string> = {
  PRODUCT: 'shop',
  MALL_ORDER: 'list',
  MALL_INQUIRY: 'compose',
  INSPECTION_SERVICE: 'eye',
  INSPECTION_ORDER: 'list',
  PRO_SERVICE: 'gear',
  ACTIVITY: 'flag',
  ACTIVITY_ENROLLMENT: 'flag',
  VENUE: 'location',
  VENUE_BOOKING: 'calendar',
  RENTAL_INQUIRY: 'compose',
  RENTAL_ORDER: 'calendar',
  DEMAND: 'compose',
}

/**
 * The page a card opens, by its type and stable id; the page reads the facts itself. Things that are
 * sold or shown per city (a product, a service, an activity, a venue) open in the current city and
 * have no page without one. INSPECTION_ORDER is a service order and opens its fulfilment page.
 */
export function supportCardTargetUrl(card: Pick<SupportCard, 'type' | 'refId'>, cityCode: string | null): string | null {
  const id = card.refId
  const city = cityCode?.trim() || null
  switch (card.type) {
    case 'PRODUCT': return city ? buildMallProductUrl(id, city) : null
    case 'MALL_ORDER': return buildGoodsOrderUrl(id)
    case 'MALL_INQUIRY': return buildMallInquiryQuotesUrl(id)
    case 'INSPECTION_SERVICE': return city ? buildInspectionDetailUrl(id, city) : null
    case 'INSPECTION_ORDER': return buildInspectionFulfillmentUrl(id)
    case 'PRO_SERVICE': return city ? buildProServiceDetailUrl(id, city) : null
    case 'ACTIVITY': return city ? buildActivityDetailUrl(id, city) : null
    case 'ACTIVITY_ENROLLMENT': return buildActivityTicketUrl(id)
    case 'VENUE': return city ? buildVenueDetailUrl(id, city) : null
    case 'VENUE_BOOKING': return buildVenueBookingUrl(id)
    case 'RENTAL_INQUIRY': return buildRentalQuoteUrl(id)
    case 'RENTAL_ORDER': return buildRentalOrderUrl(id)
    case 'DEMAND': return buildDemandQuotesUrl(id)
    default: return null
  }
}
