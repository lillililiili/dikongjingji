<script setup lang="ts">
import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { computed, ref } from 'vue'
import { onBackPress, onLoad, onShow } from '@dcloudio/uni-app'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { providerCredentialLabel } from '@/shared/ui/qualification'
import { resolveBusinessMediaUrl } from '../../../../../shared/http/business-media'
import {
  createLatestRequestCoordinator,
  getPreviousInspectionPageRoute,
  runLatestRequest,
} from '../../../application/service-flow/inspection-order-navigation'
import type {
  InspectionPlan,
  InspectionServiceOrder,
} from '../../../data/service-flow/inspection-plan-api'
import { inspectionPlanApi } from '../../../data/service-flow/uni-booking-http-client'
import {
  buildInspectionDeliveryUrl,
  buildInspectionOrderCenterUrl,
} from '../../../navigation/inspection-routes'
import { openNodePayment } from '../../../application/service-flow/node-payment'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import { describeDuePayment } from '../../shared/payment-view'
import FulfillmentArrangementCard from '../components/FulfillmentArrangementCard.vue'
import FulfillmentSummaryCard from '../components/FulfillmentSummaryCard.vue'
import VisitTimeline from '../components/VisitTimeline.vue'
import {
  describeArrangement,
  describeFulfillmentProgress,
  describePendingAcceptance,
  toVisitRows,
} from '../fulfillment-view'

interface FulfillmentRouteQuery { serviceOrderId?: string }

const order = ref<InspectionServiceOrder | null>(null)
const plan = ref<InspectionPlan | null>(null)
const isLoading = ref(true)
const loadError = ref('')
const serviceOrderId = ref('')
const latestLoad = createLatestRequestCoordinator()

const pendingAcceptance = computed(() =>
  order.value ? describePendingAcceptance(order.value.visits) : null)
const visitRows = computed(() => order.value
  ? toVisitRows(order.value.visits, plan.value?.deliveryTerms?.reportDueHours ?? null, order.value)
  : [])
// S4-1: the smallest unpaid fee (or the down payment) — only where online payment is open.
const duePayment = computed(() =>
  TRADE_PREVIEW_ENABLED && order.value ? describeDuePayment(order.value) : null)
const paying = ref(false)

async function payDue(): Promise<void> {
  if (!order.value || !duePayment.value || paying.value) return
  paying.value = true
  try {
    await openNodePayment(order.value.id, duePayment.value.nodeNo)
  } finally {
    paying.value = false
  }
}
const methodsText = computed(() =>
  plan.value?.serviceMethods?.map((method) => method.label).join(' + ') ?? '')

onLoad((query: FulfillmentRouteQuery | undefined) => {
  serviceOrderId.value = query?.serviceOrderId?.trim() ?? ''
  if (!serviceOrderId.value) { isLoading.value = false; loadError.value = '未找到服务安排'; return }
  void reload()
})

onBackPress((event) => {
  if (event.from === 'navigateBack') return false
  returnToOrders()
  return true
})

// Coming back from acceptance has to show the new state without a blank reload.
onShow(() => {
  if (order.value) void refresh()
})

async function loadOrderAndPlan(): Promise<[InspectionServiceOrder, InspectionPlan]> {
  const current = await inspectionPlanApi.getServiceOrder(serviceOrderId.value)
  return [current, await inspectionPlanApi.getPlan(current.planId)]
}

async function refresh(): Promise<void> {
  const result = await runLatestRequest(latestLoad, loadOrderAndPlan)
  if (result.status === 'success') {
    [order.value, plan.value] = result.value
    loadError.value = ''
  } else if (result.status === 'error') {
    const { error } = result
    loadError.value = error instanceof Error ? error.message : '服务安排加载失败'
  }
}

async function reload(): Promise<void> {
  if (!serviceOrderId.value) return
  isLoading.value = true
  loadError.value = ''
  await refresh()
  isLoading.value = false
}

function returnToOrders(): void {
  const previousRoute = getPreviousInspectionPageRoute(getCurrentPages())
  if (previousRoute === 'pages/mine/inspection-orders') {
    uni.navigateBack({ delta: 1 })
    return
  }
  uni.redirectTo({ url: buildInspectionOrderCenterUrl('ALL') })
}

function openDelivery(visitId: string): void {
  if (!order.value) return
  uni.navigateTo({ url: buildInspectionDeliveryUrl(order.value.id, visitId) })
}

// 联系负责人 (step G1, preview builds): the platform support with a card about this service order;
// phone numbers are not shown. Public builds keep the notice.
function showCustomerService(): void {
  const current = order.value
  if (!SUPPORT_PREVIEW_ENABLED || !current) {
    uni.showToast({ title: '客服能力由平台统一接入', icon: 'none' })
    return
  }
  openSupportChat({ kind: 'PLATFORM' }, {
    type: 'INSPECTION_ORDER',
    refId: current.id,
    title: current.serviceSnapshot.serviceTitle,
    subtitle: `服务单 ${current.serviceOrderNo}`,
  })
}
</script>

