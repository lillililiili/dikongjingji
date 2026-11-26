<script lang="ts">
export interface SubmittedBookingReservation {
  status: 'SUBMITTED' | 'PLAN_PUBLISHED' | 'CANCELLED'
  currentPlanId: string | null
}

export interface SubmittedBookingPlan {
  status: 'DRAFT' | 'PUBLISHED' | 'CONFIRMED' | 'EXPIRED' | 'WITHDRAWN'
  serviceOrderId: string | null
}

export function canCancelSubmittedBooking(
  reservation: SubmittedBookingReservation | null,
  plan: SubmittedBookingPlan | null,
  actionsReady: boolean,
): boolean {
  if (!actionsReady || !reservation) return false
  if (reservation.status === 'SUBMITTED') return true
  return reservation.status === 'PLAN_PUBLISHED'
    && plan?.status === 'PUBLISHED'
    && !plan.serviceOrderId
}

export function getSubmittedBookingFooterMode(
  reservation: SubmittedBookingReservation | null,
): 'CONTACT_AND_PRIMARY' | 'RETURN_ORDERS_ONLY' {
  return reservation?.status === 'CANCELLED'
    ? 'RETURN_ORDERS_ONLY'
    : 'CONTACT_AND_PRIMARY'
}

export function getSubmittedBookingHeaderTitle(
  reservation: SubmittedBookingReservation | null,
  openedFromOrderList: boolean,
): '提交成功' | '预约详情' {
  // 只有刚提交（状态还停在 SUBMITTED，且不是从订单列表回看）才写“提交成功”
  if (openedFromOrderList) return '预约详情'
  return !reservation || reservation.status === 'SUBMITTED' ? '提交成功' : '预约详情'
}

export type SubmittedCancellationResult<Reservation extends SubmittedBookingReservation> =
  | { kind: 'dismissed' }
  | { kind: 'cancelled'; reservation: Reservation }
  | { kind: 'refresh-required' }
  | { kind: 'failed'; message: string }

export interface SubmittedReservationLoaderOptions<
  Reservation extends SubmittedBookingReservation,
  Plan extends SubmittedBookingPlan,
> {
  load(): Promise<Reservation>
  loadPlan?(planId: string): Promise<Plan>
  setReservation(reservation: Reservation): void
  setPlan?(plan: Plan | null): void
  setLoadError(message: string): void
  setLoading(loading: boolean): void
  getLoadError(error: unknown): string
}

export function createSubmittedReservationLoader<
  Reservation extends SubmittedBookingReservation,
  Plan extends SubmittedBookingPlan = SubmittedBookingPlan,
>(
  options: SubmittedReservationLoaderOptions<Reservation, Plan>,
) {
  let latestRequestId = 0

  function begin(): number {
    latestRequestId += 1
    return latestRequestId
  }

  function isLatest(requestId: number): boolean {
    return requestId === latestRequestId
  }

  async function loadReservation(): Promise<boolean> {
    const requestId = begin()
    if (isLatest(requestId)) {
      options.setLoading(true)
      options.setLoadError('')
    }
    try {
      const reservation = await options.load()
      if (!isLatest(requestId)) return false
      let currentPlan: Plan | null = null
      if (reservation.status === 'PLAN_PUBLISHED'
        && reservation.currentPlanId
        && options.loadPlan) {
        try {
          currentPlan = await options.loadPlan(reservation.currentPlanId)
        } catch {
          if (!isLatest(requestId)) return false
          options.setPlan?.(null)
          options.setReservation(reservation)
          return true
        }
      }
      if (!isLatest(requestId)) return false
      options.setPlan?.(currentPlan)
      options.setReservation(reservation)
      return true
    } catch (error) {
      if (!isLatest(requestId)) return false
      options.setLoadError(options.getLoadError(error))
      return false
    } finally {
      if (isLatest(requestId)) {
        options.setLoading(false)
      }
    }
  }

  function invalidate(): void {
    const requestId = begin()
    if (isLatest(requestId)) {
      options.setLoading(false)
    }
  }

  return { loadReservation, invalidate, isLatest }
}

