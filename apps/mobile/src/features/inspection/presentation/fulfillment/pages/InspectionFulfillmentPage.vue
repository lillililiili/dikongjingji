<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import type { InspectionPlan, InspectionServiceOrder } from '../../../data/service-flow/inspection-plan-api'
import { inspectionPlanApi } from '../../../data/service-flow/uni-booking-http-client'
import { formatPlanAmount } from '../../../domain/service-flow/plan'
import BookingProgress from '../../booking/components/BookingProgress.vue'
import { buildInspectionDeliveryUrl } from '../../../navigation/inspection-routes'

interface FulfillmentRouteQuery { serviceOrderId?: string }

const order = ref<InspectionServiceOrder | null>(null)
const plan = ref<InspectionPlan | null>(null)
const isLoading = ref(true)
const loadError = ref('')
const serviceOrderId = ref('')

const teamAssignment = computed(() => order.value?.assignments.find((item) => item.type === 'TEAM_MEMBER') ?? null)
const deviceAssignment = computed(() => order.value?.assignments.find((item) => item.type === 'DEVICE') ?? null)
const statusLabel = computed(() => ({ ARRANGING: '安排中', IN_PROGRESS: '服务中', DELIVERED: '待验收', ACCEPTED: '已验收' }[order.value?.status ?? 'ARRANGING']))
const statusTitle = computed(() => ({ ARRANGING: '方案已确认', IN_PROGRESS: '现场服务进行中', DELIVERED: '报告已交付', ACCEPTED: '服务已验收' }[order.value?.status ?? 'ARRANGING']))
const statusCopy = computed(() => order.value?.status === 'ARRANGING' ? '服务商正在安排作业团队与设备' : '服务状态已根据真实进度更新')
const progressSteps = computed(() => {
  const current = order.value
  if (!current) return []
  const completedOnSite = current.progress.some((item) => item.eventCode === 'SERVICE_COMPLETED')
  const delivered = current.status === 'DELIVERED' || current.status === 'ACCEPTED'
  const started = current.progress.some((item) => item.eventCode === 'SERVICE_STARTED')
  const progressing = current.progress.some((item) => item.eventCode === 'SERVICE_PROGRESS')
  return [
    { code: 'PLAN_CONFIRMED', label: '方案已确认', completed: true, current: false, meta: formatDateTime(plan.value?.confirmedAt ?? null) },
    { code: 'ARRANGEMENT_READY', label: '作业资源安排', completed: current.status !== 'ARRANGING', current: current.status === 'ARRANGING', meta: current.status === 'ARRANGING' ? '进行中' : '' },
    { code: 'SERVICE_STARTED', label: '作业前确认', completed: started, current: false, meta: '' },
    { code: 'SERVICE_PROGRESS', label: '现场巡检', completed: completedOnSite, current: current.status === 'IN_PROGRESS' && progressing && !completedOnSite, meta: '' },
    { code: 'DELIVERED', label: '报告交付', completed: delivered, current: current.status === 'DELIVERED', meta: '' },
  ]
})

onLoad((query: FulfillmentRouteQuery | undefined) => {
  serviceOrderId.value = query?.serviceOrderId?.trim() ?? ''
  if (!serviceOrderId.value) { isLoading.value = false; loadError.value = '未找到服务安排'; return }
  void reload()
})

async function reload(): Promise<void> {
  if (!serviceOrderId.value) return
  isLoading.value = true
  loadError.value = ''
  try {
    const current = await inspectionPlanApi.getServiceOrder(serviceOrderId.value)
    order.value = current
    plan.value = await inspectionPlanApi.getPlan(current.planId)
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '服务安排加载失败'
  } finally { isLoading.value = false }
}

function returnHome(): void { uni.reLaunch({ url: '/pages/home/index' }) }
function openDelivery(): void {
  if (!order.value) return
  uni.navigateTo({ url: buildInspectionDeliveryUrl(order.value.id) })
}
function copyOrderNo(): void { if (order.value) uni.setClipboardData({ data: order.value.serviceOrderNo }) }
function showCustomerService(): void { uni.showToast({ title: '客服能力由平台统一接入', icon: 'none' }) }
function formatDateTime(value: string | null): string {
  if (!value) return '待服务商确认'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const part = (number: number): string => String(number).padStart(2, '0')
  return `${date.getFullYear()}-${part(date.getMonth() + 1)}-${part(date.getDate())} ${part(date.getHours())}:${part(date.getMinutes())}`
}
function assignmentLabel(
  assignment: InspectionServiceOrder['assignments'][number] | null,
): string {
  if (!assignment) return '服务商正在安排'
  return assignment.roleLabel
    ? `${assignment.displayName}（${assignment.roleLabel}）`
    : assignment.displayName
}
function formatLineLength(value: number | null): string {
  if (value == null) return '按现场方案执行'
  return value >= 1000
    ? `${(value / 1000).toFixed(1)} km`
    : `${value} m`
}
</script>

