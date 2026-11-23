<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { openSupportChat } from '@/features/support/application/support-entry'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  describeServicePlanPage,
  serviceOrderCancelCopy,
  servicePlanBar,
} from '../../application/service-order-view'
import { serviceOrderApi } from '../../data/service-order-api-client'
import ServiceOrderHeadCard from '../shared/ServiceOrderHeadCard.vue'
import ServiceOrderNotice from '../shared/ServiceOrderNotice.vue'
import ServiceOrderSheet from '../shared/ServiceOrderSheet.vue'
import { useServiceOrder } from '../shared/use-service-order'

const props = defineProps<{
  orderId: string | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  pay: [tradeOrderId: string]
  detail: [orderId: string]
  provider: [providerId: string, cityCode: string]
}>()

const { state, order, busy, notice, load, run } = useServiceOrder({
  getOrderId: () => props.orderId,
  getAccessToken: () => props.accessToken,
  getRefreshKey: () => props.refreshKey,
  onLogin: () => emit('login'),
})

const agreed = ref(false)
const cancelling = ref(false)

const view = computed(() => (order.value ? describeServicePlanPage(order.value) : null))
const bar = computed(() => (order.value ? servicePlanBar(order.value) : null))
const cancelCopy = computed(() => (order.value ? serviceOrderCancelCopy(order.value) : null))

// A new plan version needs a fresh tick: what was ticked was the version on screen.
watch(() => order.value?.plan?.version, () => { agreed.value = false })

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

// 确认方案 vN: the version on screen and the order version as If-Match; a newer plan (409
// PLAN_CHANGED) or a changed order (412) reads everything again.
async function confirm(): Promise<void> {
  const current = order.value
  if (!current?.plan || !agreed.value) return
  const planVersion = current.plan.version
  await run(
    (token, latest) => serviceOrderApi.confirmPlan(token, latest.orderId, latest.version, planVersion),
    `已确认方案 v${planVersion}`,
  )
}

async function pay(): Promise<void> {
  let tradeOrderId = ''
  const started = await run(async (token, current) => {
    tradeOrderId = (await serviceOrderApi.startPayment(token, current.orderId)).tradeOrderId
  })
  if (started && tradeOrderId) emit('pay', tradeOrderId)
}

async function confirmCancel(): Promise<void> {
  const paid = Boolean(order.value?.payment.paidAt)
  await run(
    (token, current) => serviceOrderApi.cancel(token, current.orderId, current.version, null),
    paid ? '订单已取消，款项原路退回' : '订单已取消',
  )
  cancelling.value = false
}

function openProvider(providerId: string): void {
  const current = order.value
  if (current?.cityCode) emit('provider', providerId, current.cityCode)
}
</script>

