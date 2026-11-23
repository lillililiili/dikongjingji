<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { MY_ORDER_SEARCH_PATH } from '@/features/orders/application/my-order-search-route'
import { buildMyOrdersUrl } from '@/features/orders/application/my-orders-route'
import MyOrderSearchPage from '@/features/orders/presentation/MyOrderSearchPage.vue'
import MyOrdersUnavailable from '@/features/orders/presentation/MyOrdersUnavailable.vue'
import { buildSupportOpenUrl } from '@/features/support/navigation/support-routes'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import {
  ORDER_RECENT_SEARCH_KEY,
  createRecentSearches,
  uniSearchWordStorage,
} from '@/shared/search/recent-searches'

const recentSearches = createRecentSearches(uniSearchWordStorage, ORDER_RECENT_SEARCH_KEY)
const accessToken = ref<string | null>(null)
const recent = ref<string[]>(recentSearches.load())
const shown = ref(false)

// Board OrderSearch: 返回 and 查看全部订单 go back to 我的订单.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildMyOrdersUrl({ tab: 'all', chip: null }) })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(MY_ORDER_SEARCH_PATH) })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

function remember(word: string): void {
  recent.value = recentSearches.add(word)
}

function clearRecent(): void {
  recentSearches.clear()
  recent.value = []
}

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  shown.value = true
})
</script>

<template>
  <MyOrderSearchPage
    v-if="TRADE_PREVIEW_ENABLED && shown"
    :access-token="accessToken"
    :recent="recent"
    @back="goBack"
    @login="openLogin"
    @open="open"
    @remember="remember"
    @clear-recent="clearRecent"
    @support="open(buildSupportOpenUrl({ kind: 'PLATFORM' }))"
  />
  <MyOrdersUnavailable v-else-if="!TRADE_PREVIEW_ENABLED" />
</template>
