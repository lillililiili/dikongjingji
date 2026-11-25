<script setup lang="ts">
import { computed, ref } from 'vue'

import type { MallProductDetail } from '@/features/mall/domain/mall'
import { MALL_ACTION_LABELS } from '@/features/mall/presentation/shared/mall-format'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { MAX_COMPARE_ITEMS } from '../application/compare-selection'
import { buildCompareTable, visibleCompareRows } from '../application/compare-table'

// Board MallCompare (商品对比): one column per product, its parameters row by row; rows whose values
// differ are tinted, and 只看不同 hides the rest. Facts come from the mall's product details.
const props = defineProps<{
  status: 'loading' | 'ready' | 'error' | 'too-few'
  products: MallProductDetail[]
}>()

const emit = defineEmits<{
  back: []
  retry: []
  open: [productId: string]
}>()

// The board's robot outline, drawn when a product has no picture.
const ROBOT = 'M6 8h12v10H6zM9 12h.01M15 12h.01M12 4v4M10 15h4M4 12h2M18 12h2'

const onlyDifferent = ref(false)
const table = computed(() => buildCompareTable(props.products))
const rows = computed(() => visibleCompareRows(table.value, onlyDifferent.value).map((row, index) => ({
  ...row,
  shade: index % 2 === 0,
})))
const grid = computed(() => `84px repeat(${Math.max(1, props.products.length)}, minmax(0, 1fr))`)

function coverOf(product: MallProductDetail): { url: string; contain: boolean } | null {
  if (product.coverImage) return { url: product.coverImage.url, contain: false }
  return null
}

function actionLabel(product: MallProductDetail): string {
  return product.saleMode === 'BUY' ? '选商家购买' : MALL_ACTION_LABELS[product.saleMode]
}
</script>

<template>
  <view class="compare" data-testid="mall-compare-page">
    <AppPageHeader title="商品对比" plain test-id="mall-compare" @back="emit('back')">
      <template #actions>
        <view class="compare__head-extra">
          <text v-if="status === 'ready'" class="compare__count" data-testid="mall-compare-count">
            {{ products.length }} / {{ MAX_COMPARE_ITEMS }}
          </text>
          <view
            v-if="status === 'ready'"
            class="compare__diff"
            role="checkbox"
            :aria-checked="onlyDifferent"
            data-testid="mall-compare-only-different"
            @click="onlyDifferent = !onlyDifferent"
          >
            <view :class="['compare__box', { 'compare__box--on': onlyDifferent }]">
              <LineIcon v-if="onlyDifferent" name="check" :size="12" :stroke-width="3" color="#ffffff" />
            </view>
            <text>只看不同</text>
          </view>
        </view>
      </template>
    </AppPageHeader>

    <view v-if="status === 'loading'" class="compare__state">
      <text>正在读取商品…</text>
    </view>
    <view v-else-if="status === 'error'" class="compare__state" data-testid="mall-compare-error">
      <text>商品加载失败，请稍后重试</text>
      <button class="compare__state-action" @click="emit('retry')">重新加载</button>
    </view>
    <view v-else-if="status === 'too-few'" class="compare__state" data-testid="mall-compare-too-few">
      <text>可对比的商品不足 2 件</text>
      <text class="compare__state-copy">已下架或当前城市不在售的商品不能对比，请回去重新勾选</text>
      <button class="compare__state-action" @click="emit('back')">返回</button>
    </view>

    <template v-else>
      <view class="compare__table">
        <view class="compare__columns" :style="{ gridTemplateColumns: grid }">
          <view />
          <view
            v-for="(column, index) in table.columns"
            :key="column.productId"
            class="compare__column"
            :data-testid="`mall-compare-column-${column.productId}`"
            @click="emit('open', column.productId)"
          >
            <view
              :class="['compare__picture', { 'compare__picture--contain': coverOf(products[index])?.contain }]"
            >
              <image
                v-if="coverOf(products[index])"
                class="compare__image"
                :src="coverOf(products[index])?.url"
                :mode="coverOf(products[index])?.contain ? 'aspectFit' : 'aspectFill'"
              />
              <LineIcon v-else :d="ROBOT" :size="30" :stroke-width="1.5" color="#91a0b4" />
            </view>
            <text class="compare__name">{{ column.name }}</text>
            <text class="compare__price">{{ column.price }}</text>
          </view>
        </view>

        <view
          v-for="row in rows"
          :key="row.key"
          :class="['compare__row', { 'compare__row--shade': row.shade, 'compare__row--differs': row.differs }]"
          :style="{ gridTemplateColumns: grid }"
          :data-testid="`mall-compare-row-${row.key}`"
          :data-differs="row.differs ? 'true' : 'false'"
        >
          <text class="compare__label">{{ row.label }}</text>
          <text v-for="(value, index) in row.values" :key="index" class="compare__value">{{ value }}</text>
        </view>
        <view v-if="onlyDifferent && rows.length === 0" class="compare__same" data-testid="mall-compare-all-same">
          <text>这几件商品已填的参数都相同</text>
        </view>
        <text class="compare__note">
          参数由商家填报，以商品详情页为准。同一商品多家在售时，展示最低报价及商家数。
        </text>
      </view>

      <view class="compare__bar" :style="{ gridTemplateColumns: grid }">
        <view />
        <view v-for="(product, index) in products" :key="product.productId" class="compare__bar-cell">
          <button
            :class="['compare__buy', { 'compare__buy--light': index > 0 }]"
            :data-testid="`mall-compare-open-${product.productId}`"
            @click="emit('open', product.productId)"
          >
            {{ actionLabel(product) }}
          </button>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours copied from board MallCompare (390 wide). */
