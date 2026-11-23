<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDate, formatDateTime, formatMoney } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  createInspectionWorkbenchApi,
  ORDER_STATUS_LABELS,
  STAGE_LABELS,
  STAGE_TABS,
  type ReservationFilter,
  type ReservationPage,
  type ReservationSummary,
} from './inspection-workbench-api'

const router = useRouter()
const api = createInspectionWorkbenchApi(http)
const list = usePagedList<ReservationSummary, { stage: ReservationFilter }>(
  (filters, page, size) => api.list(filters.stage, page, size),
  { stage: 'AWAITING_PLAN' },
)
const counts = computed(() => (list.last.value as ReservationPage | null)?.stageCounts ?? null)

function tabLabel(value: ReservationFilter, label: string): string {
  if (value === 'ALL' || !counts.value) return label
  return `${label}（${counts.value[value] ?? 0}）`
}

function setStage(value: string | number | boolean | undefined): void {
  list.filters.stage = (value as ReservationFilter) ?? 'ALL'
  void list.search()
}

function open(row: ReservationSummary): void {
  void router.push({
    name: 'workbench-inspection-detail',
    params: { reservationId: row.reservationId },
  })
}

function visitText(row: ReservationSummary): string {
  if (!row.visitCount) return '—'
  return `已验收 ${row.acceptedVisitCount ?? 0} / ${row.visitCount} 次`
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="巡检预约"
    description="客户预约到本机构巡检服务的单子：出方案、安排团队设备、排期、推进每次巡检、上传交付。客户确认方案和验收在 App 里由客户自己操作。"
  >
    <div class="toolbar">
      <el-radio-group :model-value="list.filters.stage" @change="setStage">
        <el-radio-button v-for="tab in STAGE_TABS" :key="tab.value" :value="tab.value">
          {{ tabLabel(tab.value, tab.label) }}
        </el-radio-button>
      </el-radio-group>
      <el-button @click="list.load()">刷新</el-button>
    </div>

    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="reservationId"
      empty-text="这一类下没有预约"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="预约编号" width="170">
        <template #default="{ row }">{{ (row as ReservationSummary).reservationNo }}</template>
      </el-table-column>
      <el-table-column label="服务" min-width="180">
        <template #default="{ row }">{{ (row as ReservationSummary).serviceTitle }}</template>
      </el-table-column>
      <el-table-column label="巡检地点" min-width="200">
        <template #default="{ row }">{{ (row as ReservationSummary).locationSummary }}</template>
      </el-table-column>
      <el-table-column label="期望日期" width="110">
        <template #default="{ row }">{{ (row as ReservationSummary).preferredServiceDate }}</template>
      </el-table-column>
      <el-table-column label="阶段" width="120">
        <template #default="{ row }">
          <el-tag effect="plain">{{ STAGE_LABELS[(row as ReservationSummary).stage] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="报价合计" width="120">
        <template #default="{ row }">{{ formatMoney((row as ReservationSummary).quoteAmountMinor) }}</template>
      </el-table-column>
      <el-table-column label="履约" width="170">
        <template #default="{ row }">
          <div v-if="(row as ReservationSummary).serviceOrderStatus">
            {{ ORDER_STATUS_LABELS[(row as ReservationSummary).serviceOrderStatus!] }}
          </div>
          <div class="muted">{{ visitText(row as ReservationSummary) }}</div>
          <div v-if="(row as ReservationSummary).nextVisitDate" class="muted">
            下次 {{ formatDate((row as ReservationSummary).nextVisitDate) }}
          </div>
        </template>
      </el-table-column>
      <!--
        付款状态：巡检付款节点（M14 统一交易模块，另一位同事在做）接入后，从服务单的 paymentNodes 读取
        （首付、按次款是否已付）。现在列表摘要里没有付款信息，先显示“—”，列的位置留好。
      -->
      <el-table-column label="付款状态" width="100">
        <template #default>—</template>
      </el-table-column>
      <el-table-column label="提交时间" width="150">
        <template #default="{ row }">{{ formatDateTime((row as ReservationSummary).submittedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as ReservationSummary)">处理</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
