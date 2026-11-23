<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatYuan } from '@/core/ops/ops-common'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import { createProServiceApi, type OpsProServiceRow, type ProServiceFilters } from './proservice-api'
import { CATEGORY_OPTIONS, statusOption, STATUS_OPTIONS, TYPE_OPTIONS, typeOption } from './proservice-rules'

const router = useRouter()
const api = createProServiceApi(http)
const list = usePagedList<OpsProServiceRow, ProServiceFilters>((filters, page, size) => api.list(filters, page, size),
  { keyword: '', type: '', status: '' })

function open(serviceId: string): void {
  void router.push({ name: 'pro-service', params: { serviceId } })
}

function price(row: OpsProServiceRow): string {
  return row.priceMode === 'PROJECT' ? '按项目报价' : `${formatYuan(row.priceAmountMinor)} 起`
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="专业服务目录"
    description="航拍、测绘、设备维护、技术支持四类服务。上架的出现在 App 专业服务目录、详情和服务商主页里（还要在用户所在城市上架）。"
    traced
  >
    <template #actions>
      <el-button type="primary" @click="router.push({ name: 'pro-service-new' })">新建服务</el-button>
    </template>

    <el-form class="filters" inline @submit.prevent="list.search()">
      <el-form-item label="关键词">
        <el-input v-model="list.filters.keyword" clearable placeholder="标题" style="width: 200px" />
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="list.filters.type" clearable placeholder="全部" style="width: 130px" @change="list.search()">
          <el-option v-for="option in TYPE_OPTIONS" :key="option.value" :value="option.value" :label="option.label" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="list.filters.status" clearable placeholder="全部" style="width: 120px"
                   @change="list.search()">
          <el-option v-for="option in STATUS_OPTIONS" :key="option.value" :value="option.value" :label="option.label" />
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
      empty-text="没有符合条件的专业服务"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="服务" min-width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="open((row as OpsProServiceRow).serviceId)">
            {{ (row as OpsProServiceRow).title }}
          </el-button>
          <el-tag v-if="(row as OpsProServiceRow).sample" size="small" type="warning" class="sample">示例</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ typeOption((row as OpsProServiceRow).type).label }}</template>
      </el-table-column>
      <el-table-column label="场景" width="80">
        <template #default="{ row }">
          {{ CATEGORY_OPTIONS.find((option) => option.value === (row as OpsProServiceRow).categoryCode)?.label }}
        </template>
      </el-table-column>
      <el-table-column label="服务商" min-width="160">
        <template #default="{ row }">{{ (row as OpsProServiceRow).providerName ?? '机构已停用' }}</template>
      </el-table-column>
      <el-table-column label="价格" width="130">
        <template #default="{ row }">{{ price(row as OpsProServiceRow) }}</template>
      </el-table-column>
      <el-table-column label="上架城市" width="90">
        <template #default="{ row }">{{ (row as OpsProServiceRow).cityCount }} 个</template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="70" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusOption((row as OpsProServiceRow).status).tag">
            {{ statusOption((row as OpsProServiceRow).status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最后修改" width="160">
        <template #default="{ row }">{{ formatDateTime((row as OpsProServiceRow).updatedAt) }}</template>
      </el-table-column>
    </PagedTable>
  </PageSection>
</template>

<style scoped>
.sample {
  margin-left: 6px;
}
</style>
