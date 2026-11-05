<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import { useCurrentCityStore } from '@/shared/city/state/current-city'
import {
  createReservationIntent,
  resolveDetailServiceId,
  type ReserveServiceIntent,
} from '../../../application/detail/inspection-detail-actions'
import {
  EMPTY_INSPECTION_SERVICE_DETAIL,
} from '../../../application/detail/resolve-inspection-service-detail'
import { ApiRequestError, inspectionServiceDetailApi, platformCityApi } from '../../../data/service-flow/uni-booking-http-client'
import type { InspectionServiceDetail } from '../../../domain/inspection-service-detail'
import InspectionScope from '../components/InspectionScope.vue'
import ServiceDeliverables from '../components/ServiceDeliverables.vue'
import ServiceDetailBottomBar from '../components/ServiceDetailBottomBar.vue'
import ServiceDetailHeader from '../components/ServiceDetailHeader.vue'
import ServiceDetailHero from '../components/ServiceDetailHero.vue'
import ServiceGuarantees from '../components/ServiceGuarantees.vue'
import ServiceProcess from '../components/ServiceProcess.vue'
import ServiceReviews from '../components/ServiceReviews.vue'

interface DetailRouteQuery {
  serviceId?: string
  cityCode?: string
}

const currentCityStore = useCurrentCityStore()
const detail = ref<InspectionServiceDetail>(
  EMPTY_INSPECTION_SERVICE_DETAIL,
)
const routeServiceId = ref('')
const detailCityCode = ref('')
const selectedReviewFilter = ref('ALL')
const isLoading = ref(true)
const loadError = ref('')
const canRetryLoad = ref(false)
const hasDetail = computed(() => Boolean(detail.value.serviceId))
// A reservation needs at least one item, so a service without any cannot be booked.
const canReserve = computed(() => Boolean(routeServiceId.value && detail.value.inspectionItems.length))
const emit = defineEmits<{
  reserve: [intent: ReserveServiceIntent]
}>()

onLoad((query: DetailRouteQuery | undefined) => {
  routeServiceId.value = resolveDetailServiceId(query?.serviceId)
  const cityCode = query?.cityCode?.trim()
  if (cityCode && !currentCityStore.currentCity) void restoreRouteCity(cityCode)
  // A city already in the store is the user's latest choice, so the route only
  // fills an empty store.
  const resolvedCityCode = currentCityStore.currentCity?.code ?? cityCode
  detailCityCode.value = resolvedCityCode ?? ''
  if (!routeServiceId.value || !resolvedCityCode) {
    isLoading.value = false
    loadError.value = '未找到可预约的巡检服务'
    return
  }
  void loadDetail(routeServiceId.value, resolvedCityCode)
})

// The URL carries a code; the name comes from the directory. Without a match the
// store stays empty, and this page keeps working from detailCityCode.
async function restoreRouteCity(cityCode: string): Promise<void> {
  const directory = await platformCityApi.load().catch(() => undefined)
  const city = directory?.provinces
    .flatMap((province) => province.cities)
    .find(({ code }) => code === cityCode)
  if (!city || currentCityStore.currentCity) return
  const { code, name, provinceCode } = city
  currentCityStore.setCurrentCity({ code, name, provinceCode })
}

async function loadDetail(serviceId: string, cityCode: string): Promise<void> {
  isLoading.value = true
  loadError.value = ''
  try {
    detail.value = await inspectionServiceDetailApi.loadDetail(
      serviceId,
      cityCode,
    )
  } catch (error) {
    // 400 and 404 mean this city cannot show this service, so a retry cannot help.
    canRetryLoad.value = !(error instanceof ApiRequestError && [400, 404].includes(error.status))
    loadError.value = canRetryLoad.value
      ? '服务详情加载失败，请检查网络后重试'
      : '当前城市暂无这项服务，或服务已下架'
  } finally {
    isLoading.value = false
  }
}

function recoverFromLoadError(): void {
  if (canRetryLoad.value) void loadDetail(routeServiceId.value, detailCityCode.value)
  else returnToCatalog()
}

