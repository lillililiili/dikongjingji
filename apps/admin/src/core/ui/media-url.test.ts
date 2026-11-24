import { describe, expect, it } from 'vitest'

import { displayMediaUrl } from './media-url'

describe('图片显示地址', () => {
  it('演示主机换成同源路径，其他地址不动', () => {
    expect(displayMediaUrl('https://media.demo.localhost/api/v1/mall/media/products/a.png')).toBe('/api/v1/mall/media/products/a.png')
    expect(displayMediaUrl('https://cdn.example.com/a.png')).toBe('https://cdn.example.com/a.png')
    expect(displayMediaUrl(null)).toBe('')
  })
})
