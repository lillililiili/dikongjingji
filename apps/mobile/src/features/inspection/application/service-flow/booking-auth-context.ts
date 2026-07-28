import type { AccessTokenPort } from '../../data/service-flow/inspection-booking-api'

export interface BookingAuthContext extends AccessTokenPort {
  getPrincipalId(): string | null
}

let context: BookingAuthContext = {
  getAccessToken: () => readStorage('inspection.accessToken'),
  getPrincipalId: () => readStorage('inspection.principalId'),
}

export function configureBookingAuthContext(next: BookingAuthContext): void {
  context = next
}

export function getBookingAuthContext(): BookingAuthContext {
  return context
}

function readStorage(key: string): string | null {
  const value = uni.getStorageSync?.(key)
  return typeof value === 'string' && value.trim() ? value.trim() : null
}
