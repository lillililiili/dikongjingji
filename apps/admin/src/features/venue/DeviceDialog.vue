<script setup lang="ts">
/** 新增或修改一件馆内设备：类别、名称、说明、图片、体验方式、关联商品 ID、排序。 */
import type { FormInstance } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import MediaField from '@/core/ops/MediaField.vue'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import {
  DEVICE_CATEGORY_OPTIONS,
  TRIAL_MODE_OPTIONS,
  type OpsVenueDetail,
  type OpsVenueDevice,
  type VenueApi,
} from './venue-api'
import { deviceDraftOf, deviceRules, VENUE_LIMITS } from './venue-rules'

const props = defineProps<{
  api: VenueApi
  venueId: string
  open: boolean
  device: OpsVenueDevice | null
}>()

const emit = defineEmits<{
  close: []
  saved: [detail: OpsVenueDetail]
}>()

const formRef = ref<FormInstance>()
const form = reactive(deviceDraftOf(null))
const busy = ref(false)
let idempotencyKey = newIdempotencyKey()
const editing = computed(() => props.device !== null)

watch(() => props.open, (open) => {
  if (!open) return
  Object.assign(form, deviceDraftOf(props.device))
  idempotencyKey = newIdempotencyKey()
  formRef.value?.clearValidate()
})

async function save(): Promise<void> {
  if (!formRef.value || busy.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const confirmed = await confirmAction({
    title: editing.value ? '确认修改设备' : '确认新增设备',
    message: form.productId.trim()
      ? '场馆上线、设备启用且商品在本城有售后，用户可从商品详情查看这家场馆。'
      : 'App 的“馆内设备”展示已上线场馆中的启用设备。',
    confirmText: '保存',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const detail = props.device
      ? await props.api.updateDevice(props.venueId, props.device.deviceId, { ...form }, props.device.version)
      : await props.api.createDevice(props.venueId, { ...form }, idempotencyKey)
    notifyRecorded(editing.value ? '修改设备' : '新增设备', '改动记录')
    emit('saved', detail)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="open" :title="editing ? '修改设备' : '新增设备'" width="600px" @close="emit('close')">
    <el-form ref="formRef" :model="form" :rules="deviceRules" label-width="96px">
      <el-form-item label="类别" prop="category">
        <el-radio-group v-model="form.category">
          <el-radio-button v-for="option in DEVICE_CATEGORY_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="设备名称" prop="name">
        <el-input v-model="form.name" :maxlength="VENUE_LIMITS.name" show-word-limit />
      </el-form-item>
      <el-form-item label="说明" prop="summary">
        <el-input v-model="form.summary" :maxlength="VENUE_LIMITS.summary" show-word-limit />
      </el-form-item>
      <el-form-item label="图片">
        <MediaField v-model="form.imageUrl" kind="IMAGE" />
      </el-form-item>
      <el-form-item label="体验方式" prop="trialMode">
        <el-radio-group v-model="form.trialMode">
          <el-radio v-for="option in TRIAL_MODE_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="关联商品 ID" prop="productId">
        <el-input v-model="form.productId" clearable placeholder="选填，商城商品的 ID" />
      </el-form-item>
      <el-form-item label="排序">
        <el-input-number v-model="form.sortOrder" :min="0" :max="VENUE_LIMITS.sortOrder" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
