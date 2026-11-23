<script setup lang="ts">
import { computed } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { COMPARE_BLOCKED_MESSAGES, MAX_COMPARE_ITEMS, type CompareSelection } from '../application/compare-selection'
import {
  favoriteTargetUrl,
  formatFavoritePrice,
  resolveFavoritePicture,
} from '../application/favorite-targets'
import type { FavoritesListState } from '../application/favorites-list'
import { FAVORITE_GROUPS, FAVORITE_GROUP_LABELS, type FavoriteGroup, type FavoriteItem } from '../domain/favorite'
import CompareBar from './CompareBar.vue'
import CompareCheck from './CompareCheck.vue'

// Board Favorites (收藏与对比): four tabs with counts, one card per favorite (a gone one greyed with
// 已下架 and no link), 对比 on products, 取消收藏 with 撤销, and the compare pill from two picks on.
const props = defineProps<{
  state: FavoritesListState
  cityCode: string
  compare: CompareSelection
}>()

const emit = defineEmits<{
  back: []
  selectGroup: [group: FavoriteGroup]
  remove: [item: FavoriteItem]
  undo: []
  dismissUndo: []
  retry: []
  loadMore: []
  startCompare: []
}>()

// The board's icons: the arm drawn on a product without a picture, the empty heart, the toast's info.
const PRODUCT_PLACEHOLDER = 'M5 21h10M10 21v-3M7 18h6M10 18L7 10l8-4M15 6l3 1.5M15 6l1.5-3M5.4 10a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0-3.2 0'
const EMPTY_HEART = 'M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.5A4 4 0 0 1 19 10c0 5.5-7 10-7 10z'

const tabs = computed(() => FAVORITE_GROUPS.map((group) => ({
  group,
  label: FAVORITE_GROUP_LABELS[group],
  count: props.state.counts ? props.state.counts[group] : null,
  on: group === props.state.group,
})))

const rows = computed(() => props.state.list.items.map((item) => ({
  item,
  key: `${item.targetType}:${item.targetId}`,
  picture: resolveFavoritePicture(item),
  price: formatFavoritePrice(item.price),
  comparable: props.state.group === 'GOODS' && item.available && item.productCategory !== null,
  picked: props.compare.isPicked(item.targetId),
  blocked: item.productCategory !== null
    && props.compare.blockedReason({ productId: item.targetId, category: item.productCategory }) !== null,
})))

const onGoods = computed(() => props.state.group === 'GOODS')
// The tip shows while at least two products could be compared (board: “勾选 2–3 件商品即可对比”).
const showCompareTip = computed(() => onGoods.value && rows.value.filter((row) => row.comparable).length >= 2)
const showCompareBar = computed(() => onGoods.value && props.compare.picks.length >= 2)

function open(item: FavoriteItem): void {
  if (!item.available) return
  uni.navigateTo({ url: favoriteTargetUrl(item, props.cityCode) })
}

function toggleCompare(item: FavoriteItem): void {
  if (!item.productCategory) return
  const result = props.compare.toggle({ productId: item.targetId, category: item.productCategory })
  if (result === 'FULL' || result === 'OTHER_CATEGORY') {
    uni.showToast({ title: COMPARE_BLOCKED_MESSAGES[result], icon: 'none' })
  }
}
</script>

