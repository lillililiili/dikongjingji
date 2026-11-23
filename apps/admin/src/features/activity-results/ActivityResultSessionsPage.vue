<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import { createActivityResultsApi, sessionLabel, type ResultSession } from './activity-results-api'

const api = createActivityResultsApi(http)
const router = useRouter()
const list = usePagedList<ResultSession, Record<string, never>>((_filters, page, size) => api.sessions(page, size), {})

function open(row: ResultSession): void {
  void router.push({ name: 'workbench-activity-roster', params: { sessionId: row.sessionId } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="活动结果"
    description="本机构主办或承办的活动最近 180 天里已经开始的场次。进名单给已签到的参与人录入完成情况、成绩和证书说明，客户在 App 的凭证和订单页看到；第一次录入时会给报名的账号发一条站内信“结果已发布”。"
  >
    <div class="toolbar">
      <el-button @click="list.load()">刷新</el-button>
    </div>
    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="sessionId"
      empty-text="最近 180 天没有已经开始的场次"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="开始时间" width="150">
        <template #default="{ row }">{{ formatDateTime((row as ResultSession).startsAt) }}</template>
      </el-table-column>
      <el-table-column label="活动" min-width="220">
        <template #default="{ row }">{{ (row as ResultSession).activityTitle }}</template>
      </el-table-column>
      <el-table-column label="场次" width="140">
        <template #default="{ row }">{{ sessionLabel(row as ResultSession) }}</template>
      </el-table-column>
      <el-table-column label="报名" width="80" align="right">
        <template #default="{ row }">{{ (row as ResultSession).enrolledPeople }}</template>
      </el-table-column>
      <el-table-column label="签到" width="80" align="right">
        <template #default="{ row }">{{ (row as ResultSession).checkedInPeople }}</template>
      </el-table-column>
      <el-table-column label="已录结果" width="90" align="right">
        <template #default="{ row }">
          <span :class="{ pending: (row as ResultSession).recordedPeople < (row as ResultSession).checkedInPeople }">
            {{ (row as ResultSession).recordedPeople }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as ResultSession)">名单与结果</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.pending {
  color: var(--el-color-warning);
  font-weight: 600;
}
</style>
