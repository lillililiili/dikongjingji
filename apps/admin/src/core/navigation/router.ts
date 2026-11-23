import { createRouter, createWebHistory, type Router, type RouteRecordRaw } from 'vue-router'

import type { AdminModule } from './module'
import { createAuthGuard, FORBIDDEN_NAME, LOGIN_PATH, type GuardSession } from './guard'

/** 所有路由：登录页（公开）+ 登录后的布局（首页、各功能块、无权限、找不到）。 */
export function adminRoutes(modules: readonly AdminModule[]): RouteRecordRaw[] {
  return [
    {
      path: LOGIN_PATH,
      name: 'login',
      component: () => import('@/features/auth/LoginPage.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: () => import('@/layouts/AdminLayout.vue'),
      children: [
        {
          path: '',
          name: 'home',
          component: () => import('@/features/auth/HomePage.vue'),
          meta: { title: '首页' },
        },
        ...modules.flatMap((module) => module.routes),
        {
          path: 'forbidden',
          name: FORBIDDEN_NAME,
          component: () => import('@/features/auth/StatusPage.vue'),
          props: { kind: 'forbidden' },
          meta: { title: '没有权限' },
        },
        {
          path: ':pathMatch(.*)*',
          name: 'not-found',
          component: () => import('@/features/auth/StatusPage.vue'),
          props: { kind: 'not-found' },
          meta: { title: '页面不存在' },
        },
      ],
    },
  ]
}

export function createAdminRouter(modules: readonly AdminModule[], session: () => GuardSession): Router {
  const router = createRouter({ history: createWebHistory(), routes: adminRoutes(modules) })
  router.beforeEach((to) => createAuthGuard(session())(to))
  router.afterEach((to) => {
    document.title = to.meta.title ? `${to.meta.title} · 低空平台运营端` : '低空平台运营端'
  })
  return router
}
