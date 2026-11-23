<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'
import { SUBMISSION_STATUS_LABELS, SUBMISSION_STATUS_TAGS } from '@/features/merchant-profile/merchant-profile-api'

import {
  createMerchantProfileReviewApi,
  REVIEW_LIMITS,
  REVIEW_STATUS_OPTIONS,
  type MallMerchantSubmission,
  type ReviewFilters,
} from './merchant-profile-review-api'

const router = useRouter()
const api = createMerchantProfileReviewApi(http)
const list = usePagedList<MallMerchantSubmission, ReviewFilters>(
  (filters, page, size) => api.list(filters, page, size), { status: '', keyword: '' })

function open(submissionId: string): void {
  void router.push({ name: 'merchant-profile-review-detail', params: { submissionId } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="商家资料审核"
    description="商家在工作台提交的商家资料（经营主体、简介、可售说明、授权有效期、品类、默认开票方和售后责任方）。通过后整份显示在 App 的商家主页上；待审核的排在前面。"
    traced
  >
    <el-form inline @submit.prevent="list.search()">
      <el-form-item label="商家">
        <el-input v-model="list.filters.keyword" clearable :maxlength="REVIEW_LIMITS.keyword" placeholder="机构名称或经营主体"
                  @clear="list.search()" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="list.filters.status" clearable placeholder="全部" style="width: 160px" @change="list.search()">
          <el-option v-for="option in REVIEW_STATUS_OPTIONS" :key="option.value" :label="option.label"
                     :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" native-type="submit">查询</el-button>
        <el-button @click="list.reset()">重置</el-button>
      </el-form-item>
    </el-form>

    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="submissionId"
      empty-text="没有符合条件的提交"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="商家" min-width="200">
        <template #default="{ row }">
          <el-button link type="primary" @click="open((row as MallMerchantSubmission).submissionId)">
            {{ (row as MallMerchantSubmission).organizationName }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="经营主体" min-width="220">
        <template #default="{ row }">{{ (row as MallMerchantSubmission).draft.businessEntity }}</template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag :type="SUBMISSION_STATUS_TAGS[(row as MallMerchantSubmission).status]">
            {{ SUBMISSION_STATUS_LABELS[(row as MallMerchantSubmission).status] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="提交人" width="120">
        <template #default="{ row }">{{ (row as MallMerchantSubmission).submittedByName }}</template>
      </el-table-column>
      <el-table-column label="提交时间" width="160">
        <template #default="{ row }">{{ formatDateTime((row as MallMerchantSubmission).submittedAt) }}</template>
      </el-table-column>
      <el-table-column label="审核" width="200">
        <template #default="{ row }">
          <template v-if="(row as MallMerchantSubmission).reviewedAt">
            {{ (row as MallMerchantSubmission).reviewedByName ?? '—' }} ·
            {{ formatDateTime((row as MallMerchantSubmission).reviewedAt) }}
          </template>
          <template v-else>—</template>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open((row as MallMerchantSubmission).submissionId)">
            {{ (row as MallMerchantSubmission).status === 'SUBMITTED' ? '审核' : '查看' }}
          </el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>
</template>
