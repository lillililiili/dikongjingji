<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { confirmAction, showError } from '@/core/ui/feedback'
import { formatMoney, yuanToMinor } from '@/core/ui/format'

import {
  buildQuoteRequest,
  initialQuoteForm,
  QUOTE_LIMITS,
  quoteAmountMinor,
  type DemandQuotesApi,
  type ProviderDemand,
  type QuoteForm,
} from './demand-quotes-api'
import { isPerUnit, lineAmountMinor, LINE_LIMITS, newLineForm, type LineForm } from './quote-lines'

/**
 * 报价 / 改价：截止之前、报价没被选定时可以改；第一次报价会给发布人发一条站内信。
 * 报价明细可以不填；填了明细时总价按明细自动合计（总价框只读），不会出现合计和总价对不上。
 */
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
  lines: [],
  team: '',
  nightWork: '',
  reportHours: null,
  archiveDays: null,
  firstVisitDate: null,
})
const problem = ref('')
const busy = ref(false)

const revising = computed(() => props.demand?.myQuote?.status === 'ACTIVE' || props.demand?.myQuote?.status === 'EXPIRED')
const hasLines = computed(() => form.lines.length > 0)
const totalMinor = computed(() => quoteAmountMinor(form))
const amountPreview = computed(() => (hasLines.value
  ? `按明细合计 ${formatMoney(totalMinor.value)}`
  : formatMoney(yuanToMinor(form.amountYuan))))

function addLine(): void {
  if (form.lines.length < LINE_LIMITS.lines) form.lines.push(newLineForm())
}

function removeLine(index: number): void {
  form.lines.splice(index, 1)
}

function lineAmountText(line: LineForm): string {
  return isPerUnit(line) ? formatMoney(lineAmountMinor(line)) : ''
}

function disabledBeforeToday(date: Date): boolean {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return date.getTime() < today.getTime()
}

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
    width="760px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <template v-if="demand">
      <p class="hint">{{ demand.title }}，报价截止前都可以改。</p>
      <el-form label-width="96px">
        <el-form-item label="报价（元）" required>
          <el-input v-if="!hasLines" v-model="form.amountYuan" placeholder="比如 12800" style="width: 180px" />
          <span class="muted" :class="{ total: hasLines }">{{ amountPreview }}</span>
        </el-form-item>
        <el-form-item label="报价明细">
          <div class="lines">
            <div v-if="hasLines" class="line line--head">
              <span class="line__name">名称</span>
              <span class="line__qty">数量</span>
              <span class="line__unit">单位</span>
              <span class="line__price">单价（元）</span>
              <span class="line__amount">金额（元）</span>
              <span class="line__op" />
            </div>
            <div v-for="(line, index) in form.lines" :key="line.key" class="line">
              <el-input v-model="line.name" class="line__name" :maxlength="LINE_LIMITS.name" placeholder="如 无人机航拍" />
              <el-input v-model="line.quantity" class="line__qty" placeholder="6" />
              <el-input v-model="line.unit" class="line__unit" :maxlength="LINE_LIMITS.unit" placeholder="次" />
              <el-input v-model="line.unitYuan" class="line__price" placeholder="1800" />
              <span v-if="isPerUnit(line)" class="line__amount line__amount--auto">{{ lineAmountText(line) }}</span>
              <el-input v-else v-model="line.amountYuan" class="line__amount" placeholder="只填金额" />
              <el-button class="line__op" link type="danger" @click="removeLine(index)">删除</el-button>
            </div>
            <el-button v-if="form.lines.length < LINE_LIMITS.lines" size="small" @click="addLine">加一行明细</el-button>
            <span class="muted">
              最多 {{ LINE_LIMITS.lines }} 行，可以不填。按次计的填数量、单位、单价（金额自动算）；不按次计的只填金额。
              有明细时总价 = 明细合计。
            </span>
          </div>
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
        <h4 class="group">对比项（选填，客户比选时并排看；不填的不显示）</h4>
        <el-form-item label="服务团队">
          <el-input v-model="form.team" :maxlength="LINE_LIMITS.team" show-word-limit placeholder="比如：东营本地"
                    style="width: 260px" />
        </el-form-item>
        <el-form-item label="夜间作业">
          <el-input v-model="form.nightWork" :maxlength="LINE_LIMITS.nightWork" show-word-limit
                    placeholder="比如：机器狗夜巡，或“不含”" style="width: 260px" />
        </el-form-item>
        <el-form-item label="报告时效">
          <el-input-number v-model="form.reportHours" :min="1" :max="LINE_LIMITS.maxReportHours" :step="1"
                           step-strictly :value-on-clear="null" placeholder="不填" />
          <span class="muted">作业后多少小时内交付报告</span>
        </el-form-item>
        <el-form-item label="影像存档">
          <el-input-number v-model="form.archiveDays" :min="0" :max="LINE_LIMITS.maxArchiveDays" :step="1"
                           step-strictly :value-on-clear="null" placeholder="不填" />
          <span class="muted">天；0 表示不含存档</span>
        </el-form-item>
        <el-form-item label="首次进场">
          <el-date-picker v-model="form.firstVisitDate" type="date" value-format="YYYY-MM-DD" placeholder="不填"
                          :disabled-date="disabledBeforeToday" />
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

.muted.total {
  margin-left: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--admin-text);
}

.group {
  margin: 8px 0 12px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.lines {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  width: 100%;
}

.lines .muted {
  margin-left: 0;
}

.line {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
}

.line--head {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.line__name {
  flex: 3;
}

.line__qty,
.line__unit {
  flex: 1;
}

.line__price,
.line__amount {
  flex: 2;
}

.line__amount--auto {
  font-size: 13px;
}

.line__op {
  flex: 0 0 40px;
}
</style>
