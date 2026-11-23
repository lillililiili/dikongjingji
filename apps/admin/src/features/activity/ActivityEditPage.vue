<script setup lang="ts">
/**
 * 活动编辑页：新建（/activities/new）和已有活动（/activities/:activityId）共用。
 * 已有活动另外有状态操作（上线、下线、删除）、场次表格（新增、修改、停售 / 恢复售卖、删除）和改动记录。
 * 上线前必填项后端会再校验一次（422 带缺的字段），这里先按详情的 publishMissing 提示。
 */
import type { FormInstance } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import ChangeLogTable from '@/core/ops/ChangeLogTable.vue'
import MediaField from '@/core/ops/MediaField.vue'
import MediaListField from '@/core/ops/MediaListField.vue'
import { describeMissing, type OpsChange } from '@/core/ops/ops-common'
import { errorMessage } from '@/core/http/api-error'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'
import { createOrganizationApi, type DirectoryCity } from '@/features/organization/organization-api'

import {
  ACTIVITY_STATUS_OPTIONS,
  ACTIVITY_TYPE_OPTIONS,
  activityFormOf,
  CHANGE_SUBJECT_LABELS,
  createActivityApi,
  emptyActivityForm,
  labelOf,
  LIMITS,
  PRICE_MODE_OPTIONS,
  PUBLISH_FIELD_LABELS,
  sessionState,
  statusTag,
  type ActivityForm,
  type OpsActivity,
  type OpsActivitySession,
} from './activity-api'
import { activityRules } from './activity-rules'
import SessionDialog from './SessionDialog.vue'

const route = useRoute()
const router = useRouter()
const api = createActivityApi(http)
const organizationApi = createOrganizationApi(http)

const activityId = computed(() => (typeof route.params.activityId === 'string' ? route.params.activityId : ''))
const activity = ref<OpsActivity | null>(null)
const changes = ref<OpsChange[]>([])
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<ActivityForm>(emptyActivityForm())
const rules = activityRules(() => form)
const cities = ref<DirectoryCity[]>([])
const organizerOptions = ref<Array<{ id: string, name: string }>>([])
const searchingOrganizers = ref(false)
const sessionDialog = reactive<{ open: boolean, session: OpsActivitySession | null }>({ open: false, session: null })

const creating = computed(() => activityId.value === '')
const locked = computed(() => activity.value?.hasEnrollments ?? false)
const missingText = computed(() => (activity.value?.publishMissing ?? [])
  .map((key) => PUBLISH_FIELD_LABELS[key] ?? key).join('、'))

function remember(id: string, name: string | null): void {
  if (!id || organizerOptions.value.some((option) => option.id === id)) return
  organizerOptions.value = [...organizerOptions.value, { id, name: name ?? `已停用的机构（${id.slice(0, 8)}）` }]
}

function show(next: OpsActivity): void {
  activity.value = next
  Object.assign(form, activityFormOf(next))
  remember(next.organizer.organizationId, next.organizer.name)
  if (next.coOrganizer) remember(next.coOrganizer.organizationId, next.coOrganizer.name)
  formRef.value?.clearValidate()
}

async function loadChanges(): Promise<void> {
  if (!activityId.value) return
  try {
    changes.value = await api.changes(activityId.value)
  } catch {
    changes.value = []
  }
}

