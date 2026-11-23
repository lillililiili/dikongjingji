<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatBytes } from '@/core/ui/format'

import {
  DELIVERY_FILE_TYPES,
  type DeliveryFileType,
  type InspectionWorkbenchApi,
  type IssueGroupBy,
  type PendingDeliveryFile,
  type PublishDeliveryRequest,
  type ServiceVisit,
} from './inspection-workbench-api'
import { deliveryMeasuresError, fileMatchesType } from './inspection-rules'

/**
 * 上传交付文件并发布某一次巡检的交付（publishInspectionDelivery）。每种文件先走三步上传（意图 → 直传存储 → 完成），
 * 全部通过校验后再发布；If-Match 是这一次巡检的版本号。发布后这一次变成“已交付，待客户验收”。
 * 要传哪几类按方案的交付物约定（服务商在出方案时定的）；方案没写文件类型时四类各一份。
 */
const props = defineProps<{
  api: InspectionWorkbenchApi
  visit: ServiceVisit
}>()

const emit = defineEmits<{
  published: []
  close: []
}>()

interface FileSlot {
  type: DeliveryFileType
  label: string
  extension: string
  included: boolean
  pending: PendingDeliveryFile | null
  /** 已经传好、通过校验的文件 ID（发布失败后重试不再重传）。 */
  fileId: string | null
  pointCount: number | null
  itemCount: number | null
}

const slots = reactive<FileSlot[]>(DELIVERY_FILE_TYPES.map((type) => ({
  type: type.type,
  label: type.label,
  extension: type.extension,
  included: true,
  pending: null,
  fileId: null,
  pointCount: null,
  itemCount: null,
})))

const form = reactive({
  inspectedLengthMeters: null as number | null,
  towerCount: null as number | null,
  metrics: [] as Array<{ code: string, label: string, value: number | null, unit: string }>,
  issueGroupBy: 'SEVERITY' as IssueGroupBy,
  issues: [] as Array<{ groupCode: string, groupName: string, count: number }>,
})

const busy = ref(false)
const step = ref('')
let publishKey = newIdempotencyKey()

const issueCount = computed(() => form.issues.reduce((sum, issue) => sum + issue.count, 0))

function choose(slot: FileSlot, event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!fileMatchesType(file.name, slot.extension)) {
    ElMessage.warning(`${slot.label}要选 .${slot.extension} 文件`)
    input.value = ''
    return
  }
  slot.pending = {
    type: slot.type,
    file,
    originalName: file.name,
    idempotencyKey: newIdempotencyKey(),
    completeKey: newIdempotencyKey(),
  }
  slot.fileId = null
}

function addMetric(): void {
  form.metrics.push({ code: `M-${form.metrics.length + 1}`, label: '', value: null, unit: '' })
}

function addIssue(): void {
  form.issues.push({ groupCode: `G-${form.issues.length + 1}`, groupName: '', count: 0 })
}

function problem(): string | null {
  const included = slots.filter((slot) => slot.included)
  if (included.length === 0) return '至少交付一类文件'
  const missing = included.find((slot) => !slot.pending && !slot.fileId)
  if (missing) return `还没选${missing.label}`
  const metrics = form.metrics.filter((metric) => metric.label.trim())
  for (const metric of metrics) {
    if (metric.value === null || metric.value < 0 || !metric.unit.trim()) return `指标“${metric.label}”要填数值和单位`
  }
  const measures = deliveryMeasuresError({
    towerCount: form.towerCount,
    inspectedLengthMeters: form.inspectedLengthMeters,
    metricCount: metrics.length,
  })
  if (measures) return measures
  if (form.issues.some((issue) => !issue.groupName.trim())) return '问题分组要填名称'
  return null
}

function request(fileIds: Map<DeliveryFileType, string>): PublishDeliveryRequest {
  return {
    towerCount: form.towerCount,
    inspectedLengthMeters: form.inspectedLengthMeters,
    metrics: form.metrics.filter((metric) => metric.label.trim()).map((metric) => ({
      code: metric.code,
      label: metric.label.trim(),
      value: metric.value ?? 0,
      unit: metric.unit.trim(),
    })),
    issueCount: issueCount.value,
    issueGroupBy: form.issueGroupBy,
    issueSummary: form.issues.map((issue) => ({
      groupCode: issue.groupCode,
      groupName: issue.groupName.trim(),
      count: issue.count,
    })),
    files: slots.filter((slot) => slot.included).map((slot) => ({
      fileId: fileIds.get(slot.type)!,
      type: slot.type,
      displayName: slot.pending?.originalName ?? slot.label,
      pointCount: slot.type === 'POINT_KML' ? slot.pointCount : null,
      itemCount: slot.type === 'POINT_KML' ? null : slot.itemCount,
    })),
  }
}

