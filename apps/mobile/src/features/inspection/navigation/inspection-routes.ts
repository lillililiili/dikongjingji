import type { InspectionOrderFilter } from '../domain/service-flow/order-center'

export const INSPECTION_PORTAL_PATH = '/pages/service/inspection/index'
export const INSPECTION_CATALOG_PATH = '/pages/service/inspection/catalog'
export const INSPECTION_DETAIL_PATH = '/pages/service/inspection/detail'
export const INSPECTION_LOGIN_PATH = '/pages/service/inspection/login'
export const INSPECTION_BOOKING_PATH = '/pages/service/inspection/booking'
export const INSPECTION_BOOKING_SUBMITTED_PATH =
  '/pages/service/inspection/booking-submitted'
export const INSPECTION_PLAN_CONFIRMATION_PATH =
  '/pages/service/inspection/plan-confirmation'
export const INSPECTION_FULFILLMENT_PATH =
  '/pages/service/inspection/fulfillment'
export const INSPECTION_DELIVERY_PATH =
  '/pages/service/inspection/delivery'
export const INSPECTION_ORDER_CENTER_PATH = '/pages/mine/inspection-orders'
export const MINE_TAB_PATH = '/pages/mine/index'
export const MESSAGE_TAB_PATH = '/pages/message/index'

const INSPECTION_ORDER_RETURN_STAGES: readonly InspectionOrderFilter[] = [
  'ALL',
  'AWAITING_PLAN',
  'AWAITING_CONFIRMATION',
  'IN_PROGRESS',
  'AWAITING_ACCEPTANCE',
  'COMPLETED',
]

export const INSPECTION_ROUTES = [
  {
    name: 'InspectionPortal',
    path: INSPECTION_PORTAL_PATH,
  },
  {
    name: 'InspectionCatalog',
    path: INSPECTION_CATALOG_PATH,
  },
  {
    name: 'InspectionLogin',
    path: INSPECTION_LOGIN_PATH,
  },
  {
    name: 'InspectionBooking',
    path: INSPECTION_BOOKING_PATH,
  },
  {
    name: 'InspectionBookingSubmitted',
    path: INSPECTION_BOOKING_SUBMITTED_PATH,
  },
  {
    name: 'InspectionPlanConfirmation',
    path: INSPECTION_PLAN_CONFIRMATION_PATH,
  },
  {
    name: 'InspectionFulfillment',
    path: INSPECTION_FULFILLMENT_PATH,
  },
  {
    name: 'InspectionDelivery',
    path: INSPECTION_DELIVERY_PATH,
  },
] as const

const INSPECTION_PROTECTED_RETURN_KEYS: Record<
  string,
  { required?: string; allowed: readonly string[] }