<template>
  <view class="fulfillment">
    <AppPageHeader class="fulfillment__header" title="服务履约" test-id="fulfillment" @back="returnToOrders" />

    <view v-if="isLoading" class="fulfillment__state">正在恢复服务安排…</view>
    <view v-else-if="loadError" class="fulfillment__state">
      <LineIcon name="info" :size="40" color="#b5bac1" />
      <text>{{ loadError }}</text>
      <button class="fulfillment__retry" @click="reload">重新加载</button>
    </view>

    <main v-else-if="order && plan" class="fulfillment__content">
      <FulfillmentSummaryCard
        :cover-url="(resolveBusinessMediaUrl(order.serviceSnapshot.serviceCoverUrl) ?? '')"
        :title="order.serviceSnapshot.serviceTitle"
        :provider-name="order.provider?.name ?? ''"
        :certification-label="providerCredentialLabel(order.provider?.qualificationReviewed, order.provider?.certificationLabel) ?? ''"
        :progress="describeFulfillmentProgress(order)"
      />
      <FulfillmentArrangementCard
        :arrangement="describeArrangement(order.assignments)"
        @contact-lead="showCustomerService"
      />
      <VisitTimeline
        :rows="visitRows"
        :methods-text="methodsText"
        @open-delivery="openDelivery"
      />
      <view v-if="duePayment" class="fulfillment__due" data-testid="fulfillment-due">
        <view class="fulfillment__due-copy">
          <text class="fulfillment__due-title">{{ duePayment.title }}</text>
          <text class="fulfillment__due-note">{{ duePayment.note }}</text>
        </view>
        <button class="fulfillment__due-pay" :disabled="paying" data-testid="fulfillment-pay" @click="payDue">去支付</button>
      </view>
      <view v-if="order.status === 'CANCELLED'" class="fulfillment__due fulfillment__due--cancelled" data-testid="fulfillment-cancelled">
        <view class="fulfillment__due-copy">
          <text class="fulfillment__due-title">服务单已取消</text>
          <text class="fulfillment__due-note">第 1 次巡检开始前取消；付过的首付原路退回</text>
        </view>
      </view>
      <view class="fulfillment__note">
        <LineIcon name="info" :size="14" color="#738096" />
        <text class="fulfillment__note-text">履约状态由服务商按实际作业更新，每次交付后由你验收</text>
      </view>
    </main>

    <view
      v-if="pendingAcceptance && !isLoading && !loadError"
      class="fulfillment__footer"
    >
      <view class="fulfillment__footer-copy">
        <text class="fulfillment__footer-title">{{ pendingAcceptance.title }}</text>
        <text class="fulfillment__footer-text">{{ pendingAcceptance.text }}</text>
      </view>
      <button class="fulfillment__accept" @click="openDelivery(pendingAcceptance.visitId)">去验收</button>
    </view>
  </view>
</template>

<style scoped>
.fulfillment {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #191919;
  background: #f5f7fc;
}

/* S4-1: the fee reminder of board FulfillmentLater. */
.fulfillment__due {
  display: flex;
  padding: 12px 14px;
  align-items: center;
  gap: 12px;
  border-radius: 14px;
  background: #fff0e3;
}

.fulfillment__due--cancelled {
  background: #eef2f8;
}

.fulfillment__due-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.fulfillment__due-title {
  color: #ba470b;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.fulfillment__due--cancelled .fulfillment__due-title {
  color: #202c42;
}

.fulfillment__due-note {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.fulfillment__due-pay {
  height: 36px;
  flex-shrink: 0;
  margin: 0;
  padding: 0 14px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #fff;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
}

.fulfillment__due-pay::after {
  border: 0;
}

.fulfillment__header {
  position: sticky;
  top: 0;
  z-index: 2;
}

.fulfillment__retry::after,
.fulfillment__accept::after {
  border: 0;
}



.fulfillment__state {
  display: flex;
  min-height: 360px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  color: #7e858e;
  font-size: 14px;
}

.fulfillment__retry {
  height: 44px;
  margin: 0;
  padding: 0 24px;
  border: 0;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 14px;
  line-height: 44px;
}

.fulfillment__content {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
  box-sizing: border-box;
}

.fulfillment__note {
  display: flex;
  padding: 0 2px;
  gap: 6px;
}

.fulfillment__note :deep(.uni-icons) {
  flex-shrink: 0;
  margin-top: 1.5px;
}

.fulfillment__note-text {
  min-width: 0;
  color: #666b73;
  font-size: 12px;
  line-height: 17px;
}

.fulfillment__footer {
  position: sticky;
  bottom: 0;
  z-index: 2;
  display: flex;
  padding: 10px 16px max(24px, env(safe-area-inset-bottom));
  align-items: center;
  gap: 12px;
  border-top: 1px solid #ebedf0;
  background: #fff;
}

.fulfillment__footer-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.fulfillment__footer-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.fulfillment__footer-text {
  color: #8a8f97;
  font-size: 12px;
  line-height: 17px;
}

.fulfillment__accept {
  height: 46px;
  flex-shrink: 0;
  margin: 0;
  padding: 0 28px;
  border: 0;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  white-space: nowrap;
}
</style>
