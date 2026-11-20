<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'

import type { TradePartyRow } from './trade-parties'

// The 交易主体 card of the boards. `card` is the white card of PdFull, OrderGoods and OrderRental
// (16px title, a note on the right); `dashed` is the dashed box with a green shield of Checkout.
withDefaults(
  defineProps<{
    rows: TradePartyRow[]
    note?: string
    footnote?: string
    variant?: 'card' | 'dashed'
    testId?: string
  }>(),
  { note: undefined, footnote: undefined, variant: 'card', testId: 'trade-parties' },
)
</script>

<template>
  <view :class="['trade-parties', `trade-parties--${variant}`]" :data-testid="testId">
    <view class="trade-parties__heading">
      <LineIcon v-if="variant === 'dashed'" name="shield" :size="18" color="#159a70" :stroke-width="2" />
      <text class="trade-parties__title">交易主体</text>
      <text v-if="note" class="trade-parties__note">{{ note }}</text>
    </view>
    <view class="trade-parties__grid">
      <template v-for="row in rows" :key="row.label">
        <text class="trade-parties__label">{{ row.label }}</text>
        <text class="trade-parties__value" :data-testid="row.testId">{{ row.value }}</text>
      </template>
    </view>
    <text v-if="footnote" class="trade-parties__footnote">{{ footnote }}</text>
    <slot />
  </view>
</template>

<style scoped>
.trade-parties {
  display: flex;
  flex-direction: column;
  gap: 10px;
  color: #202c42;
}

.trade-parties--card {
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.trade-parties--dashed {
  padding: 12px 14px;
  gap: 8px;
  border: 1px dashed #c9d3e1;
  border-radius: 14px;
}

.trade-parties__heading {
  display: flex;
  align-items: center;
  gap: 6px;
}

.trade-parties__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.trade-parties--dashed .trade-parties__title {
  font-size: 15px;
  line-height: 21px;
}

.trade-parties__note {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.trade-parties__grid {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
  line-height: 18px;
}

.trade-parties--dashed .trade-parties__grid {
  gap: 6px 10px;
}

.trade-parties__label {
  color: #627187;
  white-space: nowrap;
}

.trade-parties__value {
  min-width: 0;
}

.trade-parties__footnote {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}
</style>
