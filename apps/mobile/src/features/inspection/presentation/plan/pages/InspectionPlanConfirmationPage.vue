<script lang="ts">
export interface PlanCancellationReservation {
  id: string
  status: 'SUBMITTED' | 'PLAN_PUBLISHED' | 'CANCELLED'
}

export type PlanCancellationResult<Reservation extends PlanCancellationReservation> =
  | { kind: 'dismissed' }
  | { kind: 'cancelled'; reservation: Reservation }
  | { kind: 'refresh-required' }
  | { kind: 'failed'; message: string }

export interface PlanCancellationControllerOptions<Reservation extends PlanCancellationReservation> {
  getReservation(): Reservation | null
  setReservation(reservation: Reservation): void
  isPlanConfirmed(): boolean
  isCancelling(): boolean
  setCancelling(value: boolean): void
  requestCancellation(reservation: Reservation): Promise<PlanCancellationResult<Reservation>>
  refreshReservation(reservationId: string): Promise<Reservation>
  reloadPlan(): Promise<boolean>
  lockActions(message: string): void
  redirectToCancelledDetail(reservationId: string): void
  showToast(message: string): void
}

export interface PlanPageLoaderOptions<
  Plan extends { reservationId: string },
  Reservation,
  Version,
> {
  loadPlan(planId: string): Promise<Plan>
  loadReservation(reservationId: string): Promise<Reservation>
  loadVersions(reservationId: string): Promise<Version[]>
  setPlan(plan: Plan): void
  setReservation(reservation: Reservation): void
  setVersions(versions: Version[]): void
  setLoading(value: boolean): void
  setLoadError(message: string): void
  getLoadError(error: unknown): string
}

export function canConfirmInspectionPlan(
  planConfirmable: boolean,
  reservationStatus: PlanCancellationReservation['status'] | undefined,
  isCancelling: boolean,
  actionsReady = true,
): boolean {
  return planConfirmable
    && reservationStatus === 'PLAN_PUBLISHED'
    && !isCancelling
    && actionsReady
}

export function canCancelInspectionPlan(
  reservationStatus: PlanCancellationReservation['status'] | undefined,
  actionsReady: boolean,
): boolean {
  return reservationStatus === 'PLAN_PUBLISHED' && actionsReady
}

export function createPlanPageLoader<
  Plan extends { reservationId: string },
  Reservation,
  Version,
>(options: PlanPageLoaderOptions<Plan, Reservation, Version>) {
  let latestRequestId = 0

  async function loadPlan(planId: string): Promise<boolean> {
    const requestId = ++latestRequestId
    options.setLoading(true)
    try {
      const currentPlan = await options.loadPlan(planId)
      // The page works without the comparison, so a failed list does not block it.
      const [currentReservation, versions] = await Promise.all([
        options.loadReservation(currentPlan.reservationId),
        options.loadVersions(currentPlan.reservationId).catch(() => []),
      ])
      if (requestId !== latestRequestId) return false
      options.setPlan(currentPlan)
      options.setReservation(currentReservation)
      options.setVersions(versions)
      options.setLoadError('')
      return true
    } catch (error) {
      if (requestId !== latestRequestId) return false
      options.setLoadError(options.getLoadError(error))
      return false
    } finally {
      if (requestId === latestRequestId) options.setLoading(false)
    }
  }

  return { loadPlan }
}

export function createPlanCancellationController<Reservation extends PlanCancellationReservation>(
  options: PlanCancellationControllerOptions<Reservation>,
) {
  async function cancelOrder(): Promise<void> {
    const currentReservation = options.getReservation()
    if (!currentReservation
      || currentReservation.status !== 'PLAN_PUBLISHED'
      || options.isPlanConfirmed()
      || options.isCancelling()) return

    options.setCancelling(true)
    try {
      const result = await options.requestCancellation(currentReservation)
      if (result.kind === 'cancelled') {
        if (result.reservation.status !== 'CANCELLED') {
          options.showToast('订单状态异常，请刷新后重试')
          return
        }
        options.setReservation(result.reservation)
        options.redirectToCancelledDetail(result.reservation.id)
        return
      }
      if (result.kind === 'refresh-required') {
        try {
          const latest = await options.refreshReservation(currentReservation.id)
          if (latest.status === 'CANCELLED') {
            options.setReservation(latest)
            options.redirectToCancelledDetail(latest.id)
            return
          }
          if (!await options.reloadPlan()) {
            options.lockActions('状态刷新失败，请重试')
            return
          }
          options.showToast('订单状态已更新')
        } catch {
          options.lockActions('状态刷新失败，请重试')
        }
        return
      }
      if (result.kind === 'failed') options.showToast(result.message)
    } catch {
      options.showToast('取消失败，请检查网络后重试')
    } finally {
      options.setCancelling(false)
    }
  }

  return { cancelOrder }
}
</script>