<template>
  <view class="fulfillment">
    <view class="fulfillment__header">
      <button aria-label="返回" @click="returnHome"><UniIcons type="back" size="22" color="#25282d" /></button>
      <text>服务安排</text>
      <button aria-label="联系客服" @click="showCustomerService"><UniIcons type="headphones" size="20" color="#25282d" /><text>客服</text></button>
    </view>
    <BookingProgress :active="3" />
    <view v-if="isLoading" class="fulfillment__state">正在恢复服务安排…</view>
    <view v-else-if="loadError" class="fulfillment__state"><UniIcons type="info-filled" size="40" color="#b5bac1" /><text>{{ loadError }}</text><button @click="reload">重新加载</button></view>
    <main v-else-if="order && plan" class="fulfillment__content">
      <view class="fulfillment__status-card">
        <view class="fulfillment__status-main"><view class="fulfillment__status-icon"><UniIcons type="calendar" size="36" color="#ff5a00" /></view><view><text class="fulfillment__status-title">{{ statusTitle }}</text><text class="fulfillment__copy">{{ statusCopy }}</text><text class="fulfillment__tag">{{ statusLabel }}</text></view></view>
        <button class="fulfillment__number" @click="copyOrderNo"><text>服务单号</text><text>{{ order.serviceOrderNo }}</text><UniIcons type="copy" size="16" color="#50565f" /></button>
        <text class="fulfillment__time">方案确认时间：{{ formatDateTime(plan.confirmedAt) }}</text>
      </view>

      <view class="fulfillment__card">
        <text class="fulfillment__card-title">本次服务</text>
        <view class="fulfillment__service">
          <image :src="order.serviceSnapshot.serviceCoverUrl || '/static/images/inspection/inspection-hero-v1.png'" mode="aspectFill" />
          <view class="fulfillment__service-details">
            <text class="fulfillment__service-title">{{ order.serviceSnapshot.serviceTitle }}</text>
            <view><text>巡检地点</text><text>{{ order.serviceSnapshot.location.address }}</text></view>
            <view><text>服务时间</text><text>{{ formatDateTime(order.plannedStartAt) }}</text></view>
            <view><text>巡检长度</text><text>{{ formatLineLength(order.serviceSnapshot.lineLengthMeters) }}</text></view>
            <view><text>巡检项目</text><text>{{ order.serviceSnapshot.inspectionItems.map((item) => item.label).join('、') }}</text></view>
            <view class="fulfillment__service-quote"><text>确认报价</text><text>{{ formatPlanAmount(plan.totalAmountMinor) }}</text></view>
          </view>
        </view>
      </view>

      <view class="fulfillment__card"><text class="fulfillment__card-title">作业安排</text><view class="fulfillment__row"><text>作业地点</text><text>{{ order.serviceSnapshot.location.address }}</text></view><view class="fulfillment__row"><text>服务时间</text><text>{{ formatDateTime(order.plannedStartAt) }}</text></view><view class="fulfillment__row"><text>作业团队</text><text>{{ assignmentLabel(teamAssignment) }}</text></view><view class="fulfillment__row"><text>作业设备</text><text>{{ assignmentLabel(deviceAssignment) }}</text></view></view>

      <view class="fulfillment__card">
        <text class="fulfillment__card-title">服务进度</text>
        <view v-for="step in progressSteps" :key="step.code" class="fulfillment__progress">
          <UniIcons
            :type="step.completed ? 'checkbox-filled' : 'circle'"
            size="15"
            :color="step.current ? '#ff5a00' : step.completed ? '#16af55' : '#c8cdd2'"
          />
          <text :class="{ 'fulfillment__progress--current': step.current }">{{ step.label }}</text>
          <text :class="{ 'fulfillment__progress--current': step.current }">{{ step.meta }}</text>
        </view>
      </view>
    </main>
    <button v-if="order && (order.status === 'DELIVERED' || order.status === 'ACCEPTED')" class="fulfillment__delivery-link" @click="openDelivery">查看报告与验收</button>
    <view v-if="order" class="fulfillment__footer"><view><UniIcons type="info-filled" size="18" color="#ff6a00" /><text>服务安排会随服务商实际进度更新，请保持联系方式畅通。</text></view><button @click="returnHome">返回首页</button></view>
  </view>
</template>

