<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { MALL_PREVIEW_ENABLED } from '@/features/mall/application/mall-preview'
import {
  buildMallInquiryQuotesUrl,
  buildMallInquiryUrl,
  buildMallProductUrl,
  parseMallInquiryTarget,
  type MallInquiryTarget,
} from '@/features/mall/navigation/mall-routes'
import MallInquiryPage from '@/features/mall/presentation/inquiry/MallInquiryPage.vue'
import MallUnavailable from '@/features/mall/presentation/shared/MallUnavailable.vue'

const target = ref<MallInquiryTarget | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Opened from a link, back is the product page.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  if (target.value) uni.redirectTo({ url: buildMallProductUrl(target.value.productId, target.value.cityCode) })
  else openHomeChannel('MALL')
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(target.value ? buildMallInquiryUrl(target.value) : '/pages/home/index') })
}

// The inquiry exists now: its quotes page replaces the form, so going back never sends it twice.
function openQuotes(inquiryId: string): void {
  uni.redirectTo({ url: buildMallInquiryQuotesUrl(inquiryId) })
}

onLoad((query) => {
  target.value = parseMallInquiryTarget(query)
})

// Every return reads the merchants again: one may have stopped selling meanwhile.
onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <MallInquiryPage
    v-if="MALL_PREVIEW_ENABLED && shown"
    :target="target"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @submitted="openQuotes"
    @browse="openHomeChannel('MALL')"
  />
  <MallUnavailable v-else-if="!MALL_PREVIEW_ENABLED" test-id="mall-inquiry-unavailable" message="询价功能尚未开通" />
</template>
