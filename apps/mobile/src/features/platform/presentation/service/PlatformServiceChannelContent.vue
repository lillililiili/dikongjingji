<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import PlatformHeroBanner from '@/shared/presentation/PlatformHeroBanner.vue'
import type { PlatformFeatureIntent } from '../../domain/platform-feature-intent'
import type { PlatformServiceChannelContent } from '../../domain/platform-service-content'

const props = defineProps<{
  content: PlatformServiceChannelContent
}>()

const emit = defineEmits<{
  intent: [intent: PlatformFeatureIntent]
}>()

const capabilityIcons = ['medal-filled', 'auth-filled', 'paperplane-filled', 'star-filled']
const capabilityColors = ['#ff9900', '#27b85a', '#2688ff', '#8c62ff']
const itemKind = {
  AERIAL: 'AERIAL_SERVICE',
  FLIGHT: 'FLIGHT_SERVICE',
  ROBOT: 'ROBOT_SERVICE',
} as const

function openItem(itemId: string): void {
  emit('intent', {
    type: 'OPEN_ITEM',
    itemId,
    itemKind: itemKind[props.content.code],
  })
}
</script>

<template>
  <view class="service-channel">
    <PlatformHeroBanner
      :image-url="content.hero.imageUrl"
      :image-alt="`${content.hero.title}${content.hero.highlight}`"
      :title="content.hero.title"
      :highlight="content.hero.highlight"
      :description="content.hero.description"
      :highlights="content.hero.highlights"
      :action-label="content.hero.actionLabel"
      @action="emit('intent', { type: 'SEARCH', channel: content.code })"
    />

    <view class="service-channel__scenario-grid">
      <button
        v-for="scenario in content.scenarios"
        :key="scenario.code"
        class="service-channel__scenario"
        @click="openItem(scenario.code)"
      >
        <image
          class="service-channel__scenario-image"
          :src="scenario.imageUrl"
          mode="aspectFill"
        />
        <text class="service-channel__scenario-title">{{ scenario.title }}</text>
        <text class="service-channel__scenario-subtitle">{{ scenario.subtitle }}</text>
      </button>
    </view>

    <section class="service-channel__panel">
      <view class="service-channel__heading">
        <text class="service-channel__heading-title">{{ content.capabilityTitle }}</text>
        <text class="service-channel__heading-copy">{{ content.capabilitySubtitle }}</text>
      </view>
      <view class="service-channel__capability-grid">
        <view
          v-for="(capability, index) in content.capabilities"
          :key="capability.code"
          class="service-channel__capability"
        >
          <view class="service-channel__capability-icon">
            <UniIcons
              :type="capabilityIcons[index] || 'checkbox-filled'"
              size="20"
              :color="capabilityColors[index] || '#25ad54'"
            />
          </view>
          <view class="service-channel__capability-copy">
            <text class="service-channel__capability-title">{{ capability.title }}</text>
            <text class="service-channel__capability-description">
              {{ capability.description }}
            </text>
          </view>
        </view>
      </view>
    </section>

    <section class="service-channel__panel">
      <view class="service-channel__heading">
        <text class="service-channel__heading-title">{{ content.recommendationTitle }}</text>
        <view class="service-channel__heading-fire">
          <UniIcons type="fire-filled" size="15" color="#d84b00" />
        </view>
        <text class="service-channel__heading-copy">{{ content.recommendationSubtitle }}</text>
      </view>
      <scroll-view class="service-channel__recommendation-scroll" scroll-x>
        <view class="service-channel__recommendation-row">
          <button
            v-for="service in content.recommendations"
            :key="service.id"
            class="service-channel__card"
            @click="openItem(service.id)"
          >
            <image
              class="service-channel__card-image"
              :src="service.imageUrl"
              mode="aspectFill"
            />
            <text class="service-channel__card-title">{{ service.title }}</text>
            <text class="service-channel__card-subtitle">{{ service.subtitle }}</text>
            <text v-if="service.priceLabel" class="service-channel__card-price">
              {{ service.priceLabel }}
            </text>
            <view class="service-channel__card-stats">
              <view v-if="service.rating !== undefined" class="service-channel__card-rating">
                <UniIcons type="star-filled" size="11" color="#e18400" />
                <text>{{ service.rating.toFixed(1) }}</text>
              </view>
              <text v-if="service.salesLabel">{{ service.salesLabel }}</text>
            </view>
            <view class="service-channel__card-action">{{ service.actionLabel }}</view>
          </button>
        </view>
      </scroll-view>
      <view class="service-channel__trust">
        <view><UniIcons type="checkbox" size="13" color="#59636f" /><text>平台担保</text></view>
        <view><UniIcons type="checkbox" size="13" color="#59636f" /><text>费用透明</text></view>
        <view><UniIcons type="person" size="13" color="#59636f" /><text>专业团队</text></view>
        <view><UniIcons type="star" size="13" color="#59636f" /><text>真实评价</text></view>
      </view>
    </section>
  </view>