export interface SubmittedBookingControllerOptions<Reservation extends SubmittedBookingReservation> {
  getReservation(): Reservation | null
  setReservation(reservation: Reservation): void
  isCancelling(): boolean
  setCancelling(value: boolean): void
  requestCancellation(reservation: Reservation): Promise<SubmittedCancellationResult<Reservation>>
  loadReservation(): Promise<boolean>
  invalidateReservationLoad(): void
  showToast(title: string, icon: 'success' | 'none'): void
  returnOrders(): void
  openPlan(planId: string): void
}

export function createSubmittedBookingController<Reservation extends SubmittedBookingReservation>(
  options: SubmittedBookingControllerOptions<Reservation>,
) {
  async function cancelOrder(): Promise<void> {
    const currentReservation = options.getReservation()
    if (!currentReservation || options.isCancelling()) return

    options.invalidateReservationLoad()
    options.setCancelling(true)
    try {
      const result = await options.requestCancellation(currentReservation)
      if (result.kind === 'cancelled') {
        options.setReservation(result.reservation)
        options.showToast('订单已取消', 'success')
        return
      }
      if (result.kind === 'refresh-required') {
        try {
          if (await options.loadReservation()) {
            options.showToast('订单状态已更新', 'none')
          }
        } catch {
          // The page loader retains its error state when the refresh cannot complete.
        }
        return
      }
      if (result.kind === 'failed') {
        options.showToast(result.message, 'none')
      }
    } catch {
      options.showToast('取消失败，请检查网络后重试', 'none')
    } finally {
      options.setCancelling(false)
    }
  }

  function handlePrimaryAction(): void {
    const currentReservation = options.getReservation()
    if (currentReservation?.status === 'CANCELLED') {
      options.returnOrders()
      return
    }
    if (currentReservation?.currentPlanId) {
      options.openPlan(currentReservation.currentPlanId)
      return
    }
    options.returnOrders()
  }

  function reloadOnShow(): Promise<boolean> {
    if (options.isCancelling()) return Promise.resolve(false)
    return options.loadReservation()
  }

  return { cancelOrder, handlePrimaryAction, reloadOnShow }
}
</script>

<script setup lang="ts">
import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { computed, ref } from 'vue'
import { onBackPress, onLoad, onShow } from '@dcloudio/uni-app'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import IconTile from '@/shared/ui/IconTile.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import { resolveBusinessMediaUrl } from '../../../../../shared/http/business-media'
import { formatMonthDayTime } from '../../shared/service-flow-format'
import {
  inspectionBookingApi,
  inspectionPlanApi,
} from '../../../data/service-flow/uni-booking-http-client'
import type { BookingReservation } from '../../../data/service-flow/inspection-booking-api'
import type { InspectionPlan } from '../../../data/service-flow/inspection-plan-api'
import {
  getPreviousInspectionPageRoute,
  shouldNavigateBackToInspectionOrderCenter,
} from '../../../application/service-flow/inspection-order-navigation'
import { createBookingUuid } from '../../../application/service-flow/booking-crypto'
import {
  canCancelInspectionOrder,
  requestInspectionOrderCancellation,
} from '../../../application/service-flow/inspection-order-cancellation'
import { confirmInspectionOrderCancellation } from '../../shared/inspection-cancellation-ui'
import {
  buildInspectionDetailUrl,
  buildInspectionOrderCenterUrl,
  buildInspectionPlanConfirmationUrl,
} from '../../../navigation/inspection-routes'

interface SubmittedRouteQuery { reservationId?: string }
const reservation = ref<BookingReservation | null>(null)
const plan = ref<InspectionPlan | null>(null)
const isLoading = ref(true)
const loadError = ref('')
const reservationId = ref('')
const isCancelling = ref(false)
const openedFromOrderList = ref(false)
const isCancelled = computed(() => {
  const currentReservation = reservation.value
  return currentReservation !== null && currentReservation.status === 'CANCELLED'
})
const footerMode = computed(() => getSubmittedBookingFooterMode(reservation.value))
const headerTitle = computed(() => getSubmittedBookingHeaderTitle(reservation.value, openedFromOrderList.value))
const showCustomerContact = computed(() => footerMode.value === 'CONTACT_AND_PRIMARY')
const canCancel = computed(() =>
  reservation.value !== null
  && canCancelSubmittedBooking(
    reservation.value,
    plan.value,
    !isLoading.value && !loadError.value,
  )
  && canCancelInspectionOrder(reservation.value.status),
)

