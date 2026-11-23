<script setup lang="ts">
/**
 * 一个项目的场次（含停售、已取消）：按日期范围查看，逐场停售、恢复售卖或取消。有有效预约的场次只能停售
 * （已有预约照常到馆核销），没有预约的才能取消；开始后都不能再改。
 */
import { reactive, ref, shallowRef, watch } from 'vue'

import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import { errorMessage } from '@/core/http/api-error'

import { sessionStatus, type OpsVenueProject, type OpsVenueSession, type VenueApi } from './venue-api'
import { addDays, beijingToday } from './venue-rules'

const props = defineProps<{
  api: VenueApi
  venueId: string
  open: boolean
  project: OpsVenueProject | null
}>()

const emit = defineEmits<{
  close: []
  changed: []
}>()

const range = reactive({ value: [beijingToday(), addDays(beijingToday(), 13)] as [string, string] })
const sessions = shallowRef<OpsVenueSession[]>([])
const loading = ref(false)
const error = ref('')
const busyId = ref('')

async function load(): Promise<void> {
  if (!props.project) return
  loading.value = true
  error.value = ''
  try {
    sessions.value = await props.api.sessions(props.venueId, props.project.projectId, range.value[0], range.value[1])
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

watch(() => props.open, (open) => {
  if (open) void load()
})

function started(session: OpsVenueSession): boolean {
  return new Date(session.startsAt).getTime() <= Date.now()
}

const ACTIONS = {
  'stop-sale': { label: '停售', done: '停售场次', message: '停售后 App 不再列出这一场、不接新预约；已有的预约照常到馆核销。' },
  'resume-sale': { label: '恢复售卖', done: '恢复售卖', message: '恢复后 App 上又能约这一场。' },
  cancel: { label: '取消场次', done: '取消场次', message: '取消后这一场不再出现，也不能恢复。只有没有预约的场次能取消。' },
} as const

async function change(session: OpsVenueSession, action: keyof typeof ACTIONS): Promise<void> {
  const meta = ACTIONS[action]
  const confirmed = await confirmAction({
    title: `确认${meta.label}`,
    message: `${formatDateTime(session.startsAt)} 这一场：${meta.message}`,
    confirmText: meta.label,
    danger: action !== 'resume-sale',
  })
  if (!confirmed) return
  busyId.value = session.sessionId
  try {
    await props.api.changeSession(props.venueId, session.sessionId, action, session.version)
    notifyRecorded(meta.done, '改动记录')
    emit('changed')
    await load()
  } catch (failure) {
    showError(failure)
  } finally {
    busyId.value = ''
  }
}
</script>

<template>
  <el-drawer :model-value="open" :title="`场次 · ${project?.name ?? ''}`" size="720px" @close="emit('close')">
    <div class="toolbar">
      <el-date-picker v-model="range.value" type="daterange" value-format="YYYY-MM-DD" range-separator="至"
                      :clearable="false" />
      <el-button @click="load">查询</el-button>
      <span class="hint">北京时间，最多 62 天、500 场</span>
    </div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="sessions" row-key="sessionId" size="small" border empty-text="这段时间没有场次">
      <el-table-column label="开始" width="150">
        <template #default="{ row }">{{ formatDateTime((row as OpsVenueSession).startsAt) }}</template>
      </el-table-column>
      <el-table-column label="结束" width="150">
        <template #default="{ row }">{{ formatDateTime((row as OpsVenueSession).endsAt) }}</template>
      </el-table-column>
      <el-table-column label="已约 / 名额" width="100">
        <template #default="{ row }">{{ (row as OpsVenueSession).bookedCount }} / {{ (row as OpsVenueSession).capacity }}</template>
      </el-table-column>
      <el-table-column prop="activeBookingCount" label="有效预约" width="80" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="sessionStatus((row as OpsVenueSession).status).tag">
            {{ sessionStatus((row as OpsVenueSession).status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="160">
        <template #default="{ row }">
          <template v-if="!started(row as OpsVenueSession) && (row as OpsVenueSession).status !== 'CANCELLED'">
            <el-button v-if="(row as OpsVenueSession).status === 'ACTIVE'" link type="warning"
                       :loading="busyId === (row as OpsVenueSession).sessionId"
                       @click="change(row as OpsVenueSession, 'stop-sale')">停售</el-button>
            <el-button v-else link type="primary" :loading="busyId === (row as OpsVenueSession).sessionId"
                       @click="change(row as OpsVenueSession, 'resume-sale')">恢复售卖</el-button>
            <el-tooltip v-if="(row as OpsVenueSession).activeBookingCount > 0 || (row as OpsVenueSession).bookedCount > 0"
                        content="已有预约，只能停售" placement="top">
              <el-button link disabled>取消</el-button>
            </el-tooltip>
            <el-button v-else link type="danger" :loading="busyId === (row as OpsVenueSession).sessionId"
                       @click="change(row as OpsVenueSession, 'cancel')">取消</el-button>
          </template>
          <span v-else class="hint">{{ (row as OpsVenueSession).status === 'CANCELLED' ? '已取消' : '已开始，不能再改' }}</span>
        </template>
      </el-table-column>
    </el-table>
  </el-drawer>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
