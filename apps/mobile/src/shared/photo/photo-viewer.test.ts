import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import pagesJson from '../../pages.json?raw'
import { photoPageText, usePhotoViewerStore } from './photo-viewer'

describe('photo viewer (board PhotoViewer)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('keeps only controlled picture addresses and a start inside the list', () => {
    const store = usePhotoViewerStore()
    store.show({
      title: '评价图片',
      subtitle: '王** · 11/03',
      startIndex: 5,
      photos: [
        { url: 'https://cdn.example.test/a.jpg', alt: 'a', tag: '' },
        { url: 'http://evil.example.test/b.jpg', alt: 'b', tag: '' },
        { url: '/static/preview/c.jpg', alt: 'c', tag: '' },
        { url: 'javascript:alert(1)', alt: 'd', tag: '' },
      ],
    })
    expect(store.request?.photos.map((photo) => photo.alt)).toEqual(['a', 'c'])
    expect(store.request?.startIndex).toBe(1)
  })

  it('writes the page counter and has its page', () => {
    expect(photoPageText(0, 3)).toEqual({ text: '1 / 3', aria: '第 1 张，共 3 张' })
    expect(pagesJson).toContain('"path": "pages/common/photo-viewer"')
  })
})
