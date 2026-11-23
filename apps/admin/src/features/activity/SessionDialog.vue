<script setup lang="ts">
/**
 * 新增 / 修改场次。时间按北京时间填（“YYYY-MM-DD HH:mm”），提交时带 +08:00；修改时带页面读到的版本号。
 * 已有人报名的场次开始、结束时间不能改（锁住输入框，后端也会拒绝）。
 */
import type { FormInstance } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import {
  emptySessionForm,
  LIMITS,
  sessionFormOf,
  type ActivityApi,
  type OpsActivity,
  type OpsActivitySession,
  type SessionForm,
} from './activity-api'
import { sessionRules } from './activity-rules'

const props = defineProps<{
  api: ActivityApi
  activityId: string
  open: boolean
  /** 为空是新增。 */
  session: OpsActivitySession | null
}>()

const emit = defineEmits<{
  close: []
  saved: [activity: OpsActivity]
}>()

const formRef = ref<FormInstance>()
const form = reactive<SessionForm>(emptySessionForm())
const busy = ref(false)
const rules = sessionRules(() => form)
const timesLocked = computed(() => (props.session?.enrolledCount ?? 0) > 0)

watch(() => [props.open, props.session] as const, ([open, session]) => {
  if (!open) return
  Object.assign(form, session ? sessionFormOf(session) : emptySessionForm())
  formRef.value?.clearValidate()
}, { immediate: true })

async function save(): Promise<void> {
  if (!formRef.value || busy.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const editing = props.session
  const confirmed = await confirmAction({
    title: editing ? '确认修改场次' : '确认新增场次',
    message: editing
      ? `已报名 ${editing.enrolledCount} 人。保存后活动上线时 App 立刻按新的时间和名额显示。`
      : '新场次是“在售”状态；活动已上线时 App 立刻能报名。',
    confirmText: '保存',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const activity = editing
      ? await props.api.updateSession(props.activityId, editing.sessionId, form, editing.version)
      : await props.api.addSession(props.activityId, form)
    emit('saved', activity)
    notifyRecorded(editing ? '修改场次' : '新增场次', '改动记录')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="open" :title="session ? '修改场次' : '新增场次'" width="560px" @close="emit('close')">
    <el-alert v-if="timesLocked" type="info" :closable="false" show-icon class="session-dialog__alert"
              title="这一场已有人报名：开始和结束时间不能改，名额不能少于已报名人数。" />
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <el-form-item label="场次名称" prop="label">
        <el-input v-model="form.label" :maxlength="LIMITS.label" show-word-limit placeholder="比如“上午场”，可不填" />
      </el-form-item>
      <el-form-item label="开始时间" prop="startsAt">
        <el-date-picker v-model="form.startsAt" type="datetime" format="YYYY-MM-DD HH:mm"
                        value-format="YYYY-MM-DD HH:mm" placeholder="北京时间" :disabled="timesLocked" />
      </el-form-item>
      <el-form-item label="结束时间" prop="endsAt">
        <el-date-picker v-model="form.endsAt" type="datetime" format="YYYY-MM-DD HH:mm"
                        value-format="YYYY-MM-DD HH:mm" placeholder="可不填" :disabled="timesLocked" />
      </el-form-item>
      <el-form-item label="报名截止" prop="enrollmentClosesAt">
        <el-date-picker v-model="form.enrollmentClosesAt" type="datetime" format="YYYY-MM-DD HH:mm"
                        value-format="YYYY-MM-DD HH:mm" placeholder="不晚于开始时间" />
      </el-form-item>
      <el-form-item label="签到开放" prop="checkInOpensAt">
        <el-date-picker v-model="form.checkInOpensAt" type="datetime" format="YYYY-MM-DD HH:mm"
                        value-format="YYYY-MM-DD HH:mm" placeholder="不填时开始前 1 小时" />
      </el-form-item>
      <el-form-item label="名额" prop="capacity">
        <el-input-number v-model="form.capacity" :min="Math.max(1, session?.enrolledCount ?? 1)"
                         :max="LIMITS.capacity" :step="1" step-strictly />
      </el-form-item>
      <p class="session-dialog__hint">时间都按北京时间。</p>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.session-dialog__alert {
  margin-bottom: 12px;
}

.session-dialog__hint {
  margin: 0 0 0 96px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
