<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'
import {
  DETAIL_PRIMARY_ACTION_LABELS,
  createDetailPrimaryIntent,
  resolveDetailPrimaryAction,
  resolveDetailServiceId,
  type PublishInspectionDemandIntent,
  type ReserveServiceIntent,
} from '../../../application/detail/inspection-detail-actions'
import {
  EMPTY_INSPECTION_SERVICE_DETAIL,
} from '../../../application/detail/resolve-inspection-service-detail'
import { ApiRequestError, inspectionServiceDetailApi, platformCityApi } from '../../../data/service-flow/uni-booking-http-client'
import type { InspectionServiceDetail } from '../../../domain/inspection-service-detail'
import { buildInspectionDetailUrl } from '../../../navigation/inspection-routes'
import InspectionScope from '../components/InspectionScope.vue'
import ServiceDeliverables from '../components/ServiceDeliverables.vue'
import ServiceDetailBottomBar from '../components/ServiceDetailBottomBar.vue'
import ServiceDetailHeader from '../components/ServiceDetailHeader.vue'
import ServiceDetailHero from '../components/ServiceDetailHero.vue'
import ServiceGuarantees from '../components/ServiceGuarantees.vue'
import ServicePricingCard from '../components/ServicePricingCard.vue'
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
const props = defineProps<{
  // The provider page belongs to the professional-service catalog (F2, preview builds).
  providerPageEnabled?: boolean
  // Whether the demand form is open (M15, preview builds); the host decides through its gateway.
  demandPublishEnabled?: boolean
}>()
const emit = defineEmits<{
  reserve: [intent: ReserveServiceIntent]
  publishDemand: [intent: PublishInspectionDemandIntent]
  openProvider: [providerId: string, cityCode: string]
}>()
const primaryActionKind = computed(() =>
  resolveDetailPrimaryAction(detail.value.primaryAction, Boolean(props.demandPublishEnabled)))
// 发布需求 needs no inspection items; 立即预约 needs at least one.
const primaryActionDisabled = computed(() =>
  primaryActionKind.value === 'PUBLISH_DEMAND' ? !routeServiceId.value : !canReserve.value)

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

// 客服 (step G1, preview builds): the provider of this service, with a card about it; without a
// provider on the page, the platform support. Public builds keep the typed notice.
function contactCustomerService(): void {
  if (!SUPPORT_PREVIEW_ENABLED) {
    showUnavailable('客服能力由平台统一接入')
    return
  }
  const service = detail.value
  const card = service.serviceId
    ? { type: 'INSPECTION_SERVICE' as const, refId: service.serviceId, title: service.title, subtitle: service.provider?.name ?? null }
    : null
  if (service.provider?.id) openSupportChat({ kind: 'PROVIDER', organizationId: service.provider.id }, card)
  else openSupportChat({ kind: 'PLATFORM' }, card)
}

// 分享 (preview builds): only 复制链接 of this service in this city.
const shareTarget = ref<ShareTarget | null>(null)
function share(): void {
  if (!SUPPORT_PREVIEW_ENABLED) {
    showUnavailable('分享能力由平台统一接入')
    return
  }
  const service = detail.value
  if (!service.serviceId || !detailCityCode.value) return
  shareTarget.value = {
    subject: '服务',
    title: service.title,
    subtitle: service.priceAmountMinor === null ? '按项目报价' : null,
    owner: service.provider?.name ?? null,
    imageUrl: service.heroImageUrl || null,
    path: buildInspectionDetailUrl(service.serviceId, detailCityCode.value),
  }
}

// 立即预约 or, on a project-priced service, 发布需求 (named for the reservation it began as).
function reserveService(): void {
  if (!routeServiceId.value) {
    return
  }

  const intent = createDetailPrimaryIntent(
    primaryActionKind.value,
    routeServiceId.value,
    detailCityCode.value,
    detail.value.provider?.id,
  )
  if (intent.type === 'PUBLISH_DEMAND') emit('publishDemand', intent)
  else emit('reserve', intent)
}
</script>

<template>
  <view class="inspection-service-detail" data-testid="inspection-detail">
    <ServiceDetailHeader
      @back="returnToCatalog"
      @share="share"
    />

    <main
      v-if="hasDetail"
      class="inspection-service-detail__content"
    >
      <ServiceDetailHero
        :detail="detail"
        :city-code="detailCityCode"
        :provider-link-enabled="props.providerPageEnabled"
        @open-provider="emit('openProvider', $event, detailCityCode)"
      />
      <ServicePricingCard :detail="detail" />
      <InspectionScope
        v-if="detail.inspectionItems.length"
        :items="detail.inspectionItems"
        :category-code="detail.categoryCode"
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
      <LineIcon name="info" :size="42" color="#b5bac1" />
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
      :disabled="primaryActionDisabled"
      :label="DETAIL_PRIMARY_ACTION_LABELS[primaryActionKind]"
      :action-kind="primaryActionKind"
      @customer-service="contactCustomerService"
      @reserve="reserveService"
    />
    <ShareSheet :target="shareTarget" @close="shareTarget = null" />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspDetail (390 wide). */
.inspection-service-detail {
  min-height: 100vh;
  color: #202c42;
  background: #f5f7fc;
  box-sizing: border-box;
}

.inspection-service-detail__content {
  display: flex;
  width: 100%;
  max-width: 600px;
  min-height: 100vh;
  margin: 0 auto;
  padding:
    calc(60px + var(--status-bar-height))
    0
    calc(82px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 10px;
  background: #f5f7fc;
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
