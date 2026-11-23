import { reactive, ref, shallowRef } from 'vue'

import { errorMessage } from '@/core/http/api-error'

/** 后端分页的统一形状（各契约的 *Page：page、size、total、hasMore、items）。 */
export interface Page<T> {
  page: number
  size: number
  total: number
  hasMore: boolean
  items: T[]
}

export type PageLoader<T, F> = (filters: F, page: number, size: number) => Promise<Page<T>>

/**
 * 分页列表的状态：筛选条件、页码、每页条数、数据、加载中、错误。
 * 新的请求发出后，旧请求回来的结果直接丢掉（只认最后一次），避免快速切换筛选时旧数据闪回。
 */
export function usePagedList<T, F extends object>(loader: PageLoader<T, F>, initialFilters: F, pageSize = 20) {
  const filters = reactive({ ...initialFilters }) as F
  const items = shallowRef<T[]>([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(pageSize)
  const loading = ref(false)
  const error = ref('')
  const last = shallowRef<Page<T> | null>(null)
  let revision = 0

  async function load(): Promise<void> {
    const mine = ++revision
    loading.value = true
    error.value = ''
    try {
      const result = await loader({ ...filters } as F, page.value, size.value)
      if (mine !== revision) return
      last.value = result
      items.value = result.items
      total.value = result.total
      // 删掉或筛掉之后这一页空了，退回上一页。
      if (result.items.length === 0 && page.value > 1 && result.total > 0) {
        page.value = Math.max(1, Math.ceil(result.total / size.value))
        await load()
      }
    } catch (failure) {
      if (mine !== revision) return
      error.value = errorMessage(failure)
    } finally {
      if (mine === revision) loading.value = false
    }
  }

  /** 筛选条件变了：回到第一页再查。 */
  function search(): Promise<void> {
    page.value = 1
    return load()
  }

  function reset(): Promise<void> {
    Object.assign(filters, initialFilters)
    return search()
  }

  function changePage(next: number): Promise<void> {
    page.value = next
    return load()
  }

  function changeSize(next: number): Promise<void> {
    size.value = next
    page.value = 1
    return load()
  }

  return { filters, items, total, page, size, loading, error, last, load, search, reset, changePage, changeSize }
}

export type PagedList<T, F extends object> = ReturnType<typeof usePagedList<T, F>>
