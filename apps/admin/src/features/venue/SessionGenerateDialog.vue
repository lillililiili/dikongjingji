<script setup lang="ts">
/**
 * 按周模板批量生成场次：日期范围（北京时间，含两端）× 星期几 × 每天的开始时间，名额默认取项目每场人数。
 * 页面先按同样的规则算出会生成多少场给人看；已有的、已过的由后端跳过，一次最多新生成 200 场。
 */
import { computed, reactive, ref, watch } from 'vue'

import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import type { OpsVenueProject, OpsVenueSessionsGenerated, VenueApi, Weekday } from './venue-api'
import { addDays, beijingToday, expandWeekTemplate, HHMM, VENUE_LIMITS, WEEKDAY_OPTIONS } from './venue-rules'

const props = defineProps<{
  api: VenueApi
  venueId: string
  open: boolean
  project: OpsVenueProject | null
}>()

const emit = defineEmits<{
  close: []
  generated: [result: OpsVenueSessionsGenerated]
}>()

const form = reactive({
  range: [beijingToday(), addDays(beijingToday(), 13)] as [string, string],
  weekdays: ['SATURDAY', 'SUNDAY'] as Weekday[],
  startTimes: ['10:00', '14:00'] as string[],
  newTime: '',
  useProjectCapacity: true,
  capacity: 8,
})
const busy = ref(false)

watch(() => props.open, (open) => {
  if (!open || !props.project) return
  form.capacity = props.project.capacity
  form.useProjectCapacity = true
  form.newTime = ''
})

const plan = computed(() => expandWeekTemplate(form.range[0], form.range[1], form.weekdays, form.startTimes))
const slots = computed(() => ('slots' in plan.value ? plan.value.slots : []))
const problem = computed(() => {
  if ('problem' in plan.value) return plan.value.problem
  if (slots.value.length > VENUE_LIMITS.sessionsPerBatch) {
    return `一次最多 ${VENUE_LIMITS.sessionsPerBatch} 场，现在是 ${slots.value.length} 场，请缩短日期或减少时间`
  }
  return null
})

function addTime(): void {
  const time = form.newTime.trim()
  if (!HHMM.test(time) || form.startTimes.includes(time)) return
  form.startTimes = [...form.startTimes, time].sort()
  form.newTime = ''
}

function removeTime(time: string): void {
  form.startTimes = form.startTimes.filter((value) => value !== time)
}

async function generate(): Promise<void> {
  if (!props.project || problem.value || busy.value) return
  const project = props.project
  const confirmed = await confirmAction({
    title: '确认生成场次',
    message: `给“${project.name}”最多生成 ${slots.value.length} 场（每场 ${project.durationMinutes} 分钟、`
      + `${form.useProjectCapacity ? project.capacity : form.capacity} 人）。同一开始时间已有的、开始时间已过的会跳过。`
      + '生成后 App 上马上能约。',
    confirmText: '生成',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const result = await props.api.generateSessions(props.venueId, project.projectId, {
      fromDate: form.range[0],
      toDate: form.range[1],
      weekdays: [...form.weekdays],
      startTimes: [...form.startTimes],
      capacity: form.useProjectCapacity ? null : form.capacity,
    })
    notifyRecorded(`生成 ${result.createdCount} 场（跳过已有 ${result.skippedExistingCount}、已过 ${result.skippedPastCount}）`,
      '改动记录')
    emit('generated', result)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="open" :title="`按周生成场次 · ${project?.name ?? ''}`" width="620px" @close="emit('close')">
    <el-form label-width="96px">
      <el-form-item label="日期范围">
        <el-date-picker v-model="form.range" type="daterange" value-format="YYYY-MM-DD" range-separator="至"
                        start-placeholder="开始" end-placeholder="结束" :clearable="false" />
        <span class="hint">北京时间，含两端，最多 {{ VENUE_LIMITS.rangeDays }} 天</span>
      </el-form-item>
      <el-form-item label="星期">
        <el-checkbox-group v-model="form.weekdays">
          <el-checkbox v-for="option in WEEKDAY_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item label="开始时间">
        <div class="times">
          <el-tag v-for="time in form.startTimes" :key="time" closable @close="removeTime(time)">{{ time }}</el-tag>
          <el-input v-model="form.newTime" size="small" placeholder="如 16:00" style="width: 100px" @keyup.enter="addTime" />
          <el-button size="small" @click="addTime">添加</el-button>
        </div>
      </el-form-item>
      <el-form-item label="每场名额">
        <el-checkbox v-model="form.useProjectCapacity">用项目每场人数（{{ project?.capacity }} 人）</el-checkbox>
        <el-input-number v-if="!form.useProjectCapacity" v-model="form.capacity" :min="1" :max="VENUE_LIMITS.maxCapacity"
                         style="margin-left: 12px" />
      </el-form-item>
    </el-form>
    <el-alert v-if="problem" type="warning" :title="problem" :closable="false" show-icon />
    <el-alert v-else type="info" :closable="false" show-icon
              :title="`最多生成 ${slots.length} 场：${slots.slice(0, 4).join('、')}${slots.length > 4 ? ' …' : ''}`" />
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="!!problem" @click="generate">生成</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.times {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
</style>
