<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { useAddressChoiceStore } from '@/features/account/state/address-choice'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import type { CheckoutSource } from '@/features/mall/domain/cart'
import { CART_PREVIEW_ENABLED } from '@/features/mall/application/cart-preview'
import MallUnavailable from '@/features/mall/presentation/shared/MallUnavailable.vue'
import { buildMyDemandsUrl } from '@/features/demand/navigation/demand-routes'
import {
  CART_TAB_PATH,
  MALL_CHECKOUT_PATH,
  MALL_INQUIRY_QUOTES_PATH,
  buildAddressBookUrl,
  buildBuyNowCheckoutUrl,
  buildCartCheckoutUrl,
  buildMallInquiryQuotesUrl,
  buildQuoteCheckoutUrl,
} from '@/features/mall/navigation/mall-routes'
import MallCheckoutPage from '@/features/mall/presentation/checkout/MallCheckoutPage.vue'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const addressChoice = useAddressChoiceStore()
const source = ref<CheckoutSource | null>(null)
const cityCode = ref('')
// Quote mode (F4): the inquiry whose chosen quote is being ordered.
const inquiryId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
// The delivery mode picked on 规格与数量 (buy now only); the page starts from it when it can.
const initialDelivery = ref<'EXPRESS' | 'PICKUP' | null>(null)
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

function previousRoute(): string | null {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2]
  return previous ? `/${previous.route}` : null
}

// A quote that cannot be ordered leads back to its quotes page, which reads the inquiry again.
function backToQuotes(): void {
  if (previousRoute() === MALL_INQUIRY_QUOTES_PATH) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: inquiryId.value ? buildMallInquiryQuotesUrl(inquiryId.value) : buildMyDemandsUrl() })
}

// The order exists now: the cashier replaces this page, so going back never resubmits it.
function openCashier(orderId: string): void {
  uni.redirectTo({ url: buildCashierUrl(orderId) })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(returnUrl) })
}

onLoad((query) => {
  if (!CART_PREVIEW_ENABLED) return
  cityCode.value = decode(query?.cityCode)
  const items = decode(query?.items).split(',').map((id) => id.trim()).filter(Boolean)
  const offerId = decode(query?.offerId)
  const quantity = Number(decode(query?.quantity))
  const inquiryQuoteId = decode(query?.inquiryQuoteId)
  if (inquiryQuoteId) {
    inquiryId.value = decode(query?.inquiryId) || null
    source.value = { kind: 'QUOTE', inquiryQuoteId }
    returnUrl = buildQuoteCheckoutUrl(inquiryQuoteId, inquiryId.value ?? '', cityCode.value)
  } else if (items.length > 0) {
    source.value = { kind: 'CART', cartItemIds: items }
    returnUrl = buildCartCheckoutUrl(items, cityCode.value)
  } else if (offerId && Number.isInteger(quantity) && quantity > 0) {
    source.value = { kind: 'OFFER', offerId, quantity }
    const delivery = decode(query?.delivery)
    initialDelivery.value = delivery === 'EXPRESS' || delivery === 'PICKUP' ? delivery : null
    returnUrl = buildBuyNowCheckoutUrl(offerId, quantity, cityCode.value, initialDelivery.value)
  }
  // A new confirm page starts from the default address.
  addressChoice.choose(null)
})

onShow(() => {
  if (!CART_PREVIEW_ENABLED) return
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <MallCheckoutPage
    v-if="CART_PREVIEW_ENABLED && shown"
    :source="source"
    :city-code="cityCode"
    :address-id="addressChoice.addressId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :initial-delivery="initialDelivery"
    @back="goBack"
    @login="openLogin"
    @choose-address="chooseAddress"
    @back-to-cart="backToCart"
    @back-to-quotes="backToQuotes"
    @address-gone="addressChoice.choose(null)"
    @submitted="openCashier"
  />
  <MallUnavailable v-else-if="!CART_PREVIEW_ENABLED" test-id="checkout-unavailable" message="确认订单尚未开通" />
</template>
