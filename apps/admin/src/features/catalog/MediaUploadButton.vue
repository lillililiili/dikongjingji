<script setup lang="ts">
import type { UploadRawFile } from 'element-plus'
import { ref } from 'vue'

import { showError } from '@/core/ui/feedback'

import { MEDIA_TYPES, mediaProblem, type CatalogApi } from './catalog-api'

/** 上传一张商品图片（JPEG、PNG、WebP，≤5 MB），拿回受控地址交给父组件。 */
const props = withDefaults(defineProps<{
  api: CatalogApi
  label?: string
  disabled?: boolean
}>(), { label: '上传图片', disabled: false })

const emit = defineEmits<{
  uploaded: [url: string]
}>()

const busy = ref(false)

async function upload(raw: UploadRawFile): Promise<boolean> {
  const problem = mediaProblem(raw)
  if (problem) {
    showError(new Error(problem))
    return false
  }
  busy.value = true
  try {
    const media = await props.api.uploadMedia(raw)
    emit('uploaded', media.url)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
  // 已经自己上传了，不让 el-upload 再传一次。
  return false
}
</script>

<template>
  <el-upload :show-file-list="false" :accept="MEDIA_TYPES.join(',')" :before-upload="upload"
             :disabled="disabled || busy">
    <el-button :loading="busy" :disabled="disabled">{{ label }}</el-button>
  </el-upload>
</template>
