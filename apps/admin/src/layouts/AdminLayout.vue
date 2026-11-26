<script setup lang="ts">
import {
  Briefcase,
  Expand,
  Fold,
  FullScreen,
  HomeFilled,
  Monitor,
  Operation,
  Search,
  Service,
  SwitchButton,
} from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { roleLabel } from '@/core/auth/roles'
import { useSessionStore } from '@/core/auth/session'
import { useLayoutStore } from '@/core/layout/layout-store'
import { buildMenu, type MenuGroupKey } from '@/core/navigation/module'
import { confirmAction } from '@/core/ui/feedback'
import { useNarrowScreen } from '@/core/ui/use-narrow-screen'
import { ADMIN_MODULES } from '@/modules'

import MenuSearchDialog from './components/MenuSearchDialog.vue'
import TagsView from './components/TagsView.vue'
import ThemeSettingsDrawer from './components/ThemeSettingsDrawer.vue'

/**
 * 标准企业级中后台主框架（若依 RuoYi-Vue3 / Ant Design Pro 经典布局）：
 * 左侧 #001529 深藏青可折叠菜单树 + 48px 白色顶栏 + 34px 矩形页签栏 + 紧凑灰底内容区。
 */
const session = useSessionStore()
const layout = useLayoutStore()
const route = useRoute()
const router = useRouter()

const narrow = useNarrowScreen()
const sidebarCollapsed = computed(() => narrow.value || layout.sidebarCollapsed)

const groups = computed(() => buildMenu(ADMIN_MODULES, session.roles))
const openGroupKeys = computed(() => groups.value.map((group) => group.key))

const GROUP_ICONS: Record<MenuGroupKey, unknown> = {
  PLATFORM: Monitor,
  SUPPORT: Service,
  WORKBENCH: Briefcase,
}

const active = computed(() => {
  const paths = groups.value.flatMap((group) => group.items.map((item) => item.path))
  return (
    paths
      .filter((path) => route.path === path || route.path.startsWith(`${path}/`))
      .sort((a, b) => b.length - a.length)[0] ?? route.path
  )
})

const breadcrumbs = computed(() => {
  const items: Array<{ title: string, to?: string }> = []
  if (route.path === '/') return items
  const matchedGroup = groups.value.find((group) =>
    group.items.some((item) => route.path === item.path || route.path.startsWith(`${item.path}/`)),
  )
  if (matchedGroup) {
    items.push({ title: matchedGroup.title })
    const matchedItem = matchedGroup.items
      .filter((item) => route.path === item.path || route.path.startsWith(`${item.path}/`))
      .sort((a, b) => b.path.length - a.path.length)[0]
    if (matchedItem && matchedItem.path !== route.path) {
      items.push({ title: matchedItem.title, to: matchedItem.path })
    }
  }
  if (typeof route.meta.title === 'string' && route.meta.title) {
    items.push({ title: route.meta.title })
  }
  return items
})

const userInitial = computed(() => {
  const name = session.account?.displayName?.trim()
  return name ? name.slice(0, 1).toUpperCase() : 'U'
})

watch(
  () => route.fullPath,
  () => {
    layout.visitRoute(route)
  },
  { immediate: true },
)

function toggleFullscreen(): void {
  if (typeof document === 'undefined') return
  if (!document.fullscreenElement) {
    void document.documentElement.requestFullscreen?.()
  } else {
    void document.exitFullscreen?.()
  }
}

function handleGlobalKeydown(event: KeyboardEvent): void {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    layout.searchOpen = !layout.searchOpen
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
})

async function logout(): Promise<void> {
  if (!(await confirmAction({ title: '退出登录', message: '确定退出运营端？', confirmText: '退出' }))) return
  layout.closeAllTabs()
  session.logout()
  await router.replace('/login')
}
</script>

