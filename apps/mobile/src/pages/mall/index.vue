<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { MALL_PREVIEW_ENABLED } from '@/features/mall/application/mall-preview'

onLoad(() => {
  openHomeChannel(MALL_PREVIEW_ENABLED ? 'MALL' : 'RECOMMEND')
})
</script>

<template>
  <view />
</template>
