<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { useChangeHistory } from '@/core/ops/use-change-history'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  CITY_STATUS_OPTIONS,
  cityStatusLabel,
  cityStatusTag,
  createCityApi,
  type CityFilters,
  type OpsCity,
} from './city-api'
import CityStatusDialog from './CityStatusDialog.vue'

const api = createCityApi(http)
const list = usePagedList<OpsCity, CityFilters>((filters, page, size) => api.list(filters, page, size),
  { keyword: '', serviceStatus: '' })

const editing = ref<OpsCity | null>(null)
const historyCity = ref<OpsCity | null>(null)
const { changes: history, loading: historyLoading, error: historyError, load: loadHistory } = useChangeHistory(api.changes)

function openHistory(city: OpsCity): void {
  historyCity.value = city
  void loadHistory(city.cityCode)
}

function changed(): void {
  editing.value = null
  void list.load()
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="城市开通"
    description="已开通、筹备中的城市出现在 App 城市面板（照原型 CitySheet 分两组）；未开通的只能搜索到。"
    traced
  >
    <template #actions><el-button @click="$router.push('/cities/map-places')">地图地点</el-button></template>
    <el-form class="filters" inline @submit.prevent="list.search()">
      <el-form-item label="城市">
        <el-input
          v-model="list.filters.keyword"
          clearable
          maxlength="20"
          placeholder="名称、拼音或 6 位编码"
          @clear="list.search()"
        />
      </el-form-item>
      <el-form-item label="开通状态">
        <el-select v-model="list.filters.serviceStatus" clearable placeholder="全部" style="width: 140px"
                   @change="list.search()">
          <el-option v-for="option in CITY_STATUS_OPTIONS" :key="option.value" :label="option.label"
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
      row-key="cityCode"
      empty-text="没有符合条件的城市"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column prop="cityName" label="城市" min-width="120" />
      <el-table-column prop="provinceName" label="省份" min-width="140" />
      <el-table-column prop="cityCode" label="编码" width="100" />
      <el-table-column label="开通状态" width="110">
        <template #default="{ row }">
          <el-tag :type="cityStatusTag((row as OpsCity).serviceStatus)" effect="light">
            {{ cityStatusLabel((row as OpsCity).serviceStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最后改动" width="160">
        <template #default="{ row }">{{ formatDateTime((row as OpsCity).serviceStatusChangedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="editing = row as OpsCity">改状态</el-button>
          <el-button link @click="openHistory(row as OpsCity)">改动记录</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <CityStatusDialog :api="api" :city="editing" @close="editing = null" @changed="changed" />

  <el-drawer
    :model-value="historyCity !== null"
    :title="historyCity ? `改动记录：${historyCity.cityName}` : ''"
    size="420px"
    @close="historyCity = null"
  >
    <div v-loading="historyLoading">
      <el-alert v-if="historyError" type="error" :title="historyError" :closable="false">
        <el-button v-if="historyCity" size="small" @click="loadHistory(historyCity.cityCode)">重试改动记录</el-button>
      </el-alert>
      <el-empty v-else-if="!historyLoading && history.length === 0" description="还没有改过开通状态" />
      <el-timeline v-else>
        <el-timeline-item
          v-for="change in history"
          :key="change.changeId"
          :timestamp="formatDateTime(change.changedAt)"
          placement="top"
        >
          <p class="history__title">
            {{ cityStatusLabel(change.fromStatus) }} → {{ cityStatusLabel(change.toStatus) }}
            <span class="history__who">{{ change.operatorName }}</span>
          </p>
          <p class="history__note">{{ change.note }}</p>
        </el-timeline-item>
      </el-timeline>
    </div>
  </el-drawer>
</template>

<style scoped>
.filters {
  margin-bottom: 4px;
}

.history__title {
  margin: 0;
  font-weight: 500;
}

.history__who {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: var(--admin-text-secondary);
}

.history__note {
  margin: 4px 0 0;
  color: var(--admin-text-secondary);
}
</style>
