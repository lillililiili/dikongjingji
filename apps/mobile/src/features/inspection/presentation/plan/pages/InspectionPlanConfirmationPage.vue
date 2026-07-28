<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { createBookingUuid } from '../../../application/service-flow/booking-crypto'
import type { BookingReservation } from '../../../data/service-flow/inspection-booking-api'
import type { InspectionPlan } from '../../../data/service-flow/inspection-plan-api'
import {
  inspectionBookingApi,
  inspectionPlanApi,
} from '../../../data/service-flow/uni-booking-http-client'
import {
  formatPlanAmount,
  isPlanConfirmable,
} from '../../../domain/service-flow/plan'
import { buildInspectionFulfillmentUrl } from '../../../navigation/inspection-routes'
import BookingProgress from '../../booking/components/BookingProgress.vue'

interface PlanRouteQuery { planId?: string }

const plan = ref<InspectionPlan | null>(null)
const reservation = ref<BookingReservation | null>(null)
const isLoading = ref(true)
const isSubmitting = ref(false)
const loadError = ref('')
const actionError = ref('')
const agreementAccepted = ref(false)
const confirmationKey = ref('')

const confirmable = computed(() =>
  plan.value !== null && isPlanConfirmable(plan.value),
)
const isConfirmed = computed(() =>
  plan.value?.status === 'CONFIRMED' && Boolean(plan.value.serviceOrderId),
)

onLoad((query: PlanRouteQuery | undefined) => {
  const planId = query?.planId?.trim() ?? ''
  if (!planId) {
    isLoading.value = false
    loadError.value = '未找到服务方案'
    return
  }
  void loadPlan(planId)
})

async function loadPlan(planId: string): Promise<void> {
  isLoading.value = true
  loadError.value = ''
  try {
    const current = await inspectionPlanApi.getPlan(planId)
    plan.value = current
    reservation.value = await inspectionBookingApi.getReservation(
      current.reservationId,
    )
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '方案加载失败'
  } finally {
    isLoading.value = false
  }
}

async function confirmPlan(): Promise<void> {
  if (!plan.value) return
  if (isConfirmed.value && plan.value.serviceOrderId) {
    openFulfillment(plan.value.serviceOrderId)
    return
  }
  if (!agreementAccepted.value) {
    uni.showToast({ title: '请先确认服务方案及报价', icon: 'none' })
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
    openFulfillment(order.id)
  } catch (error) {
    const apiError = error as Error & { code?: string }
    actionError.value = apiError.message || '方案确认失败，请重试'
    if (apiError.code === 'INS_VERSION_CONFLICT') {
      await loadPlan(plan.value.id)
    }
  } finally {
    isSubmitting.value = false
  }
}

function openFulfillment(serviceOrderId: string): void {
  uni.redirectTo({ url: buildInspectionFulfillmentUrl(serviceOrderId) })
}

function goBack(): void {
  uni.navigateBack({
    fail: () => uni.reLaunch({ url: '/pages/home/index' }),
  })
}

function showCustomerService(): void {
  uni.showModal({
    title: '申请调整',
    content: '如需调整服务日期、巡检范围或报价，请通过平台客服登记，方案调整后将生成新版本。',
    showCancel: false,
    confirmText: '我知道了',
  })
}

function copyPlanNo(): void {
  if (plan.value) uni.setClipboardData({ data: plan.value.planNo })
}

function formatLength(meters: number | null): string {
  return meters == null
    ? '待服务商评估'
    : `${(meters / 1000).toFixed(1).replace(/\.0$/, '')}公里`
}

function formatValidUntil(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const part = (number: number): string => String(number).padStart(2, '0')
  return `${date.getFullYear()}-${part(date.getMonth() + 1)}-${part(date.getDate())}`
}

function formatUnitPrice(amountMinor: number, unit: string): string {
  return `${formatPlanAmount(amountMinor)}/${unit}`
}
</script>

