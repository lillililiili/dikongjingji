<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { http } from '@/core/http'
import { errorMessage, isApiError } from '@/core/http/api-error'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'
import PagedTable from '@/core/ui/PagedTable.vue'
import { usePagedList } from '@/core/ui/use-paged-list'
import CityPicker from '@/features/content/components/CityPicker.vue'
import { createMapPlacesApi, newPlaceId, type Place, type PlaceChange, type PlaceFilters, type PlaceInput } from './map-places-api'

const api = createMapPlacesApi(http)
const list = usePagedList<Place, PlaceFilters>(api.list, { cityCode: '', keyword: '', status: '' })
const editing = ref<{ id: string; form: PlaceInput } | null>(null)
const saving = ref(false)
const conflict = ref(false)
const error = ref('')
const history = ref<Place | null>(null)
const changes = ref<PlaceChange[]>([])
const loadingHistory = ref(false)
const historyError = ref('')
onMounted(list.load)

function edit(row?: Place) {
  error.value = ''
  conflict.value = false
  editing.value = {
    id: row?.id ?? newPlaceId(),
    form: { cityCode: row?.cityCode ?? list.filters.cityCode, name: row?.name ?? '', address: row?.address ?? '',
      latitude: row?.latitude, longitude: row?.longitude, sortOrder: row?.sortOrder ?? 0,
      version: row?.version ?? null, published: row?.published ?? false },
  }
}
async function save() {
  if (saving.value || !editing.value) return
  const { id, form } = editing.value
  if (!form.cityCode || !form.name.trim() || !form.address.trim() || !Number.isFinite(form.latitude)
      || !Number.isFinite(form.longitude) || !Number.isInteger(form.sortOrder)) {
    error.value = '请选择城市，并填写地点名称、地址、WGS84 经纬度和排序。'
    return
  }
  saving.value = true
  try {
    if (!await confirmAction({ title: '保存地图地点', message: form.published
      ? '保存后，用户可以在所选城市搜索到此地点。' : '保存后，用户将无法在地点搜索中看到这条记录。' })) return
    await api.save(id, { ...form, name: form.name.trim(), address: form.address.trim() })
    editing.value = null
    notifyRecorded('保存地点', '改动记录')
    await list.load()
  } catch (failure) {
    error.value = errorMessage(failure)
    if (isApiError(failure, 'OPS_VERSION_CONFLICT')) { conflict.value = true; await list.load() }
  }
  finally { saving.value = false }
}
async function toggle(row: Place) {
  if (saving.value) return
  const verb = row.published ? '停用' : '启用'
  saving.value = true
  try {
    if (!await confirmAction({ title: verb + '地点', message: row.published
      ? '停用后，App 搜索结果将移除“' + row.name + '”。' : '启用后，App 用户可搜索到“' + row.name + '”。',
      danger: row.published })) return
    await api.save(row.id, { cityCode: row.cityCode, name: row.name, address: row.address, latitude: row.latitude,
      longitude: row.longitude, sortOrder: row.sortOrder, published: !row.published, version: row.version })
    notifyRecorded(verb, '改动记录')
    await list.load()
  } catch (failure) { showError(failure); await list.load() }
  finally { saving.value = false }
}
async function showChanges(row: Place) {
  history.value = row
  changes.value = []
  historyError.value = ''
  loadingHistory.value = true
  try {
    const result = await api.changes(row.id)
    if (history.value?.id === row.id) changes.value = result
  } catch (failure) { if (history.value?.id === row.id) historyError.value = errorMessage(failure) }
  finally { if (history.value?.id === row.id) loadingHistory.value = false }
}
function summary(place: Place) {
  return [place.cityName, place.name, place.address, place.longitude + ' / ' + place.latitude,
    '排序 ' + place.sortOrder, place.published ? '启用' : '停用'].join(' · ')
}
</script>

