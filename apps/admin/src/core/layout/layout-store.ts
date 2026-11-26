import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

export type SidebarTheme = 'dark' | 'light'

export interface ThemeColorPreset {
  key: string
  label: string
  value: string
  light3: string
  light5: string
  light7: string
  light8: string
  light9: string
  dark2: string
  surface: string
  brandText: string
}

export const THEME_COLOR_PRESETS: readonly ThemeColorPreset[] = [
  {
    key: 'blue',
    label: '经典企业蓝',
    value: '#1677ff',
    light3: '#4096ff',
    light5: '#69b1ff',
    light7: '#bae0ff',
    light8: '#d6ecff',
    light9: '#e6f4ff',
    dark2: '#0958d9',
    surface: '#e6f4ff',
    brandText: '#0958d9',
  },
  {
    key: 'royal',
    label: '政务深空蓝',
    value: '#2f54eb',
    light3: '#597ef7',
    light5: '#85a5ff',
    light7: '#adc6ff',
    light8: '#d6e4ff',
    light9: '#f0f5ff',
    dark2: '#1d39c4',
    surface: '#f0f5ff',
    brandText: '#1d39c4',
  },
  {
    key: 'cyan',
    label: '工业青蓝',
    value: '#0e7490',
    light3: '#3898b0',
    light5: '#67b7ca',
    light7: '#a5dbe8',
    light8: '#ccebf3',
    light9: '#ecfeff',
    dark2: '#155e75',
    surface: '#ecfeff',
    brandText: '#155e75',
  },
  {
    key: 'green',
    label: '沉稳森绿',
    value: '#009a29',
    light3: '#33b152',
    light5: '#66c67c',
    light7: '#99dba8',
    light8: '#ccecd3',
    light9: '#e8ffea',
    dark2: '#007a21',
    surface: '#e8ffea',
    brandText: '#007a21',
  },
]

export interface VisitedTab {
  path: string
  fullPath: string
  title: string
  affix?: boolean
}

interface StoredUiPrefs {
  sidebarCollapsed?: boolean
  sidebarTheme?: SidebarTheme
  primaryColor?: string
  showTagsView?: boolean
  showBreadcrumb?: boolean
}

const UI_STORAGE_KEY = 'lowaltitude.admin.ui-prefs.v2'

const HOME_TAB: VisitedTab = {
  path: '/',
  fullPath: '/',
  title: '首页',
  affix: true,
}

function readStoredPrefs(): StoredUiPrefs {
  if (typeof window === 'undefined') return {}
  try {
    const raw = window.localStorage.getItem(UI_STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as StoredUiPrefs
    return typeof parsed === 'object' && parsed !== null ? parsed : {}
  } catch {
    return {}
  }
}

function writeStoredPrefs(prefs: StoredUiPrefs): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(UI_STORAGE_KEY, JSON.stringify(prefs))
  } catch {
    // 忽略受限存储错误
  }
}

export function applyThemePalette(colorValue: string): void {
  if (typeof document === 'undefined') return
  const preset = THEME_COLOR_PRESETS.find((item) => item.value === colorValue) ?? THEME_COLOR_PRESETS[0]!
  const root = document.documentElement
  root.style.setProperty('--el-color-primary', preset.value)
  root.style.setProperty('--el-color-primary-light-3', preset.light3)
  root.style.setProperty('--el-color-primary-light-5', preset.light5)
  root.style.setProperty('--el-color-primary-light-7', preset.light7)
  root.style.setProperty('--el-color-primary-light-8', preset.light8)
  root.style.setProperty('--el-color-primary-light-9', preset.light9)
  root.style.setProperty('--el-color-primary-dark-2', preset.dark2)
  root.style.setProperty('--admin-brand-surface', preset.surface)
  root.style.setProperty('--admin-brand-text', preset.brandText)
}

export const useLayoutStore = defineStore('admin-layout', () => {
  const initial = readStoredPrefs()

  const sidebarCollapsed = ref(Boolean(initial.sidebarCollapsed))
  const sidebarTheme = ref<SidebarTheme>(initial.sidebarTheme === 'light' ? 'light' : 'dark')
  const primaryColor = ref<string>(
    THEME_COLOR_PRESETS.some((item) => item.value === initial.primaryColor)
      ? initial.primaryColor!
      : THEME_COLOR_PRESETS[0]!.value,
  )
  const showTagsView = ref(initial.showTagsView ?? true)
  const showBreadcrumb = ref(initial.showBreadcrumb ?? true)
  const drawerOpen = ref(false)
  const searchOpen = ref(false)
  const refreshSeed = ref(0)
  const visitedTabs = ref<VisitedTab[]>([{ ...HOME_TAB }])

  applyThemePalette(primaryColor.value)

  watch(
    [sidebarCollapsed, sidebarTheme, primaryColor, showTagsView, showBreadcrumb],
    ([collapsed, theme, color, tags, breadcrumb]) => {
      applyThemePalette(color)
      writeStoredPrefs({
        sidebarCollapsed: collapsed,
        sidebarTheme: theme,
        primaryColor: color,
        showTagsView: tags,
        showBreadcrumb: breadcrumb,
      })
    },
    { flush: 'sync' },
  )

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function resetThemeDefaults(): void {
    sidebarCollapsed.value = false
    sidebarTheme.value = 'dark'
    primaryColor.value = THEME_COLOR_PRESETS[0]!.value
    showTagsView.value = true
    showBreadcrumb.value = true
  }

  function visitRoute(route: RouteLocationNormalizedLoaded): void {
    if (route.meta.public || route.name === 'forbidden' || route.name === 'not-found') return
    const title = typeof route.meta.title === 'string' && route.meta.title.trim() ? route.meta.title : '页面'
    const path = route.path || '/'
    const existing = visitedTabs.value.find((tab) => tab.path === path)
    if (existing) {
      existing.fullPath = route.fullPath
      if (!existing.affix) existing.title = title
      return
    }
    visitedTabs.value.push({
      path,
      fullPath: route.fullPath,
      title,
      affix: path === '/',
    })
    if (visitedTabs.value.length > 18) {
      const removableIndex = visitedTabs.value.findIndex((tab) => !tab.affix)
      if (removableIndex >= 0) visitedTabs.value.splice(removableIndex, 1)
    }
  }

  function closeTab(path: string): VisitedTab | null {
    const index = visitedTabs.value.findIndex((tab) => tab.path === path)
    if (index < 0 || visitedTabs.value[index]?.affix) return null
    visitedTabs.value.splice(index, 1)
    return visitedTabs.value[Math.min(index, visitedTabs.value.length - 1)] ?? HOME_TAB
  }

  function closeOtherTabs(activePath: string): void {
    visitedTabs.value = visitedTabs.value.filter((tab) => tab.affix || tab.path === activePath)
  }

  function closeAllTabs(): VisitedTab {
    visitedTabs.value = [{ ...HOME_TAB }]
    return HOME_TAB
  }

  function triggerRefresh(): void {
    refreshSeed.value += 1
  }

  return {
    sidebarCollapsed,
    sidebarTheme,
    primaryColor,
    showTagsView,
    showBreadcrumb,
    drawerOpen,
    searchOpen,
    refreshSeed,
    visitedTabs,
    toggleSidebar,
    resetThemeDefaults,
    visitRoute,
    closeTab,
    closeOtherTabs,
    closeAllTabs,
    triggerRefresh,
  }
})
