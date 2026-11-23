import { tokenStore } from '@/core/auth/token-store'

import { createApiClient, type ApiClient } from './api-client'

type UnauthorizedListener = () => void
let unauthorizedListener: UnauthorizedListener = () => undefined

/** 登录失效时要做的事（清登录状态、回登录页），由 main.ts 在路由建好后接上。 */
export function onUnauthorized(listener: UnauthorizedListener): void {
  unauthorizedListener = listener
}

/**
 * 全站共用的接口客户端：令牌取自 tokenStore；401 时先用刷新令牌续期一次（并发的请求共用一次）再重发，
 * 续不了期才交给 onUnauthorized。
 */
export const http: ApiClient = createApiClient({
  baseUrl: '',
  getToken: () => tokenStore.get(),
  renewal: {
    getRefreshToken: () => tokenStore.refreshToken(),
    onRenewed: (renewed) => tokenStore.save(renewed),
  },
  onUnauthorized: () => {
    tokenStore.clear()
    unauthorizedListener()
  },
})
