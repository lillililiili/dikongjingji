<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import ArrangementDialog from './ArrangementDialog.vue'
import DeliveryPublishDialog from './DeliveryPublishDialog.vue'
import {
  canArrange,
  canPublishPlan,
  visitActions,
  VISIT_ACTION_LABELS,
  type VisitAction,
} from './inspection-rules'
import {
  ASSIGNMENT_TYPE_LABELS,
  createInspectionWorkbenchApi,
  ORDER_STATUS_LABELS,
  PAYMENT_NODE_LABELS,
  PROGRESS_LABELS,
  STAGE_LABELS,
  TIME_SLOT_LABELS,
  VISIT_STATUS_LABELS,
  type ProgressEventCode,
  type Reservation,
  type ReservationSummary,
  type ServiceOrder,
  type ServiceVisit,
} from './inspection-workbench-api'
import PlanPublishDialog from './PlanPublishDialog.vue'
import VisitActionDialog from './VisitActionDialog.vue'

const route = useRoute()
const router = useRouter()
const api = createInspectionWorkbenchApi(http)

const reservationId = computed(() => String(route.params.reservationId ?? ''))
const reservation = shallowRef<Reservation | null>(null)
const summary = shallowRef<ReservationSummary | null>(null)
const order = shallowRef<ServiceOrder | null>(null)
const loading = ref(false)
const error = ref('')

const planDialog = ref(false)
const arrangeDialog = ref(false)
const visitDialog = shallowRef<{ visit: ServiceVisit, action: 'SCHEDULE' | ProgressEventCode } | null>(null)
const deliveryDialog = shallowRef<ServiceVisit | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [loaded, found] = await Promise.all([
      api.reservation(reservationId.value),
      api.summary(reservationId.value),
    ])
    reservation.value = loaded
    summary.value = found
    order.value = found.serviceOrderId ? await api.serviceOrder(found.serviceOrderId) : null
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

const stage = computed(() => summary.value?.stage ?? null)
const planAllowed = computed(() => !!reservation.value && !order.value
  && canPublishPlan(stage.value, reservation.value.status))
const republish = computed(() => !!reservation.value?.currentPlanId)

function act(visit: ServiceVisit, action: VisitAction): void {
  if (action === 'DELIVER') {
    deliveryDialog.value = visit
  } else {
    visitDialog.value = { visit, action }
  }
}

function lastEvent(visit: ServiceVisit): string {
  const event = visit.progress[visit.progress.length - 1]
  if (!event) return '—'
  return `${PROGRESS_LABELS[event.eventCode] ?? '进度'}：${event.message}（${formatDateTime(event.occurredAt)}）`
}