<style scoped>
.fulfillment{min-height:100vh;background:#f7f8fa;color:#25282d}.fulfillment__header{display:grid;min-height:calc(43px + env(safe-area-inset-top));padding-top:env(safe-area-inset-top);grid-template-columns:42px 1fr 62px;align-items:center;background:#fff;font-size:17px;font-weight:700;text-align:center;box-sizing:border-box}.fulfillment__header button{height:42px;margin:0;padding:0;border:0;background:transparent}.fulfillment__header button::after,.fulfillment__number::after,.fulfillment__footer button::after{border:0}.fulfillment__header button:last-child{display:flex;width:62px;align-items:center;justify-content:center;gap:3px;font-size:12px}.fulfillment :deep(.booking-progress){padding:6px 24px 5px}.fulfillment :deep(.booking-progress__line--active){background:#16ad55}.fulfillment__content{max-width:600px;margin:0 auto;padding:4px 8px 77px}.fulfillment__status-card,.fulfillment__card{padding:8px 11px 6px;border:1px solid #eceef1;border-radius:12px;background:#fff}.fulfillment__status-main{display:grid;grid-template-columns:65px minmax(0,1fr);align-items:center}.fulfillment__status-icon{display:grid;width:55px;height:55px;place-items:center;border-radius:50%;background:#fff0e6}.fulfillment__status-main>view:last-child{display:flex;align-items:flex-start;flex-direction:column}.fulfillment__status-title{font-size:17px;font-weight:700}.fulfillment__copy{margin-top:2px;color:#737b85;font-size:10px}.fulfillment__tag{margin-top:3px;padding:0 7px;border:1px solid #ff6a00;border-radius:10px;color:#ff5a00;font-size:10px}.fulfillment__number{display:grid;width:100%;min-height:31px;margin-top:5px;padding:0 9px;grid-template-columns:72px 1fr 18px;align-items:center;border:1px solid #e2e5e8;border-radius:7px;background:#fff;color:#25282d;font-size:11px;text-align:left}.fulfillment__number text:nth-child(2){font-weight:600;letter-spacing:.4px}.fulfillment__time{display:block;margin-top:3px;color:#737a83;font-size:9px}.fulfillment__card{margin-top:5px;padding:7px 10px 5px;font-size:10px}.fulfillment__card-title{display:block;margin-bottom:4px;font-size:13px;font-weight:700}.fulfillment__service{display:grid;grid-template-columns:80px minmax(0,1fr);gap:8px;align-items:center}.fulfillment__service image{width:80px;height:94px;border-radius:7px;background:#edf0f2}.fulfillment__service-details{display:flex;min-width:0;flex-direction:column}.fulfillment__service-title{overflow:hidden;padding-bottom:3px;font-size:13px;font-weight:700;text-overflow:ellipsis;white-space:nowrap}.fulfillment__service-details>view{display:grid;min-height:18px;grid-template-columns:60px minmax(0,1fr);align-items:center;border-top:1px solid #eceef1}.fulfillment__service-details>view text:last-child{overflow:hidden;text-align:right;text-overflow:ellipsis;white-space:nowrap}.fulfillment__service-details .fulfillment__service-quote{font-weight:700}.fulfillment__service-quote text:last-child{color:#ff5200;font-size:14px}.fulfillment__row{display:grid;min-height:22px;grid-template-columns:72px minmax(0,1fr);align-items:center;border-top:1px solid #eceef1}.fulfillment__row text:last-child{overflow:hidden;text-align:right;text-overflow:ellipsis;white-space:nowrap}.fulfillment__progress{display:grid;min-height:20px;grid-template-columns:22px minmax(0,1fr) auto;align-items:center;color:#7d858d;font-size:10px}.fulfillment__progress>text:last-child{font-size:9px}.fulfillment__progress--current{color:#ff5a00!important;font-weight:700}.fulfillment__state{display:flex;min-height:360px;align-items:center;justify-content:center;flex-direction:column;gap:12px;color:#7e858e;font-size:14px}.fulfillment__state button{border:0;border-radius:18px;color:#fff;background:#ff6a00;font-size:13px}.fulfillment__footer{position:fixed;right:0;bottom:0;left:0;padding:0 8px calc(5px + env(safe-area-inset-bottom));background:#fff}.fulfillment__footer>view{display:flex;margin:0 -8px;padding:4px 11px;align-items:center;gap:5px;color:#a65c2e;background:#fff5ea;font-size:9px}.fulfillment__footer button{display:block;width:min(100%,584px);height:36px;margin:3px auto 0;border:0;border-radius:21px;color:#fff;background:linear-gradient(90deg,#ff8a00,#ff4d00);font-size:15px;font-weight:700}
.fulfillment__delivery-link{display:block;width:calc(100% - 16px);height:36px;margin:8px auto 0;border:0;border-radius:18px;color:#ff5a00;background:#fff1e8;font-size:14px;font-weight:700}
</style>
