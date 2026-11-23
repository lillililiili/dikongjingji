<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'
import { extrasRows, lineQuantityLabel, type QuoteLine } from '@/features/demand-quotes/quote-lines'

import CloseOrderDialog from './CloseOrderDialog.vue'
import DeliverDialog from './DeliverDialog.vue'
import DeliverySection from './DeliverySection.vue'
import PlanDialog from './PlanDialog.vue'
import {
  createServiceOrderApi,
  type DeliveryRequest,
  type PlanRequest,
  type ServiceOrder,
  type TerminateRequest,
} from './service-order-api'
import {
  closedByLabel,
  customerLabel,
  eventLabel,
  isStale,
  orderButtons,
  paidMinor,
  REFUND_KIND_LABELS,
  REFUND_STATUS_LABELS,
  STATUS,
  TYPE_LABELS,
} from './service-order-rules'

const route = useRoute()
const router = useRouter()
const api = createServiceOrderApi(http)
const order = shallowRef<ServiceOrder | null>(null)
const loading = ref(false)
const error = ref('')
/** 写操作回 412：订单在此期间变了，页面顶上提示刷新。 */
const stale = ref(false)
const busy = ref(false)
const planning = shallowRef<ServiceOrder | null>(null)
const delivering = shallowRef<ServiceOrder | null>(null)
const closing = shallowRef<ServiceOrder | null>(null)
const closeMode = ref<'cancel' | 'terminate'>('cancel')

const orderId = computed(() => String(route.params.orderId ?? ''))
const buttons = computed(() => (order.value ? orderButtons(order.value) : []))
const extras = computed(() => (order.value ? extrasRows(order.value.quote) : []))
const events = computed(() => [...(order.value?.events ?? [])]
  .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt)))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    order.value = await api.detail(orderId.value)
    stale.value = false
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

/** 跑一个带 If-Match 的写操作：成功换成新的订单；412 时标记“要刷新”。成功返回 true。 */
async function run(action: (current: ServiceOrder) => Promise<ServiceOrder>, done: string): Promise<boolean> {
  const current = order.value
  if (!current || busy.value) return false
  busy.value = true
  try {
    order.value = await action(current)
    stale.value = false
    ElMessage.success(done)
    return true
  } catch (failure) {
    if (isStale(failure)) stale.value = true
    showError(failure)
    return false
  } finally {
    busy.value = false
  }
}

async function submitPlan(request: PlanRequest): Promise<void> {
  const revising = order.value?.plan !== null
  const confirmed = await confirmAction({
    title: revising ? '确认改方案' : '确认出方案',
    message: revising
      ? '保存后方案版本加 1，客户要确认新的一版才能付款。'
      : '方案金额就是选定的报价。出方案后客户在 App 里确认方案、再付款。',
    confirmText: revising ? '保存新一版' : '出方案',
  })
  if (!confirmed) return
  if (await run((current) => api.issuePlan(current, request), revising ? '已改方案，等客户确认新的一版' : '已出方案，等客户确认')) {
    planning.value = null
  }
}

async function start(): Promise<void> {
  const confirmed = await confirmAction({
    title: '确认标记开工',
    message: '开工之后客户不能在线取消；要终止只能和客户协商后在这里“协商终止”。',
    confirmText: '标记开工',
  })
  if (!confirmed) return
  await run((current) => api.start(current), '已标记开工')
}

async function submitDelivery(request: DeliveryRequest): Promise<void> {
  const confirmed = await confirmAction({
    title: '确认提交交付',
    message: `交付 ${request.fileIds.length} 个文件。提交后等客户验收；客户可以退回整改。`,
    confirmText: '提交交付',
  })
  if (!confirmed) return
  if (await run((current) => api.deliver(current, request), '已提交交付，等客户验收')) delivering.value = null
}

function openClose(mode: 'cancel' | 'terminate'): void {
  closeMode.value = mode
  closing.value = order.value
}

async function cancel(reason: string): Promise<void> {
  const current = order.value
  if (!current) return
  const paid = paidMinor(current)
  const confirmed = await confirmAction({
    title: '确认取消订单',
    message: paid > 0
      ? `客户已付的 ${formatMoney(paid)} 会整笔原路退回，订单不能恢复。`
      : '客户还没付款，不涉及退款。订单不能恢复。',
    confirmText: '取消订单',
    danger: true,
  })
  if (!confirmed) return
  if (await run((latest) => api.cancel(latest, reason), paid > 0 ? '订单已取消，已提交原路退款' : '订单已取消')) {
    closing.value = null
  }
}

