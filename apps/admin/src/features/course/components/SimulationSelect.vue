<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [id: string] }>()
type Option = { experienceId: string, title: string }
const options = ref<Option[]>([])
const page = ref(0)
const more = ref(true)
const busy = ref(false)
const error = ref('')
const selectedTitle = ref('')
const selectedOutsidePage = computed(() => props.modelValue && !options.value.some(item => item.experienceId === props.modelValue))

watch(() => props.modelValue, async id => {
  selectedTitle.value = ''
  if (!id || !selectedOutsidePage.value) return
  try {
    const item = await http.get<Option>(`/api/v1/experiences/${encodeURIComponent(id)}`)
    if (id === props.modelValue) selectedTitle.value = item.title
  } catch {
    // An unpublished project is still visible by ID so the operator can replace or clear it.
  }
}, { immediate: true })

async function loadNext(): Promise<void> {
  if (busy.value || !more.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await http.get<{ items: Option[], hasMore: boolean }>('/api/v1/experiences', {
      type: 'SIMULATION', page: page.value + 1, size: 50,
    })
    const known = new Set(options.value.map(item => item.experienceId))
    options.value.push(...result.items.filter(item => !known.has(item.experienceId)))
    page.value++
    more.value = result.hasMore
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-select
    :model-value="modelValue || undefined"
    clearable
    placeholder="选择配套仿真项目（可选）"
    style="max-width: 100%; width: 360px"
    data-test="simulation-picker"
    @visible-change="open => open && page === 0 && loadNext()"
    @update:model-value="value => emit('update:modelValue', value ?? '')"
  >
    <el-option v-if="selectedOutsidePage" :label="selectedTitle || modelValue" :value="modelValue" />
    <el-option v-for="item in options" :key="item.experienceId" :label="item.title" :value="item.experienceId" />
    <template #empty><span>{{ error || (busy ? '正在加载仿真项目…' : '暂无已发布的仿真项目') }}</span></template>
    <template v-if="more" #footer>
      <el-button text :loading="busy" @click="loadNext">{{ error ? '重试' : '加载更多仿真项目' }}</el-button>
    </template>
  </el-select>
  <span v-if="error && options.length" role="alert">{{ error }}</span>
</template>
