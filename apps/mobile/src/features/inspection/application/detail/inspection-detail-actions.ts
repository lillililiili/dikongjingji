const MAX_SERVICE_ID_LENGTH = 96

export interface ReserveServiceIntent {
  type: 'RESERVE_SERVICE'
  serviceId: string
  cityCode?: string
}

export function resolveDetailServiceId(
  rawServiceId: string | undefined,
): string {
  return Array.from(rawServiceId?.trim() ?? '')
    .slice(0, MAX_SERVICE_ID_LENGTH)
    .join('')
}

// 发布需求 on a service the server prices by project (primaryAction REQUEST_QUOTE): the demand
// form with 巡检 picked, inviting this service's provider when it can be invited.
export interface PublishInspectionDemandIntent {
  type: 'PUBLISH_DEMAND'
  serviceId: string
  providerId?: string
}

export type DetailPrimaryIntent = ReserveServiceIntent | PublishInspectionDemandIntent

export type DetailPrimaryActionKind = DetailPrimaryIntent['type']

export const DETAIL_PRIMARY_ACTION_LABELS: Record<DetailPrimaryActionKind, string> = {
  RESERVE_SERVICE: '立即预约',
  PUBLISH_DEMAND: '发布需求',
}

// The server's primaryAction picks the button; 发布需求 needs the demand form to be open (preview
// builds). Without it, and for every other action, the button stays 立即预约 as before.
export function resolveDetailPrimaryAction(
  primaryAction: string,
  demandPublishEnabled: boolean,
): DetailPrimaryActionKind {
  return primaryAction === 'REQUEST_QUOTE' && demandPublishEnabled
    ? 'PUBLISH_DEMAND'
    : 'RESERVE_SERVICE'
}

export function createDetailPrimaryIntent(
  kind: DetailPrimaryActionKind,
  serviceId: string,
  cityCode?: string,
  providerId?: string,
): DetailPrimaryIntent {
  if (kind === 'RESERVE_SERVICE') return createReservationIntent(serviceId, cityCode)
  return {
    type: 'PUBLISH_DEMAND',
    serviceId: resolveDetailServiceId(serviceId),
    ...(providerId?.trim() ? { providerId: providerId.trim() } : {}),
  }
}

export function createReservationIntent(
  serviceId: string,
  cityCode?: string,
): ReserveServiceIntent {
  return {
    type: 'RESERVE_SERVICE',
    serviceId: resolveDetailServiceId(serviceId),
    ...(cityCode?.trim() ? { cityCode: cityCode.trim() } : {}),
  }
}
