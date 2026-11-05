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
import { computed, ref } from 'vue'
import { onBackPress, onLoad, onShow } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
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
const isCancelled = computed(() => {
  const currentReservation = reservation.value
  return currentReservation !== null && currentReservation.status === 'CANCELLED'
})
const footerMode = computed(() => getSubmittedBookingFooterMode(reservation.value))
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
function showCustomerService(): void { uni.showToast({ title: '客服能力由平台统一接入', icon: 'none' }) }
function formatLength(meters: number | null): string { return meters == null ? '待服务商评估' : `${(meters / 1000).toFixed(1).replace(/\.0$/, '')}公里` }
function formatSubmittedAt(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const part = (number: number): string => String(number).padStart(2, '0')
  return `${date.getFullYear()}-${part(date.getMonth() + 1)}-${part(date.getDate())} ${part(date.getHours())}:${part(date.getMinutes())}`
}
function maskMobile(value: string): string {
  return /^1\d{10}$/.test(value) ? `${value.slice(0, 3)}****${value.slice(-4)}` : value
}
</script>

<template>
  <view class="booking-submitted">
    <view class="booking-submitted__header">
      <button aria-label="返回" @click="returnOrders"><UniIcons type="back" size="24" color="#17191d" /></button>
      <text>提交成功</text>
      <text class="booking-submitted__page-step">1/5</text>
    </view>
    <view v-if="isLoading" class="booking-submitted__state">正在恢复预约记录…</view>
    <view v-else-if="loadError" class="booking-submitted__state"><UniIcons type="info-filled" size="40" color="#b5bac1" /><text>{{ loadError }}</text></view>
    <main v-else-if="reservation" class="booking-submitted__content">
      <view class="booking-submitted__success">
        <view class="booking-submitted__success-icon"><UniIcons type="checkmarkempty" size="62" color="#18a947" /></view>
        <text class="booking-submitted__title">{{ isCancelled ? '订单已取消' : '订单已提交' }}</text>
        <text class="booking-submitted__copy">{{ isCancelled ? '本次服务已停止安排，订单记录将继续保留' : '预约申请已提交，服务商将在 30分钟内联系您' }}</text>
      </view>
      <view class="booking-submitted__card">
        <view class="booking-submitted__service"><image :src="reservation.serviceCoverUrl || '/static/images/inspection/inspection-hero-v1.png'" mode="aspectFill" /><view><text>{{ reservation.serviceTitle }}</text><text>云翼科技</text></view></view>
        <button class="booking-submitted__number" @click="copyReservationNo"><text>预约编号</text><text>{{ reservation.reservationNo }}</text><UniIcons type="copy" size="16" color="#50565f" /></button>
        <view class="booking-submitted__detail-row"><text>巡检地点</text><text>{{ reservation.location.address }}</text></view>
        <view class="booking-submitted__detail-row"><text>线路长度</text><text>{{ formatLength(reservation.lineLengthMeters) }}</text></view>
        <view class="booking-submitted__detail-row"><text>期望时间</text><text>{{ reservation.preferredServiceDate }}</text></view>
        <view class="booking-submitted__detail-row"><text>联系人</text><text>{{ reservation.contact.name }}　{{ maskMobile(reservation.contact.mobile) }}</text></view>
        <text class="booking-submitted__time">提交时间：{{ formatSubmittedAt(reservation.submittedAt) }}</text>
      </view>
      <view v-if="isCancelled" class="booking-submitted__next-steps">
        <text class="booking-submitted__timeline-title">订单状态</text>
        <view class="booking-submitted__timeline-row booking-submitted__timeline-row--cancelled"><UniIcons type="info-filled" size="18" color="#7d8590" /><view><text>订单已取消</text><text>服务已停止安排</text></view></view>
      </view>
      <view v-else class="booking-submitted__next-steps">
        <text class="booking-submitted__timeline-title">接下来</text>
        <view class="booking-submitted__timeline-row booking-submitted__timeline-row--done"><UniIcons type="checkbox-filled" size="18" color="#16af55" /><view><text>需求已提交</text><text>{{ formatSubmittedAt(reservation.submittedAt) }}</text></view></view>
        <view class="booking-submitted__timeline-row booking-submitted__timeline-row--current"><UniIcons type="circle-filled" size="18" color="#ff6a00" /><view><text>服务商确认需求</text><text>等待联系</text></view></view>
        <view class="booking-submitted__timeline-row"><UniIcons type="circle" size="18" color="#b5bac1" /><text>确认方案与报价</text></view>
        <view class="booking-submitted__timeline-row"><UniIcons type="circle" size="18" color="#b5bac1" /><text>现场巡检与报告交付</text></view>
      </view>
      <button v-if="canCancel" class="booking-submitted__cancel" :disabled="isCancelling" @click="cancelOrder">{{ isCancelling ? '取消中…' : '取消订单' }}</button>
    </main>
    <view class="booking-submitted__footer" :class="{ 'booking-submitted__footer--single': footerMode === 'RETURN_ORDERS_ONLY' }"><button v-if="showCustomerContact" class="booking-submitted__contact" @click="showCustomerService"><UniIcons type="chat" size="20" color="#17191d" />联系商家</button><button @click="handlePrimaryAction">{{ isCancelled ? '返回订单' : (reservation?.currentPlanId ? '查看服务方案' : '返回订单') }}</button></view>
  </view>
