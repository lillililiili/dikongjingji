<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { useRootTabBadgeStore } from '@/app-shell/state/root-tab-badges'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { CART_PREVIEW_ENABLED } from '@/features/mall/application/cart-preview'
import {
  CART_TAB_PATH,
  buildCartCheckoutUrl,
  buildMallProductUrl,
} from '@/features/mall/navigation/mall-routes'
import MallCartPage from '@/features/mall/presentation/cart/MallCartPage.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

const badgeStore = useRootTabBadgeStore()
const currentCityStore = useCurrentCityStore()
const accessToken = ref<string | null>(getBookingAuthContext().getAccessToken())
const refreshKey = ref(0)

// The cart is judged against the city the user is browsing; the home page sets it.
const cityCode = computed(() =>
  currentCityStore.currentCity?.code
    ?? import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
    ?? null)

function openLogin(): void {
  uni.navigateTo({ url: buildInspectionLoginUrl(CART_TAB_PATH) })
}

function openProduct(productId: string): void {
  if (cityCode.value) uni.navigateTo({ url: buildMallProductUrl(productId, cityCode.value) })
}

function checkout(cartItemIds: string[]): void {
  if (cityCode.value) uni.navigateTo({ url: buildCartCheckoutUrl(cartItemIds, cityCode.value) })
}

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
})
</script>

<template>
  <view data-testid="cart-page">
    <MallCartPage
      v-if="CART_PREVIEW_ENABLED"
      :access-token="accessToken"
      :city-code="cityCode"
      :refresh-key="refreshKey"
      @login="openLogin"
      @open-product="openProduct"
      @checkout="checkout"
      @browse="openHomeChannel('MALL')"
      @count-change="badgeStore.setCartCount($event)"
    />
    <view v-else class="cart-page" data-testid="cart-unavailable">
      <text>购物车功能尚未开通</text>
    </view>
    <RootAppTabBar active="CART" />
  </view>
</template>

<style scoped>
.cart-page {
  display: flex;
  min-height: 100vh;
  padding-bottom: var(--app-root-tabbar-content-offset);
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  box-sizing: border-box;
  font-size: 14px;
}
</style>
