<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'

// The board's “□ 对比” checkbox (accent orange, 16px box, 44px tall). Drawn instead of a native
// checkbox so a blocked one can still explain itself when tapped.
defineProps<{
  checked: boolean
  blocked: boolean
  label: string
  fontSize?: number
}>()
const emit = defineEmits<{ toggle: [] }>()
</script>

<template>
  <view
    class="compare-check"
    role="checkbox"
    :aria-checked="checked"
    :aria-disabled="blocked"
    :aria-label="label"
    data-testid="compare-check"
    :data-checked="checked ? 'true' : 'false'"
    :style="{ color: blocked ? '#91a0b4' : '#536178', fontSize: `${fontSize ?? 12}px` }"
    @click.stop="emit('toggle')"
  >
    <view :class="['compare-check__box', { 'compare-check__box--on': checked, 'compare-check__box--off': blocked }]">
      <LineIcon v-if="checked" name="check" :size="12" :stroke-width="3" color="#ffffff" />
    </view>
    <text>对比</text>
  </view>
</template>

<style scoped>
.compare-check {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.compare-check__box {
  display: flex;
  width: 16px;
  height: 16px;
  align-items: center;
  justify-content: center;
  border: 1px solid #767676;
  border-radius: 3px;
  background: #ffffff;
  box-sizing: border-box;
}

.compare-check__box--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.compare-check__box--off {
  border-color: #c9d3e1;
  background: #f5f7fc;
}
</style>
