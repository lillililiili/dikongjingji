<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildAddressBookUrl, buildMallProductUrl } from '@/features/mall/navigation/mall-routes'
import { RENTAL_PREVIEW_ENABLED } from '@/features/rental/application/rental-preview'
import {
  buildRentalInquiryUrl,
  buildRentalQuoteUrl,
  parseRentalInquiryTarget,
  type RentalInquiryTarget,
} from '@/features/rental/navigation/rental-routes'
import RentalInquiryPage from '@/features/rental/presentation/inquiry/RentalInquiryPage.vue'
import RentalUnavailable from '@/features/rental/presentation/shared/RentalUnavailable.vue'

const target = ref<RentalInquiryTarget | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Opened from a link, back is the device's product page.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  if (target.value) uni.redirectTo({ url: buildMallProductUrl(target.value.productId, target.value.cityCode) })
  else openHomeChannel('MALL')
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(target.value ? buildRentalInquiryUrl(target.value) : '/pages/home/index') })
}

// The inquiry exists now: its quote page replaces the form, so going back never sends it twice.
function openQuote(inquiryId: string): void {
  uni.redirectTo({ url: buildRentalQuoteUrl(inquiryId) })
}

onLoad((query) => {
  target.value = parseRentalInquiryTarget(query)
})

// Every return reads the address book again: 管理地址 may have added or changed one.
onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})

function open(url: string): void {
  uni.navigateTo({ url })
}
</script>

<template>
  <RentalInquiryPage
    v-if="RENTAL_PREVIEW_ENABLED && shown"
    :target="target"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @submitted="openQuote"
    @manage-addresses="open(buildAddressBookUrl(false))"
    @browse="openHomeChannel('MALL')"
  />
  <RentalUnavailable v-else-if="!RENTAL_PREVIEW_ENABLED" />
</template>
