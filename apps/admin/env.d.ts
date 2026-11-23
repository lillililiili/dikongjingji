/// <reference types="vite/client" />

import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /** 不登录也能看（登录页）。 */
    public?: boolean
    /** 能进这个页面的角色（任一即可）；不写的页面只要登录。 */
    roles?: readonly import('./src/core/auth/roles').AdminRole[]
    /** 页面标题（浏览器标签和面包屑）。 */
    title?: string
  }
}
