<script setup lang="ts">
/**
 * 选多个城市（巡检覆盖城市、专业服务上架城市）：城市来自平台城市目录（公开接口），按名称搜。v-model 是城市编码数组，
 * 顺序就是选的顺序（巡检的第一个是主城市）。
 */
import { computed, onMounted, ref } from 'vue'

import { http } from '@/core/http'
import { showError } from '@/core/ui/feedback'

import { createOrganizationApi, type DirectoryCity } from './organization-api'

const props = defineProps<{
  modelValue: string[]
  /** 已选城市的名称（目录还没加载、或城市已不在目录里时用）。 */
  knownNames?: Readonly<Record<string, string | null>>
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
}>()

const cities = ref<DirectoryCity[]>([])
const loading = ref(false)

const options = computed(() => {
  const listed = cities.value.map((city) => ({ value: city.code, label: `${city.name}（${city.provinceName}）` }))
  const missing = props.modelValue.filter((code) => !cities.value.some((city) => city.code === code))
    .map((code) => ({ value: code, label: props.knownNames?.[code] ?? code }))
  return [...missing, ...listed]
})

onMounted(async () => {
  loading.value = true
  try {
    cities.value = await createOrganizationApi(http).cities()
  } catch (failure) {
    showError(failure)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <el-select
    :model-value="modelValue"
    multiple
    filterable
    :loading="loading"
    :disabled="disabled"
    placeholder="输入城市名搜索"
    style="width: 100%"
    @update:model-value="(value: string[]) => emit('update:modelValue', value)"
  >
    <el-option v-for="option in options" :key="option.value" :value="option.value" :label="option.label" />
  </el-select>
</template>
