<script setup lang="ts">
/**
 * 选一家服务商机构（巡检、专业服务目录的“服务商”）：按名称远程搜机构与资质里的服务商。v-model 是机构 ID；
 * 编辑已有服务时传 currentName，下拉没加载前也能显示名称。
 */
import { onMounted, ref, watch } from 'vue'

import { http } from '@/core/http'
import { showError } from '@/core/ui/feedback'

import { createOrganizationApi, type OpsOrganization } from './organization-api'

const props = defineProps<{
  modelValue: string
  currentName?: string | null
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const api = createOrganizationApi(http)
const options = ref<Array<{ value: string, label: string }>>([])
const loading = ref(false)
let revision = 0

function remember(id: string, name: string | null | undefined): void {
  if (id && name && !options.value.some((option) => option.value === id)) {
    options.value = [{ value: id, label: name }, ...options.value]
  }
}

async function search(keyword: string): Promise<void> {
  const mine = ++revision
  loading.value = true
  try {
    const page = await api.list({ keyword, type: 'PROVIDER', qualificationStatus: '' }, 1, 20)
    if (mine !== revision) return
    options.value = page.items.map((organization: OpsOrganization) => ({
      value: organization.organizationId,
      label: `${organization.name}${organization.status === 'ACTIVE' ? '' : '（已停用）'}`,
    }))
    remember(props.modelValue, props.currentName)
  } catch (failure) {
    if (mine === revision) showError(failure)
  } finally {
    if (mine === revision) loading.value = false
  }
}

watch(() => [props.modelValue, props.currentName] as const, ([id, name]) => remember(id, name))

onMounted(() => {
  remember(props.modelValue, props.currentName)
  void search('')
})
</script>

<template>
  <el-select
    :model-value="modelValue || undefined"
    filterable
    remote
    :remote-method="search"
    :loading="loading"
    :disabled="disabled"
    placeholder="输入服务商名称搜索"
    style="width: 100%"
    @update:model-value="(value: string) => emit('update:modelValue', value ?? '')"
  >
    <el-option v-for="option in options" :key="option.value" :value="option.value" :label="option.label" />
  </el-select>
</template>