onLoad((query: SubmittedRouteQuery | undefined) => {
  reservationId.value = query?.reservationId?.trim() ?? ''
  if (!reservationId.value) {
    isLoading.value = false
    loadError.value = '未找到预约记录'
  }
})

const reservationLoader = createSubmittedReservationLoader<BookingReservation, InspectionPlan>({
  load: () => inspectionBookingApi.getReservation(reservationId.value),
  loadPlan: inspectionPlanApi.getPlan,
  setReservation: (value) => { reservation.value = value },
  setPlan: (value) => { plan.value = value },
  setLoadError: (value) => { loadError.value = value },
  setLoading: (value) => { isLoading.value = value },
  getLoadError: (error) => error instanceof Error ? error.message : '预约记录加载失败',
})
function loadReservation(): Promise<boolean> {
  return reservationLoader.loadReservation()
}

function returnOrders(): void {
  const previousRoute = getPreviousInspectionPageRoute(getCurrentPages())
  if (shouldNavigateBackToInspectionOrderCenter(previousRoute)) {
    uni.navigateBack({ delta: 1 })
    return
  }
  uni.redirectTo({ url: buildInspectionOrderCenterUrl('ALL') })
}
onBackPress((event) => {
  if (event.from === 'navigateBack') return false
  returnOrders()
  return true
})
function openPlan(planId: string): void {
    uni.navigateTo({
      url: buildInspectionPlanConfirmationUrl(planId),
    })
}
const submittedBookingController = createSubmittedBookingController<BookingReservation>({
  getReservation: () => reservation.value,
  setReservation: (value) => { reservation.value = value },
  isCancelling: () => isCancelling.value,
  setCancelling: (value) => { isCancelling.value = value },
  requestCancellation: (currentReservation) => requestInspectionOrderCancellation({
    reservation: currentReservation,
    cancelReservation: inspectionBookingApi.cancelReservation,
    confirm: confirmInspectionOrderCancellation,
    idempotencyKey: createBookingUuid,
  }),
  loadReservation,
  invalidateReservationLoad: reservationLoader.invalidate,
  showToast: (title, icon) => uni.showToast({ title, icon }),
  returnOrders,
  openPlan,
})
onShow(() => {
  // 从订单中心或“我的”最近订单进来是回看，页头不再写“提交成功”
  const previousRoute = getPreviousInspectionPageRoute(getCurrentPages())
  openedFromOrderList.value = shouldNavigateBackToInspectionOrderCenter(previousRoute)
    || previousRoute === 'pages/mine/index'
  if (reservationId.value) void submittedBookingController.reloadOnShow()
})
function handlePrimaryAction(): void {
  submittedBookingController.handlePrimaryAction()
}
async function cancelOrder(): Promise<void> {
  await submittedBookingController.cancelOrder()
}
function copyReservationNo(): void {
  if (!reservation.value) return
  uni.setClipboardData({ data: reservation.value.reservationNo })
}
// 联系服务商 (step G1, preview builds): the provider of this reservation, with a card about the
// service; before a provider is assigned, the platform support. Public builds keep the notice.
function showCustomerService(): void {
  const current = reservation.value
  if (!SUPPORT_PREVIEW_ENABLED || !current) {
    uni.showToast({ title: '客服能力由平台统一接入', icon: 'none' })
    return
  }
  const card = { type: 'INSPECTION_SERVICE' as const, refId: current.serviceId, title: current.serviceTitle, subtitle: `预约单 ${current.reservationNo}` }
  if (current.providerId) openSupportChat({ kind: 'PROVIDER', organizationId: current.providerId }, card)
  else openSupportChat({ kind: 'PLATFORM' }, card)
}
function formatLength(meters: number): string { return `${(meters / 1000).toFixed(1).replace(/\.0$/, '')}公里` }
function maskMobile(value: string): string {
  return /^1\d{10}$/.test(value) ? `${value.slice(0, 3)}****${value.slice(-4)}` : value
}

