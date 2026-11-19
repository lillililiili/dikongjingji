<script setup lang="ts">
// The bottom confirmation of the DemandList board (取消需求、删除草稿), also used for 选择此报价.
defineProps<{
  title: string
  text: string
  keepLabel: string
  goLabel: string
  busy?: boolean
}>()

const emit = defineEmits<{
  keep: []
  go: []
}>()
</script>

<template>
  <!-- Below the uni picker (999) like every sheet of the project. -->
  <view class="demand-confirm" data-testid="demand-confirm">
    <view class="demand-confirm__mask" aria-label="关闭" @click="emit('keep')" />
    <view class="demand-confirm__sheet" role="dialog" aria-modal="true" :aria-label="title">
      <text class="demand-confirm__title">{{ title }}</text>
      <text class="demand-confirm__text">{{ text }}</text>
      <view class="demand-confirm__actions">
        <button class="demand-confirm__keep" data-testid="demand-confirm-keep" @click="emit('keep')">
          {{ keepLabel }}
        </button>
        <button
          class="demand-confirm__go"
          data-testid="demand-confirm-go"
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
.demand-confirm {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
}

.demand-confirm__mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.demand-confirm__sheet {
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

.demand-confirm__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.demand-confirm__text {
  color: #536178;
  font-size: 14px;
  line-height: 20px;
}

.demand-confirm__actions {
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

.demand-confirm__keep,
.demand-confirm__go {
  height: 44px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
}

.demand-confirm__keep {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
  box-sizing: border-box;
}

.demand-confirm__go {
  border: 0;
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}
</style>
