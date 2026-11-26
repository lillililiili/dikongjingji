import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

import { useLayoutStore } from './layout-store'

function fakeRoute(path: string, title: string, query = ''): RouteLocationNormalizedLoaded {
  return {
    path,
    fullPath: query ? `${path}?${query}` : path,
    name: path === '/' ? 'home' : path.slice(1),
    meta: { title },
    params: {},
    query: {},
    hash: '',
    matched: [],
    redirectedFrom: undefined,
  }
}

describe('若依布局与多页签状态（useLayoutStore）', () => {
  beforeEach(() => {
    window.localStorage.clear()
    setActivePinia(createPinia())
  })

  it('访问路由自动追加多页签，固定首页不可关闭，支持关闭其他与全部', () => {
    const store = useLayoutStore()
    expect(store.visitedTabs.map((item) => item.path)).toEqual(['/'])

    store.visitRoute(fakeRoute('/cities', '城市开通'))
    store.visitRoute(fakeRoute('/catalog/products', '商品与供给', 'page=2'))
    expect(store.visitedTabs.map((item) => item.title)).toEqual(['首页', '城市开通', '商品与供给'])

    expect(store.closeTab('/')).toBeNull()
    const next = store.closeTab('/catalog/products')
    expect(next?.path).toBe('/cities')

    store.visitRoute(fakeRoute('/organizations', '机构与资质'))
    store.closeOtherTabs('/organizations')
    expect(store.visitedTabs.map((item) => item.path)).toEqual(['/', '/organizations'])

    const home = store.closeAllTabs()
    expect(home.path).toBe('/')
    expect(store.visitedTabs.map((item) => item.path)).toEqual(['/'])
  })

  it('切换主题主色时同步更新根元素 Element Plus 颜色变量', () => {
    const store = useLayoutStore()
    expect(document.documentElement.style.getPropertyValue('--el-color-primary')).toBe('#1677ff')
    store.primaryColor = '#2f54eb'
    store.toggleSidebar()
    expect(store.sidebarCollapsed).toBe(true)
    expect(document.documentElement.style.getPropertyValue('--el-color-primary')).toBe('#2f54eb')
  })
})