async function load(): Promise<void> {
  if (creating.value) {
    activity.value = null
    Object.assign(form, emptyActivityForm())
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    show(await api.detail(activityId.value))
    await loadChanges()
  } catch (failure) {
    loadError.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function searchOrganizers(keyword: string): Promise<void> {
  searchingOrganizers.value = true
  try {
    const page = await organizationApi.list({ keyword, type: 'PROVIDER', qualificationStatus: '' }, 1, 20)
    const found = page.items.filter((item) => item.status === 'ACTIVE').map((item) => ({ id: item.organizationId, name: item.name }))
    const kept = organizerOptions.value.filter((option) => option.id === form.organizerId || option.id === form.coOrganizerId)
    organizerOptions.value = [...kept, ...found.filter((item) => !kept.some((option) => option.id === item.id))]
  } catch (failure) {
    showError(failure)
  } finally {
    searchingOrganizers.value = false
  }
}

/** 保存后再刷新改动记录；失败统一提示（上线前缺必填时说清楚缺什么）。 */
async function run(action: () => Promise<OpsActivity>, done: string): Promise<boolean> {
  busy.value = true
  try {
    show(await action())
    notifyRecorded(done, '改动记录')
    await loadChanges()
    return true
  } catch (failure) {
    const missing = describeMissing(failure, PUBLISH_FIELD_LABELS)
    showError(missing ? new Error(missing) : failure)
    return false
  } finally {
    busy.value = false
  }
}

async function save(): Promise<void> {
  if (!formRef.value || busy.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const current = activity.value
  if (!current) {
    const confirmed = await confirmAction({ title: '确认新建活动', message: '新建后是草稿，App 上看不到；加好场次、填齐必填项后再上线。', confirmText: '新建' })
    if (!confirmed) return
    busy.value = true
    try {
      const created = await api.create(form)
      notifyRecorded('新建活动', '改动记录')
      await router.replace({ name: 'activity-detail', params: { activityId: created.activityId } })
    } catch (failure) {
      showError(failure)
    } finally {
      busy.value = false
    }
    return
  }
  const confirmed = await confirmAction({
    title: '确认保存活动信息',
    message: current.status === 'PUBLISHED' ? '活动已上线，保存后 App 上立刻显示新的内容。' : '保存后在草稿 / 已下线状态，App 上看不到。',
    confirmText: '保存',
  })
  if (!confirmed) return
  await run(() => api.update(current.activityId, form, current.version), '保存活动信息')
}

async function publish(): Promise<void> {
  const current = activity.value
  if (!current || busy.value) return
  const confirmed = await confirmAction({ title: '确认上线', message: `上线后 App 的“赛事研学”频道能看到“${current.title}”，在售场次可以报名。`, confirmText: '上线' })
  if (confirmed) await run(() => api.publish(current.activityId, current.version), '上线')
}

async function offline(): Promise<void> {
  const current = activity.value
  if (!current || busy.value) return
  const confirmed = await confirmAction({
    title: '确认下线',
    message: '下线后 App 列表和详情都看不到这个活动，不能再报名；已有的报名照常（能取消、退款、核销）。',
    confirmText: '下线',
    danger: true,
  })
  if (confirmed) await run(() => api.offline(current.activityId, current.version), '下线')
}

async function remove(): Promise<void> {
  const current = activity.value
  if (!current || busy.value) return
  const confirmed = await confirmAction({ title: '确认删除草稿', message: `删除“${current.title}”后不能恢复（改动记录还在）。`, confirmText: '删除', danger: true })
  if (!confirmed) return
  busy.value = true
  try {
    await api.remove(current.activityId, current.version)
    notifyRecorded('删除草稿', '改动记录')
    await router.replace({ name: 'activities' })
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

function editSession(session: OpsActivitySession | null): void {
  sessionDialog.session = session
  sessionDialog.open = true
}

async function sessionSaved(next: OpsActivity): Promise<void> {
  sessionDialog.open = false
  show(next)
  await loadChanges()
}

async function toggleSale(session: OpsActivitySession): Promise<void> {
  const current = activity.value
  if (!current || busy.value) return
  const stopping = session.saleStatus === 'ON_SALE'
  const confirmed = await confirmAction({
    title: stopping ? '确认停售' : '确认恢复售卖',
    message: stopping
      ? `停售后 App 上这一场显示“报名截止”，不能再报名；已报名的 ${session.enrolledCount} 人不受影响。`
      : '恢复后这一场又能在 App 上报名。',
    confirmText: stopping ? '停售' : '恢复售卖',
    danger: stopping,
  })
  if (!confirmed) return
  await run(() => stopping
    ? api.stopSale(current.activityId, session.sessionId, session.version)
    : api.resumeSale(current.activityId, session.sessionId, session.version), stopping ? '停售场次' : '恢复售卖')
}

async function removeSession(session: OpsActivitySession): Promise<void> {
  const current = activity.value
  if (!current || busy.value) return
  const confirmed = await confirmAction({ title: '确认删除场次', message: `删除 ${formatDateTime(session.startsAt)} 这一场（没有任何报名单）。`, confirmText: '删除', danger: true })
  if (confirmed) await run(() => api.removeSession(current.activityId, session.sessionId, session.version), '删除场次')
}

watch(activityId, () => {
  void load()
})

onMounted(async () => {
  void load()
  try {
    cities.value = await organizationApi.cities()
  } catch {
    cities.value = []
  }
  void searchOrganizers('')
})
</script>

<template>
  <PageSection
    :title="creating ? '新建活动' : (activity?.title ?? '活动')"
    :description="creating ? '先存成草稿，再加场次、上线。' : '改动都会记进下面的改动记录。'"
    traced
  >
    <template #actions>
      <el-button @click="router.push({ name: 'activities' })">返回列表</el-button>
      <template v-if="activity">
        <el-button v-if="activity.status !== 'PUBLISHED'" type="success" :loading="busy" @click="publish">上线</el-button>
        <el-button v-else type="warning" :loading="busy" @click="offline">下线</el-button>
        <el-button v-if="activity.deletable" type="danger" plain :loading="busy" @click="remove">删除草稿</el-button>
      </template>
    </template>

    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon>
      <el-button size="small" @click="load">重新加载</el-button>
    </el-alert>

    <div v-loading="loading" class="activity-edit">
      <div v-if="activity" class="activity-edit__status">
        <el-tag :type="statusTag(activity.status)">{{ labelOf(ACTIVITY_STATUS_OPTIONS, activity.status) }}</el-tag>
        <span>第一次上线：{{ formatDateTime(activity.firstPublishedAt) }}</span>
        <span>最后修改：{{ formatDateTime(activity.updatedAt) }}</span>
        <el-alert v-if="activity.status !== 'PUBLISHED' && missingText" type="warning" :closable="false" show-icon
                  :title="`上线前还差：${missingText}`" />
        <el-alert v-if="locked" type="info" :closable="false" show-icon
                  title="已有报名：收费方式、主办机构、城市不能再改；单价改了只影响以后的报名。" />
      </div>

      <h2 class="activity-edit__heading">活动信息</h2>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="activity-edit__form">
        <el-form-item label="类型" prop="type">
          <el-radio-group v-model="form.type">
            <el-radio-button v-for="option in ACTIVITY_TYPE_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="活动名称" prop="title">
          <el-input v-model="form.title" :maxlength="LIMITS.title" show-word-limit />
        </el-form-item>
        <el-form-item label="一句话介绍" prop="subtitle">
          <el-input v-model="form.subtitle" :maxlength="LIMITS.subtitle" show-word-limit />
        </el-form-item>
        <el-form-item label="主办机构" prop="organizerId">
          <el-select v-model="form.organizerId" filterable remote :remote-method="searchOrganizers"
                     :loading="searchingOrganizers" :disabled="locked" placeholder="搜索经营机构" style="width: 320px">
            <el-option v-for="option in organizerOptions" :key="option.id" :label="option.name" :value="option.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="承办机构" prop="coOrganizerId">
          <el-select v-model="form.coOrganizerId" filterable remote clearable :remote-method="searchOrganizers"
                     :loading="searchingOrganizers" placeholder="可不填" style="width: 320px">
            <el-option v-for="option in organizerOptions" :key="option.id" :label="option.name" :value="option.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="城市" prop="cityCode">
          <el-select v-model="form.cityCode" filterable :disabled="locked" placeholder="平台城市目录" style="width: 200px">
            <el-option v-for="city in cities" :key="city.code" :label="`${city.name}（${city.provinceName}）`"
                       :value="city.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="地点名称" prop="venueName">
          <el-input v-model="form.venueName" :maxlength="LIMITS.venueName" show-word-limit />
        </el-form-item>
        <el-form-item label="详细地址" prop="venueAddress">
          <el-input v-model="form.venueAddress" :maxlength="LIMITS.venueAddress" show-word-limit />
        </el-form-item>
        <el-form-item label="时间说明" prop="scheduleNote">
          <el-input v-model="form.scheduleNote" :maxlength="LIMITS.scheduleNote" show-word-limit
                    placeholder="比如“每周六上午”，可不填" />
        </el-form-item>
        <el-form-item label="收费方式" prop="priceMode">
          <el-radio-group v-model="form.priceMode" :disabled="locked">
            <el-radio v-for="option in PRICE_MODE_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio>
          </el-radio-group>
          <div class="activity-edit__hint">{{ PRICE_MODE_OPTIONS.find((option) => option.value === form.priceMode)?.hint }}</div>
        </el-form-item>
        <el-form-item v-if="form.priceMode === 'PER_PERSON'" label="单价（元/人）" prop="priceYuan">
          <el-input v-model="form.priceYuan" style="width: 160px" placeholder="比如 120" />
        </el-form-item>
        <el-form-item label="年龄" prop="maxAge">
          <el-input-number v-model="form.minAge" :min="0" :max="LIMITS.age" :value-on-clear="null" placeholder="不限" />
          <span class="activity-edit__to">至</span>
          <el-input-number v-model="form.maxAge" :min="0" :max="LIMITS.age" :value-on-clear="null" placeholder="不限" />
          <span class="activity-edit__to">岁</span>
        </el-form-item>
        <el-form-item label="封面" prop="coverUrl">
          <MediaField v-model="form.coverUrl" />
        </el-form-item>
        <el-form-item label="图集">
          <MediaListField v-model="form.galleryUrls" :max="LIMITS.gallery" />
        </el-form-item>
        <el-form-item label="参与条件" prop="eligibilityNote">
          <el-input v-model="form.eligibilityNote" :maxlength="LIMITS.shortNote" show-word-limit />
        </el-form-item>
        <el-form-item label="名额说明" prop="seatNote">
          <el-input v-model="form.seatNote" :maxlength="LIMITS.shortNote" show-word-limit placeholder="比如“陪同家长不用报名”" />
        </el-form-item>
        <el-form-item label="退改说明" prop="refundNote">
          <el-input v-model="form.refundNote" type="textarea" :rows="3" :maxlength="LIMITS.refundNote" show-word-limit />
        </el-form-item>
        <el-form-item label="报名须知" prop="enrollmentNotice">
          <el-input v-model="form.enrollmentNotice" type="textarea" :rows="5" :maxlength="LIMITS.enrollmentNotice"
                    show-word-limit placeholder="App 报名页“报名须知”，一行一段" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="save">{{ creating ? '存为草稿' : '保存活动信息' }}</el-button>
        </el-form-item>
      </el-form>

      <template v-if="activity">
        <div class="activity-edit__row">
          <h2 class="activity-edit__heading">场次</h2>
          <el-button type="primary" plain size="small" @click="editSession(null)">新增场次</el-button>
        </div>
        <el-table :data="activity.sessions" row-key="sessionId" border size="small" empty-text="还没有场次">
          <el-table-column label="开始" width="140">
            <template #default="{ row }">{{ formatDateTime((row as OpsActivitySession).startsAt) }}</template>
          </el-table-column>
          <el-table-column label="名称" min-width="100">
            <template #default="{ row }">{{ (row as OpsActivitySession).label ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="报名截止" width="140">
            <template #default="{ row }">{{ formatDateTime((row as OpsActivitySession).enrollmentClosesAt) }}</template>
          </el-table-column>
          <el-table-column label="已报名 / 名额" width="110">
            <template #default="{ row }">
              {{ (row as OpsActivitySession).enrolledCount }} / {{ (row as OpsActivitySession).capacity }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="sessionState(row as OpsActivitySession).tag">{{ sessionState(row as OpsActivitySession).label }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="220">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="(row as OpsActivitySession).started || (row as OpsActivitySession).cancelled"
                         @click="editSession(row as OpsActivitySession)">修改</el-button>
              <el-button v-if="!(row as OpsActivitySession).cancelled" link
                         :type="(row as OpsActivitySession).saleStatus === 'ON_SALE' ? 'danger' : 'success'"
                         :disabled="(row as OpsActivitySession).saleStatus === 'STOPPED' && (row as OpsActivitySession).started"
                         @click="toggleSale(row as OpsActivitySession)">
                {{ (row as OpsActivitySession).saleStatus === 'ON_SALE' ? '停售' : '恢复售卖' }}
              </el-button>
              <el-tooltip :disabled="!(row as OpsActivitySession).hasEnrollments" content="有过报名单的场次不能删，只能停售">
                <span>
                  <el-button link type="danger" :disabled="(row as OpsActivitySession).hasEnrollments"
                             @click="removeSession(row as OpsActivitySession)">删除</el-button>
                </span>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>

        <h2 class="activity-edit__heading">改动记录</h2>
        <ChangeLogTable :changes="changes" :subject-labels="CHANGE_SUBJECT_LABELS" />
      </template>
    </div>
  </PageSection>

  <SessionDialog
    v-if="activity"
    :api="api"
    :activity-id="activity.activityId"
    :open="sessionDialog.open"
    :session="sessionDialog.session"
    @close="sessionDialog.open = false"
    @saved="sessionSaved"
  />
</template>

<style scoped>
.activity-edit {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.activity-edit__status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.activity-edit__heading {
  margin: 12px 0 4px;
  font-size: 16px;
  font-weight: 700;
  color: var(--admin-text);
}

.activity-edit__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.activity-edit__form {
  max-width: 820px;
}

.activity-edit__hint {
  width: 100%;
  font-size: 12px;
  line-height: 18px;
  color: var(--admin-text-secondary);
}

.activity-edit__to {
  margin: 0 8px;
}
</style>
