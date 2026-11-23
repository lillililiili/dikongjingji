<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatMoney, minorToYuanText, yuanToMinor } from '@/core/ui/format'

import {
  REFUND_REASON_MAX,
  refundableMinor,
  refundAmountError,
  refundReasonError,
  type MerchantOrdersApi,
  type TradeOrder,
  type TradeRefund,
} from './merchant-orders-api'

/** 商家退款：可部分退，金额不超过可退金额；钱按原路退回买家，结果以微信退款回调为准。 */
const props = defineProps<{
  api: MerchantOrdersApi
  order: TradeOrder | null
}>()

const emit = defineEmits<{
  close: []
  done: [refund: TradeRefund]
}>()

const form = reactive({ amount: '', reason: '' })
const errors = reactive({ amount: '', reason: '' })
const submitting = ref(false)
let idempotencyKey = newIdempotencyKey()

const refundable = computed(() => (props.order ? refundableMinor(props.order) : 0))

watch(() => props.order, (order) => {
  form.amount = order ? minorToYuanText(refundableMinor(order)) : ''
  form.reason = ''
  errors.amount = ''
  errors.reason = ''
  idempotencyKey = newIdempotencyKey()
}, { immediate: true })

/** 改了金额或原因就是另一笔退款，换一个幂等键（同一个键换内容后端会拒绝）。 */
watch(() => [form.amount, form.reason], () => {
  idempotencyKey = newIdempotencyKey()
})

async function submit(): Promise<void> {
  const order = props.order
  if (!order || submitting.value) return
  const amountMinor = yuanToMinor(form.amount)
  errors.amount = refundAmountError(amountMinor, refundable.value) ?? ''
  errors.reason = refundReasonError(form.reason) ?? ''
  if (errors.amount || errors.reason || amountMinor === null) return
  const whole = amountMinor === refundable.value
  const confirmed = await confirmAction({
    title: '确认退款',
    message: `给订单 ${order.orderNo} 退 ${formatMoney(amountMinor)}${whole ? '（剩下的全部）' : '（部分退款）'}，`
      + '钱按原路退回买家的微信。提交后不能撤回。',
    confirmText: '确认退款',
    danger: true,
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const refund = await props.api.refund(order.orderId, amountMinor, form.reason, idempotencyKey)
    ElMessage.success(refund.status === 'SUCCEEDED'
      ? '退款已到账'
      : '退款已提交微信，到账以微信的退款结果为准，一般几分钟内更新')
    emit('done', refund)
  } catch (failure) {
    showError(failure)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="order !== null"
    :title="order ? `退款：${order.orderNo}` : ''"
    width="480px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <el-form v-if="order" label-width="84px" @submit.prevent>
      <el-form-item label="实付">
        <span>{{ formatMoney(order.amounts.paidMinor) }}，已退 {{ formatMoney(order.amounts.refundedMinor) }}</span>
      </el-form-item>
      <el-form-item label="可退">
        <span class="refund-dialog__refundable">{{ formatMoney(refundable) }}</span>
        <span class="refund-dialog__hint">实付减去已退和正在退的</span>
      </el-form-item>
      <el-form-item label="退款金额" required :error="errors.amount">
        <el-input v-model="form.amount" class="refund-amount" placeholder="单位：元">
          <template #prefix>¥</template>
        </el-input>
      </el-form-item>
      <el-form-item label="退款原因" required :error="errors.reason">
        <el-input v-model="form.reason" class="refund-reason" type="textarea" :rows="2" :maxlength="REFUND_REASON_MAX"
                  show-word-limit placeholder="买家能看到，比如：少发一件配件" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="danger" :loading="submitting" @click="submit">确认退款</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.refund-dialog__refundable {
  font-weight: 600;
}

.refund-dialog__hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
