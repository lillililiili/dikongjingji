import { describe, expect, it } from 'vitest'

import { ADMIN_ROLES } from '@/core/auth/roles'
import { ADMIN_MODULES } from '@/modules'

import { buildMenu } from './module'
import { adminRoutes } from './router'

describe('菜单', () => {
  it('平台运营按 order 看到全部运营菜单，看不到举报与反馈', () => {
    const groups = buildMenu(ADMIN_MODULES, ['PLATFORM_OPERATOR'])

    expect(groups.map((group) => group.title)).toEqual(['平台运营'])
    expect(groups[0]!.items.map((item) => item.title)).toEqual(['城市开通', '机构与资质', '商品与供给', '商家资料审核', '收款账户', '批量导入',
      '活动与场次', '课程', '场馆与设备', '发现内容', '直播间', '巡检服务目录', '专业服务目录'])
  })

  it('平台客服只看到举报与反馈；客户没有菜单', () => {
    expect(buildMenu(ADMIN_MODULES, ['PLATFORM_SUPPORT']).flatMap((group) => group.items.map((item) => item.title)))
      .toEqual(['举报与反馈'])
    expect(buildMenu(ADMIN_MODULES, ['CUSTOMER'])).toEqual([])
  })

  it('机构操作员（商家 / 服务商）只看到机构工作台，按 order 排；直播主办多一项“直播间”', () => {
    const operator = buildMenu(ADMIN_MODULES, ['PROVIDER_OPERATOR'])
    expect(operator.map((group) => group.key)).toEqual(['WORKBENCH'])
    expect(operator[0]!.items.map((item) => item.title)).toEqual([
      '商城订单', '售后', '商城询价', '租赁询价', '租赁订单', '需求报价', '巡检预约', '客服会话', '商家资料', '收款账户',
    ])
    expect(operator[0]!.items.every((item) => item.path.startsWith('/workbench/'))).toBe(true)

    const merchantHost = buildMenu(ADMIN_MODULES, ['PROVIDER_OPERATOR', 'LIVE_HOST'])
    expect(merchantHost[0]!.items.map((item) => item.title)).toContain('直播间')
    expect(buildMenu(ADMIN_MODULES, ['LIVE_HOST']).flatMap((group) => group.items.map((item) => item.title)))
      .toEqual(['直播间'])
  })

  it('平台角色看不到机构工作台', () => {
    for (const role of ['PLATFORM_OPERATOR', 'PLATFORM_SUPPORT']) {
      expect(buildMenu(ADMIN_MODULES, [role]).map((group) => group.key)).not.toContain('WORKBENCH')
    }
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
