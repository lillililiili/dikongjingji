import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

import { notificationExtrasApi } from '../data/notification-extras-api-client'

import { SYSTEM_NOTICE_PREVIEW_ENABLED } from './notice-preview'
import { createPushRegistration, readUniPushDevice } from './push-registration'

// The App's one push registration (S5-9, preview builds only): reported when the App comes to the
// front and after signing in, revoked before signing out.
const registration = createPushRegistration({
  api: notificationExtrasApi,
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
  getCityCode: () => {
    try {
      return useCurrentCityStore().currentCity?.code ?? null
    } catch {
      // Pinia is not ready yet on the very first launch tick.
      return null
    }
  },
  readDevice: readUniPushDevice,
})

export function reportPushDevice(): void {
  if (SYSTEM_NOTICE_PREVIEW_ENABLED) void registration.report()
}

export async function revokePushDevice(): Promise<void> {
  if (SYSTEM_NOTICE_PREVIEW_ENABLED) await registration.revoke()
}
