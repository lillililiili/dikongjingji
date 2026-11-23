<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import ResultDialog from './ResultDialog.vue'
import {
  COMPLETION_LABELS,
  createActivityResultsApi,
  sessionLabel,
  type ResultRoster,
  type ResultRosterEntry,
} from './activity-results-api'

const route = useRoute()
const router = useRouter()
const api = createActivityResultsApi(http)
const roster = shallowRef<ResultRoster | null>(null)
const loading = ref(false)
const error = ref('')
const editing = shallowRef<ResultRosterEntry | null>(null)

const sessionId = computed(() => String(route.params.sessionId ?? ''))
const description = computed(() => {
  const session = roster.value?.session
  if (!session) return undefined
  return `${sessionLabel(session)} · ${formatDateTime(session.startsAt)} 开始`
})
const checkedIn = computed(() => roster.value?.entries.filter((entry) => entry.checkedIn).length ?? 0)
const recorded = computed(() => roster.value?.entries.filter((entry) => entry.result !== null).length ?? 0)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    roster.value = await api.roster(sessionId.value)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function recordedOne(next: ResultRosterEntry): void {
  const current = roster.value
  editing.value = null
  if (!current) return
  roster.value = {
    ...current,
    entries: current.entries.map((entry) => (entry.ticketId === next.ticketId ? next : entry)),
  }
}

function rowClass({ row }: { row: ResultRosterEntry }): string {
  return row.checkedIn ? '' : 'roster__absent'
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection :title="roster?.session.activityTitle ?? '名单与结果'" :description="description">
    <template #actions>
      <el-button @click="router.push({ name: 'workbench-activity-results' })">返回场次</el-button>
      <el-button @click="load">刷新</el-button>
    </template>

    <div v-loading="loading">
      <el-result v-if="error && !roster" icon="error" :title="error">
        <template #extra><el-button type="primary" @click="load">重新加载</el-button></template>
      </el-result>

      <template v-if="roster">
        <el-alert
          v-if="!roster.started"
          type="info"
          :closable="false"
          show-icon
          title="这一场还没开始，签到之后才能录结果"
          class="roster__notice"
        />
        <p class="roster__summary">
          共 {{ roster.entries.length }} 人 · 已签到 {{ checkedIn }} 人 · 已录结果 {{ recorded }} 人。
          姓名只给姓，用凭证尾号核对；没签到的不能录。
        </p>
        <el-table :data="roster.entries" row-key="ticketId" :row-class-name="rowClass" empty-text="这一场没有有效的报名">
          <el-table-column label="姓名" width="90">
            <template #default="{ row }">{{ (row as ResultRosterEntry).holderName ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="年龄" width="70">
            <template #default="{ row }">
              {{ (row as ResultRosterEntry).age === null ? '—' : `${(row as ResultRosterEntry).age} 岁` }}
            </template>
          </el-table-column>
          <el-table-column label="凭证尾号" width="90">
            <template #default="{ row }">{{ (row as ResultRosterEntry).codeTail ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="团队" min-width="130">
            <template #default="{ row }">{{ (row as ResultRosterEntry).teamName ?? '个人报名' }}</template>
          </el-table-column>
          <el-table-column label="签到" width="150">
            <template #default="{ row }">
              <span v-if="(row as ResultRosterEntry).checkedIn">{{ formatDateTime((row as ResultRosterEntry).checkedInAt) }}</span>
              <span v-else class="muted">未签到</span>
            </template>
          </el-table-column>
          <el-table-column label="完成情况" width="90">
            <template #default="{ row }">
              <el-tag
                v-if="(row as ResultRosterEntry).result"
                :type="(row as ResultRosterEntry).result!.completion === 'COMPLETED' ? 'success' : 'info'"
                effect="plain"
              >
                {{ COMPLETION_LABELS[(row as ResultRosterEntry).result!.completion] }}
              </el-tag>
              <span v-else class="muted">未录</span>
            </template>
          </el-table-column>
          <el-table-column label="成绩" min-width="150">
            <template #default="{ row }">{{ (row as ResultRosterEntry).result?.scoreText ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="证书说明" min-width="180">
            <template #default="{ row }">{{ (row as ResultRosterEntry).result?.certificateNote ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="roster.started && (row as ResultRosterEntry).checkedIn"
                link
                type="primary"
                @click="editing = row as ResultRosterEntry"
              >
                {{ (row as ResultRosterEntry).result ? '修改结果' : '录入结果' }}
              </el-button>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>
  </PageSection>

  <ResultDialog :api="api" :entry="editing" @recorded="recordedOne" @close="editing = null" @stale="load" />
</template>

<style scoped>
.roster__notice {
  margin-bottom: 12px;
}

.roster__summary {
  margin: 0 0 12px;
  color: var(--admin-text-secondary);
}

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

:deep(.roster__absent) {
  color: var(--admin-text-secondary);
  opacity: 0.6;
}
</style>
