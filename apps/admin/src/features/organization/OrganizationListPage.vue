<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  createOrganizationApi,
  ORGANIZATION_TYPE_OPTIONS,
  organizationTypeLabel,
  QUALIFICATION_STATUS_OPTIONS,
  qualificationStatus,
  type OpsOrganization,
  type OpsOrganizationDetail,
  type OrganizationFilters,
} from './organization-api'
import OrganizationCreateDialog from './OrganizationCreateDialog.vue'

const router = useRouter()
const api = createOrganizationApi(http)
const list = usePagedList<OpsOrganization, OrganizationFilters>((filters, page, size) => api.list(filters, page, size),
  { keyword: '', type: '', qualificationStatus: '' })
const creating = ref(false)

function open(organizationId: string): void {
  void router.push({ name: 'organization-detail', params: { organizationId } })
}

function created(detail: OpsOrganizationDetail): void {
  creating.value = false
  open(detail.organization.organizationId)
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="机构入驻与资质审核"
    description="商家、服务商、主办和场馆都在这里建档、录资质、审核、开账号。审核通过后 App 上显示“资质已审核”。"
    traced
  >
    <template #actions>
      <el-button type="primary" @click="creating = true">新建机构</el-button>
    </template>

    <el-form inline @submit.prevent="list.search()">
      <el-form-item label="机构">
        <el-input v-model="list.filters.keyword" clearable maxlength="60" placeholder="名称或机构编码"
                  @clear="list.search()" />
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="list.filters.type" clearable placeholder="全部" style="width: 130px" @change="list.search()">
          <el-option v-for="option in ORGANIZATION_TYPE_OPTIONS" :key="option.value" :label="option.label"
                     :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="资质">
        <el-select v-model="list.filters.qualificationStatus" clearable placeholder="全部" style="width: 130px"
                   @change="list.search()">
          <el-option v-for="option in QUALIFICATION_STATUS_OPTIONS" :key="option.value" :label="option.label"
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
      row-key="organizationId"
      empty-text="没有符合条件的机构"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="机构" min-width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="open((row as OpsOrganization).organizationId)">
            {{ (row as OpsOrganization).name }}
          </el-button>
          <div class="code">{{ (row as OpsOrganization).organizationCode }}</div>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ organizationTypeLabel((row as OpsOrganization).type) }}</template>
      </el-table-column>
      <el-table-column label="城市" width="110">
        <template #default="{ row }">{{ (row as OpsOrganization).cityName ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="资质" width="100">
        <template #default="{ row }">
          <el-tag :type="qualificationStatus((row as OpsOrganization).qualificationStatus).tag">
            {{ qualificationStatus((row as OpsOrganization).qualificationStatus).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="审核通过" width="160">
        <template #default="{ row }">{{ formatDateTime((row as OpsOrganization).qualificationReviewedAt) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">{{ (row as OpsOrganization).status === 'ACTIVE' ? '在用' : '停用' }}</template>
      </el-table-column>
      <el-table-column label="新建时间" width="160">
        <template #default="{ row }">{{ formatDateTime((row as OpsOrganization).createdAt) }}</template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <OrganizationCreateDialog :api="api" :open="creating" @close="creating = false" @created="created" />
</template>

<style scoped>
.code {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
