<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  createRentalWorkbenchApi,
  lesseeLabel,
  ORDER_STATUS,
  ORDER_STATUS_OPTIONS,
  type OrderFilters,
  type RentalOrder,
} from './rental-api'

const api = createRentalWorkbenchApi(http)
const router = useRouter()
const list = usePagedList<RentalOrder, OrderFilters>((filters, page, size) => api.orders(filters, page, size),
  { status: '' })

function open(row: RentalOrder): void {
  void router.push({ name: 'workbench-rental-order', params: { orderId: row.rentalOrderId } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection title="租赁订单" description="客户确认合同后生成。付清租金后录入交接；客户确认交接起计租，归还同样由你录入、客户确认。">
    <div class="toolbar">
      <el-select v-model="list.filters.status" clearable placeholder="全部状态" style="width: 220px"
                 @change="list.search()">
        <el-option v-for="option in ORDER_STATUS_OPTIONS" :key="option.value" :label="option.label"
                   :value="option.value" />
      </el-select>
      <el-button @click="list.load()">刷新</el-button>
    </div>
    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="rentalOrderId"
      empty-text="还没有租赁订单"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="合同编号" width="180">
        <template #default="{ row }">{{ (row as RentalOrder).contract.contractNo }}</template>
      </el-table-column>
      <el-table-column label="设备" min-width="200">
        <template #default="{ row }">
          <div>{{ (row as RentalOrder).contract.productName }}</div>
          <div class="muted">{{ (row as RentalOrder).contract.skuName }} · {{ (row as RentalOrder).contract.quantity }} 台</div>
        </template>
      </el-table-column>
      <el-table-column label="承租方" min-width="170">
        <template #default="{ row }">{{ lesseeLabel((row as RentalOrder).contract.lessee) }}</template>
      </el-table-column>
      <el-table-column label="租期" width="190">
        <template #default="{ row }">
          {{ (row as RentalOrder).contract.startDate }} 至 {{ (row as RentalOrder).contract.endDate }}
        </template>
      </el-table-column>
      <el-table-column label="合计" width="120">
        <template #default="{ row }">{{ formatMoney((row as RentalOrder).contract.totalMinor) }}</template>
      </el-table-column>
      <el-table-column label="付款" width="150">
        <template #default="{ row }">
          <span v-if="(row as RentalOrder).payment.paidAt">{{ formatDateTime((row as RentalOrder).payment.paidAt) }}</span>
          <span v-else class="muted">未付款</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="180">
        <template #default="{ row }">
          <el-tag :type="ORDER_STATUS[(row as RentalOrder).status].tag" effect="plain">
            {{ ORDER_STATUS[(row as RentalOrder).status].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as RentalOrder)">查看</el-button>
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
