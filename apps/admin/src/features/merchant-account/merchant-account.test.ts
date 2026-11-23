import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError } from '@/core/http/api-error'

import { createMerchantAccountApi, maskSubMchid, type MerchantAccountApi } from './merchant-account-api'
import MerchantAccountPage from './MerchantAccountPage.vue'

function apiWith(fetchImpl: typeof fetch) {
  return createMerchantAccountApi(createApiClient({
    baseUrl: '',
    getToken: () => 't',
    onUnauthorized: vi.fn(),
    fetchImpl,
  }))
}

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

describe('收款账户', () => {
  it('子商户号只留后 4 位', () => {
    expect(maskSubMchid('1900000101')).toBe('******0101')
    expect(maskSubMchid('1234')).toBe('****')
  })

  it('读本机构的账户；没登记（404）返回 null，别的错误照常抛出', async () => {
    const account = { organizationId: 'm-1', subMchid: '1900000101', active: true }
    const fetchImpl = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(account), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'COMMON_NOT_FOUND', message: '', traceId: 't' }),
        { status: 404 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'COMMON_FORBIDDEN', message: '', traceId: 't' }),
        { status: 403 }))
    const api = apiWith(fetchImpl)

    await expect(api.read()).resolves.toEqual(account)
    expect(fetchImpl.mock.calls[0]![0]).toBe('/api/v1/provider/trade/merchant-account')
    await expect(api.read()).resolves.toBeNull()
    await expect(api.read()).rejects.toBeInstanceOf(ApiRequestError)
  })

  it('页面：登记了显示打码的商户号；没登记提示联系平台运营', async () => {
    const registered = mount(MerchantAccountPage, {
      props: { api: { read: vi.fn().mockResolvedValue({ organizationId: 'm-1', subMchid: '1900000101', active: true }) } },
      global: { plugins: [ElementPlus] },
    })
    await settle()
    expect(registered.text()).toContain('******0101')
    expect(registered.text()).not.toContain('1900000101')
    expect(registered.text()).toContain('可以收款')
    registered.unmount()

    const missing = mount(MerchantAccountPage, {
      props: { api: { read: vi.fn().mockResolvedValue(null) } as MerchantAccountApi },
      global: { plugins: [ElementPlus] },
    })
    await settle()
    expect(missing.text()).toContain('还没有登记收款账户，请联系平台运营')
    missing.unmount()
  })
})