async function submit(): Promise<void> {
  if (busy.value) return
  const issue = problem()
  if (issue) {
    ElMessage.warning(issue)
    return
  }
  const confirmed = await confirmAction({
    title: `发布第 ${props.visit.visitNo} 次巡检的交付`,
    message: '文件上传并通过校验后发布。发布后客户在 App 里查看报告并验收，交付不能撤回。',
    confirmText: '上传并发布',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const fileIds = new Map<DeliveryFileType, string>()
    for (const slot of slots.filter((candidate) => candidate.included)) {
      if (!slot.fileId) {
        step.value = `正在上传${slot.label}…`
        slot.fileId = await props.api.uploadDeliveryFile(props.visit.id, slot.pending!)
      }
      fileIds.set(slot.type, slot.fileId)
    }
    step.value = '正在发布…'
    await props.api.publishDelivery(props.visit, request(fileIds), publishKey)
    publishKey = newIdempotencyKey()
    ElMessage.success(`第 ${props.visit.visitNo} 次巡检已交付，等客户验收`)
    emit('published')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
    step.value = ''
  }
}
</script>

<template>
  <el-dialog :model-value="true" :title="`第 ${visit.visitNo} 次巡检：上传交付并发布`" width="820px"
             :close-on-click-modal="false" @close="emit('close')">
    <el-form label-width="96px">
      <el-form-item label="交付文件">
        <div class="slots">
          <div v-for="slot in slots" :key="slot.type" class="slot">
            <el-checkbox v-model="slot.included">{{ slot.label }}</el-checkbox>
            <template v-if="slot.included">
              <input type="file" :accept="`.${slot.extension}`" :disabled="busy" @change="choose(slot, $event)">
              <span v-if="slot.pending" class="muted">{{ formatBytes(slot.pending.file.size) }}</span>
              <el-tag v-if="slot.fileId" type="success" size="small">已上传</el-tag>
              <el-input-number v-if="slot.type === 'POINT_KML'" v-model="slot.pointCount" :min="0" size="small"
                               placeholder="点位数" controls-position="right" />
              <el-input-number v-else v-model="slot.itemCount" :min="0" size="small" placeholder="条目数"
                               controls-position="right" />
            </template>
          </div>
          <div class="hint">按方案约定的文件类型各传一份（方案没写类型时四类都要）；单个文件不超过 1 GB。</div>
        </div>
      </el-form-item>
      <el-form-item label="巡检结果">
        巡检长度
        <el-input-number v-model="form.inspectedLengthMeters" :min="0" size="small" controls-position="right" /> 米，
        塔数
        <el-input-number v-model="form.towerCount" :min="0" size="small" controls-position="right" /> 基
      </el-form-item>
      <el-form-item label="其他指标">
        <div class="rows">
          <div v-for="(metric, index) in form.metrics" :key="metric.code" class="row">
            <el-input v-model="metric.label" placeholder="名称，如巡检覆盖" maxlength="40" style="width: 180px" />
            <el-input-number v-model="metric.value" :min="0" :precision="2" size="small" controls-position="right" />
            <el-input v-model="metric.unit" placeholder="单位" maxlength="16" style="width: 80px" />
            <el-button link type="danger" @click="form.metrics.splice(index, 1)">删除</el-button>
          </div>
          <el-button size="small" :disabled="form.metrics.length >= 12" @click="addMetric">加一项指标</el-button>
        </div>
      </el-form-item>
      <el-form-item label="发现问题">
        <div class="rows">
          <el-radio-group v-model="form.issueGroupBy" size="small">
            <el-radio-button value="SEVERITY">按严重程度分</el-radio-button>
            <el-radio-button value="CATEGORY">按类别分</el-radio-button>
          </el-radio-group>
          <div v-for="(issue, index) in form.issues" :key="issue.groupCode" class="row">
            <el-input v-model="issue.groupName" placeholder="分组，如一般缺陷" maxlength="40" style="width: 180px" />
            <el-input-number v-model="issue.count" :min="0" size="small" /> 处
            <el-button link type="danger" @click="form.issues.splice(index, 1)">删除</el-button>
          </div>
          <el-button size="small" :disabled="form.issues.length >= 20" @click="addIssue">加一个分组</el-button>
          <div class="muted">共 {{ issueCount }} 处问题（各分组相加）</div>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <span class="muted step">{{ step }}</span>
      <el-button :disabled="busy" @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">上传并发布</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.slots,
.rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.slot,
.row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.hint,
.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.step {
  margin-right: 12px;
}
</style>
