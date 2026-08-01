<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { InspectionPortalGuarantee } from '../../../domain/inspection-portal-content'

defineProps<{
  guarantees: readonly InspectionPortalGuarantee[]
}>()

const GUARANTEE_ICONS: Record<string, { type: string; color: string }> = {
  QUALIFICATION: { type: 'auth-filled', color: '#3f87f5' },
  EQUIPMENT: { type: 'videocam-filled', color: '#3988f5' },
  REPORT: { type: 'compose', color: '#28b85f' },
  AFTER_SALES: { type: 'headphones', color: '#ff7418' },
}
</script>

<template>
  <view v-if="guarantees.length" class="guarantee-strip">
    <text class="guarantee-strip__heading">巡检服务保障</text>
    <view class="guarantee-strip__items">
      <view
        v-for="item in guarantees"
        :key="item.code"
        class="guarantee-strip__item"
      >
        <view
          class="guarantee-strip__icon"
        >
          <UniIcons
            :type="GUARANTEE_ICONS[item.code]?.type ?? 'checkbox-filled'"
            size="17"
            :color="GUARANTEE_ICONS[item.code]?.color ?? '#3f87f5'"
          />
        </view>
        <view class="guarantee-strip__copy">
          <text class="guarantee-strip__title">{{ item.title }}</text>
          <text class="guarantee-strip__description">{{ item.description }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.guarantee-strip {
  display: flex;
  flex-direction: column;
  padding: 12px;
  border: 1px solid #e7eef9;
  border-radius: clamp(11px, 2.8vw, 17px);
  background: linear-gradient(90deg, #f7faff, #fffdfb);
  box-sizing: border-box;
  gap: 10px;
}

.guarantee-strip__heading {
  color: #191919;
  font-size: 16px;
  font-weight: 750;
  white-space: nowrap;
}

.guarantee-strip__items {
  display: grid;
  min-width: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
}

.guarantee-strip__item {
  display: flex;
  min-width: 0;
  align-items: center;
  padding: 8px;
  border-radius: 12px;
  background: rgb(255 255 255 / 76%);
  gap: 8px;
}

.guarantee-strip__icon {
  display: block;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 50%;
  background: #eef5ff;
  line-height: 34px;
  text-align: center;
}

.guarantee-strip__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.guarantee-strip__title {
  color: #2c3036;
  font-size: 13px;
  font-weight: 700;
  line-height: 18px;
}

.guarantee-strip__description {
  margin-top: 2px;
  color: #66707c;
  font-size: 11px;
  line-height: 16px;
}

@media (max-width: 359px) {
  .guarantee-strip {
    padding-right: 8px;
    padding-left: 8px;
  }

  .guarantee-strip__heading {
    font-size: 15px;
  }

  .guarantee-strip__icon {
    width: 30px;
    height: 30px;
    flex-basis: 30px;
    line-height: 30px;
  }
}
</style>