async function terminate(request: TerminateRequest): Promise<void> {
  const confirmed = await confirmAction({
    title: '确认协商终止',
    message: request.refundAmountMinor > 0
      ? `订单终止，退给客户 ${formatMoney(request.refundAmountMinor)}（原路退回）。平台只协助沟通、不裁决；终止后不能恢复。`
      : '订单终止，不退款。平台只协助沟通、不裁决；终止后不能恢复。',
    confirmText: '终止订单',
    danger: true,
  })
  if (!confirmed) return
  if (await run((current) => api.terminate(current, request), '订单已终止')) closing.value = null
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="order ? `服务订单 ${order.orderNo}` : '服务订单'"
    :description="order ? `${TYPE_LABELS[order.type]} · ${order.title} · 生成于 ${formatDateTime(order.createdAt)}` : undefined"
  >
    <template #actions>
      <el-button @click="router.push({ name: 'workbench-service-orders' })">返回列表</el-button>
      <el-button :loading="loading" @click="load">刷新</el-button>
      <template v-if="order">
        <el-button v-if="buttons.includes('cancel')" type="danger" plain :disabled="busy" @click="openClose('cancel')">
          取消订单
        </el-button>
        <el-button v-if="buttons.includes('terminate')" type="danger" plain :disabled="busy"
                   @click="openClose('terminate')">
          协商终止
        </el-button>
        <el-button v-if="buttons.includes('issuePlan')" type="primary" :disabled="busy" @click="planning = order">
          出方案
        </el-button>
        <el-button v-if="buttons.includes('revisePlan')" type="primary" plain :disabled="busy" @click="planning = order">
          改方案
        </el-button>
        <el-button v-if="buttons.includes('start')" type="primary" :loading="busy" @click="start">标记开工</el-button>
        <el-button v-if="buttons.includes('deliver')" type="primary" :disabled="busy" @click="delivering = order">
          提交交付
        </el-button>
      </template>
    </template>

    <div v-loading="loading">
      <el-result v-if="error && !order" icon="error" :title="error">
        <template #extra><el-button type="primary" @click="load">重新加载</el-button></template>
      </el-result>

      <template v-if="order">
        <el-alert v-if="stale" class="stale" type="warning" :closable="false" show-icon
                  title="订单刚有变化（比如客户刚确认、付款或验收），请刷新后再操作。">
          <el-button size="small" type="primary" @click="load">刷新</el-button>
        </el-alert>

        <div class="status">
          <el-tag :type="STATUS[order.status].tag" size="large">{{ STATUS[order.status].label }}</el-tag>
          <span v-if="order.startedAt">{{ formatDateTime(order.startedAt) }} 开工</span>
          <span v-if="order.completedAt">{{ formatDateTime(order.completedAt) }} 完成</span>
          <span v-if="order.closedAt">
            {{ formatDateTime(order.closedAt) }} 由{{ closedByLabel(order) }}{{ order.status === 'TERMINATED' ? '终止' : '取消' }}：{{ order.closeReason || '—' }}
          </span>
        </div>

        <h3 class="title">需求</h3>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="类型">{{ TYPE_LABELS[order.type] }} · {{ order.title }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ customerLabel(order.customer) }}</el-descriptions-item>
          <el-descriptions-item label="地点" :span="2">{{ order.cityName }}{{ order.address }}</el-descriptions-item>
          <el-descriptions-item label="范围" :span="2"><span class="pre">{{ order.scope }}</span></el-descriptions-item>
          <el-descriptions-item label="期望完成" :span="2">{{ order.dueDate ?? '时间可协商' }}</el-descriptions-item>
        </el-descriptions>

        <h3 class="title">选定的报价（第 {{ order.quote.quoteVersion }} 版）</h3>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="合同金额"><strong>{{ formatMoney(order.amountMinor) }}</strong></el-descriptions-item>
          <el-descriptions-item label="工期">{{ order.quote.durationDays }} 天</el-descriptions-item>
          <el-descriptions-item label="做法">{{ order.quote.method }}</el-descriptions-item>
          <el-descriptions-item label="排期">{{ order.quote.schedule }}</el-descriptions-item>
          <el-descriptions-item label="交付物" :span="2">{{ order.quote.deliverables }}</el-descriptions-item>
          <el-descriptions-item v-for="row in extras" :key="row.label" :label="row.label">{{ row.value }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="order.lines" size="small" border class="lines">
          <el-table-column prop="name" label="明细" min-width="160" />
          <el-table-column label="数量 × 单价" min-width="160">
            <template #default="{ row }">{{ lineQuantityLabel(row as QuoteLine) || '—' }}</template>
          </el-table-column>
          <el-table-column label="金额" width="140">
            <template #default="{ row }">{{ formatMoney(row.amountMinor) }}</template>
          </el-table-column>
        </el-table>

        <h3 class="title">方案</h3>
        <el-empty v-if="!order.plan" description="还没出方案" :image-size="60" />
        <el-descriptions v-else :column="2" border>
          <el-descriptions-item label="版本">
            v{{ order.plan.version }}
            <el-tag v-if="order.plan.confirmedAt" type="success" size="small">客户已确认</el-tag>
            <el-tag v-else type="warning" size="small">等客户确认</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="方案金额">{{ formatMoney(order.plan.amountMinor) }}</el-descriptions-item>
          <el-descriptions-item label="服务安排" :span="2">{{ order.plan.schedule }}</el-descriptions-item>
          <el-descriptions-item label="交付约定" :span="2">
            <ol class="terms"><li v-for="(term, index) in order.plan.terms" :key="index">{{ term }}</li></ol>
          </el-descriptions-item>
          <el-descriptions-item label="方案说明" :span="2"><span class="pre">{{ order.plan.note || '—' }}</span></el-descriptions-item>
          <el-descriptions-item label="出具时间">{{ formatDateTime(order.plan.issuedAt) }}</el-descriptions-item>
          <el-descriptions-item label="确认">
            {{ order.plan.confirmedAt ? `${order.plan.confirmedBy ?? '客户'} · ${formatDateTime(order.plan.confirmedAt)}` : '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="title">付款</h3>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="应付">{{ formatMoney(order.payment.amountMinor) }}（一次付清）</el-descriptions-item>
          <el-descriptions-item label="在线收款">
            <span v-if="order.payment.collectable">能（本机构登记了有效的收款账户，钱进本机构的子商户号）</span>
            <span v-else class="danger">不能：本机构还没有有效的收款账户，客户付不了款，请联系平台运营登记</span>
          </el-descriptions-item>
          <el-descriptions-item label="付款时间">
            <template v-if="order.payment.paidAt">{{ formatDateTime(order.payment.paidAt) }}</template>
            <template v-else-if="order.payment.pendingPayBefore">
              未付款（客户正在付，{{ formatDateTime(order.payment.pendingPayBefore) }} 前有效）
            </template>
            <template v-else>未付款</template>
          </el-descriptions-item>
          <el-descriptions-item label="已退">{{ formatMoney(order.payment.refundedMinor) }}</el-descriptions-item>
        </el-descriptions>
        <el-table v-if="order.payment.refunds.length > 0" :data="order.payment.refunds" row-key="refundId" size="small"
                  border class="lines">
          <el-table-column label="退款" width="190">
            <template #default="{ row }">{{ REFUND_KIND_LABELS[row.kind as keyof typeof REFUND_KIND_LABELS] }}</template>
          </el-table-column>
          <el-table-column label="金额" width="130">
            <template #default="{ row }">{{ formatMoney(row.amountMinor) }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="200" />
          <el-table-column label="状态" width="160">
            <template #default="{ row }">{{ REFUND_STATUS_LABELS[row.status as keyof typeof REFUND_STATUS_LABELS] }}</template>
          </el-table-column>
          <el-table-column label="发起时间" width="150">
            <template #default="{ row }">{{ formatDateTime(row.requestedAt) }}</template>
          </el-table-column>
        </el-table>

        <h3 class="title">交付</h3>
        <DeliverySection :order="order" :api="api" @reload="load" />

        <h3 class="title">订单记录</h3>
        <el-timeline class="events">
          <el-timeline-item v-for="(event, index) in events" :key="`${event.type}-${event.occurredAt}-${index}`"
                            :timestamp="formatDateTime(event.occurredAt)">
            {{ eventLabel(event) }}
          </el-timeline-item>
        </el-timeline>
      </template>
    </div>
  </PageSection>

  <PlanDialog :order="planning" :busy="busy" @submit="submitPlan" @close="planning = null" />
  <DeliverDialog :order="delivering" :busy="busy" @submit="submitDelivery" @close="delivering = null" />
  <CloseOrderDialog :order="closing" :mode="closeMode" :busy="busy" @cancel="cancel" @terminate="terminate"
                    @close="closing = null" />
</template>

<style scoped>
.stale {
  margin-bottom: 12px;
}

.status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  color: var(--admin-text-secondary);
}

.title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.lines {
  margin-top: 10px;
}

.terms {
  margin: 0;
  padding-left: 18px;
}

.danger {
  color: var(--el-color-danger);
}

.events {
  padding-left: 4px;
}

.pre {
  white-space: pre-wrap;
}
</style>
