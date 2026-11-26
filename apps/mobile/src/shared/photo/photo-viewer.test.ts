import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import pagesJson from '../../pages.json?raw'
import { DEMO_MEDIA_ORIGIN, rewriteDemoMedia } from '../http/media-url'
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

  it('opens and saves the selected original after an unavailable picture is filtered out', async () => {
    const save = vi.fn().mockResolvedValue({ until: null })
    const photos = ['http://outside.test/photo', '/static/detail.jpg', '/static/next.jpg']
      .map((url) => ({ url, alt: '', tag: '' }))
    const store = usePhotoViewerStore()
    store.show({ title: '', subtitle: '', startIndex: 1, photos, save })
    expect(store.request?.startIndex).toBe(0)
    await store.request?.save?.(0)
    expect(save).toHaveBeenCalledWith(1)
  })

  it('opens a demo picture served from the configured LAN media origin', () => {
    const url = rewriteDemoMedia(`${DEMO_MEDIA_ORIGIN}/catalog/picture.jpg`, 'http://192.168.1.20:18420/api/v1', true)
    const store = usePhotoViewerStore()
    store.show({ title: '', subtitle: '', startIndex: 0, photos: [{ url, alt: '', tag: '' }] })
    expect(store.request?.photos).toHaveLength(1)
  })
})
