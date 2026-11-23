import 'element-plus/dist/index.css'
import './styles/theme.css'

import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import { useSessionStore } from './core/auth/session'
import { onUnauthorized } from './core/http'
import { loginRedirectFor } from './core/navigation/guard'
import { createAdminRouter } from './core/navigation/router'
import { ADMIN_MODULES } from './modules'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

const session = useSessionStore(pinia)
const router = createAdminRouter(ADMIN_MODULES, () => session)

// 接口回 401 且用刷新令牌也续不了期（刷新令牌过期或被撤销、账号被停用、角色改了令牌版本变了）：
// 清登录状态，回登录页并记住当前页面。
onUnauthorized(() => {
  session.logout()
  const target = loginRedirectFor(router.currentRoute.value)
  if (target) void router.replace(target)
})

app.use(router)
app.use(ElementPlus)
app.mount('#app')
