<script setup lang="ts">
/**
 * 标准企业后台业务页容器：
 * 单层紧凑白底卡片（无多余外框套内框、无背景底纹与悬浮胶囊），顶部标题行与下方内容区以 1px 细线自然分隔。
 */
defineProps<{
  title: string
  description?: string
  traced?: boolean
}>()
</script>

<template>
  <section class="page-section">
    <header class="page-section__header">
      <div class="page-section__heading">
        <h1 class="page-section__title">{{ title }}</h1>
        <p v-if="description || traced" class="page-section__description">
          {{ description }}
          <span v-if="traced" class="page-section__trace">本页的修改都会记录操作人和时间。</span>
        </p>
      </div>
      <div v-if="$slots.actions" class="page-section__actions">
        <slot name="actions" />
      </div>
    </header>
    <div class="page-section__body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.page-section {
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #ffffff;
}

.page-section__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 15px 20px;
  border-bottom: 1px solid var(--admin-border-subtle);
}

/* 放不下时操作按钮换到下一行，标题和说明不被挤成一列字。 */
.page-section__heading {
  flex: 1 1 320px;
  min-width: 0;
}

.page-section__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--admin-heading);
  line-height: 1.4;
}

.page-section__description {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: #8c8c8c;
}

.page-section__trace {
  color: #595959;
}

.page-section__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  max-width: 100%;
}

.page-section__body {
  padding: 16px 20px 20px;
}
</style>
