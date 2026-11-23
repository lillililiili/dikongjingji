<script setup lang="ts">
import { onMounted, shallowRef } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  canCompletePickup,
  canShip,
  createMerchantOrdersApi,
  DELIVERY_MODE_LABELS,
  ORDER_TABS,
  orderStatusLabel,
  type MerchantOrdersApi,
  type OrderFilters,
  type OrderTabKey,
  type TradeOrder,
} from './merchant-orders-api'
import PickupDialog from './PickupDialog.vue'
import ShipDialog from './ShipDialog.vue'

const props = defineProps<{
  /** 测试时传入；页面里用全站的接口客户端。 */
  api?: MerchantOrdersApi
}>()

const api = props.api ?? createMerchantOrdersApi(http)
const router = useRouter()
const list = usePagedList<TradeOrder, OrderFilters>((filters, page, size) => api.list(filters, page, size),
  { tab: 'TO_SHIP' })
const shipping = shallowRef<TradeOrder | null>(null)
const pickingUp = shallowRef<TradeOrder | null>(null)

function setTab(value: string | number | boolean | undefined): void {
  list.filters.tab = (value as OrderTabKey) ?? 'ALL'
  void list.search()
}

function linesSummary(order: TradeOrder): string {
  const first = order.lines[0]
  if (!first) return '—'
  const count = order.lines.reduce((sum, line) => sum + line.quantity, 0)
  return order.lines.length > 1 ? `${first.productName} 等，共 ${count} 件` : `${first.productName} × ${first.quantity}`
}

function open(order: TradeOrder): void {
  void router.push({ name: 'workbench-mall-order', params: { orderId: order.orderId } })
}

function finished(): void {
  shipping.value = null
  pickingUp.value = null
  void list.load()
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="商城订单"
    description="本机构卖出的商城订单。快递单付款后发货，自提单在买家到店时核销取货码；退款可以部分退，钱按原路退回买家。"
  >
    <div class="toolbar">
      <el-radio-group :model-value="list.filters.tab" @change="setTab">
        <el-radio-button v-for="tab in ORDER_TABS" :key="tab.key" :value="tab.key">{{ tab.label }}</el-radio-button>
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
      row-key="orderId"
      empty-text="没有这类订单"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="订单编号" width="190">
        <template #default="{ row }">{{ (row as TradeOrder).orderNo }}</template>
      </el-table-column>
      <el-table-column label="下单时间" width="150">
        <template #default="{ row }">{{ formatDateTime((row as TradeOrder).createdAt) }}</template>
      </el-table-column>
      <el-table-column label="商品" min-width="220">
        <template #default="{ row }">{{ linesSummary(row as TradeOrder) }}</template>
      </el-table-column>
      <el-table-column label="配送" width="100">
        <template #default="{ row }">
          {{ (row as TradeOrder).delivery ? DELIVERY_MODE_LABELS[(row as TradeOrder).delivery!.mode] : '—' }}
        </template>
      </el-table-column>
      <el-table-column label="实付" width="120" align="right">
        <template #default="{ row }">{{ formatMoney((row as TradeOrder).amounts.paidMinor) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag effect="plain">{{ orderStatusLabel(row as TradeOrder) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canShip(row as TradeOrder)" link type="primary" @click="shipping = row as TradeOrder">
            发货
          </el-button>
          <el-button v-if="canCompletePickup(row as TradeOrder)" link type="primary"
                     @click="pickingUp = row as TradeOrder">核销</el-button>
          <el-button link @click="open(row as TradeOrder)">查看</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <ShipDialog :api="api" :order="shipping" @close="shipping = null" @done="finished" />
  <PickupDialog :api="api" :order="pickingUp" @close="pickingUp = null" @done="finished" />
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
</style>
