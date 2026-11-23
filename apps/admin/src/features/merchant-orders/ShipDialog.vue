<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref, watch } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { confirmAction, showError } from '@/core/ui/feedback'

import {
  CARRIER_MAX,
  shipmentError,
  TRACKING_MAX,
  type MerchantOrdersApi,
  type TradeOrder,
} from './merchant-orders-api'

/** 发货：填物流公司和运单号，带订单版本提交（快递、已付款的单才能发）。 */
const props = defineProps<{
  api: MerchantOrdersApi
  order: TradeOrder | null
}>()

const emit = defineEmits<{
  close: []
  done: [order: TradeOrder]
}>()

const form = reactive({ carrierName: '', trackingNo: '' })
const error = ref('')
const submitting = ref(false)
/** 一次“打开弹窗-提交”用同一个幂等键，重试不会重复发货。 */
let idempotencyKey = newIdempotencyKey()

watch(() => props.order, () => {
  form.carrierName = ''
  form.trackingNo = ''
  error.value = ''
  idempotencyKey = newIdempotencyKey()
}, { immediate: true })

async function submit(): Promise<void> {
  const order = props.order
  if (!order || submitting.value) return
  error.value = shipmentError(form.carrierName, form.trackingNo) ?? ''
  if (error.value) return
  const confirmed = await confirmAction({
    title: '确认发货',
    message: `订单 ${order.orderNo} 发货：${form.carrierName.trim()}，运单号 ${form.trackingNo.trim()}。`
      + '提交后买家马上能看到物流信息，同一张单不能换运单号。',
    confirmText: '确认发货',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const shipped = await props.api.ship(order, form.carrierName, form.trackingNo, idempotencyKey)
    ElMessage.success('已发货，买家会收到“订单已发货”的消息')
    emit('done', shipped)
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
    :title="order ? `发货：${order.orderNo}` : ''"
    width="480px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <el-form v-if="order" label-width="84px" @submit.prevent>
      <el-form-item label="收货人">
        <span>{{ order.delivery?.recipientName ?? '—' }}　{{ order.delivery?.recipientPhone ?? order.delivery?.phoneMasked ?? '' }}</span>
      </el-form-item>
      <el-form-item label="收货地址">
        <span>{{ order.delivery?.addressLine ?? '—' }}</span>
      </el-form-item>
      <el-form-item label="物流公司" required>
        <el-input v-model="form.carrierName" class="ship-carrier" :maxlength="CARRIER_MAX" placeholder="比如：顺丰速运" />
      </el-form-item>
      <el-form-item label="运单号" required :error="error">
        <el-input v-model="form.trackingNo" class="ship-tracking" :maxlength="TRACKING_MAX" placeholder="快递面单上的单号" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">确认发货</el-button>
    </template>
  </el-dialog>
</template>
