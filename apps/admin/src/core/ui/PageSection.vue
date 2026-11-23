<script setup lang="ts">
/**
 * 页面标题区：标题、一句说明、右侧操作按钮，下面是一张白底卡片（照原型 Ops 看板：白底、#e5ebf3 细边、12px 圆角）。
 * traced 为 true 时在说明后面提示“本页的修改都会记录操作人和时间”。
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
      <div class="page-section__actions">
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
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-section__header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.page-section__title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--admin-text);
}

.page-section__description {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.page-section__trace {
  color: var(--admin-brand-text);
}

.page-section__actions {
  display: flex;
  gap: 8px;
}

.page-section__body {
  padding: 20px;
  border: 1px solid var(--admin-border);
  border-radius: 12px;
  background: #fff;
}
</style>
