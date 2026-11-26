import { describe, expect, it } from 'vitest'

import { businessImage } from './business-media'
import { isMediaUrl, rewriteDemoMedia } from './media-url'

describe('picture addresses', () => {
  it('lets only HTTPS through, and leaves responses alone outside the demo', () => {
    expect(isMediaUrl('https://cdn.example.com/a.png')).toBe(true)
    expect(isMediaUrl('http://192.168.1.9:19420/api/v1/mall/media/products/a.png')).toBe(false)
    expect(isMediaUrl(null)).toBe(false)
    const body = { coverImage: { url: 'https://media.demo.localhost/api/v1/mall/media/products/a.png' } }
    expect(rewriteDemoMedia(body, 'http://192.168.1.9:19420/api/v1/mall/products')).toBe(body)
  })

  it('a demo build reads the demo host from the address the API answered on, and nothing else over HTTP', () => {
    const body = { items: [{ url: 'https://media.demo.localhost/api/v1/mall/media/products/a.png', name: 'a', n: null }] }
    const rewritten = rewriteDemoMedia(body, 'http://192.168.1.9:19420/api/v1/mall/products?x=1', true)
    expect(rewritten).toEqual({ items: [{ url: 'http://192.168.1.9:19420/api/v1/mall/media/products/a.png', name: 'a', n: null }] })
    expect(isMediaUrl(rewritten.items[0].url)).toBe(true)
    expect(isMediaUrl('http://192.168.1.10:19420/a.png')).toBe(false)
    // The mappers go through businessImage: it has to pass what the demo rewrite produced (ops pictures too).
    const ops = rewriteDemoMedia({ url: 'https://media.demo.localhost/api/v1/dev/ops-media/cover-1.png' }, 'http://192.168.1.9:19420/api/v1/activities', true)
    expect(businessImage({ url: ops.url, alt: '封面' })).toEqual({ url: 'http://192.168.1.9:19420/api/v1/dev/ops-media/cover-1.png', alt: '封面' })
    expect(businessImage({ url: 'http://192.168.1.10:19420/api/v1/dev/ops-media/cover-1.png', alt: '' })).toBeNull()
  })
})
