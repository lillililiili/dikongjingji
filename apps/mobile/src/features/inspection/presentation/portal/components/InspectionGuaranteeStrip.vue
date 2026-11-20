<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'

import type { InspectionPortalGuarantee } from '../../../domain/inspection-portal-content'

defineProps<{
  guarantees: readonly InspectionPortalGuarantee[]
}>()
</script>

<template>
  <view v-if="guarantees.length" class="guarantee-strip" data-testid="portal-guarantees">
    <view class="guarantee-strip__heading">
      <LineIcon name="shield" :size="16" :stroke-width="2" color="#159a70" />
      <text>巡检保障</text>
    </view>
    <view
      v-for="item in guarantees"
      :key="item.code"
      class="guarantee-strip__item"
      :aria-label="item.description ? `${item.title}，${item.description}` : item.title"
    >
      <LineIcon name="check" :size="12" :stroke-width="3" color="#159a70" />
      <text>{{ item.title }}</text>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board HomeSvc (390 wide). */
.guarantee-strip {
  display: flex;
  min-height: 34px;
  padding: 4px 12px;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 2px 10px;
  border-radius: 10px;
  background: #eefaf3;
  box-sizing: border-box;
}

.guarantee-strip__heading {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #11804a;
  font-size: 13px;
  font-weight: 700;
  line-height: 18px;
  white-space: nowrap;
}

.guarantee-strip__item {
  display: flex;
  align-items: center;
  gap: 3px;
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}
</style>
