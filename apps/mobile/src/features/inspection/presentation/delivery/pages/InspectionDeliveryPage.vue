<script setup lang="ts">
import { computed, ref } from 'vue'
import { onBackPress, onLoad } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import {
  createAcceptanceCoordinator,
  executeDeliveryFileAction,
  executeDeliverySaveAction,
  type DeliveryFileAction,
  type DeliveryFileRuntime,
} from '../../../application/service-flow/delivery-actions'
import { createBookingUuid } from '../../../application/service-flow/booking-crypto'
// #ifdef APP-PLUS
// @ts-ignore The UTS module resolves its native implementation for each App platform.
import { pickInspectionDeliveryDirectory, saveInspectionDeliveryFile } from '@/uni_modules/inspection-delivery-storage'
// #endif
import type {
  InspectionDelivery,
  InspectionServiceOrder,
} from '../../../data/service-flow/inspection-plan-api'
import { inspectionPlanApi } from '../../../data/service-flow/uni-booking-http-client'
import {
  createDeliveryFileStorage,
  type DeliveryDirectoryStorageBridge,
} from '../../../data/service-flow/delivery-file-storage'
import { buildInspectionOrderCenterUrl } from '../../../navigation/inspection-routes'
import {
  resolveInspectionDeliveryBackAction,
  shouldNavigateBackToInspectionOrderCenter,
} from '../../../application/service-flow/inspection-order-navigation'

interface DeliveryRouteQuery { serviceOrderId?: string }
type DeliveryFile = InspectionDelivery['files'][number]

const serviceOrderId = ref('')
const order = ref<InspectionServiceOrder | null>(null)
const delivery = ref<InspectionDelivery | null>(null)
const isLoading = ref(true)
const loadError = ref('')
const actionError = ref('')
const isAccepting = ref(false)
const activeFileAction = ref('')
const acceptanceCoordinator = createAcceptanceCoordinator(createBookingUuid)
const nonAppDeliveryStorageBridge: DeliveryDirectoryStorageBridge = {
  pick(options) {
    options.fail?.({
      code: 'DELIVERY_DIRECTORY_PICKER_UNAVAILABLE',
      errMsg: '当前平台不支持选择保存目录',
    })
  },
  save(options) {
    options.fail?.({
      code: 'DELIVERY_FILE_WRITE_FAILED',
      errMsg: '当前平台不支持目录保存',
    })
  },
}
let deliveryFileStorage = createDeliveryFileStorage(nonAppDeliveryStorageBridge)
// #ifdef APP-PLUS
deliveryFileStorage = createDeliveryFileStorage({
  pick: pickInspectionDeliveryDirectory,
  save: saveInspectionDeliveryFile,
})
// #endif

const isAccepted = computed(() => delivery.value?.status === 'ACCEPTED')
const deliveryStages = ['服务安排', '现场巡检', '报告交付']
const fileTypePresentation: Record<DeliveryFile['type'], {
  icon: string
  accent: string
  previewable: boolean
}> = {
  IMAGE_ARCHIVE: { icon: 'image', accent: '#ff7a00', previewable: false },
  DEFECT_LIST: { icon: 'list', accent: '#20a45a', previewable: true },
  POINT_KML: { icon: 'location', accent: '#2977d5', previewable: false },
  INSPECTION_REPORT: { icon: 'compose', accent: '#ef4e45', previewable: true },
}
const serviceProgress = computed(() => {
  const current = order.value
  if (!current) return []
  const eventTime = (code: InspectionServiceOrder['progress'][number]['eventCode']): string => {
    const event = current.progress.find((item) => item.eventCode === code)
    return event ? formatDateTime(event.occurredAt) : ''
  }
  return [
    { code: 'PLAN_CONFIRMED', label: '方案已确认', time: formatDateTime(current.createdAt) },
    { code: 'ARRANGEMENT_READY', label: '作业资源安排', time: eventTime('ARRANGEMENT_READY') },
    { code: 'SERVICE_STARTED', label: '作业前确认', time: eventTime('SERVICE_STARTED') },
    { code: 'SERVICE_COMPLETED', label: '现场巡检', time: eventTime('SERVICE_COMPLETED') },
    { code: 'DELIVERED', label: '报告交付', time: formatDateTime(current.deliveredAt ?? delivery.value?.publishedAt ?? null) },
  ]
})

