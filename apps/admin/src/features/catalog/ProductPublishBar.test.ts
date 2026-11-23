import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiRequestError } from '@/core/http/api-error'

import type { CatalogApi, OpsProductDetail } from './catalog-api'

const confirmAction = vi.fn()
const notifyRecorded = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, notifyRecorded, showError }))

const { default: ProductPublishBar } = await import('./ProductPublishBar.vue')

function detail(overrides: Partial<OpsProductDetail> = {}): OpsProductDetail {
  return {
    product: {
      productId: 'p-1',
      category: 'ROBOT',
      name: '编程机器人探索套装',
      summary: null,
      coverUrl: null,
      saleMode: 'BUY',
      experienceBadge: null,
      sortOrder: 10,
      status: 'DRAFT',
      createdAt: '2026-11-23T02:00:00Z',
      updatedAt: '2026-11-23T02:00:00Z',
      version: 4,
    },
    sellingPoints: [],
    specs: [],
    packItems: [],
    gallery: [],
    introBlocks: [],
    skus: [],
    offers: [],
    publishBlockers: ['SUMMARY_MISSING', 'NO_ACTIVE_OFFER'],
    operations: [],
    ...overrides,
  }
}

function mountBar(api: Partial<CatalogApi>, value: OpsProductDetail) {
  return mount(ProductPublishBar, {
    props: { api: api as CatalogApi, detail: value },
    global: { plugins: [ElementPlus] },
  })
}

describe('上架按钮', () => {
  beforeEach(() => {
    confirmAction.mockReset()
    showError.mockReset()
    notifyRecorded.mockReset()
  })

  it('缺必填时禁用，并列出缺什么', async () => {
    const changeStatus = vi.fn()
    const wrapper = mountBar({ changeStatus }, detail())
    expect(wrapper.find('[data-testid="publish-blockers"]').text()).toBe('上架还缺：缺一句话介绍、没有启用的供给')
    const button = wrapper.find('[data-testid="publish-button"]')
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    expect(confirmAction).not.toHaveBeenCalled()
    expect(changeStatus).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('齐了就能上架：确认后带版本号提交；后端说还缺时显示后端给的', async () => {
    confirmAction.mockResolvedValue(true)
    const changeStatus = vi.fn().mockRejectedValue(new ApiRequestError(422, 'OPS_PRODUCT_INCOMPLETE', '', 't-1',
      { blockers: ['NO_ACTIVE_SKU'] }))
    const wrapper = mountBar({ changeStatus }, detail({ publishBlockers: [] }))
    expect(wrapper.find('[data-testid="publish-blockers"]').exists()).toBe(false)
    await wrapper.find('[data-testid="publish-button"]').trigger('click')
    await flushPromises()
    expect(changeStatus).toHaveBeenCalledWith('p-1', 'PUBLISH', 4)
    expect(showError).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="publish-blockers"]').text()).toBe('上架还缺：没有启用的规格')
    wrapper.unmount()
  })

  it('已上架时是下架按钮', async () => {
    confirmAction.mockResolvedValue(true)
    const published = detail({ publishBlockers: [], product: { ...detail().product, status: 'PUBLISHED' } })
    const changeStatus = vi.fn().mockResolvedValue(published)
    const wrapper = mountBar({ changeStatus }, published)
    const button = wrapper.find('[data-testid="publish-button"]')
    expect(button.text()).toBe('下架')
    await button.trigger('click')
    await flushPromises()
    expect(changeStatus).toHaveBeenCalledWith('p-1', 'UNPUBLISH', 4)
    expect(notifyRecorded).toHaveBeenCalledWith('下架', '操作记录')
    expect(wrapper.emitted('changed')?.[0]).toEqual([published])
    wrapper.unmount()
  })
})
