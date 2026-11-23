<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  budgetLabel,
  createDemandQuotesApi,
  DEMAND_STATUS,
  DEMAND_STATUS_OPTIONS,
  DEMAND_TYPE_LABELS,
  QUOTE_STATUS,
  type DemandFilters,
  type ProviderDemand,
} from './demand-quotes-api'

const api = createDemandQuotesApi(http)
const router = useRouter()
const list = usePagedList<ProviderDemand, DemandFilters>((filters, page, size) => api.list(filters, page, size),
  { status: '' })

function setStatus(value: string | number | boolean | undefined): void {
  list.filters.status = (value as DemandFilters['status']) ?? ''
  void list.search()
}

function open(row: ProviderDemand): void {
  void router.push({ name: 'workbench-demand', params: { demandId: row.demandId } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="需求报价"
    description="平台匹配到本机构服务城市、或发布人邀请了本机构的需求，以及本机构报过价的。报价期 72 小时，发布人比选后选定一家。"
  >
    <div class="toolbar">
      <el-radio-group :model-value="list.filters.status" @change="setStatus">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button v-for="option in DEMAND_STATUS_OPTIONS" :key="option.value" :value="option.value">
          {{ option.label }}
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
      row-key="demandId"
      empty-text="暂时没有能报价的需求"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="提交时间" width="150">
        <template #default="{ row }">{{ formatDateTime((row as ProviderDemand).submittedAt) }}</template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ DEMAND_TYPE_LABELS[(row as ProviderDemand).type] }}</template>
      </el-table-column>
      <el-table-column label="需求" min-width="220">
        <template #default="{ row }">
          <div>{{ (row as ProviderDemand).title }}</div>
          <div class="muted">
            {{ (row as ProviderDemand).cityName }} · {{ (row as ProviderDemand).invited ? '发布人邀请' : '平台匹配' }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="预算" width="150">
        <template #default="{ row }">{{ budgetLabel(row as ProviderDemand) }}</template>
      </el-table-column>
      <el-table-column label="期望完成" width="110">
        <template #default="{ row }">{{ (row as ProviderDemand).dueDate ?? '不限' }}</template>
      </el-table-column>
      <el-table-column label="报价截止" width="150">
        <template #default="{ row }">
          {{ formatDateTime((row as ProviderDemand).quoteDeadline) }}
          <div v-if="!(row as ProviderDemand).quotingOpen" class="muted">已截止</div>
        </template>
      </el-table-column>
      <el-table-column label="需求状态" width="100">
        <template #default="{ row }">
          <el-tag :type="DEMAND_STATUS[(row as ProviderDemand).status].tag" effect="plain">
            {{ DEMAND_STATUS[(row as ProviderDemand).status].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="本机构报价" width="150">
        <template #default="{ row }">
          <template v-if="(row as ProviderDemand).myQuote">
            <div>{{ formatMoney((row as ProviderDemand).myQuote?.amountMinor) }}</div>
            <div class="muted">{{ QUOTE_STATUS[(row as ProviderDemand).myQuote!.status].label }}</div>
          </template>
          <span v-else class="muted">未报价</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as ProviderDemand)">查看</el-button>
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

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
