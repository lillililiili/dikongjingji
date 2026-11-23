<script setup lang="ts">
/**
 * 一个素材地址（封面、设备图、视频、正文配图）：上传文件（经 /api/v1/ops/media 存进对象存储，拿回受控 https 地址），
 * 或者粘贴已有的受控 https 地址；可以清掉。v-model 是地址（没有时为空串）。
 */
import { ref } from 'vue'

import { http } from '@/core/http'
import { showError } from '@/core/ui/feedback'

import MediaPreview from './MediaPreview.vue'
import { createMediaApi, isHttpsUrl, mediaProblem, type MediaApi, type OpsMediaKind } from './media-api'

const props = withDefaults(defineProps<{
  modelValue: string
  kind?: OpsMediaKind
  api?: MediaApi
  disabled?: boolean
}>(), { kind: 'IMAGE', api: undefined, disabled: false })

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const uploading = ref(false)
const typed = ref('')
const typedError = ref('')
const input = ref<HTMLInputElement>()

async function upload(event: Event): Promise<void> {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  target.value = ''
  if (!file) return
  const problem = mediaProblem(file, props.kind)
  if (problem) {
    showError(new Error(problem))
    return
  }
  uploading.value = true
  try {
    const media = await (props.api ?? createMediaApi(http)).upload(file, file.name)
    emit('update:modelValue', media.url)
  } catch (failure) {
    showError(failure)
  } finally {
    uploading.value = false
  }
}

function useTyped(): void {
  if (!isHttpsUrl(typed.value)) {
    typedError.value = '只能填带主机名的 https 地址'
    return
  }
  typedError.value = ''
  emit('update:modelValue', typed.value.trim())
  typed.value = ''
}
</script>

<template>
  <div class="media-field">
    <MediaPreview :url="modelValue" :kind="kind" :api="api" :width="kind === 'VIDEO' ? 160 : 120" :height="90" />
    <div class="media-field__controls">
      <div class="media-field__buttons">
        <el-button size="small" type="primary" plain :loading="uploading" :disabled="disabled" @click="input?.click()">
          {{ modelValue ? '重新上传' : '上传' }}{{ kind === 'VIDEO' ? '视频' : '图片' }}
        </el-button>
        <el-button v-if="modelValue" size="small" :disabled="disabled" @click="emit('update:modelValue', '')">清除</el-button>
        <input
          ref="input"
          class="media-field__file"
          type="file"
          :accept="kind === 'VIDEO' ? 'video/mp4' : 'image/jpeg,image/png,image/webp'"
          @change="upload"
        >
      </div>
      <div class="media-field__typed">
        <el-input v-model="typed" size="small" placeholder="或粘贴受控 https 地址" :disabled="disabled" @keyup.enter="useTyped" />
        <el-button size="small" :disabled="disabled || !typed.trim()" @click="useTyped">使用</el-button>
      </div>
      <span v-if="typedError" class="media-field__error">{{ typedError }}</span>
      <span v-else class="media-field__hint">
        {{ kind === 'VIDEO' ? 'MP4，≤50 MB' : 'JPEG、PNG、WebP，≤5 MB' }}<template v-if="modelValue"> · {{ modelValue }}</template>
      </span>
    </div>
  </div>
</template>

<style scoped>
.media-field {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  width: 100%;
}

.media-field__controls {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.media-field__buttons,
.media-field__typed {
  display: flex;
  gap: 8px;
}

.media-field__file {
  display: none;
}

.media-field__hint,
.media-field__error {
  font-size: 12px;
  line-height: 18px;
  word-break: break-all;
  color: var(--admin-text-secondary);
}

.media-field__error {
  color: var(--el-color-danger);
}
</style>
