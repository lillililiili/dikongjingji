<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import ReasonDialog from './ReasonDialog.vue'
import RentalRecordDialog from './RentalRecordDialog.vue'
import {
  canCancel,
  canRecordHandover,
  canRecordReturn,
  createRentalWorkbenchApi,
  HANDOVER_MODE_LABELS,
  lesseeLabel,
  ORDER_STATUS,
  placeLabel,
  REFUND_STATUS_LABELS,
  RETURN_MODE_LABELS,
  type RentalOrder,
  type RentalRecord,
} from './rental-api'

const route = useRoute()
const router = useRouter()
const api = createRentalWorkbenchApi(http)
const order = shallowRef<RentalOrder | null>(null)
const loading = ref(false)
const error = ref('')
const recording = shallowRef<RentalOrder | null>(null)
const recordKind = ref<'handover' | 'return'>('handover')
const cancelling = ref(false)
const busy = ref(false)

const orderId = computed(() => String(route.params.orderId ?? ''))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    order.value = await api.order(orderId.value)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function openRecord(kind: 'handover' | 'return'): void {
  recordKind.value = kind
  recording.value = order.value
}

function recorded(next: RentalOrder): void {
  order.value = next
  recording.value = null
}

function recordState(record: RentalRecord | null): string {
  if (!record) return '还没录入'
  return record.confirmedAt ? `客户已于 ${formatDateTime(record.confirmedAt)} 确认` : '等客户在 App 里确认'
}

async function cancel(reason: string): Promise<void> {
  const current = order.value
  if (!current || busy.value) return
  const confirmed = await confirmAction({
    title: '确认取消订单并退款',
    message: `租金 ${formatMoney(current.payment.amountMinor)} 会整单原路退回给客户，订单不能恢复。`,
    confirmText: '取消并退款',
    danger: true,
  })
  if (!confirmed) return
  busy.value = true
  try {
    order.value = await api.cancel(current.rentalOrderId, reason)
    cancelling.value = false
    ElMessage.success('订单已取消，租金已提交原路退回')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="order ? `租赁订单 ${order.contract.contractNo}` : '租赁订单'"
    :description="order ? `合同确认于 ${formatDateTime(order.contract.confirmedAt)}` : undefined"
  >
    <template #actions>
      <el-button @click="router.push({ name: 'workbench-rental-orders' })">返回列表</el-button>
      <template v-if="order">
        <el-button v-if="canCancel(order)" type="danger" plain @click="cancelling = true">取消并退款</el-button>
        <el-button v-if="canRecordHandover(order)" type="primary" @click="openRecord('handover')">
          {{ order.handoverRecord ? '改交接记录' : '录入交接' }}
        </el-button>
        <el-button v-if="canRecordReturn(order)" type="primary" @click="openRecord('return')">
          {{ order.returnRecord ? '改归还记录' : '录入归还' }}
        </el-button>
      </template>
    </template>

    <div v-loading="loading">
      <el-result v-if="error && !order" icon="error" :title="error">
        <template #extra><el-button type="primary" @click="load">重新加载</el-button></template>
      </el-result>

      <template v-if="order">
        <div class="status">
          <el-tag :type="ORDER_STATUS[order.status].tag" size="large">{{ ORDER_STATUS[order.status].label }}</el-tag>
          <span v-if="order.rentStartedAt">{{ formatDateTime(order.rentStartedAt) }} 起计租</span>
          <span v-if="order.cancelledAt">{{ formatDateTime(order.cancelledAt) }} 取消：{{ order.cancelReason }}</span>
        </div>

        <h3 class="title">付款</h3>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="租金合计">{{ formatMoney(order.payment.amountMinor) }}</el-descriptions-item>
          <el-descriptions-item label="付款时间">
            {{ order.payment.paidAt ? formatDateTime(order.payment.paidAt) : `未付款（截止 ${formatDateTime(order.payment.payBefore)}）` }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.refund" label="退款" :span="2">
            {{ formatMoney(order.refund.amountMinor) }}，{{ REFUND_STATUS_LABELS[order.refund.status] }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="title">交接与归还</h3>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="交接">
            <div>{{ recordState(order.handoverRecord) }}</div>
            <div v-if="order.handoverRecord" class="muted">
              {{ formatDateTime(order.handoverRecord.occurredAt) }} · <span class="pre">{{ order.handoverRecord.note }}</span>
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="归还">
            <div>{{ recordState(order.returnRecord) }}</div>
            <div v-if="order.returnRecord" class="muted">
              {{ formatDateTime(order.returnRecord.occurredAt) }} · <span class="pre">{{ order.returnRecord.note }}</span>
            </div>
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="title">合同</h3>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="设备">{{ order.contract.productName }} · {{ order.contract.skuName }}</el-descriptions-item>
          <el-descriptions-item label="承租方">{{ lesseeLabel(order.contract.lessee) }}</el-descriptions-item>
          <el-descriptions-item label="租期">
            {{ order.contract.startDate }} 至 {{ order.contract.endDate }}，{{ order.contract.days }} 天，{{ order.contract.quantity }} 台
          </el-descriptions-item>
          <el-descriptions-item label="租金">
            {{ formatMoney(order.contract.dailyRentMinor) }}/天，共 {{ formatMoney(order.contract.rentMinor) }}
          </el-descriptions-item>
          <el-descriptions-item label="其他费用" :span="2">
            <div v-if="order.contract.fees.length === 0">无</div>
            <div v-for="fee in order.contract.fees" :key="fee.name">
              {{ fee.name }} {{ formatMoney(fee.amountMinor) }}<span v-if="fee.note" class="muted">（{{ fee.note }}）</span>
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="交接方式">{{ HANDOVER_MODE_LABELS[order.contract.handoverMode] }}</el-descriptions-item>
          <el-descriptions-item label="归还方式">{{ RETURN_MODE_LABELS[order.contract.returnMode] }}</el-descriptions-item>
          <el-descriptions-item label="使用地点" :span="2">
            {{ placeLabel(order.contract.place) }}（{{ order.contract.place.recipientName }} {{ order.contract.place.phone }}）
          </el-descriptions-item>
          <el-descriptions-item label="合同条款" :span="2"><span class="pre">{{ order.contract.terms }}</span></el-descriptions-item>
        </el-descriptions>
      </template>
    </div>
  </PageSection>

  <RentalRecordDialog :api="api" :order="recording" :kind="recordKind" @changed="recorded" @close="recording = null" />
  <ReasonDialog
    :open="cancelling"
    title="取消订单并退款"
    hint="交接之前才能取消。写一句原因，客户在 App 里能看到；租金整单原路退回。"
    placeholder="比如：设备临时故障，无法按期交付"
    confirm-text="取消并退款"
    :busy="busy"
    @submit="cancel"
    @close="cancelling = false"
  />
</template>

<style scoped>
.status {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  color: var(--admin-text-secondary);
}

.title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.pre {
  white-space: pre-wrap;
}
</style>
