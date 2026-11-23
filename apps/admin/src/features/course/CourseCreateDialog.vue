<script setup lang="ts">
/**
 * 新建课程草稿：先选线上课或线下班（建好后不能改）、子类目、名称、开课机构；线下班还要选挂靠的活动。
 */
import type { FormInstance } from 'element-plus'
import { reactive, ref, watch } from 'vue'

import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import ActivitySelect from './components/ActivitySelect.vue'
import OrganizationSelect from './components/OrganizationSelect.vue'
import {
  emptyCreateForm,
  labelOf,
  LIMITS,
  MODE_OPTIONS,
  TOPIC_OPTIONS,
  type CourseApi,
  type CourseCreateForm,
  type OpsCourse,
} from './course-api'
import { createRules } from './course-rules'

const props = defineProps<{
  api: CourseApi
  open: boolean
}>()

const emit = defineEmits<{
  close: []
  created: [course: OpsCourse]
}>()

const formRef = ref<FormInstance>()
const form = reactive<CourseCreateForm>(emptyCreateForm())
const rules = createRules(() => form)
const submitting = ref(false)

watch(() => props.open, (open) => {
  if (!open) return
  Object.assign(form, emptyCreateForm())
  formRef.value?.clearValidate()
})

watch(() => form.mode, () => {
  formRef.value?.clearValidate('activityId')
})

async function submit(): Promise<void> {
  if (!formRef.value || submitting.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const confirmed = await confirmAction({
    title: '确认新建课程',
    message: `新建${labelOf(MODE_OPTIONS, form.mode)}“${form.title.trim()}”（上课方式建好后不能改）？新建的是草稿，App 上看不到。`,
    confirmText: '新建',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const course = await props.api.create(form)
    notifyRecorded(`新建课程“${course.title}”`, '课程详情 · 改动记录')
    emit('created', course)
  } catch (failure) {
    showError(failure)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="open" title="新建课程" width="600px" :close-on-click-modal="false" @close="emit('close')">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <el-form-item label="上课方式" prop="mode">
        <el-radio-group v-model="form.mode">
          <el-radio-button v-for="option in MODE_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
        <div class="field-hint">{{ MODE_OPTIONS.find((option) => option.value === form.mode)?.hint }}。建好后不能改。</div>
      </el-form-item>
      <el-form-item label="子类目" prop="topic">
        <el-radio-group v-model="form.topic">
          <el-radio v-for="option in TOPIC_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="课程名称" prop="title">
        <el-input v-model="form.title" :maxlength="LIMITS.title" show-word-limit
                  :placeholder="form.mode === 'OFFLINE' ? '运营端识别用；App 上显示活动的标题' : 'App 上显示的名称'" />
      </el-form-item>
      <el-form-item label="开课机构" prop="organizationId">
        <OrganizationSelect v-model="form.organizationId" />
      </el-form-item>
      <el-form-item v-if="form.mode === 'OFFLINE'" label="挂靠的活动" prop="activityId">
        <ActivitySelect v-model="form.activityId" />
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