// Board InspBooked (2026-11-14): status card with a 待方案 / 待确认 / 已取消 chip, the number with
// 复制, the reservation rows, then 接下来 as a four-step line with 刷新状态.
const isPlanPublished = computed(() => reservation.value?.status === 'PLAN_PUBLISHED')
// Opened again from the order list after the plan was confirmed: the plan page and the service
// order carry the rest, so this page only says the plan is settled.
const isPlanConfirmed = computed(() => isPlanPublished.value && plan.value?.status === 'CONFIRMED')
const statusChip = computed(() => {
  if (isCancelled.value) return '已取消'
  if (isPlanConfirmed.value) return '已确认'
  return isPlanPublished.value ? '待确认' : '待方案'
})
const statusTitle = computed(() => {
  if (isCancelled.value) return '订单已取消'
  if (isPlanConfirmed.value) return '方案已确认'
  return isPlanPublished.value ? '方案已发布' : '需求已提交'
})
const statusCopy = computed(() => {
  if (isCancelled.value) return '本次服务已停止安排，订单记录将继续保留'
  if (isPlanConfirmed.value) return '服务商正在安排巡检，进度可在服务履约中查看'
  if (isPlanPublished.value) {
    return plan.value?.validUntil
      ? `服务商已发布方案，请在 ${formatMonthDayTime(plan.value.validUntil)} 前确认`
      : '服务商已发布方案，请尽快确认'
  }
  return '等待服务商确认，方案确认前不收费'
})
const copied = ref(false)
const SLOT_LABELS: Record<string, string> = { MORNING: '上午', AFTERNOON: '下午', EVENING: '晚上' }
const servicePicture = computed(() => reservation.value
  ? (resolveBusinessMediaUrl(reservation.value.serviceCoverUrl) ?? '')
  : '')
const detailRows = computed(() => {
  const current = reservation.value
  if (!current) return []
  const date = current.preferredServiceDate.slice(5).replace('-', '/')
  const slot = SLOT_LABELS[current.preferredTimeSlotCode] ?? ''
  return [
    { key: 'location', label: '巡检地点', value: current.location.address },
    ...(current.lineLengthMeters == null ? [] : [{ key: 'length', label: '线路长度', value: formatLength(current.lineLengthMeters) }]),
    { key: 'time', label: '期望时间', value: slot ? `${date} ${slot}` : date },
    ...(current.inspectionItems.length
      ? [{ key: 'items', label: '巡检项目', value: current.inspectionItems.map((item) => item.label).join('、') }]
      : []),
    { key: 'contact', label: '联系人', value: `${current.contact.name} ${maskMobile(current.contact.mobile)}` },
  ]
})
type StepState = 'done' | 'current' | 'todo'
const progressSteps = computed(() => {
  const current = reservation.value
  if (!current) return []
  const published = isPlanPublished.value
  const steps: Array<{ title: string; note: string; state: StepState }> = [
    { title: '需求已提交', note: formatMonthDayTime(current.submittedAt), state: 'done' },
    published
      ? { title: '服务商确认需求', note: plan.value?.publishedAt ? formatMonthDayTime(plan.value.publishedAt) : '', state: 'done' }
      : { title: '服务商确认需求', note: '等待联系', state: 'current' },
    isPlanConfirmed.value
      ? { title: '确认方案与报价', note: plan.value?.confirmedAt ? formatMonthDayTime(plan.value.confirmedAt) : '', state: 'done' }
      : published
        ? { title: '确认方案与报价', note: '等待你确认', state: 'current' }
        : { title: '确认方案与报价', note: '', state: 'todo' },
    { title: '现场巡检与报告交付', note: '', state: isPlanConfirmed.value ? 'current' : 'todo' },
  ]
  return steps
})
const isRefreshing = ref(false)
async function refreshStatus(): Promise<void> {
  if (isRefreshing.value) return
  isRefreshing.value = true
  try {
    await loadReservation()
  } finally {
    isRefreshing.value = false
  }
}
function copyNumber(): void {
  copyReservationNo()
  copied.value = true
}
const currentCityStore = useCurrentCityStore()
function openService(): void {
  const current = reservation.value
  if (!current) return
  uni.navigateTo({ url: buildInspectionDetailUrl(current.serviceId, current.cityCode || currentCityStore.currentCity?.code || '') })
}
</script>

