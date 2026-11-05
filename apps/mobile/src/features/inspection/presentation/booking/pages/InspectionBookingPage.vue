<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { storeToRefs } from 'pinia'

import { submitBookingReservation, createBookingSubmissionSession, uploadReservationAttachments } from '../../../application/service-flow/submit-booking-reservation'
import { createBookingFileHashPort, createBookingUuid } from '../../../application/service-flow/booking-crypto'
import { createBookingImageAttachments, pickBookingPdfAttachments } from '../../../application/service-flow/booking-attachment-selection'
import { uniBookingFileAccessPort } from '../../../data/service-flow/booking-file-access'
import { createUniBookingDocumentPicker, uniBookingDocumentPicker } from '../../../data/service-flow/uni-booking-document-picker'
import { ApiRequestError, inspectionBookingApi, platformCityApi } from '../../../data/service-flow/uni-booking-http-client'
import { createBookingLocationSelection, createEmptyBookingDraft, type BookingAttachment, type BookingServiceSnapshot, type BookingTimeSelection } from '../../../domain/service-flow/booking'
import { buildInspectionBookingSubmittedUrl, buildInspectionCatalogUrl, buildInspectionDetailUrl } from '../../../navigation/inspection-routes'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import BookingServiceSnapshotCard from '../components/BookingServiceSnapshotCard.vue'
import InspectionTimePickerSheet from '../components/InspectionTimePickerSheet.vue'

// #ifdef APP-PLUS
// @ts-ignore The UTS module resolves its native implementation for each App platform.
import { pickInspectionPdfDocuments, releaseInspectionPdfDocuments } from '@/uni_modules/inspection-document-picker'
// #endif

interface BookingRouteQuery {
  serviceId?: string
  cityCode?: string
}

const SERVICE_UNAVAILABLE_TEXT = '当前城市暂无这项服务，或服务已下架'

const currentCityStore = useCurrentCityStore()
const { currentCity } = storeToRefs(currentCityStore)
const service = ref<BookingServiceSnapshot | null>(null)
const draft = ref(createEmptyBookingDraft())
const isLoading = ref(true)
const isSubmitting = ref(false)
const isChoosingLocation = ref(false)
const loadError = ref('')
const canRetryLoad = ref(false)
const submitError = ref('')
const isTimePickerVisible = ref(false)
const preferredServiceTimeText = ref('')
const submission = createBookingSubmissionSession()
const bookingFileHashPort = createBookingFileHashPort(uniBookingFileAccessPort)
const releasedPdfPaths = new Set<string>()
let cleanupRequested = false
let isPageActive = false
let reloadService = (): void => undefined
let bookingDocumentPicker = uniBookingDocumentPicker
// #ifdef APP-PLUS
bookingDocumentPicker = createUniBookingDocumentPicker(
  undefined,
  pickInspectionPdfDocuments,
  releaseInspectionPdfDocuments,
)
// #endif

const availableItems = computed(() => service.value?.inspectionItems ?? [])
const canAddAttachment = computed(
  () => draft.value.attachments.length < 9,
)

onLoad((query: BookingRouteQuery | undefined) => {
  isPageActive = true
  const serviceId = query?.serviceId?.trim() ?? ''
  if (!serviceId) {
    isLoading.value = false
    loadError.value = '未找到要预约的巡检服务'
    return
  }

  const cityCode = query?.cityCode?.trim()
  if (cityCode && !currentCity.value) void restoreRouteCity(cityCode)
  reloadService = () => void loadService(serviceId, cityCode)
  reloadService()
})

onUnload(() => {
  isPageActive = false
  cleanupRequested = true
  releasePdfAttachmentsWhenSafe()
})

// The URL carries a code; the name comes from the directory. Without a match the
// store stays empty, and this page keeps working from service.cityCode.
async function restoreRouteCity(cityCode: string): Promise<void> {
  const directory = await platformCityApi.load().catch(() => undefined)
  const city = directory?.provinces
    .flatMap((province) => province.cities)
    .find(({ code }) => code === cityCode)
  if (!city || currentCity.value) return
  const { code, name, provinceCode } = city
  currentCityStore.setCurrentCity({ code, name, provinceCode })
}

