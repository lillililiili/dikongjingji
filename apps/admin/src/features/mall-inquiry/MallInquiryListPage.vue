<script setup lang="ts">
import { onMounted, shallowRef } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  createMallInquiryApi,
  INQUIRY_STATUS_OPTIONS,
  inquiryStatusLabel,
  INVITATION_STATUS_LABELS,
  quoteAction,
  type InquiryFilters,
  type MallInquiryApi,
  type MallMerchantInquiry,
} from './mall-inquiry-api'
import QuoteDialog from './QuoteDialog.vue'

const props = defineProps<{
  api?: MallInquiryApi
}>()

const api = props.api ?? createMallInquiryApi(http)
const router = useRouter()
const list = usePagedList<MallMerchantInquiry, InquiryFilters>((filters, page, size) => api.list(filters, page, size),
  { status: '' })
const quoting = shallowRef<MallMerchantInquiry | null>(null)

function setStatus(value: string | number | boolean | undefined): void {
  list.filters.status = (value as InquiryFilters['status']) ?? ''
  void list.search()
}

function open(inquiry: MallMerchantInquiry): void {
  void router.push({ name: 'workbench-mall-inquiry', params: { inquiryId: inquiry.inquiryId } })
}

function quoted(): void {
  quoting.value = null
  void list.load()
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="商城询价"
    description="客户发给本机构的询价单。提交后 72 小时内可以报价、改价，也可以写明原因拒绝；客户只看到报价，看不到你们之间的其他信息。"
  >
    <div class="toolbar">
      <el-radio-group :model-value="list.filters.status" @change="setStatus">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button v-for="option in INQUIRY_STATUS_OPTIONS" :key="option.value" :value="option.value">
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
      row-key="inquiryId"
      empty-text="没有询价单"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="提交时间" width="150">
        <template #default="{ row }">{{ formatDateTime((row as MallMerchantInquiry).submittedAt) }}</template>
      </el-table-column>
      <el-table-column label="商品" min-width="180">
        <template #default="{ row }">
          {{ (row as MallMerchantInquiry).product.name }} × {{ (row as MallMerchantInquiry).quantity }}
        </template>
      </el-table-column>
      <el-table-column label="配置和用途" min-width="220">
        <template #default="{ row }"><span class="clip">{{ (row as MallMerchantInquiry).requirement }}</span></template>
      </el-table-column>
      <el-table-column label="报价截止" width="150">
        <template #default="{ row }">{{ formatDateTime((row as MallMerchantInquiry).quoteDeadline) }}</template>
      </el-table-column>
      <el-table-column label="询价单" width="90">
        <template #default="{ row }">{{ inquiryStatusLabel((row as MallMerchantInquiry).status) }}</template>
      </el-table-column>
      <el-table-column label="本机构" width="150">
        <template #default="{ row }">
          <el-tag effect="plain">{{ INVITATION_STATUS_LABELS[(row as MallMerchantInquiry).invitationStatus] }}</el-tag>
          <div v-if="(row as MallMerchantInquiry).myQuote" class="sub">
            合计 {{ formatMoney((row as MallMerchantInquiry).myQuote!.totalMinor) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button v-if="quoteAction(row as MallMerchantInquiry)" link type="primary"
                     @click="quoting = row as MallMerchantInquiry">{{ quoteAction(row as MallMerchantInquiry) }}</el-button>
          <el-button link @click="open(row as MallMerchantInquiry)">查看</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <QuoteDialog :api="api" :inquiry="quoting" @close="quoting = null" @done="quoted" />
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.clip {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  white-space: pre-wrap;
}

.sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
