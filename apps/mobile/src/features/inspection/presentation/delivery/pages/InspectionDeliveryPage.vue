<script setup lang="ts">
import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { computed, ref } from 'vue'
import { onBackPress, onLoad } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import {
  createAcceptanceCoordinator,
  executeDeliveryFileAction,
  executeDeliverySaveAction,
  type DeliveryFileRuntime,
} from '../../../application/service-flow/delivery-actions'
import { createBookingUuid } from '../../../application/service-flow/booking-crypto'
// #ifdef APP-PLUS
// @ts-ignore The UTS module resolves its native implementation for each App platform.
import { pickInspectionDeliveryDirectory, saveInspectionDeliveryFile } from '@/uni_modules/inspection-delivery-storage'
// #endif
import type {
  InspectionDelivery,
  InspectionDownloadIntent,
  InspectionPlan,
  InspectionServiceOrder,
} from '../../../data/service-flow/inspection-plan-api'
import { inspectionPlanApi } from '../../../data/service-flow/uni-booking-http-client'
import {
  createDeliveryFileStorage,
  type DeliveryDirectoryStorageBridge,
} from '../../../data/service-flow/delivery-file-storage'
import { resolveDeliveryVisit } from '../../../domain/service-flow/service-visit'
import { buildInspectionOrderCenterUrl } from '../../../navigation/inspection-routes'
import {
  getPreviousInspectionPageRoute,
  resolveInspectionDeliveryBackAction,
  shouldNavigateBackToInspectionOrderCenter,
} from '../../../application/service-flow/inspection-order-navigation'
import { formatClock } from '../../shared/service-flow-format'
import AcceptanceConfirmSheet from '../components/AcceptanceConfirmSheet.vue'
import DeliveryFilesCard from '../components/DeliveryFilesCard.vue'
import DeliveryResultCard from '../components/DeliveryResultCard.vue'
import {
  describeAcceptanceBanner,
  describeAcceptanceSheet,
  describeDeliveryHeader,
  toFileRows,
  toIssueRows,
  toResultTiles,
} from '../delivery-view'

interface DeliveryRouteQuery { serviceOrderId?: string; visitId?: string }
type DeliveryFile = InspectionDelivery['files'][number]

const ALL_FILES_LINK = 'all'

const serviceOrderId = ref('')
const routeVisitId = ref('')
const order = ref<InspectionServiceOrder | null>(null)
const plan = ref<InspectionPlan | null>(null)
const delivery = ref<InspectionDelivery | null>(null)
const isLoading = ref(true)
const loadError = ref('')
const actionError = ref('')
const isAccepting = ref(false)
const isSheetOpen = ref(false)
const activeFileAction = ref('')
const linkExpiries = ref<Record<string, string>>({})
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

const visit = computed(() => order.value
  ? resolveDeliveryVisit(order.value.visits, routeVisitId.value)
  : null)
const isAccepted = computed(() => delivery.value?.status === 'ACCEPTED')
const header = computed(() => order.value && visit.value && delivery.value
  ? describeDeliveryHeader(order.value, visit.value, delivery.value)
  : null)
const acceptanceBanner = computed(() => visit.value && delivery.value
  ? describeAcceptanceBanner(visit.value, delivery.value)
  : null)
const allLinkText = computed(() => {
  const expiresAt = linkExpiries.value[ALL_FILES_LINK]
  return expiresAt ? `全部文件链接已生成，${formatClock(expiresAt)} 前有效` : ''
})
const retentionText = computed(() => {
  const days = plan.value?.deliveryTerms?.mediaRetentionDays
  return days ? `影像存档按交付约定保存 ${days} 天` : ''
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
  routeVisitId.value = query?.visitId?.trim() ?? ''
  if (!serviceOrderId.value) {
    isLoading.value = false
    loadError.value = '未找到报告交付记录'
    return
  }
  void reload()
})

onBackPress((event) => {
  if (event.from === 'navigateBack') return false
  if (isSheetOpen.value) {
    isSheetOpen.value = false
    return true
  }
  returnToFulfillment()
  return true
})

