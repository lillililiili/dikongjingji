import { describe, expect, it } from 'vitest'

import type { ContentLinkedApis } from '../../content/application/content-refs'
import { currentLiveItem, liveItemPrice, resolveLiveItems } from '../application/live-items'
import type { LiveItem } from '../domain/live'

const CITY = '370500'
const SERVICE = '77777777-7777-7777-7777-777777777777'
const PRODUCT = 'c2000000-0000-4000-8000-000000000401'
const GONE = 'c2000000-0000-4000-8000-000000000902'

// Only what the explainer list reads: a park inspection service and a rental robot dog.
const apis = {
  inspection: {
    async loadDetail(serviceId: string) {
      if (serviceId !== SERVICE) throw new Error('not found')
      return { title: '园区综合巡检服务', priceAmountMinor: null, priceUnitLabel: '', heroImageUrl: null }
    },
  },
  proService: { async loadService() { throw new Error('not used') } },
  mall: {
    async loadProduct(productId: string) {
      if (productId !== PRODUCT) throw new Error('taken down')
      return {
        name: '巡检机器狗',
        saleMode: 'RENT',
        coverImage: null,
        offers: [{ amountMinor: 38000, unit: 'DAY' }, { amountMinor: 42000, unit: 'DAY' }],
      }
    },
  },
  activity: { async loadDetail() { throw new Error('not used') } },
  experience: { async loadVenue() { throw new Error('not used') }, async loadSessions() { return [] } },
} as unknown as ContentLinkedApis

const items: LiveItem[] = [
  { seq: 1, type: 'INSPECTION_SERVICE', refId: SERVICE, title: '园区安防巡检' },
  { seq: 2, type: 'PRODUCT', refId: PRODUCT, title: '巡检机器狗' },
  { seq: 3, type: 'PRODUCT', refId: GONE, title: '已下架的' },
]

describe('explainer list', () => {
  it('reads each item from its own module, keeps the host order and leaves out what cannot be read', async () => {
    const views = await resolveLiveItems(items, CITY, apis)

    expect(views.map((view) => view.seq)).toEqual([1, 2])
    expect(views[0]).toMatchObject({ tag: '服务', title: '园区综合巡检服务', actionLabel: '看服务', primary: true })
    expect(views[0].url).toBe(`/pages/service/inspection/detail?serviceId=${SERVICE}&cityCode=${CITY}`)
    expect(views[1]).toMatchObject({ tag: '租赁', actionLabel: '看设备' })
    expect(views[1].url).toBe(`/pages/mall/product?productId=${PRODUCT}&cityCode=${CITY}`)
    expect(liveItemPrice(views[0])).toBe('按项目报价')
    expect(liveItemPrice(views[1])).toBe('¥380/天 起')
  })

  it('pins the current item only when it could be read', async () => {
    const views = await resolveLiveItems(items, CITY, apis)
    expect(currentLiveItem(views, 2)?.seq).toBe(2)
    expect(currentLiveItem(views, 3)).toBeNull()
    expect(currentLiveItem(views, null)).toBeNull()
  })
})
