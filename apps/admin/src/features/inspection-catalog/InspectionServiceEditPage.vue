<script setup lang="ts">
/**
 * 一项巡检服务的编辑：新建（/inspection-services/new，保存后是未上架）或修改；上架、下架；改动记录。
 * 整份提交（带版本号），已上架的服务改完也要齐全；缺必填项时后端回 OPS_PUBLISH_INCOMPLETE，这里翻成“还差：…”。
 */
import type { FormInstance } from 'element-plus'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import ChangeLogTable from '@/core/ops/ChangeLogTable.vue'
import MediaField from '@/core/ops/MediaField.vue'
import { describeMissing, type OpsChange } from '@/core/ops/ops-common'
import RowListEditor from '@/core/ops/RowListEditor.vue'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'
import CityMultiSelect from '@/features/organization/CityMultiSelect.vue'
import ProviderSelect from '@/features/organization/ProviderSelect.vue'

import {
  createInspectionCatalogApi,
  type OpsInspectionCategory,
  type OpsInspectionService,
} from './inspection-catalog-api'
import {
  AVAILABILITY_OPTIONS,
  CHANGE_ACTION_LABELS,
  CHANGE_SUBJECT_LABELS,
  emptyForm,
  formFromDetail,
  ITEM_SECTIONS,
  LIMITS,
  METHOD_OPTIONS,
  PRICE_MODE_OPTIONS,
  PRIMARY_ACTION_OPTIONS,
  PUBLISH_FIELD_LABELS,
  serviceRules,
  serviceStatus,
  toRequest,
  type InspectionServiceForm,
  type ItemRow,
} from './inspection-catalog-rules'

const route = useRoute()
const router = useRouter()
const api = createInspectionCatalogApi(http)

const formRef = ref<FormInstance>()
const form = reactive<InspectionServiceForm>(emptyForm())
const detail = shallowRef<OpsInspectionService | null>(null)
const categories = ref<OpsInspectionCategory[]>([])
const changes = ref<OpsChange[]>([])
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)
const problem = ref('')

const serviceId = computed(() => (route.name === 'inspection-service-new' ? '' : String(route.params.serviceId ?? '')))
const creating = computed(() => serviceId.value === '')
const projectQuote = computed(() => form.priceMode === 'PROJECT_QUOTE')
const dirty = computed(() => detail.value !== null
  && JSON.stringify(formFromDetail(detail.value)) !== JSON.stringify(form))
const cityNames = computed(() => Object.fromEntries((detail.value?.cities ?? [])
  .map((city) => [city.cityCode, city.cityName])))
const itemFields = (titleMax: number) => [
  { key: 'title', label: '名称', max: titleMax, grow: 2 },
  { key: 'description', label: '说明（选填）', max: LIMITS.itemDescription, grow: 3 },
  { key: 'iconKey', label: '图标键（选填）', grow: 1 },
] as const

function newItem(): ItemRow {
  return { code: '', title: '', description: '', iconKey: '' }
}

