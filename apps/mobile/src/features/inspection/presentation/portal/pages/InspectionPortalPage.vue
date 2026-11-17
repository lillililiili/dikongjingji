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
  uni.navigateTo({
    url: buildPortalHotServiceUrl(currentCity.value?.code, service.id),
  })
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
          <InspectionGuaranteeStrip :guarantees="content.guarantees" />
          <InspectionCategoryGrid
            v-if="content.categories.length"
            :categories="content.categories"
            @select="selectCategory"
          />
          <!-- Publishing a request is not built yet, so the bar is only there in development. -->
          <view v-if="showRequestAction" class="portal-request" data-testid="portal-request">
            <view class="portal-request__copy">
              <text class="portal-request__title">非标需求？发布巡检需求</text>
              <text class="portal-request__note">说明场景和范围，由服务商给出方案</text>
            </view>
            <button
              class="portal-request__action"
              data-testid="portal-request-action"
              @click="showUnavailable('发布巡检需求将在下一阶段接入')"
            >
              发布巡检需求
            </button>
          </view>
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
/* Sizes and colours follow the 2026-11-14 prototype board HomeSvc (390 wide). */
.inspection-portal {
  background: #f5f7fc;
  box-sizing: border-box;
}

.inspection-portal__surface {
  width: 100%;
  max-width: 600px;
  margin: 0 auto;
  background: #f5f7fc;
}

.inspection-portal__content {
  display: flex;
  padding: 2px 0 16px;
  flex-direction: column;
  gap: 10px;
  background: #f5f7fc;
  box-sizing: border-box;
}

.portal-request {
  display: flex;
  padding: 12px 12px 12px 14px;
  align-items: center;
  gap: 10px;
  border-radius: 14px;
  background: linear-gradient(90deg, #fff0e3 0%, #fffaf5 100%);
}

.portal-request__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.portal-request__title {
  color: #202c42;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.portal-request__note {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.portal-request__action {
  display: flex;
  height: 44px;
  margin: 0;
  padding: 0 16px;
  flex: 0 0 auto;
  align-items: center;
  border: 0;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  font-size: 15px;
  font-weight: 500;
  line-height: 1.2;
  white-space: nowrap;
}

.portal-request__action::after {
  border: 0;
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
