<script setup lang="ts">
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { MALL_PREVIEW_ENABLED } from '@/features/mall/application/mall-preview'
import { parseMallMerchantTarget } from '@/features/mall/navigation/mall-routes'
import MallMerchantPage from '@/features/mall/presentation/merchant/MallMerchantPage.vue'

// 商家主页（S5-2，画板 Merchant）。只收商家 ID 和城市；公开构建不出现（商城还没公开）。
const merchantId = ref('')
const cityCode = ref('')
const reachBottomKey = ref(0)
const loaded = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('MALL')
}

function open(url: string): void {
  uni.navigateTo({ url })
}

onLoad((query) => {
  const target = parseMallMerchantTarget(query)
  merchantId.value = target?.merchantId ?? ''
  cityCode.value = target?.cityCode ?? ''
  loaded.value = true
})

onReachBottom(() => {
  reachBottomKey.value += 1
})
</script>

<template>
  <MallMerchantPage
    v-if="MALL_PREVIEW_ENABLED && loaded"
    :merchant-id="merchantId"
    :city-code="cityCode"
    :reach-bottom-key="reachBottomKey"
    @back="goBack"
    @open="open"
  />
  <view v-else-if="!MALL_PREVIEW_ENABLED" class="merchant-unavailable" data-testid="mall-merchant-unavailable">
    <text>商家主页尚未开通</text>
  </view>
</template>

<style scoped>
.merchant-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
