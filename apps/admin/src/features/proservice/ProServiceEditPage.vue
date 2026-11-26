<script setup lang="ts">
/**
 * 一项专业服务的编辑：新建（/pro-services/new，保存后是未上架）或修改；上架、下架；改动记录。
 * 整份提交（带版本号）；缺必填项时后端回 OPS_PUBLISH_INCOMPLETE，这里翻成“还差：…”。
 */
import type { FormInstance } from 'element-plus'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { useChangeHistory } from '@/core/ops/use-change-history'
import ChangeLogTable from '@/core/ops/ChangeLogTable.vue'
import MediaField from '@/core/ops/MediaField.vue'
import { describeMissing } from '@/core/ops/ops-common'
import RowListEditor from '@/core/ops/RowListEditor.vue'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'
import CityMultiSelect from '@/features/organization/CityMultiSelect.vue'
import ProviderSelect from '@/features/organization/ProviderSelect.vue'

import { createProServiceApi, type OpsProService } from './proservice-api'
import {
  CATEGORY_OPTIONS,
  CHANGE_ACTION_LABELS,
  CHANGE_SUBJECT_LABELS,
  emptyForm,
  FACET_LABELS,
  formFromDetail,
  LABEL_LISTS,
  LIMITS,
  LINE_LISTS,
  proServiceRules,
  PUBLISH_FIELD_LABELS,
  statusOption,
  toRequest,
  TYPE_OPTIONS,
  typeOption,
  type ProServiceForm,
} from './proservice-rules'

const route = useRoute()
const router = useRouter()
const api = createProServiceApi(http)

const formRef = ref<FormInstance>()
const form = reactive<ProServiceForm>(emptyForm())
const detail = shallowRef<OpsProService | null>(null)
const { changes, loading: changesLoading, error: changesError, load: loadChanges } = useChangeHistory(api.changes)
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)
const problem = ref('')

const serviceId = computed(() => (route.name === 'pro-service-new' ? '' : String(route.params.serviceId ?? '')))
const creating = computed(() => serviceId.value === '')
const typeMeta = computed(() => typeOption(form.type))
const dirty = computed(() => detail.value !== null
  && JSON.stringify(formFromDetail(detail.value)) !== JSON.stringify(form))
const cityNames = computed(() => Object.fromEntries((detail.value?.cities ?? [])
  .map((city) => [city.cityCode, city.cityName])))
const labelFields = [{ key: 'label', label: '内容', max: LIMITS.label }] as const
const lineFields = [
  { key: 'label', label: '名称', max: LIMITS.label, grow: 1 },
  { key: 'detail', label: '说明', max: LIMITS.detail, grow: 3 },
] as const

// 换了类型，不属于新类型的筛选项去掉。
watch(() => form.type, (type) => {
  form.facets = form.facets.filter((facet) => typeOption(type).facets.includes(facet))
})