<template>
  <view class="booking-submitted">
    <AppPageHeader class="booking-submitted__header" :title="headerTitle" test-id="booking-submitted" @back="returnOrders" />
    <view v-if="isLoading" class="booking-submitted__state">正在恢复预约记录…</view>
    <view v-else-if="loadError" class="booking-submitted__state"><LineIcon name="info" :size="40" color="#b5bac1" /><text>{{ loadError }}</text></view>
    <main v-else-if="reservation" class="booking-submitted__content">
      <view class="booking-submitted__success app-card" data-testid="booking-submitted-status">
        <view class="booking-submitted__status-row">
          <!-- The board's 56px status mark is drawn as the theme's icon tile (blue). -->
          <IconTile class="booking-submitted__success-icon" tone="blue" :bg="isCancelled ? '#eef2f8' : undefined" :ink="isCancelled ? '#91a0b4' : undefined" :size="56" :name="isCancelled ? 'close' : 'check'" :icon-size="28" />
          <view class="booking-submitted__status-copy">
            <text class="booking-submitted__title">{{ statusTitle }}</text>
            <text class="booking-submitted__copy">{{ statusCopy }}</text>
            <text v-if="!isCancelled && !isPlanPublished" class="booking-submitted__note">进度可在“我的订单”查看</text>
            <text :class="['booking-submitted__chip', { 'booking-submitted__chip--cancelled': isCancelled }]" data-testid="booking-submitted-chip">{{ statusChip }}</text>
          </view>
        </view>
        <view class="booking-submitted__number">
          <text class="booking-submitted__number-label">预约编号</text>
          <text class="booking-submitted__number-value">{{ reservation.reservationNo }}</text>
          <button :class="['booking-submitted__copy-button', { 'booking-submitted__copy-button--done': copied }]" data-testid="booking-submitted-copy" @click="copyNumber">{{ copied ? '已复制' : '复制' }}</button>
        </view>
        <text class="booking-submitted__time">提交时间：{{ formatMonthDayTime(reservation.submittedAt) }}</text>
      </view>
      <view class="booking-submitted__card app-card">
        <text class="booking-submitted__card-title">预约信息</text>
        <view class="booking-submitted__service" data-testid="booking-submitted-service" @click="openService">
          <image class="booking-submitted__service-picture" :src="servicePicture" mode="aspectFill" />
          <text class="booking-submitted__service-title">{{ reservation.serviceTitle }}</text>
          <LineIcon name="chevronRight" :size="16" color="#91a0b4" :stroke-width="2.4" />
        </view>
        <view v-for="row in detailRows" :key="row.key" class="booking-submitted__detail-row"><text>{{ row.label }}</text><text>{{ row.value }}</text></view>
      </view>
      <view class="booking-submitted__next-steps app-card">
        <view class="booking-submitted__timeline-head">
          <text class="booking-submitted__timeline-title">{{ isCancelled ? '订单状态' : '接下来' }}</text>
          <button v-if="!isCancelled" class="booking-submitted__refresh" data-testid="booking-submitted-refresh" :disabled="isRefreshing" @click="refreshStatus">刷新状态</button>
        </view>
        <view v-if="isCancelled" class="booking-submitted__timeline-row booking-submitted__timeline-row--cancelled">
          <view class="booking-submitted__dot booking-submitted__dot--cancelled"><LineIcon name="close" :size="9" color="#ffffff" :stroke-width="4" /></view>
          <view class="booking-submitted__step-copy"><text>订单已取消</text><text>服务已停止安排</text></view>
        </view>
        <view v-else role="list" aria-label="订单进度">
          <view
            v-for="(step, index) in progressSteps"
            :key="step.title"
            role="listitem"
            :class="['booking-submitted__timeline-row', `booking-submitted__timeline-row--${step.state}`]"
          >
            <view class="booking-submitted__rail">
              <view v-if="index < progressSteps.length - 1" :class="['booking-submitted__line', { 'booking-submitted__line--done': step.state === 'done' }]" />
              <view v-if="step.state === 'done'" class="booking-submitted__dot"><LineIcon name="check" :size="9" color="#ffffff" :stroke-width="4" /></view>
              <view v-else :class="['booking-submitted__dot', 'booking-submitted__dot--open', { 'booking-submitted__dot--current': step.state === 'current' }]"><view v-if="step.state === 'current'" class="booking-submitted__dot-core" /></view>
            </view>
            <view class="booking-submitted__step-copy"><text>{{ step.title }}</text><text v-if="step.note">{{ step.note }}</text></view>
          </view>
        </view>
      </view>
      <button v-if="canCancel" class="booking-submitted__cancel" :disabled="isCancelling" @click="cancelOrder">{{ isCancelling ? '取消中…' : '取消订单' }}</button>
    </main>
    <view class="booking-submitted__footer" :class="{ 'booking-submitted__footer--single': footerMode === 'RETURN_ORDERS_ONLY' }">
      <button v-if="showCustomerContact" class="booking-submitted__contact" @click="showCustomerService"><LineIcon name="chat" :size="22" color="#536178" /><text>联系服务商</text></button>
      <button class="booking-submitted__primary" @click="handlePrimaryAction">{{ isCancelled ? '返回订单' : (reservation?.currentPlanId ? '查看服务方案' : '返回订单') }}</button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspBooked (390 wide). */
