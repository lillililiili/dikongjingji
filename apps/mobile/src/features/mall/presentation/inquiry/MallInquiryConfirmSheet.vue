<script setup lang="ts">
// The bottom confirmation of the quotes page (QuoteGoods): 选择此报价 and 关闭询价.
defineProps<{
  title: string
  text: string
  keepLabel: string
  goLabel: string
  busy?: boolean
  // The record being confirmed, shown on a grey panel.
  rows?: Array<{ key: string; label: string; value: string }>
}>()

const emit = defineEmits<{
  keep: []
  go: []
}>()
</script>

<template>
  <!-- Below the uni picker (999) like every sheet of the project. -->
  <view class="inquiry-confirm" data-testid="inquiry-confirm">
    <view class="inquiry-confirm__mask" aria-label="关闭" @click="emit('keep')" />
    <view class="inquiry-confirm__sheet" role="dialog" aria-modal="true" :aria-label="title">
      <text class="inquiry-confirm__title">{{ title }}</text>
      <text class="inquiry-confirm__text">{{ text }}</text>
      <view v-if="rows && rows.length" class="inquiry-confirm__rows" data-testid="inquiry-confirm-rows">
        <template v-for="row in rows" :key="row.key">
          <text class="inquiry-confirm__label">{{ row.label }}</text>
          <text class="inquiry-confirm__value">{{ row.value }}</text>
        </template>
      </view>
      <view class="inquiry-confirm__actions">
        <button class="inquiry-confirm__keep" data-testid="inquiry-confirm-keep" @click="emit('keep')">
          {{ keepLabel }}
        </button>
        <button
          class="inquiry-confirm__go"
          data-testid="inquiry-confirm-go"
          :disabled="busy"
          @click="!busy && emit('go')"
        >
          {{ busy ? '处理中…' : goLabel }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.inquiry-confirm {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
}

.inquiry-confirm__mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.inquiry-confirm__sheet {
  position: relative;
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 14px;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #ffffff;
  box-sizing: border-box;
}

.inquiry-confirm__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.inquiry-confirm__text {
  color: #536178;
  font-size: 14px;
  line-height: 20px;
}

.inquiry-confirm__rows {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 8px 10px;
  padding: 12px;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 18px;
}

.inquiry-confirm__label {
  color: #627187;
  white-space: nowrap;
}

.inquiry-confirm__value {
  min-width: 0;
  word-break: break-all;
}

.inquiry-confirm__actions {
  display: flex;
  gap: 10px;
}

button {
  margin: 0;
  padding: 0;
  font-family: inherit;
}

button::after {
  border: 0;
}

.inquiry-confirm__keep,
.inquiry-confirm__go {
  height: 46px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 46px;
}

.inquiry-confirm__keep {
  border: 1.5px solid #e5ebf3;
  color: #202c42;
  background: #ffffff;
  box-sizing: border-box;
}

.inquiry-confirm__go {
  border: 0;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
}
</style>