<template>
  <view class="so-plan" data-testid="service-order-plan" :data-status="order?.status ?? ''">
    <view class="so-plan__top">
      <AppPageHeader title="方案确认" test-id="service-order-plan" @back="emit('back')" />
    </view>

    <view v-if="state.kind === 'loading'" class="so-plan__body" aria-label="正在加载">
      <view class="so-plan__skeleton" />
      <view class="so-plan__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing' || state.kind === 'error'" class="so-plan__state" data-testid="service-order-plan-error">
      <text class="so-plan__state-title">{{ state.kind === 'missing' ? '没有找到这张服务订单' : '加载失败' }}</text>
      <button v-if="state.kind === 'error'" class="so-plan__state-button" @click="load">重试</button>
      <button v-else class="so-plan__state-button" @click="emit('back')">返回</button>
    </view>

    <template v-else-if="order && view && bar">
      <view class="so-plan__body">
        <ServiceOrderHeadCard :order="order" @provider="openProvider" />

        <view class="so-plan__card so-plan__version" data-testid="service-order-plan-version">
          <view class="so-plan__version-line">
            <text class="so-plan__version-title">{{ view.version ? `方案 v${view.version}` : '服务方案' }}</text>
            <text :class="['so-plan__chip', `so-plan__chip--${view.chip.tone.toLowerCase()}`]" data-testid="service-order-plan-chip">
              {{ view.chip.label }}
            </text>
            <text v-if="view.updated" class="so-plan__updated">{{ view.updated }}</text>
          </view>
          <view v-if="!order.plan" class="so-plan__waiting" data-testid="service-order-plan-waiting">
            <LineIcon name="clock" :size="14" color="#ff6a00" :stroke-width="2.4" />
            <text class="so-plan__waiting-text">服务商正在出方案，出方案后在这里核对并确认；下面是选定的报价</text>
          </view>
        </view>

        <view class="so-plan__card so-plan__lines" data-testid="service-order-plan-lines">
          <text class="so-plan__card-title so-plan__card-title--tight">报价明细</text>
          <view v-for="line in view.lines" :key="line.key" class="so-plan__line" data-testid="service-order-plan-line">
            <text class="so-plan__line-name">{{ line.name }}</text>
            <text class="so-plan__line-qty">{{ line.quantity }}</text>
            <text class="so-plan__line-amount">{{ line.amount }}</text>
          </view>
          <view class="so-plan__total">
            <text class="so-plan__total-label">合计</text>
            <text class="so-plan__total-value" data-testid="service-order-plan-total">{{ view.total }}</text>
          </view>
        </view>

        <view class="so-plan__card" data-testid="service-order-plan-schedule">
          <text class="so-plan__card-title">服务安排</text>
          <view class="so-plan__grid">
            <template v-for="row in view.schedule" :key="row.key">
              <text class="so-plan__grid-label">{{ row.label }}</text>
              <text class="so-plan__grid-value">{{ row.value }}</text>
            </template>
          </view>
        </view>

        <view v-if="view.terms.length" class="so-plan__card so-plan__terms" data-testid="service-order-plan-terms">
          <text class="so-plan__card-title so-plan__card-title--terms">交付约定</text>
          <view v-for="term in view.terms" :key="term" class="so-plan__term">
            <LineIcon name="check" :size="16" color="#159a70" :stroke-width="2.2" />
            <text class="so-plan__term-text">{{ term }}</text>
          </view>
        </view>

        <view class="so-plan__card so-plan__pay" data-testid="service-order-plan-payment">
          <text class="so-plan__card-title so-plan__card-title--tight">付款</text>
          <view class="so-plan__pay-row">
            <view class="so-plan__pay-copy">
              <text class="so-plan__pay-name">{{ view.payment.name }}</text>
              <text :class="['so-plan__pay-state', `so-plan__pay-state--${view.payment.stateTone.toLowerCase()}`]" data-testid="service-order-plan-pay-state">
                {{ view.payment.state }}
              </text>
            </view>
            <text :class="['so-plan__pay-amount', `so-plan__pay-amount--${view.payment.amountTone.toLowerCase()}`]">{{ view.payment.amount }}</text>
          </view>
          <view class="so-plan__pay-foot">
            <LineIcon name="info" :size="14" color="#738096" :stroke-width="2" />
            <text class="so-plan__pay-foot-text">款项经平台支付渠道付给服务商；本平台不承担飞行审批与空中调度。</text>
          </view>
        </view>

        <button
          v-if="order.actions.cancel"
          class="so-plan__cancel"
          data-testid="service-order-plan-cancel"
          @click="cancelling = true"
        >
          取消订单
        </button>
      </view>

      <view class="so-plan__bar" data-testid="service-order-plan-bar" :data-kind="bar.kind">
        <button
          v-if="bar.kind === 'CONFIRM'"
          class="so-plan__agree"
          data-testid="service-order-plan-agree"
          role="checkbox"
          :aria-checked="agreed"
          @click="agreed = !agreed"
        >
          <view v-if="agreed" class="so-plan__tick so-plan__tick--on">
            <LineIcon name="check" :size="11" color="#ffffff" :stroke-width="3.2" />
          </view>
          <view v-else class="so-plan__tick" />
          <text class="so-plan__agree-text">我已核对方案 v{{ bar.version }} 的服务范围、金额与交付约定</text>
        </button>

        <view v-else-if="bar.kind === 'WAITING'" class="so-plan__status" role="status" data-testid="service-order-plan-status">
          <text class="so-plan__status-title">服务商正在出方案</text>
          <text class="so-plan__status-text">出方案后在这里核对并确认，确认后一次付清</text>
        </view>

        <view
          v-else-if="bar.kind === 'PAY' || bar.kind === 'CONFIRMED'"
          class="so-plan__status so-plan__status--done"
          role="status"
          data-testid="service-order-plan-status"
        >
          <view class="so-plan__status-icon">
            <LineIcon name="check" :size="12" color="#ffffff" :stroke-width="3.2" />
          </view>
          <view class="so-plan__status-copy">
            <text class="so-plan__status-title so-plan__status-title--done">已确认方案 v{{ bar.version }}</text>
            <text v-if="bar.by || bar.at" class="so-plan__status-text" data-testid="service-order-plan-confirmed-by">
              {{ [bar.by, bar.at].filter(Boolean).join(' · ') }}
            </text>
            <text v-if="bar.kind === 'PAY' && !bar.collectable" class="so-plan__status-text so-plan__status-text--alert" data-testid="service-order-plan-uncollectable">
              服务商暂未开通在线收款，请先联系服务商
            </text>
          </view>
        </view>

        <view v-else class="so-plan__status" role="status" data-testid="service-order-plan-status">
          <text class="so-plan__status-title">订单已{{ order.status === 'TERMINATED' ? '终止' : '取消' }}</text>
          <text class="so-plan__status-text">需要服务时请重新发布需求</text>
        </view>

        <view class="so-plan__actions">
          <button class="so-plan__talk" data-testid="service-order-plan-contact" @click="contactProvider">
            <LineIcon name="chat" :size="22" color="#536178" />
            <text>联系服务商</text>
          </button>
          <button
            v-if="bar.kind === 'CONFIRM'"
            :class="['so-plan__primary', { 'so-plan__primary--off': !agreed }]"
            data-testid="service-order-plan-confirm"
            :disabled="!agreed || busy"
            :aria-disabled="!agreed || busy"
            @click="confirm"
          >
            {{ busy ? '处理中…' : `确认方案 v${bar.version}` }}
          </button>
          <button
            v-else-if="bar.kind === 'PAY'"
            :class="['so-plan__primary', { 'so-plan__primary--off': !bar.collectable }]"
            data-testid="service-order-plan-pay"
            :disabled="!bar.collectable || busy"
            :aria-disabled="!bar.collectable || busy"
            @click="pay"
          >
            {{ busy ? '处理中…' : `支付 ${bar.amount}` }}
          </button>
          <button
            v-else
            class="so-plan__secondary"
            data-testid="service-order-plan-view-order"
            @click="emit('detail', order.orderId)"
          >
            查看订单
          </button>
        </view>
      </view>
    </template>

    <ServiceOrderNotice v-if="notice" :text="notice" @close="notice = null" />

    <ServiceOrderSheet
      v-if="cancelling && cancelCopy"
      test-id="service-order-cancel-sheet"
      :title="cancelCopy.title"
      :text="cancelCopy.text"
      :keep-label="cancelCopy.keep"
      :go-label="cancelCopy.go"
      :busy="busy"
      @keep="cancelling = false"
      @go="confirmCancel"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board PlanConfirm (390 wide), one visit and
   one payment. */
