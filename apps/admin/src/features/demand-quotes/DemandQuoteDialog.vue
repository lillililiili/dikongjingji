<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { confirmAction, showError } from '@/core/ui/feedback'
import { formatMoney, yuanToMinor } from '@/core/ui/format'

import {
  buildQuoteRequest,
  initialQuoteForm,
  QUOTE_LIMITS,
  type DemandQuotesApi,
  type ProviderDemand,
  type QuoteForm,
} from './demand-quotes-api'

/** 报价 / 改价：截止之前、报价没被选定时可以改；第一次报价会给发布人发一条站内信。 */
const props = defineProps<{
  api: Pick<DemandQuotesApi, 'quote'>
  demand: ProviderDemand | null
}>()

const emit = defineEmits<{
  changed: [demand: ProviderDemand]
  close: []
}>()

const form = reactive<QuoteForm>({
  amountYuan: '',
  method: '',
  schedule: '',
  durationDays: 1,
  deliverables: '',
  validUntil: null,
})
const problem = ref('')
const busy = ref(false)

const revising = computed(() => props.demand?.myQuote?.status === 'ACTIVE' || props.demand?.myQuote?.status === 'EXPIRED')
const amountPreview = computed(() => formatMoney(yuanToMinor(form.amountYuan)))

watch(() => props.demand, (demand) => {
  if (!demand) return
  Object.assign(form, initialQuoteForm(demand))
  problem.value = ''
}, { immediate: true })

async function submit(): Promise<void> {
  const demand = props.demand
  if (!demand || busy.value) return
  const built = buildQuoteRequest(form)
  if (!built.ok) {
    problem.value = built.message
    return
  }
  problem.value = ''
  const confirmed = await confirmAction({
    title: revising.value ? '确认改价' : '确认报价',
    message: `报价 ${formatMoney(built.request.amountMinor)}，工期 ${built.request.durationDays} 天。发布人在 App 里比选，`
      + '选定之前你还可以改价或撤回。',
    confirmText: revising.value ? '提交改价' : '提交报价',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const next = await props.api.quote(demand.demandId, built.request)
    ElMessage.success(revising.value ? '已改价，发布人会看到新的报价' : '已报价，发布人会收到通知')
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
    :model-value="demand !== null"
    :title="revising ? '改价' : '报价'"
    width="560px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <template v-if="demand">
      <p class="hint">{{ demand.title }}，报价截止前都可以改。</p>
      <el-form label-width="96px">
        <el-form-item label="报价（元）" required>
          <el-input v-model="form.amountYuan" placeholder="比如 12800" style="width: 180px" />
          <span class="muted">{{ amountPreview }}</span>
        </el-form-item>
        <el-form-item label="做法" required>
          <el-input v-model="form.method" :maxlength="QUOTE_LIMITS.method" show-word-limit
                    placeholder="比如：多旋翼无人机 + 可见光、红外双光" />
        </el-form-item>
        <el-form-item label="排期" required>
          <el-input v-model="form.schedule" :maxlength="QUOTE_LIMITS.schedule" show-word-limit
                    placeholder="比如：确认后 3 天内进场" />
        </el-form-item>
        <el-form-item label="工期（天）" required>
          <el-input-number v-model="form.durationDays" :min="1" :max="QUOTE_LIMITS.maxDurationDays" :step="1"
                           step-strictly />
        </el-form-item>
        <el-form-item label="交付物" required>
          <el-input v-model="form.deliverables" :maxlength="QUOTE_LIMITS.deliverables" show-word-limit
                    placeholder="比如：巡检报告、隐患清单" />
        </el-form-item>
        <el-form-item label="报价有效期" required>
          <el-date-picker v-model="form.validUntil" type="datetime" placeholder="选择时间" />
          <span class="muted">晚于现在，最长 {{ QUOTE_LIMITS.validDays }} 天</span>
        </el-form-item>
      </el-form>
      <el-alert v-if="problem" type="error" :title="problem" :closable="false" show-icon />
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

.muted {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
