<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { maxChars, required } from '@/core/ui/form-rules'

import {
  CITY_NOTE_MAX,
  CITY_STATUS_OPTIONS,
  cityStatusLabel,
  type CityApi,
  type CityServiceStatus,
  type OpsCity,
} from './city-api'

const props = defineProps<{
  api: CityApi
  city: OpsCity | null
}>()

const emit = defineEmits<{
  close: []
  changed: [city: OpsCity]
}>()

const formRef = ref<FormInstance>()
const form = reactive<{ serviceStatus: CityServiceStatus | '', note: string }>({ serviceStatus: '', note: '' })
const submitting = ref(false)

const rules: FormRules = {
  serviceStatus: [required('开通状态', 'change')],
  note: [required('改动说明'), maxChars('改动说明', CITY_NOTE_MAX)],
}

watch(() => props.city, () => {
  form.serviceStatus = ''
  form.note = ''
  formRef.value?.clearValidate()
}, { immediate: true })

const unchanged = computed(() => props.city?.serviceStatus === form.serviceStatus)

async function submit(): Promise<void> {
  const city = props.city
  if (!city || !formRef.value || submitting.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  if (form.serviceStatus === '' || unchanged.value) return
  const target = form.serviceStatus
  const confirmed = await confirmAction({
    title: '确认修改开通状态',
    message: `把“${city.cityName}”从“${cityStatusLabel(city.serviceStatus)}”改成“${cityStatusLabel(target)}”？`
      + '保存后 App 城市面板马上按新状态显示。',
    confirmText: '确认修改',
    danger: target === 'CLOSED',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const changed = await props.api.changeStatus(city.cityCode, target, form.note, city.version)
    notifyRecorded(`把${city.cityName}改为${cityStatusLabel(target)}`, '改动记录')
    emit('changed', changed)
  } catch (failure) {
    showError(failure)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="city !== null"
    :title="city ? `修改开通状态：${city.cityName}` : ''"
    width="520px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <el-form v-if="city" ref="formRef" :model="form" :rules="rules" label-width="96px">
      <el-form-item label="现在">
        <span>{{ cityStatusLabel(city.serviceStatus) }}</span>
      </el-form-item>
      <el-form-item label="改成" prop="serviceStatus">
        <el-radio-group v-model="form.serviceStatus" class="city-status__options">
          <el-radio
            v-for="option in CITY_STATUS_OPTIONS"
            :key="option.value"
            :value="option.value"
            :disabled="option.value === city.serviceStatus"
          >
            {{ option.label }}<span class="city-status__hint">{{ option.hint }}</span>
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="改动说明" prop="note">
        <el-input
          v-model="form.note"
          type="textarea"
          :rows="3"
          :maxlength="CITY_NOTE_MAX"
          show-word-limit
          placeholder="为什么改，比如：首批商家已入驻、本地供给不足暂停等"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!form.serviceStatus || unchanged" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.city-status__options {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.city-status__hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
