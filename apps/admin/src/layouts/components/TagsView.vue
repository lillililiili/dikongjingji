<script setup lang="ts">
import { ArrowDown, Close, Refresh } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'

import { useLayoutStore, type VisitedTab } from '@/core/layout/layout-store'

const route = useRoute()
const router = useRouter()
const layout = useLayoutStore()

function isActive(tab: VisitedTab): boolean {
  return tab.path === route.path
}

function go(tab: VisitedTab): void {
  if (tab.fullPath !== route.fullPath) {
    void router.push(tab.fullPath)
  }
}

function closeSingle(tab: VisitedTab): void {
  const active = isActive(tab)
  const fallback = layout.closeTab(tab.path)
  if (active && fallback) {
    void router.push(fallback.fullPath)
  }
}

function handleCommand(command: 'refresh' | 'close-current' | 'close-others' | 'close-all'): void {
  if (command === 'refresh') {
    layout.triggerRefresh()
    return
  }
  if (command === 'close-current') {
    const current = layout.visitedTabs.find((tab) => tab.path === route.path)
    if (current && !current.affix) closeSingle(current)
    return
  }
  if (command === 'close-others') {
    layout.closeOtherTabs(route.path)
    return
  }
  if (command === 'close-all') {
    const home = layout.closeAllTabs()
    if (route.path !== home.path) void router.push(home.fullPath)
  }
}
</script>

<template>
  <div class="tags-bar" role="navigation" aria-label="已打开页面页签">
    <div class="tags-bar__scroll">
      <button
        v-for="tab in layout.visitedTabs"
        :key="tab.path"
        type="button"
        :class="['tags-item', { 'is-active': isActive(tab) }]"
        @click="go(tab)"
      >
        <span v-if="isActive(tab)" class="tags-item__dot" />
        <span class="tags-item__label">{{ tab.title }}</span>
        <span
          v-if="!tab.affix"
          class="tags-item__close"
          role="button"
          tabindex="0"
          :aria-label="`关闭 ${tab.title}`"
          @click.stop="closeSingle(tab)"
          @keydown.enter.stop="closeSingle(tab)"
        >
          <el-icon><Close /></el-icon>
        </span>
      </button>
    </div>

    <div class="tags-bar__actions">
      <button type="button" class="tags-bar__tool" title="刷新当前页" @click="handleCommand('refresh')">
        <el-icon><Refresh /></el-icon>
      </button>
      <el-dropdown trigger="click" @command="handleCommand">
        <button type="button" class="tags-bar__tool">
          <span>页签操作</span>
          <el-icon><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="refresh">刷新当前页面</el-dropdown-item>
            <el-dropdown-item :disabled="route.path === '/'" command="close-current">关闭当前页签</el-dropdown-item>
            <el-dropdown-item command="close-others">关闭其他页签</el-dropdown-item>
            <el-dropdown-item divided command="close-all">关闭全部页签</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<style scoped>
.tags-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  height: 34px;
  padding: 0 16px;
  background: #ffffff;
  border-bottom: 1px solid var(--admin-border);
}

.tags-bar__scroll {
  display: flex;
  align-items: center;
  gap: 5px;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.tags-bar__scroll::-webkit-scrollbar {
  display: none;
}

.tags-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  padding: 0 9px;
  border: 1px solid #d9d9d9;
  border-radius: 3px;
  background: #ffffff;
  color: #595959;
  font-size: 12px;
  font-family: inherit;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
}

.tags-item:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary-light-5);
}

.tags-item.is-active {
  border-color: var(--el-color-primary-light-5);
  background: var(--admin-brand-surface);
  color: var(--el-color-primary);
  font-weight: 500;
}

.tags-item__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-primary);
}

.tags-item__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  margin-right: -2px;
  border-radius: 2px;
  font-size: 10px;
}

.tags-item__close:hover {
  background: rgba(0, 0, 0, 0.08);
}

.tags-bar__actions {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}

.tags-bar__tool {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 24px;
  padding: 0 8px;
  border: 1px solid #d9d9d9;
  border-radius: 3px;
  background: #ffffff;
  color: #595959;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}

.tags-bar__tool:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}
</style>
