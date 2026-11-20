<script setup lang="ts">
import { openSupportChat } from '@/features/support/application/support-entry'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { providerCredentialLabel } from '@/shared/ui/qualification'

import {
  PRO_SERVICE_TYPE_INFO,
  avatarText,
  formatProServicePrice,
  providerDemandType,
  shortCityName,
} from '../../application/proservice-view'
import { proServiceApi } from '../../data/proservice-api-client'
import {
  inspectionServicePicture,
  resolveProServicePreviewCover,
} from '../../data/preview/proservice-preview-covers'
import type { ProServicePrice, ProServiceType, ProviderPage } from '../../domain/proservice'

// A provider's page (ProviderPage board): who it is, what it offers in this city and where it
// works. The board's 案例 and 资质 tabs, its 经营主体 / 服务方式 / 授权有效期 rows and its 可交付
// card are left out: the provider read has no cases, qualification rows, company facts or
// deliverables yet.
const props = defineProps<{
  providerId: string
  cityCode: string
}>()

const emit = defineEmits<{
  back: []
  browse: []
  openInspectionService: [serviceId: string, cityCode: string]
  openProService: [serviceId: string, cityCode: string]
  publish: [type: 'INSPECTION' | ProServiceType | undefined, providerId: string]
}>()

type PageState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: ProviderPage }
  | { kind: 'missing' }
  | { kind: 'error' }

type ProviderTab = 'scope' | 'cities'

const TABS: ReadonlyArray<{ code: ProviderTab; label: string }> = [
  { code: 'scope', label: '服务范围' },
  { code: 'cities', label: '可服务城市' },
]

interface ScopeRow {
  key: string
  kind: 'inspection' | 'pro'
  serviceId: string
  title: string
  summary: string
  typeLabel: string
  // A picture for the 96px photo; null leaves the grey placeholder.
  picture: string | null
  price: ProServicePrice
  sample: boolean
}

const state = ref<PageState>({ kind: 'loading' })
const tab = ref<ProviderTab>('scope')
let requestVersion = 0

const provider = computed(() => (state.value.kind === 'ready' ? state.value.page : null))
// The board's green 资质已审核 tag when the platform reviewed the provider (R6-4), else its own label.
const credential = computed(() =>
  provider.value ? providerCredentialLabel(provider.value.qualificationReviewed, provider.value.certificationLabel) : null,
)
// Inspection first (it has its own booking flow), then the four types in the server's order.
const scopeRows = computed<ScopeRow[]>(() => {
  const page = provider.value
  if (!page) return []
  return [
    ...page.inspectionServices.map((service) => ({
      key: `inspection-${service.serviceId}`,
      kind: 'inspection' as const,
      serviceId: service.serviceId,
      title: service.title,
      summary: service.summary,
      typeLabel: `巡检 · ${service.categoryName}`,
      picture: inspectionServicePicture(service.categoryName),
      price: service.price,
      sample: false,
    })),
    ...page.proServices.map((service) => ({
      key: `pro-${service.serviceId}`,
      kind: 'pro' as const,
      serviceId: service.serviceId,
      title: service.title,
      summary: service.summary,
      typeLabel: `${PRO_SERVICE_TYPE_INFO[service.type].label} · ${service.category.label}`,
      picture: service.coverUrl ?? resolveProServicePreviewCover(service.type, service.category.code),
      price: service.price,
      sample: service.sample,
    })),
  ]
})

