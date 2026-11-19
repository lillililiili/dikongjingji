<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref } from 'vue'

import { openSupportChat } from '@/features/support/application/support-entry'
import { ApiRequestError } from '@/shared/http/public-api-request'
import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'

import {
  PRO_SERVICE_TYPE_INFO,
  avatarText,
  badgeTone,
  buildProServiceSections,
  citiesText,
  formatProServicePrice,
  modeIcon,
  stepIcon,
} from '../../application/proservice-view'
import { proServiceApi } from '../../data/proservice-api-client'
import { buildProServiceDetailUrl } from '../../navigation/proservice-routes'
import type { ProServiceDetail, ProServiceType } from '../../domain/proservice'
import ProServiceCover from '../shared/ProServiceCover.vue'

// One service of the four types (SvcAerial, SvcSurvey, SvcMaint, SvcTech boards share this
// template): no booking and no time slots; the main action is 发布需求.
const props = defineProps<{
  serviceId: string
  cityCode: string
}>()

const emit = defineEmits<{
  back: []
  // Back to a list when the service cannot be shown here.
  browse: []
  openProvider: [providerId: string, cityCode: string]
  publish: [type: ProServiceType, providerId: string | null]
}>()

type DetailState =
  | { kind: 'loading' }
  | { kind: 'ready'; detail: ProServiceDetail }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<DetailState>({ kind: 'loading' })
let requestVersion = 0

const detail = computed(() => (state.value.kind === 'ready' ? state.value.detail : null))
const info = computed(() => (detail.value ? PRO_SERVICE_TYPE_INFO[detail.value.type] : null))
const price = computed(() => (detail.value ? formatProServicePrice(detail.value.price) : null))
const sections = computed(() => (detail.value ? buildProServiceSections(detail.value) : []))
const serviceCities = computed(() => (detail.value ? citiesText(detail.value.cities) : ''))

