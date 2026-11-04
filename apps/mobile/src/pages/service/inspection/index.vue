<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'

onLoad(() => {
  openHomeChannel('SERVICE')
})
</script>

<template>
  <view />
</template>