</template>

<style scoped>
.booking-submitted{min-height:100vh;background:#f7f8fa;color:#25282d}.booking-submitted__header{display:grid;min-height:calc(48px + env(safe-area-inset-top));padding-top:env(safe-area-inset-top);grid-template-columns:44px 1fr 64px;align-items:center;background:#fff;font-size:17px;font-weight:700;text-align:center;box-sizing:border-box}.booking-submitted__header button{height:44px;margin:0;padding:0;border:0;background:transparent}.booking-submitted__header button::after{border:0}.booking-submitted__header button:last-child{display:flex;width:64px;align-items:center;justify-content:center;gap:3px;font-size:12px}.booking-submitted__content{max-width:600px;margin:0 auto;padding:8px 8px 92px}.booking-submitted__success{padding:14px 14px 12px;border:1px solid #eceef1;border-radius:13px;background:#fff}.booking-submitted__success-main{display:grid;grid-template-columns:86px minmax(0,1fr);align-items:center}.booking-submitted__success-icon{display:grid;width:72px;height:72px;place-items:center;border-radius:50%;background:#e7f8e9}.booking-submitted__success-copy{display:flex;align-items:flex-start;flex-direction:column}.booking-submitted__title{font-size:19px;font-weight:700;letter-spacing:1px}.booking-submitted__copy{margin-top:4px;color:#737b85;font-size:12px}.booking-submitted__status{margin-top:6px;padding:2px 8px;border:1px solid #ff6a00;border-radius:10px;color:#ff5a00;font-size:11px}.booking-submitted__number{display:grid;width:100%;min-height:37px;margin-top:10px;padding:0 12px;grid-template-columns:78px 1fr 22px;align-items:center;border:1px solid #e2e5e8;border-radius:7px;background:#fff;color:#25282d;font-size:13px;text-align:left}.booking-submitted__number::after,.booking-submitted__footer button::after{border:0}.booking-submitted__number text:nth-child(2){font-weight:600;letter-spacing:.5px}.booking-submitted__time{display:block;margin-top:7px;color:#737a83;font-size:11px}.booking-submitted__card{margin-top:8px;padding:11px 12px 8px;border:1px solid #eceef1;border-radius:12px;background:#fff;color:#25282d;font-size:12px}.booking-submitted__card-title{display:block;margin-bottom:7px;font-size:15px;font-weight:700}.booking-submitted__service{display:grid;min-height:60px;padding-bottom:7px;grid-template-columns:88px minmax(0,1fr) 18px;gap:10px;align-items:center}.booking-submitted__service image{width:88px;height:60px;border-radius:7px;background:#edf0f2}.booking-submitted__service>text{overflow:hidden;font-size:15px;font-weight:700;text-overflow:ellipsis;white-space:nowrap}.booking-submitted__detail-row{display:grid;min-height:28px;grid-template-columns:92px minmax(0,1fr);align-items:center;border-top:1px solid #eceef1}.booking-submitted__detail-row>text:last-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.booking-submitted__timeline{margin-top:8px;padding:11px 12px 9px;border:1px solid #eceef1;border-radius:12px;background:#fff}.booking-submitted__timeline-title{display:block;margin-bottom:6px;font-size:15px;font-weight:700}.booking-submitted__timeline-row{position:relative;display:grid;min-height:33px;grid-template-columns:24px minmax(0,1fr);align-items:start;color:#41464e;font-size:12px}.booking-submitted__timeline-row:not(:last-child)::after{position:absolute;top:17px;bottom:-4px;left:8px;border-left:1px dashed #b6bbc1;content:''}.booking-submitted__timeline-row--done:not(:last-child)::after{border-color:#16af55}.booking-submitted__timeline-row>view{display:flex;flex-direction:column}.booking-submitted__timeline-row>view text:last-child{margin-top:1px;color:#8b929a;font-size:10px}.booking-submitted__timeline-row--current>view text{color:#ff5a00}.booking-submitted__state{display:flex;min-height:360px;align-items:center;justify-content:center;flex-direction:column;gap:12px;color:#7e858e;font-size:14px}.booking-submitted__footer{position:fixed;right:0;bottom:0;left:0;padding:0 8px calc(7px + env(safe-area-inset-bottom));background:#fff}.booking-submitted__footer>view{display:flex;margin:0 -8px;padding:6px 12px;align-items:center;gap:6px;color:#a65c2e;background:#fff5ea;font-size:10px}.booking-submitted__footer button{display:block;width:min(100%,584px);height:40px;margin:5px auto 0;border:0;border-radius:22px;color:#fff;background:linear-gradient(90deg,#ff8a00,#ff4d00);font-size:16px;font-weight:700}
.booking-submitted__content{padding:7px 8px 84px}
.booking-submitted__success{padding:11px 12px 9px}
.booking-submitted__success-main{grid-template-columns:76px minmax(0,1fr)}
.booking-submitted__success-icon{width:64px;height:64px}
.booking-submitted__title{font-size:18px;letter-spacing:.5px}
.booking-submitted__copy{margin-top:3px;font-size:11px}
.booking-submitted__status{margin-top:5px;padding:1px 7px;font-size:10px}
.booking-submitted__number{min-height:34px;margin-top:8px;padding:0 10px;grid-template-columns:74px 1fr 20px;font-size:12px}
.booking-submitted__time{margin-top:5px;font-size:10px}
.booking-submitted__card{margin-top:7px;padding:9px 11px 6px;font-size:11px}
.booking-submitted__card-title{margin-bottom:5px;font-size:14px}
.booking-submitted__service{min-height:54px;padding-bottom:5px;grid-template-columns:80px minmax(0,1fr) 18px;gap:9px}
.booking-submitted__service image{width:80px;height:54px}
.booking-submitted__service>text{font-size:14px}
.booking-submitted__detail-row{min-height:25px;grid-template-columns:86px minmax(0,1fr)}
.booking-submitted__timeline{margin-top:7px;padding:9px 11px 7px}
.booking-submitted__timeline-title{margin-bottom:4px;font-size:14px}
.booking-submitted__timeline-row{min-height:29px;grid-template-columns:23px minmax(0,1fr);font-size:11px}
.booking-submitted__timeline-row:not(:last-child)::after{top:16px;bottom:-3px}
.booking-submitted__timeline-row>view text:last-child{font-size:9px}
.booking-submitted__footer{padding-bottom:calc(6px + env(safe-area-inset-bottom))}
.booking-submitted__footer>view{padding:5px 11px;gap:5px;font-size:9px}
.booking-submitted__footer button{height:38px;margin-top:4px;border-radius:21px;font-size:15px}

.booking-submitted{background:#fff}
.booking-submitted__header{min-height:calc(56px + var(--status-bar-height));padding-top:var(--status-bar-height);grid-template-columns:56px 1fr 56px;background:#fff;font-size:19px}
.booking-submitted__header button{width:56px;height:56px}
.booking-submitted__page-step{justify-self:center;padding:5px 11px;border-radius:16px;background:#f3f4f5;font-size:13px;font-weight:500}
.booking-submitted__content{padding:42px 22px 124px}
.booking-submitted__success{display:flex;padding:0 0 34px;align-items:center;flex-direction:column;border:0;background:transparent}
.booking-submitted__success-icon{width:94px;height:94px;border:5px solid #20aa49;background:#fff}
.booking-submitted__title{margin-top:24px;font-size:30px}
.booking-submitted__copy{margin-top:12px;color:#737985;font-size:15px;text-align:center}
.booking-submitted__copy>text{color:#20ad4f}
.booking-submitted__card{margin-top:0;padding:14px;border:0;border-radius:22px;box-shadow:0 10px 32px rgba(32,43,55,.08);font-size:13px}
.booking-submitted__service{min-height:92px;padding-bottom:12px;grid-template-columns:110px minmax(0,1fr);gap:14px}
.booking-submitted__service image{width:110px;height:92px;border-radius:14px}
.booking-submitted__service>view{display:flex;min-width:0;flex-direction:column}
.booking-submitted__service>view text:first-child{font-size:17px;font-weight:700}
.booking-submitted__service>view text:last-child{margin-top:8px;color:#7f858d;font-size:13px}
.booking-submitted__number{min-height:44px;margin-top:4px;font-size:13px}
.booking-submitted__detail-row{min-height:40px;grid-template-columns:86px minmax(0,1fr);font-size:13px}
.booking-submitted__time{margin-top:8px;font-size:11px}
.booking-submitted__next-steps{margin-top:24px;padding:20px 18px;border-radius:22px;background:#fff;box-shadow:0 10px 32px rgba(32,43,55,.08)}
.booking-submitted__cancel{display:block;width:100%;height:44px;margin-top:16px;border:1px solid #d9dde2;border-radius:14px;color:#c74432;background:#fff;font-size:16px;font-weight:600}.booking-submitted__cancel::after{border:0}
.booking-submitted__timeline-title{margin-bottom:16px;font-size:20px}
.booking-submitted__timeline-row{min-height:64px;grid-template-columns:34px minmax(0,1fr);font-size:16px}
.booking-submitted__timeline-row:not(:last-child)::after{top:20px;bottom:-8px;left:8px}
.booking-submitted__timeline-row>view text:last-child{margin-top:7px;font-size:12px}
.booking-submitted__footer{display:grid;padding:10px 22px calc(12px + env(safe-area-inset-bottom));grid-template-columns:1fr 1fr;gap:12px;box-shadow:0 -7px 24px rgba(25,35,48,.07)}
.booking-submitted__footer--single{grid-template-columns:1fr}
.booking-submitted__footer button{width:100%;height:52px;margin:0;border-radius:16px;font-size:16px}
.booking-submitted__footer .booking-submitted__contact{display:flex;align-items:center;justify-content:center;gap:8px;border:1px solid #aeb3ba;color:#17191d;background:#fff}
</style>
