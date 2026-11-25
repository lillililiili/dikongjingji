import { describe, expect, it } from 'vitest'

import pages from '../../../pages.json'
import mallRouteSource from '../../../pages/mall/index.vue?raw'

describe('legacy mall link', () => {
  it('opens the current mall when available and the recommend channel otherwise', () => {
    expect(mallRouteSource).toContain("openHomeChannel(MALL_PREVIEW_ENABLED ? 'MALL' : 'RECOMMEND')")
    expect(pages.pages.some(page => page.path === 'pages/mall/index')).toBe(true)
    expect(pages.tabBar.list.some(tab => tab.pagePath === 'pages/mall/index')).toBe(false)
  })
})