const deliveryFileRuntime: DeliveryFileRuntime = {
  pickSaveTarget() {
    return deliveryFileStorage.pickSaveTarget()
  },
  download(url) {
    return new Promise((resolve, reject) => {
      uni.downloadFile({
        url,
        success: ({ statusCode, tempFilePath }) => {
          if (statusCode < 200 || statusCode >= 300 || !tempFilePath) {
            reject(new Error(`文件下载失败（${statusCode}）`))
            return
          }
          resolve(tempFilePath)
        },
        fail: () => reject(new Error('网络连接失败，请稍后重试')),
      })
    })
  },
  preview(filePath) {
    return new Promise((resolve, reject) => {
      uni.openDocument({
        filePath,
        showMenu: true,
        success: () => resolve(),
        fail: () => reject(new Error('当前设备无法预览此文件，请下载后查看')),
      })
    })
  },
  save(filePath, suggestedFileName, target) {
    return deliveryFileStorage.save(filePath, suggestedFileName, target)
  },
}

onLoad((query: DeliveryRouteQuery | undefined) => {
  serviceOrderId.value = query?.serviceOrderId?.trim() ?? ''
  if (!serviceOrderId.value) {
    isLoading.value = false
    loadError.value = '未找到报告交付记录'
    return
  }
  void reload()
})

onBackPress((event) => {
  if (event.from === 'navigateBack') return false
  returnToFulfillment()
  return true
})

async function reload(): Promise<void> {
  if (!serviceOrderId.value) return
  isLoading.value = true
  loadError.value = ''
  try {
    const [currentOrder, currentDelivery] = await Promise.all([
      inspectionPlanApi.getServiceOrder(serviceOrderId.value),
      inspectionPlanApi.getDelivery(serviceOrderId.value),
    ])
    order.value = currentOrder
    delivery.value = currentDelivery
  } catch (error) {
    loadError.value = messageOf(error, '报告交付加载失败')
  } finally {
    isLoading.value = false
  }
}

async function runFileAction(file: DeliveryFile, action: DeliveryFileAction): Promise<void> {
  if (activeFileAction.value) return
  activeFileAction.value = `${file.fileId}:${action}`
  actionError.value = ''
  try {
    if (action === 'SAVE') {
      const outcome = await executeDeliverySaveAction(
        () => inspectionPlanApi.createDownloadIntent(
          serviceOrderId.value,
          file.fileId,
          createBookingUuid(),
        ),
        file.type,
        deliveryFileRuntime,
      )
      if (outcome.status === 'CANCELLED') {
        uni.showToast({ title: '已取消保存', icon: 'none' })
        return
      }
      showSavedName(outcome.savedName)
      return
    }

    const intent = await inspectionPlanApi.createDownloadIntent(
      serviceOrderId.value,
      file.fileId,
      createBookingUuid(),
    )
    await executeDeliveryFileAction(
      intent,
      action,
      file.type,
      deliveryFileRuntime,
    )
  } catch (error) {
    actionError.value = messageOf(error, '文件处理失败，请重试')
  } finally {
    activeFileAction.value = ''
  }
}

async function downloadAll(): Promise<void> {
  if (activeFileAction.value) return
  activeFileAction.value = 'all:SAVE'
  actionError.value = ''
  try {
    const outcome = await executeDeliverySaveAction(
      () => inspectionPlanApi.createDownloadAllIntent(
        serviceOrderId.value,
        createBookingUuid(),
      ),
      'IMAGE_ARCHIVE',
      deliveryFileRuntime,
    )
    if (outcome.status === 'CANCELLED') {
      uni.showToast({ title: '已取消保存', icon: 'none' })
      return
    }
    showAllSavedName(outcome.savedName)
  } catch (error) {
    actionError.value = messageOf(error, '全部文件保存失败，请重试')
  } finally {
    activeFileAction.value = ''
  }
}