<template>
  <view class="favorites" data-testid="favorites-page">
    <AppPageHeader title="收藏与对比" plain test-id="favorites" @back="emit('back')">
      <template #bottom>
        <view class="favorites__tabs" role="tablist" aria-label="收藏类型">
          <button
            v-for="tab in tabs"
            :key="tab.group"
            :class="['favorites__tab', { 'favorites__tab--on': tab.on }]"
            role="tab"
            :aria-selected="tab.on"
            :data-testid="`favorites-tab-${tab.group.toLowerCase()}`"
            @click="emit('selectGroup', tab.group)"
          >
            <text>{{ tab.label }}</text>
            <text v-if="tab.count !== null" class="favorites__tab-count">{{ tab.count }}</text>
            <view v-if="tab.on" class="favorites__tab-line" />
          </button>
        </view>
      </template>
    </AppPageHeader>

    <view class="favorites__body">
      <text v-if="showCompareTip" class="favorites__tip">勾选 2–{{ MAX_COMPARE_ITEMS }} 件商品即可对比</text>

      <template v-if="state.list.status === 'loading' || state.list.status === 'idle'">
        <view v-for="index in 3" :key="index" class="favorites__skeleton" />
      </template>

      <view v-else-if="state.list.status === 'error'" class="favorites__state" data-testid="favorites-error">
        <text class="favorites__state-text">收藏加载失败，请稍后重试</text>
        <button class="favorites__retry" data-testid="favorites-retry" @click="emit('retry')">重新加载</button>
      </view>

      <view v-else-if="rows.length === 0" class="favorites__empty" data-testid="favorites-empty">
        <view class="favorites__empty-icon">
          <LineIcon :d="EMPTY_HEART" :size="30" :stroke-width="1.6" color="#91a0b4" />
        </view>
        <text class="favorites__state-text">还没有收藏</text>
      </view>

      <template v-else>
        <view
          v-for="row in rows"
          :key="row.key"
          :class="['favorite-card', { 'favorite-card--picked': row.picked }]"
          :data-testid="`favorite-${row.item.targetId}`"
          :data-available="row.item.available ? 'true' : 'false'"
        >
          <view
            :class="['favorite-card__main', { 'favorite-card__main--gone': !row.item.available }]"
            :role="row.item.available ? 'link' : undefined"
            @click="open(row.item)"
          >
            <view :class="['favorite-card__cover', { 'favorite-card__cover--contain': row.picture?.contain }]">
              <image
                v-if="row.picture"
                class="favorite-card__image"
                :src="row.picture.url"
                :mode="row.picture.contain ? 'aspectFit' : 'aspectFill'"
                lazy-load
              />
              <view v-else class="favorite-card__placeholder">
                <LineIcon :d="PRODUCT_PLACEHOLDER" :size="28" :stroke-width="1.5" color="#91a0b4" />
                <text>商品图</text>
                <text>待授权素材</text>
              </view>
            </view>
            <view class="favorite-card__copy">
              <text class="favorite-card__name">{{ row.item.title }}</text>
              <template v-if="row.item.available">
                <view v-if="row.price" class="favorite-card__price">
                  <text class="favorite-card__amount">{{ row.price.amount }}</text>
                  <text v-if="row.price.unit" class="favorite-card__unit">{{ row.price.unit }}</text>
                </view>
                <text v-if="row.item.meta" class="favorite-card__meta">{{ row.item.meta }}</text>
                <text v-if="row.item.tag" class="favorite-card__tag">{{ row.item.tag }}</text>
              </template>
              <template v-else>
                <text class="favorite-card__gone-tag">已下架</text>
                <text class="favorite-card__gone-note">不能查看详情或购买</text>
              </template>
            </view>
          </view>
          <view class="favorite-card__footer">
            <CompareCheck
              v-if="row.comparable"
              :checked="row.picked"
              :blocked="!row.picked && row.blocked"
              :label="`对比：${row.item.title}`"
              :font-size="13"
              @toggle="toggleCompare(row.item)"
            />
            <button
              class="favorite-card__remove"
              :aria-label="`取消收藏：${row.item.title}`"
              data-testid="favorite-remove"
              @click="emit('remove', row.item)"
            >
              取消收藏
            </button>
          </view>
        </view>

        <view class="favorites__more">
          <text v-if="state.list.loadingMore">正在加载…</text>
          <button v-else-if="state.list.loadMoreFailed" class="favorites__more-retry" @click="emit('loadMore')">
            加载失败，点此重试
          </button>
          <text v-else-if="!state.list.hasMore">没有更多了</text>
        </view>
      </template>
    </view>

    <view class="favorites__float">
      <view v-if="state.undo" class="favorites__undo" role="status" data-testid="favorites-undo">
        <LineIcon name="info" :size="14" :stroke-width="2" color="#ff6a00" />
        <text>已取消收藏 ·</text>
        <button class="favorites__undo-action" data-testid="favorites-undo-action" @click="emit('undo')">撤销</button>
        <button class="favorites__undo-close" aria-label="关闭提示" @click="emit('dismissUndo')">×</button>
      </view>
      <CompareBar v-if="showCompareBar" :count="compare.picks.length" @start="emit('startCompare')" />
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours copied from board Favorites (390 wide). */
.favorites {
  min-height: 100vh;
  padding-bottom: calc(96px + env(safe-area-inset-bottom));
  color: #202c42;
  background: #f5f7fc;
  box-sizing: border-box;
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

.favorites__tabs {
  display: grid;
  padding: 0 8px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.favorites__tab {
  position: relative;
  display: flex;
  height: 44px;
  align-items: center;
  justify-content: center;
  gap: 3px;
  color: #627187;
  font-size: 16px;
  line-height: 22px;
  white-space: nowrap;
}

.favorites__tab--on {
  color: #202c42;
  font-weight: 700;
}

.favorites__tab-count {
  color: #738096;
  font-size: 13px;
  font-weight: 400;
}

.favorites__tab--on .favorites__tab-count {
  color: #ba470b;
}

.favorites__tab-line {
  position: absolute;
  bottom: 3px;
  left: 50%;
  width: 20px;
  height: 3px;
  margin-left: -10px;
  border-radius: 3px;
  background: #ff6a00;
}

.favorites__body {
  display: flex;
  padding: 12px var(--app-page-horizontal-gutter);
  flex-direction: column;
  gap: 10px;
}

.favorites__tip {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.favorites__skeleton {
  height: 140px;
  border-radius: 14px;
  background: #ffffff;
}

.favorite-card {
  display: flex;
  padding: 12px 12px 4px;
  flex-direction: column;
  border: 1.5px solid #ffffff;
  border-radius: 14px;
  background: #ffffff;
}

.favorite-card--picked {
  border-color: #ff6a00;
}

.favorite-card__main {
  display: flex;
  gap: 12px;
}

.favorite-card__cover {
  position: relative;
  width: 80px;
  height: 80px;
  flex: 0 0 auto;
  overflow: hidden;
  border-radius: 10px;
  background: #eef0f3;
}

.favorite-card__cover--contain {
  background: #f5f7fc;
}

.favorite-card__image {
  display: block;
  width: 100%;
  height: 100%;
}

.favorite-card__main--gone .favorite-card__image {
  filter: grayscale(1);
  opacity: 0.5;
}

.favorite-card__placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #738096;
  font-size: 12px;
  line-height: 16px;
}

.favorite-card__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.favorite-card__name {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.favorite-card__main--gone .favorite-card__name {
  color: #738096;
}

.favorite-card__price {
  display: flex;
  align-items: baseline;
  gap: 3px;
  white-space: nowrap;
}

.favorite-card__amount {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
}

.favorite-card__unit {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.favorite-card__meta {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.favorite-card__tag {
  padding: 0 6px;
  border-radius: 6px;
  color: #1d63c4;
  background: #e6f0ff;
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;
}

.favorite-card__gone-tag {
  padding: 0 6px;
  border-radius: 6px;
  color: #627187;
  background: #eef2f8;
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;
}

.favorite-card__gone-note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.favorite-card__footer {
  display: flex;
  min-height: 44px;
  margin-top: 2px;
  align-items: center;
}

.favorite-card__footer :deep(.compare-check) {
  padding-right: 12px;
  gap: 6px;
}

.favorite-card__remove {
  display: flex;
  height: 36px;
  margin-left: auto;
  padding: 0 14px;
  align-items: center;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #536178;
  background: #ffffff;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
  box-sizing: border-box;
}

.favorites__empty {
  display: flex;
  padding: 72px 0;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.favorites__empty-icon {
  display: flex;
  width: 64px;
  height: 64px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef0f3;
}

.favorites__state {
  display: flex;
  padding: 56px 0;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.favorites__state-text {
  color: #738096;
  font-size: 14px;
  line-height: 20px;
}

.favorites__retry {
  height: 36px;
  padding: 0 16px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 13px;
}

.favorites__more {
  display: flex;
  min-height: 36px;
  align-items: center;
  justify-content: center;
  color: #738096;
  font-size: 12px;
}

.favorites__more-retry {
  color: #ba470b;
  font-size: 12px;
}

.favorites__float {
  position: fixed;
  right: var(--app-page-horizontal-gutter);
  bottom: calc(16px + env(safe-area-inset-bottom));
  left: var(--app-page-horizontal-gutter);
  z-index: 3;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.favorites__float :deep(.compare-bar) {
  height: 56px;
}

.favorites__float :deep(.compare-bar__start) {
  height: 44px;
}

.favorites__undo {
  display: flex;
  height: 46px;
  padding-left: 12px;
  align-items: center;
  gap: 6px;
  border: 1px solid #ffd8b8;
  border-radius: 10px;
  color: #536178;
  background: #fff0e3;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
  box-sizing: border-box;
}

.favorites__undo-action,
.favorites__undo-close {
  display: flex;
  align-items: center;
  justify-content: center;
}

.favorites__undo-action {
  height: 44px;
  margin-left: -4px;
  padding: 0 10px 0 6px;
  color: #ba470b;
  font-size: 14px;
  font-weight: 700;
}

.favorites__undo-close {
  width: 44px;
  height: 44px;
  margin-left: auto;
  color: #ba470b;
  font-size: 16px;
}
</style>