function show(next: OpsProService): void {
  detail.value = next
  Object.assign(form, formFromDetail(next))
  formRef.value?.clearValidate()
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    if (!creating.value) {
      show(await api.detail(serviceId.value))
      void loadChanges(serviceId.value)
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
      ? (current.status === 'PUBLISHED' ? '这项服务已上架，保存后 App 上马上看到新的内容。' : '保存后仍是未上架。')
      : '新建后是未上架，资料齐了再上架。',
    confirmText: '保存',
  })
  if (!confirmed) return
  busy.value = true
  try {
    if (current) {
      show(await api.update(current.serviceId, request, current.version))
      notifyRecorded('保存修改', '改动记录')
      void loadChanges(current.serviceId)
    } else {
      const created = await api.create(request)
      notifyRecorded('新建服务', '改动记录')
      await router.replace({ name: 'pro-service', params: { serviceId: created.serviceId } })
    }
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
      ? `上架后，上架城市的用户在 App 专业服务目录里能看到“${current.title}”。`
      : `下架后 App 上马上看不到“${current.title}”；已经发出的需求和报价不受影响。`,
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
    void loadChanges(current.serviceId)
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
    :title="creating ? '新建专业服务' : (detail?.title ?? '专业服务')"
    :description="detail ? `${typeOption(detail.type).label} · 最后修改 ${formatDateTime(detail.updatedAt)}${detail.sample ? ' · 示例数据' : ''}` : '新建后是未上架，资料齐了再上架。'"
    traced
  >
    <template #actions>
      <el-tag v-if="detail" :type="statusOption(detail.status).tag" class="status">
        {{ statusOption(detail.status).label }}
      </el-tag>
      <el-button @click="router.push({ name: 'pro-services' })">返回列表</el-button>
      <template v-if="detail">
        <el-button v-if="detail.status === 'PUBLISHED'" type="danger" plain :loading="busy" @click="changeStatus(false)">
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

    <el-form v-if="!loadError" ref="formRef" v-loading="loading" :disabled="loading || busy" :model="form" :rules="proServiceRules" label-width="110px">
      <h2 class="group">基本信息</h2>
      <el-form-item label="服务商" prop="providerId">
        <ProviderSelect v-model="form.providerId" :current-name="detail?.providerName" />
      </el-form-item>
      <el-form-item label="类型">
        <el-radio-group v-model="form.type">
          <el-radio v-for="option in TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="场景分类">
        <el-select v-model="form.categoryCode" placeholder="没有授权照片时 App 按它画图标" style="width: 260px">
          <el-option v-for="option in CATEGORY_OPTIONS" :key="option.value" :value="option.value" :label="option.label" />
        </el-select>
      </el-form-item>
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" :maxlength="LIMITS.title" show-word-limit />
      </el-form-item>
      <el-form-item label="简介" prop="summary">
        <el-input v-model="form.summary" :maxlength="LIMITS.summary" show-word-limit />
      </el-form-item>
      <el-form-item label="封面">
        <MediaField v-model="form.coverUrl" />
      </el-form-item>
      <el-form-item label="综合排序">
        <el-input-number v-model="form.sortOrder" :min="0" :max="9999" />
        <span class="hint">小的在前</span>
      </el-form-item>

      <h2 class="group">价格</h2>
      <el-form-item label="报价方式">
        <el-radio-group v-model="form.priceMode">
          <el-radio value="PRICE_FROM">起步价</el-radio>
          <el-radio value="PROJECT">按项目报价</el-radio>
        </el-radio-group>
      </el-form-item>
      <template v-if="form.priceMode === 'PRICE_FROM'">
        <el-form-item label="起步价（元）" prop="priceYuan">
          <el-input v-model="form.priceYuan" placeholder="如 1800" style="width: 160px" />
          <el-input v-model="form.priceUnit" placeholder="单位，如 /次起" :maxlength="LIMITS.priceUnit"
                    style="width: 160px; margin-left: 8px" />
        </el-form-item>
      </template>
      <el-form-item label="价格说明" prop="priceNote">
        <el-input v-model="form.priceNote" :maxlength="LIMITS.priceNote" show-word-limit
                  :placeholder="form.priceMode === 'PRICE_FROM' ? '起步价含什么（必填）' : '选填'" />
      </el-form-item>

      <el-form-item label="质保期" prop="warrantyText">
        <el-input v-model="form.warrantyText" :maxlength="LIMITS.warrantyText" show-word-limit
                  placeholder="选填，如：维修后质保 90 天" />
        <span class="hint">App 设备维护详情的“保修与收费边界”里显示；不填就不显示</span>
      </el-form-item>

      <h2 class="group">上架城市与筛选</h2>
      <el-form-item label="上架城市">
        <CityMultiSelect v-model="form.cityCodes" :known-names="cityNames" />
        <span class="hint">上架前至少一个</span>
      </el-form-item>
      <el-form-item :label="typeMeta.facetLabel">
        <el-checkbox-group v-model="form.facets">
          <el-checkbox v-for="facet in typeMeta.facets" :key="facet" :value="facet">{{ FACET_LABELS[facet] }}</el-checkbox>
        </el-checkbox-group>
        <span class="hint">目录筛选用，上架前至少选一个</span>
      </el-form-item>

      <h2 class="group">展示内容</h2>
      <el-form-item v-for="list in LABEL_LISTS" :key="list.key" :label="list.label">
        <RowListEditor
          :model-value="form[list.key]"
          :fields="labelFields"
          :max="LIMITS.items"
          :new-row="() => ({ label: '' })"
          @update:model-value="(value: Record<string, string>[]) => (form[list.key] = value as Array<{ label: string }>)"
        />
        <span v-if="list.hint" class="hint">{{ list.hint }}</span>
      </el-form-item>
      <el-form-item v-for="list in LINE_LISTS" :key="list.key" :label="list.label">
        <RowListEditor
          :model-value="form[list.key]"
          :fields="lineFields"
          :max="LIMITS.items"
          :new-row="() => ({ label: '', detail: '' })"
          @update:model-value="(value: Record<string, string>[]) => (form[list.key] = value as Array<{ label: string, detail: string }>)"
        />
        <span v-if="list.hint" class="hint">{{ list.hint }}</span>
      </el-form-item>
    </el-form>

    <template v-if="detail">
      <h2 class="group">改动记录</h2>
      <el-button v-if="changesError" size="small" @click="loadChanges(serviceId)">重试改动记录</el-button>
      <ChangeLogTable :changes="changes" :error="changesError" :loading="changesLoading" :subject-labels="CHANGE_SUBJECT_LABELS" :action-labels="CHANGE_ACTION_LABELS" />
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
