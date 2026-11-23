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
const REFRESH_TOKEN_KEY = 'inspection.refreshToken'

export interface BookingAuthSession {
  accessToken: string
  expiresInSeconds: number
  /** Renews the login when the access token runs out (POST /api/v1/auth/refresh). */
  refreshToken?: string
}

// Where the refresh token is kept. The App keeps it where it keeps the access token (uni storage, the
// App's own sandbox). On H5 it never goes to localStorage: only to this tab's sessionStorage, or to
// memory where that is not available, so closing the tab ends the login once the access token runs out.
interface RefreshTokenStore {
  read(): string | null
  write(token: string): void
  remove(): void
}

let memoryRefreshToken: string | null = null

const sessionRefreshTokenStore: RefreshTokenStore = {
  read() {
    const stored = browserSessionStorage()?.getItem(REFRESH_TOKEN_KEY)
    return stored?.trim() ? stored.trim() : memoryRefreshToken
  },
  write(token) {
    memoryRefreshToken = token
    try {
      browserSessionStorage()?.setItem(REFRESH_TOKEN_KEY, token)
    } catch {
      // Private mode or storage blocked: memory only.
    }
  },
  remove() {
    memoryRefreshToken = null
    try {
      browserSessionStorage()?.removeItem(REFRESH_TOKEN_KEY)
    } catch {
      // Nothing stored.
    }
  },
}

const uniRefreshTokenStore: RefreshTokenStore = {
  read: () => readStorage(REFRESH_TOKEN_KEY),
  write: (token) => uni.setStorageSync(REFRESH_TOKEN_KEY, token),
  remove: () => uni.removeStorageSync?.(REFRESH_TOKEN_KEY),
}

function refreshTokenStore(): RefreshTokenStore {
  // #ifdef H5
  return sessionRefreshTokenStore
  // #endif
  return uniRefreshTokenStore
}

function browserSessionStorage(): Storage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage
  } catch {
    return null
  }
}

/** The refresh token of the current login, or null when there is none to renew it with. */
export function readBookingRefreshToken(): string | null {
  return refreshTokenStore().read()
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
  const refreshToken = session.refreshToken?.trim()
  if (refreshToken) refreshTokenStore().write(refreshToken)
  else refreshTokenStore().remove()
}

export function clearBookingAuthSession(): void {
  uni.removeStorageSync?.(ACCESS_TOKEN_KEY)
  uni.removeStorageSync?.(ACCESS_TOKEN_EXPIRES_AT_KEY)
  uni.removeStorageSync?.('inspection.principalId')
  refreshTokenStore().remove()
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
    // A login that can be renewed is still a login: the request goes out with the old token, gets a
    // 401 and the HTTP client renews it once (uni-booking-http-client). Without a refresh token the
    // login is over.
    if (readBookingRefreshToken()) return token
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
