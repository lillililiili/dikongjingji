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
import type { InspectionServiceDetail } from '../../../domain/inspection-service-detail'
import InspectionScope from '../components/InspectionScope.vue'
import ServiceDeliverables from '../components/ServiceDeliverables.vue'
import ServiceDetailBottomBar from '../components/ServiceDetailBottomBar.vue'
import ServiceDetailHeader from '../components/ServiceDetailHeader.vue'
import ServiceDetailHero from '../components/ServiceDetailHero.vue'
import ServiceExplanation from '../components/ServiceExplanation.vue'
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
const selectedReviewFilter = ref('ALL')
const hasDetail = computed(() => Boolean(detail.value.serviceId))
const canReserve = computed(() => Boolean(routeServiceId.value))
const emit = defineEmits<{
  reserve: [intent: ReserveServiceIntent]
}>()

onLoad((query: DetailRouteQuery | undefined) => {
  routeServiceId.value = resolveDetailServiceId(query?.serviceId)
  const cityCode = query?.cityCode?.trim()
  if (cityCode) {
    currentCityStore.setCurrentCity({
      code: cityCode,
      name: currentCityStore.currentCity?.name ?? cityCode,
    })
  }
  if (import.meta.env.DEV) {
    void loadDevelopmentDetail(routeServiceId.value)
  }
})

async function loadDevelopmentDetail(serviceId: string): Promise<void> {
  const { DEVELOPMENT_POWER_SERVICE_DETAIL } = await import(
    '../../../data/fixtures/inspection-service-detail.fixture'
  )
  detail.value = serviceId
    ? { ...DEVELOPMENT_POWER_SERVICE_DETAIL, serviceId }
    : DEVELOPMENT_POWER_SERVICE_DETAIL
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
    currentCityStore.currentCity?.code,
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
      <InspectionScope :items="detail.inspectionItems" />
      <ServiceProcess :steps="detail.processSteps" />
      <ServiceDeliverables :items="detail.deliverables" />
      <ServiceGuarantees :items="detail.guarantees" />
      <ServiceExplanation :items="detail.explanations" />
      <ServiceReviews
        v-if="
          detail.ratingAverage !== null &&
          detail.reviewCount !== null &&
          detail.positiveRateLabel
        "
        :average="detail.ratingAverage"
        :total="detail.reviewCount"
        :positive-rate-label="detail.positiveRateLabel"
        :filters="detail.reviewFilters"
        :reviews="detail.reviews"
        :selected-filter="selectedReviewFilter"
        @select-filter="selectedReviewFilter = $event"
        @view-all="showUnavailable('全部评价能力待接入')"
      />
    </main>

    <view v-else class="inspection-service-detail__empty">
      <UniIcons type="info-filled" size="42" color="#b5bac1" />
      <text class="inspection-service-detail__empty-title">
        服务详情暂未开放
      </text>
      <text class="inspection-service-detail__empty-copy">
        正式详情数据接入后将在这里展示
      </text>
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
    calc(36px + env(safe-area-inset-top))
    0
    calc(72px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 6px;
  background: #f7f8fa;
  box-sizing: border-box;
}

.inspection-service-detail__empty {
  display: flex;
  min-height: 100vh;
  padding:
    calc(90px + env(safe-area-inset-top))
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
</style>
