<script setup lang="ts">
/**
 * 一个场馆：资料（运营机构、城市、封面、地址、电话、营业时间、检票与迟到规则、信息条目、排序）、上线与下线、项目与场次、
 * 馆内设备、改动记录。/venues/new 是新建草稿（保存后才能加项目和设备）。
 */
import type { FormInstance } from 'element-plus'
import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { newIdempotencyKey } from '@/core/http/api-client'
import { errorMessage } from '@/core/http/api-error'
import { useChangeHistory } from '@/core/ops/use-change-history'
import ChangeLogTable from '@/core/ops/ChangeLogTable.vue'
import MediaField from '@/core/ops/MediaField.vue'
import { describeMissing, formatYuan } from '@/core/ops/ops-common'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'
import { createCityApi } from '@/features/city/city-api'
import { createOrganizationApi } from '@/features/organization/organization-api'

import DeviceDialog from './DeviceDialog.vue'
import ProjectDialog from './ProjectDialog.vue'
import SessionGenerateDialog from './SessionGenerateDialog.vue'
import SessionsDrawer from './SessionsDrawer.vue'
import {
  createVenueApi,
  deviceCategoryLabel,
  PUBLISH_FIELD_LABELS,
  SUBJECT_LABELS,
  trialModeLabel,
  VENUE_ACTION_LABELS,
  venueStatus,
  type OpsVenueDetail,
  type OpsVenueDevice,
  type OpsVenueProject,
} from './venue-api'
import {
  emptyVenueDraft,
  entryRuleText,
  openingHoursProblem,
  openingNotePreview,
  projectSummaryPreview,
  VENUE_LIMITS,
  venueDraftOf,
  venueRules,
} from './venue-rules'

const route = useRoute()
const router = useRouter()
const api = createVenueApi(http)
const cityApi = createCityApi(http)
const organizationApi = createOrganizationApi(http)

const venueId = computed(() => (typeof route.params.venueId === 'string' ? route.params.venueId : ''))
const detail = shallowRef<OpsVenueDetail | null>(null)
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)
const formRef = ref<FormInstance>()
const form = reactive(emptyVenueDraft())
const lateEntryLimited = ref(false)
let createKey = newIdempotencyKey()

const operatorOptions = ref<Array<{ value: string, label: string }>>([])
const cityOptions = ref<Array<{ value: string, label: string }>>([])
const { changes, loading: changesLoading, error: changesError, load: loadChanges } = useChangeHistory(api.changes)

const projectDialog = reactive({ open: false, project: null as OpsVenueProject | null })
const generateDialog = reactive({ open: false, project: null as OpsVenueProject | null })
const sessionsDrawer = reactive({ open: false, project: null as OpsVenueProject | null })
const deviceDialog = reactive({ open: false, device: null as OpsVenueDevice | null })

const dirty = computed(() => detail.value !== null && (lateEntryLimited.value !== (detail.value.lateEntryMinutes !== null)
  || JSON.stringify(form) !== JSON.stringify(venueDraftOf(detail.value))))
const hoursProblem = computed(() => openingHoursProblem(form.opensAt, form.closesAt))
const notePreview = computed(() => openingNotePreview(form.opensAt, form.closesAt, form.closedNote))
const summaryPreview = computed(() => projectSummaryPreview(form.projectSummary, detail.value?.projects ?? []))
const missingText = computed(() => (detail.value?.publishMissing ?? [])
  .map((key) => PUBLISH_FIELD_LABELS[key] ?? key).join('、'))

function fill(next: OpsVenueDetail): void {
  detail.value = next
  Object.assign(form, venueDraftOf(next))
  lateEntryLimited.value = next.lateEntryMinutes !== null
  operatorOptions.value = [{ value: next.operatorId, label: next.operatorName }]
  cityOptions.value = [{ value: next.cityCode, label: next.cityName ?? next.cityCode }]
  formRef.value?.clearValidate()
}

