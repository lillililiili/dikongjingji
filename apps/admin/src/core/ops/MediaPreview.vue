<script setup lang="ts">
/**
 * 素材缩略图：本平台上传的（地址末尾 /ops-media/<文件名>）带登录取回再显示，开发环境的地址打不开也能预览；
 * 别的受控地址直接显示。取不到时显示“预览不可用”，不影响保存。
 */
import { onBeforeUnmount, ref, watch } from 'vue'

import { http } from '@/core/http'

import { createMediaApi, uploadedFileName, type MediaApi, type OpsMediaKind } from './media-api'

const props = withDefaults(defineProps<{
  url: string
  kind?: OpsMediaKind
  width?: number
  height?: number
  api?: MediaApi
}>(), { kind: 'IMAGE', width: 120, height: 80, api: undefined })

const source = ref('')
const failed = ref(false)
let objectUrl: string | null = null

function release(): void {
  if (objectUrl) URL.revokeObjectURL(objectUrl)
  objectUrl = null
}

watch(() => props.url, async (url) => {
  release()
  failed.value = false
  source.value = ''
  if (!url) return
  const fileName = uploadedFileName(url)
  if (!fileName) {
    source.value = url
    return
  }
  try {
    const { blob } = await (props.api ?? createMediaApi(http)).preview(fileName)
    if (url !== props.url) return
    objectUrl = URL.createObjectURL(blob)
    source.value = objectUrl
  } catch {
    failed.value = true
  }
}, { immediate: true })

onBeforeUnmount(release)
</script>

<template>
  <div class="media-preview" :style="{ width: `${width}px`, height: `${height}px` }">
    <span v-if="failed || !url" class="media-preview__empty">{{ url ? '预览不可用' : '未设置' }}</span>
    <video v-else-if="kind === 'VIDEO' && source" :src="source" controls preload="metadata" />
    <img v-else-if="source" :src="source" alt="" @error="failed = true">
  </div>
</template>

<style scoped>
.media-preview {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #f5f7fc;
}

.media-preview img,
.media-preview video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.media-preview__empty {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