<template>
  <view class="plan-confirmation">
    <view class="plan-confirmation__header">
      <button aria-label="返回" @click="goBack">
        <UniIcons type="back" size="22" color="#25282d" />
      </button>
      <text>方案确认</text>
      <button aria-label="联系客服" @click="showCustomerService">
        <UniIcons type="headphones" size="20" color="#25282d" />
        <text>客服</text>
      </button>
    </view>

    <BookingProgress :active="2" />

    <view v-if="isLoading" class="plan-confirmation__state">
      正在恢复服务方案…
    </view>
    <view v-else-if="loadError" class="plan-confirmation__state">
      <UniIcons type="info-filled" size="40" color="#b5bac1" />
      <text>{{ loadError }}</text>
    </view>

    <main
      v-else-if="plan && reservation"
      class="plan-confirmation__content"
    >
      <view class="plan-confirmation__status-card">
        <view class="plan-confirmation__status-main">
          <view class="plan-confirmation__status-icon">
            <UniIcons
              type="checkmarkempty"
              size="42"
              color="#08a942"
            />
          </view>
          <view class="plan-confirmation__status-copy">
            <text class="plan-confirmation__status-title">
              {{ isConfirmed ? '服务方案已确认' : '服务方案待确认' }}
            </text>
            <text>服务商已提交巡检方案与最终报价，请核对后确认</text>
            <text
              class="plan-confirmation__status-tag"
              :class="{ 'plan-confirmation__status-tag--confirmed': isConfirmed }"
            >
              {{ isConfirmed ? '已确认' : '待用户确认' }}
            </text>
          </view>
        </view>
        <button class="plan-confirmation__number" @click="copyPlanNo">
          <text>方案编号</text>
          <text>{{ plan.planNo }}</text>
          <UniIcons type="copy" size="16" color="#50565f" />
        </button>
        <text class="plan-confirmation__valid-until">
          报价有效期至：{{ formatValidUntil(plan.validUntil) }}
        </text>
      </view>

      <view class="plan-confirmation__card">
        <text class="plan-confirmation__card-title">服务方案</text>
        <view class="plan-confirmation__detail-row">
          <text>服务项目</text><text>{{ reservation.serviceTitle }}</text>
        </view>
        <view class="plan-confirmation__detail-row">
          <text>巡检地点</text><text>{{ reservation.location.address }}</text>
        </view>
        <view class="plan-confirmation__detail-row">
          <text>线路长度</text><text>{{ formatLength(reservation.lineLengthMeters) }}</text>
        </view>
        <view class="plan-confirmation__detail-row">
          <text>作业时间</text><text>{{ plan.serviceDate }}</text>
        </view>
        <view class="plan-confirmation__detail-row">
          <text>巡检项目</text>
          <text>{{ reservation.inspectionItems.map((item) => item.label).join('、') }}</text>
        </view>
        <view class="plan-confirmation__detail-row">
          <text>作业方式</text><text>{{ plan.summary || '按服务方案执行' }}</text>
        </view>
        <view class="plan-confirmation__detail-row">
          <text>交付内容</text>
          <text>{{ plan.deliverables.map((item) => item.label).join('、') }}</text>
        </view>
      </view>

      <view class="plan-confirmation__card plan-confirmation__quote">
        <text class="plan-confirmation__card-title">报价明细</text>
        <view
          v-for="item in plan.planItems"
          :key="item.code"
          class="plan-confirmation__quote-row"
        >
          <text>{{ item.name }}</text>
          <text>{{ item.quantity }}{{ item.unit }} × {{ formatUnitPrice(item.unitPriceMinor, item.unit) }}</text>
          <text>{{ formatPlanAmount(item.lineAmountMinor) }}</text>
        </view>
        <view class="plan-confirmation__quote-total">
          <view>
            <text>最终报价</text>
            <text>已包含现场巡检、数据处理及报告交付</text>
          </view>
          <text>{{ formatPlanAmount(plan.totalAmountMinor) }}</text>
        </view>
      </view>
    </main>

    <view
      v-if="plan && reservation"
      class="plan-confirmation__footer"
    >
      <view class="plan-confirmation__notice">
        <UniIcons type="info-filled" size="18" color="#ff6a00" />
        <text>确认方案后进入服务安排，本页面不会立即扣款。</text>
      </view>
      <button
        v-if="!isConfirmed"
        class="plan-confirmation__agreement"
        @click="agreementAccepted = !agreementAccepted"
      >
        <UniIcons
          :type="agreementAccepted ? 'checkbox-filled' : 'circle'"
          size="18"
          :color="agreementAccepted ? '#ff5a00' : '#9ba1a9'"
        />
        <text>我已阅读并确认服务方案及报价</text>
      </button>
      <text v-if="actionError" class="plan-confirmation__error">
        {{ actionError }}
      </text>
      <view class="plan-confirmation__actions">
        <button class="plan-confirmation__support" @click="showCustomerService">
          <UniIcons type="headphones" size="22" color="#25282d" />
          <text>客服</text>
        </button>
        <button
          class="plan-confirmation__confirm"
          :disabled="(!agreementAccepted || !confirmable) && !isConfirmed"
          @click="confirmPlan"
        >
          {{ isConfirmed ? '查看服务安排' : (isSubmitting ? '正在确认…' : '确认方案') }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.plan-confirmation{min-height:100vh;background:#f7f8fa;color:#25282d}.plan-confirmation__header{display:grid;min-height:calc(44px + env(safe-area-inset-top));padding-top:env(safe-area-inset-top);grid-template-columns:44px 1fr 64px;align-items:center;background:#fff;font-size:16px;font-weight:700;text-align:center;box-sizing:border-box}.plan-confirmation__header button{height:44px;margin:0;padding:0;border:0;background:transparent}.plan-confirmation__header button::after,.plan-confirmation__number::after,.plan-confirmation__agreement::after,.plan-confirmation__actions button::after{border:0}.plan-confirmation__header button:last-child{display:flex;width:64px;align-items:center;justify-content:center;gap:3px;font-size:11px}.plan-confirmation :deep(.booking-progress){padding:10px 24px 8px}.plan-confirmation :deep(.booking-progress__dot){width:18px;height:18px}.plan-confirmation :deep(.booking-progress__line){top:8px}.plan-confirmation :deep(.booking-progress__label){margin-top:4px;font-size:10px}.plan-confirmation__content{max-width:600px;margin:0 auto;padding:5px 8px 136px}.plan-confirmation__status-card,.plan-confirmation__card{padding:9px 11px 7px;border:1px solid #eceef1;border-radius:13px;background:#fff}.plan-confirmation__status-main{display:grid;grid-template-columns:68px minmax(0,1fr);align-items:center}.plan-confirmation__status-icon{display:grid;width:56px;height:56px;place-items:center;border-radius:50%;background:#e7f8e9}.plan-confirmation__status-copy{display:flex;align-items:flex-start;flex-direction:column;color:#737b85;font-size:10px}.plan-confirmation__status-title{color:#25282d;font-size:16px;font-weight:700;letter-spacing:.5px}.plan-confirmation__status-tag{margin-top:3px;padding:1px 7px;border:1px solid #16af55;border-radius:10px;color:#16a34a;font-size:9px}.plan-confirmation__status-tag--confirmed{background:#eaf8ef}.plan-confirmation__number{display:grid;width:100%;min-height:31px;margin-top:6px;padding:0 9px;grid-template-columns:70px 1fr 20px;align-items:center;border:1px solid #e2e5e8;border-radius:7px;background:#fff;color:#25282d;font-size:11px;text-align:left}.plan-confirmation__number text:nth-child(2){font-weight:600;letter-spacing:.5px}.plan-confirmation__valid-until{display:block;margin-top:4px;color:#737a83;font-size:9px}.plan-confirmation__card{margin-top:6px;padding:7px 10px 5px;font-size:10px}.plan-confirmation__card-title{display:block;margin-bottom:3px;font-size:13px;font-weight:700}.plan-confirmation__detail-row{display:grid;min-height:22px;grid-template-columns:82px minmax(0,1fr);align-items:start;border-top:1px solid #eceef1}.plan-confirmation__detail-row:first-of-type{border-top:0}.plan-confirmation__detail-row>text{padding-top:4px}.plan-confirmation__detail-row>text:last-child{line-height:14px}.plan-confirmation__quote-row{display:grid;min-height:29px;grid-template-columns:74px minmax(0,1fr) auto;align-items:center;border-top:1px solid #eceef1;gap:5px}.plan-confirmation__quote-row>text:nth-child(2){color:#5e646d}.plan-confirmation__quote-row>text:last-child{text-align:right}.plan-confirmation__quote-total{display:flex;padding:7px 0 3px;align-items:center;justify-content:space-between;border-top:1px solid #dfe2e5}.plan-confirmation__quote-total>view{display:flex;flex-direction:column;font-size:12px;font-weight:700}.plan-confirmation__quote-total>view text:last-child{margin-top:2px;color:#777e87;font-size:8px;font-weight:400}.plan-confirmation__quote-total>text{color:#ff5200;font-size:20px}.plan-confirmation__state{display:flex;min-height:420px;align-items:center;justify-content:center;flex-direction:column;gap:12px;color:#7e858e;font-size:14px}.plan-confirmation__footer{position:fixed;right:0;bottom:0;left:0;padding:0 8px calc(6px + env(safe-area-inset-bottom));background:#fff}.plan-confirmation__notice{display:flex;margin:0 -8px;padding:5px 11px;align-items:center;gap:5px;color:#a65c2e;background:#fff5ea;font-size:9px}.plan-confirmation__agreement{display:flex;height:31px;margin:0;padding:0 3px;align-items:center;gap:6px;border:0;background:#fff;color:#343940;font-size:11px}.plan-confirmation__error{display:block;padding:0 3px 4px;color:#d73928;font-size:10px}.plan-confirmation__actions{display:grid;grid-template-columns:56px minmax(0,1fr);gap:6px}.plan-confirmation__actions button{height:40px;margin:0;border:0}.plan-confirmation__support{display:flex;padding:0;align-items:center;justify-content:center;flex-direction:column;background:#fff;color:#25282d;font-size:9px}.plan-confirmation__confirm{border-radius:22px!important;color:#fff!important;background:linear-gradient(90deg,#ff8a00,#ff4d00)!important;font-size:16px!important;font-weight:700}.plan-confirmation__confirm[disabled]{color:#fff!important;background:#ffb483!important;opacity:1}
</style>
