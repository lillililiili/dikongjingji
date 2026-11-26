import { describe, expect, it, vi } from 'vitest'
import { usePagedList } from './use-paged-list'

describe('分页列表刷新', () => {
  it('数据全部移除时回到第一页', async () => {
    const load = vi.fn(async () => ({ page: 2, size: 20, items: [], total: 0, hasMore: false }))
    const list = usePagedList(load, {})
    await list.changePage(2)
    expect(list.page.value).toBe(1)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('稀疏空页不反复请求同一页', async () => {
    const load = vi.fn().mockResolvedValueOnce({ page: 2, size: 20, items: [], total: 25, hasMore: false })
    const list = usePagedList(load, {})
    await list.changePage(2)
    expect(load).toHaveBeenCalledTimes(1)
    expect(list.error.value).toBe('')
  })
})
