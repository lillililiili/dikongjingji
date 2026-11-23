import { describe, expect, it } from 'vitest'

import { articleFigures } from '../application/content-view'
import type { ContentBlock } from '../domain/content'

const ARTICLE = 'f3000000-0000-4000-8000-000000000001'

function image(url: string | null, caption: string | null = '围墙外侧由无人机补充巡查，机器狗负责地面路线'): ContentBlock {
  return { type: 'IMAGE', text: null, items: [], image: { url, alt: '无人机在园区上空巡查', caption } }
}

const steps: ContentBlock = { type: 'STEPS', text: null, items: [{ title: '白天踏勘，定路线', text: null }], image: null }

describe('正文配图（1.3.0，画板 ContentArticle）', () => {
  it('draws the server picture, or the board picture by the order of the picture blocks', () => {
    const asked: Array<[string, number]> = []
    const figures = articleFigures(
      { articleId: ARTICLE, blocks: [steps, image(null), image('https://cdn.example/b.png', null), image(null)] },
      (articleId, ordinal) => {
        asked.push([articleId, ordinal])
        return ordinal === 0 ? '/static/images/preview/content-patrol-drone.jpg' : null
      },
    )

    expect(figures).toEqual([
      null,
      { url: '/static/images/preview/content-patrol-drone.jpg', alt: '无人机在园区上空巡查', caption: '围墙外侧由无人机补充巡查，机器狗负责地面路线' },
      { url: 'https://cdn.example/b.png', alt: '无人机在园区上空巡查', caption: null },
      // No licensed picture and none on the board: the block is left out.
      null,
    ])
    // The server's own picture does not ask for a stand-in, but still counts as a picture block.
    expect(asked).toEqual([[ARTICLE, 0], [ARTICLE, 2]])
  })

  it('leaves every picture block out when there is nothing to draw (public builds)', () => {
    expect(articleFigures({ articleId: ARTICLE, blocks: [image(null)] }, () => null)).toEqual([null])
    expect(articleFigures({ articleId: ARTICLE, blocks: [] })).toEqual([])
  })
})
