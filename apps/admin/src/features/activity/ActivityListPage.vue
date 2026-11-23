<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatYuan } from '@/core/ops/ops-common'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'
import { createOrganizationApi, type DirectoryCity } from '@/features/organization/organization-api'

import {
  ACTIVITY_STATUS_OPTIONS,
  ACTIVITY_TYPE_OPTIONS,
  createActivityApi,
  labelOf,
  PRICE_MODE_OPTIONS,
  statusTag,
  type ActivityFilters,
  type OpsActivitySummary,
} from './activity-api'

const router = useRouter()
const api = createActivityApi(http)
const cities = ref<DirectoryCity[]>([])
const list = usePagedList<OpsActivitySummary, ActivityFilters>((filters, page, size) => api.list(filters, page, size),
  { keyword: '', cityCode: '', type: '', status: '' })

function open(activityId: string): void {
  void router.push({ name: 'activity-detail', params: { activityId } })
}

function price(row: OpsActivitySummary): string {
  return row.priceMode === 'PER_PERSON' ? `${formatYuan(row.priceAmountMinor)}/人` : labelOf(PRICE_MODE_OPTIONS, row.priceMode)
}

onMounted(async () => {
  void list.load()
  try {
    cities.value = await createOrganizationApi(http).cities()
  } catch {
    cities.value = []
  }
})
</script>

<template>
  <PageSection
    title="活动与场次"
    description="赛事、培训、研学、体验日：录入活动和场次，校验必填后上线；有人报名的场次不能删，只能停售。"
    traced
  >
    <template #actions>
      <el-button type="primary" @click="router.push({ name: 'activity-new' })">新建活动</el-button>
    </template>

    <el-form inline @submit.prevent="list.search()">
      <el-form-item label="活动">
        <el-input v-model="list.filters.keyword" clearable maxlength="20" placeholder="名称或地点"
                  @clear="list.search()" />
      </el-form-item>
      <el-form-item label="城市">
        <el-select v-model="list.filters.cityCode" clearable filterable placeholder="全部" style="width: 140px"
                   @change="list.search()">
          <el-option v-for="city in cities" :key="city.code" :label="city.name" :value="city.code" />
        </el-select>
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="list.filters.type" clearable placeholder="全部" style="width: 110px" @change="list.search()">
          <el-option v-for="option in ACTIVITY_TYPE_OPTIONS" :key="option.value" :label="option.label"
                     :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="list.filters.status" clearable placeholder="全部" style="width: 110px" @change="list.search()">
          <el-option v-for="option in ACTIVITY_STATUS_OPTIONS" :key="option.value" :label="option.label"
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
      row-key="activityId"
      empty-text="没有符合条件的活动"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="活动" min-width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="open((row as OpsActivitySummary).activityId)">
            {{ (row as OpsActivitySummary).title }}
          </el-button>
          <div class="sub">{{ (row as OpsActivitySummary).organizer.name ?? '机构已停用' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="80">
        <template #default="{ row }">{{ labelOf(ACTIVITY_TYPE_OPTIONS, (row as OpsActivitySummary).type) }}</template>
      </el-table-column>
      <el-table-column label="城市" width="90">
        <template #default="{ row }">{{ (row as OpsActivitySummary).cityName ?? (row as OpsActivitySummary).cityCode }}</template>
      </el-table-column>
      <el-table-column label="收费" width="110">
        <template #default="{ row }">{{ price(row as OpsActivitySummary) }}</template>
      </el-table-column>
      <el-table-column label="场次 / 已报名" width="120">
        <template #default="{ row }">
          {{ (row as OpsActivitySummary).sessionCount }} 场 / {{ (row as OpsActivitySummary).enrolledCount }} 人
        </template>
      </el-table-column>
      <el-table-column label="最近一场" width="150">
        <template #default="{ row }">{{ formatDateTime((row as OpsActivitySummary).nextStartsAt) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag((row as OpsActivitySummary).status)">
            {{ labelOf(ACTIVITY_STATUS_OPTIONS, (row as OpsActivitySummary).status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最后修改" width="150">
        <template #default="{ row }">{{ formatDateTime((row as OpsActivitySummary).updatedAt) }}</template>
      </el-table-column>
    </PagedTable>
  </PageSection>
</template>

<style scoped>
.sub {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
