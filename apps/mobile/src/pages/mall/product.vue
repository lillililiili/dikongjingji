<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { useRootTabBadgeStore } from '@/app-shell/state/root-tab-badges'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { cartApi } from '@/features/mall/data/cart-api-client'
import {
  buildBuyNowCheckoutUrl,
  buildMallProductUrl,
} from '@/features/mall/navigation/mall-routes'
import MallProductPage from '@/features/mall/presentation/product/MallProductPage.vue'
import { ApiRequestError } from '@/shared/http/public-api-request'

const badgeStore = useRootTabBadgeStore()
const busy = ref(false)
let productId = ''
let cityCode = ''

// Signing in comes back to this product; the sheet has to be opened again.
function openLogin(): void {
  uni.navigateTo({ url: buildInspectionLoginUrl(buildMallProductUrl(productId, cityCode)) })
}

async function addToCart(offerId: string, quantity: number): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token) {
    openLogin()
    return
  }
  if (busy.value) return
  busy.value = true
  try {
    const added = await cartApi.add(token, { offerId, quantity, cityCode })
    badgeStore.setCartCount(added.cartCount)
    uni.showToast({ title: '已加入购物车', icon: 'success' })
  } catch (error) {
    if (error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')) {
      openLogin()
      return
    }
    uni.showToast({
      title: error instanceof ApiRequestError ? error.message : '没有加入成功，请重试',
      icon: 'none',
    })
  } finally {
    busy.value = false
  }
}

function buyNow(offerId: string, quantity: number): void {
  if (!getBookingAuthContext().getAccessToken()) {
    openLogin()
    return
  }
  uni.navigateTo({ url: buildBuyNowCheckoutUrl(offerId, quantity, cityCode) })
}

onLoad((query) => {
  productId = typeof query?.productId === 'string' ? query.productId : ''
  cityCode = typeof query?.cityCode === 'string' ? query.cityCode : ''
})
</script>

<template>
  <MallProductPage :busy="busy" @add-to-cart="addToCart" @buy-now="buyNow" />
</template>
