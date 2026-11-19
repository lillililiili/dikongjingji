<script setup lang="ts">
import { onLoad, onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { DEMAND_PREVIEW_ENABLED } from '@/features/demand/application/demand-preview'
import {
  buildDemandPublishUrl,
  buildDemandQuotesUrl,
  buildMyDemandsUrl,
  parseDemandListFilter,
  type DemandListFilter,
} from '@/features/demand/navigation/demand-routes'
import DemandListPage from '@/features/demand/presentation/list/DemandListPage.vue'
import DemandUnavailable from '@/features/demand/presentation/shared/DemandUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  MINE_TAB_PATH,
  buildInspectionLoginUrl,
} from '@/features/inspection/navigation/inspection-routes'
import {
  buildMallInquiryQuotesUrl,
  buildMallInquiryUrl,
} from '@/features/mall/navigation/mall-routes'
import {
  buildRentalInquiryUrl,
  buildRentalOrderUrl,
  buildRentalQuoteUrl,
} from '@/features/rental/navigation/rental-routes'
import { buildGoodsOrderUrl } from '@/features/trade/navigation/trade-routes'

const initialFilter = ref<DemandListFilter>('ALL')
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
  uni.redirectTo({ url: buildInspectionLoginUrl(buildMyDemandsUrl(initialFilter.value)) })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

onLoad((query) => {
  initialFilter.value = parseDemandListFilter(query?.status)
})

// Every return reads the list again: a demand may have been sent, chosen or cancelled meanwhile, an
// inquiry quoted, chosen or confirmed.
onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})

onReachBottom(() => {
  reachBottomKey.value += 1
})
</script>

<template>
  <DemandListPage
    v-if="DEMAND_PREVIEW_ENABLED && shown"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :reach-bottom-key="reachBottomKey"
    :initial-filter="initialFilter"
    @back="goBack"
    @login="openLogin"
    @publish="open(buildDemandPublishUrl())"
    @edit="(demandId) => open(buildDemandPublishUrl({ demandId }))"
    @view="(demandId) => open(buildDemandPublishUrl({ demandId }))"
    @quotes="(demandId) => open(buildDemandQuotesUrl(demandId))"
    @republish="(demandId) => open(buildDemandPublishUrl({ from: demandId }))"
    @rental-quote="(inquiryId) => open(buildRentalQuoteUrl(inquiryId))"
    @rental-order="(rentalOrderId) => open(buildRentalOrderUrl(rentalOrderId))"
    @reinquire="(target) => open(buildRentalInquiryUrl(target))"
    @mall-quotes="(inquiryId) => open(buildMallInquiryQuotesUrl(inquiryId))"
    @goods-order="(orderId) => open(buildGoodsOrderUrl(orderId))"
    @mall-reinquire="(target) => open(buildMallInquiryUrl(target))"
  />
  <DemandUnavailable v-else-if="!DEMAND_PREVIEW_ENABLED" />
</template>