async function loadService(
  serviceId: string,
  routeCityCode?: string,
): Promise<void> {
  // A city already in the store is the user's latest choice, so the route only
  // fills an empty store.
  const cityCode = currentCity.value?.code ?? routeCityCode
  if (!cityCode) {
    isLoading.value = false
    loadError.value = '请从巡检服务页面进入并选择服务城市'
    return
  }

  isLoading.value = true
  loadError.value = ''
  canRetryLoad.value = false
  try {
    service.value = await inspectionBookingApi.loadServiceSnapshot(
      serviceId,
      cityCode,
    )
    if (!service.value) loadError.value = SERVICE_UNAVAILABLE_TEXT
  } catch (error) {
    // 400 and 404 mean this city cannot offer the service, and asking again cannot
    // get past 401 or 403. Network, 5xx and unreadable replies can still recover.
    canRetryLoad.value = !(error instanceof ApiRequestError && [400, 401, 403, 404].includes(error.status))
    loadError.value = canRetryLoad.value
      ? '服务信息加载失败，请检查网络后重试'
      : SERVICE_UNAVAILABLE_TEXT
  } finally {
    isLoading.value = false
  }
}

function recoverFromLoadError(): void {
  if (canRetryLoad.value) {
    reloadService()
    return
  }

  // M03 sits right behind this page and would offer the same service again, so go
  // past it to where the user picked the service. With nothing there, open the list.
  if (getCurrentPages().length > 2) uni.navigateBack({ delta: 2 })
  else uni.redirectTo({ url: buildInspectionCatalogUrl() })
}

function toggleItem(code: string): void {
  const selected = draft.value.inspectionItemCodes
  draft.value.inspectionItemCodes = selected.includes(code)
    ? selected.filter((item) => item !== code)
    : [...selected, code]
}

function itemIcon(code: string): string {
  return {
    POWER_LINE: 'paperplane-filled',
    TOWER: 'flag-filled',
    INSULATOR_FITTINGS: 'settings-filled',
    CORRIDOR_ENV: 'map-filled',
  }[code] ?? 'navigate-filled'
}

function chooseInspectionLocation(): void {
  if (isChoosingLocation.value) {
    return
  }

  isChoosingLocation.value = true
  uni.chooseLocation({
    success: (result) => {
      try {
        const location = createBookingLocationSelection(result)
        draft.value.locationAddress = location.address
        draft.value.locationLongitude = location.longitude
        draft.value.locationLatitude = location.latitude
      } catch {
        showLocationPickerError()
      }
    },
    fail: ({ errMsg }) => {
      if (!errMsg.toLowerCase().includes('cancel')) {
        showLocationPickerError()
      }
    },
    complete: () => {
      isChoosingLocation.value = false
    },
  })
}

function showLocationPickerError(): void {
  uni.showModal({
    title: '无法选择巡检地点',
    content: '请开启手机定位并授予应用位置权限后重试；如仍无法使用，请联系客服。',
    showCancel: false,
  })
}

function chooseAttachments(): void {
  if (!canAddAttachment.value) {
    return
  }

  uni.showActionSheet({ itemList: ['拍照或选择图片', '选择 PDF 文件'], success: ({ tapIndex }) => {
    if (tapIndex === 0) chooseImages()
    else choosePdfFiles()
  } })
}

function chooseImages(): void {
  uni.chooseMedia({
    count: 9 - draft.value.attachments.length,
    mediaType: ['image'],
    sourceType: ['album', 'camera'],
    success: ({ tempFiles }) => {
      const files = Array.isArray(tempFiles) ? tempFiles : [tempFiles]
      try {
        draft.value.attachments.push(...createBookingImageAttachments(files, {
          count: 9 - draft.value.attachments.length,
          createLocalId: () => createBookingUuid(),
        }))
      } catch (error) {
        showImagePickerError(error)
      }
    },
  })
}

