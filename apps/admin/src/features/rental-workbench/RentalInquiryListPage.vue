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
  HANDOVER_MODE_LABELS,
  INQUIRY_STATUS,
  INQUIRY_STATUS_OPTIONS,
  lesseeLabel,
  type InquiryFilters,
  type RentalInquiry,
} from './rental-api'

const api = createRentalWorkbenchApi(http)
const router = useRouter()
const list = usePagedList<RentalInquiry, InquiryFilters>((filters, page, size) => api.inquiries(filters, page, size),
  { status: '' })

function open(row: RentalInquiry): void {
  void router.push({ name: 'workbench-rental-inquiry', params: { inquiryId: row.inquiryId } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection title="租赁询价" description="客户在商城租赁商品上发来的询价。报价后客户在 App 里确认合同、付清租金；7 天内没有报价会自动关闭。">
    <div class="toolbar">
      <el-select v-model="list.filters.status" clearable placeholder="全部状态" style="width: 200px"
                 @change="list.search()">
        <el-option v-for="option in INQUIRY_STATUS_OPTIONS" :key="option.value" :label="option.label"
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
      row-key="inquiryId"
      empty-text="还没有租赁询价"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="提交时间" width="150">
        <template #default="{ row }">{{ formatDateTime((row as RentalInquiry).submittedAt) }}</template>
      </el-table-column>
      <el-table-column label="设备" min-width="200">
        <template #default="{ row }">
          <div>{{ (row as RentalInquiry).item.productName }}</div>
          <div class="muted">{{ (row as RentalInquiry).item.skuName }}</div>
        </template>
      </el-table-column>
      <el-table-column label="承租方" min-width="180">
        <template #default="{ row }">{{ lesseeLabel((row as RentalInquiry).lessee) }}</template>
      </el-table-column>
      <el-table-column label="租期" width="200">
        <template #default="{ row }">
          <div>{{ (row as RentalInquiry).startDate }} 起 {{ (row as RentalInquiry).days }} 天</div>
          <div class="muted">
            {{ (row as RentalInquiry).quantity }} 台 · {{ HANDOVER_MODE_LABELS[(row as RentalInquiry).handoverMode] }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="报价合计" width="120">
        <template #default="{ row }">{{ formatMoney((row as RentalInquiry).quote?.totalMinor) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="170">
        <template #default="{ row }">
          <el-tag :type="INQUIRY_STATUS[(row as RentalInquiry).status].tag" effect="plain">
            {{ INQUIRY_STATUS[(row as RentalInquiry).status].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as RentalInquiry)">查看</el-button>
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
