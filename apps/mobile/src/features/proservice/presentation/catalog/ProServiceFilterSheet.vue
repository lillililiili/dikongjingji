<script lang="ts">
import type { ProServiceFacet as Facet, ProServicePriceMode as PriceMode } from '../../domain/proservice'

export interface ProServiceFilters {
  facet: Facet | null
  priceMode: PriceMode | null
}
</script>

<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { ref } from 'vue'

import { PRO_SERVICE_PRICE_MODE_OPTIONS } from '../../application/proservice-view'
import type { ProServiceFacet, ProServiceFacetGroup, ProServicePriceMode } from '../../domain/proservice'

// 筛选服务 (SvcCatalog): the type's own group (成果类型 / 服务方式 / 支持方式, from the server) and
// 报价方式. The sheet edits a copy; nothing reaches the list until 查看结果.
const props = defineProps<{
  group: ProServiceFacetGroup
  filters: ProServiceFilters
}>()

const emit = defineEmits<{
  close: []
  apply: [filters: ProServiceFilters]
}>()

const draft = ref<ProServiceFilters>({ ...props.filters })
const facetOptions = [
  { code: null, label: '不限' },
  ...props.group.options,
] as ReadonlyArray<{ code: ProServiceFacet | null; label: string }>

function pickFacet(code: ProServiceFacet | null): void {
  draft.value = { ...draft.value, facet: code }
}

function pickPriceMode(code: ProServicePriceMode | null): void {
  draft.value = { ...draft.value, priceMode: code }
}

function reset(): void {
  emit('apply', { facet: null, priceMode: null })
}
</script>

<template>
  <view class="pro-sheet" data-testid="pro-filter-sheet" @click.self="emit('close')">
    <view class="pro-sheet__panel" role="dialog" aria-modal="true" aria-label="筛选服务">
      <view class="pro-sheet__heading">
        <text class="pro-sheet__title">筛选服务</text>
        <button class="pro-sheet__close" data-testid="pro-filter-close" aria-label="关闭筛选" @click="emit('close')">
          <UniIcons type="closeempty" size="22" color="#202c42" />
        </button>
      </view>

      <view class="pro-sheet__group">
        <text class="pro-sheet__group-title">{{ group.label }}</text>
        <view class="pro-sheet__options">
          <button
            v-for="option in facetOptions"
            :key="option.code ?? 'ALL'"
            :class="['pro-sheet__option', { 'pro-sheet__option--on': draft.facet === option.code }]"
            :data-testid="`pro-filter-facet-${(option.code ?? 'all').toLowerCase()}`"
            :aria-pressed="draft.facet === option.code"
            @click="pickFacet(option.code)"
          >
            {{ option.label }}
          </button>
        </view>
      </view>

      <view class="pro-sheet__group">
        <text class="pro-sheet__group-title">报价方式</text>
        <view class="pro-sheet__options">
          <button
            v-for="option in PRO_SERVICE_PRICE_MODE_OPTIONS"
            :key="option.code ?? 'ALL'"
            :class="['pro-sheet__option', { 'pro-sheet__option--on': draft.priceMode === option.code }]"
            :data-testid="`pro-filter-price-${(option.code ?? 'all').toLowerCase()}`"
            :aria-pressed="draft.priceMode === option.code"
            @click="pickPriceMode(option.code)"
          >
            {{ option.label }}
          </button>
        </view>
      </view>

      <view class="pro-sheet__actions">
        <button class="pro-sheet__reset" data-testid="pro-filter-reset" @click="reset">重置</button>
        <button class="pro-sheet__apply" data-testid="pro-filter-apply" @click="emit('apply', { ...draft })">查看结果</button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the filter sheet of the SvcCatalog board. Below 999, where uni's
   pickers open. */
.pro-sheet {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.45);
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.pro-sheet__panel {
  display: flex;
  width: 100%;
  max-width: 600px;
  margin: 0 auto;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 14px;
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #ffffff;
  box-sizing: border-box;
}

.pro-sheet__heading {
  display: flex;
  margin-right: -12px;
  align-items: center;
}

.pro-sheet__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.pro-sheet__close {
  display: flex;
  width: 44px;
  height: 44px;
  margin-left: auto;
  align-items: center;
  justify-content: center;
}

.pro-sheet__group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pro-sheet__group-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.pro-sheet__options {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.pro-sheet__option {
  height: 44px;
  padding: 0 4px;
  border: 1px solid #eef2f8;
  border-radius: 8px;
  color: #202c42;
  background: #eef2f8;
  font-size: 14px;
  line-height: 42px;
  white-space: nowrap;
  box-sizing: border-box;
}

.pro-sheet__option--on {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fffaf5;
  font-weight: 500;
}

.pro-sheet__actions {
  display: flex;
  gap: 10px;
}

.pro-sheet__reset {
  width: 112px;
  height: 46px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 15px;
  line-height: 44px;
}

.pro-sheet__apply {
  height: 46px;
  flex: 1 1 auto;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
  line-height: 46px;
}
</style>
