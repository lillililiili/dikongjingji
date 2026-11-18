<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import {
  EMPTY_MALL_FILTERS,
  MALL_FILTER_GROUPS,
  cloneMallFilters,
  isMallPriceRangeValid,
  parseMallPriceInput,
  toggleMallFilter,
  type MallFilterListKey,
} from '../../application/mall-filters'
import type { MallFilters } from '../../domain/mall'

const props = defineProps<{
  filters: MallFilters
  // How many products the given filters would show, for the label of the confirm button.
  countProducts: (filters: MallFilters) => Promise<number>
}>()

const emit = defineEmits<{
  close: []
  apply: [filters: MallFilters]
}>()

// The sheet edits a copy; nothing reaches the list until 查看 is tapped.
const draft = ref<MallFilters>(cloneMallFilters(props.filters))
const minText = ref(props.filters.minPriceYuan?.toString() ?? '')
const maxText = ref(props.filters.maxPriceYuan?.toString() ?? '')
const matchCount = ref<number | null>(null)
let countRevision = 0

const candidate = computed<MallFilters>(() => ({
  ...draft.value,
  minPriceYuan: parseMallPriceInput(minText.value),
  maxPriceYuan: parseMallPriceInput(maxText.value),
}))
const rangeValid = computed(() => isMallPriceRangeValid(candidate.value))

function isPicked(key: MallFilterListKey, code: string): boolean {
  return (draft.value[key] as string[]).includes(code)
}

function toggle(key: MallFilterListKey, code: string): void {
  draft.value = toggleMallFilter(draft.value, key, code)
}

function valueOf(event: Event): string {
  return (event as Event & { detail?: { value?: string } }).detail?.value?.toString() ?? ''
}

function reset(): void {
  draft.value = cloneMallFilters(EMPTY_MALL_FILTERS)
  minText.value = ''
  maxText.value = ''
}

function apply(): void {
  if (!rangeValid.value) return
  emit('apply', cloneMallFilters(candidate.value))
}

// Only the latest answer may set the label; an invalid range has nothing to count.
watch(candidate, async (filters) => {
  const revision = ++countRevision
  matchCount.value = null
  if (!isMallPriceRangeValid(filters)) return
  try {
    const total = await props.countProducts(filters)
    if (revision === countRevision) matchCount.value = total
  } catch {
    // The button falls back to its plain label; applying still works.
  }
}, { immediate: true, deep: true })
</script>

<template>
  <view class="mall-sheet" data-testid="mall-filter-sheet" @click.self="emit('close')">
    <view class="mall-sheet__panel">
      <view class="mall-sheet__heading">
        <text class="mall-sheet__title">筛选</text>
        <button
          class="mall-sheet__close"
          data-testid="mall-filter-close"
          aria-label="关闭"
          @click="emit('close')"
        >
          <UniIcons type="closeempty" size="20" color="#536178" />
        </button>
      </view>

      <scroll-view class="mall-sheet__body" scroll-y>
        <view class="mall-sheet__groups">
          <view v-for="group in MALL_FILTER_GROUPS" :key="group.key" class="mall-sheet__group">
            <view class="mall-sheet__group-heading">
              <text class="mall-sheet__group-title">{{ group.title }}</text>
              <text v-if="group.hint" class="mall-sheet__group-hint">{{ group.hint }}</text>
            </view>
            <view class="mall-sheet__options">
              <button
                v-for="option in group.options"
                :key="option.code"
                :class="[
                  'mall-sheet__option',
                  { 'mall-sheet__option--picked': isPicked(group.key, option.code) },
                ]"
                :data-testid="`mall-filter-${option.code.toLowerCase()}`"
                :aria-pressed="isPicked(group.key, option.code)"
                @click="toggle(group.key, option.code)"
              >
                {{ option.label }}
              </button>
            </view>
          </view>

          <view class="mall-sheet__group">
            <text class="mall-sheet__group-title">价格区间（元）</text>
            <view class="mall-sheet__prices">
              <input
                class="mall-sheet__price"
                data-testid="mall-filter-min-price"
                type="number"
                maxlength="9"
                aria-label="最低价"
                placeholder="最低价"
                :value="minText"
                @input="minText = valueOf($event)"
                @blur="minText = valueOf($event)"
              />
              <text class="mall-sheet__dash">—</text>
              <input
                class="mall-sheet__price"
                data-testid="mall-filter-max-price"
                type="number"
                maxlength="9"
                aria-label="最高价"
                placeholder="最高价"
                :value="maxText"
                @input="maxText = valueOf($event)"
                @blur="maxText = valueOf($event)"
              />
            </view>
            <text
              v-if="!rangeValid"
              class="mall-sheet__note mall-sheet__note--error"
              data-testid="mall-filter-range-error"
            >
              最低价不能高于最高价
            </text>
            <text v-else class="mall-sheet__note">询价商品无标价，设置价格区间后不显示</text>
          </view>
        </view>
      </scroll-view>

      <view class="mall-sheet__actions">
        <button class="mall-sheet__reset" data-testid="mall-filter-reset" @click="reset">重置</button>
        <button
          :class="['mall-sheet__apply', { 'mall-sheet__apply--disabled': !rangeValid }]"
          data-testid="mall-filter-apply"
          :aria-disabled="!rangeValid"
          @click="apply"
        >
          {{ matchCount === null ? '查看商品' : `查看 ${matchCount} 件` }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board MallFilter (390 wide). */
.mall-sheet {
  position: fixed;
  z-index: 1200;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.45);
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
}

button::after {
  border: 0;
}

.mall-sheet__panel {
  display: flex;
  width: 100%;
  max-height: 86vh;
  flex-direction: column;
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #ffffff;
}

.mall-sheet__heading {
  display: flex;
  flex: none;
  align-items: center;
  padding: 14px 16px 6px;
}

.mall-sheet__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.mall-sheet__close {
  display: flex;
  width: 44px;
  height: 44px;
  margin-left: auto;
  align-items: center;
  justify-content: center;
}

.mall-sheet__body {
  min-height: 0;
  flex: 1;
}

.mall-sheet__groups {
  display: flex;
  padding: 0 16px 16px;
  flex-direction: column;
  gap: 18px;
}

.mall-sheet__group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.mall-sheet__group-heading {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.mall-sheet__group-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.mall-sheet__group-hint {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.mall-sheet__options {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.mall-sheet__option {
  min-height: 38px;
  padding: 0 4px;
  border: 1px solid #eef2f8;
  border-radius: 8px;
  color: #202c42;
  background: #eef2f8;
  font-size: 13px;
  line-height: 36px;
  box-sizing: border-box;
}

.mall-sheet__option--picked {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fffaf5;
}

.mall-sheet__prices {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mall-sheet__price {
  min-width: 0;
  height: 38px;
  flex: 1 1 0;
  padding: 0 12px;
  border: 1px solid #e5ebf3;
  border-radius: 8px;
  background: #f5f7fc;
  font-size: 14px;
  text-align: center;
  box-sizing: border-box;
}

.mall-sheet__dash {
  color: #738096;
}

.mall-sheet__note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.mall-sheet__note--error {
  color: #c0392b;
}

.mall-sheet__actions {
  display: flex;
  flex: none;
  gap: 10px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
}

.mall-sheet__reset {
  width: 112px;
  height: 46px;
  flex: none;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 15px;
  line-height: 44px;
  box-sizing: border-box;
}

.mall-sheet__apply {
  height: 46px;
  flex: 1;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
  line-height: 46px;
}

.mall-sheet__apply--disabled {
  background: #c9d3e1;
}
</style>
