<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime, formatMoney, minorToYuanText, yuanToMinor } from '@/core/ui/format'

import {
  INCLUDES_MAX,
  LEAD_DAYS_MAX,
  MODEL_MAX,
  QUOTE_VALIDITY_MAX_MS,
  quoteAction,
  quoteErrors,
  type MallInquiryApi,
  type MallMerchantInquiry,
  type QuoteDraft,
  type QuoteErrors,
} from './mall-inquiry-api'

/** 报价 / 改价：单价、型号配置、交期、包含内容、有效期（晚于现在、不超过 7 天）。合计 = 单价 × 询价数量。 */
const props = defineProps<{
  api: MallInquiryApi
  inquiry: MallMerchantInquiry | null
}>()

const emit = defineEmits<{
  close: []
  done: [inquiry: MallMerchantInquiry]
}>()

const DEFAULT_VALIDITY_MS = 3 * 24 * 60 * 60 * 1000

const form = reactive<{ unitAmount: string, model: string, leadDays: number | null, includes: string, validUntil: Date | null }>({
  unitAmount: '',
  model: '',
  leadDays: null,
  includes: '',
  validUntil: null,
})
const errors = ref<QuoteErrors>({})
const submitting = ref(false)

const action = computed(() => (props.inquiry ? quoteAction(props.inquiry) ?? '报价' : '报价'))
const unitMinor = computed(() => yuanToMinor(form.unitAmount))
const totalMinor = computed(() =>
  unitMinor.value !== null && props.inquiry ? unitMinor.value * props.inquiry.quantity : null)

watch(() => props.inquiry, (inquiry) => {
  errors.value = {}
  const mine = inquiry?.myQuote ?? null
  form.unitAmount = mine ? minorToYuanText(mine.unitAmountMinor) : ''
  form.model = mine?.model ?? ''
  form.leadDays = mine?.leadDays ?? null
  form.includes = mine?.includes ?? ''
  const now = Date.now()
  const previous = mine ? new Date(mine.validUntil).getTime() : Number.NaN
  form.validUntil = previous > now && previous <= now + QUOTE_VALIDITY_MAX_MS
    ? new Date(previous)
    : new Date(now + DEFAULT_VALIDITY_MS)
}, { immediate: true })

function disabledDate(date: Date): boolean {
  const startOfToday = new Date()
  startOfToday.setHours(0, 0, 0, 0)
  return date.getTime() < startOfToday.getTime() || date.getTime() > Date.now() + QUOTE_VALIDITY_MAX_MS
}

function draft(): QuoteDraft {
  return {
    unitAmountMinor: unitMinor.value,
    model: form.model,
    leadDays: form.leadDays,
    includes: form.includes,
    validUntil: form.validUntil ? form.validUntil.toISOString() : null,
  }
}

async function submit(): Promise<void> {
  const inquiry = props.inquiry
  if (!inquiry || submitting.value) return
  const value = draft()
  errors.value = quoteErrors(value)
  if (Object.keys(errors.value).length > 0) return
  const confirmed = await confirmAction({
    title: `确认${action.value}`,
    message: `${inquiry.product.name} × ${inquiry.quantity}，单价 ${formatMoney(value.unitAmountMinor)}，`
      + `合计 ${formatMoney(totalMinor.value)}，交期 ${value.leadDays} 天，有效期到 ${formatDateTime(value.validUntil)}。`
      + '客户马上能看到这份报价。',
    confirmText: `确认${action.value}`,
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const next = await props.api.quote(inquiry.inquiryId, value)
    ElMessage.success(action.value === '改价' ? '已改价，客户看到的是新的一版' : '已报价，等客户比选')
    emit('done', next)
  } catch (failure) {
    showError(failure)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="inquiry !== null"
    :title="inquiry ? `${action}：${inquiry.product.name}` : ''"
    width="560px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <el-form v-if="inquiry" label-width="96px" @submit.prevent>
      <el-form-item label="询价数量">
        <span>{{ inquiry.quantity }} 件，报价截止 {{ formatDateTime(inquiry.quoteDeadline) }}</span>
      </el-form-item>
      <el-form-item label="单价" required :error="errors.unitAmount">
        <el-input v-model="form.unitAmount" class="quote-unit" placeholder="单位：元">
          <template #prefix>¥</template>
        </el-input>
      </el-form-item>
      <el-form-item label="合计">
        <span class="quote-total">{{ formatMoney(totalMinor) }}</span>
      </el-form-item>
      <el-form-item label="型号配置" required :error="errors.model">
        <el-input v-model="form.model" class="quote-model" :maxlength="MODEL_MAX" show-word-limit
                  placeholder="比如：M350 RTK + H30T 双光" />
      </el-form-item>
      <el-form-item label="交期（天）" required :error="errors.leadDays">
        <el-input-number v-model="form.leadDays" class="quote-lead" :min="1" :max="LEAD_DAYS_MAX" :step="1"
                         step-strictly :value-on-clear="null" placeholder="付款后几天交付" />
      </el-form-item>
      <el-form-item label="包含内容" :error="errors.includes">
        <el-input v-model="form.includes" class="quote-includes" type="textarea" :rows="2" :maxlength="INCLUDES_MAX"
                  show-word-limit placeholder="选填，比如：含两块电池、上门培训一次" />
      </el-form-item>
      <el-form-item label="有效期到" required :error="errors.validUntil">
        <el-date-picker v-model="form.validUntil" type="datetime" :disabled-date="disabledDate"
                        format="YYYY-MM-DD HH:mm" placeholder="最长 7 天" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">{{ action }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.quote-total {
  font-weight: 600;
}
</style>