.booking-submitted { min-height: 100vh; color: #202c42; background: #f5f7fc; }
.booking-submitted__content { display: flex; max-width: 600px; margin: 0 auto; padding: 12px 16px calc(96px + env(safe-area-inset-bottom)); flex-direction: column; gap: 10px; box-sizing: border-box; }
.booking-submitted__state { display: flex; min-height: 360px; align-items: center; justify-content: center; flex-direction: column; gap: 12px; color: #738096; font-size: 14px; }
.booking-submitted__success { display: flex; padding: 14px; flex-direction: column; gap: 12px; border-radius: 14px; background: #fff; }
.booking-submitted__status-row { display: flex; align-items: flex-start; gap: 12px; }
.booking-submitted__status-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; align-items: flex-start; gap: 4px; }
.booking-submitted__title { font-size: 18px; font-weight: 700; line-height: 25px; white-space: nowrap; }
.booking-submitted__copy { color: #536178; font-size: 13px; line-height: 19px; }
.booking-submitted__note { color: #738096; font-size: 12px; line-height: 17px; }
.booking-submitted__chip { margin-top: 2px; padding: 1px 6px; border-radius: 6px; color: #ba470b; background: #fff0e3; font-size: 12px; line-height: 17px; white-space: nowrap; }
.booking-submitted__chip--cancelled { color: #536178; background: #eef2f8; }
.booking-submitted__number { display: flex; min-height: 44px; padding: 0 12px; align-items: center; gap: 12px; border-radius: 10px; background: #f5f7fc; }
.booking-submitted__number-label { flex: none; color: #627187; font-size: 13px; line-height: 18px; white-space: nowrap; }
.booking-submitted__number-value { min-width: 0; flex: 1; overflow: hidden; font-size: 14px; font-weight: 500; line-height: 20px; text-overflow: ellipsis; white-space: nowrap; }
.booking-submitted__copy-button { height: 44px; margin: 0 -8px 0 0; padding: 0 8px; flex: none; border: 0; color: #ba470b; background: transparent; font-size: 13px; line-height: 44px; }
.booking-submitted__copy-button--done { color: #11804a; }
.booking-submitted__time { margin-top: -6px; color: #738096; font-size: 12px; line-height: 17px; }
.booking-submitted__card { display: flex; padding: 14px 14px 0; flex-direction: column; border-radius: 14px; background: #fff; }
.booking-submitted__card-title { margin-bottom: 8px; font-size: 16px; font-weight: 700; line-height: 22px; }
.booking-submitted__service { display: flex; padding-bottom: 10px; align-items: center; gap: 12px; }
.booking-submitted__service-picture { width: 72px; height: 48px; flex: none; border-radius: 8px; background: #eef2f8; }
.booking-submitted__service-title { min-width: 0; flex: 1; overflow: hidden; font-size: 15px; font-weight: 700; line-height: 21px; text-overflow: ellipsis; white-space: nowrap; }
.booking-submitted__detail-row { display: flex; min-height: 44px; align-items: center; gap: 12px; border-top: 1px solid #f0f1f3; font-size: 14px; line-height: 20px; }
.booking-submitted__detail-row > text:first-child { width: 70px; flex: none; color: #627187; white-space: nowrap; }
.booking-submitted__detail-row > text:last-child { min-width: 0; flex: 1; }
.booking-submitted__next-steps { display: flex; padding: 14px 14px 2px; flex-direction: column; gap: 10px; border-radius: 14px; background: #fff; }
.booking-submitted__timeline-head { display: flex; min-height: 32px; align-items: center; gap: 8px; }
.booking-submitted__timeline-title { font-size: 16px; font-weight: 700; line-height: 22px; white-space: nowrap; }
.booking-submitted__refresh { height: 32px; margin: 0 0 0 auto; padding: 0 12px; border: 1px solid #e5ebf3; border-radius: 999px; color: #536178; background: #fff; font-size: 13px; line-height: 30px; box-sizing: border-box; }
.booking-submitted__timeline-row { display: flex; padding-bottom: 12px; gap: 10px; }
.booking-submitted__rail { position: relative; width: 16px; flex: none; }
.booking-submitted__line { position: absolute; top: 10px; bottom: -14px; left: 7px; width: 2px; background: #e5ebf3; }
.booking-submitted__line--done { background: #159a70; }
.booking-submitted__dot { position: absolute; top: 2px; left: 0; display: flex; width: 16px; height: 16px; align-items: center; justify-content: center; border-radius: 50%; background: #159a70; box-sizing: border-box; }
.booking-submitted__dot--open { border: 2px solid #c9d3e1; background: #fff; }
.booking-submitted__dot--current { border-color: #ff6a00; }
.booking-submitted__dot--cancelled { position: static; margin-top: 2px; background: #91a0b4; }
.booking-submitted__dot-core { width: 6px; height: 6px; border-radius: 50%; background: #ff6a00; }
.booking-submitted__step-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; }
.booking-submitted__step-copy > text:first-child { color: #536178; font-size: 14px; line-height: 20px; }
.booking-submitted__step-copy > text:last-child:not(:first-child) { color: #738096; font-size: 12px; line-height: 17px; }
.booking-submitted__timeline-row--done .booking-submitted__step-copy > text:first-child { color: #202c42; font-weight: 500; }
.booking-submitted__timeline-row--current .booking-submitted__step-copy > text { color: #ba470b !important; }
.booking-submitted__timeline-row--current .booking-submitted__step-copy > text:first-child { font-weight: 500; }
.booking-submitted__timeline-row--cancelled .booking-submitted__step-copy > text:first-child { color: #202c42; font-weight: 500; }
.booking-submitted__cancel { width: 100%; height: 44px; margin: 0; border: 1px solid #c9d3e1; border-radius: 999px; color: #536178; background: #fff; font-size: 15px; line-height: 42px; box-sizing: border-box; }
.booking-submitted__footer { position: fixed; right: 0; bottom: 0; left: 0; display: flex; padding: 10px 16px calc(10px + env(safe-area-inset-bottom)); align-items: center; gap: 10px; border-top: 1px solid #e5ebf3; background: #fff; box-shadow: var(--app-bar-shadow); }
.booking-submitted__contact { display: flex; width: 68px; height: 44px; margin: 0; padding: 0; flex: none; flex-direction: column; align-items: center; justify-content: center; gap: 2px; border: 0; color: #536178; background: transparent; font-size: 12px; line-height: 16px; white-space: nowrap; }
.booking-submitted__primary { height: 46px; margin: 0; flex: 1; border: 0; border-radius: 999px; color: #fff; background: #ff6a00; box-shadow: var(--app-primary-shadow); font-size: 16px; font-weight: 500; line-height: 46px; }
.booking-submitted__copy-button::after, .booking-submitted__refresh::after, .booking-submitted__cancel::after, .booking-submitted__contact::after, .booking-submitted__primary::after { border: 0; }
</style>