async function load(): Promise<void> {
  if (!venueId.value) {
    detail.value = null
    Object.assign(form, emptyVenueDraft())
    createKey = newIdempotencyKey()
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    fill(await api.get(venueId.value))
    void loadChanges(venueId.value)
  } catch (failure) {
    loadError.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function searchOperators(keyword: string): Promise<void> {
  try {
    const page = await organizationApi.list({ keyword, type: 'PROVIDER', qualificationStatus: '' }, 1, 20)
    operatorOptions.value = page.items.map((item) => ({ value: item.organizationId, label: item.name }))
  } catch (failure) {
    showError(failure)
  }
}

async function searchCities(keyword: string): Promise<void> {
  try {
    const page = await cityApi.list({ keyword, serviceStatus: '' }, 1, 20)
    cityOptions.value = page.items.map((item) => ({ value: item.cityCode, label: `${item.cityName}（${item.provinceName}）` }))
  } catch (failure) {
    showError(failure)
  }
}

function addInfoItem(): void {
  if (form.infoItems.length < VENUE_LIMITS.infoItems) form.infoItems.push({ label: '', content: '' })
}

function moveInfoItem(index: number, offset: number): void {
  const target = index + offset
  if (target < 0 || target >= form.infoItems.length) return
  const items = [...form.infoItems]
  ;[items[index], items[target]] = [items[target]!, items[index]!]
  form.infoItems = items
}

async function save(): Promise<void> {
  if (!formRef.value || busy.value || (venueId.value && !detail.value)) return
  if (!(await formRef.value.validate().catch(() => false))) return
  if (hoursProblem.value) {
    showError(new Error(hoursProblem.value))
    return
  }
  if (form.infoItems.some((item) => item.label.trim() === '' || item.content.trim() === '')) {
    showError(new Error('信息条目的名称和内容都要填，不要的请删掉'))
    return
  }
  const draft = { ...form, lateEntryMinutes: lateEntryLimited.value ? form.lateEntryMinutes ?? 0 : null }
  const current = detail.value
  const confirmed = await confirmAction({
    title: current ? '确认保存场馆资料' : '确认新建场馆',
    message: current
      ? (current.status === 'PUBLISHED' ? '场馆已上线，保存后 App 立即显示新的资料。' : '保存后还是当前状态。')
      : '新建的场馆是草稿，App 上看不到；加好项目和场次后再上线。',
    confirmText: '保存',
  })
  if (!confirmed) return
  busy.value = true
  try {
    if (current) {
      fill(await api.update(current.venueId, draft, current.version))
      notifyRecorded('保存场馆资料', '改动记录')
      void loadChanges(venueId.value)
    } else {
      const created = await api.create(draft, createKey)
      notifyRecorded('新建场馆草稿', '改动记录')
      await router.replace({ name: 'venue-detail', params: { venueId: created.venueId } })
    }
  } catch (failure) {
    const missing = describeMissing(failure, PUBLISH_FIELD_LABELS)
    showError(missing ? new Error(`场馆已上线，${missing.replace('上线前还差：', '不能清掉：')}`) : failure)
  } finally {
    busy.value = false
  }
}

async function transition(kind: 'publish' | 'offline'): Promise<void> {
  const current = detail.value
  if (!current || busy.value) return
  const confirmed = await confirmAction({
    title: kind === 'publish' ? '确认上线' : '确认下线',
    message: kind === 'publish'
      ? '上线后 App 体验频道和场馆详情马上能看到、能预约。'
      : '下线后 App 不再显示这家场馆；已有预约照常到馆、核销、取消退款。',
    confirmText: kind === 'publish' ? '上线' : '下线',
    danger: kind === 'offline',
  })
  if (!confirmed) return
  busy.value = true
  try {
    fill(kind === 'publish'
      ? await api.publish(current.venueId, current.version, '')
      : await api.offline(current.venueId, current.version, ''))
    notifyRecorded(kind === 'publish' ? '上线' : '下线', '改动记录')
    void loadChanges(venueId.value)
  } catch (failure) {
    const missing = describeMissing(failure, PUBLISH_FIELD_LABELS)
    showError(missing ? new Error(missing) : failure)
  } finally {
    busy.value = false
  }
}

async function toggleProject(project: OpsVenueProject): Promise<void> {
  const current = detail.value
  if (!current) return
  const disabling = project.status === 'ACTIVE'
  const confirmed = await confirmAction({
    title: disabling ? '确认停用项目' : '确认启用项目',
    message: disabling ? `停用“${project.name}”会关闭新预约，已有预约照常。` : `启用“${project.name}”后，用户可在已上线的场馆查看项目。`,
    confirmText: disabling ? '停用' : '启用',
    danger: disabling,
  })
  if (!confirmed) return
  try {
    fill(await api.projectStatus(current.venueId, project.projectId, disabling ? 'DISABLED' : 'ACTIVE', project.version))
    notifyRecorded(disabling ? '停用项目' : '启用项目', '改动记录')
    void loadChanges(venueId.value)
  } catch (failure) {
    const missing = describeMissing(failure, PUBLISH_FIELD_LABELS)
    showError(missing ? new Error('场馆已上线，不能停用最后一个启用的项目') : failure)
  }
}

async function toggleDevice(device: OpsVenueDevice): Promise<void> {
  const current = detail.value
  if (!current) return
  const disabling = device.status === 'ACTIVE'
  const confirmed = await confirmAction({
    title: disabling ? '确认停用设备' : '确认启用设备',
    message: disabling ? `停用后 App“馆内设备”不再显示“${device.name}”。` : `启用“${device.name}”后，用户可在已上线的场馆查看设备。`,
    confirmText: disabling ? '停用' : '启用',
    danger: disabling,
  })
  if (!confirmed) return
  try {
    fill(await api.deviceStatus(current.venueId, device.deviceId, disabling ? 'DISABLED' : 'ACTIVE', device.version))
    notifyRecorded(disabling ? '停用设备' : '启用设备', '改动记录')
    void loadChanges(venueId.value)
  } catch (failure) {
    showError(failure)
  }
}

function saved(next: OpsVenueDetail): void {
  projectDialog.open = false
  deviceDialog.open = false
  fill(next)
  void loadChanges(venueId.value)
}

async function refreshAfterSessions(): Promise<void> {
  generateDialog.open = false
  await load()
}

watch(venueId, () => {
  void load()
})

onMounted(() => {
  void load()
  void searchOperators('')
})
</script>

<template>
  <PageSection
    :title="detail?.name ?? (venueId ? '场馆详情' : '新建场馆')"
    :description="detail ? `${detail.operatorName} · ${detail.cityName ?? detail.cityCode}` : '先保存场馆资料（草稿），再加项目、场次和设备。'"
    traced
  >
    <template #actions>
      <el-button @click="router.push({ name: 'venues' })">返回列表</el-button>
      <template v-if="detail">
        <el-button v-if="detail.status !== 'PUBLISHED'" type="primary" :disabled="busy || dirty" @click="transition('publish')">
          上线
        </el-button>
        <el-button v-else type="danger" plain :disabled="busy || dirty" @click="transition('offline')">下线</el-button>
      </template>
    </template>

    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon>
      <el-button size="small" @click="load">重新加载</el-button>
    </el-alert>

    <div v-else v-loading="loading || busy" class="venue">
      <el-alert v-if="dirty" type="info" title="场馆资料有未保存的修改，请先保存再调整上线状态、项目、设备或场次。" :closable="false" />
      <div v-if="detail" class="venue__status">
        <el-tag :type="venueStatus(detail.status).tag">{{ venueStatus(detail.status).label }}</el-tag>
        <span v-if="detail.publishedAt" class="hint">首次上线 {{ formatDateTime(detail.publishedAt) }}</span>
        <el-alert v-if="detail.publishMissing.length > 0" type="warning" :closable="false" show-icon
                  :title="`上线前还差：${missingText}`" />
        <el-alert v-else-if="detail.status !== 'PUBLISHED'" type="success" :closable="false" show-icon
                  title="必填项都齐了，可以上线" />
      </div>

      <h2 class="venue__heading">场馆资料</h2>
      <el-form ref="formRef" :disabled="loading || busy" :model="form" :rules="venueRules" label-width="120px" class="venue__form">
        <el-form-item label="运营机构" prop="operatorId">
          <el-select v-model="form.operatorId" filterable remote :remote-method="searchOperators" placeholder="搜索服务商机构"
                     style="width: 320px">
            <el-option v-for="option in operatorOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <span class="hint">核销码只认这家机构的操作员；收款进这家机构的子商户号</span>
        </el-form-item>
        <el-form-item label="场馆名称" prop="name">
          <el-input v-model="form.name" :maxlength="VENUE_LIMITS.name" show-word-limit style="width: 420px" />
        </el-form-item>
        <el-form-item label="城市" prop="cityCode">
          <el-select v-model="form.cityCode" filterable remote :remote-method="searchCities" placeholder="搜索城市"
                     style="width: 320px">
            <el-option v-for="option in cityOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="封面">
          <MediaField v-model="form.coverUrl" kind="IMAGE" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" :maxlength="VENUE_LIMITS.address" show-word-limit style="width: 420px" />
        </el-form-item>
        <el-form-item label="服务电话" prop="phone">
          <el-input v-model="form.phone" :maxlength="VENUE_LIMITS.phone" style="width: 220px" />
        </el-form-item>
        <el-form-item label="项目摘要" prop="projectSummary">
          <el-input v-model="form.projectSummary" :maxlength="VENUE_LIMITS.projectSummary" show-word-limit
                    placeholder="如：模拟飞行 · 机器人编程 · 厂家展区（选填）" style="width: 420px"
                    data-testid="venue-project-summary" />
          <div class="hint block">
            <template v-if="summaryPreview">App 首页场馆卡显示：{{ summaryPreview }}</template>
            <template v-else>App 首页场馆卡第一行；不填时用启用的前三个项目名</template>
          </div>
        </el-form-item>
        <el-form-item label="营业时间">
          <el-time-select v-model="form.opensAt" start="06:00" end="23:30" step="00:30" placeholder="开门"
                          style="width: 120px" />
          <span class="sep">–</span>
          <el-time-select v-model="form.closesAt" start="06:30" end="23:59" step="00:30" placeholder="关门"
                          style="width: 120px" />
          <el-input v-model="form.closedNote" :maxlength="VENUE_LIMITS.closedNote" placeholder="休息日说明，如：周一闭馆"
                    style="width: 220px; margin-left: 12px" />
          <div class="hint block">
            <template v-if="hoursProblem">{{ hoursProblem }}</template>
            <template v-else-if="notePreview">App 显示：{{ notePreview }}</template>
            <template v-else-if="detail?.openingNote">App 现在显示：{{ detail.openingNote }}（填好开门、关门时间后按新的生成）</template>
            <template v-else>北京时间；上线前必填</template>
          </div>
        </el-form-item>
        <el-form-item label="检票与迟到">
          开场前
          <el-input-number v-model="form.checkinOpensMinutes" :min="0" :max="VENUE_LIMITS.checkinMinutes" size="small"
                           style="margin: 0 6px" />
          分钟开始检票；
          <el-checkbox v-model="lateEntryLimited" style="margin-left: 8px">限制迟到</el-checkbox>
          <template v-if="lateEntryLimited">
            开场
            <el-input-number v-model="form.lateEntryMinutes" :min="0" :max="VENUE_LIMITS.checkinMinutes" size="small"
                             style="margin: 0 6px" />
            分钟后停止入场
          </template>
          <div class="hint block">
            {{ entryRuleText(form.checkinOpensMinutes, lateEntryLimited ? form.lateEntryMinutes ?? 0 : null) }}；
            到馆核销按这条规则判断
          </div>
        </el-form-item>
        <el-form-item label="信息条目">
          <div class="info-items">
            <div v-for="(item, index) in form.infoItems" :key="index" class="info-items__row">
              <el-input v-model="item.label" :maxlength="VENUE_LIMITS.infoLabel" placeholder="名称，如：入口" style="width: 140px" />
              <el-input v-model="item.content" :maxlength="VENUE_LIMITS.infoContent" placeholder="内容"
                        style="width: 360px" />
              <el-button link :disabled="index === 0" @click="moveInfoItem(index, -1)">上移</el-button>
              <el-button link :disabled="index === form.infoItems.length - 1" @click="moveInfoItem(index, 1)">下移</el-button>
              <el-button link type="danger" @click="form.infoItems.splice(index, 1)">删除</el-button>
            </div>
            <el-button size="small" :disabled="form.infoItems.length >= VENUE_LIMITS.infoItems" @click="addInfoItem">
              添加一条（{{ form.infoItems.length }} / {{ VENUE_LIMITS.infoItems }}）
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="VENUE_LIMITS.sortOrder" />
          <span class="hint">同城场馆里小的在前</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="save">{{ detail ? '保存资料' : '新建草稿' }}</el-button>
        </el-form-item>
      </el-form>

      <template v-if="detail">
        <div class="venue__bar">
          <h2 class="venue__heading">项目与场次</h2>
          <el-button size="small" type="primary" plain :disabled="dirty || busy" @click="projectDialog.project = null; projectDialog.open = true">
            新建项目
          </el-button>
        </div>
        <el-table :data="detail.projects" row-key="projectId" border size="small" empty-text="还没有项目">
          <el-table-column prop="name" label="项目" min-width="160" />
          <el-table-column label="时长" width="80">
            <template #default="{ row }">{{ (row as OpsVenueProject).durationMinutes }} 分钟</template>
          </el-table-column>
          <el-table-column label="每人" width="90">
            <template #default="{ row }">{{ formatYuan((row as OpsVenueProject).priceAmountMinor) }}</template>
          </el-table-column>
          <el-table-column label="年龄" width="80">
            <template #default="{ row }">{{ (row as OpsVenueProject).minAge === null ? '不限' : `${(row as OpsVenueProject).minAge} 岁+` }}</template>
          </el-table-column>
          <el-table-column prop="capacity" label="每场人数" width="80" />
          <el-table-column label="未来场次" width="150">
            <template #default="{ row }">
              {{ (row as OpsVenueProject).upcomingSessionCount }} 场
              <div class="hint">{{ (row as OpsVenueProject).nextSessionAt ? `最近 ${formatDateTime((row as OpsVenueProject).nextSessionAt)}` : '没有在售场次' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="70">
            <template #default="{ row }">
              <el-tag size="small" :type="(row as OpsVenueProject).status === 'ACTIVE' ? 'success' : 'info'">
                {{ (row as OpsVenueProject).status === 'ACTIVE' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="250">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="dirty || busy" @click="projectDialog.project = row as OpsVenueProject; projectDialog.open = true">编辑</el-button>
              <el-button link type="primary" :disabled="dirty || busy" @click="generateDialog.project = row as OpsVenueProject; generateDialog.open = true">按周生成场次</el-button>
              <el-button link type="primary" :disabled="dirty || busy" @click="sessionsDrawer.project = row as OpsVenueProject; sessionsDrawer.open = true">场次</el-button>
              <el-button link :type="(row as OpsVenueProject).status === 'ACTIVE' ? 'danger' : 'primary'"
                         :disabled="dirty || busy" @click="toggleProject(row as OpsVenueProject)">
                {{ (row as OpsVenueProject).status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="venue__bar">
          <h2 class="venue__heading">馆内设备</h2>
          <el-button size="small" type="primary" plain :disabled="dirty || busy" @click="deviceDialog.device = null; deviceDialog.open = true">
            新增设备
          </el-button>
        </div>
        <el-table :data="detail.devices" row-key="deviceId" border size="small" empty-text="还没有设备">
          <el-table-column prop="name" label="设备" min-width="160" />
          <el-table-column label="类别" width="80">
            <template #default="{ row }">{{ deviceCategoryLabel((row as OpsVenueDevice).category) }}</template>
          </el-table-column>
          <el-table-column label="体验方式" width="140">
            <template #default="{ row }">{{ trialModeLabel((row as OpsVenueDevice).trialMode) }}</template>
          </el-table-column>
          <el-table-column label="关联商品" min-width="140">
            <template #default="{ row }">{{ (row as OpsVenueDevice).productId ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="70">
            <template #default="{ row }">
              <el-tag size="small" :type="(row as OpsVenueDevice).status === 'ACTIVE' ? 'success' : 'info'">
                {{ (row as OpsVenueDevice).status === 'ACTIVE' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="dirty || busy" @click="deviceDialog.device = row as OpsVenueDevice; deviceDialog.open = true">编辑</el-button>
              <el-button link :type="(row as OpsVenueDevice).status === 'ACTIVE' ? 'danger' : 'primary'"
                         :disabled="dirty || busy" @click="toggleDevice(row as OpsVenueDevice)">
                {{ (row as OpsVenueDevice).status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <h2 class="venue__heading">改动记录</h2>
        <el-button v-if="changesError" size="small" @click="loadChanges(venueId)">重试改动记录</el-button>
        <ChangeLogTable :changes="changes" :error="changesError" :loading="changesLoading" :subject-labels="SUBJECT_LABELS"
                        :action-labels="VENUE_ACTION_LABELS" />
      </template>
    </div>
  </PageSection>

  <template v-if="detail">
    <ProjectDialog :api="api" :venue-id="detail.venueId" :open="projectDialog.open" :project="projectDialog.project"
                   @close="projectDialog.open = false" @saved="saved" />
    <SessionGenerateDialog :api="api" :venue-id="detail.venueId" :open="generateDialog.open"
                           :project="generateDialog.project" @close="generateDialog.open = false"
                           @generated="refreshAfterSessions" />
    <SessionsDrawer :api="api" :venue-id="detail.venueId" :open="sessionsDrawer.open" :project="sessionsDrawer.project"
                    @close="sessionsDrawer.open = false" @changed="load" />
    <DeviceDialog :api="api" :venue-id="detail.venueId" :open="deviceDialog.open" :device="deviceDialog.device"
                  @close="deviceDialog.open = false" @saved="saved" />
  </template>
</template>

<style scoped>
.venue {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.venue__status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.venue__heading {
  margin: 8px 0 0;
  font-size: 16px;
  font-weight: 700;
}

.venue__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.venue__form {
  max-width: 960px;
}

.info-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.info-items__row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.sep {
  margin: 0 6px;
}

.hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.hint.block {
  display: block;
  width: 100%;
  margin-left: 0;
}
</style>
