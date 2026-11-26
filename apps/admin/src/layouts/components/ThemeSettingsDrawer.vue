<script setup lang="ts">
import { RefreshLeft } from '@element-plus/icons-vue'

import { THEME_COLOR_PRESETS, useLayoutStore } from '@/core/layout/layout-store'

defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

const layout = useLayoutStore()
</script>

<template>
  <el-drawer
    :model-value="open"
    title="系统布局与主题配置"
    size="310px"
    append-to-body
    @close="emit('close')"
  >
    <div class="theme-drawer">
      <section class="theme-drawer__group">
        <h3 class="theme-drawer__title">整体侧栏风格</h3>
        <div class="style-cards">
          <button
            type="button"
            :class="['style-card', { 'is-active': layout.sidebarTheme === 'dark' }]"
            @click="layout.sidebarTheme = 'dark'"
          >
            <span class="style-card__preview style-card__preview--dark">
              <i class="style-card__aside" />
              <i class="style-card__top" />
            </span>
            <span class="style-card__label">若依经典暗色</span>
          </button>

          <button
            type="button"
            :class="['style-card', { 'is-active': layout.sidebarTheme === 'light' }]"
            @click="layout.sidebarTheme = 'light'"
          >
            <span class="style-card__preview style-card__preview--light">
              <i class="style-card__aside" />
              <i class="style-card__top" />
            </span>
            <span class="style-card__label">清爽极简浅色</span>
          </button>
        </div>
      </section>

      <section class="theme-drawer__group">
        <h3 class="theme-drawer__title">主色调方案</h3>
        <div class="color-list">
          <button
            v-for="preset in THEME_COLOR_PRESETS"
            :key="preset.key"
            type="button"
            :class="['color-chip', { 'is-active': layout.primaryColor === preset.value }]"
            @click="layout.primaryColor = preset.value"
          >
            <span class="color-chip__swatch" :style="{ backgroundColor: preset.value }" />
            <span>{{ preset.label }}</span>
          </button>
        </div>
      </section>

      <section class="theme-drawer__group">
        <h3 class="theme-drawer__title">界面显示项</h3>
        <div class="switch-row">
          <span>开启多页签栏（Tags-View）</span>
          <el-switch v-model="layout.showTagsView" />
        </div>
        <div class="switch-row">
          <span>开启顶部面包屑导航</span>
          <el-switch v-model="layout.showBreadcrumb" />
        </div>
        <div class="switch-row">
          <span>折叠左侧导航菜单</span>
          <el-switch v-model="layout.sidebarCollapsed" />
        </div>
      </section>

      <div class="theme-drawer__footer">
        <el-button :icon="RefreshLeft" style="width: 100%" @click="layout.resetThemeDefaults()">
          恢复默认布局配置
        </el-button>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.theme-drawer {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.theme-drawer__title {
  margin: 0 0 12px;
  font-size: 13.5px;
  font-weight: 600;
  color: #334155;
}

.style-cards {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.style-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 10px;
  border: 1.5px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}

.style-card.is-active {
  border-color: var(--el-color-primary);
  background: var(--admin-brand-surface);
}

.style-card__preview {
  position: relative;
  display: block;
  width: 100%;
  height: 54px;
  border-radius: 6px;
  background: #f0f3f8;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
}

.style-card__aside {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 30%;
}

.style-card__top {
  position: absolute;
  left: 30%;
  right: 0;
  top: 0;
  height: 24%;
  background: #fff;
}

.style-card__preview--dark .style-card__aside {
  background: #001529;
}

.style-card__preview--light .style-card__aside {
  background: #fff;
  border-right: 1px solid #e2e8f0;
}

.style-card__label {
  font-size: 12px;
  font-weight: 500;
  color: #334155;
}

.color-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.color-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  font-size: 12.5px;
  font-family: inherit;
  color: #334155;
  cursor: pointer;
  transition: all 0.15s ease;
}

.color-chip.is-active {
  border-color: var(--el-color-primary);
  background: var(--admin-brand-surface);
  font-weight: 600;
  color: var(--admin-brand-text);
}

.color-chip__swatch {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  flex-shrink: 0;
}

.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 0;
  border-bottom: 1px solid #f1f5f9;
  font-size: 13px;
  color: #334155;
}

.theme-drawer__footer {
  padding-top: 8px;
}
</style>
