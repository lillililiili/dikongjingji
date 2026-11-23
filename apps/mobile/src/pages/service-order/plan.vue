<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildProviderPageUrl } from '@/features/proservice/navigation/proservice-routes'
import { SERVICE_ORDER_PREVIEW_ENABLED } from '@/features/serviceorder/application/serviceorder-preview'
import {
  SERVICE_ORDER_DETAIL_PATH,
  buildServiceOrderDetailUrl,
  buildServiceOrderPlanUrl,
  parseServiceOrderId,
} from '@/features/serviceorder/navigation/service-order-routes'
import ServiceOrderPlanPage from '@/features/serviceorder/presentation/plan/ServiceOrderPlanPage.vue'
import ServiceOrderUnavailable from '@/features/serviceorder/presentation/shared/ServiceOrderUnavailable.vue'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const orderId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function previousRoute(): string | null {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2]
  return previous ? `/${previous.route}` : null
}

// Opened alone (a link, a login), back is the order page.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  if (orderId.value) uni.redirectTo({ url: buildServiceOrderDetailUrl(orderId.value) })
}

// 查看订单: back to the order page when it is right underneath, else it replaces this page.
function showDetail(id: string): void {
  if (previousRoute() === SERVICE_ORDER_DETAIL_PATH) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildServiceOrderDetailUrl(id) })
}

function openLogin(): void {
  if (orderId.value) uni.redirectTo({ url: buildInspectionLoginUrl(buildServiceOrderPlanUrl(orderId.value)) })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

onLoad((query) => {
  orderId.value = parseServiceOrderId(query)
})

// Every return reads the order again: the provider may revise the plan; the payment may have gone
// through on the cashier.
onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <ServiceOrderPlanPage
    v-if="SERVICE_ORDER_PREVIEW_ENABLED && shown"
    :order-id="orderId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @pay="(tradeOrderId) => open(buildCashierUrl(tradeOrderId))"
    @detail="showDetail"
    @provider="(providerId, cityCode) => open(buildProviderPageUrl(providerId, cityCode))"
  />
  <ServiceOrderUnavailable v-else-if="!SERVICE_ORDER_PREVIEW_ENABLED" />
</template>
