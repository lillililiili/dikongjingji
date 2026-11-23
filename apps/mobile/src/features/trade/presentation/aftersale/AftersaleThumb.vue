<script setup lang="ts">
import { ref, watch } from 'vue'

import { aftersaleApi } from '../../data/aftersale-api-client'

// 一张凭证图片的缩略图。图片不公开，要带登录令牌读回来（GET /api/v1/trade/aftersale-images/{imageId}）；
// 读不到时用刚选的本地文件（有的话），都没有就是灰底。
const props = defineProps<{
  imageId: string
  accessToken: string | null
  fallback?: string | null
  size?: number
}>()

const source = ref<string | null>(null)
let version = 0

async function load(): Promise<void> {
  const requested = ++version
  source.value = null
  if (!props.accessToken) {
    source.value = props.fallback ?? null
    return
  }
  try {
    const loaded = await aftersaleApi.imageSource(props.accessToken, props.imageId)
    if (requested === version) source.value = loaded
  } catch {
    if (requested === version) source.value = props.fallback ?? null
  }
}

watch(() => [props.imageId, props.accessToken], () => { void load() }, { immediate: true })
</script>

<template>
  <image
    v-if="source"
    class="aftersale-thumb"
    :style="{ width: `${size ?? 64}px`, height: `${size ?? 64}px` }"
    :src="source"
    mode="aspectFill"
    :data-testid="`aftersale-thumb-${imageId}`"
  />
  <view
    v-else
    class="aftersale-thumb aftersale-thumb--empty"
    :style="{ width: `${size ?? 64}px`, height: `${size ?? 64}px` }"
    :data-testid="`aftersale-thumb-${imageId}`"
  />
</template>

<style scoped>
.aftersale-thumb {
  display: block;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}
</style>
