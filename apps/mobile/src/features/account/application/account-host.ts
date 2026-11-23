import {
  beginBookingAuthenticationRecovery,
  getBookingAuthContext,
} from '@/features/inspection/application/service-flow/booking-auth-context'
import { MINE_TAB_PATH, buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { SETTINGS_PATH } from '@/features/settings/navigation/settings-routes'

// What every account page host does (S5-7, S5-8): these pages belong to a signed-in account, so
// without a token the user signs in and comes back; a token the server rejects starts the same
// recovery. Back returns to where the user came from, or to 设置 / 我的 when the page was opened alone.
export function accountToken(returnUrl: string): string | null {
  const token = getBookingAuthContext().getAccessToken()
  if (!token) uni.redirectTo({ url: buildInspectionLoginUrl(returnUrl) })
  return token
}

export function onAccountUnauthorized(): void {
  beginBookingAuthenticationRecovery()
}

export function leaveAccountPage(fallback: 'settings' | 'mine' = 'settings'): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: fallback === 'settings' ? SETTINGS_PATH : MINE_TAB_PATH })
}
