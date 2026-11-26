<script setup lang="ts">
/** 选一个平台城市目录里的城市（按名称、拼音或编码远程搜运营端城市列表）。v-model 是 6 位城市编码，可以清空。 */
import { computed, ref } from 'vue'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { createCityApi, type OpsCity } from '@/features/city/city-api'

const props = defineProps<{
  modelValue: string
  disabled?: boolean
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const api = createCityApi(http)
const options = ref<OpsCity[]>([])
const loading = ref(false)
const error = ref('')
let revision = 0

const shown = computed(() => {
  const list = options.value.map((city) => ({ value: city.cityCode, label: `${city.cityName}（${city.provinceName}）` }))
  if (props.modelValue && !list.some((option) => option.value === props.modelValue)) {
    list.unshift({ value: props.modelValue, label: props.modelValue })
  }
  return list
})

async function search(keyword: string): Promise<void> {
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    const result = await api.list({ keyword, serviceStatus: '' }, 1, 20)
    if (current === revision) options.value = result.items
  } catch (failure) {
    if (current !== revision) return
    error.value = errorMessage(failure)
    options.value = []
  } finally {
    if (current === revision) loading.value = false
  }
}
</script>

<template>
  <el-select
    :model-value="modelValue || undefined"
    filterable
    remote
    clearable
    :remote-method="search"
    :loading="loading"
    :disabled="disabled"
    :placeholder="placeholder ?? '输入城市名称搜索'"
    style="width: 240px"
    @update:model-value="(value: string | undefined) => emit('update:modelValue', value ?? '')"
    @visible-change="(open: boolean) => open && options.length === 0 && search('')"
  >
    <el-option v-for="option in shown" :key="option.value" :label="option.label" :value="option.value" />
  </el-select>
  <span v-if="error" role="alert">城市目录加载失败：{{ error }}</span>
</template>