async function acceptDelivery(): Promise<void> {
  if (!delivery.value || !order.value || isAccepted.value || isAccepting.value) return
  isAccepting.value = true
  actionError.value = ''
  try {
    await acceptanceCoordinator.accept(
      inspectionPlanApi.acceptDelivery,
      serviceOrderId.value,
      order.value.version,
    )
    await reload()
  } catch (error) {
    const acceptanceError = messageOf(error, '验收结果未确认，请重试')
    await reload()
    actionError.value = acceptanceError
  } finally {
    isAccepting.value = false
  }
}

function returnToFulfillment(): void {
  const previousRoute = getCurrentPages().at(-2)?.route
  const action = resolveInspectionDeliveryBackAction(
    previousRoute,
    shouldNavigateBackToInspectionOrderCenter(previousRoute),
  )
  if (action === 'navigateBack') {
    uni.navigateBack({ delta: 1 })
    return
  }
  uni.reLaunch({ url: buildInspectionOrderCenterUrl('ALL') })
}
function showCustomerService(): void {
  uni.showToast({ title: '客服能力由平台统一接入', icon: 'none' })
}
function copyOrderNo(): void {
  if (order.value) uni.setClipboardData({ data: order.value.serviceOrderNo })
}
function showSavedPath(savedPath: string): void {
  uni.showToast({
    title: savedPath.startsWith('Download/') ? '已保存至 Download/巡检交付' : '文件已保存',
    icon: 'none',
    duration: 2500,
  })
}
function isAndroidApp(): boolean {
  return typeof plus !== 'undefined' && plus.os.name === 'Android'
}
function showSavedName(savedName: string): void {
  if (!isAndroidApp()) {
    showSavedPath(savedName)
    return
  }
  uni.showToast({ title: `${savedName} 已保存`, icon: 'none', duration: 2500 })
}
function showAllSavedName(savedName: string): void {
  if (!isAndroidApp()) {
    showSavedPath(savedName)
    return
  }
  uni.showToast({
    title: '全部文件压缩包已保存' + `：${savedName}`,
    icon: 'none',
    duration: 2500,
  })
}
function messageOf(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}
function formatLength(value: number): string {
  return value >= 1000 ? `${(value / 1000).toFixed(1)} km` : `${value} m`
}
function formatSize(value: number): string {
  return value >= 1024 * 1024
    ? `${(value / 1024 / 1024).toFixed(1)} MB`
    : `${Math.max(1, Math.round(value / 1024))} KB`
}
function formatDateTime(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const part = (number: number): string => String(number).padStart(2, '0')
  return `${date.getFullYear()}-${part(date.getMonth() + 1)}-${part(date.getDate())} ${part(date.getHours())}:${part(date.getMinutes())}`
}
</script>

