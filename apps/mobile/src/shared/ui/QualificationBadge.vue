<script setup lang="ts">
import { QUALIFICATION_REVIEWED_TEXT } from './qualification'

// R6-4: the boards' green “资质已审核” tag (QuoteGoods, RentalQuote, OrderRental, OrderList):
// #e2f6ed on #11804a, 6px corners, 1px 6px padding. The pages show it only when the server's
// qualificationReviewed is true. Most boards use 11px; a few (InspDetail, PlanConfirm) use 12px.
withDefaults(defineProps<{ size?: 11 | 12 }>(), { size: 11 })
</script>

<template>
  <text :class="['qualification-badge', `qualification-badge--${size}`]" data-testid="qualification-badge">
    {{ QUALIFICATION_REVIEWED_TEXT }}
  </text>
</template>

<style scoped>
.qualification-badge {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  line-height: 16px;
  white-space: nowrap;
}

.qualification-badge--11 { font-size: 11px; }
.qualification-badge--12 { font-size: 12px; }
</style>
