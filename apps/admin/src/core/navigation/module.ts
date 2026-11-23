import type { Component } from 'vue'
import type { RouteRecordRaw } from 'vue-router'

import type { AdminRole } from '@/core/auth/roles'

/**
 * 菜单分组：平台运营（PLATFORM_OPERATOR）、客服（PLATFORM_SUPPORT）、机构工作台（PROVIDER_OPERATOR、LIVE_HOST 等机构角色）。
 * 同一组里按 order 从小到大排。
 */
export type MenuGroupKey = 'PLATFORM' | 'SUPPORT' | 'WORKBENCH'

export const MENU_GROUPS: ReadonlyArray<{ key: MenuGroupKey, title: string }> = [
  { key: 'PLATFORM', title: '平台运营' },
  { key: 'SUPPORT', title: '客服' },
  { key: 'WORKBENCH', title: '机构工作台' },
]

export interface AdminMenuItem {
  /** 菜单指向的路由路径（以 / 开头，和 routes 里的某个 path 一致）。 */
  path: string
  title: string
  icon: Component
  group: MenuGroupKey
  /** 能看到这个菜单的角色（任一即可）；要和对应路由 meta.roles 一致，测试会检查。 */
  roles: readonly AdminRole[]
  order: number
}

/**
 * 一块运营功能（一个 feature 目录）对外交出来的东西：菜单项和路由。新增一块功能只要写一个 AdminModule，
 * 再在 src/modules.ts 里登记，不用改布局、守卫和菜单代码。路由都挂在登录后的布局下，path 用绝对路径。
 */
export interface AdminModule {
  key: string
  menu: readonly AdminMenuItem[]
  routes: readonly RouteRecordRaw[]
}

export interface MenuGroupView {
  key: MenuGroupKey
  title: string
  items: AdminMenuItem[]
}

/** 按账号角色出菜单：只留看得见的项，空的分组不显示。 */
export function buildMenu(modules: readonly AdminModule[], accountRoles: readonly string[]): MenuGroupView[] {
  const visible = modules.flatMap((module) => module.menu)
    .filter((item) => item.roles.some((role) => accountRoles.includes(role)))
  return MENU_GROUPS
    .map((group) => ({
      key: group.key,
      title: group.title,
      items: visible.filter((item) => item.group === group.key).sort((a, b) => a.order - b.order),
    }))
    .filter((group) => group.items.length > 0)
}
