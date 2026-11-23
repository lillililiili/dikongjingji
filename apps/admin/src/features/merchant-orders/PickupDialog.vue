<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { ref, watch } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { isApiError } from '@/core/http/api-error'
import { confirmAction, showError } from '@/core/ui/feedback'

import { pickupCodeError, type MerchantOrdersApi, type TradeOrder } from './merchant-orders-api'

/** 自提核销：输入买家出示的 6 位取货码（取货码只给买家，商家这边看不到）。 */
const props = defineProps<{
  api: MerchantOrdersApi
  order: TradeOrder | null
}>()

const emit = defineEmits<{
  close: []
  done: [order: TradeOrder]
}>()

const code = ref('')
const error = ref('')
const submitting = ref(false)
let idempotencyKey = newIdempotencyKey()

watch(() => props.order, () => {
  code.value = ''
  error.value = ''
  idempotencyKey = newIdempotencyKey()
}, { immediate: true })

async function submit(): Promise<void> {
  const order = props.order
  if (!order || submitting.value) return
  error.value = pickupCodeError(code.value) ?? ''
  if (error.value) return
  const confirmed = await confirmAction({
    title: '确认核销',
    message: `核对过买家和商品了吗？核销后订单 ${order.orderNo} 就完成了，不能撤回。`,
    confirmText: '确认核销',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const completed = await props.api.completePickup(order, code.value, idempotencyKey)
    ElMessage.success('核销成功，订单已完成')
    emit('done', completed)
  } catch (failure) {
    if (isApiError(failure, 'TRADE_PICKUP_CODE_INVALID')) {
      // 留在弹窗里让人重新输；后端连续错 10 次后锁定这张单，同样是这个错误码。
      error.value = '取货码不对，请让买家重新出示。连续输错 10 次后这张单不能再核销，请买家联系平台客服。'
      code.value = ''
      idempotencyKey = newIdempotencyKey()
    } else {
      showError(failure)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="order !== null"
    :title="order ? `自提核销：${order.orderNo}` : ''"
    width="440px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <el-form v-if="order" label-width="84px" @submit.prevent>
      <el-form-item label="自提点">
        <span>{{ order.delivery?.pickupPoint ?? '—' }}</span>
      </el-form-item>
      <el-form-item label="取货码" required :error="error">
        <el-input v-model="code" class="pickup-code" maxlength="6" inputmode="numeric" placeholder="买家出示的 6 位数字" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">核销</el-button>
    </template>
  </el-dialog>
</template>
