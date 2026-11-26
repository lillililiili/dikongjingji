<script setup lang="ts">
/** 新建或修改一个项目：名称、时长、价格（元，存成分）、年龄限制、每场人数、限制说明、排序。 */
import type { FormInstance } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { minorToYuanText, yuanToMinor } from '@/core/ops/ops-common'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import type { OpsVenueDetail, OpsVenueProject, VenueApi } from './venue-api'
import { projectDraftOf, projectRules, VENUE_LIMITS } from './venue-rules'

const props = defineProps<{
  api: VenueApi
  venueId: string
  open: boolean
  /** 为空是新建。 */
  project: OpsVenueProject | null
}>()

const emit = defineEmits<{
  close: []
  saved: [detail: OpsVenueDetail]
}>()

const formRef = ref<FormInstance>()
const form = reactive({ ...projectDraftOf(null), priceYuan: '0', hasMinAge: false })
const busy = ref(false)
let idempotencyKey = newIdempotencyKey()
const editing = computed(() => props.project !== null)

watch(() => props.open, (open) => {
  if (!open) return
  Object.assign(form, projectDraftOf(props.project))
  form.priceYuan = minorToYuanText(props.project?.priceAmountMinor ?? 0) || '0'
  form.hasMinAge = props.project?.minAge !== null && props.project?.minAge !== undefined
  idempotencyKey = newIdempotencyKey()
  formRef.value?.clearValidate()
})

async function save(): Promise<void> {
  if (!formRef.value || busy.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const priceAmountMinor = yuanToMinor(form.priceYuan)
  if (priceAmountMinor === null || priceAmountMinor > VENUE_LIMITS.maxPriceYuan * 100) {
    showError(new Error(`价格要在 0–${VENUE_LIMITS.maxPriceYuan} 元之间`))
    return
  }
  const draft = {
    name: form.name,
    durationMinutes: form.durationMinutes,
    priceAmountMinor,
    minAge: form.hasMinAge ? form.minAge ?? 0 : null,
    capacity: form.capacity,
    limitNote: form.limitNote,
    sortOrder: form.sortOrder,
  }
  const confirmed = await confirmAction({
    title: editing.value ? '确认修改项目' : '确认新建项目',
    message: editing.value
      ? '场馆上线且项目启用时，用户能看到更新的资料。新价格用于之后的预约。'
      : '新建项目默认启用。场馆上线并开放可预约场次后，用户可在 App 预约。',
    confirmText: '保存',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const detail = props.project
      ? await props.api.updateProject(props.venueId, props.project.projectId, draft, props.project.version)
      : await props.api.createProject(props.venueId, draft, idempotencyKey)
    notifyRecorded(editing.value ? '修改项目' : '新建项目', '改动记录')
    emit('saved', detail)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="open" :title="editing ? '修改项目' : '新建项目'" width="560px" @close="emit('close')">
    <el-form ref="formRef" :model="form" :rules="projectRules" label-width="96px">
      <el-form-item label="项目名称" prop="name">
        <el-input v-model="form.name" :maxlength="VENUE_LIMITS.name" show-word-limit />
      </el-form-item>
      <el-form-item label="时长（分钟）">
        <el-input-number v-model="form.durationMinutes" :min="VENUE_LIMITS.minDuration" :max="VENUE_LIMITS.maxDuration"
                         :step="5" />
      </el-form-item>
      <el-form-item label="每人价格（元）" prop="priceYuan">
        <el-input v-model="form.priceYuan" style="width: 160px" placeholder="0 为免费" />
      </el-form-item>
      <el-form-item label="年龄限制">
        <el-checkbox v-model="form.hasMinAge">有最低年龄</el-checkbox>
        <el-input-number v-if="form.hasMinAge" v-model="form.minAge" :min="0" :max="VENUE_LIMITS.maxAge"
                         style="margin-left: 12px" />
        <span v-if="form.hasMinAge" class="hint">周岁及以上</span>
      </el-form-item>
      <el-form-item label="每场人数">
        <el-input-number v-model="form.capacity" :min="1" :max="VENUE_LIMITS.maxCapacity" />
        <span class="hint">新生成场次的默认名额</span>
      </el-form-item>
      <el-form-item label="限制说明" prop="limitNote">
        <el-input v-model="form.limitNote" :maxlength="VENUE_LIMITS.limitNote" show-word-limit
                  placeholder="如：未满 8 岁需 1 名成人陪同" />
      </el-form-item>
      <el-form-item label="排序">
        <el-input-number v-model="form.sortOrder" :min="0" :max="VENUE_LIMITS.sortOrder" />
        <span class="hint">小的在前</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
