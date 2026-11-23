<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { roleLabel } from '@/core/auth/roles'
import { useSessionStore } from '@/core/auth/session'
import { buildMenu } from '@/core/navigation/module'
import { confirmAction } from '@/core/ui/feedback'
import { ADMIN_MODULES } from '@/modules'

/** 登录后的布局：左侧按角色出的菜单（照原型 Ops 看板：白底侧栏、选中项浅橙底），右侧页面。 */
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const groups = computed(() => buildMenu(ADMIN_MODULES, session.roles))
/** 详情页（/organizations/xxx）也点亮它的列表菜单。 */
const active = computed(() => {
  const paths = groups.value.flatMap((group) => group.items.map((item) => item.path))
  return paths.filter((path) => route.path === path || route.path.startsWith(`${path}/`))
    .sort((a, b) => b.length - a.length)[0] ?? route.path
})

async function logout(): Promise<void> {
  if (!(await confirmAction({ title: '退出登录', message: '确定退出运营端？', confirmText: '退出' }))) return
  session.logout()
  await router.replace('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside class="layout__aside" width="220px">
      <router-link to="/" class="layout__brand">城市运营工作台</router-link>
      <el-menu :default-active="active" router class="layout__menu">
        <el-menu-item index="/">
          <span>首页</span>
        </el-menu-item>
        <el-menu-item-group v-for="group in groups" :key="group.key" :title="group.title">
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="layout__header" height="56px">
        <span class="layout__page">{{ route.meta.title ?? '' }}</span>
        <div class="layout__account">
          <span class="layout__name">{{ session.account?.displayName }}</span>
          <el-tag v-for="role in session.roles" :key="role" size="small" effect="plain">{{ roleLabel(role) }}</el-tag>
          <el-button link @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="layout__main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}

.layout__aside {
  display: flex;
  flex-direction: column;
  padding: 20px 12px;
  border-right: 1px solid var(--admin-border);
  background: #fff;
}

.layout__brand {
  padding: 0 12px 16px;
  font-size: 17px;
  font-weight: 700;
  color: var(--admin-text);
  text-decoration: none;
}

.layout__menu {
  border-right: 0;
  --el-menu-item-height: 42px;
}

.layout__menu :deep(.el-menu-item) {
  margin: 2px 0;
  border-radius: 10px;
}

.layout__menu :deep(.el-menu-item.is-active) {
  background: var(--admin-brand-surface);
  color: var(--admin-brand-text);
  font-weight: 500;
}

.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--admin-border);
  background: #fff;
}

.layout__page {
  font-size: 15px;
  color: var(--admin-text-secondary);
}

.layout__account {
  display: flex;
  align-items: center;
  gap: 8px;
}

.layout__name {
  font-weight: 500;
}

.layout__main {
  padding: 24px 32px;
  background: var(--admin-background);
}
</style>
