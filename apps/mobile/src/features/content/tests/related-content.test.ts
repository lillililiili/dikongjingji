import { describe, expect, it, vi } from 'vitest'

import {
  RELATED_CONTENT_LIMIT,
  loadRelatedContentRows,
  relatedContentArticleUrl,
  toRelatedContentRows,
} from '../application/related-content'
import { createContentApi } from '../data/content-api'
import { CITY, articleCard, articleCardDto } from './content-fixtures'

const KIT = 'c2000000-0000-4000-8000-000000000101'
const TIPS = 'f3000000-0000-4000-8000-000000000005'

describe('related content API (1.1.0)', () => {
  it('reads the articles mentioning an object, once each, as discover cards', async () => {
    const urls: string[] = []
    const api = createContentApi({
      async request<T>(input: { url: string; method: 'GET' }): Promise<T> {
        urls.push(input.url)
        return {
          refType: 'PRODUCT',
          refId: KIT,
          items: [
            articleCardDto({ articleId: TIPS, kind: 'KNOWLEDGE', author: { kind: 'MERCHANT', organizationId: 'm', name: '示例机器人商家' } }),
            articleCardDto({ articleId: TIPS }),
            articleCardDto({ coverImage: { url: 'http://insecure.example/a.jpg', alt: '封面' } }),
          ],
        } as T
      },
    })

    const cards = await api.loadRelated('PRODUCT', KIT, CITY, 3)

    expect(urls).toEqual([`/api/v1/content/related?refType=PRODUCT&refId=${KIT}&cityCode=${CITY}&limit=3`])
    expect(cards.map((card) => card.articleId)).toEqual([TIPS, 'f3000000-0000-4000-8000-000000000001'])
    // A merchant's article is an advertisement even if the row says otherwise.
    expect(cards[0].sponsored).toBe(true)
    expect(cards[1].coverImage).toBeNull()
  })
})

describe('相关内容 rows', () => {
  it('writes the kind line and uses only the server picture', () => {
    const rows = toRelatedContentRows([
      articleCard({ coverImage: { url: 'https://media.example.com/a.jpg', alt: '园区外景' }, sample: false }),
      articleCard({ articleId: TIPS, kind: 'KNOWLEDGE', sponsored: true, sample: true }),
    ])

    expect(rows[0]).toMatchObject({
      kindLine: '案例',
      title: '一台机器狗如何完成园区夜间巡逻',
      image: { url: 'https://media.example.com/a.jpg', alt: '园区外景', cutout: false },
    })
    expect(rows[1].kindLine).toBe('知识 · 广告 · 示例')
    expect(rows[1].image).toBeNull()
    expect(rows[1].tone).toEqual({ background: '#fff0e3', color: '#ba470b' })
  })

  it('has no picture on public builds or for an unknown article, and opens the article page', () => {
    expect(toRelatedContentRows([articleCard({ articleId: TIPS })])[0].image).toBeNull()
    const unknown = toRelatedContentRows([articleCard({ articleId: 'f3000000-0000-4000-8000-000000000099' })])[0]
    expect(unknown.image).toBeNull()
    expect(unknown.tone).toEqual({ background: '#e2f6ed', color: '#11804a' })
    expect(relatedContentArticleUrl({ articleId: TIPS }, CITY))
      .toBe(`/pages/content/article?articleId=${TIPS}&cityCode=${CITY}`)
  })

  it('asks for three and leaves the block out when the read fails', async () => {
    const api = { loadRelated: vi.fn().mockResolvedValue([articleCard()]) }
    expect(await loadRelatedContentRows(api, 'PRODUCT', KIT, CITY)).toHaveLength(1)
    expect(api.loadRelated).toHaveBeenCalledWith('PRODUCT', KIT, CITY, RELATED_CONTENT_LIMIT)
    expect(RELATED_CONTENT_LIMIT).toBe(3)

    const failing = { loadRelated: vi.fn().mockRejectedValue(new Error('offline')) }
    expect(await loadRelatedContentRows(failing, 'PRODUCT', KIT, CITY)).toEqual([])
    expect(await loadRelatedContentRows(api, 'PRODUCT', '', CITY)).toEqual([])
  })
})
