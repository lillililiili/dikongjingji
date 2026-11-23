import type { ApiClient } from '@/core/http/api-client'

/** POST /api/v1/auth/login（契约 openapi-inspection-service-flow.yaml 2.4.0 起带刷新令牌）。 */
export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  refreshToken?: string
  refreshExpiresInSeconds?: number
}

/** GET /api/v1/auth/me：显示名、所属机构和角色；不含登录名、手机号。 */
export interface CurrentAccount {
  displayName: string
  organizationName: string | null
  organizationType: string | null
  roles: string[]
}

export interface AuthApi {
  login(loginName: string, password: string): Promise<LoginResponse>
  me(): Promise<CurrentAccount>
  /** POST /api/v1/auth/logout：撤销这次登录的刷新令牌；不论令牌在不在都回 204。 */
  logout(refreshToken: string): Promise<void>
}

export function createAuthApi(http: ApiClient): AuthApi {
  return {
    login: (loginName, password) =>
      http.post<LoginResponse>('/api/v1/auth/login', { loginName, password }),
    me: () => http.get<CurrentAccount>('/api/v1/auth/me'),
    logout: (refreshToken) => http.post<void>('/api/v1/auth/logout', { refreshToken }),
  }
}
