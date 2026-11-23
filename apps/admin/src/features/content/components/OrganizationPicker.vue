<script setup lang="ts">
/**
 * 选一家服务商类机构（作者、专题发布方、发布商家）：按名称远程搜运营端机构列表（只列服务商类，商家也是这一类）。
 * v-model 是机构 ID；选中后另发 picked 带回名称，编辑已有内容时用 label 显示原来的名称。
 */
import { computed, ref } from 'vue'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { createOrganizationApi, type OpsOrganization } from '@/features/organization/organization-api'

const props = defineProps<{
  modelValue: string
  label?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  picked: [organization: { organizationId: string, name: string } | null]
}>()

const api = createOrganizationApi(http)
const options = ref<OpsOrganization[]>([])
const loading = ref(false)
const failure = ref('')

const shown = computed(() => {
  const list = options.value.map((option) => ({ value: option.organizationId, label: option.name }))
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
    options.value = page.items
  } catch (error) {
    failure.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function change(value: string | undefined): void {
  const id = value ?? ''
  emit('update:modelValue', id)
  const found = shown.value.find((option) => option.value === id)
  emit('picked', found ? { organizationId: found.value, name: found.label } : null)
}
</script>

<template>
  <div class="organization-picker">
    <el-select
      :model-value="modelValue || undefined"
      filterable
      remote
      clearable
      :remote-method="search"
      :loading="loading"
      :disabled="disabled"
      placeholder="输入机构名称搜索"
      style="width: 320px"
      @update:model-value="change"
      @visible-change="(open: boolean) => open && options.length === 0 && search('')"
    >
      <el-option v-for="option in shown" :key="option.value" :label="option.label" :value="option.value" />
    </el-select>
    <span v-if="failure" class="organization-picker__error">{{ failure }}</span>
  </div>
</template>

<style scoped>
.organization-picker {
  display: flex;
  align-items: center;
  gap: 8px;
}

.organization-picker__error {
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
