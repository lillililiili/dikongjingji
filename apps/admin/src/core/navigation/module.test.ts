import { describe, expect, it } from 'vitest'

import { ADMIN_ROLES } from '@/core/auth/roles'
import { ADMIN_MODULES } from '@/modules'

import { buildMenu } from './module'
import { adminRoutes } from './router'

describe('菜单', () => {
  it('平台运营看到城市开通和机构与资质，看不到举报与反馈', () => {
    const groups = buildMenu(ADMIN_MODULES, ['PLATFORM_OPERATOR'])

    expect(groups.map((group) => group.title)).toEqual(['平台运营'])
    // 前两项固定；S3-1 第三部分各块（场馆与设备等）按 order 排在后面。
    expect(groups[0]!.items.map((item) => item.title).slice(0, 2)).toEqual(['城市开通', '机构与资质'])
    expect(groups[0]!.items.map((item) => item.title)).toContain('场馆与设备')
    expect(groups[0]!.items.map((item) => item.title)).not.toContain('举报与反馈')
  })

  it('平台客服只看到举报与反馈；机构角色和客户现在没有菜单', () => {
    expect(buildMenu(ADMIN_MODULES, ['PLATFORM_SUPPORT']).flatMap((group) => group.items.map((item) => item.title)))
      .toEqual(['举报与反馈'])
    expect(buildMenu(ADMIN_MODULES, ['PROVIDER_OPERATOR', 'LIVE_HOST'])).toEqual([])
    expect(buildMenu(ADMIN_MODULES, ['CUSTOMER'])).toEqual([])
  })

  it('两个平台角色都有时按分组顺序排', () => {
    expect(buildMenu(ADMIN_MODULES, ['PLATFORM_SUPPORT', 'PLATFORM_OPERATOR']).map((group) => group.key))
      .toEqual(['PLATFORM', 'SUPPORT'])
  })
})

// 新增功能块时最容易漏的：菜单指向的路由不存在、路由忘了写角色、菜单和路由的角色不一致。
describe('功能块登记', () => {
  const routes = ADMIN_MODULES.flatMap((module) => module.routes)

  it('每个功能块的 key 和路由名不重复', () => {
    const keys = ADMIN_MODULES.map((module) => module.key)
    expect(new Set(keys).size).toBe(keys.length)
    const names = routes.map((route) => route.name)
    expect(new Set(names).size).toBe(names.length)
  })

  it('每个路由都写了标题和角色，角色都是运营端认的', () => {
    for (const route of routes) {
      expect(route.path, String(route.name)).toMatch(/^\//)
      expect(route.meta?.title, route.path).toBeTruthy()
      expect(route.meta?.roles?.length, route.path).toBeGreaterThan(0)
      for (const role of route.meta?.roles ?? []) {
        expect(ADMIN_ROLES).toContain(role)
      }
    }
  })

  it('每个菜单项都指向一个路由，角色和路由一致', () => {
    for (const item of ADMIN_MODULES.flatMap((module) => module.menu)) {
      const route = routes.find((candidate) => candidate.path === item.path)
      expect(route, item.path).toBeDefined()
      expect([...(route?.meta?.roles ?? [])].sort()).toEqual([...item.roles].sort())
    }
  })

  it('登录页公开，其余都挂在登录后的布局下', () => {
    const all = adminRoutes(ADMIN_MODULES)
    expect(all.find((route) => route.path === '/login')?.meta?.public).toBe(true)
    const layout = all.find((route) => route.path === '/')
    expect(layout?.children?.map((child) => child.path)).toEqual(expect.arrayContaining(
      ['', '/cities', '/organizations', '/organizations/:organizationId', '/feedback', 'forbidden']))
  })
})
