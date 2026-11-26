import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'

import type { PagedList } from '@/core/ui/use-paged-list'
import type { OrderFilters, UserFilters } from './lookup-api'

interface LookupPosition {
  path: string
  filters: UserFilters | OrderFilters
  page: number
  size: number
}

declare module 'vue-router' {
  interface RouteMeta {
    lookupPosition?: LookupPosition
  }
}

/** Keep search terms, which can contain a phone number, in this list/detail navigation only. */
export function useLookupPosition<T, F extends UserFilters>(list: PagedList<T, F>, detailName: string): boolean {
  const route = useRoute()
  const saved = route.meta.lookupPosition
  const restoring = saved?.path === route.fullPath
  if (restoring && saved) {
    Object.assign(list.filters, saved.filters)
    list.page.value = saved.page
    list.size.value = saved.size
  }

  onBeforeRouteLeave((to, from) => {
    if (to.name === detailName) {
      to.meta.lookupPosition = {
        path: from.fullPath, filters: { ...list.filters }, page: list.page.value, size: list.size.value,
      }
    }
  })
  return restoring
}

export function useLookupReturn(defaultList: string) {
  const router = useRouter()
  const saved = useRoute().meta.lookupPosition
  let returning = false
  onBeforeRouteLeave(to => {
    // Browser Back has already activated the target history entry by the time Vue runs this guard.
    if (saved?.path === to.fullPath && (returning || router.options.history.state.current === saved.path)) {
      to.meta.lookupPosition = saved
    }
  })
  return () => {
    returning = true
    if (saved && router.options.history.state.back === saved.path) router.back()
    else void router.push(saved?.path ?? { name: defaultList })
  }
}
