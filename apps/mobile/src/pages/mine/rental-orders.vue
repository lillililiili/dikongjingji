<script setup lang="ts">
import { onLoad, onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  MINE_TAB_PATH,
  buildInspectionLoginUrl,
} from '@/features/inspection/navigation/inspection-routes'
import { RENTAL_PREVIEW_ENABLED } from '@/features/rental/application/rental-preview'
import {
  buildMyRentalOrdersUrl,
  buildRentalOrderUrl,
  parseRentalOrderTab,
  type RentalOrderTab,
} from '@/features/rental/navigation/rental-routes'
import RentalOrdersPage from '@/features/rental/presentation/orders/RentalOrdersPage.vue'
import RentalUnavailable from '@/features/rental/presentation/shared/RentalUnavailable.vue'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const initialTab = ref<RentalOrderTab>('ALL')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const reachBottomKey = ref(0)
const shown = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: MINE_TAB_PATH })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildMyRentalOrdersUrl(initialTab.value)) })
}

onLoad((query) => {
  initialTab.value = parseRentalOrderTab(query?.status)
})

// Every return reads the list again: an order may have been paid, handed over or returned meanwhile.
onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})

onReachBottom(() => {
  reachBottomKey.value += 1
})

function open(url: string): void {
  uni.navigateTo({ url })
}
</script>

<template>
  <RentalOrdersPage
    v-if="RENTAL_PREVIEW_ENABLED && shown"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :reach-bottom-key="reachBottomKey"
    :initial-tab="initialTab"
    @back="goBack"
    @login="openLogin"
    @open="(id) => open(buildRentalOrderUrl(id))"
    @pay="(tradeOrderId) => open(buildCashierUrl(tradeOrderId))"
    @browse="openHomeChannel('MALL')"
  />
  <RentalUnavailable v-else-if="!RENTAL_PREVIEW_ENABLED" />
</template>
