<script setup lang="ts">
/**
 * 一张售后凭证缩略图：图片不公开，带登录令牌取回二进制，转成 blob 地址显示（照 core/ops/MediaPreview 的做法）；
 * 点开看大图。取不到时显示“图片读取失败”。
 */
import { onBeforeUnmount, ref, watch } from 'vue'

import type { AftersaleApi } from './aftersale-api'

const props = defineProps<{
  api: AftersaleApi
  aftersaleId: string
  imageId: string
}>()

const source = ref('')
const failed = ref(false)
let objectUrl: string | null = null

function release(): void {
  if (objectUrl) URL.revokeObjectURL(objectUrl)
  objectUrl = null
}

watch(() => [props.aftersaleId, props.imageId] as const, async ([aftersaleId, imageId]) => {
  release()
  failed.value = false
  source.value = ''
  try {
    const { blob } = await props.api.image(aftersaleId, imageId)
    if (aftersaleId !== props.aftersaleId || imageId !== props.imageId) return
    objectUrl = URL.createObjectURL(blob)
    source.value = objectUrl
  } catch {
    failed.value = true
  }
}, { immediate: true })

onBeforeUnmount(release)
</script>

<template>
  <div class="aftersale-image">
    <span v-if="failed" class="aftersale-image__empty">图片读取失败</span>
    <el-image v-else-if="source" :src="source" :preview-src-list="[source]" fit="cover" preview-teleported />
    <span v-else class="aftersale-image__empty">读取中…</span>
  </div>
</template>

<style scoped>
.aftersale-image {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 96px;
  height: 96px;
  overflow: hidden;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #f5f7fc;
}

.aftersale-image :deep(.el-image) {
  width: 100%;
  height: 100%;
}

.aftersale-image__empty {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
