<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

// The light page head of the 2026-11-14 boards (Cashier, PayResult, OrderList): white with the
// theme's soft gradient, a 44px back button and an 18px title, left or centred.
defineProps<{
  title: string
  centered?: boolean
  testId: string
}>()

const emit = defineEmits<{ back: [] }>()
</script>

<template>
  <view :class="['trade-head', { 'trade-head--centered': centered }]">
    <button class="trade-head__back" :data-testid="`${testId}-back`" aria-label="返回" @click="emit('back')">
      <UniIcons type="left" size="22" color="#202c42" />
    </button>
    <text class="trade-head__title">{{ title }}</text>
    <view v-if="centered" class="trade-head__spacer" />
  </view>
</template>

<style scoped>
.trade-head {
  display: flex;
  min-height: calc(60px + var(--status-bar-height));
  padding: var(--status-bar-height) 8px 0;
  align-items: center;
  gap: 4px;
  color: #202c42;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
  box-sizing: border-box;
}

.trade-head__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  margin: 0;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
}

.trade-head__back::after {
  border: 0;
}

.trade-head__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.trade-head--centered .trade-head__title {
  flex: 1;
  text-align: center;
}

.trade-head__spacer {
  width: 44px;
  height: 44px;
  flex: none;
}
</style>
