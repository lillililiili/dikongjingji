<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { confirmAction, showError } from '@/core/ui/feedback'
import { charCount, formatMoney, minorToYuanText, yuanToMinor } from '@/core/ui/format'

import {
  DELIVERY_FILE_TYPES,
  type CurrentProviderPlan,
  type DeliveryFileType,
  type InspectionWorkbenchApi,
  type PublishedPlan,
  type PublishPlanRequest,
  type Reservation,
} from './inspection-workbench-api'
import {
  beijingToday,
  DEFAULT_DELIVERABLES,
  planTotalMinor,
  SERVICE_METHOD_OPTIONS,
  visitDatesError,
  type PlanItemDraft,
} from './inspection-rules'

/**
 * 出方案（publishInspectionPlan）：If-Match 是预约的版本号。客户还没确认时可以再发一版（要写变更说明），
 * 客户看到的是最新一版。明细行和交付物的编码由页面按顺序生成，操作员只填名称。
 */
const props = defineProps<{
  api: InspectionWorkbenchApi
  reservation: Reservation
  previous: CurrentProviderPlan | null
}>()

const emit = defineEmits<{
  published: [plan: PublishedPlan]
  close: []
}>()

function addDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
}

const today = beijingToday()
const firstDate = props.reservation.preferredServiceDate >= today ? props.reservation.preferredServiceDate : today
const previous = props.previous
const priorMethods = previous?.serviceMethods ?? []
const methodOptions = [
  ...priorMethods,
  ...SERVICE_METHOD_OPTIONS.filter(option => !priorMethods.some(method => method.code === option.code)),
]
const items: PlanItemDraft[] = previous
  ? previous.planItems.map(({ code, name, quantity, unit, unitPriceMinor }) =>
    ({ code, name, quantity, unit, unitPrice: minorToYuanText(unitPriceMinor) }))
  : props.reservation.inspectionItems.map(({ code, label }) => ({ code, name: label, quantity: 1, unit: '项', unitPrice: '' }))
const deliverables: Array<{ code: string, name: string, fileType: DeliveryFileType | null }> = previous
  ? previous.deliverables.map(({ code, label, fileType }) => ({ code, name: label, fileType }))
  : DEFAULT_DELIVERABLES.map(item => ({ ...item }))

const form = reactive({
  validUntil: previous?.validUntil ?? new Date(Date.now() + 3 * 86_400_000).toISOString(),
  visits: previous?.visits.length ? previous.visits.map(visit => visit.plannedDate) : [previous?.serviceDate ?? firstDate],
  summary: previous?.summary ?? '',
  scopeSummary: previous?.scopeSummary ?? '',
  serviceMethods: previous ? previous.serviceMethods.map(method => method.code) : ['DRONE'],
  reportDueHours: previous ? previous.deliveryTerms?.reportDueHours ?? null : 48,
  mediaRetentionDays: previous ? previous.deliveryTerms?.mediaRetentionDays ?? null : 90,
  changeNote: '',
  items,
  deliverables,
})
if (form.items.length === 0) form.items.push({ code: 'ITEM-1', name: '', quantity: 1, unit: '项', unitPrice: '' })

const busy = ref(false)
/** 一次“填写-提交”用同一个幂等键，提交失败后重试不会建两版。 */
let key = newIdempotencyKey()

const total = computed(() => planTotalMinor(form.items))

function addVisit(): void {
  const last = form.visits[form.visits.length - 1] ?? firstDate
  form.visits.push(addDays(last, 7))
}

function addItem(): void {
  form.items.push({ code: nextCode('ITEM', form.items), name: '', quantity: 1, unit: '项', unitPrice: '' })
}

function addDeliverable(): void {
  form.deliverables.push({ code: nextCode('D', form.deliverables), name: '', fileType: null })
}

function nextCode(prefix: string, rows: Array<{ code: string }>): string {
  let index = 1
  while (rows.some(row => row.code === `${prefix}-${index}`)) index++
  return `${prefix}-${index}`
}

/** 提交前检查，返回第一条问题。 */
function problem(): string | null {
  if (!form.validUntil || new Date(form.validUntil).getTime() <= Date.now()) return '有效期要晚于现在'
  const dates = visitDatesError(form.visits, today)
  if (dates) return dates
  if (charCount(form.summary.trim()) > 1000) return '方案概述最多 1000 个字'
  if (charCount(form.scopeSummary.trim()) > 120) return '巡检范围最多 120 个字'
  if (form.items.length === 0 || form.items.length > 50) return '明细要有 1–50 行'
  for (const [index, item] of form.items.entries()) {
    if (!item.name.trim() || charCount(item.name.trim()) > 120) return `第 ${index + 1} 行明细要填名称（≤120 字）`
    if (!item.unit.trim() || charCount(item.unit.trim()) > 24) return `第 ${index + 1} 行明细要填单位（≤24 字）`
    if (yuanToMinor(item.unitPrice) === null) return `第 ${index + 1} 行明细的单价格式不对（元，最多两位小数）`
  }
  if (form.deliverables.length === 0 || form.deliverables.length > 20) return '交付物要有 1–20 项'
  for (const [index, item] of form.deliverables.entries()) {
    if (!item.name.trim() || charCount(item.name.trim()) > 120) return `第 ${index + 1} 项交付物要填名称`
  }
  if (previous && !form.changeNote.trim()) return '再发一版要写变更说明，客户会看到'
  if (charCount(form.changeNote.trim()) > 200) return '变更说明最多 200 个字'
  return null
}