function showImagePickerError(error: unknown): void {
  uni.showModal({
    title: '无法添加图片',
    content: error instanceof Error ? error.message : '图片无法读取，请重新选择',
    showCancel: false,
  })
}

async function choosePdfFiles(): Promise<void> {
  try {
    const attachments = await pickBookingPdfAttachments({
      picker: bookingDocumentPicker,
      count: 9 - draft.value.attachments.length,
      isPageActive: () => isPageActive,
      createLocalId: () => createBookingUuid(),
    })
    draft.value.attachments.push(...attachments)
  } catch (error) {
    showDocumentPickerError(error)
  }
}

function showDocumentPickerError(error: unknown): void {
  uni.showModal({
    title: '无法选择 PDF 文件',
    content: error instanceof Error ? error.message : '无法打开 PDF 文件选择器，请稍后重试',
    showCancel: false,
  })
}

function retryAttachment(attachment: BookingAttachment): void {
  attachment.status = 'PENDING_UPLOAD'
  attachment.errorMessage = undefined
}

function removeAttachment(localId: string): void {
  if (isSubmitting.value) {
    return
  }
  const attachment = draft.value.attachments.find(
    (candidate) => candidate.localId === localId,
  )
  if (attachment) {
    releasePdfAttachments([attachment])
  }
  draft.value.attachments = draft.value.attachments.filter(
    (attachment) => attachment.localId !== localId,
  )
}

function releasePdfAttachments(attachments: readonly BookingAttachment[]): void {
  const filePaths = attachments
    .filter((attachment) => attachment.contentType === 'application/pdf')
    .map((attachment) => attachment.filePath.trim())
    .filter((filePath) => filePath && !releasedPdfPaths.has(filePath))

  if (filePaths.length === 0) {
    return
  }
  filePaths.forEach((filePath) => releasedPdfPaths.add(filePath))
  bookingDocumentPicker.releasePdfFiles(filePaths)
}

function releasePdfAttachmentsWhenSafe(): void {
  if (!cleanupRequested || isSubmitting.value) {
    return
  }
  releasePdfAttachments(draft.value.attachments)
  cleanupRequested = false
}

function openTimePicker(): void {
  if (!service.value) {
    return
  }
  isTimePickerVisible.value = true
}

function confirmPreferredTime(
  selection: BookingTimeSelection,
  displayText: string,
): void {
  draft.value.preferredServiceTime = selection
  preferredServiceTimeText.value = displayText
  isTimePickerVisible.value = false
}

async function submit(): Promise<void> {
  if (isSubmitting.value || !service.value) {
    return
  }

  isSubmitting.value = true
  submitError.value = ''
  try {
    const result = await submitBookingReservation(
      inspectionBookingApi,
      submission,
      service.value,
      draft.value,
    )
    const attachmentsReady = await uploadReservationAttachments(
      inspectionBookingApi,
      submission,
      draft.value,
      bookingFileHashPort,
    )
    if (!attachmentsReady) {
      submitError.value = '附件仍在扫描或上传失败，请重试后再提交'
      return
    }
    releasePdfAttachments(draft.value.attachments)
    uni.redirectTo({ url: buildInspectionBookingSubmittedUrl(result.reservationId) })
  } catch (error) {
    if (
      error instanceof ApiRequestError
      && error.code === 'INS_TIME_SLOT_FULL'
    ) {
      submitError.value = '该时段刚刚约满，请重新选择'
      isTimePickerVisible.value = true
      return
    }
    if (
      error instanceof ApiRequestError
      && error.code === 'INS_TIME_SLOT_TOO_SOON'
    ) {
      draft.value.preferredServiceTime = null
      preferredServiceTimeText.value = ''
      submitError.value = '该时段需提前1小时预约，请重新选择'
      isTimePickerVisible.value = true
      return
    }
    submitError.value = error instanceof Error ? error.message : '预约提交失败，请重试'
  } finally {
    isSubmitting.value = false
    releasePdfAttachmentsWhenSafe()
  }
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }

  // After a deep link or a refresh this page is alone in the stack, where
  // navigateBack only reloads it. M03 is the only way in; without a loaded service
  // it has nothing to show, so go to the list.
  uni.redirectTo({
    url: service.value
      ? buildInspectionDetailUrl(service.value.serviceId, service.value.cityCode)
      : buildInspectionCatalogUrl(),
  })
}
function showCustomerService(): void { uni.showToast({ title: '客服能力由平台统一接入', icon: 'none' }) }