.compare {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
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

/* Board MallCompare: “2 / 3” right after the title, 只看不同 at the right edge. */
.compare :deep(.app-head__titles) {
  flex: none;
}

.compare :deep(.app-head__actions) {
  min-width: 0;
  flex: 1;
}

.compare__head-extra {
  display: flex;
  flex: 1;
  align-items: center;
}

.compare__count {
  margin-left: 6px;
  color: #627187;
  font-size: 13px;
  white-space: nowrap;
}

.compare__diff {
  display: flex;
  min-height: 44px;
  margin: 0 8px 0 auto;
  align-items: center;
  gap: 6px;
  color: #536178;
  font-size: 13px;
  white-space: nowrap;
}

.compare__box {
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

.compare__box--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.compare__table {
  flex: 1;
}

.compare__columns,
.compare__row,
.compare__bar {
  display: grid;
}

.compare__columns {
  border-bottom: 1px solid #e5ebf3;
  background: #ffffff;
}

.compare__column {
  display: flex;
  min-width: 0;
  padding: 12px 8px;
  flex-direction: column;
  gap: 6px;
  border-left: 1px solid #f0f1f3;
}

.compare__picture {
  display: flex;
  height: 76px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 10px;
  background: #eef0f3;
}

.compare__picture--contain {
  background: #f5f7fc;
}

.compare__image {
  width: 100%;
  height: 100%;
}

.compare__name {
  font-size: 13px;
  font-weight: 500;
  line-height: 1.4;
}

.compare__price {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
}

.compare__row {
  border-bottom: 1px solid #f0f1f3;
  background: #ffffff;
}

.compare__row--shade {
  background: #fcfcfd;
}

/* The values differ: a light orange tint, so the eye finds what sets the products apart. */
.compare__row--differs {
  background: #fff7f0;
}

.compare__row--differs .compare__value {
  color: #202c42;
  font-weight: 500;
}

.compare__label {
  padding: 10px 8px 10px 16px;
  color: #627187;
  font-size: 12px;
}

.compare__value {
  min-width: 0;
  padding: 10px 8px;
  border-left: 1px solid #f0f1f3;
  font-size: 13px;
  line-height: 1.45;
  word-break: break-all;
}

.compare__same {
  padding: 16px;
  color: #738096;
  font-size: 13px;
  text-align: center;
}

.compare__note {
  display: block;
  padding: 10px 16px;
  color: #627187;
  font-size: 12px;
  line-height: 1.6;
}

.compare__bar {
  position: sticky;
  bottom: 0;
  padding: 10px 0 calc(14px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.compare__bar-cell {
  padding: 0 6px;
}

.compare__buy {
  display: flex;
  width: 100%;
  height: 40px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}

.compare__buy--light {
  color: #ba470b;
  background: #fff0e3;
}

.compare__state {
  display: flex;
  padding: 72px 24px;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: #738096;
  font-size: 14px;
  text-align: center;
}

.compare__state-copy {
  font-size: 12px;
  line-height: 18px;
}

.compare__state-action {
  height: 36px;
  padding: 0 16px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 13px;
}
</style>