function request(): PublishPlanRequest {
  const text = (value: string) => value.trim() || null
  return {
    providerId: props.reservation.providerId ?? '',
    validUntil: new Date(form.validUntil).toISOString(),
    visits: form.visits.map((plannedDate) => ({ plannedDate })),
    summary: text(form.summary),
    scopeSummary: text(form.scopeSummary),
    serviceMethods: methodOptions.filter((option) => form.serviceMethods.includes(option.code))
      .map((option) => ({ code: option.code, label: option.label })),
    deliveryTerms: form.reportDueHours === null && form.mediaRetentionDays === null
      ? null
      : { reportDueHours: form.reportDueHours, mediaRetentionDays: form.mediaRetentionDays },
    changeNote: previous ? text(form.changeNote) : null,
    planItems: form.items.map((item) => ({
      code: item.code,
      name: item.name.trim(),
      quantity: item.quantity,
      unit: item.unit.trim(),
      unitPriceMinor: yuanToMinor(item.unitPrice) ?? 0,
    })),
    deliverables: form.deliverables.map((item) => ({ code: item.code, name: item.name.trim(), fileType: item.fileType })),
    currency: 'CNY',
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
    title: previous ? '再发一版方案' : '发布方案',
    message: `共 ${form.visits.length} 次巡检，合计 ${formatMoney(total.value)}。发布后客户在 App 里能看到并确认；客户确认后就不能再改。`,
    confirmText: '发布',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const plan = await props.api.publishPlan(props.reservation, request(), key)
    key = newIdempotencyKey()
    ElMessage.success(`已发布方案 ${plan.planNo}（第 ${plan.planVersion} 版）`)
    emit('published', plan)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="true" :title="previous ? '再发一版方案' : '出方案'" width="860px"
             :close-on-click-modal="false" @close="emit('close')">
    <el-form label-width="96px" class="plan-form">
      <el-form-item label="有效期至">
        <el-date-picker v-model="form.validUntil" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss.SSSZ"
                        placeholder="客户要在这之前确认" />
      </el-form-item>
      <el-form-item label="巡检日期">
        <div class="rows">
          <div v-for="(_, index) in form.visits" :key="`visit-${index}`" class="row">
            <span class="row__label">第 {{ index + 1 }} 次</span>
            <el-date-picker v-model="form.visits[index]" type="date" value-format="YYYY-MM-DD" :clearable="false" />
            <el-button v-if="form.visits.length > 1" link type="danger" @click="form.visits.splice(index, 1)">删除</el-button>
          </div>
          <el-button size="small" :disabled="form.visits.length >= 52" @click="addVisit">加一次（默认隔 7 天）</el-button>
          <div class="hint">日期严格往后排，第 1 次是方案的服务日期。客户期望 {{ reservation.preferredServiceDate }}。</div>
        </div>
      </el-form-item>
      <el-form-item label="方案概述">
        <el-input v-model="form.summary" type="textarea" :rows="2" maxlength="1000" show-word-limit />
      </el-form-item>
      <el-form-item label="巡检范围">
        <el-input v-model="form.scopeSummary" maxlength="120" show-word-limit placeholder="如：园区周界约 3.2 公里" />
      </el-form-item>
      <el-form-item label="服务方式">
        <el-checkbox-group v-model="form.serviceMethods">
          <el-checkbox v-for="option in methodOptions" :key="option.code" :value="option.code">
            {{ option.label }}
          </el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item label="交付约定">
        报告在现场完成后
        <el-input-number v-model="form.reportDueHours" :min="1" :max="8760" size="small" />
        小时内交付，影像保存
        <el-input-number v-model="form.mediaRetentionDays" :min="1" :max="3650" size="small" />
        天
      </el-form-item>
      <el-form-item label="报价明细">
        <div class="rows">
          <div v-for="(item, index) in form.items" :key="item.code" class="row">
            <el-input v-model="item.name" placeholder="名称" style="width: 220px" maxlength="120" />
            <el-input-number v-model="item.quantity" :min="1" :step="1" step-strictly size="small" />
            <el-input v-model="item.unit" placeholder="单位" style="width: 70px" maxlength="24" />
            <el-input v-model="item.unitPrice" placeholder="单价（元）" style="width: 120px" />
            <el-button v-if="form.items.length > 1" link type="danger" @click="form.items.splice(index, 1)">删除</el-button>
          </div>
          <el-button size="small" :disabled="form.items.length >= 50" @click="addItem">加一行</el-button>
          <div class="total">合计 <strong>{{ formatMoney(total) }}</strong>（每行数量 × 单价）</div>
        </div>
      </el-form-item>
      <el-form-item label="交付物">
        <div class="rows">
          <div v-for="(item, index) in form.deliverables" :key="item.code" class="row">
            <el-input v-model="item.name" placeholder="名称" style="width: 220px" maxlength="120" />
            <el-select v-model="item.fileType" clearable placeholder="对应文件类型" style="width: 180px">
              <el-option v-for="type in DELIVERY_FILE_TYPES" :key="type.type" :label="type.label" :value="type.type" />
            </el-select>
            <el-button v-if="form.deliverables.length > 1" link type="danger" @click="form.deliverables.splice(index, 1)">
              删除
            </el-button>
          </div>
          <el-button size="small" :disabled="form.deliverables.length >= 20" @click="addDeliverable">加一项</el-button>
          <div class="hint">每次交付要按这里写的文件类型各传一份；都不写文件类型时，每次要四类文件各一份。</div>
        </div>
      </el-form-item>
      <el-form-item v-if="previous" label="变更说明">
        <el-input v-model="form.changeNote" maxlength="200" show-word-limit placeholder="这一版改了什么，客户会看到" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">发布方案</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.row__label {
  width: 56px;
  color: var(--admin-text-secondary);
}

.hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.total {
  color: var(--admin-text);
}
</style>
