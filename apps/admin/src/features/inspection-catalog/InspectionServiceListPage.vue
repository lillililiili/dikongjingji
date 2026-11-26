<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatYuan } from '@/core/ops/ops-common'
import { showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  createInspectionCatalogApi,
  type InspectionServiceFilters,
  type OpsInspectionCategory,
  type OpsInspectionServiceRow,
} from './inspection-catalog-api'
import { priceModeLabel, serviceStatus, SERVICE_STATUS_OPTIONS } from './inspection-catalog-rules'
import InspectionCategoryPanel from './InspectionCategoryPanel.vue'
import PortalContentPanel from '@/features/page-content/PortalContentPanel.vue'

const router = useRouter()
const api = createInspectionCatalogApi(http)
const tab = ref<'services' | 'categories' | 'portal'>('services')
const categories = ref<OpsInspectionCategory[]>([])
const list = usePagedList<OpsInspectionServiceRow, InspectionServiceFilters>(
  (filters, page, size) => api.list(filters, page, size), { keyword: '', categoryCode: '', status: '' })

async function loadCategories(): Promise<void> {
  try {
    categories.value = await api.categories()
  } catch (failure) {
    showError(failure)
  }
}

function open(serviceId: string): void {
  void router.push({ name: 'inspection-service', params: { serviceId } })
}

function price(row: OpsInspectionServiceRow): string {
  if (row.priceMode === 'PROJECT_QUOTE') return '按项目报价'
  if (!row.priceMode) return '未填'
  return `${priceModeLabel(row.priceMode)} ${formatYuan(row.startingAmountMinor)}`
}

onMounted(() => {
  void list.load()
  void loadCategories()
})
</script>

<template>
  <PageSection
    title="巡检服务目录"
    description="上架的服务出现在 App 首页“专业服务 → 巡检”的目录、详情和首页推荐里（还要覆盖用户所在城市）；未上架的只在这里能看到。"
    traced
  >
    <template #actions>
      <el-button v-if="tab === 'services'" type="primary" @click="router.push({ name: 'inspection-service-new' })">
        新建服务
      </el-button>
    </template>

    <el-tabs v-model="tab">
      <el-tab-pane label="服务" name="services">
        <el-form class="filters" inline @submit.prevent="list.search()">
          <el-form-item label="关键词">
            <el-input v-model="list.filters.keyword" clearable placeholder="标题或服务编号" style="width: 200px" />
          </el-form-item>
          <el-form-item label="类目">
            <el-select v-model="list.filters.categoryCode" clearable placeholder="全部" style="width: 140px"
                       @change="list.search()">
              <el-option v-for="category in categories" :key="category.code" :value="category.code"
                         :label="category.parentCode ? `　${category.name}` : category.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="list.filters.status" clearable placeholder="全部" style="width: 120px"
                       @change="list.search()">
              <el-option v-for="option in SERVICE_STATUS_OPTIONS" :key="option.value" :value="option.value"
                         :label="option.label" />
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
          row-key="serviceId"
          origin-resource="INSPECTION"
          empty-text="没有符合条件的巡检服务"
          @change-page="list.changePage"
          @change-size="list.changeSize"
          @retry="list.load()"
        >
          <el-table-column label="服务" min-width="240">
            <template #default="{ row }">
              <el-button link type="primary" @click="open((row as OpsInspectionServiceRow).serviceId)">
                {{ (row as OpsInspectionServiceRow).title }}
              </el-button>
              <div class="sub">{{ (row as OpsInspectionServiceRow).serviceNo ?? '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="类目" width="100">
            <template #default="{ row }">{{ (row as OpsInspectionServiceRow).categoryName ?? '未填' }}</template>
          </el-table-column>
          <el-table-column label="服务商" min-width="160">
            <template #default="{ row }">{{ (row as OpsInspectionServiceRow).providerName ?? '机构已停用' }}</template>
          </el-table-column>
          <el-table-column label="价格" width="160">
            <template #default="{ row }">{{ price(row as OpsInspectionServiceRow) }}</template>
          </el-table-column>
          <el-table-column label="覆盖城市" width="90">
            <template #default="{ row }">{{ (row as OpsInspectionServiceRow).cityCount }} 个</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="serviceStatus((row as OpsInspectionServiceRow).status).tag">
                {{ serviceStatus((row as OpsInspectionServiceRow).status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最后修改" width="160">
            <template #default="{ row }">{{ formatDateTime((row as OpsInspectionServiceRow).updatedAt) }}</template>
          </el-table-column>
        </PagedTable>
      </el-tab-pane>

      <el-tab-pane label="类目" name="categories">
        <InspectionCategoryPanel :api="api" :categories="categories" @changed="loadCategories" />
      </el-tab-pane>
      <el-tab-pane label="入口配置" name="portal" lazy>
        <PortalContentPanel :categories="categories" />
      </el-tab-pane>
    </el-tabs>
  </PageSection>
</template>

<style scoped>
.sub {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