</script>

<template>
  <view class="inspection-booking">
    <view class="inspection-booking__header">
      <button class="inspection-booking__back" aria-label="返回" @click="goBack"><UniIcons type="back" size="22" color="#25282d" /></button>
      <text class="inspection-booking__title">确认订单</text>
      <button class="inspection-booking__customer" aria-label="联系客服" @click="showCustomerService"><UniIcons type="headphones" size="20" color="#25282d" /><text>客服</text></button>
    </view>
    <scroll-view scroll-y class="inspection-booking__scroll" :show-scrollbar="false">
      <view v-if="isLoading" class="booking-state"><text>正在加载服务信息…</text></view>
      <view v-else-if="loadError" class="booking-state">
        <UniIcons type="info-filled" size="40" color="#b5bac1" />
        <text>{{ loadError }}</text>
        <button class="booking-state__action" @click="recoverFromLoadError">{{ canRetryLoad ? '重新加载' : '看看其他服务' }}</button>
      </view>
      <main v-else-if="service" class="inspection-booking__content">
        <BookingServiceSnapshotCard :service="service" />
        <view class="booking-section">
          <text class="booking-section__title">预约信息</text>
          <view class="booking-row booking-row--selectable" @tap="chooseInspectionLocation">
            <UniIcons type="location-filled" size="19" color="#ff6a00" />
            <text class="booking-row__label">巡检地点</text>
            <text :class="['booking-row__location', { 'booking-row__location--empty': !draft.locationAddress }]">{{ draft.locationAddress || (isChoosingLocation ? '正在打开地图…' : '请选择巡检地点') }}</text>
            <UniIcons type="right" size="15" color="#8e949c" />
          </view>
          <view class="booking-row">
            <UniIcons type="paperplane-filled" size="18" color="#ff6a00" />
            <text class="booking-row__label">线路长度</text>
            <input v-model="draft.lineLengthKm" class="booking-row__input" type="digit" placeholder="请输入预计公里数" />
            <text class="booking-row__suffix">公里</text>
          </view>
          <view class="booking-row booking-row--selectable booking-row--time" @tap="openTimePicker">
            <UniIcons type="calendar" size="19" color="#ff6a00" />
            <text class="booking-row__label">期望时间</text>
            <text
              :class="[
                'booking-row__date',
                { 'booking-row__date--empty': !preferredServiceTimeText },
              ]"
            >
              {{ preferredServiceTimeText || '请选择服务时间' }}
            </text>
            <UniIcons type="right" size="15" color="#8e949c" />
          </view>
        </view>
        <view class="booking-section">
          <text class="booking-section__title">巡检项目（可多选）</text>
          <view class="booking-items">
            <button v-for="item in availableItems" :key="item.code" :class="['booking-item', { 'booking-item--selected': draft.inspectionItemCodes.includes(item.code) }]" @click="toggleItem(item.code)">
              <UniIcons :type="itemIcon(item.code)" size="20" :color="draft.inspectionItemCodes.includes(item.code) ? '#ff6500' : '#18a84f'" />
              <text>{{ item.label }}</text>
              <UniIcons :type="draft.inspectionItemCodes.includes(item.code) ? 'checkbox-filled' : 'circle'" size="18" :color="draft.inspectionItemCodes.includes(item.code) ? '#ff6500' : '#c6cbd1'" />
            </button>
          </view>
        </view>
        <view class="booking-section">
          <text class="booking-section__title">现场资料</text>
          <view class="booking-attachments">
            <view v-for="attachment in draft.attachments" :key="attachment.localId" class="booking-attachment">
              <text class="booking-attachment__name">{{ attachment.originalName }}</text>
              <text class="booking-attachment__status">{{ attachment.status === 'PENDING_UPLOAD' ? '已选择，提交时上传' : attachment.status === 'FAILED' ? attachment.errorMessage : attachment.status === 'SCANNING' ? '安全扫描中' : attachment.status === 'READY' ? '已完成' : '上传中' }}</text>
              <button v-if="attachment.status === 'FAILED'" class="booking-attachment__action" @click="retryAttachment(attachment)">重试</button>
              <button class="booking-attachment__action" @click="removeAttachment(attachment.localId)">移除</button>
            </view>
            <button v-if="canAddAttachment" class="booking-attachment-add" @click="chooseAttachments"><UniIcons type="cloud-upload" size="27" color="#8c939c" /><view><text>上传线路图或现场照片</text><text>支持图片、PDF，最多9个文件</text></view></button>
          </view>
        </view>
        <view class="booking-section"><text class="booking-section__title">补充说明</text><textarea v-model="draft.note" class="booking-note" placeholder="请补充线路情况、现场环境或其他需求（选填）" maxlength="1000" /></view>
        <view class="booking-section booking-section--contact">
          <text class="booking-section__title">联系人信息</text>
          <view class="booking-row"><UniIcons type="person-filled" size="15" color="#ff6a00" /><text class="booking-row__label">联系人</text><input v-model="draft.contactName" class="booking-row__input" placeholder="请输入联系人姓名" maxlength="40" /></view>
          <view class="booking-row"><UniIcons type="phone-filled" size="15" color="#ff6a00" /><text class="booking-row__label">手机号</text><input v-model="draft.contactMobile" class="booking-row__input" type="number" placeholder="请输入联系人手机号" maxlength="11" /></view>
        </view>
        <text v-if="submitError" class="booking-submit-error">{{ submitError }}</text>
      </main>
    </scroll-view>
    <view v-if="service" class="inspection-booking__footer">
      <view class="inspection-booking__tip"><UniIcons type="shield-filled" size="17" color="#20ad56" /><text>平台保障 · 方案确认前不收取费用</text></view>
      <label class="booking-agreement"><checkbox :checked="draft.agreementAccepted" color="#ff6a00" @click="draft.agreementAccepted = !draft.agreementAccepted" /><text>我已阅读并同意 <text class="booking-agreement__link">《服务协议》《隐私政策》</text></text></label>
      <button class="inspection-booking__submit" :loading="isSubmitting" :disabled="isSubmitting" @click="submit">提交订单</button>
    </view>
    <InspectionTimePickerSheet
      v-if="service"
      :visible="isTimePickerVisible"
      :service-id="service.serviceId"
      :city-code="service.cityCode"
      :model-value="draft.preferredServiceTime"
      :load-slots="inspectionBookingApi.loadAvailableTimeSlots"
      @close="isTimePickerVisible = false"
      @confirm="confirmPreferredTime"
    />
  </view>
