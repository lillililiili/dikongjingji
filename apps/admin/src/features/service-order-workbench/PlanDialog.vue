<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'

import RowListEditor from '@/core/ops/RowListEditor.vue'
import { formatMoney } from '@/core/ui/format'
import { lineQuantityLabel, type QuoteLine } from '@/features/demand-quotes/quote-lines'

import type { PlanRequest, ServiceOrder } from './service-order-api'
import { buildPlanRequest, initialPlanForm, LIMITS, type PlanForm } from './service-order-rules'

/**
 * 出方案 / 改方案（客户确认之前）：金额和明细等于选定的报价，只读；服务商只写服务安排、交付约定（1–5 条）和方案说明。
 * 校验过了才发出 submit，接口由详情页调（带 If-Match）。
 */
const props = defineProps<{
  order: ServiceOrder | null
  busy: boolean
}>()

const emit = defineEmits<{
  submit: [request: PlanRequest]
  close: []
}>()

const form = reactive<PlanForm>({ schedule: '', terms: [], note: '' })
const problem = ref('')
const revising = computed(() => props.order?.plan !== null && props.order?.plan !== undefined)
const termFields = [{ key: 'label', label: '交付约定', max: LIMITS.term, placeholder: '比如：作业后 3 天内交付成片和原片' }] as const

watch(() => props.order, (order) => {
  if (!order) return
  Object.assign(form, initialPlanForm(order))
  problem.value = ''
}, { immediate: true })

function submit(): void {
  const built = buildPlanRequest(form)
  problem.value = built.ok ? '' : built.message
  if (built.ok) emit('submit', built.request)
}
</script>

<template>
  <el-dialog
    :model-value="order !== null"
    :title="revising ? `改方案（现在是 v${order?.plan?.version}，保存后 v${(order?.plan?.version ?? 0) + 1}）` : '出方案'"
    width="680px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <template v-if="order">
      <p class="hint">
        金额和明细就是客户选定的报价，不能改。客户确认之前可以再改方案；客户确认的必须是最新一版。
      </p>
      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="方案金额">
          <strong>{{ formatMoney(order.amountMinor) }}</strong>
          <span class="muted">（报价第 {{ order.quote.quoteVersion }} 版）</span>
        </el-form-item>
        <el-form-item label="明细">
          <el-table :data="order.lines" size="small" border class="lines">
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column label="数量 × 单价" min-width="150">
              <template #default="{ row }">{{ lineQuantityLabel(row as QuoteLine) || '—' }}</template>
            </el-table-column>
            <el-table-column label="金额" width="120">
              <template #default="{ row }">{{ formatMoney(row.amountMinor) }}</template>
            </el-table-column>
          </el-table>
        </el-form-item>
        <el-form-item label="服务安排" required>
          <el-input v-model="form.schedule" :maxlength="LIMITS.schedule" show-word-limit
                    placeholder="比如：11/26 上门检测，约半天" />
        </el-form-item>
        <el-form-item label="交付约定" required>
          <RowListEditor
            :model-value="form.terms"
            :fields="termFields"
            :max="LIMITS.terms"
            :new-row="() => ({ label: '' })"
            add-label="加一条"
            @update:model-value="(value: Record<string, string>[]) => (form.terms = value as Array<{ label: string }>)"
          />
          <span class="muted block">1–{{ LIMITS.terms }} 条，每条最多 {{ LIMITS.term }} 个字</span>
        </el-form-item>
        <el-form-item label="方案说明">
          <el-input v-model="form.note" type="textarea" :rows="4" :maxlength="LIMITS.note" show-word-limit
                    placeholder="选填：人员、设备、安全措施、注意事项等" />
        </el-form-item>
      </el-form>
      <el-alert v-if="problem" type="error" :title="problem" :closable="false" show-icon />
    </template>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">{{ revising ? '保存新一版方案' : '出方案' }}</el-button>
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

.muted.block {
  display: block;
  width: 100%;
  margin-left: 0;
}

.lines {
  width: 100%;
}
</style>