<template>
  <view class="delivery">
    <view class="delivery__header">
      <button aria-label="返回" @click="returnToFulfillment">
        <UniIcons type="back" size="22" color="#25282d" />
      </button>
      <text>报告交付</text>
      <text class="delivery__page-step">5/5</text>
    </view>
    <view class="delivery__flow">
      <view v-for="(stage, index) in deliveryStages" :key="stage" class="delivery__flow-item">
        <view class="delivery__flow-dot" :class="{ 'delivery__flow-dot--current': index === 2 }">
          <UniIcons v-if="index < 2" type="checkmarkempty" size="10" color="#ffffff" />
          <text v-else>3</text>
        </view>
        <text :class="{ 'delivery__flow-label--current': index === 2 }">{{ stage }}</text>
        <text v-if="index < 2" class="delivery__flow-line" />
      </view>
    </view>

    <view v-if="isLoading" class="delivery__state">正在恢复报告交付记录…</view>
    <view v-else-if="loadError" class="delivery__state">
      <UniIcons type="info-filled" size="40" color="#b5bac1" />
      <text>{{ loadError }}</text>
      <button @click="reload">重新加载</button>
    </view>

    <main v-else-if="delivery && order" class="delivery__content">
      <view class="delivery__status">
        <view class="delivery__status-main">
          <view class="delivery__status-icon">
            <UniIcons
              v-if="isAccepted"
              type="checkbox-filled"
              size="38"
              color="#ffffff"
            />
            <UniIcons
              v-else
              type="compose"
              size="34"
              color="#ffffff"
            />
          </view>
          <view>
            <text class="delivery__status-title">{{ isAccepted ? '服务已验收' : '报告已交付' }}</text>
            <text class="delivery__status-copy">{{ isAccepted ? '本次巡检服务已完成验收' : '请查看巡检成果，确认无误后完成验收' }}</text>
            <text class="delivery__status-tag">{{ isAccepted ? '已验收' : '待验收' }}</text>
          </view>
        </view>
        <button class="delivery__order-no" @click="copyOrderNo">
          <text>服务单号</text>
          <text>{{ order.serviceOrderNo }}</text>
          <UniIcons type="copy" size="16" color="#50565f" />
        </button>
        <text class="delivery__status-time">交付时间：{{ formatDateTime(delivery.publishedAt) }}</text>
      </view>

      <view class="delivery__card">
        <text class="delivery__title">巡检结果</text>
        <view class="delivery__result">
          <image
            :src="order.serviceSnapshot.serviceCoverUrl || '/static/images/inspection/inspection-hero-v1.png'"
            mode="aspectFill"
          />
          <view class="delivery__result-body">
            <text class="delivery__service-title">{{ order.serviceSnapshot.serviceTitle }}</text>
            <view class="delivery__metrics">
              <view><text>{{ delivery.towerCount }}</text><text>巡检杆塔</text></view>
              <view><text>{{ formatLength(delivery.inspectedLengthMeters) }}</text><text>巡检长度</text></view>
              <view><text>{{ delivery.issueCount }}</text><text>发现问题</text></view>
            </view>
            <view class="delivery__result-line">
              <UniIcons type="calendar" size="13" color="#727981" />
              <text>作业时间</text>
              <text>{{ formatDateTime(order.plannedStartAt) }}</text>
            </view>
            <view class="delivery__result-line">
              <UniIcons type="location" size="13" color="#727981" />
              <text>巡检地点</text>
              <text>{{ order.serviceSnapshot.location.address }}</text>
            </view>
          </view>
        </view>
      </view>

      <view class="delivery__card">
        <text class="delivery__title">问题摘要</text>
        <view class="delivery__issues">
          <view v-for="item in delivery.issueSummary" :key="item.severityCode">
            <text class="delivery__issue-dot" />
            <text>{{ item.severityName }}</text>
            <text>{{ item.count }} 项</text>
          </view>
          <text v-if="!delivery.issueSummary.length">本次巡检未发现问题</text>
        </view>
      </view>

      <view class="delivery__card">
        <view class="delivery__file-head">
          <text class="delivery__title">成果文件 · 交付文件（{{ delivery.files.length }}）</text>
          <button :disabled="Boolean(activeFileAction)" @click="downloadAll">全部下载</button>
        </view>
        <view class="delivery__files-grid">
          <view v-for="file in delivery.files" :key="file.fileId" class="delivery__file">
            <view class="delivery__file-icon" :style="{ backgroundColor: `${fileTypePresentation[file.type].accent}16` }">
              <UniIcons
                :type="fileTypePresentation[file.type].icon"
                size="22"
                :color="fileTypePresentation[file.type].accent"
              />
            </view>
            <view class="delivery__file-copy">
              <text>{{ file.displayName }}</text>
              <text>{{ file.pointCount != null ? `${file.pointCount} 个点位 · ` : '' }}{{ formatSize(file.sizeBytes) }}</text>
            </view>
            <view class="delivery__file-action">
              <button
                v-if="fileTypePresentation[file.type].previewable"
                :disabled="Boolean(activeFileAction)"
                @click="runFileAction(file, 'PREVIEW')"
              >查看</button>
              <button
                v-else
                :disabled="Boolean(activeFileAction)"
                @click="runFileAction(file, 'SAVE')"
              >{{ activeFileAction === `${file.fileId}:SAVE` ? '下载中' : '下载' }}</button>
            </view>
          </view>
        </view>
      </view>

      <view class="delivery__card">
        <text class="delivery__title">服务进度</text>
        <view v-for="step in serviceProgress" :key="step.code" class="delivery__progress">
          <UniIcons
            type="checkbox-filled"
            size="10"
            :color="step.code === 'DELIVERED' && !isAccepted ? '#ff6a00' : '#16af55'"
          />
          <text>{{ step.label }}</text>
          <text>{{ step.time }}</text>
        </view>
      </view>

      <text v-if="actionError" class="delivery__error">{{ actionError }}</text>
    </main>

    <view v-if="delivery" class="delivery__footer">
      <view>
        <UniIcons type="info-filled" size="17" color="#ff6a00" />
        <text>请核对巡检结果，确认后本次服务将完成。</text>
      </view>
      <button v-if="isAccepted" class="delivery__accepted" disabled>验收已完成</button>
      <button v-else :disabled="isAccepting" @click="acceptDelivery">
        {{ isAccepting ? '验收确认中' : '确认验收' }}
      </button>
    </view>
  </view>
