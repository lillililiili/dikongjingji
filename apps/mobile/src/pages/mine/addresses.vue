<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import AddressBookPage from '@/features/account/presentation/AddressBookPage.vue'
import { useAddressChoiceStore } from '@/features/account/state/address-choice'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  MINE_TAB_PATH,
  buildInspectionLoginUrl,
} from '@/features/inspection/navigation/inspection-routes'
import { buildAddressBookUrl } from '@/features/mall/navigation/mall-routes'
import { CART_PREVIEW_ENABLED } from '@/features/mall/application/cart-preview'
import MallUnavailable from '@/features/mall/presentation/shared/MallUnavailable.vue'

const addressChoice = useAddressChoiceStore()
const selecting = ref(false)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: MINE_TAB_PATH })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildAddressBookUrl(selecting.value)) })
}

// Chosen for the order being confirmed: the confirm page reads it when it is shown again.
function choose(addressId: string): void {
  addressChoice.choose(addressId)
  goBack()
}

onLoad((query) => {
  selecting.value = query?.select === '1'
})

onShow(() => {
  if (!CART_PREVIEW_ENABLED) return
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <AddressBookPage
    v-if="CART_PREVIEW_ENABLED && shown"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :selecting="selecting"
    :selected-address-id="addressChoice.addressId"
    @back="goBack"
    @login="openLogin"
    @choose="choose"
  />
  <MallUnavailable v-else-if="!CART_PREVIEW_ENABLED" test-id="address-book-unavailable" message="收货地址尚未开通" />
</template>
