<script setup lang="ts">
import { computed, ref } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  CARRIER_NAME_LIMIT,
  SAMPLE_CARRIER,
  initialWaybillDraft,
  waybillHint,
  waybillInput,
  type CarrierPick,
} from '../../application/aftersale-waybill'
import type { AftersaleWaybill, ReturnShipmentInput } from '../../domain/aftersale'

// 填写 / 修改退货运单（画板 AftersaleFlow 的底部弹层）。提交由详情页带 If-Match 发出。
const props = defineProps<{
  editing: boolean
  waybill: AftersaleWaybill | null
  busy: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: [input: ReturnShipmentInput]
}>()

const draft = ref(initialWaybillDraft(props.waybill))
const input = computed(() => waybillInput(draft.value))
const hint = computed(() => waybillHint(draft.value))
const CARRIERS: Array<{ pick: CarrierPick; name: string }> = [
  { pick: 'SAMPLE', name: SAMPLE_CARRIER },
  { pick: 'OTHER', name: '其他承运商' },
]

function submit(): void {
  if (input.value && !props.busy) emit('submit', input.value)
}
</script>

<template>
  <view class="waybill-sheet" data-testid="aftersale-waybill-sheet">
    <button class="waybill-sheet__scrim" aria-label="关闭" @click="emit('close')" />
    <view class="waybill-sheet__panel" role="dialog" aria-modal="true" :aria-label="editing ? '修改退货运单' : '填写退货运单'">
      <view class="waybill-sheet__head">
        <text class="waybill-sheet__title">{{ editing ? '修改退货运单' : '填写退货运单' }}</text>
        <button class="waybill-sheet__close" aria-label="关闭" @click="emit('close')">
          <LineIcon name="close" :size="20" color="#536178" :stroke-width="2" />
        </button>
      </view>
      <view class="waybill-sheet__fields">
        <view class="waybill-sheet__row waybill-sheet__row--carriers">
          <text class="waybill-sheet__label">承运商</text>
          <view class="waybill-sheet__carriers" role="radiogroup" aria-label="承运商">
            <button
              v-for="carrier in CARRIERS"
              :key="carrier.pick"
              :class="['waybill-sheet__carrier', { 'waybill-sheet__carrier--on': draft.pick === carrier.pick }]"
              role="radio"
              :aria-checked="draft.pick === carrier.pick"
              :data-testid="`aftersale-carrier-${carrier.pick.toLowerCase()}`"
              @click="draft.pick = carrier.pick"
            >
              {{ carrier.name }}
            </button>
          </view>
        </view>
        <view v-if="draft.pick === 'OTHER'" class="waybill-sheet__row">
          <text class="waybill-sheet__label">名称</text>
          <input
            v-model="draft.otherCarrier"
            class="waybill-sheet__input"
            data-testid="aftersale-carrier-name"
            placeholder="填写承运商名称"
            placeholder-class="waybill-sheet__placeholder"
            :maxlength="CARRIER_NAME_LIMIT"
          />
        </view>
        <view class="waybill-sheet__row">
          <text class="waybill-sheet__label">运单号</text>
          <input
            v-model="draft.trackingNo"
            class="waybill-sheet__input"
            data-testid="aftersale-tracking-no"
            placeholder="输入或粘贴运单号"
            placeholder-class="waybill-sheet__placeholder"
            :maxlength="40"
          />
        </view>
      </view>
      <text :class="['waybill-sheet__note', { 'waybill-sheet__note--alert': hint }]" data-testid="aftersale-waybill-hint">
        {{ hint ?? '商家确认收货前可以修改运单。' }}
      </text>
      <view class="waybill-sheet__actions">
        <button class="waybill-sheet__cancel" @click="emit('close')">取消</button>
        <button
          :class="['waybill-sheet__submit', { 'waybill-sheet__submit--off': !input }]"
          data-testid="aftersale-waybill-submit"
          :disabled="!input || busy"
          @click="submit"
        >
          {{ busy ? '正在提交…' : '提交运单' }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* The sheet of the 2026-11-14 board AftersaleFlow; below uni's picker (999). */
.waybill-sheet {
  position: fixed;
  inset: 0;
  z-index: 900;
  color: #202c42;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.waybill-sheet__scrim {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.waybill-sheet__panel {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 16px max(24px, env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  background: #ffffff;
}

.waybill-sheet__head {
  display: flex;
  align-items: center;
}

.waybill-sheet__title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.waybill-sheet__close {
  display: flex;
  width: 44px;
  height: 44px;
  margin-right: -10px;
  margin-left: auto;
  align-items: center;
  justify-content: center;
}

.waybill-sheet__fields {
  display: flex;
  flex-direction: column;
}

.waybill-sheet__row {
  display: flex;
  min-height: 48px;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid #f0f1f3;
}

.waybill-sheet__row--carriers {
  min-height: 56px;
}

.waybill-sheet__label {
  width: 60px;
  flex: none;
  color: #627187;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.waybill-sheet__carriers {
  display: grid;
  flex: 1;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.waybill-sheet__carrier {
  display: flex;
  height: 40px;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #e5ebf3;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 14px;
  white-space: nowrap;
  box-sizing: border-box;
}

.waybill-sheet__carrier--on {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fffaf5;
  font-weight: 500;
}

.waybill-sheet__input {
  min-width: 0;
  height: 48px;
  flex: 1;
  color: #202c42;
  font-size: 14px;
}

:deep(.waybill-sheet__placeholder) {
  color: #91a0b4;
}

.waybill-sheet__note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.waybill-sheet__note--alert {
  color: #d64f67;
}

.waybill-sheet__actions {
  display: flex;
  margin-top: 4px;
  gap: 10px;
}

.waybill-sheet__cancel,
.waybill-sheet__submit {
  display: flex;
  height: 44px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  font-size: 15px;
  box-sizing: border-box;
}

.waybill-sheet__cancel {
  border: 1px solid #c9d3e1;
  background: #ffffff;
}

.waybill-sheet__submit {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.waybill-sheet__submit--off,
.waybill-sheet__submit[disabled] {
  color: #ffffff;
  background: #c9d3e1;
}
</style>
