<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  createProviderProfileReviewApi,
  OPS_REVIEW_STATUS,
  reviewStatus,
  type OpsProviderProfileRow,
  type ProviderProfileFilters,
} from './provider-profile-review-api'

const router = useRouter()
const api = createProviderProfileReviewApi(http)
const list = usePagedList<OpsProviderProfileRow, ProviderProfileFilters>(
  (filters, page, size) => api.list(filters, page, size), { status: '' })

function open(row: OpsProviderProfileRow): void {
  void router.push({ name: 'provider-profile-review', params: { providerId: row.providerId } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="服务商资料审核"
    description="服务商在工作台提交的主页资料（经营主体、服务方式、授权有效期、可交付清单、本地团队标签）。待审核的在前；通过后 App 服务商主页显示新的一份。"
    traced
  >
    <el-form class="filters" inline @submit.prevent="list.search()">
      <el-form-item label="状态">
        <el-select v-model="list.filters.status" clearable placeholder="全部" style="width: 140px" @change="list.search()">
          <el-option v-for="option in OPS_REVIEW_STATUS" :key="option.value" :value="option.value" :label="option.label" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button @click="list.load()">刷新</el-button>
      </el-form-item>
    </el-form>
    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="providerId"
      empty-text="没有服务商资料"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column prop="providerName" label="服务商" min-width="220" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="reviewStatus((row as OpsProviderProfileRow).status).tag" effect="plain">
            {{ reviewStatus((row as OpsProviderProfileRow).status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近提交" width="160">
        <template #default="{ row }">{{ formatDateTime((row as OpsProviderProfileRow).submittedAt) }}</template>
      </el-table-column>
      <el-table-column label="最近审核" width="160">
        <template #default="{ row }">{{ formatDateTime((row as OpsProviderProfileRow).reviewedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as OpsProviderProfileRow)">
            {{ (row as OpsProviderProfileRow).status === 'PENDING' ? '审核' : '查看' }}
          </el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>
</template>

<style scoped>
.filters {
  margin-bottom: 4px;
}
</style>