<template>
  <el-container :class="['layout', `layout--${layout.sidebarTheme}`, { 'is-collapsed': sidebarCollapsed }]">
    <el-aside class="layout__aside" :width="sidebarCollapsed ? '64px' : '216px'">
      <router-link to="/" class="layout__brand" :title="sidebarCollapsed ? '城市运营工作台' : undefined">
        <span class="layout__logo" aria-hidden="true">
          <svg viewBox="0 0 28 28" fill="none">
            <rect width="28" height="28" rx="5" fill="var(--el-color-primary)" />
            <path d="M7 15.2L20.5 8.5L16.2 20.5L13 15.8L7 15.2Z" fill="#ffffff" />
          </svg>
        </span>
        <span v-show="!sidebarCollapsed" class="layout__brand-title">城市运营工作台</span>
      </router-link>

      <div class="layout__menu-wrap">
        <el-menu
          :default-active="active"
          :default-openeds="openGroupKeys"
          :collapse="sidebarCollapsed"
          :collapse-transition="false"
          router
          class="layout__menu"
        >
          <el-menu-item index="/" aria-label="首页">
            <el-icon><HomeFilled /></el-icon>
            <template #title><span>首页</span></template>
          </el-menu-item>

          <el-sub-menu v-for="group in groups" :key="group.key" :index="group.key" :aria-label="group.title">
            <template #title>
              <el-icon><component :is="GROUP_ICONS[group.key]" /></el-icon>
              <span>{{ group.title }}</span>
            </template>
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path" :aria-label="item.title">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title><span>{{ item.title }}</span></template>
            </el-menu-item>
          </el-sub-menu>
        </el-menu>
      </div>
    </el-aside>

    <el-container class="layout__workspace" direction="vertical">
      <el-header class="layout__header" height="48px">
        <div class="layout__header-left">
          <button
            v-if="!narrow"
            type="button"
            class="layout__hamburger"
            :title="sidebarCollapsed ? '展开菜单' : '折叠菜单'"
            @click="layout.toggleSidebar()"
          >
            <el-icon :size="18">
              <Expand v-if="sidebarCollapsed" />
              <Fold v-else />
            </el-icon>
          </button>

          <el-breadcrumb v-if="layout.showBreadcrumb" separator="/" class="layout__breadcrumb">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item
              v-for="(crumb, idx) in breadcrumbs"
              :key="`${crumb.title}-${idx}`"
              :to="crumb.to ? { path: crumb.to } : undefined"
            >
              {{ crumb.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
          <span v-else class="layout__page">{{ route.meta.title ?? '' }}</span>
        </div>

        <div class="layout__header-right">
          <button type="button" class="layout__icon-btn" title="菜单搜索 (Ctrl+K)" @click="layout.searchOpen = true">
            <el-icon :size="16"><Search /></el-icon>
          </button>

          <button type="button" class="layout__icon-btn" title="全屏切换" @click="toggleFullscreen">
            <el-icon :size="16"><FullScreen /></el-icon>
          </button>

          <button type="button" class="layout__icon-btn" title="布局与主题设置" @click="layout.drawerOpen = true">
            <el-icon :size="16"><Operation /></el-icon>
          </button>

          <span class="layout__divider" />

          <div class="layout__account">
            <span class="layout__avatar" aria-hidden="true">{{ userInitial }}</span>
            <span class="layout__name">{{ session.account?.displayName }}</span>
            <span v-if="session.account?.organizationName" class="layout__org">
              （{{ session.account.organizationName }}）
            </span>
            <div class="layout__roles">
              <el-tag v-for="role in session.roles" :key="role" size="small" type="info" effect="plain">
                {{ roleLabel(role) }}
              </el-tag>
            </div>
            <el-button link class="layout__logout" @click="logout">
              <el-icon><SwitchButton /></el-icon>
              <span>退出</span>
            </el-button>
          </div>
        </div>
      </el-header>

      <TagsView v-if="layout.showTagsView" />

      <el-main class="layout__main">
        <div class="layout__content">
          <router-view :key="`${route.path}:${layout.refreshSeed}`" />
        </div>
      </el-main>
    </el-container>

    <MenuSearchDialog :open="layout.searchOpen" :groups="groups" @close="layout.searchOpen = false" />
    <ThemeSettingsDrawer :open="layout.drawerOpen" @close="layout.drawerOpen = false" />
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
  background: var(--admin-background);
}

.layout__aside {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  height: 100vh;
  transition: width 0.18s ease;
  overflow: hidden;
  z-index: 10;
}

.layout--dark .layout__aside {
  background: #001529;
  box-shadow: 2px 0 6px rgba(0, 21, 41, 0.16);
}

.layout--light .layout__aside {
  background: #ffffff;
  border-right: 1px solid var(--admin-border);
}

.layout__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 48px;
  padding: 0 16px;
  text-decoration: none;
  flex-shrink: 0;
  overflow: hidden;
}

.layout--dark .layout__brand {
  background: #002140;
}

.layout--light .layout__brand {
  border-bottom: 1px solid var(--admin-border-subtle);
}

.layout__logo {
  display: inline-flex;
  width: 26px;
  height: 26px;
  flex-shrink: 0;
}

.layout__logo svg {
  width: 100%;
  height: 100%;
}