async function load(): Promise<void> {
  if (!props.serviceId || !props.cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  try {
    const loaded = await proServiceApi.loadService(props.serviceId, props.cityCode)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', detail: loaded }
  } catch (error) {
    if (requested !== requestVersion) return
    // 404: not on sale in this city (or gone); 400: a broken link. Retrying cannot help either.
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

// 客服 (step G1): the provider of this service, with a card about it; the page is only in preview
// builds, so the conversation is always open. Without a provider the platform support answers.
function contactCustomerService(): void {
  const current = detail.value
  if (!current) return
  const card = {
    type: 'PRO_SERVICE' as const,
    refId: current.serviceId,
    title: current.title,
    subtitle: price.value ? `${price.value.amount}${price.value.unit}` : null,
  }
  if (current.provider) openSupportChat({ kind: 'PROVIDER', organizationId: current.provider.providerId }, card)
  else openSupportChat({ kind: 'PLATFORM' }, card)
}

// 分享: only 复制链接 of this service in this city.
const shareTarget = ref<ShareTarget | null>(null)
function share(): void {
  const current = detail.value
  if (!current) return
  shareTarget.value = {
    subject: '服务',
    title: current.title,
    subtitle: price.value ? `${price.value.amount}${price.value.unit}` : null,
    owner: current.provider?.name ?? null,
    imageUrl: current.coverUrl,
    path: buildProServiceDetailUrl(current.serviceId, props.cityCode),
  }
}

function openProvider(): void {
  const provider = detail.value?.provider
  if (provider) emit('openProvider', provider.providerId, props.cityCode)
}

function publish(): void {
  const current = detail.value
  if (!current) return
  emit('publish', current.type, current.provider?.providerId ?? null)
}

void load()
</script>

<template>
  <view class="pro-detail" data-testid="pro-detail" :data-state="state.kind">
    <view class="pro-detail__header">
      <button class="pro-detail__icon-button" data-testid="pro-detail-back" aria-label="返回" @click="emit('back')">
        <UniIcons type="left" size="22" color="#202c42" />
      </button>
      <text class="pro-detail__title">服务详情</text>
      <button
        class="pro-detail__icon-button pro-detail__share"
        data-testid="pro-detail-share"
        aria-label="分享"
        @click="share"
      >
        <UniIcons type="redo" size="20" color="#202c42" />
      </button>
    </view>

    <view v-if="state.kind === 'loading'" class="pro-detail__body" aria-label="正在加载">
      <view class="pro-detail__skeleton pro-detail__skeleton--hero" />
      <view class="pro-detail__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing' || state.kind === 'error'" class="pro-detail__state" data-testid="pro-detail-unavailable">
      <UniIcons type="info-filled" size="42" color="#b5bac1" />
      <text class="pro-detail__state-title">服务详情暂不可用</text>
      <text class="pro-detail__state-copy">
        {{ state.kind === 'missing' ? '当前城市暂无这项服务，或服务已下架' : '服务详情加载失败，请检查网络后重试' }}
      </text>
      <button
        class="pro-detail__state-action"
        data-testid="pro-detail-recover"
        @click="state.kind === 'missing' ? emit('browse') : load()"
      >
        {{ state.kind === 'missing' ? '看看其他服务' : '重新加载' }}
      </button>
    </view>

    <template v-else-if="detail && info && price">
      <view class="pro-detail__hero">
        <ProServiceCover
          :type="detail.type"
          :category="detail.category"
          :cover-url="detail.coverUrl"
          :title="detail.title"
          :label="`${info.label}服务`"
          :icon-size="72"
          pill
        />
      </view>

      <view class="pro-detail__body pro-detail__body--overlap">
        <view class="pro-detail__card pro-detail__card--main">
          <view class="pro-detail__name-line">
            <text class="pro-detail__name" data-testid="pro-detail-title">{{ detail.title }}</text>
            <text v-if="detail.sample" class="pro-detail__sample" data-testid="pro-detail-sample">示例</text>
          </view>
          <text class="pro-detail__summary">{{ detail.summary }}</text>
          <view class="pro-detail__price-line" data-testid="pro-detail-price">
            <text :class="['pro-detail__amount', { 'pro-detail__amount--quote': price.quote }]">{{ price.amount }}</text>
            <text v-if="price.unit" class="pro-detail__unit">{{ price.unit }}</text>
          </view>
          <text v-if="price.note" class="pro-detail__price-note">{{ price.note }}</text>
          <view v-if="detail.capabilities.length" class="pro-detail__capabilities">
            <view v-for="capability in detail.capabilities" :key="capability" class="pro-detail__capability">
              <UniIcons type="checkmarkempty" size="14" color="#11804a" />
              <text>{{ capability }}</text>
            </view>
          </view>
          <view v-if="detail.provider" class="pro-detail__provider" data-testid="pro-detail-provider">
            <text class="pro-detail__avatar" aria-hidden="true">{{ avatarText(detail.provider.name) }}</text>
            <view class="pro-detail__provider-copy">
              <text class="pro-detail__provider-name">{{ detail.provider.name }}</text>
              <view class="pro-detail__provider-meta">
                <text v-if="detail.provider.certificationLabel" class="pro-detail__certification">
                  {{ detail.provider.certificationLabel }}
                </text>
                <text v-if="serviceCities" class="pro-detail__cities">{{ serviceCities }}</text>
              </view>
            </view>
            <button
              class="pro-detail__provider-link"
              data-testid="pro-detail-open-provider"
              @click="openProvider"
            >
              查看服务商 ›
            </button>
          </view>
        </view>

        <view v-if="detail.priceRows.length" class="pro-detail__card pro-detail__card--rows">
          <text class="pro-detail__section-title">价格与取消</text>
          <view class="pro-detail__price-rows">
            <view
              v-for="(row, index) in detail.priceRows"
              :key="row.label"
              :class="['pro-detail__price-row', { 'pro-detail__price-row--first': index === 0 }]"
            >
              <text class="pro-detail__price-key">{{ row.label }}</text>
              <text class="pro-detail__price-value">{{ row.detail }}</text>
            </view>
          </view>
        </view>

        <view
          v-for="section in sections"
          :key="section.title"
          class="pro-detail__card"
          :data-testid="`pro-detail-section-${section.kind}`"
        >
          <text class="pro-detail__section-title">{{ section.title }}</text>

          <template v-if="section.kind === 'scope'">
            <view class="pro-detail__scope">
              <view v-for="item in section.items" :key="item" class="pro-detail__scope-item">
                <UniIcons type="checkbox" size="18" :color="info.badgeColor" />
                <text>{{ item }}</text>
              </view>
            </view>
            <text v-if="section.note" class="pro-detail__note">{{ section.note }}</text>
          </template>

          <view v-else-if="section.kind === 'outputs'" class="pro-detail__outputs">
            <view v-for="line in section.lines" :key="line.label" class="pro-detail__output">
              <view class="pro-detail__output-icon">
                <UniIcons type="folder-add" size="20" color="#1d63c4" />
              </view>
              <view class="pro-detail__output-copy">
                <text class="pro-detail__output-label">{{ line.label }}</text>
                <text class="pro-detail__output-detail">{{ line.detail }}</text>
              </view>
            </view>
          </view>

          <view v-else-if="section.kind === 'modes'" class="pro-detail__modes">
            <view v-for="line in section.lines" :key="line.label" class="pro-detail__mode">
              <view class="pro-detail__mode-icon">
                <UniIcons :type="modeIcon(line.label)" size="18" color="#ba470b" />
              </view>
              <text class="pro-detail__mode-label">{{ line.label }}</text>
              <text class="pro-detail__mode-detail">{{ line.detail }}</text>
            </view>
          </view>

          <template v-else-if="section.kind === 'rows'">
            <view class="pro-detail__term-rows">
              <view
                v-for="(line, index) in section.lines"
                :key="line.label"
                :class="['pro-detail__term-row', { 'pro-detail__term-row--first': index === 0 }]"
              >
                <text class="pro-detail__term-key">{{ line.label }}</text>
                <text class="pro-detail__term-value">{{ line.detail }}</text>
              </view>
            </view>
            <text v-if="section.note" class="pro-detail__note">{{ section.note }}</text>
          </template>

          <view v-else class="pro-detail__badges">
            <view v-for="(line, index) in section.lines" :key="line.label" class="pro-detail__badge-row">
              <text
                class="pro-detail__badge"
                :style="{ background: badgeTone(index).background, color: badgeTone(index).color }"
              >
                {{ line.label }}
              </text>
              <text class="pro-detail__badge-detail">{{ line.detail }}</text>
            </view>
          </view>
        </view>

        <view v-if="detail.steps.length" class="pro-detail__card" data-testid="pro-detail-steps">
          <text class="pro-detail__section-title">服务流程</text>
          <view class="pro-detail__steps">
            <template v-for="(step, index) in detail.steps" :key="step">
              <view class="pro-detail__step">
                <view class="pro-detail__step-icon">
                  <UniIcons :type="stepIcon(index, detail.steps.length)" size="15" color="#1d63c4" />
                </view>
                <text class="pro-detail__step-label">{{ step }}</text>
              </view>
              <UniIcons
                v-if="index < detail.steps.length - 1"
                class="pro-detail__step-arrow"
                type="right"
                size="11"
                color="#91a0b4"
              />
            </template>
          </view>
          <!-- The board's 首付 / 尾款 line is left out: paying for these services is not open. -->
          <view class="pro-detail__step-note">
            <UniIcons type="info" size="13" color="#627187" />
            <text>提交需求不产生费用</text>
          </view>
        </view>

        <view v-if="detail.guarantees.length" class="pro-detail__card">
          <text class="pro-detail__section-title">服务保障</text>
          <view class="pro-detail__guarantees">
            <view v-for="guarantee in detail.guarantees" :key="guarantee" class="pro-detail__guarantee">
              <UniIcons type="auth" size="14" color="#11804a" />
              <text>{{ guarantee }}</text>
            </view>
          </view>
        </view>
      </view>

      <view class="pro-detail__bar">
        <button class="pro-detail__bar-service" data-testid="pro-detail-customer-service" @click="contactCustomerService">
          <UniIcons type="headphones" size="20" color="#536178" />
          <text>客服</text>
        </button>
        <button class="pro-detail__publish" data-testid="pro-detail-publish" @click="publish">发布需求</button>
      </view>
      <ShareSheet :target="shareTarget" @close="shareTarget = null" />
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype boards SvcAerial / SvcSurvey / SvcMaint /
   SvcTech (390 wide). */
.pro-detail {
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

.pro-detail__header {
  position: sticky;
  z-index: 10;
  top: 0;
  display: flex;
  padding: calc(8px + var(--status-bar-height)) 8px 8px;
  align-items: center;
  gap: 4px;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.pro-detail__icon-button {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.pro-detail__share {
  margin-left: auto;
}

.pro-detail__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  white-space: nowrap;
}

.pro-detail__hero {
  height: 200px;
}

.pro-detail__body {
  display: flex;
  padding: 12px 16px;
  flex-direction: column;
  gap: 10px;
}

.pro-detail__body--overlap {
  position: relative;
  margin-top: -24px;
  padding-top: 0;
}

.pro-detail__skeleton {
  height: 160px;
  border-radius: 14px;
  background: #ffffff;
}

.pro-detail__skeleton--hero {
  height: 200px;
}

.pro-detail__state {
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

.pro-detail__state-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.pro-detail__state-copy {
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.pro-detail__state-action {
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

.pro-detail__card {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
}

.pro-detail__card--main {
  padding: 14px 14px 4px;
  gap: 0;
}

.pro-detail__card--rows {
  padding: 14px 14px 4px;
  gap: 4px;
}

.pro-detail__name-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.pro-detail__name {
  min-width: 0;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.pro-detail__sample {
  margin-top: 5px;
  flex: none;
  padding: 0 6px;
  border-radius: 6px;
  color: #536178;
  background: #eef2f8;
  font-size: 11px;
  line-height: 18px;
  white-space: nowrap;
}

.pro-detail__summary {
  margin-top: 2px;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.pro-detail__price-line {
  display: flex;
  margin-top: 8px;
  align-items: baseline;
  gap: 2px;
  white-space: nowrap;
}

.pro-detail__amount {
  color: #e85400;
  font-size: 28px;
  font-weight: 700;
  line-height: 36px;
}

.pro-detail__amount--quote {
  font-size: 22px;
}

.pro-detail__unit {
  color: #e85400;
  font-size: 14px;
  line-height: 20px;
}

.pro-detail__price-note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.pro-detail__capabilities {
  display: flex;
  margin-top: 10px;
  flex-wrap: wrap;
  gap: 4px 16px;
}

.pro-detail__capability {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.pro-detail__provider {
  display: flex;
  min-height: 60px;
  margin-top: 12px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.pro-detail__avatar {
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 50%;
  color: #ba470b;
  background: #fff0e3;
  font-size: 15px;
  font-weight: 700;
  line-height: 36px;
  text-align: center;
}

.pro-detail__provider-copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 2px;
}

.pro-detail__provider-name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pro-detail__provider-meta {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.pro-detail__certification {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.pro-detail__cities {
  overflow: hidden;
  min-width: 0;
  color: #738096;
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pro-detail__provider-link {
  height: 44px;
  flex: none;
  padding-left: 4px;
  color: #627187;
  font-size: 13px;
  line-height: 44px;
  white-space: nowrap;
}

.pro-detail__section-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.pro-detail__price-rows,
.pro-detail__term-rows {
  display: flex;
  flex-direction: column;
}

.pro-detail__price-row {
  display: flex;
  padding: 10px 0;
  align-items: flex-start;
  gap: 12px;
  border-top: 1px solid #f0f1f3;
}

.pro-detail__price-row--first,
.pro-detail__term-row--first {
  border-top-color: #ffffff;
}

.pro-detail__price-key {
  width: 56px;
  flex: none;
  color: #627187;
  font-size: 13px;
  line-height: 19px;
  white-space: nowrap;
}

.pro-detail__price-value {
  min-width: 0;
  flex: 1 1 auto;
  font-size: 13px;
  line-height: 19px;
}

.pro-detail__scope {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.pro-detail__scope-item {
  display: flex;
  min-height: 48px;
  min-width: 0;
  padding: 0 10px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  box-sizing: border-box;
}

.pro-detail__note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.pro-detail__outputs {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pro-detail__output {
  display: flex;
  min-height: 56px;
  padding: 8px 10px 8px 8px;
  align-items: center;
  gap: 10px;
  border-radius: 10px;
  background: #f5f7fc;
  box-sizing: border-box;
}

.pro-detail__output-icon {
  display: flex;
  width: 40px;
  height: 40px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #e6f0ff;
}

.pro-detail__output-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.pro-detail__output-label {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.pro-detail__output-detail {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.pro-detail__modes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.pro-detail__mode {
  display: flex;
  min-width: 0;
  padding: 12px;
  flex-direction: column;
  gap: 4px;
  border: 1px solid #ffe7d4;
  border-radius: 12px;
  background: #fffaf5;
}

.pro-detail__mode-icon {
  display: flex;
  width: 36px;
  height: 36px;
  margin-bottom: 4px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff0e3;
}

.pro-detail__mode-label {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.pro-detail__mode-detail {
  color: #627187;
  font-size: 12px;
  line-height: 18px;
}

.pro-detail__term-row {
  display: flex;
  min-height: 40px;
  padding: 10px 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-top: 1px solid #f0f1f3;
  box-sizing: border-box;
}

.pro-detail__term-key {
  flex: none;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.pro-detail__term-value {
  min-width: 0;
  font-size: 13px;
  line-height: 18px;
  text-align: right;
}

.pro-detail__badges {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pro-detail__badge-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.pro-detail__badge {
  width: 56px;
  height: 22px;
  flex: none;
  border-radius: 6px;
  font-size: 12px;
  line-height: 22px;
  text-align: center;
  white-space: nowrap;
}

.pro-detail__badge-detail {
  min-width: 0;
  color: #536178;
  font-size: 13px;
  line-height: 20px;
}

.pro-detail__steps {
  display: flex;
  align-items: flex-start;
}

.pro-detail__step {
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.pro-detail__step-icon {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #e6f0ff;
}

.pro-detail__step-label {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.pro-detail__step-arrow {
  margin-top: 8px;
  flex: none;
}

.pro-detail__step-note {
  display: flex;
  padding-top: 10px;
  align-items: center;
  gap: 4px;
  border-top: 1px solid #f0f1f3;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.pro-detail__guarantees {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.pro-detail__guarantee {
  display: flex;
  height: 36px;
  min-width: 0;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 10px;
  color: #11804a;
  background: #eefaf3;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.pro-detail__bar {
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
}

.pro-detail__bar-service {
  display: flex;
  width: 56px;
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

.pro-detail__publish {
  height: 46px;
  flex: 1 1 auto;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  white-space: nowrap;
}
</style>
