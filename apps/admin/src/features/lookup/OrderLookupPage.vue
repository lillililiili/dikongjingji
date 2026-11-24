<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  createLookupApi,
  ORDER_QUERY_MAX,
  SOURCE_LABELS,
  STATUS_LABELS,
  type LookupApi,
  type OrderFilters,
  type OrderSummary,
} from './lookup-api'

const props = defineProps<{
  api?: LookupApi
}>()

const api = props.api ?? createLookupApi(http)
const route = useRoute()
const router = useRouter()

/** 从用户详情点“查看订单”过来时带 buyerId。 */
const buyerId = typeof route.query.buyerId === 'string' ? route.query.buyerId : ''
const buyerName = typeof route.query.buyerName === 'string' ? route.query.buyerName : ''

const list = usePagedList<OrderSummary, OrderFilters>((filters, page, size) => api.orders(filters, page, size),
  { q: typeof route.query.q === 'string' ? route.query.q : '', buyerId, source: '', status: '', dates: null })

const sourceOptions = Object.entries(SOURCE_LABELS) as Array<[keyof typeof SOURCE_LABELS, string]>
const statusOptions = Object.entries(STATUS_LABELS) as Array<[keyof typeof STATUS_LABELS, string]>

function clearBuyer(): void {
  list.filters.buyerId = ''
  void router.replace({ name: 'ops-orders' })
  void list.search()
}

function open(order: OrderSummary): void {
  void router.push({ name: 'ops-order-detail', params: { orderId: order.id } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="订单查询"
    description="客户来问时，按手机号、订单号、微信支付单号、退款单号或售后单号查交易订单。只能看，不能改单和退款（退款由商家在工作台操作）。手机号、收货人已打码；打开订单详情会记录查看人和时间。"
  >
    <div class="toolbar">
      <el-input
        v-model="list.filters.q"
        data-testid="order-lookup-q"
        clearable
        :maxlength="ORDER_QUERY_MAX"
        placeholder="手机号 / 订单号 / 微信交易号 / 退款单号 / 售后单号"
        style="width: 340px"
        @keyup.enter="list.search()"
        @clear="list.search()"
      />
      <el-select v-model="list.filters.source" clearable placeholder="全部来源" style="width: 130px"
                 @change="list.search()">
        <el-option v-for="[value, text] in sourceOptions" :key="value" :label="text" :value="value" />
      </el-select>
      <el-select v-model="list.filters.status" clearable placeholder="全部状态" style="width: 130px"
                 @change="list.search()">
        <el-option v-for="[value, text] in statusOptions" :key="value" :label="text" :value="value" />
      </el-select>
      <el-date-picker
        v-model="list.filters.dates"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="下单开始"
        end-placeholder="下单结束"
        style="width: 260px"
        @change="list.search()"
      />
      <el-button type="primary" data-testid="order-lookup-search" @click="list.search()">查询</el-button>
    </div>
    <el-alert v-if="list.filters.buyerId" type="info" :closable="false" class="buyer-filter">
      只看 {{ buyerName || '这个用户' }} 下的订单。
      <el-button link type="primary" @click="clearBuyer">看全部</el-button>
    </el-alert>

    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="id"
      empty-text="没有查到订单"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="订单号" width="190">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as OrderSummary)">{{ (row as OrderSummary).orderNo }}</el-button>
        </template>
      </el-table-column>
      <el-table-column label="内容" min-width="150">
        <template #default="{ row }">
          <div>
            {{ (row as OrderSummary).title }}<span v-if="(row as OrderSummary).itemCount > 1" class="muted">
              等 {{ (row as OrderSummary).itemCount }} 项</span>
          </div>
          <div class="muted">{{ SOURCE_LABELS[(row as OrderSummary).source] }}</div>
        </template>
      </el-table-column>
      <el-table-column label="买家" width="120">
        <template #default="{ row }">
          <div>{{ (row as OrderSummary).buyer.displayName ?? '已注销' }}</div>
          <div class="muted">{{ (row as OrderSummary).buyer.maskedPhone ?? '' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="收款方" min-width="110">
        <template #default="{ row }">{{ (row as OrderSummary).merchantName }}</template>
      </el-table-column>
      <el-table-column label="实付 / 已退" width="120" align="right">
        <template #default="{ row }">
          <div>{{ formatMoney((row as OrderSummary).paidMinor) }}</div>
          <div v-if="(row as OrderSummary).refundedMinor > 0" class="muted">
            退 {{ formatMoney((row as OrderSummary).refundedMinor) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">{{ STATUS_LABELS[(row as OrderSummary).status] }}</template>
      </el-table-column>
      <el-table-column label="下单时间" width="130">
        <template #default="{ row }">{{ formatDateTime((row as OrderSummary).createdAt) }}</template>
      </el-table-column>
    </PagedTable>
  </PageSection>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.buyer-filter {
  margin-bottom: 12px;
}

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
