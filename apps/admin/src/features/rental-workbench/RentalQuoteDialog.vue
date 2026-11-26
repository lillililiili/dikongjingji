<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { confirmAction, showError } from '@/core/ui/feedback'
import { formatMoney } from '@/core/ui/format'

import {
  buildQuoteRequest,
  initialQuoteForm,
  QUOTE_LIMITS,
  quoteTotals,
  type QuoteForm,
  type RentalInquiry,
  type RentalWorkbenchApi,
} from './rental-api'

/** 报价 / 改价：每次提交是新的一版，询价单回到“等客户确认”。 */
const props = defineProps<{
  api: Pick<RentalWorkbenchApi, 'quote'>
  inquiry: RentalInquiry | null
}>()

const emit = defineEmits<{
  changed: [inquiry: RentalInquiry]
  close: []
}>()

const form = reactive<QuoteForm>({
  dailyRentYuan: '',
  days: 1,
  quantity: 1,
  fees: [],
  scheduleAvailable: true,
  validUntil: null,
  terms: '',
})
const problem = ref('')
const busy = ref(false)
let feeKey = 100

const totals = computed(() => quoteTotals(form))
const revising = computed(() => props.inquiry?.quote !== null && props.inquiry?.quote !== undefined)

watch(() => props.inquiry, (inquiry) => {
  if (!inquiry) return
  Object.assign(form, initialQuoteForm(inquiry))
  problem.value = ''
}, { immediate: true })

function addFee(): void {
  if (form.fees.length >= QUOTE_LIMITS.fees) return
  form.fees.push({ key: ++feeKey, name: '', note: '', amountYuan: '0' })
}

function removeFee(key: number): void {
  form.fees = form.fees.filter((fee) => fee.key !== key)
}

async function submit(): Promise<void> {
  const inquiry = props.inquiry
  if (!inquiry || busy.value) return
  const built = buildQuoteRequest(form)
  if (!built.ok) {
    problem.value = built.message
    return
  }
  problem.value = ''
  const confirmed = await confirmAction({
    title: revising.value ? '确认改价' : '确认报价',
    message: `合计 ${formatMoney(totals.value.totalMinor)}（租金 ${formatMoney(totals.value.rentMinor)}`
      + ` + 其他费用 ${formatMoney(totals.value.feesMinor)}）。`
      + (built.request.scheduleAvailable ? '客户可在 App 确认合同后付款。' : '档期不可用，客户只能查看报价。'),
    confirmText: revising.value ? '提交改价' : '提交报价',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const next = await props.api.quote(inquiry.inquiryId, built.request)
    ElMessage.success(`已提交第 ${next.quote?.version ?? 1} 版报价，${next.quote?.scheduleAvailable ? '等客户确认' : '已告知客户档期不可用'}`)
    emit('changed', next)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="inquiry !== null"
    :title="revising ? '改价' : '报价'"
    width="640px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <template v-if="inquiry">
      <p class="hint">
        {{ inquiry.item.productName }} · {{ inquiry.item.skuName }}，客户要 {{ inquiry.startDate }} 起租
        {{ inquiry.days }} 天、{{ inquiry.quantity }} 台。
      </p>
      <el-form label-width="96px">
        <el-form-item label="日租金（元）" required>
          <el-input v-model="form.dailyRentYuan" placeholder="比如 380" style="width: 180px" class="daily-rent" />
        </el-form-item>
        <el-form-item label="天数" required>
          <el-input-number v-model="form.days" :min="1" :max="QUOTE_LIMITS.days" :step="1" step-strictly />
        </el-form-item>
        <el-form-item label="台数" required>
          <el-input-number v-model="form.quantity" :min="1" :max="QUOTE_LIMITS.quantity" :step="1" step-strictly />
        </el-form-item>
        <el-form-item label="其他费用">
          <div class="fees">
            <div v-for="fee in form.fees" :key="fee.key" class="fee">
              <el-input v-model="fee.name" placeholder="名称，如 运输费" :maxlength="QUOTE_LIMITS.feeName"
                        style="width: 150px" />
              <el-input v-model="fee.amountYuan" placeholder="金额（元）" style="width: 110px" />
              <el-input v-model="fee.note" placeholder="说明（可不填）" :maxlength="QUOTE_LIMITS.feeNote" />
              <el-button link type="danger" @click="removeFee(fee.key)">删除</el-button>
            </div>
            <el-button v-if="form.fees.length < QUOTE_LIMITS.fees" size="small" @click="addFee">加一项费用</el-button>
            <span class="muted">最多 {{ QUOTE_LIMITS.fees }} 项，不能叫“租金”，含在里面不另收的可以填 0。</span>
          </div>
        </el-form-item>
        <el-form-item label="档期">
          <el-switch v-model="form.scheduleAvailable" active-text="这段时间可以出租" inactive-text="档期不可用" />
        </el-form-item>
        <el-form-item label="报价有效期" required>
          <el-date-picker v-model="form.validUntil" type="datetime" placeholder="选择时间"
                          :disabled-date="(date: Date) => date.getTime() < Date.now() - 86_400_000" />
          <span class="muted">晚于现在，最长 {{ QUOTE_LIMITS.validDays }} 天</span>
        </el-form-item>
        <el-form-item label="合同条款" required>
          <el-input v-model="form.terms" type="textarea" :rows="5" :maxlength="QUOTE_LIMITS.terms" show-word-limit
                    placeholder="押金线下收取、损坏赔付、超期计费等，客户确认合同时一并确认" />
        </el-form-item>
      </el-form>
      <div class="total">
        合计 <strong class="total__amount">{{ formatMoney(totals.totalMinor) }}</strong>
        <span class="muted">= 租金 {{ formatMoney(totals.rentMinor) }} + 其他费用 {{ formatMoney(totals.feesMinor) }}</span>
      </div>
      <el-alert v-if="problem" class="problem" type="error" :title="problem" :closable="false" show-icon />
    </template>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">{{ revising ? '提交改价' : '提交报价' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  color: var(--admin-text-secondary);
}

.fees {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.fee {
  display: flex;
  gap: 8px;
  align-items: center;
}

.muted {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.total {
  margin-top: 4px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--admin-surface-muted);
}

.total__amount {
  font-size: 18px;
  color: var(--admin-brand-text);
}

.problem {
  margin-top: 12px;
}
</style>