<template>
  <PageSection title="地图地点" description="管理巡检预约按城市搜索的候选地点。" traced>
    <template #actions><el-button type="primary" @click="edit()">新增地点</el-button></template>
    <el-form inline @submit.prevent="list.search()">
      <el-form-item label="城市"><CityPicker v-model="list.filters.cityCode" @update:model-value="list.search()" /></el-form-item>
      <el-form-item label="关键词"><el-input v-model="list.filters.keyword" placeholder="名称或地址" clearable maxlength="120" @clear="list.search()" /></el-form-item>
      <el-form-item label="状态"><el-select v-model="list.filters.status" style="width: 115px" @change="list.search()">
        <el-option label="全部" value="" /><el-option label="启用" value="on" /><el-option label="停用" value="off" />
      </el-select></el-form-item>
      <el-form-item><el-button type="primary" native-type="submit">查询</el-button><el-button @click="list.reset()">重置</el-button></el-form-item>
    </el-form>
    <PagedTable :items="list.items.value" :total="list.total.value" :page="list.page.value" :size="list.size.value"
      :loading="list.loading.value" :error="list.error.value" row-key="id" empty-text="没有符合条件的地点"
      @change-page="list.changePage" @change-size="list.changeSize" @retry="list.load()">
      <el-table-column prop="cityName" label="城市" width="96" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="address" label="地址" min-width="180" />
      <el-table-column label="经度 / 纬度（WGS84）" min-width="170"><template #default="{row}">{{ row.longitude }} / {{ row.latitude }}</template></el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="76" />
      <el-table-column label="状态" width="78"><template #default="{row}"><el-tag :type="row.published ? 'success' : 'info'">{{ row.published ? '启用' : '停用' }}</el-tag></template></el-table-column>
      <el-table-column label="来源" width="108"><template #default="{row}">{{ row.sample ? (row.version ? '示例 · 已维护' : '开发示例') : '运营录入' }}</template></el-table-column>
      <el-table-column label="操作" width="190" fixed="right"><template #default="{row}">
        <el-button link type="primary" @click="edit(row as Place)">编辑</el-button>
        <el-button link :disabled="saving" @click="toggle(row as Place)">{{ row.published ? '停用' : '启用' }}</el-button>
        <el-button link @click="showChanges(row as Place)">改动记录</el-button>
      </template></el-table-column>
    </PagedTable>
  </PageSection>
  <el-dialog :model-value="!!editing" :title="editing?.form.version === null ? '新增地图地点' : '编辑地图地点'"
    width="min(560px, 94vw)" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" @close="editing = null">
    <el-form v-if="editing" label-width="105px" @submit.prevent="save">
      <el-form-item label="所属城市" required><CityPicker v-model="editing.form.cityCode" /></el-form-item>
      <el-form-item label="名称" required><el-input v-model="editing.form.name" maxlength="120" /></el-form-item>
      <el-form-item label="详细地址" required><el-input v-model="editing.form.address" maxlength="240" /></el-form-item>
      <el-form-item label="WGS84 经度" required><el-input-number v-model="editing.form.longitude" :min="-180" :max="180" :controls="false" /></el-form-item>
      <el-form-item label="WGS84 纬度" required><el-input-number v-model="editing.form.latitude" :min="-90" :max="90" :controls="false" /></el-form-item>
      <el-form-item label="排序" required><el-input-number v-model="editing.form.sortOrder" :min="0" :max="2147483647" :precision="0" /></el-form-item>
      <el-form-item label="启用"><el-switch v-model="editing.form.published" /></el-form-item>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    </el-form>
    <template #footer><el-button :disabled="saving" @click="editing = null">{{ conflict ? '返回最新列表' : '取消' }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="conflict" @click="save">保存</el-button></template>
  </el-dialog>
  <el-drawer :model-value="!!history" :title="(history?.name ?? '') + ' · 改动记录'" size="min(520px, 94vw)" @close="history = null">
    <div v-loading="loadingHistory">
      <el-alert v-if="historyError" :title="historyError" type="error" :closable="false"><el-button v-if="history" @click="showChanges(history)">重试</el-button></el-alert>
      <el-empty v-else-if="!loadingHistory && !changes.length" description="暂无改动记录" />
      <el-timeline v-else><el-timeline-item v-for="entry in changes" :key="entry.version" :timestamp="formatDateTime(entry.changedAt)">
        <strong>{{ entry.operatorName }} · 版本 {{ entry.version }}</strong>
        <p v-if="entry.previous">修改前　{{ summary(entry.previous) }}</p>
        <p>{{ entry.previous ? '修改后' : '新增' }}　{{ summary(entry.current) }}</p>
      </el-timeline-item></el-timeline>
    </div>
  </el-drawer>
</template>