function show(next: OpsInspectionService): void {
  detail.value = next
  Object.assign(form, formFromDetail(next))
  formRef.value?.clearValidate()
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    categories.value = await api.categories()
    if (!creating.value) {
      show(await api.detail(serviceId.value))
      changes.value = await api.changes(serviceId.value)
    } else {
      detail.value = null
      Object.assign(form, emptyForm())
    }
  } catch (failure) {
    loadError.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function explain(failure: unknown): void {
  const missing = describeMissing(failure, PUBLISH_FIELD_LABELS)
  if (missing) {
    problem.value = missing
    ElMessage({ type: 'error', message: missing, duration: 6000, showClose: true })
  } else {
    showError(failure)
  }
}

async function save(): Promise<void> {
  if (!formRef.value || busy.value) return
  problem.value = ''
  if (!(await formRef.value.validate().catch(() => false))) return
  const { request, problems } = toRequest(form)
  const first = Object.values(problems)[0]
  if (first) {
    problem.value = first
    return
  }
  const current = detail.value
  const confirmed = await confirmAction({
    title: current ? '确认保存修改' : '确认新建服务',
    message: current
      ? (current.status === 'ACTIVE' ? '这项服务已上架，保存后 App 上马上看到新的内容。' : '保存后仍是未上架。')
      : '新建后是未上架，资料齐了再上架。',
    confirmText: '保存',
  })
  if (!confirmed) return
  busy.value = true
  try {
    if (current) {
      show(await api.update(current.serviceId, request, current.version))
      notifyRecorded('保存修改', '改动记录')
    } else {
      const created = await api.create(request)
      notifyRecorded('新建服务', '改动记录')
      await router.replace({ name: 'inspection-service', params: { serviceId: created.serviceId } })
      return
    }
    changes.value = await api.changes(current.serviceId)
  } catch (failure) {
    explain(failure)
  } finally {
    busy.value = false
  }
}

async function changeStatus(publish: boolean): Promise<void> {
  const current = detail.value
  if (!current || busy.value) return
  if (dirty.value) {
    problem.value = '有没保存的修改，先保存再上架或下架。'
    return
  }
  const confirmed = await confirmAction({
    title: publish ? '确认上架' : '确认下架',
    message: publish
      ? `上架后，覆盖城市的用户在 App 巡检目录、详情和首页推荐里能看到“${current.title}”。`
      : `下架后 App 上马上看不到“${current.title}”；已有的预约、方案和订单照常进行。`,
    confirmText: publish ? '上架' : '下架',
    danger: !publish,
  })
  if (!confirmed) return
  busy.value = true
  problem.value = ''
  try {
    show(publish ? await api.publish(current.serviceId, current.version)
      : await api.offline(current.serviceId, current.version))
    notifyRecorded(publish ? '上架' : '下架', '改动记录')
    changes.value = await api.changes(current.serviceId)
  } catch (failure) {
    explain(failure)
  } finally {
    busy.value = false
  }
}

watch(() => route.fullPath, () => {
  void load()
})

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="creating ? '新建巡检服务' : (detail?.title ?? '巡检服务')"
    :description="detail ? `${detail.serviceNo ?? ''} · 最后修改 ${formatDateTime(detail.updatedAt)} · 完成 ${detail.completedCount} 次` : '新建后是未上架，资料齐了再上架。'"
    traced
  >
    <template #actions>
      <el-tag v-if="detail" :type="serviceStatus(detail.status).tag" class="status">
        {{ serviceStatus(detail.status).label }}
      </el-tag>
      <el-button @click="router.push({ name: 'inspection-services' })">返回列表</el-button>
      <template v-if="detail">
        <el-button v-if="detail.status === 'ACTIVE'" type="danger" plain :loading="busy" @click="changeStatus(false)">
          下架
        </el-button>
        <el-button v-else type="success" plain :loading="busy" @click="changeStatus(true)">上架</el-button>
      </template>
      <el-button type="primary" :loading="busy" :disabled="loading || !!loadError" @click="save">保存</el-button>
    </template>

    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon>
      <el-button size="small" @click="load">重新加载</el-button>
    </el-alert>
    <el-alert v-if="problem" class="problem" type="warning" :title="problem" :closable="false" show-icon />

    <el-form v-if="!loadError" ref="formRef" v-loading="loading" :model="form" :rules="serviceRules" label-width="110px">
      <h2 class="group">基本信息</h2>
      <el-form-item label="服务商" prop="providerId">
        <ProviderSelect v-model="form.providerId" :current-name="detail?.providerName" />
      </el-form-item>
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" :maxlength="LIMITS.title" show-word-limit />
      </el-form-item>
      <el-form-item label="简介" prop="summary">
        <el-input v-model="form.summary" type="textarea" :rows="2" :maxlength="LIMITS.summary" show-word-limit
                  placeholder="卡片和详情上的一句话，如“高精度巡检 · 缺陷识别 · 报告生成”" />
      </el-form-item>
      <el-form-item label="类目">
        <el-select v-model="form.categoryCode" clearable placeholder="选类目" style="width: 240px">
          <el-option v-for="category in categories" :key="category.code" :value="category.code"
                     :disabled="category.status !== 'ACTIVE'"
                     :label="`${category.parentCode ? '　' : ''}${category.name}${category.status === 'ACTIVE' ? '' : '（已停用）'}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="封面">
        <MediaField v-model="form.coverUrl" />
      </el-form-item>

      <h2 class="group">价格</h2>
      <el-form-item label="计价方式">
        <el-radio-group v-model="form.priceMode">
          <el-radio v-for="option in PRICE_MODE_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio>
        </el-radio-group>
        <span class="hint">{{ PRICE_MODE_OPTIONS.find((option) => option.value === form.priceMode)?.hint ?? '上架前要选' }}</span>
      </el-form-item>
      <template v-if="form.priceMode && !projectQuote">
        <el-form-item label="起步价（元）" prop="startingYuan">
          <el-input v-model="form.startingYuan" placeholder="如 699" style="width: 160px" />
        </el-form-item>
        <el-form-item label="计价单位">
          <el-input v-model="form.unitCode" placeholder="编码，如 KM" style="width: 140px" />
          <el-input v-model="form.unitLabel" placeholder="名称，如 公里" :maxlength="LIMITS.unitLabel"
                    style="width: 160px; margin-left: 8px" />
        </el-form-item>
      </template>
      <el-form-item label="价格说明" prop="priceNote">
        <el-input v-model="form.priceNote" :maxlength="LIMITS.priceNote" show-word-limit
                  placeholder="起步价含什么，或按项目报价时何时定价" />
      </el-form-item>
      <el-form-item label="取消说明" prop="cancellationNote">
        <el-input v-model="form.cancellationNote" :maxlength="LIMITS.cancellationNote" show-word-limit
                  placeholder="如“方案确认前可取消，不收费”（只是展示文案）" />
      </el-form-item>

      <h2 class="group">预约与筛选</h2>
      <el-form-item label="可预约状态">
        <el-select v-model="form.availability" clearable style="width: 160px">
          <el-option v-for="option in AVAILABILITY_OPTIONS" :key="option.value" :value="option.value" :label="option.label" />
        </el-select>
      </el-form-item>
      <el-form-item label="详情页主按钮">
        <el-select v-model="form.primaryAction" clearable style="width: 160px">
          <el-option v-for="option in PRIMARY_ACTION_OPTIONS" :key="option.value" :value="option.value"
                     :label="option.label" />
        </el-select>
      </el-form-item>
      <el-form-item label="巡检方式">
        <el-checkbox-group v-model="form.serviceMethods">
          <el-checkbox v-for="option in METHOD_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</el-checkbox>
        </el-checkbox-group>
        <el-checkbox v-model="form.includesReport" style="margin-left: 24px">含巡检报告</el-checkbox>
      </el-form-item>
      <el-form-item label="覆盖城市" prop="cityCodes">
        <CityMultiSelect v-model="form.cityCodes" :known-names="cityNames" />
        <span class="hint">第一个是主城市；只到城市级，距离排序用已有的中心点</span>
      </el-form-item>

      <h2 class="group">服务项目与详情</h2>
      <el-form-item v-for="section in ITEM_SECTIONS" :key="section.key" :label="section.label">
        <RowListEditor
          :model-value="form[section.key]"
          :fields="itemFields(section.titleMax)"
          @update:model-value="(value: Record<string, string>[]) => (form[section.key] = value as ItemRow[])"
          :max="section.max"
          :new-row="newItem"
        />
        <span v-if="section.hint" class="hint">{{ section.hint }}</span>
      </el-form-item>
    </el-form>

    <template v-if="detail">
      <h2 class="group">改动记录</h2>
      <ChangeLogTable :changes="changes" :subject-labels="CHANGE_SUBJECT_LABELS" :action-labels="CHANGE_ACTION_LABELS" />
    </template>
  </PageSection>
</template>

<style scoped>
.group {
  margin: 20px 0 12px;
  font-size: 15px;
  font-weight: 700;
  color: var(--admin-text);
}

.group:first-of-type {
  margin-top: 0;
}

.hint {
  display: block;
  width: 100%;
  font-size: 12px;
  line-height: 18px;
  color: var(--admin-text-secondary);
}

.problem {
  margin-bottom: 12px;
}

.status {
  align-self: center;
}
</style>
