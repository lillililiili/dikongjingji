export const INSPECTION_PORTAL_PATH = '/pages/service/inspection/index'
export const INSPECTION_CATALOG_PATH = '/pages/service/inspection/catalog'
export const INSPECTION_BOOKING_PATH = '/pages/service/inspection/booking'
export const INSPECTION_BOOKING_SUBMITTED_PATH =
  '/pages/service/inspection/booking-submitted'
export const INSPECTION_PLAN_CONFIRMATION_PATH =
  '/pages/service/inspection/plan-confirmation'
export const INSPECTION_FULFILLMENT_PATH =
  '/pages/service/inspection/fulfillment'
export const INSPECTION_DELIVERY_PATH =
  '/pages/service/inspection/delivery'

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
    name: 'InspectionDelivery',
    path: INSPECTION_DELIVERY_PATH,
  },
] as const

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

export function buildInspectionDeliveryUrl(serviceOrderId: string): string {
  return `${INSPECTION_DELIVERY_PATH}?serviceOrderId=${encodeURIComponent(serviceOrderId.trim())}`
}

export type InspectionCatalogSort =
  | 'COMPREHENSIVE'
  | 'DISTANCE_ASC'
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