</template>

<style scoped>
.inspection-booking { min-height: 100vh; color: #25282d; background: #f7f8fa; box-sizing: border-box; }
.inspection-booking__header { display: grid; min-height: calc(44px + env(safe-area-inset-top)); padding-top: env(safe-area-inset-top); grid-template-columns: 44px 1fr 64px; align-items: center; background: #fff; box-sizing: border-box; }
.inspection-booking__back,.inspection-booking__customer { height: 42px; margin: 0; padding: 0; border: 0; background: transparent; line-height: 42px; }.inspection-booking__back{width:44px}.inspection-booking__customer{display:flex;width:64px;align-items:center;justify-content:center;gap:3px;font-size:11px}.inspection-booking__back::after,.inspection-booking__customer::after,.inspection-booking__submit::after,.booking-item::after,.booking-attachment-add::after,.booking-attachment__action::after{border:0}.inspection-booking__title{text-align:center;font-size:16px;font-weight:700}.inspection-booking :deep(.booking-progress){padding:4px 24px 1px}.inspection-booking :deep(.booking-progress__label){margin-top:3px}.inspection-booking__scroll{height:calc(100vh - 172px - env(safe-area-inset-top) - env(safe-area-inset-bottom));}.inspection-booking__content{display:flex;max-width:600px;margin:0 auto;padding:4px 8px 6px;flex-direction:column;gap:4px;box-sizing:border-box}.inspection-booking :deep(.booking-service-card){grid-template-columns:90px minmax(0,1fr);min-height:90px;padding:6px}.inspection-booking :deep(.booking-service-card__image){width:90px;height:90px}.inspection-booking :deep(.booking-service-card__copy){padding-left:9px}.booking-section{padding:4px 10px;border:1px solid #eceef1;border-radius:12px;background:#fff}.booking-section__title{display:block;margin-bottom:2px;color:#25282d;font-size:13px;font-weight:700}.booking-row{display:grid;min-height:27px;grid-template-columns:22px 67px minmax(0,1fr) auto;align-items:center;border-bottom:1px solid #eceef1;color:#24282e;font-size:11px}.booking-row:last-child{border-bottom:0}.booking-row--selectable{cursor:pointer}.booking-row--time{min-height:44px}.booking-section--contact .booking-row{min-height:21px}.booking-section--contact .booking-row__input{height:21px}.booking-row__label{font-weight:600}.booking-row__input{width:100%;height:27px;color:#25282d;font-size:11px}.booking-row__date,.booking-row__location{overflow:hidden;color:#25282d;text-overflow:ellipsis;white-space:nowrap}.booking-row__date--empty,.booking-row__location--empty,.booking-row__input::placeholder{color:#a4a9b0}.booking-row__suffix{color:#848b94}.booking-note{width:100%;height:42px;margin-top:3px;padding:5px 7px;border:1px solid #eceef1;border-radius:7px;background:#fff;box-sizing:border-box;color:#25282d;font-size:10px}.booking-items{display:grid;margin-top:3px;grid-template-columns:repeat(2,minmax(0,1fr));gap:4px}.booking-item{display:grid;min-height:38px;margin:0;padding:0 5px;grid-template-columns:22px minmax(0,1fr) 19px;align-items:center;border:1px solid #dfe3e7;border-radius:7px;color:#3f454d;background:#fff;font-size:10px;line-height:1.2}.booking-item--selected{border-color:#ff6a00;color:#25282d;background:#fff8f3}.booking-attachments{display:flex;margin-top:3px;gap:4px;flex-wrap:wrap}.booking-attachment{display:flex;width:86px;height:44px;min-height:44px;padding:4px;align-items:center;justify-content:center;flex-direction:column;border:1px solid #eceef1;border-radius:7px;background:#fafbfc;box-sizing:border-box;overflow:hidden}.booking-attachment-add{display:flex;width:100%;height:44px;min-height:44px;margin:0;padding:0 10px;align-items:center;justify-content:center;gap:8px;border:1px dashed #c7ccd2;border-radius:7px;color:#4c535c;background:#fff;font-size:10px;line-height:1.2}.booking-attachment-add view{display:flex;align-items:flex-start;flex-direction:column}.booking-attachment-add view text:last-child{margin-top:1px;color:#969ca4;font-size:9px}.booking-attachment__name{width:100%;overflow:hidden;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.booking-attachment__status{margin-top:2px;color:#9298a0;font-size:8px;text-align:center}.booking-attachment__action{margin:2px 0 0;padding:0;border:0;color:#ff6a00;background:transparent;font-size:8px}.booking-agreement{display:flex;height:21px;margin-top:1px;align-items:center;gap:3px;color:#707780;font-size:9px}.booking-agreement :deep(.uni-checkbox-input){width:15px;height:15px;margin-right:2px}.booking-agreement__link{color:#ff5a00}.booking-submit-error{padding:0 4px;color:#df3e21;font-size:12px}.inspection-booking__footer{position:fixed;right:0;bottom:0;left:0;padding:0 8px calc(2px + env(safe-area-inset-bottom));background:rgba(255,255,255,.99);box-shadow:0 -3px 14px rgba(22,30,40,.08)}.inspection-booking__tip{display:flex;margin:0 -8px;padding:3px 12px;align-items:center;gap:5px;color:#a65c2e;background:#fff5ea;font-size:9px}.inspection-booking__submit{display:block;width:min(100%,584px);height:32px;margin:1px auto 0;border:0;border-radius:17px;color:#fff;background:linear-gradient(90deg,#ff8a00,#ff4d00);font-size:14px;font-weight:700;line-height:32px}.booking-state{display:flex;min-height:340px;align-items:center;justify-content:center;flex-direction:column;gap:12px;color:#7e858e;font-size:14px;text-align:center}
.booking-section--contact{padding-top:2px;padding-bottom:2px}.booking-section--contact .booking-section__title{margin-bottom:0}.booking-section--contact .booking-row{min-height:20px}.booking-section--contact .booking-row__input{height:20px}

/* Candidate visual shell: preserve the reservation logic, expand it into the
   approved confirm-order hierarchy and mobile touch sizes. */
.inspection-booking__header{min-height:calc(52px + var(--status-bar-height));padding-top:var(--status-bar-height);grid-template-columns:52px 1fr 64px}
.inspection-booking__title{font-size:18px}
.inspection-booking__scroll{height:calc(100vh - 142px - var(--status-bar-height) - env(safe-area-inset-bottom))}
.inspection-booking__content{padding:12px 14px 160px;gap:12px}
.inspection-booking :deep(.booking-service-card){grid-template-columns:126px minmax(0,1fr);min-height:140px;padding:12px;border:0;border-radius:20px;box-shadow:0 8px 28px rgba(35,45,55,.07)}
.inspection-booking :deep(.booking-service-card__image){width:126px;height:116px}
.inspection-booking :deep(.booking-service-card__copy){padding-left:14px}
.booking-section{padding:17px 16px;border:0;border-radius:20px;box-shadow:0 8px 28px rgba(35,45,55,.06)}
.booking-section__title{margin-bottom:8px;font-size:17px}
.booking-row,.booking-section--contact .booking-row{min-height:50px;grid-template-columns:26px 78px minmax(0,1fr) auto;font-size:13px}
.booking-row__input,.booking-section--contact .booking-row__input{height:48px;font-size:13px}
.booking-row__date,.booking-row__location{text-align:right}
.booking-note{height:82px;margin-top:7px;padding:10px;border-radius:12px;background:#fafbfc;font-size:13px}
.booking-items{margin-top:8px;gap:8px}
.booking-item{min-height:52px;padding:0 9px;border-radius:12px;font-size:12px}
.booking-attachments{margin-top:8px;gap:8px}
.booking-attachment{width:104px;height:auto;min-height:68px;border-radius:10px}
.booking-attachment-add{height:68px;min-height:68px;border-radius:12px;font-size:12px}
.inspection-booking__price-summary{padding:16px;border-radius:20px;background:#fff;box-shadow:0 8px 28px rgba(35,45,55,.06)}
.inspection-booking__price-summary>view{display:flex;min-height:34px;align-items:center;justify-content:space-between;color:#646b74;font-size:13px}
.inspection-booking__price-summary>view text:last-child{color:#25282d}
.inspection-booking__price-total{margin-top:6px;padding-top:10px;border-top:1px solid #eceef1;color:#17191d!important;font-size:16px!important;font-weight:700}
.inspection-booking__price-total text:last-child{color:#ff5a00!important;font-size:22px}
.inspection-booking__footer{padding:0 14px calc(8px + env(safe-area-inset-bottom))}
.inspection-booking__tip{margin:0 -14px;padding:7px 16px;justify-content:center;color:#5f6b65;background:#f3fbf5;font-size:10px}
.booking-agreement{height:30px;font-size:10px}
.inspection-booking__submit{height:48px;margin-top:2px;border-radius:16px;font-size:16px;line-height:48px}
.booking-state__action{height:44px;margin-top:6px;padding:0 28px;border:0;border-radius:22px;color:#fff;background:#ff5a00;font-size:14px;line-height:44px}
.booking-state__action::after{border:0}
</style>
