<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { useRootTabBadgeStore } from '@/app-shell/state/root-tab-badges'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { cartApi } from '@/features/mall/data/cart-api-client'
import {
  buildBuyNowCheckoutUrl,
  buildMallInquiryUrl,
  buildMallProductUrl,
} from '@/features/mall/navigation/mall-routes'
import type { DeliveryMode } from '@/features/mall/domain/cart'
import type { MallBuyDelivery } from '@/features/mall/presentation/product/buy-delivery'
import MallProductPage from '@/features/mall/presentation/product/MallProductPage.vue'
import { buildRentalInquiryUrl } from '@/features/rental/navigation/rental-routes'
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

function buyNow(offerId: string, quantity: number, delivery: DeliveryMode | null): void {
  if (!getBookingAuthContext().getAccessToken()) {
    openLogin()
    return
  }
  uni.navigateTo({ url: buildBuyNowCheckoutUrl(offerId, quantity, cityCode, delivery) })
}

// 规格与数量 shows the delivery options and fee of the confirm page's preview of this offer; a guest
// (or a failed read) gets null and the sheet shows the merchant's shipping note instead.
async function loadDelivery(offerId: string, quantity: number, mode: DeliveryMode | null): Promise<MallBuyDelivery | null> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token) return null
  try {
    const preview = await cartApi.preview(token, {
      cityCode,
      source: { kind: 'OFFER', offerId, quantity },
      deliveryMode: mode,
      addressId: null,
    })
    return { options: preview.deliveryOptions, mode: preview.deliveryMode, shippingMinor: preview.amounts.shippingMinor }
  } catch {
    return null
  }
}

// The form asks for a login itself and comes back to itself.
function rent(rentProductId: string, offerId: string): void {
  uni.navigateTo({ url: buildRentalInquiryUrl({ offerId, productId: rentProductId, cityCode }) })
}

// The inquiry form of an inquiry product (F4) also asks for a login itself.
function inquire(inquiryProductId: string): void {
  uni.navigateTo({ url: buildMallInquiryUrl({ productId: inquiryProductId, cityCode }) })
}

onLoad((query) => {
  productId = typeof query?.productId === 'string' ? query.productId : ''
  cityCode = typeof query?.cityCode === 'string' ? query.cityCode : ''
})
</script>

<template>
  <MallProductPage
    :busy="busy"
    :load-delivery="loadDelivery"
    @add-to-cart="addToCart"
    @buy-now="buyNow"
    @rent="rent"
    @inquire="inquire"
  />
</template>
