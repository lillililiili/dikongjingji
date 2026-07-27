<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { InspectionPortalHero } from '../../../domain/inspection-portal-content'

defineProps<{
  hero: InspectionPortalHero
  showRequestAction: boolean
}>()

defineEmits<{
  request: []
}>()
</script>

<template>
  <view class="inspection-hero">
    <image
      class="inspection-hero__image"
      :src="hero.imageUrl"
      mode="aspectFill"
      lazy-load
      aria-label="无人机巡检工业设施"
    />
    <view class="inspection-hero__veil" />
    <view class="inspection-hero__content">
      <view class="inspection-hero__title-row">
        <text class="inspection-hero__title">{{ hero.title }}</text>
        <text class="inspection-hero__accent">{{ hero.accent }}</text>
      </view>
      <text class="inspection-hero__description">{{ hero.description }}</text>
      <view class="inspection-hero__highlights">
        <view
          v-for="highlight in hero.highlights"
          :key="highlight"
          class="inspection-hero__highlight"
        >
          <UniIcons
            class="inspection-hero__check"
            type="checkbox-filled"
            size="14"
            color="#19a861"
          />
          <text>{{ highlight }}</text>
        </view>
      </view>
      <button
        v-if="showRequestAction"
        class="inspection-hero__action"
        @click="$emit('request')"
      >
        发布巡检需求
        <UniIcons type="right" size="14" color="#ffffff" />
      </button>
    </view>
  </view>
</template>

<style scoped>
.inspection-hero {
  position: relative;
  height: clamp(132px, 35.1vw, 210px);
  overflow: hidden;
  border-radius: clamp(11px, 2.8vw, 17px);
  background: #dcefff;
}

.inspection-hero__image,
.inspection-hero__veil {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.inspection-hero__veil {
  background: linear-gradient(
    90deg,
    rgba(238, 248, 255, 0.97) 0%,
    rgba(238, 248, 255, 0.9) 33%,
    rgba(238, 248, 255, 0.2) 68%,
    rgba(238, 248, 255, 0.02) 100%
  );
}

.inspection-hero__content {
  position: relative;
  z-index: 1;
  width: 72%;
  padding:
    clamp(20px, 5.2vw, 31px)
    clamp(16px, 4.2vw, 25px)
    clamp(10px, 2.6vw, 16px);
  box-sizing: border-box;
}

.inspection-hero__title-row {
  display: flex;
  flex-wrap: nowrap;
  gap: clamp(8px, 2.1vw, 13px);
  align-items: baseline;
}

.inspection-hero__title,
.inspection-hero__accent {
  font-size: clamp(20px, 5.8vw, 34px);
  font-weight: 800;
  line-height: 1.15;
}

.inspection-hero__title {
  color: #111820;
}

.inspection-hero__accent {
  color: #ff5a00;
}

.inspection-hero__description {
  display: block;
  max-width: 330px;
  margin-top: clamp(6px, 1.6vw, 10px);
  color: #39434e;
  font-size: clamp(10.5px, 2.8vw, 16px);
  line-height: 1.35;
  white-space: nowrap;
}

.inspection-hero__highlights {
  display: flex;
  flex-wrap: wrap;
  gap:
    clamp(5px, 1.3vw, 8px)
    clamp(12px, 3.2vw, 19px);
  margin-top: clamp(8px, 2.1vw, 13px);
  white-space: nowrap;
}

.inspection-hero__highlight {
  display: flex;
  align-items: center;
  gap: clamp(4px, 1.1vw, 7px);
  color: #39434e;
  font-size: clamp(10px, 2.6vw, 15px);
}

.inspection-hero__check {
  flex: 0 0 auto;
}

.inspection-hero__action {
  display: inline-flex;
  min-height: clamp(34px, 9.1vw, 54px);
  margin-top: clamp(8px, 2.1vw, 13px);
  padding: 0 clamp(14px, 3.7vw, 22px);
  align-items: center;
  justify-content: center;
  gap: clamp(10px, 2.6vw, 16px);
  border: 0;
  border-radius: 999px;
  color: #ffffff;
  background: linear-gradient(135deg, #ff8a18, #ff5200);
  box-shadow: 0 5px 12px rgba(255, 82, 0, 0.2);
  font-size: clamp(13px, 3.4vw, 20px);
  font-weight: 700;
  line-height: 1;
}

.inspection-hero__action::after {
  border: 0;
}

@media (max-width: 359px) {
  .inspection-hero__content {
    width: 78%;
    padding-right: 8px;
    padding-left: 12px;
  }

  .inspection-hero__description {
    font-size: 9.5px;
  }
}
</style>