.layout__brand-title {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  letter-spacing: 0.02em;
}

.layout--dark .layout__brand-title {
  color: #ffffff;
}

.layout--light .layout__brand-title {
  color: #1f2329;
}

.layout__menu-wrap {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 6px 0 14px;
}

.layout__menu {
  border-right: 0 !important;
  --el-menu-item-height: 40px;
  --el-menu-sub-item-height: 38px;
}

/* 经典 #001529 暗色侧栏样式 */
.layout--dark .layout__menu {
  --el-menu-bg-color: #001529;
  --el-menu-text-color: rgba(255, 255, 255, 0.68);
  --el-menu-hover-bg-color: transparent;
  --el-menu-active-color: #ffffff;
}

.layout--dark .layout__menu :deep(.el-sub-menu__title) {
  color: rgba(255, 255, 255, 0.85);
  font-size: 13.5px;
}

.layout--dark .layout__menu :deep(.el-sub-menu__title:hover) {
  color: #ffffff;
  background-color: rgba(255, 255, 255, 0.05);
}

.layout--dark .layout__menu :deep(.el-sub-menu .el-menu) {
  background-color: #000c17;
}

.layout--dark .layout__menu :deep(.el-menu-item) {
  margin: 2px 8px;
  border-radius: 4px;
  font-size: 13.5px;
  color: rgba(255, 255, 255, 0.68);
}

.layout--dark .layout__menu :deep(.el-menu-item:hover) {
  color: #ffffff;
  background-color: rgba(255, 255, 255, 0.08);
}

.layout--dark .layout__menu :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary);
  color: #ffffff;
  font-weight: 500;
}

/* 清爽浅色侧栏样式 */
.layout--light .layout__menu {
  --el-menu-bg-color: #ffffff;
  --el-menu-text-color: #4e5969;
  --el-menu-hover-bg-color: #f5f7fa;
}

.layout--light .layout__menu :deep(.el-sub-menu__title) {
  color: #1f2329;
  font-weight: 500;
  font-size: 13.5px;
}

.layout--light .layout__menu :deep(.el-menu-item) {
  margin: 2px 8px;
  border-radius: 4px;
  font-size: 13.5px;
}

.layout--light .layout__menu :deep(.el-menu-item.is-active) {
  background: var(--admin-brand-surface);
  color: var(--el-color-primary);
  font-weight: 500;
}

.is-collapsed .layout__menu :deep(.el-menu-item) {
  margin: 4px 6px;
}

.layout__workspace {
  min-width: 0;
  flex: 1;
}

.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 16px 0 8px;
  border-bottom: 1px solid var(--admin-border);
  background: #ffffff;
}

.layout__header-left {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.layout__hamburger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #595959;
  cursor: pointer;
}

.layout__hamburger:hover {
  background: #f5f7fa;
  color: var(--el-color-primary);
}

.layout__breadcrumb {
  font-size: 13px;
}

.layout__page {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 14px;
  font-weight: 500;
  color: var(--admin-text);
}

.layout__header-right {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.layout__icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #595959;
  cursor: pointer;
}

.layout__icon-btn:hover {
  background: #f5f7fa;
  color: var(--el-color-primary);
}

.layout__divider {
  width: 1px;
  height: 16px;
  background: var(--admin-border);
  margin: 0 8px;
}

.layout__account {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.layout__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.layout__name {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 13px;
  font-weight: 500;
  color: #262626;
}

.layout__org {
  font-size: 12px;
  color: #8c8c8c;
}

.layout__roles {
  display: flex;
  align-items: center;
  gap: 4px;
}

.layout__logout {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: 4px;
  color: #595959;
}

.layout__logout:hover {
  color: var(--el-color-danger);
}

.layout__main {
  padding: 16px;
  background: var(--admin-background);
}

.layout__content {
  max-width: 1680px;
  margin: 0 auto;
}

@media (max-width: 1024px) {
  .layout__roles,
  .layout__org {
    display: none;
  }
}
@media (max-width: 640px) {
  .layout__header {
    gap: 6px;
    padding: 0 8px;
  }

  .layout__breadcrumb {
    overflow: hidden;
    white-space: nowrap;
  }

  .layout__breadcrumb :deep(.el-breadcrumb__item:not(:last-child)) {
    display: none;
  }

  .layout__breadcrumb :deep(.el-breadcrumb__inner) {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .layout__name,
  .layout__divider {
    display: none;
  }

  .layout__main {
    padding: 8px;
  }
}
</style>