async function load(): Promise<void> {
  if (!props.providerId || !props.cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  try {
    const page = await proServiceApi.loadProvider(props.providerId, props.cityCode)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', page }
  } catch (error) {
    if (requested !== requestVersion) return
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function openRow(row: ScopeRow): void {
  if (row.kind === 'inspection') emit('openInspectionService', row.serviceId, props.cityCode)
  else emit('openProService', row.serviceId, props.cityCode)
}

function publish(): void {
  const page = provider.value
  if (page) emit('publish', providerDemandType(page), page.providerId)
}

// 咨询 (step G1): this provider's conversation; the page is only in preview builds.
function showConsult(): void {
  const page = provider.value
  if (page) openSupportChat({ kind: 'PROVIDER', organizationId: page.providerId })
}

void load()
</script>

<template>
  <view class="provider-page" data-testid="provider-page" :data-state="state.kind">
    <view class="provider-page__top">
      <button class="provider-page__back" data-testid="provider-back" aria-label="返回" @click="emit('back')">
        <LineIcon name="back" :size="22" color="#202c42" :stroke-width="2" />
      </button>
      <view v-if="provider" class="provider-page__identity">
        <text class="provider-page__logo" aria-hidden="true">{{ avatarText(provider.name) }}</text>
        <view class="provider-page__identity-copy">
          <text class="provider-page__name" data-testid="provider-name">{{ provider.name }}</text>
          <view v-if="credential || provider.description" class="provider-page__tags">
            <text v-if="credential" class="provider-page__tag" data-testid="provider-certification">
              {{ credential }}
            </text>
            <!-- The board's orange tag (东营本地团队): the provider's own one-line description. -->
            <text v-if="provider.description" class="provider-page__tag provider-page__tag--team" data-testid="provider-description">
              {{ provider.description }}
            </text>
          </view>
        </view>
      </view>
    </view>

    <view v-if="state.kind === 'loading'" class="provider-page__body" aria-label="正在加载">
      <view class="provider-page__skeleton" />
      <view class="provider-page__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing' || state.kind === 'error'" class="provider-page__state" data-testid="provider-unavailable">
      <UniIcons type="info-filled" size="42" color="#b5bac1" />
      <text class="provider-page__state-title">服务商主页暂不可用</text>
      <text class="provider-page__state-copy">
        {{ state.kind === 'missing' ? '没有找到这家服务商，或它暂停了服务' : '加载失败，请检查网络后重试' }}
      </text>
      <button
        class="provider-page__state-action"
        data-testid="provider-recover"
        @click="state.kind === 'missing' ? emit('browse') : load()"
      >
        {{ state.kind === 'missing' ? '看看其他服务' : '重新加载' }}
      </button>
    </view>

    <template v-else-if="provider">
      <view class="provider-page__tabs" role="tablist" aria-label="服务商信息">
        <button
          v-for="item in TABS"
          :key="item.code"
          :class="['provider-page__tab', { 'provider-page__tab--on': tab === item.code }]"
          :data-testid="`provider-tab-${item.code}`"
          role="tab"
          :aria-selected="tab === item.code"
          @click="tab = item.code"
        >
          {{ item.label }}
        </button>
      </view>

      <view v-if="tab === 'scope'" class="provider-page__body" data-testid="provider-scope">
        <view v-if="!scopeRows.length" class="provider-page__empty">
          <text>当前城市暂时没有这家服务商的服务</text>
        </view>
        <button
          v-for="row in scopeRows"
          :key="row.key"
          class="provider-page__service"
          :data-testid="`provider-service-${row.serviceId}`"
          :data-kind="row.kind"
          @click="openRow(row)"
        >
          <view class="provider-page__service-cover" :aria-label="row.typeLabel">
            <image
              v-if="row.picture"
              class="provider-page__service-image"
              :src="row.picture"
              mode="aspectFill"
              lazy-load
            />
          </view>
          <view class="provider-page__service-copy">
            <view class="provider-page__service-title-line">
              <text class="provider-page__service-title">{{ row.title }}</text>
              <text v-if="row.sample" class="provider-page__sample">示例</text>
            </view>
            <text class="provider-page__service-summary">{{ row.summary }}</text>
            <view class="provider-page__service-price">
              <text :class="['provider-page__amount', { 'provider-page__amount--quote': formatProServicePrice(row.price).quote }]">
                {{ formatProServicePrice(row.price).amount }}
              </text>
              <text v-if="formatProServicePrice(row.price).unit" class="provider-page__unit">
                {{ formatProServicePrice(row.price).unit }}
              </text>
            </view>
          </view>
        </button>
      </view>

      <view v-else class="provider-page__body" data-testid="provider-cities">
        <view class="provider-page__cities">
          <view
            v-for="(city, index) in provider.cities"
            :key="city.cityCode"
            :class="['provider-page__city', { 'provider-page__city--last': index === provider.cities.length - 1 }]"
            :data-testid="`provider-city-${city.cityCode}`"
          >
            <view class="provider-page__city-copy">
              <text class="provider-page__city-name">{{ shortCityName(city.cityName) }}</text>
              <text v-if="city.cityCode === provider.cityCode" class="provider-page__city-sub">当前城市</text>
            </view>
            <text class="provider-page__city-state">可服务</text>
          </view>
          <view v-if="!provider.cities.length" class="provider-page__empty">
            <text>暂无可服务城市</text>
          </view>
        </view>
      </view>

      <view class="provider-page__bar">
        <button class="provider-page__consult" data-testid="provider-consult" @click="showConsult">
          <LineIcon name="chat" :size="22" color="#536178" />
          <text>咨询</text>
        </button>
        <button class="provider-page__publish" data-testid="provider-publish" @click="publish">发布需求</button>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board ProviderPage (390 wide). */
.provider-page {
  min-height: 100vh;
  padding-bottom: calc(80px + env(safe-area-inset-bottom));
  color: #202c42;
  background: #f5f7fc;
  box-sizing: border-box;
  font-family:
    "PingFang SC", "Noto Sans SC", system-ui, -apple-system, BlinkMacSystemFont,
    "Microsoft YaHei", sans-serif;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.provider-page__top {
  display: flex;
  padding: calc(8px + var(--status-bar-height)) 16px 16px;
  flex-direction: column;
  gap: 12px;
  background: var(--app-head-gradient);
}

.provider-page__back {
  display: flex;
  width: 44px;
  height: 44px;
  margin-left: -8px;
  align-items: center;
  justify-content: center;
}

.provider-page__identity {
  display: flex;
  align-items: center;
  gap: 12px;
}

.provider-page__logo {
  width: 56px;
  height: 56px;
  flex: none;
  border: 1px solid #e5ebf3;
  border-radius: 14px;
  color: #ff6a00;
  background: #ffffff;
  font-size: 22px;
  font-weight: 700;
  line-height: 54px;
  text-align: center;
  box-sizing: border-box;
}

.provider-page__identity-copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 4px;
}

.provider-page__name {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.provider-page__tags {
  display: flex;
  min-width: 0;
  gap: 6px;
}

.provider-page__tag {
  padding: 1px 6px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.provider-page__tag--team {
  overflow: hidden;
  min-width: 0;
  color: #ba470b;
  background: #fff0e3;
  text-overflow: ellipsis;
}

.provider-page__tabs {
  display: flex;
  padding: 0 16px;
  gap: 22px;
  border-bottom: 1px solid #f0f1f3;
  background: #ffffff;
}

.provider-page__tab {
  min-height: 44px;
  padding: 10px 0 7px;
  border-bottom: 3px solid transparent;
  border-radius: 0;
  color: #627187;
  font-size: 15px;
  line-height: 21px;
  white-space: nowrap;
}

.provider-page__tab--on {
  border-bottom-color: #ff6a00;
  color: #202c42;
  font-weight: 700;
}

.provider-page__body {
  display: flex;
  padding: 12px 16px;
  flex-direction: column;
  gap: 10px;
}

.provider-page__skeleton {
  height: 104px;
  border-radius: 12px;
  background: #ffffff;
}

.provider-page__state {
  display: flex;
  margin: 12px 16px;
  padding: 40px 16px;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  border-radius: 14px;
  background: #ffffff;
  text-align: center;
}

.provider-page__state-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.provider-page__state-copy {
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.provider-page__state-action {
  height: 44px;
  margin-top: 8px;
  padding: 0 22px;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 14px;
  font-weight: 500;
  line-height: 41px;
  white-space: nowrap;
  box-sizing: border-box;
}

.provider-page__empty {
  padding: 24px 14px;
  border-radius: 12px;
  color: #627187;
  background: #ffffff;
  font-size: 13px;
  line-height: 19px;
  text-align: center;
}

.provider-page__service {
  display: flex;
  width: 100%;
  padding: 10px;
  gap: 10px;
  border-radius: 12px;
  color: #202c42;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
  text-align: left;
  box-sizing: border-box;
}

.provider-page__service-cover {
  position: relative;
  display: flex;
  width: 96px;
  height: 84px;
  flex: none;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 8px;
  background: #eef2f8;
}

.provider-page__service-image {
  display: block;
  width: 96px;
  height: 84px;
}

.provider-page__service-copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 3px;
}

.provider-page__service-title-line {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}

.provider-page__service-title {
  min-width: 0;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

/* 示例 stays, as quiet grey words (user decision R5-14, 2026-11-20). */
.provider-page__sample {
  margin-top: 2px;
  flex: none;
  color: #738096;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.provider-page__service-summary {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.provider-page__service-price {
  display: flex;
  margin-top: auto;
  align-items: baseline;
  gap: 2px;
  white-space: nowrap;
}

.provider-page__amount {
  color: #e85400;
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
}

.provider-page__amount--quote {
  font-size: 14px;
}

.provider-page__unit {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.provider-page__cities {
  padding: 4px 14px;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.provider-page__city {
  display: flex;
  min-height: 60px;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid #f0f1f3;
}

.provider-page__city--last {
  border-bottom-color: transparent;
}

.provider-page__city-copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 2px;
}

.provider-page__city-name {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.provider-page__city-sub {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.provider-page__city-state {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.provider-page__bar {
  position: fixed;
  z-index: 20;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 10px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

.provider-page__consult {
  display: flex;
  width: 44px;
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

.provider-page__publish {
  height: 46px;
  flex: 1 1 auto;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  white-space: nowrap;
}
</style>
