<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { openSupportChat } from '@/features/support/application/support-entry'
import { ApiRequestError, resolvePublicApiUrl } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { resolveServiceOrderDownloadUrl } from '../../application/service-order-download'
import {
  SERVICE_ORDER_FILE_ICONS,
  formatFileSize,
  formatServiceClock,
  serviceOrderFileKind,
} from '../../application/service-order-format'
import {
  SERVICE_REWORK_REASON_MAX,
  describeServiceDeliveryHead,
  describeServiceOrderFailure,
  latestDelivery,
  serviceAcceptRows,
  serviceAcceptedLine,
  serviceArchiveNote,
  serviceDeliveryHistory,
  serviceDeliveryReviewable,
} from '../../application/service-order-view'
import { serviceOrderApi } from '../../data/service-order-api-client'
import { openServiceOrderFile } from '../../data/service-order-file-opener'
import type { ServiceOrderFile } from '../../domain/service-order'
import ServiceOrderNotice from '../shared/ServiceOrderNotice.vue'
import ServiceOrderSheet from '../shared/ServiceOrderSheet.vue'
import { isUnauthorized, useServiceOrder } from '../shared/use-service-order'

const props = defineProps<{
  orderId: string | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  detail: [orderId: string]
}>()

const { state, order, busy, notice, load, run } = useServiceOrder({
  getOrderId: () => props.orderId,
  getAccessToken: () => props.accessToken,
  getRefreshKey: () => props.refreshKey,
  onLogin: () => emit('login'),
})

// The round on screen: the latest by default, an earlier one picked from 历次交付.
const pickedRound = ref<number | null>(null)
const sheet = ref<'ACCEPT' | 'REWORK' | null>(null)
const reason = ref('')
// When each file's short-lived link runs out (DeliveryOne: "下载链接已生成，15:40 前有效").
const links = ref<Record<string, string>>({})
const downloading = ref<string | null>(null)

const delivery = computed(() => {
  const current = order.value
  if (!current) return null
  return current.deliveries.find((item) => item.round === pickedRound.value) ?? latestDelivery(current)
})
const head = computed(() => (order.value && delivery.value ? describeServiceDeliveryHead(order.value, delivery.value) : null))
const history = computed(() => (order.value && order.value.deliveries.length > 1 ? serviceDeliveryHistory(order.value) : []))
const reviewable = computed(() => Boolean(order.value && delivery.value && serviceDeliveryReviewable(order.value, delivery.value)))
const acceptRows = computed(() => (order.value && delivery.value ? serviceAcceptRows(order.value, delivery.value) : []))
const archiveNote = computed(() => (order.value ? serviceArchiveNote(order.value) : null))
const allLinked = computed(() => Boolean(delivery.value?.files.length) && delivery.value!.files.every((file) => links.value[file.fileId]))
const reasonText = computed(() => reason.value.trim())

watch(() => order.value?.deliveries.length, () => { pickedRound.value = null })

function contactProvider(): void {
  const current = order.value
  if (!current) return
  openSupportChat(
    { kind: 'PROVIDER', organizationId: current.provider.providerId },
    current.demandId
      ? { type: 'DEMAND', refId: current.demandId, title: current.title, subtitle: `服务订单 ${current.orderNo}` }
      : null,
  )
}

// 下载: a short-lived link (10 minutes) from the server, opened after the API address; the page
// never sees where the file is stored.
async function downloadOne(file: ServiceOrderFile): Promise<boolean> {
  const token = props.accessToken
  const current = order.value
  if (!token) {
    emit('login')
    return false
  }
  if (!current) return false
  try {
    const intent = await serviceOrderApi.createDownload(token, current.orderId, file.fileId)
    const url = await resolveServiceOrderDownloadUrl(intent, resolvePublicApiUrl)
    links.value = { ...links.value, [file.fileId]: intent.expiresAt }
    openServiceOrderFile(url, file.name)
    return true
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return false
    }
    const failure = describeServiceOrderFailure(error instanceof ApiRequestError
      ? { status: error.status, code: error.code ?? null, message: error.message }
      : { status: -1, code: null, message: error instanceof Error ? error.message : '' })
    notice.value = failure.message
    return false
  }
}

async function download(file: ServiceOrderFile): Promise<void> {
  if (downloading.value) return
  downloading.value = file.fileId
  try {
    await downloadOne(file)
  } finally {
    downloading.value = null
  }
}

// 全部下载: one link per file, one after another; stops at the first that fails.
async function downloadAll(): Promise<void> {
  const files = delivery.value?.files ?? []
  if (downloading.value || !files.length) return
  downloading.value = 'ALL'
  try {
    for (const file of files) {
      if (!(await downloadOne(file))) break
    }
  } finally {
    downloading.value = null
  }
}

