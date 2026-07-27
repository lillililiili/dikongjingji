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
  height: clamp(38px, 10.1vw, 60px);
  align-items: center;
  padding:
    clamp(4px, 1.1vw, 7px)
    clamp(8px, 2.1vw, 13px);
  border: 1px solid #e7eef9;
  border-radius: clamp(11px, 2.8vw, 17px);
  background: linear-gradient(90deg, #f7faff, #fffdfb);
  box-sizing: border-box;
}

.guarantee-strip__heading {
  width: clamp(70px, 18.7vw, 112px);
  flex: 0 0 clamp(70px, 18.7vw, 112px);
  padding-right: clamp(8px, 2.1vw, 13px);
  border-right: 1px solid #e4e7ec;
  color: #191919;
  font-size: clamp(11px, 2.9vw, 17px);
  font-weight: 750;
  white-space: nowrap;
}

.guarantee-strip__items {
  display: grid;
  min-width: 0;
  padding-left: clamp(7px, 1.9vw, 11px);
  flex: 1;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: clamp(3px, 0.8vw, 5px);
}

.guarantee-strip__item {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: clamp(3px, 0.8vw, 5px);
}

.guarantee-strip__icon {
  display: block;
  width: clamp(24px, 6.4vw, 38px);
  height: clamp(24px, 6.4vw, 38px);
  flex: 0 0 clamp(24px, 6.4vw, 38px);
  border-radius: 50%;
  background: #eef5ff;
  line-height: clamp(24px, 6.4vw, 38px);
  text-align: center;
}

.guarantee-strip__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.guarantee-strip__title,
.guarantee-strip__description {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.guarantee-strip__title {
  color: #2c3036;
  font-size: clamp(8px, 2.1vw, 12px);
  font-weight: 700;
}

.guarantee-strip__description {
  margin-top: clamp(1px, 0.3vw, 2px);
  color: #969ba3;
  font-size: clamp(7px, 1.8vw, 11px);
}

@media (max-width: 359px) {
  .guarantee-strip__heading {
    width: 60px;
    flex-basis: 60px;
    font-size: 10px;
  }

  .guarantee-strip__icon {
    width: 21px;
    height: 21px;
    flex-basis: 21px;
    line-height: 21px;
  }
}
</style>
