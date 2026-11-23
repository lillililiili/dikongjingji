import type { ApiClient } from '@/core/http/api-client'
import { ApiRequestError } from '@/core/http/api-error'

// 契约 contracts/openapi-trade.yaml 0.5.0 readMyMerchantAccount：商家操作员读本机构登记的微信收款账户（只读）。
// 登记和停用由平台运营调 registerMerchantAccount；二级商户进件在微信服务商后台完成。

export interface MerchantAccount {
  organizationId: string
  /** 微信支付分配的二级商户号（子商户号）。 */
  subMchid: string
  active: boolean
}

/** 子商户号只留后 4 位，其余打码（页面不必露出完整商户号）。 */
export function maskSubMchid(subMchid: string): string {
  const value = subMchid.trim()
  if (value.length <= 4) return '*'.repeat(value.length)
  return `${'*'.repeat(value.length - 4)}${value.slice(-4)}`
}

export function createMerchantAccountApi(http: ApiClient) {
  return {
    /** 还没登记时返回 null（后端回 404）。 */
    async read(): Promise<MerchantAccount | null> {
      try {
        return await http.get<MerchantAccount>('/api/v1/provider/trade/merchant-account')
      } catch (failure) {
        if (failure instanceof ApiRequestError && failure.status === 404) return null
        throw failure
      }
    },
  }
}

export type MerchantAccountApi = ReturnType<typeof createMerchantAccountApi>
