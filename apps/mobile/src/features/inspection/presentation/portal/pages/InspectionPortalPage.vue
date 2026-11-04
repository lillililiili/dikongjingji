<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'

import { useCurrentCityStore } from '@/shared/city/state/current-city'

import { composeInspectionPortalContentForRuntime } from '../../../data/preview/inspection-portal-preview'
import { inspectionCatalogApi } from '../../../data/service-flow/uni-booking-http-client'
import {
  buildPortalCategoryUrl,
  buildPortalHotServiceUrl,
  buildPortalViewAllUrl,
} from '../../../application/portal/build-portal-navigation'
import {
  EMPTY_PORTAL_CONTENT,
  createInspectionPortalContentLoader,
} from '../../../application/portal/resolve-portal-content'
import type {
  InspectionPortalCategory,
  InspectionPortalContent,
  InspectionPortalHotService,
} from '../../../domain/inspection-portal-content'
import HotInspectionServices from '../components/HotInspectionServices.vue'
import InspectionCategoryGrid from '../components/InspectionCategoryGrid.vue'
import InspectionGuaranteeStrip from '../components/InspectionGuaranteeStrip.vue'
import InspectionHero from '../components/InspectionHero.vue'
import InspectionProcess from '../components/InspectionProcess.vue'

const currentCityStore = useCurrentCityStore()
const { currentCity } = storeToRefs(currentCityStore)
const content = ref<InspectionPortalContent>(EMPTY_PORTAL_CONTENT)
const isLoading = ref(true)
const loadError = ref('')
const showRequestAction = import.meta.env.DEV
const portalPreviewEnabled =
  import.meta.env.DEV ||
  import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
const portalContentLoader = createInspectionPortalContentLoader(
  (cityCode) => inspectionCatalogApi.loadPortal(cityCode),
)

onMounted(() => {
  if (!currentCity.value) {
    const defaultCityCode = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
    const defaultCityName = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
    if (defaultCityCode && defaultCityName) {
      currentCityStore.setCurrentCity({
        code: defaultCityCode,
        name: defaultCityName,
      })
      return
    }
  }
  void loadPortalContent()
})

watch(
  () => currentCity.value?.code,
  () => {
    void loadPortalContent()
  },
  { flush: 'sync' },
)

async function loadPortalContent(): Promise<void> {
  isLoading.value = true
  loadError.value = ''

  const cityCode = currentCity.value?.code
  if (!cityCode) {
    portalContentLoader.invalidate()
    content.value = EMPTY_PORTAL_CONTENT
    loadError.value = '巡检入口加载失败，请稍后重试'
    isLoading.value = false
    return
  }

  const result = await portalContentLoader.load(cityCode)
  if (result.kind === 'stale') return
  content.value = result.kind === 'loaded'
    ? portalPreviewEnabled
      ? composeInspectionPortalContentForRuntime(result.content)
      : result.content
    : EMPTY_PORTAL_CONTENT
  loadError.value = result.kind === 'error'
    ? '巡检入口加载失败，请稍后重试'
    : ''
  isLoading.value = false
}

function navigateToCatalog(url: string): void {
  uni.navigateTo({ url })
}

function selectCategory(category: InspectionPortalCategory): void {
  navigateToCatalog(
    buildPortalCategoryUrl(currentCity.value?.code, category.code),
  )
}

function selectHotService(service: InspectionPortalHotService): void {
  navigateToCatalog(
    buildPortalHotServiceUrl(
      currentCity.value?.code,
      service.categoryCode,
    ),
  )
}

function viewAllServices(): void {
  navigateToCatalog(buildPortalViewAllUrl(currentCity.value?.code))
}

function showUnavailable(message: string): void {
  uni.showToast({
    title: message,
    icon: 'none',
  })
}
</script>

<template>
  <view class="inspection-portal" data-testid="inspection-portal">
    <view class="inspection-portal__surface">
      <main class="inspection-portal__content">
        <view v-if="isLoading" class="portal-skeleton" aria-label="正在加载巡检入口">
          <view class="portal-skeleton__hero" />
          <view class="portal-skeleton__grid" />
        </view>

        <view v-else-if="loadError" class="portal-state">
          <text class="portal-state__title">暂时无法加载</text>
          <text class="portal-state__description">{{ loadError }}</text>
          <button
            class="portal-state__action"
            data-testid="portal-retry"
            @click="loadPortalContent"
          >
            重新加载
          </button>
        </view>

        <template v-else-if="content.hero">
          <InspectionHero
            :hero="content.hero"
            :show-request-action="showRequestAction"
            @request="showUnavailable('发布巡检需求将在下一阶段接入')"
          />
          <InspectionCategoryGrid
            :categories="content.categories"
            @select="selectCategory"
          />
          <InspectionGuaranteeStrip :guarantees="content.guarantees" />
          <HotInspectionServices
            :services="content.hotServices"
            @select="selectHotService"
            @view-all="viewAllServices"
          />
          <InspectionProcess :steps="content.processSteps" />
        </template>

        <view v-else class="portal-state">
          <text class="portal-state__title">巡检服务正在配置</text>
          <text class="portal-state__description">
            正式入口数据接入后将在这里展示
          </text>
        </view>
      </main>
    </view>
  </view>
</template>

<style scoped>
.inspection-portal {
  background: #f7f8fa;
  box-sizing: border-box;
}

.inspection-portal__surface {
  width: 100%;
  max-width: 600px;
  margin: 0 auto;
  background: #f7f8fa;
}

.inspection-portal__content {
  display: flex;
  padding: 0 0 clamp(12px, 3vw, 18px);
  flex-direction: column;
  gap: clamp(7px, 1.7vw, 10px);
  background: #f7f8fa;
  box-sizing: border-box;
}

.portal-skeleton {
  display: flex;
  flex-direction: column;
  gap: clamp(7px, 1.7vw, 10px);
}

.portal-skeleton__hero,
.portal-skeleton__grid {
  overflow: hidden;
  border-radius: 16px;
  background: linear-gradient(
    100deg,
    #eef0f3 20%,
    #f8f9fa 40%,
    #eef0f3 60%
  );
  background-size: 200% 100%;
  animation: portal-shimmer 1.2s ease-in-out infinite;
}

.portal-skeleton__hero {
  height: 248rpx;
}

.portal-skeleton__grid {
  height: clamp(152px, 40.5vw, 243px);
  border-radius: 12px;
}

.portal-state {
  display: flex;
  min-height: 300px;
  padding: 40px 24px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 1px solid #ebedf0;
  border-radius: 14px;
  background: #ffffff;
  box-sizing: border-box;
  text-align: center;
}

.portal-state__title {
  color: #25282d;
  font-size: 18px;
  font-weight: 700;
}

.portal-state__description {
  margin-top: 9px;
  color: #7e848c;
  font-size: 13px;
  line-height: 1.6;
}

.portal-state__action {
  min-width: 112px;
  min-height: 44px;
  margin-top: 18px;
  border: 0;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 650;
}

.portal-state__action::after {
  border: 0;
}

@keyframes portal-shimmer {
  0% {
    background-position: 120% 0;
  }

  100% {
    background-position: -120% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .portal-skeleton__hero,
  .portal-skeleton__grid {
    animation: none;
  }
}
</style>
