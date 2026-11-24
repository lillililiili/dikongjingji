import { describe, expect, it } from 'vitest'

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
  })
})