async function reload(): Promise<void> {
  if (!serviceOrderId.value) return
  isLoading.value = true
  loadError.value = ''
  try {
    const currentOrder = await inspectionPlanApi.getServiceOrder(serviceOrderId.value)
    const currentVisit = resolveDeliveryVisit(currentOrder.visits, routeVisitId.value)
    if (!currentVisit) {
      loadError.value = '未找到报告交付记录'
      return
    }
    // A link without a visit stays on the visit it opened, even once that is accepted.
    routeVisitId.value = currentVisit.id
    const [currentDelivery, currentPlan] = await Promise.all([
      inspectionPlanApi.getDelivery(serviceOrderId.value, currentVisit.id),
      // The plan only adds the retention note, so the page opens without it.
      inspectionPlanApi.getPlan(currentOrder.planId).catch(() => null),
    ])
    order.value = currentOrder
    delivery.value = currentDelivery
    plan.value = currentPlan
  } catch (error) {
    loadError.value = messageOf(error, '报告交付加载失败')
  } finally {
    isLoading.value = false
  }
}

function rememberLink(key: string, intent: InspectionDownloadIntent): InspectionDownloadIntent {
  linkExpiries.value = { ...linkExpiries.value, [key]: intent.expiresAt }
  return intent
}

function createFileIntent(file: DeliveryFile): Promise<InspectionDownloadIntent> {
  return inspectionPlanApi.createDownloadIntent(
    serviceOrderId.value,
    delivery.value?.visitId ?? '',
    file.fileId,
    createBookingUuid(),
  ).then((intent) => rememberLink(file.fileId, intent))
}

function findFile(fileId: string): DeliveryFile | undefined {
  return delivery.value?.files.find((file) => file.fileId === fileId)
}

async function saveFile(fileId: string): Promise<void> {
  const file = findFile(fileId)
  if (!file || activeFileAction.value) return
  activeFileAction.value = `${file.fileId}:SAVE`
  actionError.value = ''
  try {
    const outcome = await executeDeliverySaveAction(
      () => createFileIntent(file),
      file.type,
      deliveryFileRuntime,
    )
    if (outcome.status === 'CANCELLED') {
      uni.showToast({ title: '已取消保存', icon: 'none' })
      return
    }
    showSavedName(outcome.savedName)
  } catch (error) {
    actionError.value = messageOf(error, '文件处理失败，请重试')
  } finally {
    activeFileAction.value = ''
  }
}

