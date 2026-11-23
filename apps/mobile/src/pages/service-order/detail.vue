<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildMyOrdersUrl } from '@/features/orders/application/my-orders-route'
import { buildProviderPageUrl } from '@/features/proservice/navigation/proservice-routes'
import { SERVICE_ORDER_PREVIEW_ENABLED } from '@/features/serviceorder/application/serviceorder-preview'
import {
  buildServiceOrderDeliveryUrl,
  buildServiceOrderDetailUrl,
  buildServiceOrderPlanUrl,
  parseServiceOrderId,
} from '@/features/serviceorder/navigation/service-order-routes'
import ServiceOrderDetailPage from '@/features/serviceorder/presentation/detail/ServiceOrderDetailPage.vue'
import ServiceOrderUnavailable from '@/features/serviceorder/presentation/shared/ServiceOrderUnavailable.vue'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const orderId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Opened from the pay result, a choice or a link, back is 我的订单 → 服务.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildMyOrdersUrl({ tab: 'service' }) })
}

function openLogin(): void {
  uni.redirectTo({
    url: buildInspectionLoginUrl(orderId.value ? buildServiceOrderDetailUrl(orderId.value) : buildMyOrdersUrl({ tab: 'service' })),
  })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

onLoad((query) => {
  orderId.value = parseServiceOrderId(query)
})

// Every return reads the order again: the provider issues the plan, starts and delivers meanwhile.
onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <ServiceOrderDetailPage
    v-if="SERVICE_ORDER_PREVIEW_ENABLED && shown"
    :order-id="orderId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @plan="(id) => open(buildServiceOrderPlanUrl(id))"
    @delivery="(id) => open(buildServiceOrderDeliveryUrl(id))"
    @pay="(tradeOrderId) => open(buildCashierUrl(tradeOrderId))"
    @provider="(providerId, cityCode) => open(buildProviderPageUrl(providerId, cityCode))"
  />
  <ServiceOrderUnavailable v-else-if="!SERVICE_ORDER_PREVIEW_ENABLED" />
</template>
