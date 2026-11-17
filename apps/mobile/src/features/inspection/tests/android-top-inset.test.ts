import { describe, expect, it } from 'vitest'

import catalogHeaderSource from '../presentation/catalog/components/CatalogHeader.vue?raw'
import catalogPageSource from '../presentation/catalog/pages/InspectionCatalogPage.vue?raw'
import detailHeaderSource from '../presentation/detail/components/ServiceDetailHeader.vue?raw'
import detailPageSource from '../presentation/detail/pages/InspectionServiceDetailPage.vue?raw'
import homeHeaderSource from '../../../app-shell/components/HomeHeader.vue?raw'

describe('Android custom-navigation top inset', () => {
  it.each([
    ['home header', homeHeaderSource],
    ['catalog header', catalogHeaderSource],
    ['detail header', detailHeaderSource],
    ['detail content', detailPageSource],
  ])('keeps %s below the native status bar', (_label, source) => {
    expect(source).toContain('var(--status-bar-height)')
    expect(source).not.toContain('env(safe-area-inset-top)')
  })

  // The catalog header carries the inset; the list takes the space left in a flex column,
  // so the page itself no longer subtracts the status bar and a header height.
  it('lets the catalog list fill what the header leaves', () => {
    expect(catalogPageSource).toContain('flex: 1 1 0')
    expect(catalogPageSource).not.toContain('calc(100vh - var(--status-bar-height)')
    expect(catalogPageSource).not.toContain('env(safe-area-inset-top)')
  })
})