<script setup lang="ts">
import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { computed, ref } from 'vue'
import { onBackPress, onLoad, onShow } from '@dcloudio/uni-app'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import { openNodePayment } from '../../../application/service-flow/node-payment'
import { findDueNode, isDownPayment } from '../../shared/payment-view'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { providerCredentialLabel } from '@/shared/ui/qualification'
import { inspectionServiceImage } from '../../../data/inspection-service-images'
import { createBookingUuid } from '../../../application/service-flow/booking-crypto'
import {
  canCancelInspectionOrder,
  requestInspectionOrderCancellation,
} from '../../../application/service-flow/inspection-order-cancellation'
import { getPreviousInspectionPageRoute } from '../../../application/service-flow/inspection-order-navigation'
import type { BookingReservation } from '../../../data/service-flow/inspection-booking-api'
import type {
  InspectionPlan,
  InspectionPlanVersionSummary,
  InspectionServiceOrder,
} from '../../../data/service-flow/inspection-plan-api'
import {
  inspectionBookingApi,
  inspectionPlanApi,
} from '../../../data/service-flow/uni-booking-http-client'
import {
  formatPlanPrice,
  isPlanConfirmable,
} from '../../../domain/service-flow/plan'
import {
  buildInspectionBookingSubmittedUrl,
  buildInspectionFulfillmentUrl,
  buildInspectionOrderCenterUrl,
} from '../../../navigation/inspection-routes'
import { confirmInspectionOrderCancellation } from '../../shared/inspection-cancellation-ui'
import {
  formatMonthDay,
  formatMonthDayTime,
} from '../../shared/service-flow-format'
import PlanArrangementCard from '../components/PlanArrangementCard.vue'
import PlanPaymentCard from '../components/PlanPaymentCard.vue'
import PlanQuoteCard from '../components/PlanQuoteCard.vue'
import PlanSummaryCard from '../components/PlanSummaryCard.vue'
import PlanTermsCard from '../components/PlanTermsCard.vue'
import PlanVersionCard from '../components/PlanVersionCard.vue'
import {
  describeConfirmation,
  describeCustomerParty,
  describeDeliveryTerms,
  describePaymentNodes,
  describePlanChange,
  describePlanStatus,
  describePreviousVersion,
  describeServiceArrangement,
  findPreviousPlanVersion,
  toQuoteRows,
} from '../plan-confirmation-view'

interface PlanRouteQuery { planId?: string }

const routePlanId = ref('')
const plan = ref<InspectionPlan | null>(null)
const reservation = ref<BookingReservation | null>(null)
const versions = ref<InspectionPlanVersionSummary[]>([])
const isLoading = ref(true)
const isSubmitting = ref(false)
const isCancelling = ref(false)
const loadError = ref('')
const actionError = ref('')
const agreementAccepted = ref(false)
const confirmationKey = ref('')
// S4-1: the confirmed order, read for its payment nodes (what is paid, whether the down payment is due).
const serviceOrder = ref<InspectionServiceOrder | null>(null)
const paying = ref(false)

const actionsReady = computed(() => !isLoading.value && !loadError.value)
const isExpired = computed(() =>
  plan.value?.status === 'WITHDRAWN'
  || plan.value?.status === 'EXPIRED'
  || (plan.value?.status === 'PUBLISHED' && !isPlanConfirmable(plan.value)),
)
const confirmable = computed(() => canConfirmInspectionPlan(
  plan.value !== null && isPlanConfirmable(plan.value),
  reservation.value?.status,
  isCancelling.value,
  actionsReady.value,
))
const canCancel = computed(() =>
  canCancelInspectionPlan(reservation.value?.status, actionsReady.value)
  && reservation.value !== null
  && canCancelInspectionOrder(reservation.value.status),
)
const isConfirmed = computed(() =>
  plan.value?.status === 'CONFIRMED' && Boolean(plan.value.serviceOrderId),
)
const isCancelled = computed(() => reservation.value?.status === 'CANCELLED')
const isPending = computed(() =>
  !isConfirmed.value && !isCancelled.value && !isExpired.value,
)

