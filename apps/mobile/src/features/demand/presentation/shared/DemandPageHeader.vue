<script setup lang="ts">
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'

// The head of every demand page (Demand, DemandList, Quote, PlanCompare boards): the shared board
// head with back, a left-aligned title, and whatever the page puts on the right; tabs go below.
defineProps<{
  title: string
  // 企业 · 组织名 on the quotes and compare pages.
  badge?: string | null
}>()

const emit = defineEmits<{
  back: []
}>()
</script>

<template>
  <AppPageHeader :title="title" test-id="demand" @back="emit('back')">
    <template #actions>
      <text v-if="badge" class="demand-header__badge" data-testid="demand-header-badge">{{ badge }}</text>
      <slot />
    </template>
    <template #bottom>
      <slot name="bottom" />
    </template>
  </AppPageHeader>
</template>

<style scoped>
.demand-header__badge {
  overflow: hidden;
  max-width: 180px;
  margin-right: 8px;
  padding: 3px 8px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
