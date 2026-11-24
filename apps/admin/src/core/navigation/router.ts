import { createRouter, createWebHistory, type Router, type RouteRecordRaw } from 'vue-router'

import type { AdminModule } from './module'
import { createAuthGuard, FORBIDDEN_NAME, LOGIN_PATH, type GuardSession } from './guard'

export const SIGNUP_PATH = '/signup'

/** 所有路由：登录页和入驻申请页（公开）+ 登录后的布局（首页、各功能块、无权限、找不到）。 */
export function adminRoutes(modules: readonly AdminModule[]): RouteRecordRaw[] {
  return [
    {
      path: LOGIN_PATH,
      name: 'login',
      component: () => import('@/features/auth/LoginPage.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      // 商家自助入驻（契约 openapi-organization-signup.yaml）：不登录就能填；申请成功后回登录页。
      path: SIGNUP_PATH,
      name: 'signup',
      component: () => import('@/features/signup/SignupPage.vue'),
      meta: { public: true, title: '商家入驻申请' },
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