const previousVersion = computed(() => plan.value
  ? findPreviousPlanVersion(plan.value, versions.value)
  : null)
const statusChip = computed(() => describePlanStatus({
  isConfirmed: isConfirmed.value,
  isExpired: isExpired.value,
  isCancelled: isCancelled.value,
}))
const paymentRows = computed(() => plan.value
  ? describePaymentNodes(plan.value, isConfirmed.value, {
      isExpired: isExpired.value,
      isCancelled: isCancelled.value || serviceOrder.value?.status === 'CANCELLED',
      order: serviceOrder.value,
    })
  : [])
// 支付首付 ¥5,580 (board PlanConfirm) once the plan is confirmed and the down payment is still due.
const downPaymentDue = computed(() => {
  if (!TRADE_PREVIEW_ENABLED || !isConfirmed.value || !serviceOrder.value) return null
  const due = findDueNode(serviceOrder.value)
  return due && isDownPayment(due) ? due : null
})
const confirmation = computed(() => plan.value ? describeConfirmation(plan.value) : null)

const planLoader = createPlanPageLoader<
  InspectionPlan,
  BookingReservation,
  InspectionPlanVersionSummary
>({
  loadPlan: inspectionPlanApi.getPlan,
  loadReservation: inspectionBookingApi.getReservation,
  loadVersions: inspectionPlanApi.listPlanVersions,
  setPlan: (value) => {
    // A tick covers the exact plan that was checked, however the page came to change.
    if (plan.value && (plan.value.id !== value.id || plan.value.version !== value.version)) {
      agreementAccepted.value = false
      confirmationKey.value = ''
    }
    plan.value = value
  },
  setReservation: (value) => { reservation.value = value },
  setVersions: (value) => { versions.value = value },
  setLoading: (value) => { isLoading.value = value },
  setLoadError: (value) => { loadError.value = value },
  getLoadError: (error) => error instanceof Error ? error.message : '方案加载失败',
})

onLoad((query: PlanRouteQuery | undefined) => {
  routePlanId.value = query?.planId?.trim() ?? ''
  if (!routePlanId.value) {
    isLoading.value = false
    loadError.value = '未找到服务方案'
    return
  }
  void loadPlan(routePlanId.value)
})

onBackPress((event) => {
  if (event.from === 'navigateBack') return false
  goBack()
  return true
})

async function loadServiceOrder(): Promise<void> {
  const orderId = plan.value?.status === 'CONFIRMED' ? plan.value.serviceOrderId : null
  if (!orderId || !plan.value?.paymentNodes?.length) {
    serviceOrder.value = null
    return
  }
  try {
    serviceOrder.value = await inspectionPlanApi.getServiceOrder(orderId)
  } catch {
    // The page still works from the plan alone; the button waits for the next visit.
    serviceOrder.value = null
  }
}

// Back from the cashier: the down payment may have been paid meanwhile.
onShow(() => {
  if (plan.value && isConfirmed.value) void loadServiceOrder()
})

async function payDownPayment(): Promise<void> {
  if (!serviceOrder.value || !downPaymentDue.value || paying.value) return
  paying.value = true
  try {
    await openNodePayment(serviceOrder.value.id, downPaymentDue.value.nodeNo)
  } finally {
    paying.value = false
  }
}

async function loadPlan(planId: string): Promise<boolean> {
  const loaded = await loadPlanOnly(planId)
  if (loaded) await loadServiceOrder()
  return loaded
}

async function loadPlanOnly(planId: string): Promise<boolean> {
  const loaded = await planLoader.loadPlan(planId)
  const currentPlanId = reservation.value?.currentPlanId
  // A replaced version has nothing left to act on, so the page moves to the one in force.
  if (loaded && plan.value?.status === 'WITHDRAWN'
    && currentPlanId && currentPlanId !== planId) {
    return planLoader.loadPlan(currentPlanId)
  }
  return loaded
}