async function previewFile(fileId: string): Promise<void> {
  const file = findFile(fileId)
  if (!file || activeFileAction.value) return
  activeFileAction.value = `${file.fileId}:PREVIEW`
  actionError.value = ''
  try {
    await executeDeliveryFileAction(
      await createFileIntent(file),
      'PREVIEW',
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
  if (activeFileAction.value || !delivery.value) return
  const visitId = delivery.value.visitId
  activeFileAction.value = 'all:SAVE'
  actionError.value = ''
  try {
    const outcome = await executeDeliverySaveAction(
      () => inspectionPlanApi.createDownloadAllIntent(
        serviceOrderId.value,
        visitId,
        createBookingUuid(),
      ).then((intent) => rememberLink(ALL_FILES_LINK, intent)),
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
  if (!delivery.value || !visit.value || isAccepted.value || isAccepting.value) return
  isAccepting.value = true
  actionError.value = ''
  try {
    await acceptanceCoordinator.accept(
      inspectionPlanApi.acceptDelivery,
      serviceOrderId.value,
      visit.value.id,
      visit.value.version,
    )
    await reload()
  } catch (error) {
    const acceptanceError = messageOf(error, '验收结果未确认，请重试')
    await reload()
    // The response can be lost after the acceptance landed; the reload shows which it was.
    if (!isAccepted.value) actionError.value = acceptanceError
  } finally {
    isAccepting.value = false
    isSheetOpen.value = false
  }
}

function returnToFulfillment(): void {
  const previousRoute = getPreviousInspectionPageRoute(getCurrentPages())
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
// 对报告有疑问 (step G1, preview builds): the platform support, which helps both sides talk, with a
// card about this service order. Public builds keep the notice.
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
</script>

<template>
  <view class="delivery">
    <view class="delivery__header">
      <button class="delivery__back" aria-label="返回" @click="returnToFulfillment">
        <UniIcons type="back" size="22" color="#191919" />
      </button>
      <text class="delivery__heading">交付与验收</text>
    </view>

    <view v-if="isLoading && !delivery" class="delivery__state">正在恢复报告交付记录…</view>
    <view v-else-if="loadError" class="delivery__state">
      <UniIcons type="info-filled" size="40" color="#b5bac1" />
      <text>{{ loadError }}</text>
      <button class="delivery__retry" @click="reload">重新加载</button>
    </view>

    <main v-else-if="delivery && header" class="delivery__content">
      <view class="delivery__summary">
        <text class="delivery__context">{{ header.contextText }}</text>
        <view class="delivery__title-line">
          <text class="delivery__title">{{ header.title }}</text>
          <text
            class="delivery__chip"
            :class="{ 'delivery__chip--accepted': header.status.isAccepted }"
          >{{ header.status.label }}</text>
        </view>
        <text class="delivery__delivered">{{ header.deliveredText }}</text>
      </view>

      <view v-if="acceptanceBanner" class="delivery__accepted" role="status">
        <view class="delivery__accepted-icon">
          <UniIcons type="checkmarkempty" size="18" color="#ffffff" />
        </view>
        <view class="delivery__accepted-copy">
          <text class="delivery__accepted-title">{{ acceptanceBanner.title }}</text>
          <text class="delivery__accepted-text">{{ acceptanceBanner.text }}</text>
        </view>
      </view>

      <DeliveryResultCard
        :tiles="toResultTiles(delivery)"
        :issues="toIssueRows(delivery)"
      />
      <DeliveryFilesCard
        :rows="toFileRows(delivery, linkExpiries)"
        :all-link-text="allLinkText"
        :active-action="activeFileAction"
        :retention-text="retentionText"
        @preview="previewFile"
        @save="saveFile"
        @save-all="downloadAll"
      />

      <text v-if="actionError" class="delivery__error">{{ actionError }}</text>
    </main>

    <view
      v-if="delivery && !isAccepted && !loadError"
      class="delivery__footer"
    >
      <button class="delivery__question" @click="showCustomerService">对报告有疑问</button>
      <button class="delivery__accept" @click="isSheetOpen = true">确认验收</button>
    </view>

    <AcceptanceConfirmSheet
      v-if="isSheetOpen && delivery && order && visit"
      :sheet="describeAcceptanceSheet(order, visit, delivery)"
      :is-accepting="isAccepting"
      @close="isSheetOpen = false"
      @confirm="acceptDelivery"
    />
  </view>
</template>

<style scoped>
.delivery {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #191919;
  background: #f5f7fc;
}

.delivery__header {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  padding: calc(8px + var(--status-bar-height)) 8px 8px;
  align-items: center;
  gap: 4px;
  /* The page-head gradient of the 2026-11-14 prototype's light theme. */
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.delivery__back {
  display: flex;
  width: 44px;
  height: 44px;
  margin: 0;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
}

.delivery__back::after,
.delivery__retry::after,
.delivery__question::after,
.delivery__accept::after {
  border: 0;
}

.delivery__heading {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.delivery__state {
  display: flex;
  min-height: 360px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  color: #7e858e;
  font-size: 14px;
}

.delivery__retry {
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

.delivery__content {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
  box-sizing: border-box;
}

.delivery__summary {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 4px;
  border-radius: 14px;
  background: #fff;
}

.delivery__context {
  color: #666b73;
  font-size: 13px;
  line-height: 18px;
}

.delivery__title-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.delivery__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  white-space: nowrap;
}

.delivery__chip {
  flex-shrink: 0;
  margin-left: auto;
  padding: 2px 8px;
  border-radius: 6px;
  color: #d94f00;
  background: #fff3e8;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.delivery__chip--accepted {
  color: #11804a;
  background: #e8f7ef;
}

.delivery__delivered {
  color: #555a62;
  font-size: 13px;
  line-height: 18px;
}

.delivery__accepted {
  display: flex;
  padding: 12px 14px;
  align-items: center;
  gap: 10px;
  border-radius: 14px;
  background: #e8f7ef;
}

.delivery__accepted-icon {
  display: flex;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #19b866;
}

.delivery__accepted-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  color: #11804a;
}

.delivery__accepted-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.delivery__accepted-text {
  font-size: 12px;
  line-height: 17px;
}

.delivery__error {
  color: #d73928;
  font-size: 13px;
  line-height: 19px;
}

.delivery__footer {
  position: sticky;
  bottom: 0;
  z-index: 2;
  display: flex;
  padding: 10px 16px max(24px, env(safe-area-inset-bottom));
  gap: 10px;
  border-top: 1px solid #ebedf0;
  background: #fff;
}

.delivery__question,
.delivery__accept {
  height: 46px;
  flex: 1;
  min-height: 44px;
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
  white-space: nowrap;
}

.delivery__question {
  border: 1px solid #c4c8ce;
  color: #191919;
  background: #fff;
}

.delivery__accept {
  border: 0;
  color: #fff;
  background: #ff6a00;
  font-weight: 500;
  line-height: 46px;
}
</style>
