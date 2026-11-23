import type { FormRules } from 'element-plus'

import type { ApiClient } from '@/core/http/api-client'
import { matches, required } from '@/core/ui/form-rules'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-catalog.yaml 1.0.0 的商家收款账户部分（交易模块，二级商户号）。

export type OpsMerchantAccountAction = 'REGISTER' | 'CHANGE' | 'IMPORT'

export const MERCHANT_ACCOUNT_ACTION_LABELS: Record<OpsMerchantAccountAction, string> = {
  REGISTER: '登记',
  CHANGE: '修改',
  IMPORT: '批量导入',
}

export interface OpsMerchantAccount {
  organizationId: string
  organizationCode: string
  organizationName: string
  organizationStatus: 'ACTIVE' | 'DISABLED'
  registered: boolean
  subMchid: string | null
  active: boolean
  updatedAt: string | null
  version: number | null
}

export interface OpsMerchantAccountOperation {
  operationId: string
  action: OpsMerchantAccountAction
  detail: string
  operatorName: string
  occurredAt: string
}

export interface MerchantAccountFilters {
  keyword: string
  /** '' 全部；'true' 只看登记过的；'false' 只看没登记的。 */
  registered: '' | 'true' | 'false'
}

/** 二级商户号：8–32 位数字（和库里的约束一致）。开发环境以外，后端还会拒绝 1900000xxx 占位号段。 */
export const SUB_MCHID = /^[0-9]{8,32}$/
/** 借微信文档示例号段的开发占位号（种子里用的），只有开发环境能登记。 */
export const PLACEHOLDER_SUB_MCHID = /^1900000[0-9]{3}$/

export const merchantAccountRules: FormRules = {
  subMchid: [required('二级商户号'), matches(SUB_MCHID, '二级商户号是 8–32 位数字')],
}

export function createMerchantAccountApi(http: ApiClient) {
  const one = (organizationId: string) => `/api/v1/ops/merchant-accounts/${encodeURIComponent(organizationId)}`
  return {
    list(filters: MerchantAccountFilters, page: number, size: number): Promise<Page<OpsMerchantAccount>> {
      return http.get<Page<OpsMerchantAccount>>('/api/v1/ops/merchant-accounts', {
        keyword: filters.keyword.trim(),
        registered: filters.registered,
        page,
        size,
      })
    },
    /** 第一次登记 version 为 null；修改时带列表里读到的版本号。 */
    save(organizationId: string, subMchid: string, active: boolean, version: number | null):
    Promise<OpsMerchantAccount> {
      return http.put<OpsMerchantAccount>(one(organizationId), { subMchid: subMchid.trim(), active, version })
    },
    operations(organizationId: string): Promise<OpsMerchantAccountOperation[]> {
      return http.get<OpsMerchantAccountOperation[]>(`${one(organizationId)}/operations`)
    },
  }
}

export type MerchantAccountApi = ReturnType<typeof createMerchantAccountApi>
