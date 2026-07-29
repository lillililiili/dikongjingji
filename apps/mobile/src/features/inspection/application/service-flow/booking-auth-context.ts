import type { AccessTokenPort } from '../../data/service-flow/inspection-booking-api'
import {
  buildInspectionLoginUrl,
  normalizeInspectionReturnUrl,
} from '../../navigation/inspection-routes'

export interface BookingAuthContext extends AccessTokenPort {
  getPrincipalId(): string | null
}

let context: BookingAuthContext = {
  getAccessToken: readAccessToken,
  getPrincipalId: () => readStorage('inspection.principalId'),
}
let authenticationRecoveryInProgress = false

const ACCESS_TOKEN_KEY = 'inspection.accessToken'
const ACCESS_TOKEN_EXPIRES_AT_KEY = 'inspection.accessTokenExpiresAt'

export interface BookingAuthSession {
  accessToken: string
  expiresInSeconds: number
}

export function configureBookingAuthContext(next: BookingAuthContext): void {
  context = next
}

export function getBookingAuthContext(): BookingAuthContext {
  return context
}

export function saveBookingAuthSession(session: BookingAuthSession): void {
  const token = session.accessToken.trim()
  if (!token || !Number.isFinite(session.expiresInSeconds) || session.expiresInSeconds <= 0) {
    throw new Error('登录响应无效')
  }
  uni.setStorageSync(ACCESS_TOKEN_KEY, token)
  uni.setStorageSync(
    ACCESS_TOKEN_EXPIRES_AT_KEY,
    Date.now() + session.expiresInSeconds * 1000,
  )
}

export function clearBookingAuthSession(): void {
  uni.removeStorageSync?.(ACCESS_TOKEN_KEY)
  uni.removeStorageSync?.(ACCESS_TOKEN_EXPIRES_AT_KEY)
  uni.removeStorageSync?.('inspection.principalId')
}

export function beginBookingAuthenticationRecovery(): void {
  if (authenticationRecoveryInProgress) return
  clearBookingAuthSession()
  const returnUrl = currentInspectionReturnUrl()
  if (returnUrl === '/pages/home/index') return
  authenticationRecoveryInProgress = true
  uni.redirectTo({
    url: buildInspectionLoginUrl(returnUrl),
    fail: () => {
      authenticationRecoveryInProgress = false
      uni.reLaunch({ url: '/pages/home/index' })
    },
  })
}

export function completeBookingAuthenticationRecovery(): void {
  authenticationRecoveryInProgress = false
}

function readStorage(key: string): string | null {
  const value = uni.getStorageSync?.(key)
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function readAccessToken(): string | null {
  const token = readStorage(ACCESS_TOKEN_KEY)
  if (!token) return null

  const expiresAt = Number(uni.getStorageSync?.(ACCESS_TOKEN_EXPIRES_AT_KEY))
  if (Number.isFinite(expiresAt) && expiresAt > 0 && Date.now() >= expiresAt) {
    clearBookingAuthSession()
    return null
  }
  return token
}

function currentInspectionReturnUrl(): string {
  const pages = getCurrentPages() as Array<{
    route?: string
    options?: Record<string, unknown>
    $page?: { options?: Record<string, unknown> }
  }>
  const page = pages[pages.length - 1]
  if (!page?.route) return '/pages/home/index'

  const pageOptions = page.options ?? page.$page?.options ?? {}
  const parameters = Object.entries(pageOptions).flatMap(([key, value]) => {
    if (typeof value !== 'string' || !value.trim()) return []
    return [`${encodeURIComponent(key)}=${encodeURIComponent(value.trim())}`]
  })
  const route = `/${page.route}${parameters.length ? `?${parameters.join('&')}` : ''}`
  return normalizeInspectionReturnUrl(route)
}
