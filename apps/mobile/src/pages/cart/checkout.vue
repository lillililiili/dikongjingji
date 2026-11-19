<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { useAddressChoiceStore } from '@/features/account/state/address-choice'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import type { CheckoutSource } from '@/features/mall/domain/cart'
import {
  CART_TAB_PATH,
  MALL_CHECKOUT_PATH,
  buildAddressBookUrl,
} from '@/features/mall/navigation/mall-routes'
import MallCheckoutPage from '@/features/mall/presentation/checkout/MallCheckoutPage.vue'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const addressChoice = useAddressChoiceStore()
const source = ref<CheckoutSource | null>(null)
const cityCode = ref('')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)
let returnUrl = MALL_CHECKOUT_PATH

function decode(value: unknown): string {
  if (typeof value !== 'string') return ''
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: CART_TAB_PATH })
}

function chooseAddress(): void {
  uni.navigateTo({ url: buildAddressBookUrl(true) })
}

function backToCart(): void {
  uni.switchTab({ url: CART_TAB_PATH })
}

// The order exists now: the cashier replaces this page, so going back never resubmits it.
function openCashier(orderId: string): void {
  uni.redirectTo({ url: buildCashierUrl(orderId) })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(returnUrl) })
}

onLoad((query) => {
  cityCode.value = decode(query?.cityCode)
  const items = decode(query?.items).split(',').map((id) => id.trim()).filter(Boolean)
  const offerId = decode(query?.offerId)
  const quantity = Number(decode(query?.quantity))
  if (items.length > 0) {
    source.value = { kind: 'CART', cartItemIds: items }
    returnUrl = `${MALL_CHECKOUT_PATH}?items=${items.map(encodeURIComponent).join(',')}&cityCode=${encodeURIComponent(cityCode.value)}`
  } else if (offerId && Number.isInteger(quantity) && quantity > 0) {
    source.value = { kind: 'OFFER', offerId, quantity }
    returnUrl = `${MALL_CHECKOUT_PATH}?offerId=${encodeURIComponent(offerId)}&quantity=${quantity}&cityCode=${encodeURIComponent(cityCode.value)}`
  }
  // A new confirm page starts from the default address.
  addressChoice.choose(null)
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <MallCheckoutPage
    v-if="shown"
    :source="source"
    :city-code="cityCode"
    :address-id="addressChoice.addressId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @choose-address="chooseAddress"
    @back-to-cart="backToCart"
    @address-gone="addressChoice.choose(null)"
    @submitted="openCashier"
  />
</template>