async function accept(): Promise<void> {
  const round = delivery.value?.round
  if (!round) return
  await run(
    (token, current) => serviceOrderApi.accept(token, current.orderId, current.version, round),
    `第 ${round} 次交付已验收`,
  )
  sheet.value = null
}

async function sendBack(): Promise<void> {
  const round = delivery.value?.round
  const text = reasonText.value
  if (!round || !text) return
  const done = await run(
    (token, current) => serviceOrderApi.rework(token, current.orderId, current.version, round, text),
    '已退回整改',
  )
  sheet.value = null
  if (done) reason.value = ''
}

function openSheet(kind: 'ACCEPT' | 'REWORK'): void {
  if (!busy.value) sheet.value = kind
}
</script>

<template>
  <view class="so-delivery" data-testid="service-order-delivery" :data-status="order?.status ?? ''">
    <view class="so-delivery__top">
      <AppPageHeader title="交付与验收" test-id="service-order-delivery" @back="emit('back')" />
    </view>

    <view v-if="state.kind === 'loading'" class="so-delivery__body" aria-label="正在加载">
      <view class="so-delivery__skeleton" />
      <view class="so-delivery__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing' || state.kind === 'error'" class="so-delivery__state" data-testid="service-order-delivery-error">
      <text class="so-delivery__state-title">{{ state.kind === 'missing' ? '没有找到这张服务订单' : '加载失败' }}</text>
      <button v-if="state.kind === 'error'" class="so-delivery__state-button" @click="load">重试</button>
      <button v-else class="so-delivery__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="order && !delivery" class="so-delivery__state" data-testid="service-order-delivery-empty">
      <text class="so-delivery__state-title">服务商还没有提交交付</text>
      <text class="so-delivery__state-text">服务完成后，交付说明和文件会在这里</text>
      <button class="so-delivery__state-button" @click="emit('detail', order.orderId)">查看订单</button>
    </view>

    <template v-else-if="order && delivery && head">
      <view class="so-delivery__body">
        <view class="so-delivery__card so-delivery__head" data-testid="service-order-delivery-head" :data-round="delivery.round">
          <text class="so-delivery__subtitle">{{ head.subtitle }}</text>
          <view class="so-delivery__title-line">
            <text class="so-delivery__title" data-testid="service-order-delivery-title">{{ head.title }}</text>
            <text :class="['so-delivery__chip', `so-delivery__chip--${head.tag.tone.toLowerCase()}`]" data-testid="service-order-delivery-chip">
              {{ head.tag.label }}
            </text>
          </view>
          <text class="so-delivery__line">{{ head.line }}</text>
        </view>

        <view v-if="delivery.status === 'ACCEPTED'" class="so-delivery__accepted" role="status" data-testid="service-order-delivery-accepted">
          <view class="so-delivery__accepted-icon">
            <LineIcon name="check" :size="18" color="#ffffff" :stroke-width="3" />
          </view>
          <view class="so-delivery__accepted-copy">
            <text class="so-delivery__accepted-title">第 {{ delivery.round }} 次已验收</text>
            <text class="so-delivery__accepted-text">{{ serviceAcceptedLine(delivery) }}</text>
          </view>
        </view>

        <view v-else-if="delivery.status === 'RETURNED'" class="so-delivery__returned" role="status" data-testid="service-order-delivery-returned">
          <text class="so-delivery__returned-title">第 {{ delivery.round }} 次已退回整改</text>
          <text v-if="delivery.reviewNote" class="so-delivery__returned-text">退回原因：{{ delivery.reviewNote }}</text>
        </view>

        <view class="so-delivery__card so-delivery__note" data-testid="service-order-delivery-note">
          <text class="so-delivery__card-title">交付说明</text>
          <text class="so-delivery__note-text">{{ delivery.note }}</text>
        </view>

        <view class="so-delivery__card so-delivery__files" data-testid="service-order-delivery-files">
          <view class="so-delivery__files-head">
            <view class="so-delivery__files-copy">
              <text class="so-delivery__card-title">交付文件</text>
              <view v-if="allLinked" class="so-delivery__linked" role="status">
                <LineIcon name="check" :size="12" color="#159a70" :stroke-width="3" />
                <text>全部文件链接已生成</text>
              </view>
              <text v-else class="so-delivery__files-count">{{ delivery.files.length ? `共 ${delivery.files.length} 个文件` : '这次交付没有附带文件' }}</text>
            </view>
            <button
              v-if="delivery.files.length > 1"
              class="so-delivery__download"
              data-testid="service-order-download-all"
              :disabled="Boolean(downloading)"
              @click="downloadAll"
            >
              全部下载
            </button>
          </view>
          <view
            v-for="file in delivery.files"
            :key="file.fileId"
            class="so-delivery__file"
            :data-testid="`service-order-file-${file.fileId}`"
          >
            <view class="so-delivery__file-icon">
              <LineIcon :d="SERVICE_ORDER_FILE_ICONS[serviceOrderFileKind(file.contentType, file.name)]" :size="20" color="#2f80ed" :stroke-width="1.8" />
            </view>
            <view class="so-delivery__file-copy">
              <text class="so-delivery__file-name">{{ file.name }}</text>
              <view v-if="links[file.fileId]" class="so-delivery__linked" role="status" :data-testid="`service-order-file-link-${file.fileId}`">
                <LineIcon name="check" :size="12" color="#159a70" :stroke-width="3" />
                <text>下载链接已生成，{{ formatServiceClock(links[file.fileId]) }} 前有效</text>
              </view>
              <text v-else class="so-delivery__file-size">{{ formatFileSize(file.sizeBytes) }}</text>
            </view>
            <button
              class="so-delivery__download"
              :data-testid="`service-order-download-${file.fileId}`"
              :aria-label="`下载${file.name}`"
              :disabled="Boolean(downloading)"
              @click="download(file)"
            >
              {{ downloading === file.fileId ? '生成中…' : '下载' }}
            </button>
          </view>
          <view class="so-delivery__files-foot">
            <LineIcon name="lock" :size="14" color="#738096" :stroke-width="2" />
            <view class="so-delivery__files-foot-copy">
              <text>文件经受控链接下载，不显示存储地址</text>
              <text v-if="archiveNote">{{ archiveNote }}</text>
            </view>
          </view>
        </view>

        <view v-if="history.length" class="so-delivery__card so-delivery__history" data-testid="service-order-delivery-history">
          <text class="so-delivery__card-title">历次交付</text>
          <button
            v-for="row in history"
            :key="row.round"
            :class="['so-delivery__history-row', { 'so-delivery__history-row--on': row.round === delivery.round }]"
            :data-testid="`service-order-delivery-round-${row.round}`"
            @click="pickedRound = row.round"
          >
            <view class="so-delivery__history-copy">
              <text class="so-delivery__history-title">{{ row.title }}</text>
              <text v-if="row.reason" class="so-delivery__history-reason">{{ row.reason }}</text>
              <text class="so-delivery__history-files">{{ row.files }}</text>
            </view>
            <text :class="['so-delivery__chip', `so-delivery__chip--${row.tag.tone.toLowerCase()}`]">{{ row.tag.label }}</text>
          </button>
        </view>
      </view>

      <view class="so-delivery__bar">
        <template v-if="reviewable">
          <button class="so-delivery__talk" data-testid="service-order-delivery-contact" @click="contactProvider">
            <LineIcon name="chat" :size="22" color="#536178" />
            <text>联系服务商</text>
          </button>
          <button
            v-if="order.actions.rework"
            class="so-delivery__secondary"
            data-testid="service-order-rework"
            @click="openSheet('REWORK')"
          >
            退回整改
          </button>
          <button
            v-if="order.actions.accept"
            class="so-delivery__primary"
            data-testid="service-order-accept"
            @click="openSheet('ACCEPT')"
          >
            确认验收
          </button>
        </template>
        <template v-else>
          <button class="so-delivery__secondary" data-testid="service-order-delivery-contact" @click="contactProvider">
            联系服务商
          </button>
          <button class="so-delivery__primary" data-testid="service-order-delivery-view-order" @click="emit('detail', order.orderId)">
            查看订单
          </button>
        </template>
      </view>
    </template>

    <ServiceOrderNotice v-if="notice" :text="notice" @close="notice = null" />

    <ServiceOrderSheet
      v-if="sheet === 'ACCEPT' && delivery"
      test-id="service-order-accept-sheet"
      :title="`确认第 ${delivery.round} 次验收`"
      :rows="acceptRows"
      warning="验收确认后不可撤销；验收人与时间会留存记录"
      keep-label="取消"
      go-label="确认验收"
      :busy="busy"
      @keep="sheet = null"
      @go="accept"
    />

    <ServiceOrderSheet
      v-if="sheet === 'REWORK' && delivery"
      test-id="service-order-rework-sheet"
      :title="`退回第 ${delivery.round} 次交付`"
      text="写明需要整改的地方，服务商整改后重新交付，再由你验收。退回不会自动退款。"
      keep-label="取消"
      go-label="确认退回"
      :busy="busy"
      :go-disabled="!reasonText"
      @keep="sheet = null"
      @go="sendBack"
    >
      <view class="so-delivery__reason">
        <textarea
          v-model="reason"
          class="so-delivery__reason-input"
          data-testid="service-order-rework-reason"
          :maxlength="SERVICE_REWORK_REASON_MAX"
          placeholder="比如：缺少东侧厂房的影像，报告第 3 页数据有误"
          placeholder-class="so-delivery__reason-placeholder"
          :auto-height="false"
        />
        <text class="so-delivery__reason-count">{{ reason.length }}/{{ SERVICE_REWORK_REASON_MAX }}</text>
      </view>
    </ServiceOrderSheet>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board DeliveryOne (390 wide). */
