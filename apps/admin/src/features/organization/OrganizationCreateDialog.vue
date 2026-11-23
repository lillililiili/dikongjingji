<script setup lang="ts">
import type { FormInstance } from 'element-plus'
import { reactive, ref, watch } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import {
  ORGANIZATION_TYPE_OPTIONS,
  organizationTypeLabel,
  type DirectoryCity,
  type OpsOrganizationDetail,
  type OrganizationApi,
  type OrganizationType,
} from './organization-api'
import { createOrganizationRules, LIMITS } from './organization-rules'

const props = defineProps<{
  api: OrganizationApi
  open: boolean
}>()

const emit = defineEmits<{
  close: []
  created: [detail: OpsOrganizationDetail]
}>()

const formRef = ref<FormInstance>()
const form = reactive<{ type: OrganizationType, name: string, cityCode: string }>({
  type: 'PROVIDER',
  name: '',
  cityCode: '',
})
const cities = ref<DirectoryCity[]>([])
const citiesLoading = ref(false)
const submitting = ref(false)
// 一次打开弹窗用一个幂等键：网络超时后再点提交不会建出两家。
let idempotencyKey = newIdempotencyKey()

watch(() => props.open, async (open) => {
  if (!open) return
  idempotencyKey = newIdempotencyKey()
  form.type = 'PROVIDER'
  form.name = ''
  form.cityCode = ''
  formRef.value?.clearValidate()
  if (cities.value.length > 0) return
  citiesLoading.value = true
  try {
    cities.value = await props.api.cities()
  } catch (failure) {
    showError(failure)
  } finally {
    citiesLoading.value = false
  }
})

async function submit(): Promise<void> {
  if (!formRef.value || submitting.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const city = cities.value.find((candidate) => candidate.code === form.cityCode)
  const confirmed = await confirmAction({
    title: '确认新建机构',
    message: `新建${organizationTypeLabel(form.type)}“${form.name.trim()}”，所在城市 ${city?.name ?? form.cityCode}？`
      + '建好后在详情里录入资质、上传附件、建账号。',
    confirmText: '新建',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const detail = await props.api.create(idempotencyKey, form.type, form.name, form.cityCode)
    notifyRecorded(`新建${detail.organization.name}`, '机构详情 · 操作记录')
    emit('created', detail)
  } catch (failure) {
    showError(failure)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="open" title="新建机构" width="520px" :close-on-click-modal="false" @close="emit('close')">
    <el-form ref="formRef" :model="form" :rules="createOrganizationRules" label-width="96px">
      <el-form-item label="机构类型" prop="type">
        <el-radio-group v-model="form.type">
          <el-radio v-for="option in ORGANIZATION_TYPE_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio>
        </el-radio-group>
        <div class="field-hint">
          {{ ORGANIZATION_TYPE_OPTIONS.find((option) => option.value === form.type)?.hint }}
        </div>
      </el-form-item>
      <el-form-item label="机构名称" prop="name">
        <el-input v-model="form.name" :maxlength="LIMITS.name" show-word-limit placeholder="对外显示的名称" />
      </el-form-item>
      <el-form-item label="所在城市" prop="cityCode">
        <el-select
          v-model="form.cityCode"
          filterable
          :loading="citiesLoading"
          placeholder="输入城市名搜索"
          style="width: 100%"
        >
          <el-option
            v-for="city in cities"
            :key="city.code"
            :value="city.code"
            :label="`${city.name}（${city.provinceName}）`"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">新建</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.field-hint {
  width: 100%;
  font-size: 12px;
  line-height: 1.6;
  color: var(--admin-text-secondary);
}
</style>