> = {
  [INSPECTION_BOOKING_PATH]: {
    required: 'serviceId',
    allowed: ['serviceId', 'cityCode'],
  },
  [INSPECTION_BOOKING_SUBMITTED_PATH]: {
    required: 'reservationId',
    allowed: ['reservationId'],
  },
  [INSPECTION_PLAN_CONFIRMATION_PATH]: {
    required: 'planId',
    allowed: ['planId'],
  },
  [INSPECTION_FULFILLMENT_PATH]: {
    required: 'serviceOrderId',
    allowed: ['serviceOrderId'],
  },
  [INSPECTION_DELIVERY_PATH]: {
    required: 'serviceOrderId',
    allowed: ['serviceOrderId', 'visitId'],
  },
  [INSPECTION_ORDER_CENTER_PATH]: {
    allowed: ['stage'],
  },
  // The two root tabs that offer a login themselves. They take no parameters.
  [MINE_TAB_PATH]: { allowed: [] },
  [MESSAGE_TAB_PATH]: { allowed: [] },
  // Activity sign-up pages (features/activity/navigation/activity-routes.ts). The paths are
  // repeated here so this module does not import another feature; activity-routes.test.ts
  // checks that every one of them survives normalizeInspectionReturnUrl.
  '/pages/activity/enroll': {
    required: 'activityId',
    allowed: ['activityId', 'sessionId', 'cityCode'],
  },
  '/pages/activity/participants': { allowed: [] },
  '/pages/activity/ticket': {
    required: 'enrollmentId',
    allowed: ['enrollmentId'],
  },
  '/pages/mine/activity-enrollments': { allowed: ['status'] },
  // Paid enrolment order and refund pages (2026-11-20, R5 #4/#5).
  '/pages/activity/order': { required: 'enrollmentId', allowed: ['enrollmentId'] },
  '/pages/activity/refund': { required: 'enrollmentId', allowed: ['enrollmentId', 'ticketId'] },
  // Mall pages (features/mall/navigation/mall-routes.ts), repeated for the same reason;
  // mall-routes.test.ts checks them.
  '/pages/mall/product': {
    required: 'productId',
    allowed: ['productId', 'cityCode'],
  },
  '/pages/cart/index': { allowed: [] },
  '/pages/cart/checkout': {
    required: 'cityCode',
    allowed: ['items', 'offerId', 'quantity', 'inquiryQuoteId', 'inquiryId', 'cityCode'],
  },
  // Inquiry goods (F4).
  '/pages/mall/inquiry': { required: 'productId', allowed: ['productId', 'cityCode'] },
  '/pages/mall/inquiry-quotes': { required: 'inquiryId', allowed: ['inquiryId'] },
  '/pages/mine/addresses': { allowed: ['select'] },
  // Trade pages (features/trade/navigation/trade-routes.ts), repeated for the same reason;
  // trade-routes.test.ts checks them.
  '/pages/trade/cashier': { required: 'orderId', allowed: ['orderId'] },
  '/pages/trade/pay-result': { required: 'orderId', allowed: ['orderId'] },
  '/pages/mine/goods-orders': { allowed: ['tab'] },
  '/pages/mine/goods-order': { required: 'orderId', allowed: ['orderId'] },
  // 我的订单 merged list (features/orders, R6-1).
  '/pages/mine/orders': { allowed: ['tab', 'status'] },
  // Venue booking pages (features/experience/navigation/experience-routes.ts, E10), repeated for
  // the same reason; venue-booking-routes.test.ts checks them.
  '/pages/venue/book': {
    required: 'sessionId',
    allowed: ['venueId', 'cityCode', 'projectId', 'sessionId', 'date'],
  },
  '/pages/venue/booking': { required: 'bookingId', allowed: ['bookingId'] },
  '/pages/mine/venue-bookings': { allowed: ['bucket'] },
  // Demand pages (features/demand/navigation/demand-routes.ts, F1), repeated for the same reason;
  // demand-routes.test.ts checks them.
  '/pages/demand/publish': { allowed: ['demandId', 'type', 'from', 'providerId'] },
  '/pages/demand/quotes': { required: 'demandId', allowed: ['demandId'] },
  '/pages/demand/compare': { required: 'demandId', allowed: ['demandId'] },
  '/pages/mine/demands': { allowed: ['status'] },
  // Rental pages (features/rental/navigation/rental-routes.ts, F3), repeated for the same reason;
  // rental-routes.test.ts checks them.
  '/pages/rental/inquiry': { required: 'offerId', allowed: ['offerId', 'productId', 'cityCode'] },
  '/pages/rental/quote': { required: 'inquiryId', allowed: ['inquiryId'] },
  '/pages/rental/order': { required: 'rentalOrderId', allowed: ['rentalOrderId'] },
  '/pages/rental/contract': { required: 'rentalOrderId', allowed: ['rentalOrderId'] },
  '/pages/mine/rental-orders': { allowed: ['status'] },
  // Customer-service and settings pages (features/support/navigation/support-routes.ts and
  // features/settings/navigation/settings-routes.ts, G1), repeated for the same reason;
  // support-routes.test.ts checks them. The card fields are display hints for the chat's banner.
  '/pages/support/chat': {
    allowed: ['conversationId', 'kind', 'organizationId', 'cardType', 'cardId', 'cardTitle', 'cardSubtitle'],
  },
  '/pages/mine/settings': { allowed: [] },
  '/pages/mine/help': { allowed: [] },
  // Discover videos (features/content/navigation/content-routes.ts, 1.2.0): liking, following and
  // commenting ask for a login and come back to the same video. content-routes.test.ts checks them.
  '/pages/content/video': { required: 'videoId', allowed: ['videoId', 'cityCode'] },
  '/pages/content/video-feed': { required: 'cityCode', allowed: ['cityCode', 'videoId', 'feed'] },
  // Live pages (features/live/navigation/live-routes.ts, M20), repeated for the same reason;
  // live-routes.test.ts checks them.
  '/pages/live/notice': { required: 'roomId', allowed: ['roomId', 'cityCode'] },
  '/pages/live/room': { required: 'roomId', allowed: ['roomId', 'cityCode'] },
}

// A root tab cannot be the target of redirectTo, so the login page relaunches to these.
export function isRootTabReturnUrl(returnUrl: string): boolean {
  return returnUrl === '/pages/home/index'
    || returnUrl === '/pages/cart/index'
    || returnUrl === MINE_TAB_PATH
    || returnUrl === MESSAGE_TAB_PATH
}

export function buildInspectionLoginUrl(returnUrl: string): string {
  return `${INSPECTION_LOGIN_PATH}?returnUrl=${encodeURIComponent(
    normalizeInspectionReturnUrl(returnUrl),
  )}`
}