async function confirmPlan(): Promise<void> {
  if (!plan.value) return
  if (isCancelling.value) return
  if (!agreementAccepted.value) {
    uni.showToast({ title: '请先核对服务范围、报价与交付约定', icon: 'none' })
    return
  }
  if (!confirmable.value || isSubmitting.value) return

  isSubmitting.value = true
  actionError.value = ''
  confirmationKey.value ||= createBookingUuid()
  try {
    const order = await inspectionPlanApi.confirmPlan(
      plan.value.id,
      plan.value.version,
      confirmationKey.value,
    )
    // S4-1: with a down payment due the page stays, confirmed, and offers 支付首付 (board PlanConfirm).
    if (order.status === 'AWAITING_PAYMENT' && TRADE_PREVIEW_ENABLED) {
      await loadPlan(plan.value.id)
      return
    }
    openFulfillment(order.id)
  } catch (error) {
    const apiError = error as Error & { code?: string }
    actionError.value = apiError.message || '方案确认失败，请重试'
    if (apiError.code === 'INS_VERSION_CONFLICT' || apiError.code === 'INS_PLAN_NOT_PUBLISHED') {
      await loadPlan(plan.value.id)
      actionError.value = '方案已更新，请重新核对'
    } else if (apiError.code === 'INS_PLAN_EXPIRED' || apiError.code === 'INS_PLAN_ALREADY_CONFIRMED') {
      await loadPlan(plan.value.id)
      actionError.value = ''
    }
  } finally {
    isSubmitting.value = false
  }
}

const cancellationController = createPlanCancellationController<BookingReservation>({
  getReservation: () => reservation.value,
  setReservation: (value) => { reservation.value = value },
  isPlanConfirmed: () => isConfirmed.value || isSubmitting.value,
  isCancelling: () => isCancelling.value,
  setCancelling: (value) => { isCancelling.value = value },
  requestCancellation: (currentReservation) => requestInspectionOrderCancellation({
    reservation: currentReservation,
    cancelReservation: inspectionBookingApi.cancelReservation,
    confirm: confirmInspectionOrderCancellation,
    idempotencyKey: createBookingUuid,
  }),
  refreshReservation: inspectionBookingApi.getReservation,
  reloadPlan: () => plan.value ? loadPlan(plan.value.id) : Promise.resolve(false),
  lockActions: (message) => { loadError.value = message },
  redirectToCancelledDetail: (reservationId) => {
    uni.redirectTo({ url: buildInspectionBookingSubmittedUrl(reservationId) })
  },
  showToast: (title) => uni.showToast({ title, icon: 'none' }),
})

async function cancelOrder(): Promise<void> {
  await cancellationController.cancelOrder()
}

function openFulfillment(serviceOrderId: string): void {
  uni.redirectTo({ url: buildInspectionFulfillmentUrl(serviceOrderId) })
}

function openConfirmedFulfillment(): void {
  if (plan.value?.serviceOrderId) openFulfillment(plan.value.serviceOrderId)
}

function retryLoad(): void {
  if (routePlanId.value) void loadPlan(plan.value?.id ?? routePlanId.value)
}

function goBack(): void {
  const previousRoute = getPreviousInspectionPageRoute(getCurrentPages())
  if (previousRoute === 'pages/mine/inspection-orders'
    || previousRoute === 'pages/service/inspection/booking-submitted') {
    uni.navigateBack({ delta: 1 })
    return
  }
  uni.redirectTo({ url: buildInspectionOrderCenterUrl('ALL') })
}

function showCustomerService(title: string): void {
  // 联系服务商 (step G1, preview builds): the provider who wrote this plan, with a card about the
  // service. 申请更新方案 keeps its explanation.
  const current = plan.value
  if (SUPPORT_PREVIEW_ENABLED && title === '联系服务商' && current?.providerId) {
    const booked = reservation.value
    openSupportChat(
      { kind: 'PROVIDER', organizationId: current.providerId },
      booked ? { type: 'INSPECTION_SERVICE', refId: booked.serviceId, title: booked.serviceTitle, subtitle: `方案 ${current.planNo}` } : null,
    )
    return
  }
  uni.showModal({
    title,
    content: '如需调整服务日期、巡检范围或报价，请通过平台客服登记，方案调整后将生成新版本。',
    showCancel: false,
    confirmText: '我知道了',
  })
}
</script>

