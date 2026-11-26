<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import { createVenueApi, VENUE_STATUS_OPTIONS, venueStatus, type OpsVenueSummary, type VenueFilters } from './venue-api'

const router = useRouter()
const api = createVenueApi(http)
const list = usePagedList<OpsVenueSummary, VenueFilters>((filters, page, size) => api.list(filters, page, size),
  { keyword: '', cityCode: '', status: '' })

function open(venueId: string): void {
  void router.push({ name: 'venue-detail', params: { venueId } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="场馆与设备"
    description="到馆体验的场馆、项目、场次和馆内设备。新建的场馆是草稿，填齐封面、营业时间、项目和场次后才能上线到 App。"
    traced
  >
    <template #actions>
      <el-button @click="router.push({ name: 'online-experiences' })">线上体验项目</el-button>
      <el-button type="primary" @click="router.push({ name: 'venue-new' })">新建场馆</el-button>
    </template>

    <el-form inline @submit.prevent="list.search()">
      <el-form-item label="场馆">
        <el-input v-model="list.filters.keyword" clearable maxlength="20" placeholder="名称或地址" @clear="list.search()" />
      </el-form-item>
      <el-form-item label="城市编码">
        <el-input v-model="list.filters.cityCode" clearable maxlength="6" placeholder="如 370500" style="width: 120px"
                  @clear="list.search()" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="list.filters.status" clearable placeholder="全部" style="width: 120px" @change="list.search()">
          <el-option v-for="option in VENUE_STATUS_OPTIONS" :key="option.value" :label="option.label"
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
      row-key="venueId"
      origin-resource="VENUE"
      empty-text="没有符合条件的场馆"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="场馆" min-width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="open((row as OpsVenueSummary).venueId)">
            {{ (row as OpsVenueSummary).name }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="cityCode" label="城市编码" width="100" />
      <el-table-column prop="operatorName" label="运营机构" min-width="180" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="venueStatus((row as OpsVenueSummary).status).tag">
            {{ venueStatus((row as OpsVenueSummary).status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="projectCount" label="启用项目" width="90" />
      <el-table-column prop="upcomingSessionCount" label="未来场次" width="90" />
      <el-table-column prop="sortOrder" label="排序" width="70" />
      <el-table-column label="最后修改" width="160">
        <template #default="{ row }">{{ formatDateTime((row as OpsVenueSummary).updatedAt) }}</template>
      </el-table-column>
    </PagedTable>
  </PageSection>
</template>
