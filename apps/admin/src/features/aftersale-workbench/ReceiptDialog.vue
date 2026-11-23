<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { confirmAction, showError } from '@/core/ui/feedback'
import { formatYuan, minorToYuan, yuanToMinor } from '@/core/ui/money'

import {
  LIMITS,
  needsRefresh,
  receiptAmountError,
  receiptNoteError,
  type Aftersale,
  type AftersaleApi,
} from './aftersale-api'

/**
 * 确认收货并退款：金额默认是申请金额，可以少退（要写说明），不能多于申请金额；钱经平台支付渠道原路退回买家，
 * 到账以微信的退款结果为准。
 */
const props = defineProps<{
  api: AftersaleApi
  aftersale: Aftersale | null
}>()

const emit = defineEmits<{
  close: []
  done: [next: Aftersale]
  stale: []
}>()

const form = reactive({ amount: '', note: '' })
const errors = reactive({ amount: '', note: '' })
const submitting = ref(false)

const requested = computed(() => props.aftersale?.requestedMinor ?? 0)
const amountMinor = computed(() => yuanToMinor(form.amount))
const partial = computed(() => amountMinor.value !== null && amountMinor.value < requested.value)

watch(() => props.aftersale, (aftersale) => {
  if (!aftersale) return
  form.amount = String(minorToYuan(aftersale.requestedMinor) ?? '')
  form.note = ''
  errors.amount = ''
  errors.note = ''
}, { immediate: true })

async function submit(): Promise<void> {
  const aftersale = props.aftersale
  if (!aftersale || submitting.value) return
  const amount = amountMinor.value
  errors.amount = receiptAmountError(amount, requested.value) ?? ''
  errors.note = receiptNoteError(amount, requested.value, form.note) ?? ''
  if (errors.amount || errors.note || amount === null) return
  const confirmed = await confirmAction({
    title: '确认收货并退款',
    message: `已收到售后 ${aftersale.aftersaleNo} 寄回的商品，给买家退 ${formatYuan(amount)}`
      + `${partial.value ? `（少于申请的 ${formatYuan(requested.value)}，买家能看到你的说明）` : ''}。`
      + '钱经平台支付渠道原路退回买家的微信，提交后不能撤回。',
    confirmText: '确认退款',
    danger: true,
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const next = await props.api.confirmReceipt(aftersale, amount, form.note)
    ElMessage.success(next.refund?.status === 'SUCCEEDED'
      ? '退款已到账'
      : '退款已发起，到账以微信的退款结果为准，一般几分钟内更新')
    emit('done', next)
  } catch (failure) {
    showError(failure)
    if (needsRefresh(failure)) emit('stale')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="aftersale !== null"
    :title="aftersale ? `确认收货并退款：${aftersale.aftersaleNo}` : ''"
    width="500px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <el-form v-if="aftersale" label-width="84px" @submit.prevent>
      <el-form-item label="订单实付">{{ formatYuan(aftersale.paidMinor) }}</el-form-item>
      <el-form-item label="申请金额">
        <strong>{{ formatYuan(aftersale.requestedMinor) }}</strong>
        <span class="receipt__hint">退款不能超过这个数</span>
      </el-form-item>
      <el-form-item label="退款金额" required :error="errors.amount">
        <el-input v-model="form.amount" class="receipt-amount" placeholder="单位：元">
          <template #prefix>¥</template>
        </el-input>
      </el-form-item>
      <el-form-item label="说明" :required="partial" :error="errors.note">
        <el-input v-model="form.note" class="receipt-note" type="textarea" :rows="2" :maxlength="LIMITS.note"
                  show-word-limit :placeholder="partial ? '少退的原因，买家能看到，比如：缺少充电器' : '可空'" />
      </el-form-item>
    </el-form>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="退款经平台支付渠道（微信支付）原路退回买家，平台不经手资金；到账以微信的退款结果为准。"
    />
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="danger" :loading="submitting" @click="submit">确认收货并退款</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.receipt__hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
