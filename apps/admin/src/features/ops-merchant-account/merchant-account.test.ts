import type { FormItemRule } from 'element-plus'
import { describe, expect, it } from 'vitest'

import { jsonBody, recordingClient } from '@/core/http/recording-client'
import { firstError } from '@/core/ui/form-rules'

import { createMerchantAccountApi, merchantAccountRules, PLACEHOLDER_SUB_MCHID } from './merchant-account-api'

const ORG = 'c1000000-0000-4000-8000-000000000001'

describe('商家收款账户', () => {
  it('列表带关键词和是否登记；登记第一次 version 为 null，修改带版本号', async () => {
    const { http, requests } = recordingClient({ page: 1, size: 20, total: 0, hasMore: false, items: [] })
    const api = createMerchantAccountApi(http)
    await api.list({ keyword: '机器人', registered: 'false' }, 1, 20)
    await api.list({ keyword: '', registered: '' }, 1, 20)
    await api.save(ORG, ' 1900000101 ', true, null)
    await api.save(ORG, '1900000101', false, 3)
    await api.operations(ORG)

    expect(requests[0]?.url).toBe('/api/v1/ops/merchant-accounts?keyword=%E6%9C%BA%E5%99%A8%E4%BA%BA'
      + '&registered=false&page=1&size=20')
    expect(requests[1]?.url).toBe('/api/v1/ops/merchant-accounts?page=1&size=20')
    expect(requests[2]?.method).toBe('PUT')
    expect(requests[2]?.url).toBe(`/api/v1/ops/merchant-accounts/${ORG}`)
    expect(jsonBody(requests[2])).toEqual({ subMchid: '1900000101', active: true, version: null })
    expect(jsonBody(requests[3])).toEqual({ subMchid: '1900000101', active: false, version: 3 })
    expect(requests[4]?.url).toBe(`/api/v1/ops/merchant-accounts/${ORG}/operations`)
  })

  it('二级商户号 8–32 位数字；占位号段只在开发环境能用（页面提示，后端拒绝）', async () => {
    const rules = merchantAccountRules.subMchid as FormItemRule[]
    expect(await firstError(rules, '1900000101')).toBeNull()
    expect(await firstError(rules, '')).toBe('请填写二级商户号')
    expect(await firstError(rules, '1234567')).toBe('二级商户号是 8–32 位数字')
    expect(await firstError(rules, '12345678a')).toBe('二级商户号是 8–32 位数字')
    expect(PLACEHOLDER_SUB_MCHID.test('1900000205')).toBe(true)
    expect(PLACEHOLDER_SUB_MCHID.test('1600123456')).toBe(false)
  })
})