</template>

<style scoped>
.service-channel {
  display: flex;
  flex-direction: column;
  gap: 10px;
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

.service-channel__scenario-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px 9px;
  padding: 10px;
  border: 1px solid #edf0f3;
  border-radius: 16px;
  background: #ffffff;
}

.service-channel__scenario {
  text-align: center;
}

.service-channel__scenario-image {
  width: 100%;
  height: 73px;
  border-radius: 12px;
  background: #eff5fa;
}

.service-channel__scenario-title {
  display: block;
  margin-top: 7px;
  overflow-wrap: break-word;
  color: #17191d;
  font-size: 14px;
  font-weight: 800;
  line-height: 1.2;
  white-space: nowrap;
}

.service-channel__scenario-subtitle {
  display: block;
  margin-top: 3px;
  color: var(--app-secondary-text);
  font-size: 11px;
  line-height: 1.3;
  white-space: normal;
}

.service-channel__panel {
  padding: 12px 10px 10px;
  border: 1px solid #edf0f3;
  border-radius: 16px;
  background: #ffffff;
}

.service-channel__heading {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  margin-bottom: 10px;
}

.service-channel__heading-title {
  color: #17191d;
  font-size: 17px;
  font-weight: 850;
}

.service-channel__heading-copy {
  min-width: 0;
  overflow: hidden;
  color: var(--app-secondary-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-channel__heading-fire {
  display: flex;
  align-items: center;
}

.service-channel__capability-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 10px;
}

.service-channel__capability {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
}

.service-channel__capability-icon {
  display: flex;
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #f2f7fc;
}

.service-channel__capability-copy {
  min-width: 0;
}

.service-channel__capability-title,
.service-channel__capability-description {
  display: block;
  overflow-wrap: anywhere;
}

.service-channel__capability-title {
  color: #252a30;
  font-size: 13px;
  font-weight: 800;
}

.service-channel__capability-description {
  margin-top: 2px;
  color: var(--app-secondary-text);
  font-size: 11px;
  line-height: 1.35;
}

.service-channel__recommendation-scroll {
  width: 100%;
  white-space: nowrap;
}

.service-channel__recommendation-row {
  display: grid;
  width: max-content;
  min-width: 100%;
  grid-auto-columns: calc((100vw - 49px) / 2);
  grid-auto-flow: column;
  gap: 10px;
  padding-right: 2px;
  box-sizing: border-box;
}

.service-channel__card {
  min-width: 0;
  text-align: left;
}

.service-channel__card-image {
  width: 100%;
  height: 108px;
  border-radius: 10px;
  background: #eef3f7;
}

.service-channel__card-title,
.service-channel__card-subtitle,
.service-channel__card-price {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-channel__card-title {
  margin-top: 6px;
  color: #17191d;
  font-size: 14px;
  font-weight: 850;
}

.service-channel__card-subtitle {
  margin-top: 3px;
  color: var(--app-secondary-text);
  font-size: 12px;
}

.service-channel__card-price {
  min-height: 19px;
  margin-top: 4px;
  color: #d84b00;
  font-size: 14px;
  font-weight: 850;
}

.service-channel__card-stats {
  display: flex;
  min-height: 16px;
  align-items: center;
  justify-content: space-between;
  color: var(--app-secondary-text);
  font-size: 11px;
}

.service-channel__card-rating {
  display: flex;
  align-items: center;
  gap: 2px;
}

.service-channel__card-action {
  height: 32px;
  margin-top: 6px;
  border-radius: 16px;
  color: #ffffff;
  background: linear-gradient(
    90deg,
    var(--app-action-orange-start),
    var(--app-action-orange-end)
  );
  font-size: 12px;
  font-weight: 800;
  line-height: 32px;
  text-align: center;
}

.service-channel__trust {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 5px;
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px solid #edf0f3;
  color: #59636f;
  font-size: 11px;
  text-align: center;
}

.service-channel__trust > view {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
}

@media (min-width: 420px) {
  .service-channel__scenario-image {
    height: 82px;
  }

  .service-channel__card-image {
    height: 116px;
  }
}
</style>
