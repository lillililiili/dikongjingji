import { describe, expect, it } from 'vitest'

import { articleFigures } from '../application/content-view'
import type { ContentBlock } from '../domain/content'

function image(url: string | null, caption: string | null = '围墙外侧由无人机补充巡查，机器狗负责地面路线'): ContentBlock {
  return { type: 'IMAGE', text: null, items: [], image: { url, alt: '无人机在园区上空巡查', caption } }
}

const steps: ContentBlock = { type: 'STEPS', text: null, items: [{ title: '白天踏勘，定路线', text: null }], image: null }

describe('正文配图（1.3.0，画板 ContentArticle）', () => {
  it('keeps only the images supplied by the article, in block order', () => {
    expect(articleFigures({ blocks: [steps, image(null), image('https://cdn.example/b.png', null)] })).toEqual([
      null, null, { url: 'https://cdn.example/b.png', alt: '无人机在园区上空巡查', caption: null },
    ])
    expect(articleFigures({ blocks: [] })).toEqual([])
  })
})
