const MAX_SERVICE_ID_LENGTH = 96

export interface ReserveServiceIntent {
  type: 'RESERVE_SERVICE'
  serviceId: string
}

export function resolveDetailServiceId(
  rawServiceId: string | undefined,
): string {
  return Array.from(rawServiceId?.trim() ?? '')
    .slice(0, MAX_SERVICE_ID_LENGTH)
    .join('')
}

export function createReservationIntent(
  serviceId: string,
): ReserveServiceIntent {
  return {
    type: 'RESERVE_SERVICE',
    serviceId: resolveDetailServiceId(serviceId),
  }
}
