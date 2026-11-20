<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed } from 'vue'

import { describeDemandMeta, describeDemandQuoteSummary } from '../../application/demand-view'
import { resolveDemandPreviewCover } from '../../data/preview/demand-preview-covers'
import type { Demand } from '../../domain/demand'

// The demand at the top of the quotes and compare pages (Quote, PlanCompare boards). A demand has
// no picture of its own yet (attachments come with a later step): preview builds show the board's
// scene photo for its type, otherwise the document icon of the DemandList board's empty image.
const props = defineProps<{
  demand: Demand
  quoteCount: number
}>()

const summary = computed(() => describeDemandQuoteSummary(props.demand, props.quoteCount))
const meta = computed(() => describeDemandMeta(props.demand))
const picture = computed(() => resolveDemandPreviewCover(props.demand.type))
</script>

<template>
  <view class="demand-summary" data-testid="demand-summary">
    <view class="demand-summary__image">
      <image v-if="picture" class="demand-summary__photo" :src="picture" mode="aspectFill" data-testid="demand-summary-photo" />
      <UniIcons v-else type="compose" size="26" color="#91a0b4" />
    </view>
    <view class="demand-summary__body">
      <text class="demand-summary__title">{{ demand.title }}</text>
      <text v-if="meta" class="demand-summary__meta">{{ meta }}</text>
      <view class="demand-summary__line">
        <text :class="['demand-summary__count', { 'demand-summary__count--none': quoteCount === 0 }]">
          ● {{ summary.count }}
        </text>
        <text v-if="summary.deadline" class="demand-summary__deadline" data-testid="demand-summary-deadline">
          · {{ summary.deadline }}
        </text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.demand-summary {
  display: flex;
  padding: 12px;
  gap: 12px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.demand-summary__image {
  display: flex;
  width: 72px;
  height: 72px;
  flex: none;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 10px;
  background: #eef2f8;
}

.demand-summary__photo {
  display: block;
  width: 72px;
  height: 72px;
}

.demand-summary__body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.demand-summary__title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.demand-summary__meta {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.demand-summary__line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 17px;
}

.demand-summary__count {
  color: #11804a;
  white-space: nowrap;
}

.demand-summary__count--none {
  color: #ba470b;
}

.demand-summary__deadline {
  color: #627187;
  white-space: nowrap;
}
</style>