.so-plan {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
}

.so-plan__top {
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

.so-plan__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.so-plan__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.so-plan__card-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.so-plan__card-title--tight {
  margin-bottom: -6px;
}

.so-plan__card-title--terms {
  margin-bottom: -2px;
}

.so-plan__version {
  padding: 12px 14px 14px;
}

.so-plan__version-line {
  display: flex;
  min-height: 32px;
  align-items: center;
  gap: 8px;
}

.so-plan__version-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  white-space: nowrap;
}

.so-plan__chip {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.so-plan__chip--action { color: #ba470b; background: #fff0e3; }
.so-plan__chip--done { color: #11804a; background: #e2f6ed; }
.so-plan__chip--muted { color: #536178; background: #eef2f8; }

.so-plan__updated {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.so-plan__waiting {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 10px;
  background: #fffaf5;
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.so-plan__waiting-text {
  min-width: 0;
}

.so-plan__lines {
  gap: 0;
  padding: 14px 14px 0;
}

.so-plan__lines .so-plan__card-title {
  margin-bottom: 4px;
}

.so-plan__line {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
}

.so-plan__line-name {
  min-width: 0;
  flex: 1;
  font-size: 14px;
  line-height: 20px;
}

.so-plan__line-qty {
  flex: none;
  color: #738096;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.so-plan__line-amount {
  width: 68px;
  flex: none;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-align: right;
  white-space: nowrap;
}

.so-plan__total {
  display: flex;
  min-height: 48px;
  align-items: center;
  border-top: 1px solid #f0f1f3;
}

.so-plan__total-label {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.so-plan__total-value {
  margin-left: auto;
  color: #e85400;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.so-plan__grid {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
  line-height: 19px;
}

.so-plan__grid-label {
  color: #627187;
  white-space: nowrap;
}

.so-plan__grid-value {
  min-width: 0;
  word-break: break-all;
}

.so-plan__terms {
  gap: 8px;
}

.so-plan__term {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 19px;
}

.so-plan__term :deep(.line-icon) {
  margin-top: 2px;
}

.so-plan__term-text {
  min-width: 0;
  color: #536178;
}

.so-plan__pay {
  gap: 0;
  padding: 14px 14px 12px;
}

.so-plan__pay .so-plan__card-title {
  margin-bottom: 4px;
}

.so-plan__pay-row {
  display: flex;
  min-height: 56px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.so-plan__pay-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.so-plan__pay-name {
  font-size: 14px;
  line-height: 20px;
}

.so-plan__pay-state {
  font-size: 12px;
  line-height: 17px;
}

.so-plan__pay-state--muted { color: #738096; }
.so-plan__pay-state--action { color: #ba470b; }
.so-plan__pay-state--done { color: #11804a; }
.so-plan__pay-state--alert { color: #c43c3c; }

.so-plan__pay-amount {
  flex: none;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.so-plan__pay-amount--plain { color: #202c42; }
.so-plan__pay-amount--accent { color: #e85400; }
.so-plan__pay-amount--muted { color: #738096; }
.so-plan__pay-amount--done { color: #202c42; }

.so-plan__pay-foot {
  display: flex;
  gap: 6px;
  padding-top: 10px;
  border-top: 1px solid #f0f1f3;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.so-plan__pay-foot :deep(.line-icon) {
  margin-top: 1.5px;
}

.so-plan__pay-foot-text {
  min-width: 0;
}

.so-plan__cancel {
  align-self: center;
  height: 44px;
  padding: 0 20px;
  color: #627187;
  font-size: 14px;
  line-height: 44px;
  white-space: nowrap;
}

.so-plan__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 16px calc(20px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.so-plan__agree {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.so-plan__tick {
  width: 18px;
  height: 18px;
  flex: none;
  border: 1.5px solid #91a0b4;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.so-plan__tick--on {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  background: #ff6a00;
}

.so-plan__agree-text {
  min-width: 0;
}

.so-plan__status {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #eef2f8;
}

.so-plan__status--done {
  flex-direction: row;
  gap: 8px;
  background: #eefaf3;
}

.so-plan__status-icon {
  display: flex;
  width: 20px;
  height: 20px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #159a70;
}

.so-plan__status-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.so-plan__status-title {
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
}

.so-plan__status-title--done {
  color: #11804a;
}

.so-plan__status-text {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.so-plan__status-text--alert {
  color: #c43c3c;
}

.so-plan__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.so-plan__talk {
  display: flex;
  width: 68px;
  height: 44px;
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

.so-plan__primary {
  height: 46px;
  flex: 1;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
  white-space: nowrap;
}

/* uni-button[disabled] turns the text 30% black; the board keeps it white on grey. */
.so-plan__primary--off {
  color: #ffffff !important;
  background: #c9d3e1;
}

.so-plan__secondary {
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

.so-plan__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.so-plan__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 32px;
}

.so-plan__state-title {
  font-size: 16px;
  font-weight: 700;
}

.so-plan__state-button {
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
