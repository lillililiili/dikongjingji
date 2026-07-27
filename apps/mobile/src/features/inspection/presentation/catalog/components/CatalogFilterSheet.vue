<script setup lang="ts">
import { ref, watch } from 'vue'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

export interface CatalogFilterValue {
  method: 'ALL' | 'UAV' | 'ROBOT'
  priceMode: 'ALL' | 'FIXED' | 'PROJECT_QUOTE'
  includesReport: boolean
}

const props = defineProps<{
  modelValue: CatalogFilterValue
}>()

const emit = defineEmits<{
  close: []
  apply: [value: CatalogFilterValue]
  reset: []
}>()

const draft = ref<CatalogFilterValue>({ ...props.modelValue })
const METHOD_OPTIONS: Array<{
  value: CatalogFilterValue['method']
  label: string
}> = [
  { value: 'ALL', label: '不限' },
  { value: 'UAV', label: '无人机' },
  { value: 'ROBOT', label: '智能设备' },
]
const PRICE_OPTIONS: Array<{
  value: CatalogFilterValue['priceMode']
  label: string
}> = [
  { value: 'ALL', label: '不限' },
  { value: 'FIXED', label: '明确价格' },
  { value: 'PROJECT_QUOTE', label: '按项目报价' },
]

watch(
  () => props.modelValue,
  (value) => {
    draft.value = { ...value }
  },
  { deep: true },
)

function apply(): void {
  emit('apply', { ...draft.value })
}
</script>

<template>
  <view class="catalog-sheet" @click.self="$emit('close')">
    <section class="catalog-sheet__panel">
      <view class="catalog-sheet__heading">
        <text class="catalog-sheet__title">筛选巡检服务</text>
        <button
          class="catalog-sheet__close"
          aria-label="关闭筛选"
          @click="$emit('close')"
        >
          <UniIcons type="closeempty" size="24" color="#555a61" />
        </button>
      </view>

      <view class="catalog-sheet__group">
        <text class="catalog-sheet__label">巡检方式</text>
        <view class="catalog-sheet__options">
          <button
            v-for="option in METHOD_OPTIONS"
            :key="option.value"
            class="catalog-sheet__option"
            :class="{
              'catalog-sheet__option--active':
                draft.method === option.value,
            }"
            @click="draft.method = option.value"
          >
            {{ option.label }}
          </button>
        </view>
      </view>

      <view class="catalog-sheet__group">
        <text class="catalog-sheet__label">报价方式</text>
        <view class="catalog-sheet__options">
          <button
            v-for="option in PRICE_OPTIONS"
            :key="option.value"
            class="catalog-sheet__option"
            :class="{
              'catalog-sheet__option--active':
                draft.priceMode === option.value,
            }"
            @click="draft.priceMode = option.value"
          >
            {{ option.label }}
          </button>
        </view>
      </view>

      <button
        class="catalog-sheet__report"
        @click="draft.includesReport = !draft.includesReport"
      >
        <UniIcons
          :type="
            draft.includesReport
              ? 'checkbox-filled'
              : 'circle'
          "
          size="20"
          :color="draft.includesReport ? '#19b866' : '#a8adb4'"
        />
        <text>包含巡检报告</text>
      </button>

      <view class="catalog-sheet__actions">
        <button
          class="catalog-sheet__button catalog-sheet__button--secondary"
          @click="$emit('reset')"
        >
          重置
        </button>
        <button
          class="catalog-sheet__button catalog-sheet__button--primary"
          @click="apply"
        >
          查看结果
        </button>
      </view>
    </section>
  </view>
</template>

<style scoped>
.catalog-sheet {
  position: fixed;
  z-index: 50;
  inset: 0;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(18, 21, 25, 0.38);
}

.catalog-sheet__panel {
  width: 100%;
  max-width: 600px;
  padding:
    18px
    18px
    calc(18px + env(safe-area-inset-bottom));
  border-radius: 18px 18px 0 0;
  background: #ffffff;
  box-sizing: border-box;
}

.catalog-sheet__heading {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) 44px;
  align-items: center;
}

.catalog-sheet__title {
  grid-column: 2;
  color: #23262b;
  font-size: 18px;
  font-weight: 750;
  text-align: center;
}

.catalog-sheet__close {
  grid-column: 3;
  display: flex;
  width: 44px;
  height: 44px;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
}

.catalog-sheet__close::after,
.catalog-sheet__option::after,
.catalog-sheet__report::after,
.catalog-sheet__button::after {
  border: 0;
}

.catalog-sheet__group {
  margin-top: 20px;
}

.catalog-sheet__label {
  color: #35393f;
  font-size: 14px;
  font-weight: 650;
}

.catalog-sheet__options {
  display: grid;
  margin-top: 10px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 9px;
}

.catalog-sheet__option {
  height: 40px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  color: #4c5158;
  background: #f5f6f7;
  font-size: 13px;
}

.catalog-sheet__option--active {
  border-color: #ffb37c;
  color: #ff5a00;
  background: #fff5ed;
  font-weight: 650;
}

.catalog-sheet__report {
  display: flex;
  width: 100%;
  min-height: 44px;
  margin-top: 16px;
  padding: 0;
  align-items: center;
  gap: 8px;
  border: 0;
  color: #444950;
  background: transparent;
  font-size: 14px;
  text-align: left;
}

.catalog-sheet__actions {
  display: grid;
  margin-top: 16px;
  grid-template-columns: 1fr 1.6fr;
  gap: 10px;
}

.catalog-sheet__button {
  height: 46px;
  border-radius: 24px;
  font-size: 15px;
  font-weight: 650;
}

.catalog-sheet__button--secondary {
  border: 1px solid #dfe2e6;
  color: #4d5259;
  background: #ffffff;
}

.catalog-sheet__button--primary {
  border: 1px solid #ff5a00;
  color: #ffffff;
  background: #ff5a00;
}
</style>
