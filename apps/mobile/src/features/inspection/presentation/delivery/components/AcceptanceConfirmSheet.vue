<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import type { AcceptanceSheetView } from '../delivery-view'

defineProps<{
  sheet: AcceptanceSheetView
  isAccepting: boolean
}>()

const emit = defineEmits<{
  close: []
  confirm: []
}>()
</script>

<template>
  <view class="acceptance-sheet">
    <button class="acceptance-sheet__mask" aria-label="关闭" @click="emit('close')" />
    <view class="acceptance-sheet__panel" role="dialog" aria-modal="true" :aria-label="sheet.title">
      <text class="acceptance-sheet__title">{{ sheet.title }}</text>
      <view class="acceptance-sheet__facts">
        <text class="acceptance-sheet__label">验收内容</text>
        <view class="acceptance-sheet__items">
          <view v-for="item in sheet.items" :key="item.key" class="acceptance-sheet__item">
            <UniIcons type="checkmarkempty" size="14" color="#19b866" />
            <text>{{ item.name }}</text>
          </view>
        </view>
        <template v-if="sheet.providerName">
          <text class="acceptance-sheet__label">服务商</text>
          <text>{{ sheet.providerName }}</text>
        </template>
        <template v-if="sheet.acceptorText">
          <text class="acceptance-sheet__label">验收人</text>
          <text>{{ sheet.acceptorText }}</text>
        </template>
        <template v-if="sheet.feeText">
          <text class="acceptance-sheet__label">本次服务费</text>
          <text>{{ sheet.feeText }}</text>
        </template>
      </view>
      <view class="acceptance-sheet__warning">
        <image
          class="acceptance-sheet__warning-icon"
          src="/static/images/inspection/service-flow-warning.svg"
          mode="aspectFit"
        />
        <text class="acceptance-sheet__warning-text">验收确认后不可撤销；验收人与时间会留存记录</text>
      </view>
      <view class="acceptance-sheet__actions">
        <button class="acceptance-sheet__cancel" @click="emit('close')">取消</button>
        <button
          class="acceptance-sheet__confirm"
          :disabled="isAccepting"
          @click="emit('confirm')"
        >
          {{ isAccepting ? '验收确认中' : '确认验收' }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.acceptance-sheet {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 20;
}

.acceptance-sheet__mask {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: rgba(0, 0, 0, 0.45);
}

.acceptance-sheet__mask::after,
.acceptance-sheet__cancel::after,
.acceptance-sheet__confirm::after {
  border: 0;
}

.acceptance-sheet__panel {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  padding: 20px 16px max(24px, env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 14px;
  border-radius: 20px 20px 0 0;
  background: #fff;
}

.acceptance-sheet__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.acceptance-sheet__facts {
  display: grid;
  padding: 12px;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 8px 12px;
  border-radius: 10px;
  background: #f7f8fa;
  font-size: 13px;
  line-height: 18px;
}

.acceptance-sheet__label {
  color: #666b73;
  white-space: nowrap;
}

.acceptance-sheet__items {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.acceptance-sheet__item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.acceptance-sheet__warning {
  display: flex;
  padding: 10px 12px;
  gap: 6px;
  border-radius: 10px;
  background: #fff3e8;
}

.acceptance-sheet__warning-icon {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  margin-top: 1px;
}

.acceptance-sheet__warning-text {
  min-width: 0;
  color: #d94f00;
  font-size: 13px;
  line-height: 18px;
}

.acceptance-sheet__actions {
  display: flex;
  gap: 10px;
}

.acceptance-sheet__cancel,
.acceptance-sheet__confirm {
  height: 44px;
  flex: 1;
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  border-radius: 999px;
  font-size: 15px;
  line-height: 42px;
}

.acceptance-sheet__cancel {
  border: 1px solid #c4c8ce;
  color: #191919;
  background: #fff;
}

.acceptance-sheet__confirm {
  border: 0;
  color: #fff;
  background: #ff6a00;
  font-weight: 500;
  line-height: 44px;
}

.acceptance-sheet__confirm[disabled] {
  color: #fff;
  background: #c4c8ce;
  opacity: 1;
}
</style>