export function normalizeInspectionReturnUrl(returnUrl: string): string {
  const candidate = decodeReturnUrl(returnUrl.trim())
  const separator = candidate.indexOf('?')
  const path = separator >= 0 ? candidate.slice(0, separator) : candidate
  const query = separator >= 0 ? candidate.slice(separator + 1) : ''
  const rule = INSPECTION_PROTECTED_RETURN_KEYS[path]
  if (!rule) return '/pages/home/index'

  const values = parseQuery(query)
  if (rule.required && !values[rule.required]) return '/pages/home/index'

  const parameters = rule.allowed.flatMap((key) => {
    const value = values[key]?.trim()
    if (!value || (path === INSPECTION_ORDER_CENTER_PATH
      && key === 'stage'
      && !INSPECTION_ORDER_RETURN_STAGES.includes(value as InspectionOrderFilter))) {
      return []
    }
    return [`${key}=${encodeURIComponent(value)}`]
  })
  return parameters.length === 0 ? path : `${path}?${parameters.join('&')}`
}

export function buildInspectionOrderCenterUrl(
  stage: InspectionOrderFilter = 'ALL',
): string {
  return `${INSPECTION_ORDER_CENTER_PATH}?stage=${encodeURIComponent(stage)}`
}

export function buildInspectionDetailUrl(
  serviceId: string,
  cityCode: string,
): string {
  return `${INSPECTION_DETAIL_PATH}?serviceId=${encodeURIComponent(
    serviceId,
  )}&cityCode=${encodeURIComponent(cityCode)}`
}

export function buildInspectionBookingUrl(
  serviceId: string,
  cityCode?: string,
): string {
  const parameters = [
    `serviceId=${encodeURIComponent(serviceId.trim())}`,
  ]
  appendParameter(parameters, 'cityCode', cityCode?.trim())
  return `${INSPECTION_BOOKING_PATH}?${parameters.join('&')}`
}

export function buildInspectionBookingSubmittedUrl(
  reservationId: string,
): string {
  return `${INSPECTION_BOOKING_SUBMITTED_PATH}?reservationId=${encodeURIComponent(
    reservationId.trim(),
  )}`
}

export function buildInspectionPlanConfirmationUrl(planId: string): string {
  return `${INSPECTION_PLAN_CONFIRMATION_PATH}?planId=${encodeURIComponent(
    planId.trim(),
  )}`
}

export function buildInspectionFulfillmentUrl(
  serviceOrderId: string,
): string {
  return `${INSPECTION_FULFILLMENT_PATH}?serviceOrderId=${encodeURIComponent(
    serviceOrderId.trim(),
  )}`
}

export function buildInspectionDeliveryUrl(
  serviceOrderId: string,
  visitId: string,
): string {
  return `${INSPECTION_DELIVERY_PATH}?serviceOrderId=${encodeURIComponent(
    serviceOrderId.trim(),
  )}&visitId=${encodeURIComponent(visitId.trim())}`
}

export type InspectionCatalogSort =
  | 'COMPREHENSIVE'
  | 'SALES_DESC'
  | 'PRICE_ASC'
  | 'PRICE_DESC'

export type InspectionRouteSource =
  | 'SEARCH'
  | 'CATEGORY'
  | 'HOT'
  | 'VIEW_ALL'
  | 'DEEP_LINK'

export interface InspectionCatalogRouteQuery {
  cityCode?: string
  categoryCode?: string
  keyword?: string
  sort?: InspectionCatalogSort
  openFilter?: boolean
  source?: InspectionRouteSource
}

export function buildInspectionCatalogUrl(
  query: InspectionCatalogRouteQuery = {},
): string {
  const parameters: string[] = []

  appendParameter(parameters, 'cityCode', query.cityCode)
  appendParameter(parameters, 'categoryCode', query.categoryCode)
  appendParameter(parameters, 'keyword', query.keyword?.trim())
  appendParameter(parameters, 'sort', query.sort)
  appendParameter(parameters, 'openFilter', query.openFilter ? 'true' : undefined)
  appendParameter(parameters, 'source', query.source)

  return parameters.length === 0
    ? INSPECTION_CATALOG_PATH
    : `${INSPECTION_CATALOG_PATH}?${parameters.join('&')}`
}

function appendParameter(
  parameters: string[],
  key: string,
  value: string | undefined,
): void {
  if (!value) {
    return
  }

  parameters.push(`${key}=${encodeURIComponent(value)}`)
}

function decodeReturnUrl(value: string): string {
  if (value.startsWith('/')) return value
  try {
    return decodeURIComponent(value)
  } catch {
    return ''
  }
}

function parseQuery(query: string): Record<string, string> {
  return query.split('&').reduce<Record<string, string>>((values, part) => {
    const [rawKey, ...rawValue] = part.split('=')
    if (!rawKey) return values
    try {
      values[decodeURIComponent(rawKey)] = decodeURIComponent(rawValue.join('='))
    } catch {
      return values
    }
    return values
  }, {})
}