<template>
  <view class="plan-confirmation">
    <AppPageHeader class="plan-confirmation__header" title="方案确认" test-id="plan-confirmation" @back="goBack" />

    <view v-if="isLoading" class="plan-confirmation__state">
      正在恢复服务方案…
    </view>
    <view v-else-if="loadError" class="plan-confirmation__state">
      <LineIcon name="info" :size="40" color="#b5bac1" />
      <text>{{ loadError }}</text>
      <button v-if="routePlanId" class="plan-confirmation__retry" @click="retryLoad">重新加载</button>
    </view>

    <main
      v-else-if="plan && reservation"
      class="plan-confirmation__content"
    >
      <PlanSummaryCard
        :cover-url="inspectionServiceImage({ coverUrl: reservation.serviceCoverUrl, title: reservation.serviceTitle })"
        :title="reservation.serviceTitle"
        :provider-name="plan.provider?.name ?? ''"
        :certification-label="providerCredentialLabel(plan.provider?.qualificationReviewed, plan.provider?.certificationLabel) ?? ''"
        :customer="describeCustomerParty(plan.customerParty)"
      />
      <PlanVersionCard
        :plan-version="plan.planVersion"
        :status="statusChip"
        :updated-text="`${formatMonthDay(plan.publishedAt)} 更新`"
        :valid-until-text="isPending || isExpired ? `报价有效期至 ${formatMonthDayTime(plan.validUntil)}` : ''"
        :change="describePlanChange(plan, previousVersion)"
        :previous="previousVersion ? describePreviousVersion(previousVersion) : null"
        :previous-version="previousVersion?.planVersion ?? null"
      />
      <PlanQuoteCard
        :rows="toQuoteRows(plan, previousVersion)"
        :total-text="formatPlanPrice(plan.totalAmountMinor)"
      />
      <PlanArrangementCard
        :arrangement="describeServiceArrangement(plan, reservation.lineLengthMeters)"
      />
      <PlanTermsCard :terms="describeDeliveryTerms(plan)" />
      <PlanPaymentCard v-if="paymentRows.length" :rows="paymentRows" />
      <button
        v-if="!isConfirmed && canCancel"
        class="plan-confirmation__cancel"
        :disabled="isCancelling || isSubmitting"
        @click="cancelOrder"
      >
        {{ isCancelling ? '取消中…' : '取消订单' }}
      </button>
    </main>

    <view
      v-if="plan && reservation && !isLoading && !loadError"
      class="plan-confirmation__footer"
    >
      <button
        v-if="isPending"
        class="plan-confirmation__agreement"
        @click="agreementAccepted = !agreementAccepted"
      >
        <view
          class="plan-confirmation__check"
          :class="{ 'plan-confirmation__check--on': agreementAccepted }"
        >
          <LineIcon v-if="agreementAccepted" name="check" :size="12" color="#ffffff" :stroke-width="3" />
        </view>
        <text>我已核对方案 v{{ plan.planVersion }} 的服务范围、报价与交付约定</text>
      </button>

      <view v-if="isConfirmed && confirmation" class="plan-confirmation__status plan-confirmation__status--confirmed">
        <view class="plan-confirmation__status-check">
          <LineIcon name="check" :size="13" color="#ffffff" :stroke-width="3" />
        </view>
        <view class="plan-confirmation__status-copy">
          <text class="plan-confirmation__status-title">{{ confirmation.title }}</text>
          <text class="plan-confirmation__status-text">{{ confirmation.byText }}</text>
          <text class="plan-confirmation__status-note">{{ confirmation.note }}</text>
        </view>
      </view>
      <view v-else-if="isCancelled" class="plan-confirmation__status plan-confirmation__status--cancelled">
        <view class="plan-confirmation__status-copy">
          <text class="plan-confirmation__status-title">订单已取消</text>
          <text class="plan-confirmation__status-text">需要服务时请重新预约</text>
        </view>
      </view>
      <view v-else-if="isExpired" class="plan-confirmation__status plan-confirmation__status--expired">
        <image
          class="plan-confirmation__status-image"
          src="/static/images/inspection/service-flow-expired.svg"
          mode="aspectFit"
        />
        <view class="plan-confirmation__status-copy">
          <text class="plan-confirmation__status-title">方案 v{{ plan.planVersion }} 已过期</text>
          <text class="plan-confirmation__status-text">报价有效期已结束，请申请更新方案后再确认</text>
        </view>
      </view>

      <text v-if="actionError" class="plan-confirmation__error">{{ actionError }}</text>
      <view class="plan-confirmation__actions">
        <button class="plan-confirmation__contact" @click="showCustomerService('联系服务商')">
          <image
            class="plan-confirmation__contact-icon"
            src="/static/images/inspection/service-flow-contact.svg"
            mode="aspectFit"
          />
          <text>联系服务商</text>
        </button>
        <button
          v-if="isConfirmed && downPaymentDue"
          class="plan-confirmation__primary"
          data-testid="plan-pay-down"
          :disabled="paying"
          @click="payDownPayment"
        >
          支付首付 {{ formatPlanPrice(downPaymentDue.amountMinor) }}
        </button>
        <button
          v-else-if="isConfirmed"
          class="plan-confirmation__primary"
          @click="openConfirmedFulfillment"
        >
          查看服务安排
        </button>
        <button
          v-else-if="isCancelled"
          class="plan-confirmation__primary plan-confirmation__primary--secondary"
          @click="goBack"
        >
          查看我的订单
        </button>
        <button
          v-else-if="isExpired"
          class="plan-confirmation__primary"
          @click="showCustomerService('申请更新方案')"
        >
          申请更新方案
        </button>
        <button
          v-else
          class="plan-confirmation__primary plan-confirmation__confirm"
          :disabled="isSubmitting || !confirmable"
          @click="confirmPlan"
        >
          {{ isSubmitting ? '正在确认…' : `确认方案 v${plan.planVersion}` }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.plan-confirmation {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #191919;
  background: #f5f7fc;
}

.plan-confirmation__header {
  position: sticky;
  top: 0;
  z-index: 2;
}

.plan-confirmation__retry::after,
.plan-confirmation__cancel::after,
.plan-confirmation__agreement::after,
.plan-confirmation__contact::after,
.plan-confirmation__primary::after {
  border: 0;
}



.plan-confirmation__state {
  display: flex;
  min-height: 420px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  color: #7e858e;
  font-size: 14px;
}

.plan-confirmation__retry {
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

.plan-confirmation__content {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
  box-sizing: border-box;
}

.plan-confirmation__cancel {
  height: 44px;
  margin: 0 auto;
  padding: 0 20px;
  border: 0;
  background: transparent;
  color: #666b73;
  font-size: 14px;
  line-height: 44px;
  white-space: nowrap;
}

.plan-confirmation__footer {
  position: sticky;
  bottom: 0;
  z-index: 2;
  display: flex;
  padding: 8px 16px max(20px, env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 6px;
  border-top: 1px solid #ebedf0;
  background: #fff;
}

.plan-confirmation__agreement {
  display: flex;
  min-height: 44px;
  width: 100%;
  margin: 0;
  padding: 0;
  align-items: center;
  gap: 8px;
  border: 0;
  background: transparent;
  color: #555a62;
  font-size: 13px;
  line-height: 18px;
  text-align: left;
}

.plan-confirmation__check {
  display: flex;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  border: 1.5px solid #a3a8b0;
  border-radius: 50%;
  background: #fff;
}

.plan-confirmation__check--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.plan-confirmation__status {
  display: flex;
  padding: 10px 12px;
  gap: 8px;
  border-radius: 10px;
}

.plan-confirmation__status--confirmed {
  background: #eefaf3;
}

.plan-confirmation__status--expired {
  background: #fff3e8;
}

.plan-confirmation__status--cancelled {
  background: #f2f3f5;
}

.plan-confirmation__status-check {
  display: flex;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #19b866;
}

.plan-confirmation__status-image {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
}

.plan-confirmation__status-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.plan-confirmation__status-title {
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
}

.plan-confirmation__status--confirmed .plan-confirmation__status-title {
  color: #11804a;
}

.plan-confirmation__status--expired .plan-confirmation__status-title {
  color: #d94f00;
}

.plan-confirmation__status-text {
  color: #555a62;
  font-size: 12px;
  line-height: 17px;
}

.plan-confirmation__status-note {
  color: #8a8f97;
  font-size: 12px;
  line-height: 17px;
}

.plan-confirmation__error {
  color: #d73928;
  font-size: 12px;
  line-height: 17px;
}

.plan-confirmation__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.plan-confirmation__contact {
  display: flex;
  width: 68px;
  height: 44px;
  flex-shrink: 0;
  margin: 0;
  padding: 0;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 2px;
  border: 0;
  background: transparent;
  color: #555a62;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.plan-confirmation__contact-icon {
  width: 22px;
  height: 22px;
}

.plan-confirmation__primary {
  height: 46px;
  flex: 1;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  white-space: nowrap;
}

.plan-confirmation__primary--secondary {
  box-sizing: border-box;
  border: 1px solid #c4c8ce;
  color: #191919;
  background: #fff;
  font-weight: 400;
  line-height: 44px;
}

.plan-confirmation__confirm[disabled] {
  color: #fff;
  background: #c4c8ce;
  opacity: 1;
}
</style>
