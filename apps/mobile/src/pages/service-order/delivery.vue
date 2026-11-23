<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { SERVICE_ORDER_PREVIEW_ENABLED } from '@/features/serviceorder/application/serviceorder-preview'
import {
  SERVICE_ORDER_DETAIL_PATH,
  buildServiceOrderDeliveryUrl,
  buildServiceOrderDetailUrl,
  parseServiceOrderId,
} from '@/features/serviceorder/navigation/service-order-routes'
import ServiceOrderDeliveryPage from '@/features/serviceorder/presentation/delivery/ServiceOrderDeliveryPage.vue'
import ServiceOrderUnavailable from '@/features/serviceorder/presentation/shared/ServiceOrderUnavailable.vue'

const orderId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function previousRoute(): string | null {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2]
  return previous ? `/${previous.route}` : null
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  if (orderId.value) uni.redirectTo({ url: buildServiceOrderDetailUrl(orderId.value) })
}

function showDetail(id: string): void {
  if (previousRoute() === SERVICE_ORDER_DETAIL_PATH) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildServiceOrderDetailUrl(id) })
}

function openLogin(): void {
  if (orderId.value) uni.redirectTo({ url: buildInspectionLoginUrl(buildServiceOrderDeliveryUrl(orderId.value)) })
}

onLoad((query) => {
  orderId.value = parseServiceOrderId(query)
})

// Every return reads the order again: the provider may deliver again after a rework.
onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <ServiceOrderDeliveryPage
    v-if="SERVICE_ORDER_PREVIEW_ENABLED && shown"
    :order-id="orderId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @detail="showDetail"
  />
  <ServiceOrderUnavailable v-else-if="!SERVICE_ORDER_PREVIEW_ENABLED" />
</template>
