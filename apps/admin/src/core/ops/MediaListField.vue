<script setup lang="ts">
/**
 * 一组图片地址（活动图集等）：按顺序显示，能上移、下移、删除，最多 max 张；新加一张用 MediaField 上传或粘贴地址。
 * v-model 是地址数组。
 */
import { ref } from 'vue'

import MediaField from './MediaField.vue'
import MediaPreview from './MediaPreview.vue'
import type { MediaApi } from './media-api'

const props = withDefaults(defineProps<{
  modelValue: string[]
  max: number
  api?: MediaApi
  disabled?: boolean
}>(), { api: undefined, disabled: false })

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
}>()

const adding = ref('')

function add(url: string): void {
  adding.value = ''
  if (!url || props.modelValue.includes(url) || props.modelValue.length >= props.max) return
  emit('update:modelValue', [...props.modelValue, url])
}

function remove(index: number): void {
  emit('update:modelValue', props.modelValue.filter((_, position) => position !== index))
}

function move(index: number, offset: number): void {
  const next = [...props.modelValue]
  const target = index + offset
  if (target < 0 || target >= next.length) return
  ;[next[index], next[target]] = [next[target] as string, next[index] as string]
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="media-list">
    <div v-for="(url, index) in modelValue" :key="url" class="media-list__item">
      <MediaPreview :url="url" :api="api" :width="96" :height="64" />
      <span class="media-list__order">第 {{ index + 1 }} 张</span>
      <el-button size="small" link :disabled="disabled || index === 0" @click="move(index, -1)">上移</el-button>
      <el-button size="small" link :disabled="disabled || index === modelValue.length - 1" @click="move(index, 1)">下移</el-button>
      <el-button size="small" link type="danger" :disabled="disabled" @click="remove(index)">删除</el-button>
    </div>
    <MediaField
      v-if="modelValue.length < max"
      :model-value="adding"
      :api="api"
      :disabled="disabled"
      @update:model-value="add"
    />
    <span class="media-list__hint">{{ modelValue.length }} / {{ max }} 张</span>
  </div>
</template>

<style scoped>
.media-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.media-list__item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.media-list__order,
.media-list__hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
