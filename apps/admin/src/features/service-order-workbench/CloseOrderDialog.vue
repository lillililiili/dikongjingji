<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { formatMoney, minorToYuanText } from '@/core/ui/format'

import type { ServiceOrder, TerminateRequest } from './service-order-api'
import {
  buildTerminateRequest,
  cancelReasonError,
  LIMITS,
  paidMinor,
  terminateMaxMinor,
} from './service-order-rules'

/**
 * 关闭订单：开工前“取消”（写原因，已付款的整笔原路退）或开工后“协商终止”（退款按元填、按分提交，0 到实付，写原因）。
 * 校验过了才发出 cancel / terminate，接口和二次确认由详情页做。
 */
const props = defineProps<{
  order: ServiceOrder | null
  mode: 'cancel' | 'terminate'
  busy: boolean
}>()

const emit = defineEmits<{
  cancel: [reason: string]
  terminate: [request: TerminateRequest]
  close: []
}>()

const reason = ref('')
const refundYuan = ref('')
const problem = ref('')

const paid = computed(() => (props.order ? paidMinor(props.order) : 0))
const maxRefund = computed(() => (props.order ? terminateMaxMinor(props.order) : 0))
const terminating = computed(() => props.mode === 'terminate')

watch(() => [props.order, props.mode] as const, ([order]) => {
  if (!order) return
  reason.value = ''
  refundYuan.value = minorToYuanText(terminateMaxMinor(order))
  problem.value = ''
}, { immediate: true })

function submit(): void {
  if (!props.order) return
  if (terminating.value) {
    const built = buildTerminateRequest(refundYuan.value, reason.value, maxRefund.value)
    problem.value = built.ok ? '' : built.message
    if (built.ok) emit('terminate', built.request)
    return
  }
  const error = cancelReasonError(reason.value)
  problem.value = error ?? ''
  if (!error) emit('cancel', reason.value.trim())
}
</script>

<template>
  <el-dialog
    :model-value="order !== null"
    :title="terminating ? '协商终止' : '取消订单'"
    width="520px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <template v-if="order">
      <template v-if="terminating">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="开工之后不能在线取消，只能和客户协商一致后终止。平台只协助沟通、不裁决。"
          description="终止后订单不能恢复；退款金额大于 0 时按原路退回客户。"
        />
        <el-form label-width="96px" class="form" @submit.prevent>
          <el-form-item label="实付">
            {{ formatMoney(paid) }}<span v-if="order.payment.refundedMinor > 0" class="muted">，已退
              {{ formatMoney(order.payment.refundedMinor) }}</span>
          </el-form-item>
          <el-form-item label="退给客户" required>
            <el-input v-model="refundYuan" class="amount" placeholder="单位：元，不退款填 0">
              <template #prefix>¥</template>
            </el-input>
            <span class="muted">0 到 {{ formatMoney(maxRefund) }}</span>
          </el-form-item>
          <el-form-item label="终止原因" required>
            <el-input v-model="reason" type="textarea" :rows="3" :maxlength="LIMITS.terminateReason" show-word-limit
                      placeholder="写清和客户协商的结果，比如：现场条件不具备，双方同意终止，按已完成部分退款" />
          </el-form-item>
        </el-form>
      </template>
      <template v-else>
        <p class="hint">
          开工之前可以取消，客户在 App 里能看到原因。
          <strong v-if="paid > 0">客户已付款 {{ formatMoney(paid) }}，取消后整笔原路退回。</strong>
          <span v-else>客户还没付款，不涉及退款（正在付的那一笔会先关掉）。</span>
        </p>
        <el-input v-model="reason" type="textarea" :rows="3" :maxlength="LIMITS.cancelReason" show-word-limit
                  placeholder="比如：设备临时故障，无法按期服务" />
      </template>
      <p v-if="problem" class="problem">{{ problem }}</p>
    </template>
    <template #footer>
      <el-button @click="emit('close')">不了</el-button>
      <el-button type="danger" :loading="busy" @click="submit">{{ terminating ? '终止订单' : '取消订单' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form {
  margin-top: 12px;
}

.hint {
  margin: 0 0 10px;
  color: var(--admin-text-secondary);
}

.amount {
  width: 180px;
}

.muted {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.problem {
  margin: 8px 0 0;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