async function done(): Promise<void> {
  planDialog.value = false
  arrangeDialog.value = false
  visitDialog.value = null
  deliveryDialog.value = null
  await load()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="reservation ? `巡检预约 ${reservation.reservationNo}` : '巡检预约'"
    :description="reservation?.serviceTitle"
  >
    <template #actions>
      <el-button @click="load">刷新</el-button>
      <el-button @click="router.push({ name: 'workbench-inspection' })">返回列表</el-button>
    </template>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load">重新加载</el-button>
    </el-alert>

    <div v-loading="loading">
      <template v-if="reservation">
        <h2 class="block-title">预约</h2>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="阶段">
            <el-tag effect="plain">
              {{ stage ? STAGE_LABELS[stage] : reservation.status === 'CANCELLED' ? '已取消' : '—' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ formatDateTime(reservation.submittedAt) }}</el-descriptions-item>
          <el-descriptions-item label="巡检地点" :span="2">{{ reservation.location.address }}</el-descriptions-item>
          <el-descriptions-item label="期望时间">
            {{ reservation.preferredServiceDate }} {{ TIME_SLOT_LABELS[reservation.preferredTimeSlotCode] }}
          </el-descriptions-item>
          <el-descriptions-item label="线路长度">
            {{ reservation.lineLengthMeters ? `${reservation.lineLengthMeters} 米` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="巡检项目" :span="2">
            {{ reservation.inspectionItems.map((item) => item.label).join('、') || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="联系人">
            {{ reservation.contact.name }} {{ reservation.contact.mobile }}
          </el-descriptions-item>
          <el-descriptions-item label="客户附件">{{ reservation.attachments.length }} 份</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ reservation.note || '—' }}</el-descriptions-item>
        </el-descriptions>

        <h2 class="block-title">方案</h2>
        <div class="plan">
          <span v-if="reservation.currentPlanId">
            已发布方案，报价合计 <strong>{{ formatMoney(summary?.quoteAmountMinor) }}</strong>
            <template v-if="order">，客户已确认</template>
            <template v-else>，等客户在 App 里确认</template>
          </span>
          <span v-else>还没有发方案。</span>
          <el-button v-if="planAllowed" type="primary" @click="planDialog = true">
            {{ republish ? '再发一版方案' : '出方案' }}
          </el-button>
        </div>
        <p class="muted">方案详情只给客户和平台看；这里只显示报价合计。</p>

        <template v-if="order">
          <h2 class="block-title">服务单 {{ order.serviceOrderNo }}</h2>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="状态">{{ ORDER_STATUS_LABELS[order.status] }}</el-descriptions-item>
            <el-descriptions-item label="客户">
              {{ order.customerParty?.organizationName ?? '' }} {{ order.customerParty?.operatorName ?? '' }}
            </el-descriptions-item>
            <el-descriptions-item label="付款" :span="2">
              <template v-if="order.paymentMode === 'NODES' && order.paymentNodes?.length">
                <div v-for="node in order.paymentNodes" :key="node.nodeNo">
                  {{ PAYMENT_NODE_LABELS[node.nodeType] }}{{ node.visitNo ? `（第 ${node.visitNo} 次）` : '' }}
                  {{ formatMoney(node.amountMinor) }} ·
                  {{ node.paidAt ? `已付 ${formatDateTime(node.paidAt)}` : node.payable ? '待客户付款' : '未到付款时间' }}
                </div>
              </template>
              <span v-else>本单没有在线付款节点</span>
            </el-descriptions-item>
          </el-descriptions>

          <h3 class="sub-title">
            人员和设备
            <el-button v-if="canArrange(order)" size="small" @click="arrangeDialog = true">
              {{ order.assignments.length ? '改安排' : '安排人员和设备' }}
            </el-button>
          </h3>
          <el-table :data="order.assignments" row-key="resourceId" border empty-text="还没有安排">
            <el-table-column label="类型" width="120">
              <template #default="{ row }">{{ ASSIGNMENT_TYPE_LABELS[(row as ServiceOrder['assignments'][number]).type] }}</template>
            </el-table-column>
            <el-table-column label="名称" prop="displayName" />
            <el-table-column label="角色">
              <template #default="{ row }">{{ (row as ServiceOrder['assignments'][number]).roleLabel ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="手机号">
              <template #default="{ row }">{{ (row as ServiceOrder['assignments'][number]).contactMobileMasked ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="持证飞手" width="100">
              <template #default="{ row }">{{ (row as ServiceOrder['assignments'][number]).certifiedPilot ? '是' : '—' }}</template>
            </el-table-column>
          </el-table>

          <h3 class="sub-title">每次巡检</h3>
          <el-table :data="order.visits" row-key="id" border>
            <el-table-column label="第几次" width="80">
              <template #default="{ row }">第 {{ (row as ServiceVisit).visitNo }} 次</template>
            </el-table-column>
            <el-table-column label="计划" width="180">
              <template #default="{ row }">
                <div>{{ (row as ServiceVisit).plannedDate }}</div>
                <div v-if="(row as ServiceVisit).plannedStartAt" class="muted">
                  开始 {{ formatDateTime((row as ServiceVisit).plannedStartAt) }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="170">
              <template #default="{ row }">
                <el-tag :type="(row as ServiceVisit).status === 'ACCEPTED' ? 'success' : 'info'" effect="plain">
                  {{ VISIT_STATUS_LABELS[(row as ServiceVisit).status] }}
                </el-tag>
                <div v-if="(row as ServiceVisit).acceptedAt" class="muted">
                  验收 {{ formatDateTime((row as ServiceVisit).acceptedAt) }}
                </div>
                <div v-else-if="(row as ServiceVisit).deliveredAt" class="muted">
                  交付 {{ formatDateTime((row as ServiceVisit).deliveredAt) }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="最近进度" min-width="220">
              <template #default="{ row }">{{ lastEvent(row as ServiceVisit) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="260">
              <template #default="{ row }">
                <template v-for="state in visitActions(order, row as ServiceVisit)" :key="state.action">
                  <el-tooltip :disabled="state.enabled" :content="state.reason ?? ''" placement="top">
                    <span class="action">
                      <el-button link type="primary" :disabled="!state.enabled"
                                 @click="act(row as ServiceVisit, state.action)">
                        {{ state.action === 'SCHEDULE' && (row as ServiceVisit).status === 'SCHEDULED'
                          ? '改排期' : VISIT_ACTION_LABELS[state.action] }}
                      </el-button>
                    </span>
                  </el-tooltip>
                </template>
                <span v-if="visitActions(order, row as ServiceVisit).length === 0" class="muted">
                  {{ (row as ServiceVisit).status === 'DELIVERED' ? '等客户验收' : '—' }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </template>
    </div>
  </PageSection>

  <PlanPublishDialog v-if="planDialog && reservation" :api="api" :reservation="reservation" :republish="republish"
                     @published="done" @close="planDialog = false" />
  <ArrangementDialog v-if="arrangeDialog && order" :api="api" :order="order" @saved="done"
                     @close="arrangeDialog = false" />
  <VisitActionDialog v-if="visitDialog" :api="api" :visit="visitDialog.visit" :action="visitDialog.action"
                     @saved="done" @close="visitDialog = null" />
  <DeliveryPublishDialog v-if="deliveryDialog" :api="api" :visit="deliveryDialog" @published="done"
                         @close="deliveryDialog = null" />
</template>

<style scoped>
.block-title {
  margin: 20px 0 10px;
  font-size: 16px;
}

.block-title:first-child {
  margin-top: 0;
}

.sub-title {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 16px 0 8px;
  font-size: 14px;
}

.plan {
  display: flex;
  align-items: center;
  gap: 16px;
}

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.action + .action {
  margin-left: 8px;
}
</style>
