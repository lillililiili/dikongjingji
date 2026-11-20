<script lang="ts">
export function createOrderCenterVisibilityController(
  reload: () => Promise<void>,
) {
  let activeReload: Promise<void> | null = null

  function reloadOnShow(): Promise<void> {
    if (activeReload) return activeReload
    let currentReload: Promise<void>
    currentReload = reload().finally(() => {
      if (activeReload === currentReload) activeReload = null
    })
    activeReload = currentReload
    return currentReload
  }

  return { reloadOnShow }
}

export function createLatestOrderCenterApi<Input, Response>(
  list: (input: Input) => Promise<Response>,
  applyResponse: (response: Response) => void,
) {
  let requestVersion = 0

  return {
    async list(input: Input): Promise<Response> {
      const version = ++requestVersion
      const response = await list(input)
      if (version === requestVersion) applyResponse(response)
      return response
    },
  }
}
</script>

<script setup lang="ts">
import {
  onBackPress,
  onLoad,
  onPullDownRefresh,
  onReachBottom,
  onShow,
} from '@dcloudio/uni-app'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import { reactive, ref } from 'vue'
import {
  getPreviousInspectionPageRoute,
  resolveInspectionOrderDestination,
} from '../../../application/service-flow/inspection-order-navigation'
import { inspectionOrderApi } from '../../../data/service-flow/uni-booking-http-client'
import type {
  InspectionOrderFilter,
  InspectionOrderPage,
  InspectionOrderSummary,
} from '../../../domain/service-flow/order-center'
import { buildInspectionFulfillmentUrl } from '../../../navigation/inspection-routes'
import InspectionOrderCard from '../components/InspectionOrderCard.vue'
import {
  createInspectionOrderCenterController,
  createInspectionOrderCenterState,
  getInspectionOrderListState,
  INSPECTION_ORDER_FILTER_ITEMS,
  normalizeInspectionOrderFilter,
} from '../order-center-view'

const state = reactive(createInspectionOrderCenterState())
const stageCounts = ref<InspectionOrderPage['stageCounts'] | null>(null)
const latestOrderApi = createLatestOrderCenterApi(
  inspectionOrderApi.list,
  (response: InspectionOrderPage) => { stageCounts.value = response.stageCounts },
)
const controller = createInspectionOrderCenterController({
  api: latestOrderApi,
  resolveDestination: resolveInspectionOrderDestination,
  navigateTo: (url) => uni.navigateTo({ url }),
  showToast: (message) => uni.showToast({ title: message, icon: 'none' }),
  navigateBack: () => new Promise((resolve) => {
    // Alone in the stack, navigateBack reloads this page on H5 and offers to quit on
    // App, yet reports success, so answer for it and let the controller go to my tab.
    // Opened from the booking flow, the page underneath is not my tab either.
    if (getPreviousInspectionPageRoute(getCurrentPages()) !== 'pages/mine/index') {
      resolve(false)
      return
    }
    uni.navigateBack({
      delta: 1,
      success: () => resolve(true),
      fail: () => resolve(false),
    })
  }),
  switchTab: (url) => uni.switchTab({ url }),
}, state)
const visibilityController = createOrderCenterVisibilityController(
  () => controller.reload(),
)

onLoad((query: { stage?: string } | undefined) => {
  state.selectedStage = normalizeInspectionOrderFilter(query?.stage)
})

onShow(() => {
  void visibilityController.reloadOnShow()
})

function reload(): void {
  void controller.reload()
}

function selectStage(stage: InspectionOrderFilter): void {
  void controller.selectStage(stage)
}

function getStageCount(stage: InspectionOrderFilter): number | null {
  if (stage === 'ALL' || !stageCounts.value) return null
  return stageCounts.value[stage]
}

function openOrder(order: InspectionOrderSummary): void {
  void controller.openOrder(order)
}

// The card's grey 查看履约 next to 去验收: the visits of the service order, by its stable id only.
function openFulfillment(order: InspectionOrderSummary): void {
  if (order.serviceOrderId) uni.navigateTo({ url: buildInspectionFulfillmentUrl(order.serviceOrderId) })
}

function returnMine(): void {
  void controller.returnMine()
}

onReachBottom(() => {
  void controller.loadMore()
})

onPullDownRefresh(() => {
  void controller.reload().finally(() => uni.stopPullDownRefresh())
})

onBackPress((event) => {
  if (event.from === 'navigateBack') return false
  returnMine()
  return true
})
</script>