.so-delivery {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
}

.so-delivery__top {
  position: sticky;
  top: 0;
  z-index: 5;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
}

button::after {
  border: 0;
}

.so-delivery__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.so-delivery__card {
  display: flex;
  flex-direction: column;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.so-delivery__card-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.so-delivery__head {
  gap: 4px;
}

.so-delivery__subtitle {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.so-delivery__title-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.so-delivery__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  white-space: nowrap;
}

.so-delivery__chip {
  flex: none;
  margin-left: auto;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.so-delivery__chip--action { color: #ba470b; background: #fff0e3; }
.so-delivery__chip--done { color: #11804a; background: #e2f6ed; }
.so-delivery__chip--muted { color: #536178; background: #eef2f8; }

.so-delivery__line {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.so-delivery__accepted {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 14px;
  background: #e2f6ed;
}

.so-delivery__accepted-icon {
  display: flex;
  width: 32px;
  height: 32px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #159a70;
}

.so-delivery__accepted-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  color: #11804a;
}

.so-delivery__accepted-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.so-delivery__accepted-text {
  font-size: 12px;
  line-height: 17px;
}

.so-delivery__returned {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 14px;
  border-radius: 14px;
  background: #eef2f8;
}

.so-delivery__returned-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.so-delivery__returned-text {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.so-delivery__note {
  gap: 8px;
}

.so-delivery__note-text {
  color: #536178;
  font-size: 14px;
  line-height: 21px;
  white-space: pre-wrap;
  word-break: break-all;
}

.so-delivery__files {
  padding: 14px 14px 12px;
}

.so-delivery__files-head {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}

.so-delivery__files-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.so-delivery__files-count,
.so-delivery__file-size {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.so-delivery__linked {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #11804a;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.so-delivery__download {
  height: 36px;
  flex: none;
  padding: 0 16px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.so-delivery__file {
  display: flex;
  min-height: 60px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.so-delivery__file-icon {
  display: flex;
  width: 36px;
  height: 36px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #e6f0ff;
}

.so-delivery__file-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.so-delivery__file-name {
  font-size: 14px;
  line-height: 20px;
  word-break: break-all;
}

.so-delivery__files-foot {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding-top: 10px;
  border-top: 1px solid #f0f1f3;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.so-delivery__files-foot :deep(.line-icon) {
  margin-top: 1.5px;
}

.so-delivery__files-foot-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.so-delivery__history {
  gap: 0;
  padding: 14px 14px 4px;
}

.so-delivery__history .so-delivery__card-title {
  margin-bottom: 6px;
}

.so-delivery__history-row {
  display: flex;
  min-height: 52px;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-top: 1px solid #f0f1f3;
}

.so-delivery__history-row--on .so-delivery__history-title {
  color: #ba470b;
  font-weight: 500;
}

.so-delivery__history-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.so-delivery__history-title {
  font-size: 14px;
  line-height: 20px;
}

.so-delivery__history-reason {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.so-delivery__history-files {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.so-delivery__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.so-delivery__talk {
  display: flex;
  width: 68px;
  height: 46px;
  flex: none;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #536178;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.so-delivery__secondary,
.so-delivery__primary {
  height: 46px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  text-align: center;
  white-space: nowrap;
  box-sizing: border-box;
}

.so-delivery__secondary {
  border: 1px solid #c9d3e1;
  background: #ffffff;
  line-height: 44px;
}

.so-delivery__primary {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
  line-height: 46px;
}

.so-delivery__reason {
  position: relative;
  padding: 10px 12px 24px;
  border-radius: 10px;
  background: #f5f7fc;
}

.so-delivery__reason-input {
  width: 100%;
  height: 88px;
  color: #202c42;
  font-size: 14px;
  line-height: 20px;
}

:deep(.so-delivery__reason-placeholder) {
  color: #91a0b4;
}

.so-delivery__reason-count {
  position: absolute;
  right: 12px;
  bottom: 6px;
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
}

.so-delivery__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.so-delivery__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 32px;
}

.so-delivery__state-title {
  font-size: 16px;
  font-weight: 700;
}

.so-delivery__state-text {
  color: #627187;
  font-size: 14px;
  line-height: 20px;
}

.so-delivery__state-button {
  height: 44px;
  padding: 0 24px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
  line-height: 44px;
  white-space: nowrap;
}
</style>
