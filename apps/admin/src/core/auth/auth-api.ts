import type { ApiClient } from '@/core/http/api-client'

/** POST /api/v1/auth/login（契约 openapi-inspection-service-flow.yaml）。 */
export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
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
}

export function createAuthApi(http: ApiClient): AuthApi {
  return {
    login: (loginName, password) =>
      http.post<LoginResponse>('/api/v1/auth/login', { loginName, password }),
    me: () => http.get<CurrentAccount>('/api/v1/auth/me'),
  }
}
