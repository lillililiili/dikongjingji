<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed } from 'vue'

import { PRO_SERVICE_CATEGORY_ICONS, PRO_SERVICE_TYPE_INFO } from '../../application/proservice-view'
import type { ProServiceCategoryView, ProServiceType } from '../../domain/proservice'

// The picture area of a service: the authorised photo when there is one, otherwise the scene's
// icon on the type's tint (SvcCatalog: no stand-in photos). The corner label names the scene.
const props = defineProps<{
  type: ProServiceType
  category: ProServiceCategoryView
  coverUrl: string | null
  title: string
  // The corner label; the detail page names the type (航拍服务) instead of the scene.
  label?: string
  iconSize?: number
  // The detail hero has a floating pill (SvcAerial) instead of the card's corner tab.
  pill?: boolean
}>()

const info = computed(() => PRO_SERVICE_TYPE_INFO[props.type])
const icon = computed(() => PRO_SERVICE_CATEGORY_ICONS[props.category.code] ?? 'gear')
</script>

<template>
  <view class="pro-cover" :style="{ background: coverUrl ? '#eef0f3' : info.tintColor }">
    <image
      v-if="coverUrl"
      class="pro-cover__image"
      :src="coverUrl"
      mode="aspectFill"
      lazy-load
      :aria-label="title"
    />
    <view v-else class="pro-cover__icon" :aria-label="`${category.label}场景`" role="img">
      <UniIcons :type="icon" :size="iconSize ?? 40" :color="info.badgeColor" />
    </view>
    <text :class="['pro-cover__label', { 'pro-cover__label--pill': pill }]" :style="{ background: info.badgeColor }">{{ label ?? category.label }}</text>
  </view>
</template>

<style scoped>
.pro-cover {
  position: relative;
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.pro-cover__image {
  display: block;
  width: 100%;
  height: 100%;
}

.pro-cover__icon {
  display: flex;
  align-items: center;
  justify-content: center;
}

.pro-cover__label {
  position: absolute;
  top: 0;
  left: 0;
  padding: 1px 8px;
  border-radius: 0 0 8px 0;
  color: #ffffff;
  font-size: 11px;
  font-weight: 500;
  line-height: 17px;
  white-space: nowrap;
}

.pro-cover__label--pill {
  top: 12px;
  left: 16px;
  padding: 3px 10px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 18px;
}
</style>
