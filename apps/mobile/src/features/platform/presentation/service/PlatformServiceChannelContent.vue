<script lang="ts">
import type { ProServicePage } from '@/features/proservice/domain/proservice'

export type PlatformServiceListState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: ProServicePage }
  | { kind: 'error' }
</script>

<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import { closedCityText } from '@/features/proservice/application/proservice-view'
import ProServiceCard from '@/features/proservice/presentation/shared/ProServiceCard.vue'

import type { PlatformFeatureIntent } from '../../domain/platform-feature-intent'
import type {
  PlatformServiceChannelContent,
  PlatformServiceProcessStepCode,
} from '../../domain/platform-service-content'

defineProps<{
  content: PlatformServiceChannelContent
  typeLabel: string
  list: PlatformServiceListState
}>()

const emit = defineEmits<{
  intent: [intent: PlatformFeatureIntent]
  openService: [serviceId: string]
  retry: []
}>()

const PROCESS_ICONS: Record<PlatformServiceProcessStepCode, string> = {
  REQUEST: 'compose',
  QUOTE: 'wallet-filled',
  EXECUTE: 'calendar-filled',
  ACCEPT: 'checkbox-filled',
}
</script>

<template>
  <view class="service-entry" :data-testid="`service-entry-${content.code.toLowerCase()}`">
    <view class="service-entry__head">
      <view class="service-entry__mark" />
      <text class="service-entry__head-title">{{ typeLabel }}服务</text>
      <button
        class="service-entry__all"
        data-testid="service-entry-all"
        @click="emit('intent', { type: 'SEARCH', channel: content.code })"
      >
        查看全部服务 ›
      </button>
    </view>

    <view v-if="list.kind === 'loading'" class="service-entry__list" aria-label="正在加载">
      <view v-for="index in 2" :key="index" class="service-entry__skeleton" />
    </view>
    <view v-else-if="list.kind === 'error'" class="service-entry__state" data-testid="service-entry-error">
      <text class="service-entry__state-title">服务加载失败，请稍后重试</text>
      <button class="service-entry__state-action" data-testid="service-entry-retry" @click="emit('retry')">重新加载</button>
    </view>
    <view v-else-if="!list.page.cityOpen || !list.page.items.length" class="service-entry__state" data-testid="service-entry-closed">
      <text class="service-entry__state-title">{{ closedCityText(list.page.cityName, list.page.type) }}</text>
      <text class="service-entry__state-copy">可以先发布需求，服务商开通后报价</text>
    </view>
    <view v-else class="service-entry__list" data-testid="service-entry-list">
      <ProServiceCard
        v-for="service in list.page.items"
        :key="service.serviceId"
        :service="service"
        @open="emit('openService', $event)"
      />
    </view>

    <view class="service-entry__request">
      <view class="service-entry__request-copy">
        <text class="service-entry__request-title">非标需求？发布后多家报价</text>
        <text class="service-entry__request-sub">报价版本留档，比选后再签约</text>
      </view>
      <button
        class="service-entry__publish"
        data-testid="service-entry-publish"
        @click="emit('intent', { type: 'PUBLISH_DEMAND', channel: content.code })"
      >
        发布需求
      </button>
    </view>

    <view v-if="content.processSteps.length" class="service-entry__process">
      <text class="service-entry__process-title">服务流程</text>
      <view class="service-entry__steps">
        <template v-for="(step, index) in content.processSteps" :key="step.code">
          <view class="service-entry__step">
            <view class="service-entry__step-icon">
              <UniIcons :type="PROCESS_ICONS[step.code]" size="16" color="#2f80ed" />
            </view>
            <text class="service-entry__step-label">{{ step.title }}</text>
          </view>
          <UniIcons
            v-if="index < content.processSteps.length - 1"
            class="service-entry__arrow"
            type="right"
            size="12"
            color="#91a0b4"
          />
        </template>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board HomeSvc (390 wide), types other than inspection. */
.service-entry {
  display: flex;
  flex-direction: column;
  gap: 10px;
  color: #202c42;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
}

button::after {
  border: 0;
}

.service-entry__process {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.service-entry__head {
  display: flex;
  height: 24px;
  align-items: center;
  gap: 6px;
}

.service-entry__mark {
  width: 4px;
  height: 16px;
  flex: none;
  border-radius: 2px;
  background: #ff6a00;
}

.service-entry__head-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  white-space: nowrap;
}

.service-entry__all {
  height: 44px;
  margin: -10px 0 -10px auto;
  color: #627187;
  font-size: 13px;
  line-height: 44px;
  white-space: nowrap;
}

.service-entry__list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.service-entry__skeleton {
  height: 129px;
  border-radius: 14px;
  background: #ffffff;
}

.service-entry__state {
  display: flex;
  padding: 20px 14px;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  border-radius: 14px;
  background: #ffffff;
  text-align: center;
}

.service-entry__state-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.service-entry__state-copy {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.service-entry__state-action {
  height: 44px;
  margin-top: 4px;
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

.service-entry__request {
  display: flex;
  padding: 12px 12px 12px 14px;
  align-items: center;
  gap: 10px;
  border-radius: 14px;
  background: linear-gradient(90deg, #fff0e3 0%, #fffaf5 100%);
}

.service-entry__request-copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 2px;
}

.service-entry__request-title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-entry__request-sub {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-entry__publish {
  height: 44px;
  flex: none;
  padding: 0 16px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
  line-height: 44px;
  white-space: nowrap;
}

.service-entry__process {
  padding: 12px;
  gap: 8px;
}

.service-entry__process-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 20px;
}

.service-entry__steps {
  display: flex;
  align-items: flex-start;
}

.service-entry__step {
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.service-entry__step-icon {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #e6f0ff;
}

.service-entry__step-label {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.service-entry__arrow {
  margin-top: 8px;
  flex: 0 0 auto;
}
</style>
