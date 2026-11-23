import { describe, expect, it, vi } from 'vitest'

import {
  EMPTY_HOME_RECOMMEND,
  createHomeRecommendLoader,
  formatTodaySeats,
  formatTodaySlotTime,
  type HomeRecommendContent,
} from '../application/home-recommend'
import { createHomeRecommendApi } from '../data/home-recommend-api'

const venueDto = {
  venueId: 'b2000000-0000-4000-8000-000000000001',
  name: '东营低空与机器人体验馆',
  coverImage: { url: 'http://insecure.example/venue.jpg', alt: '场馆' },
  openingNote: '09:30–18:00',
  address: '东营区示例路 66 号',
  openSessionsToday: 2,
  projectSummary: '模拟飞行 · 机器人编程 · 厂家展区',
}
const slot = {
  projectId: 'b4000000-0000-4000-8000-000000000101',
  projectName: '机器人避障闯关',
  startsAt: '2026-11-17T14:00:00+08:00',
  seatsLeft: 6,
}

function contentFor(name: string): HomeRecommendContent {
  return {
    ...EMPTY_HOME_RECOMMEND,
    venues: [{
      venueId: name,
      name,
      coverImage: null,
      openingNote: '',
      address: '',
      openSessionsToday: 0,
      projectSummary: null,
    }],
  }
}

describe('home recommend api', () => {
  it('asks once for the city and maps each block with the mapper of its own feature', async () => {
    const request = vi.fn().mockResolvedValue({
      today: { venue: venueDto, slots: [slot] },
      activities: [],
      products: [{
        productId: 'c2000000-0000-4000-8000-000000000101',
        category: 'ROBOT',
        name: '编程机器人探索套装',
        coverImage: null,
        saleMode: 'BUY',
        experienceBadge: 'ONLINE_EXPERIENCE',
        price: { fromAmountMinor: 129900, unit: 'ITEM', multiplePrices: true },
        sellerCount: 3,
      }],
      venues: [venueDto],
    })

    const content = await createHomeRecommendApi({ request }).load('370500')

    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0][0]).toEqual({ method: 'GET', url: '/api/v1/home/recommend?cityCode=370500' })
    expect(content.today?.venue.name).toBe('东营低空与机器人体验馆')
    // The venue mapper drops a picture that is not HTTPS.
    expect(content.today?.venue.coverImage).toBeNull()
    expect(content.today?.slots).toEqual([slot])
    expect(content.products[0].price.fromAmountMinor).toBe(129900)
    expect(content.venues).toHaveLength(1)
    expect(content.venues[0].projectSummary).toBe('模拟飞行 · 机器人编程 · 厂家展区')
  })

  it('treats a today block without slots as no block', async () => {
    const request = vi.fn().mockResolvedValue({
      today: { venue: venueDto, slots: [] },
      activities: [],
      products: [],
      venues: [],
    })

    expect((await createHomeRecommendApi({ request }).load('370500')).today).toBeNull()
  })
})

describe('home recommend loader', () => {
  it('clears the blocks first, then shows what the server returned', async () => {
    const seen: HomeRecommendContent[] = []
    const loader = createHomeRecommendLoader(
      { load: async () => contentFor('东营') },
      { setContent: (content) => { seen.push(content) } },
    )

    await loader.load('370500')

    expect(seen).toEqual([EMPTY_HOME_RECOMMEND, contentFor('东营')])
  })

  it('leaves the blocks out when the request fails', async () => {
    let current: HomeRecommendContent = contentFor('旧内容')
    const loader = createHomeRecommendLoader(
      { load: async () => { throw new Error('offline') } },
      { setContent: (content) => { current = content } },
    )

    await loader.load('370500')

    expect(current).toEqual(EMPTY_HOME_RECOMMEND)
  })

  it('drops the answer of the previous city once another city is being loaded', async () => {
    let current: HomeRecommendContent = EMPTY_HOME_RECOMMEND
    const releases: Record<string, () => void> = {}
    const loader = createHomeRecommendLoader(
      {
        load: (cityCode) => new Promise((resolve) => {
          releases[cityCode] = () => resolve(contentFor(cityCode))
        }),
      },
      { setContent: (content) => { current = content } },
    )

    const first = loader.load('370500')
    const second = loader.load('370100')
    releases['370100']()
    await second
    releases['370500']()
    await first

    expect(current.venues[0].name).toBe('370100')
  })
})

describe('today slot labels', () => {
  it('formats the start time in Beijing time and the seats left', () => {
    expect(formatTodaySlotTime({ ...slot, startsAt: '2026-11-17T06:00:00Z' })).toBe('14:00 场')
    expect(formatTodaySeats(slot)).toBe('余 6 位')
  })
})
