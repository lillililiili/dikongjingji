<script setup lang="ts">
import IconTile from '@/shared/ui/IconTile.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'

import { describeRentalLessee, formatRentalAmount } from '../../application/rental-format'
import { openSupportChat } from '@/features/support/application/support-entry'
import {
  rentalContractParties,
  rentalContractScope,
  rentalContractTag,
  rentalFeeRows,
} from '../../application/rental-order-view'
import { rentalTermRows } from '../../application/rental-quote-view'
import { rentalApi } from '../../data/rental-api-client'
import type { RentalOrder } from '../../domain/rental'
import RentalPageHeader from '../shared/RentalPageHeader.vue'

// The Contract board in its rental frame, read-only: the contract was made from the confirmed
// quote and does not change. It is signed offline; there is no file to download yet (§24.1 rule 8).
const props = defineProps<{
  rentalOrderId: string | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  order: [rentalOrderId: string]
  quote: [inquiryId: string]
}>()

type ContractState =
  | { kind: 'loading' }
  | { kind: 'ready'; order: RentalOrder }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<ContractState>({ kind: 'loading' })
const copied = ref(false)
let requestVersion = 0

const order = computed(() => (state.value.kind === 'ready' ? state.value.order : null))

// 联系商家 (step G1): the lessor's conversation with a card about this rental order.
function contactMerchant(): void {
  const current = order.value
  if (!current) return
  openSupportChat(
    { kind: 'MERCHANT', organizationId: current.contract.lessor.merchantId },
    { type: 'RENTAL_ORDER', refId: current.rentalOrderId, title: current.contract.productName, subtitle: `合同 ${current.contract.contractNo}` },
  )
}
const tag = computed(() => (order.value ? rentalContractTag(order.value.status) : null))
const parties = computed(() => (order.value ? rentalContractParties(order.value) : []))
const scope = computed(() => (order.value ? rentalContractScope(order.value) : []))
const pays = computed(() => (order.value ? rentalFeeRows(order.value) : []))
const terms = computed(() => (order.value ? rentalTermRows(order.value.contract.terms) : []))
const lesseeName = computed(() => {
  if (!order.value) return ''
  const lessee = order.value.contract.lessee
  return lessee.kind === 'ORGANIZATION' && lessee.organizationName
    ? lessee.organizationName
    : describeRentalLessee(lessee).text
})

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.rentalOrderId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await rentalApi.readOrder(token, props.rentalOrderId)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', order: loaded }
  } catch (error) {
    if (requested !== requestVersion) return
    if (error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')) {
      emit('login')
      return
    }
    state.value = {
      kind: error instanceof ApiRequestError && (error.status === 404 || error.status === 400) ? 'missing' : 'error',
    }
  }
}

function copyNo(): void {
  if (!order.value) return
  uni.setClipboardData({
    data: order.value.contract.contractNo,
    showToast: false,
    success: () => { copied.value = true },
  } as UniApp.SetClipboardDataOptions)
}