function returnToCatalog(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }

  uni.redirectTo({
    url: '/pages/service/inspection/catalog',
  })
}

function showUnavailable(title: string): void {
  uni.showToast({
    title,
    icon: 'none',
  })
}

function reserveService(): void {
  if (!routeServiceId.value) {
    return
  }

  const intent = createReservationIntent(
    routeServiceId.value,
    detailCityCode.value,
  )
  emit('reserve', intent)
}
</script>

<template>
  <view class="inspection-service-detail" data-testid="inspection-detail">
    <ServiceDetailHeader
      @back="returnToCatalog"
      @share="showUnavailable('分享能力由平台统一接入')"
    />

    <main
      v-if="hasDetail"
      class="inspection-service-detail__content"
    >
      <ServiceDetailHero :detail="detail" />
      <InspectionScope
        v-if="detail.inspectionItems.length"
        :items="detail.inspectionItems"
      />
      <ServiceReviews
        :average="detail.ratingAverage"
        :total="detail.reviewCount ?? 0"
        :positive-rate-label="detail.positiveRateLabel"
        :filters="detail.reviewFilters"
        :reviews="detail.reviews"
        :selected-filter="selectedReviewFilter"
        @select-filter="selectedReviewFilter = $event"
        @view-all="showUnavailable('全部评价功能正在完善')"
      />
      <ServiceProcess
        v-if="detail.processSteps.length"
        :steps="detail.processSteps"
      />
      <ServiceDeliverables
        v-if="detail.deliverables.length"
        :items="detail.deliverables"
      />
      <ServiceGuarantees
        v-if="detail.guarantees.length"
        :items="detail.guarantees"
      />
    </main>

    <view v-else class="inspection-service-detail__empty">
      <UniIcons type="info-filled" size="42" color="#b5bac1" />
      <text class="inspection-service-detail__empty-title">
        {{ isLoading ? '正在加载服务详情' : '服务详情暂不可用' }}
      </text>
      <text class="inspection-service-detail__empty-copy">
        {{ isLoading ? '正在读取真实服务数据…' : loadError }}
      </text>
      <button
        v-if="!isLoading"
        class="inspection-service-detail__empty-action"
        @click="recoverFromLoadError"
      >
        {{ canRetryLoad ? '重新加载' : '看看其他服务' }}
      </button>
    </view>

    <ServiceDetailBottomBar
      :disabled="!canReserve"
      @customer-service="showUnavailable('客服能力由平台统一接入')"
      @reserve="reserveService"
    />
  </view>
</template>

<style scoped>
.inspection-service-detail {
  min-height: 100vh;
  color: #1a1c20;
  background: #f7f8fa;
  box-sizing: border-box;
}

.inspection-service-detail__content {
  display: flex;
  width: 100%;
  max-width: 600px;
  min-height: 100vh;
  margin: 0 auto;
  padding:
    calc(50px + var(--status-bar-height))
    0
    calc(82px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 6px;
  background: #f7f8fa;
  box-sizing: border-box;
}

.inspection-service-detail__empty {
  display: flex;
  min-height: 100vh;
  padding:
    calc(90px + var(--status-bar-height))
    24px
    calc(110px + env(safe-area-inset-bottom));
  align-items: center;
  justify-content: center;
  flex-direction: column;
  box-sizing: border-box;
  text-align: center;
}

.inspection-service-detail__empty-title {
  margin-top: 14px;
  color: #343840;
  font-size: 18px;
  font-weight: 700;
}

.inspection-service-detail__empty-copy {
  margin-top: 8px;
  color: #8e939b;
  font-size: 13px;
}

.inspection-service-detail__empty-action {
  height: 44px;
  margin-top: 18px;
  border-radius: 22px;
  color: #ffffff;
  background: #ff5a00;
  font-size: 14px;
  line-height: 44px;
}

.inspection-service-detail__empty-action::after {
  border: 0;
}
</style>
