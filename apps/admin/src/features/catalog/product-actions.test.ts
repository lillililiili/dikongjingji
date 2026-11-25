import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { ApiRequestError } from '@/core/http/api-error'
import { recordingClient } from '@/core/http/recording-client'

import { createCatalogApi, MERCHANT_CATALOG_BASE, type OpsProductStatus } from './catalog-api'

const confirmAction = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, notifyRecorded: vi.fn(), showError }))

const http = { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), request: vi.fn(), download: vi.fn() }
vi.mock('@/core/http', () => ({ http }))

const { default: ProductListPage } = await import('./ProductListPage.vue')
const { default: ProductDetailPage } = await import('./ProductDetailPage.vue')

function summary(productId: string, status: OpsProductStatus) {
  return {
    productId,
    category: 'ROBOT',
    name: `商品 ${productId}`,
    saleMode: 'RENT',
    status,
    coverUrl: null,
    skuCount: 0,
    activeOfferCount: 0,
    updatedAt: '2026-11-25T03:25:22Z',
    version: 4,
    ownerOrganizationId: 'o-1',
    ownerName: '示例机器人商家',
  }
}

function detail(status: OpsProductStatus) {
  return {
    product: { ...summary('p-1', status), summary: null, experienceBadge: null, sortOrder: 0, createdAt: '2026-11-25T03:23:39Z' },
    sellingPoints: [],
    specs: [],
    packItems: [],
    gallery: [],
    introBlocks: [],
    skus: [],
    offers: [],
    publishBlockers: ['SUMMARY_MISSING'],
    operations: [],
  }
}

async function mountPage(component: object, merchant: boolean) {
  const page = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: page },
      { path: '/workbench/products', name: 'workbench-products', component: page },
      { path: '/workbench/products/:productId', name: 'workbench-product-detail', component: page },
      { path: '/catalog/products', name: 'catalog-products', component: page },
      { path: '/catalog/products/:productId', name: 'catalog-product-detail', component: page },
    ],
  })
  await router.push(merchant ? '/workbench/products/p-1' : '/catalog/products/p-1')
  const wrapper = mount(component, { props: { merchant }, global: { plugins: [ElementPlus, router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('商品的编辑和删除', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    http.get.mockResolvedValue({ items: [summary('p-1', 'DRAFT'), summary('p-2', 'PUBLISHED'), summary('p-3', 'OFFLINE')],
      page: 1, size: 20, total: 3 })
    http.delete.mockResolvedValue(undefined)
    confirmAction.mockResolvedValue(true)
  })

  it('删除请求按契约发出：DELETE 商家前缀，带版本号', async () => {
    const { http: client, requests } = recordingClient()
    await createCatalogApi(client, MERCHANT_CATALOG_BASE).removeDraft('p-1', 4)
    expect(requests.map((request) => `${request.method} ${request.url}`))
      .toEqual(['DELETE /api/v1/provider/catalog/products/p-1?version=4'])
  })

  it('列表每行有编辑；商家只有草稿能删，删完重新读列表', async () => {
    const { wrapper, router } = await mountPage(ProductListPage, true)
    expect(wrapper.findAll('[data-testid="product-edit"]')).toHaveLength(3)
    const removes = wrapper.findAll('[data-testid="product-remove"]')
    expect(removes).toHaveLength(1)

    await removes[0]!.trigger('click')
    await flushPromises()
    expect(confirmAction).toHaveBeenCalledWith(expect.objectContaining({ title: '确认删除草稿', danger: true }))
    expect(http.delete).toHaveBeenCalledWith('/api/v1/provider/catalog/products/p-1', { query: { version: 4 } })
    expect(http.get).toHaveBeenCalledTimes(2)

    await wrapper.findAll('[data-testid="product-edit"]')[1]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/workbench/products/p-2')
  })

  it('取消不删；后端拒绝时提示，列表不重读', async () => {
    const { wrapper } = await mountPage(ProductListPage, true)
    confirmAction.mockResolvedValueOnce(false)
    await wrapper.get('[data-testid="product-remove"]').trigger('click')
    await flushPromises()
    expect(http.delete).not.toHaveBeenCalled()

    http.delete.mockRejectedValueOnce(new ApiRequestError(409, 'OPS_PRODUCT_NOT_DELETABLE', '', null, null))
    await wrapper.get('[data-testid="product-remove"]').trigger('click')
    await flushPromises()
    expect(showError).toHaveBeenCalledTimes(1)
    expect(http.get).toHaveBeenCalledTimes(1)
  })

  it('运营端只有编辑，没有删除', async () => {
    const { wrapper } = await mountPage(ProductListPage, false)
    expect(wrapper.findAll('[data-testid="product-edit"]')).toHaveLength(3)
    expect(wrapper.find('[data-testid="product-remove"]').exists()).toBe(false)
  })

  it('详情页：商家的草稿能删，删完回列表；上架过的和运营端没有删除', async () => {
    http.get.mockResolvedValue(detail('DRAFT'))
    const { wrapper, router } = await mountPage(ProductDetailPage, true)
    await wrapper.get('[data-testid="product-remove"]').trigger('click')
    await flushPromises()
    expect(http.delete).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.fullPath).toBe('/workbench/products')

    expect((await mountPage(ProductDetailPage, false)).wrapper.find('[data-testid="product-remove"]').exists()).toBe(false)
    http.get.mockResolvedValue(detail('OFFLINE'))
    expect((await mountPage(ProductDetailPage, true)).wrapper.find('[data-testid="product-remove"]').exists()).toBe(false)
  })
})
