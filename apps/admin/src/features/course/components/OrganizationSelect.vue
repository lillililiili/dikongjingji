<script setup lang="ts">
/**
 * 开课机构：按名称远程搜运营端机构列表（机构与资质的接口，只列在用的经营机构）。v-model 是机构 ID；
 * 已有课程的机构（可能是平台机构，列表里搜不到）用 label 显示原来的名称。
 */
import { computed, ref } from 'vue'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { createOrganizationApi } from '@/features/organization/organization-api'

const props = defineProps<{
  modelValue: string
  label?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const api = createOrganizationApi(http)
const options = ref<Array<{ value: string, label: string }>>([])
const loading = ref(false)
const failure = ref('')

const shown = computed(() => {
  const list = [...options.value]
  if (props.modelValue && !list.some((option) => option.value === props.modelValue)) {
    list.unshift({ value: props.modelValue, label: props.label || props.modelValue })
  }
  return list
})

async function search(keyword: string): Promise<void> {
  loading.value = true
  failure.value = ''
  try {
    const page = await api.list({ keyword, type: 'PROVIDER', qualificationStatus: '' }, 1, 20)
    options.value = page.items.filter((item) => item.status === 'ACTIVE')
      .map((item) => ({ value: item.organizationId, label: item.name }))
  } catch (error) {
    failure.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="course-picker">
    <el-select
      :model-value="modelValue || undefined"
      filterable
      remote
      :remote-method="search"
      :loading="loading"
      :disabled="disabled"
      placeholder="输入机构名称搜索"
      style="width: 320px"
      @update:model-value="(value: string | undefined) => emit('update:modelValue', value ?? '')"
      @visible-change="(open: boolean) => open && options.length === 0 && search('')"
    >
      <el-option v-for="option in shown" :key="option.value" :label="option.label" :value="option.value" />
    </el-select>
    <span v-if="failure" class="course-picker__error">{{ failure }}</span>
  </div>
</template>

<style scoped>
.course-picker {
  display: flex;
  align-items: center;
  gap: 8px;
}

.course-picker__error {
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
