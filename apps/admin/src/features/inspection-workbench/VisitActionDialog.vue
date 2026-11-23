<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, ref } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { confirmAction, showError } from '@/core/ui/feedback'
import { charCount } from '@/core/ui/format'

import type { InspectionWorkbenchApi, ProgressEventCode, ServiceVisit } from './inspection-workbench-api'
import { VISIT_ACTION_LABELS } from './inspection-rules'

/**
 * 一次巡检的排期（putInspectionVisitSchedule）或履约进度（appendInspectionProgressEvent）：If-Match 是这一次的版本号。
 * 进度的发生时间用提交那一刻（后端要求不早于之前的事件）。
 */
const props = defineProps<{
  api: InspectionWorkbenchApi
  visit: ServiceVisit
  action: 'SCHEDULE' | ProgressEventCode
}>()

const emit = defineEmits<{
  saved: [visit: ServiceVisit]
  close: []
}>()

const DEFAULT_MESSAGES: Record<ProgressEventCode, string> = {
  SERVICE_STARTED: '团队已到场，开始巡检',
  SERVICE_PROGRESS: '',
  SERVICE_COMPLETED: '现场巡检完成，整理报告中',
}

/** 默认北京时间计划日期的上午 9 点。 */
const startAt = ref(props.visit.plannedStartAt ?? `${props.visit.plannedDate}T09:00:00.000+08:00`)
const message = ref(props.action === 'SCHEDULE' ? '' : DEFAULT_MESSAGES[props.action])
const busy = ref(false)
let key = newIdempotencyKey()

const title = computed(() => `第 ${props.visit.visitNo} 次巡检：${VISIT_ACTION_LABELS[props.action]}`)

async function submit(): Promise<void> {
  if (busy.value) return
  if (props.action === 'SCHEDULE') {
    if (!startAt.value) {
      ElMessage.warning('选一个开始时间')
      return
    }
  } else if (!message.value.trim() || charCount(message.value.trim()) > 500) {
    ElMessage.warning('写一句进度说明（≤500 字），客户在履约页能看到')
    return
  }
  const confirmed = await confirmAction({
    title: title.value,
    message: props.action === 'SCHEDULE'
      ? '客户会在 App 的履约页看到开始时间，并收到一条站内信。'
      : '记下后不能撤回，客户在 App 的履约页能看到，并收到一条站内信。',
    confirmText: '确认',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const saved = props.action === 'SCHEDULE'
      ? await props.api.schedule(props.visit, new Date(startAt.value).toISOString(), key)
      : await props.api.progress(props.visit, props.action, message.value, new Date().toISOString(), key)
    key = newIdempotencyKey()
    ElMessage.success(`已${VISIT_ACTION_LABELS[props.action]}`)
    emit('saved', saved)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="true" :title="title" width="520px" :close-on-click-modal="false" @close="emit('close')">
    <el-form label-width="80px">
      <el-form-item v-if="action === 'SCHEDULE'" label="开始时间">
        <el-date-picker v-model="startAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss.SSSZ" :clearable="false" />
        <div class="hint">方案里这一次是 {{ visit.plannedDate }}；时间按你电脑的时区选，页面上统一按北京时间显示。</div>
      </el-form-item>
      <el-form-item v-else label="进度说明">
        <el-input v-model="message" type="textarea" :rows="3" maxlength="500" show-word-limit
                  placeholder="如：已完成一半线路" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">确认</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  width: 100%;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