watch(() => [props.refreshKey, props.accessToken, props.rentalOrderId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="rental-contract" data-testid="rental-contract">
    <RentalPageHeader title="合同详情" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="rental-contract__body" aria-label="正在加载">
      <view class="rental-contract__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="rental-contract__state" data-testid="rental-contract-missing">
      <text class="rental-contract__state-title">没有找到这份合同</text>
      <button class="rental-contract__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="rental-contract__state" data-testid="rental-contract-error">
      <text class="rental-contract__state-title">加载失败</text>
      <text class="rental-contract__state-text">请检查网络后重试</text>
      <button class="rental-contract__state-button" data-testid="rental-contract-retry" @click="load">重试</button>
    </view>

    <template v-else-if="order && tag">
      <view class="rental-contract__body">
        <view class="rental-contract__card rental-contract__top">
          <view class="rental-contract__title-row">
            <IconTile tone="blue" :size="44" :icon-size="22" name="doc" />
            <view class="rental-contract__title-copy">
              <text class="rental-contract__name" data-testid="rental-contract-name">{{ order.contract.productName }}租赁合同</text>
              <text :class="['rental-contract__tag', `rental-contract__tag--${tag.tone.toLowerCase()}`]" data-testid="rental-contract-tag">
                {{ tag.label }}
              </text>
            </view>
          </view>
          <view class="rental-contract__line">
            <text class="rental-contract__line-label">合同编号</text>
            <text class="rental-contract__line-value" data-testid="rental-contract-no">{{ order.contract.contractNo }}</text>
            <button :class="['rental-contract__copy', { 'rental-contract__copy--done': copied }]" data-testid="rental-contract-copy" @click="copyNo">
              {{ copied ? '已复制' : '复制' }}
            </button>
          </view>
          <view class="rental-contract__line rental-contract__line--amount">
            <text class="rental-contract__line-label">合同金额</text>
            <text class="rental-contract__amount" data-testid="rental-contract-amount">{{ formatRentalAmount(order.contract.totalMinor) }}</text>
          </view>
        </view>

        <view class="rental-contract__card" data-testid="rental-contract-parties">
          <text class="rental-contract__card-title">签约主体</text>
          <view class="rental-contract__grid">
            <template v-for="row in parties" :key="row.key">
              <text class="rental-contract__grid-label">{{ row.label }}</text>
              <text class="rental-contract__grid-value">{{ row.value }}</text>
            </template>
          </view>
        </view>

        <view class="rental-contract__card rental-contract__scope" data-testid="rental-contract-scope">
          <text class="rental-contract__card-title">租赁内容</text>
          <view class="rental-contract__grid">
            <template v-for="row in scope" :key="row.key">
              <text class="rental-contract__grid-label">{{ row.label }}</text>
              <text class="rental-contract__grid-value">{{ row.value }}</text>
            </template>
          </view>
          <button class="rental-contract__link" data-testid="rental-contract-quote" @click="emit('quote', order.inquiryId)">
            <text class="rental-contract__link-name">租赁报价单</text>
            <text class="rental-contract__tag rental-contract__tag--done">已确认 v{{ order.contract.quoteVersion }}</text>
            <text class="rental-contract__link-cta">查看报价</text>
            <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
        </view>

        <view class="rental-contract__card rental-contract__pays">
          <text class="rental-contract__card-title rental-contract__card-title--rows">付款约定</text>
          <view v-for="pay in pays" :key="pay.key" class="rental-contract__pay">
            <view class="rental-contract__pay-copy">
              <text class="rental-contract__pay-name">{{ pay.name }}</text>
              <text :class="['rental-contract__pay-state', { 'rental-contract__pay-state--paid': pay.paid }]">{{ pay.state }}</text>
            </view>
            <text :class="['rental-contract__pay-amount', { 'rental-contract__pay-amount--quiet': !pay.money }]">{{ pay.amount }}</text>
          </view>
        </view>

        <view class="rental-contract__card" data-testid="rental-contract-terms">
          <text class="rental-contract__card-title">合同条款</text>
          <view v-for="term in terms" :key="term.key" class="rental-contract__term">
            <text v-if="term.label" class="rental-contract__term-label">{{ term.label }}</text>
            <text class="rental-contract__term-text">{{ term.text }}</text>
          </view>
        </view>

        <view class="rental-contract__card" data-testid="rental-contract-files">
          <text class="rental-contract__card-title">合同文件</text>
          <view class="rental-contract__offline">
            <LineIcon name="info" :size="16" color="#627187" :stroke-width="2" />
            <text>合同由出租方线下签署、扫描归档，暂不提供在线下载；需要纸质或扫描件请联系出租方。</text>
          </view>
        </view>

        <text class="rental-contract__footnote">
          合同由{{ lesseeName }}与{{ order.contract.lessor.name }}签订。履约有分歧时平台协助沟通；押金、超期和赔付以合同约定为准。
        </text>
      </view>

      <view class="rental-contract__bar">
        <button class="rental-contract__contact" data-testid="rental-contract-contact" @click="contactMerchant">
          <LineIcon name="chat" :size="20" color="#536178" />
          <text>联系商家</text>
        </button>
        <button class="rental-contract__order" data-testid="rental-contract-order" @click="emit('order', order.rentalOrderId)">
          查看订单
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Contract, rental frame (390 wide). */
.rental-contract {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
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

.rental-contract__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.rental-contract__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.rental-contract__card-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.rental-contract__card-title--rows {
  margin-bottom: 4px;
}

.rental-contract__top {
  gap: 0;
  padding: 14px 14px 4px;
}

.rental-contract__title-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding-bottom: 12px;
}

.rental-contract__icon {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: #e6f0ff;
}

.rental-contract__title-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.rental-contract__name {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.rental-contract__tag {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.rental-contract__tag--action { color: #ba470b; background: #fff0e3; }
.rental-contract__tag--done { color: #11804a; background: #e2f6ed; }
.rental-contract__tag--muted { color: #627187; background: #eef2f8; }

.rental-contract__line {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 12px;
  border-top: 1px solid #f0f1f3;
  font-size: 14px;
  line-height: 20px;
}

.rental-contract__line--amount {
  min-height: 48px;
}

.rental-contract__line-label {
  flex: none;
  color: #627187;
  white-space: nowrap;
}

.rental-contract__line-value {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rental-contract__copy {
  min-height: 44px;
  flex: none;
  padding-left: 8px;
  color: #ba470b;
  font-size: 13px;
  line-height: 44px;
  white-space: nowrap;
}

.rental-contract__copy--done {
  color: #11804a;
}

.rental-contract__amount {
  margin-left: auto;
  color: #e85400;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.rental-contract__grid {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
  line-height: 19px;
}

.rental-contract__grid-label {
  color: #627187;
  white-space: nowrap;
}

.rental-contract__grid-value {
  min-width: 0;
  word-break: break-all;
}

.rental-contract__scope {
  padding-bottom: 0;
}

.rental-contract__link {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
}

.rental-contract__link-name {
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.rental-contract__link-cta {
  margin-left: auto;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.rental-contract__pays {
  gap: 0;
  padding: 14px 14px 4px;
}

.rental-contract__pay {
  display: flex;
  min-height: 52px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.rental-contract__pay-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.rental-contract__pay-name {
  font-size: 14px;
  line-height: 20px;
}

.rental-contract__pay-state {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.rental-contract__pay-state--paid {
  color: #11804a;
}

.rental-contract__pay-amount {
  flex: none;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.rental-contract__pay-amount--quiet {
  color: #627187;
  font-size: 13px;
  font-weight: 400;
}

.rental-contract__term {
  display: flex;
  gap: 10px;
  font-size: 13px;
  line-height: 19px;
}

.rental-contract__term-label {
  width: 60px;
  flex: none;
  color: #627187;
  white-space: nowrap;
}

.rental-contract__term-text {
  min-width: 0;
  color: #536178;
}

.rental-contract__offline {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.rental-contract__footnote {
  color: #627187;
  font-size: 12px;
  line-height: 19px;
}

.rental-contract__bar {
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

.rental-contract__contact {
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

.rental-contract__order {
  height: 46px;
  flex: 1;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  background: #ffffff;
  font-size: 16px;
  line-height: 44px;
  text-align: center;
  white-space: nowrap;
  box-sizing: border-box;
}

.rental-contract__skeleton {
  height: 220px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.rental-contract__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 32px;
}

.rental-contract__state-title {
  font-size: 16px;
  font-weight: 700;
}

.rental-contract__state-text {
  color: #627187;
  font-size: 14px;
  line-height: 20px;
}

.rental-contract__state-button {
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
