<script setup lang="ts">
import { onLoad, onReachBottom, onShow } from '@dcloudio/uni-app'

import { useCompareSelection } from '@/features/favorite/application/compare-selection'
import { FAVORITE_PREVIEW_ENABLED } from '@/features/favorite/application/favorite-preview'
import { createFavoritesList } from '@/features/favorite/application/favorites-list'
import { favoriteApi } from '@/features/favorite/data/favorite-api-client'
import type { FavoriteGroup } from '@/features/favorite/domain/favorite'
import {
  buildFavoritesUrl,
  buildMallCompareUrl,
  parseFavoriteGroup,
} from '@/features/favorite/navigation/favorite-routes'
import FavoritesPage from '@/features/favorite/presentation/FavoritesPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { MINE_TAB_PATH, buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

// 我的 → 收藏与对比 (board Favorites). Needs a login; the comparison picks live on this device.
const cityStore = useCurrentCityStore()
const compare = useCompareSelection()
let leaving = false

function cityCode(): string {
  return cityStore.currentCity?.code ?? (import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim() || '')
}

const favorites = createFavoritesList({
  api: favoriteApi,
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
  getCityCode: cityCode,
  onLogin: openLogin,
  toast: (title) => uni.showToast({ title, icon: 'none' }),
  // A product that is no longer a favorite leaves the comparison; 撤销 brings it back when it still fits.
  onRemoved: (item) => { if (item.targetType === 'PRODUCT') compare.remove(item.targetId) },
})

function openLogin(): void {
  if (leaving) return
  leaving = true
  uni.redirectTo({ url: buildInspectionLoginUrl(buildFavoritesUrl(favorites.state.group)) })
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: MINE_TAB_PATH })
}

function startCompare(): void {
  uni.navigateTo({ url: buildMallCompareUrl(compare.picks.map((pick) => pick.productId), cityCode()) })
}

function selectGroup(group: FavoriteGroup): void {
  void favorites.selectGroup(group)
}

onLoad((query) => {
  favorites.state.group = parseFavoriteGroup(query?.group)
})

// Every return reads the list again: something may have been favorited or taken down meanwhile.
onShow(() => {
  if (!FAVORITE_PREVIEW_ENABLED) return
  if (!getBookingAuthContext().getAccessToken()) {
    openLogin()
    return
  }
  void favorites.reload()
})

onReachBottom(() => {
  void favorites.loadMore()
})
</script>

<template>
  <FavoritesPage
    v-if="FAVORITE_PREVIEW_ENABLED"
    :state="favorites.state"
    :city-code="cityCode()"
    :compare="compare"
    @back="goBack"
    @select-group="selectGroup"
    @remove="favorites.remove"
    @undo="favorites.undo"
    @dismiss-undo="favorites.dismissUndo"
    @retry="favorites.reload"
    @load-more="favorites.loadMore"
    @start-compare="startCompare"
  />
  <view v-else class="favorites-closed" data-testid="favorites-unavailable">
    <text>收藏与对比暂未开放</text>
  </view>
</template>

<style scoped>
.favorites-closed {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #738096;
  background: #f5f7fc;
  font-size: 14px;
}
</style>