</template>

<style scoped>
.delivery{min-height:100vh;background:#f7f8fa;color:#25282d}
.delivery__header{display:grid;height:calc(44px + var(--status-bar-height));padding-top:var(--status-bar-height);grid-template-columns:44px 1fr 62px;align-items:center;overflow:hidden;background:#fff;font-size:17px;font-weight:700;text-align:center;box-sizing:border-box}
.delivery__header button{height:44px;min-height:44px;margin:0;padding:0;border:0;background:transparent;line-height:1}
.delivery__customer-service{display:flex;align-items:center;justify-content:center;gap:3px;color:#25282d;font-size:11px;font-weight:400}
.delivery__header button::after,.delivery__order-no::after,.delivery__file-head button::after,.delivery__file-action button::after,.delivery__footer button::after{border:0}
.delivery__flow{display:flex;height:38px;padding:2px 54px 3px;align-items:flex-start;justify-content:space-between;background:#fff;box-sizing:border-box}
.delivery__flow-item{position:relative;display:flex;min-width:70px;align-items:center;flex-direction:column;color:#596069;font-size:9px}
.delivery__flow-dot{z-index:1;display:grid;width:16px;height:16px;place-items:center;border-radius:50%;color:#fff;background:#18ad55;font-size:9px;font-weight:700}
.delivery__flow-dot--current{background:#ff6a00}
.delivery__flow-label--current{color:#ff5a00}
.delivery__flow-line{position:absolute;top:7px;left:45px;width:96px;height:2px;background:#18ad55}
.delivery__content{max-width:600px;margin:0 auto;padding:5px 8px 56px}
.delivery__status,.delivery__card{padding:9px 10px;border:1px solid #eceef1;border-radius:12px;background:#fff}
.delivery__status-main{display:grid;grid-template-columns:64px minmax(0,1fr);align-items:center}
.delivery__status-icon{display:grid;width:54px;height:54px;place-items:center;border-radius:50%;background:#22b95c}
.delivery__status-main>view:last-child{display:flex;align-items:flex-start;flex-direction:column}
.delivery__status-title{font-size:17px;font-weight:700}
.delivery__status-copy{margin-top:2px;color:#717983;font-size:10px}
.delivery__status-tag{margin-top:3px;padding:1px 7px;border:1px solid #ff6a00;border-radius:10px;color:#ff5a00;font-size:9px}
.delivery__order-no{display:grid;width:100%;min-height:44px;margin-top:5px;padding:0 9px;grid-template-columns:68px 1fr 18px;align-items:center;border:1px solid #e2e5e8;border-radius:7px;background:#fff;color:#25282d;font-size:11px;text-align:left}
.delivery__order-no text:nth-child(2){font-weight:600;letter-spacing:.4px}
.delivery__status-time{display:block;margin-top:3px;color:#737a83;font-size:9px}
.delivery__card{margin-top:6px;padding:8px 10px}
.delivery__title{display:block;font-size:13px;font-weight:700}
.delivery__result{display:grid;margin-top:6px;grid-template-columns:90px minmax(0,1fr);gap:8px}
.delivery__result>image{width:90px;height:102px;border-radius:7px;background:#edf0f2}
.delivery__result-body{display:flex;min-width:0;flex-direction:column}
.delivery__service-title{overflow:hidden;font-size:12px;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.delivery__metrics{display:grid;margin-top:5px;grid-template-columns:repeat(3,1fr)}
.delivery__metrics view{display:flex;align-items:center;flex-direction:column;border-left:1px solid #eceef1}
.delivery__metrics view:first-child{border-left:0}
.delivery__metrics text:first-child{font-size:13px;font-weight:700}
.delivery__metrics text:last-child{margin-top:1px;color:#7b828a;font-size:8px}
.delivery__result-line{display:grid;min-height:22px;grid-template-columns:16px 51px minmax(0,1fr);align-items:center;border-top:1px solid #eceef1;color:#5f666e;font-size:8px}
.delivery__result-line:first-of-type{margin-top:5px}
.delivery__result-line text:last-child{overflow:hidden;text-align:right;text-overflow:ellipsis;white-space:nowrap}
.delivery__issues{margin-top:5px}
.delivery__issues view{display:grid;min-height:20px;grid-template-columns:12px minmax(0,1fr) auto;align-items:center;color:#555d65;font-size:9px}
.delivery__issue-dot{width:6px;height:6px;border-radius:50%;background:#ff7a00}
.delivery__issues view:nth-child(even) .delivery__issue-dot{background:#2c78dc}
.delivery__file-head{position:relative;display:flex;height:16px;align-items:center}
.delivery__file-head button{position:absolute;top:-14px;right:-6px;min-width:72px;min-height:44px;margin:0;padding:0;border:0;background:transparent;color:#ff5a00;font-size:10px}
.delivery__files-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px}
.delivery__file{display:grid;height:44px;min-height:44px;padding:0 5px;grid-template-columns:28px minmax(0,1fr) 44px;align-items:center;gap:5px;overflow:hidden;border:1px solid #e7e9ec;border-radius:8px;box-sizing:border-box}
.delivery__file-icon{display:grid;width:28px;height:28px;place-items:center;border-radius:6px}
.delivery__file-copy{display:flex;min-width:0;flex-direction:column}
.delivery__file-copy text:first-child{overflow:hidden;font-size:9px;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.delivery__file-copy text:last-child{margin-top:3px;color:#939aa1;font-size:8px}
.delivery__file-action button{width:44px;min-height:44px;margin:0;padding:0;border:0;background:transparent;color:#ff5a00;font-size:9px}
.delivery__progress{display:grid;height:10px;min-height:10px;grid-template-columns:18px minmax(0,1fr) auto;align-items:center;color:#464d55;font-size:8px;line-height:10px}
.delivery__progress>text:last-child{color:#92989f;font-size:7px}
.delivery__card:last-of-type{height:74px;padding-top:4px;padding-bottom:4px;overflow:hidden;box-sizing:border-box}
.delivery__error{display:block;padding:8px 3px;color:#d73928;font-size:10px}
.delivery__state{display:flex;min-height:360px;align-items:center;justify-content:center;flex-direction:column;gap:12px;color:#7e858e;font-size:14px}
.delivery__state button{min-height:44px;border:0;border-radius:22px;color:#fff;background:#ff6a00;font-size:13px}
.delivery__footer{position:fixed;right:0;bottom:0;left:0;height:calc(56px + env(safe-area-inset-bottom));padding:0 8px env(safe-area-inset-bottom);background:#fff;box-sizing:border-box}
.delivery__footer>view{display:flex;height:12px;margin:0 -8px;padding:0 11px;align-items:center;gap:5px;color:#a65c2e;background:#fff5ea;font-size:8px;line-height:12px}
.delivery__footer button{display:block;width:min(100%,584px);height:44px;min-height:44px;margin:0 auto;border:0;border-radius:22px;color:#fff;background:linear-gradient(90deg,#ff8a00,#ff4d00);font-size:15px;font-weight:700}
.delivery__footer .delivery__accepted{color:#168b48;background:#e9f8ee;opacity:1}
.delivery{background:#fff}
.delivery__header{height:calc(56px + var(--status-bar-height));grid-template-columns:56px 1fr 56px;font-size:19px}
.delivery__header button{width:56px;height:56px}
.delivery__page-step{justify-self:center;padding:5px 11px;border-radius:16px;background:#f3f4f5;font-size:13px;font-weight:500}
.delivery__flow{display:none}
.delivery__content{padding:24px 12px 130px}
.delivery__status,.delivery__card{margin-top:14px;padding:17px;border:0;border-radius:22px;box-shadow:0 10px 30px rgba(29,42,58,.08)}
.delivery__status-main{grid-template-columns:76px minmax(0,1fr)}
.delivery__status-icon{width:64px;height:64px}
.delivery__status-title{font-size:19px}
.delivery__status-copy{font-size:12px}
.delivery__status-tag{font-size:11px}
.delivery__order-no{min-height:44px;margin-top:12px;font-size:12px}
.delivery__status-time{margin-top:7px;font-size:11px}
.delivery__title{font-size:18px}
.delivery__result{margin-top:12px;grid-template-columns:122px minmax(0,1fr);gap:12px}
.delivery__result>image{width:122px;height:142px;border-radius:13px}
.delivery__service-title{font-size:14px}
.delivery__metrics text:first-child{font-size:16px}
.delivery__metrics text:last-child{font-size:9px}
.delivery__result-line{min-height:30px;font-size:10px}
.delivery__issues{margin-top:10px}
.delivery__issues view{min-height:34px;font-size:12px}
.delivery__file-head{height:30px}
.delivery__file-head button{top:-8px;font-size:12px}
.delivery__files-grid{grid-template-columns:1fr;gap:9px}
.delivery__file{height:58px;min-height:58px;padding:0 9px;grid-template-columns:38px minmax(0,1fr) 52px;border-radius:12px}
.delivery__file-icon{width:38px;height:38px}
.delivery__file-copy text:first-child{font-size:12px}
.delivery__file-copy text:last-child{font-size:10px}
.delivery__file-action button{width:52px;min-height:44px;font-size:12px}
.delivery__progress{height:auto;min-height:36px;grid-template-columns:22px minmax(0,1fr) auto;font-size:12px}
.delivery__progress>text:last-child{font-size:10px}
.delivery__card:last-of-type{height:auto;padding:17px;overflow:visible}
.delivery__footer{height:auto;padding:8px 12px calc(10px + env(safe-area-inset-bottom));box-shadow:0 -7px 24px rgba(25,35,48,.07)}
.delivery__footer>view{height:22px;margin:0 -12px 7px;padding:0 14px;font-size:10px;line-height:22px}
.delivery__footer button{height:52px;border-radius:16px;font-size:17px}
</style>