<template>
  <view class="inspection-orders">
    <AppPageHeader class="inspection-orders__header" title="我的订单" centered test-id="inspection-orders" @back="returnMine" />
    <view class="inspection-orders__filters">
      <button
        v-for="item in INSPECTION_ORDER_FILTER_ITEMS"
        :key="item.code"
        :class="{ 'inspection-orders__filter--active': item.code === state.selectedStage }"
        @click="selectStage(item.code)"
      >
        <text>{{ item.label }}</text>
        <text
          v-if="getStageCount(item.code) !== null"
          class="inspection-orders__filter-count"
        >
          {{ getStageCount(item.code) }}
        </text>
      </button>
    </view>
    <view v-if="getInspectionOrderListState(state) === 'loading'" class="inspection-orders__state">正在加载订单…</view>
    <view v-else-if="getInspectionOrderListState(state) === 'error'" class="inspection-orders__state">
      <text>订单加载失败</text>
      <text>{{ state.loadError }}</text>
      <button @click="reload">重新加载</button>
    </view>
    <view v-else-if="getInspectionOrderListState(state) === 'empty'" class="inspection-orders__state">
      <text>暂无巡检订单</text>
      <text>提交巡检需求后，可在这里查看服务进度</text>
    </view>
    <view v-else class="inspection-orders__list">
      <InspectionOrderCard
        v-for="order in state.orders"
        :key="order.reservationId"
        :order="order"
        @select="openOrder"
        @fulfillment="openFulfillment"
      />
      <text v-if="state.isLoadingMore" class="inspection-orders__more">正在加载更多…</text>
      <text v-else-if="!state.hasMore" class="inspection-orders__more">没有更多订单</text>
    </view>
  </view>
</template>

<style scoped>
.inspection-orders{min-height:100vh;color:#17191d;background:#f7f8fa}
.inspection-orders__filters button::after,.inspection-orders__state button::after{border:0}
.inspection-orders__filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;width:100%;padding:10px 16px;box-sizing:border-box;background:#fff}
.inspection-orders__filters button{display:inline-flex;width:100%;min-height:44px;align-items:center;justify-content:center;margin:0;padding:0 6px;border-radius:22px;background:#f2f4f7;font-size:15px;white-space:nowrap}
.inspection-orders__filter-count{margin-left:4px;font-size:12px}
.inspection-orders__filter--active{color:#fff!important;background:#ff6508!important;font-weight:700}
.inspection-orders__list{display:flex;flex-direction:column;gap:12px;padding:12px 16px calc(24px + env(safe-area-inset-bottom))}
.inspection-orders__state{display:flex;min-height:420px;flex-direction:column;align-items:center;justify-content:center;gap:12px;padding:24px;color:#68717d;text-align:center}
.inspection-orders__state button{min-height:44px;padding:0 18px;border:0;border-radius:22px;color:#fff;background:#ff6508;font-size:14px}
.inspection-orders__more{padding:8px 0 12px;color:#7d8590;font-size:14px;text-align:center}
/* 2026-11-14 prototype (board OrderList, 390 wide): the theme layer over the layout above.
   The board lists every kind of order; this page lists inspection orders only, so it keeps
   its own stage filters and takes the board's header, pills and card look. */
.inspection-orders{color:#202c42;background:#f5f7fc}
.inspection-orders__filters{display:flex;padding:0 16px 6px;gap:8px;overflow-x:auto;background:linear-gradient(125deg,#fff9f3,#f3f7ff 65%,#edf9f4);scrollbar-width:none}
.inspection-orders__filters::-webkit-scrollbar{display:none}
/* A 32-high pill inside a 44-high tap target. */
.inspection-orders__filters button{position:relative;width:auto;min-height:44px;padding:0 12px;flex:0 0 auto;border-radius:0;color:#536178;background:transparent;font-size:13px;z-index:0}
.inspection-orders__filters button::before{position:absolute;top:6px;right:0;bottom:6px;left:0;border-radius:999px;background:#eef2f8;content:'';z-index:-1}
.inspection-orders__filter-count{margin-left:3px;font-size:11px}
.inspection-orders__filter--active{background:transparent!important;font-weight:500}
.inspection-orders__filter--active::before{background:#ff6a00!important}
.inspection-orders__list{gap:10px;padding:12px 16px calc(24px + env(safe-area-inset-bottom))}
.inspection-orders__state{color:#627187}
.inspection-orders__state button{border-radius:999px;background:#ff6a00}
.inspection-orders__more{color:#738096;font-size:12px}
</style>
