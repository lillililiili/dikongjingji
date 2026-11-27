import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import { ApiRequestError } from '@/core/http/api-error'
import { recordingClient } from '@/core/http/recording-client'
import { createCatalogApi, type OpsOffer, type OpsProductDetail } from './catalog-api'

const confirmAction = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, notifyRecorded: vi.fn(), showError: vi.fn() }))
const { default: OfferDialog } = await import('./OfferDialog.vue')

describe('可售库存编辑', () => {
  it('旧版本被拒后禁止再次保存，点击重新加载才读回当前库存及版本', async () => {
    confirmAction.mockResolvedValue(true)
    const offer: OpsOffer = {
      offerId: 'o1', skuId: 's1', skuName: '标准版', merchantId: 'm1', merchantName: '商家',
      merchantKind: 'LOCAL_STORE', cityCode: null, cityName: null, priceAmountMinor: 100,
      priceUnit: 'ITEM', stockQuantity: 2, expressAvailable: true, shippingFeeMinor: 0, pickupPoint: null,
      deliveryNote: null, shippingNote: null, fulfilledFrom: null, status: 'ACTIVE',
      merchantCollecting: true, updatedAt: '2026-11-27T00:00:00Z', version: 3,
    }
    const detail: OpsProductDetail = {
      product: {
        productId: 'p1', category: 'ROBOT', name: '机器人', saleMode: 'BUY', status: 'PUBLISHED',
        summary: '测试商品', coverUrl: null, experienceBadge: null, sortOrder: 1, createdAt: offer.updatedAt,
        updatedAt: offer.updatedAt, version: 1, ownerOrganizationId: offer.merchantId, ownerName: offer.merchantName,
      },
      sellingPoints: [], specs: [], packItems: [], gallery: [], introBlocks: [], operations: [], publishBlockers: [],
      skus: [{ skuId: offer.skuId, name: offer.skuName, sortOrder: 1, status: 'ACTIVE', version: 1 }], offers: [offer],
    }
    const latest = { ...offer, stockQuantity: 1, version: 4 }
    const updated = { ...detail, offers: [latest] }
    const updateOffer = vi.fn().mockRejectedValue(new ApiRequestError(412, 'OPS_VERSION_CONFLICT', '供给已变化', null))
    const read = vi.fn().mockResolvedValue(updated)
    const page = mount(OfferDialog, {
      props: { api: { ...createCatalogApi(recordingClient().http), updateOffer, detail: read }, detail, target: offer, merchant: true },
      global: { plugins: [ElementPlus] }, attachTo: document.body,
    })
    try {
      await vi.waitFor(() => expect(document.querySelector('input[placeholder="空着表示不限"]')).toBeTruthy())
      await flushPromises()
      const save = [...document.body.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button')].find(button => button.textContent?.trim() === '保存')!
      save.click()
      await vi.waitFor(() => expect(updateOffer).toHaveBeenCalledWith('p1', 'o1', expect.objectContaining({ stockQuantity: 2 }), 3))
      await vi.waitFor(() => expect(save.disabled).toBe(true))
      expect(read).not.toHaveBeenCalled()
      expect(page.emitted('changed')).toBeUndefined()
      document.body.querySelector<HTMLButtonElement>('[data-testid="offer-reload"]')!.click()
      await flushPromises()
      expect(read).toHaveBeenCalledWith('p1')
      expect(page.emitted('reloaded')?.[0]).toEqual([updated, latest])
    } finally {
      page.unmount()
    }
  })
})
