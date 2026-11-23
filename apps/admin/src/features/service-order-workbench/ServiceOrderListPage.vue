<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  createServiceOrderApi,
  type ServiceOrderFilters,
  type ServiceOrderStatus,
  type ServiceOrderSummary,
} from './service-order-api'
import { customerLabel, STATUS, STATUS_TABS, TYPE_LABELS } from './service-order-rules'

const ALL = 'ALL'

const api = createServiceOrderApi(http)
const router = useRouter()
const list = usePagedList<ServiceOrderSummary, ServiceOrderFilters>((filters, page, size) => api.list(filters, page, size),
  { status: '' })

const tab = computed(() => list.filters.status || ALL)

function changeTab(name: string | number): void {
  list.filters.status = name === ALL ? '' : name as ServiceOrderStatus
  void list.search()
}

function open(row: ServiceOrderSummary): void {
  void router.push({ name: 'workbench-service-order', params: { orderId: row.orderId } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="服务订单"
    description="客户在需求比选里选定本机构的报价后生成（航拍、测绘、设备维护、技术支持）。出方案 → 客户确认并付款 → 开工 → 交付 → 客户验收。"
  >
    <template #actions>
      <el-button @click="list.load()">刷新</el-button>
    </template>
    <el-tabs :model-value="tab" class="tabs" @tab-change="changeTab">
      <el-tab-pane v-for="item in STATUS_TABS" :key="item.value || ALL" :label="item.label" :name="item.value || ALL" />
    </el-tabs>
    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="orderId"
      empty-text="没有这个状态的服务订单"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column prop="orderNo" label="订单编号" width="170" />
      <el-table-column label="服务" min-width="220">
        <template #default="{ row }">
          <div>{{ TYPE_LABELS[(row as ServiceOrderSummary).type] }} · {{ (row as ServiceOrderSummary).title }}</div>
          <div class="muted">{{ (row as ServiceOrderSummary).cityName }} · {{ (row as ServiceOrderSummary).scope }}</div>
        </template>
      </el-table-column>
      <el-table-column label="客户" min-width="180">
        <template #default="{ row }">{{ customerLabel((row as ServiceOrderSummary).customer) }}</template>
      </el-table-column>
      <el-table-column label="合同金额" width="130">
        <template #default="{ row }">{{ formatMoney((row as ServiceOrderSummary).amountMinor) }}</template>
      </el-table-column>
      <el-table-column label="方案" width="90">
        <template #default="{ row }">
          <span v-if="(row as ServiceOrderSummary).planVersion">v{{ (row as ServiceOrderSummary).planVersion }}</span>
          <span v-else class="muted">未出</span>
        </template>
      </el-table-column>
      <el-table-column label="付款" width="150">
        <template #default="{ row }">
          <span v-if="(row as ServiceOrderSummary).paidAt">{{ formatDateTime((row as ServiceOrderSummary).paidAt) }}</span>
          <span v-else class="muted">未付款</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <el-tag :type="STATUS[(row as ServiceOrderSummary).status].tag" effect="plain">
            {{ STATUS[(row as ServiceOrderSummary).status].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近更新" width="150">
        <template #default="{ row }">{{ formatDateTime((row as ServiceOrderSummary).updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as ServiceOrderSummary)">查看</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>
</template>

<style scoped>
.tabs {
  margin-bottom: 8px;
}

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
