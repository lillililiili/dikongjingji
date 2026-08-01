<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

const props = withDefaults(defineProps<{
  imageUrl: string
  imageAlt: string
  title: string
  highlight: string
  description: string
  highlights: readonly string[]
  actionLabel?: string
  actionDisabled?: boolean
}>(), {
  actionLabel: '',
  actionDisabled: false,
})

const emit = defineEmits<{
  action: []
}>()
</script>

<template>
  <view class="platform-hero-banner">
    <image
      class="platform-hero-banner__image"
      :src="props.imageUrl"
      mode="aspectFill"
      lazy-load
      :aria-label="props.imageAlt"
    />
    <view class="platform-hero-banner__veil" />
    <view class="platform-hero-banner__bottom-fade" />
    <view class="platform-hero-banner__copy">
      <view class="platform-hero-banner__title-row">
        <text class="platform-hero-banner__title">{{ props.title }}</text>
        <text class="platform-hero-banner__highlight">{{ props.highlight }}</text>
      </view>
      <text class="platform-hero-banner__description">{{ props.description }}</text>
      <view class="platform-hero-banner__points">
        <view
          v-for="point in props.highlights"
          :key="point"
          class="platform-hero-banner__point"
        >
          <UniIcons type="checkbox-filled" size="14" color="#1f9853" />
          <text>{{ point }}</text>
        </view>
      </view>
      <button
        v-if="props.actionLabel"
        class="platform-hero-banner__action"
        :disabled="props.actionDisabled"
        @click="emit('action')"
      >
        <text>{{ props.actionLabel }}</text>
        <UniIcons type="right" size="14" color="#ffffff" />
      </button>
    </view>
  </view>
</template>

<style scoped>
.platform-hero-banner {
  position: relative;
  height: var(--app-primary-banner-height);
  overflow: hidden;
  border-radius: var(--app-hero-radius);
  background: #dcefff;
}

.platform-hero-banner__image,
.platform-hero-banner__veil,
.platform-hero-banner__bottom-fade {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.platform-hero-banner__veil {
  background: linear-gradient(
    90deg,
    rgba(240, 249, 255, 0.98) 0%,
    rgba(240, 249, 255, 0.9) 36%,
    rgba(240, 249, 255, 0.18) 72%,
    rgba(240, 249, 255, 0) 100%
  );
}

.platform-hero-banner__bottom-fade {
  top: auto;
  height: 30%;
  background: linear-gradient(
    180deg,
    rgba(255, 255, 255, 0),
    rgba(255, 255, 255, 0.72)
  );
}

.platform-hero-banner__copy {
  position: relative;
  z-index: 1;
  display: flex;
  width: 72%;
  padding: var(--app-hero-content-top) 0 0 var(--app-hero-content-left);
  flex-direction: column;
  align-items: flex-start;
  box-sizing: border-box;
}

.platform-hero-banner__title-row,
.platform-hero-banner__points,
.platform-hero-banner__point,
.platform-hero-banner__action {
  display: flex;
  align-items: center;
}

.platform-hero-banner__title-row {
  gap: 10rpx;
  align-items: baseline;
}

.platform-hero-banner__title,
.platform-hero-banner__highlight {
  color: #111820;
  font-size: 34rpx;
  font-weight: 900;
  line-height: 1.08;
}

.platform-hero-banner__highlight {
  color: #ff5a00;
}

.platform-hero-banner__description {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 8rpx;
  color: #39434e;
  font-size: 18rpx;
  line-height: 1.35;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.platform-hero-banner__points {
  margin-top: 9rpx;
  gap: 13rpx;
  white-space: nowrap;
}

.platform-hero-banner__point {
  gap: 4rpx;
  color: #39434e;
  font-size: 15rpx;
}

.platform-hero-banner__action {
  min-height: var(--app-hero-action-height);
  margin-top: 10rpx;
  margin-right: 0;
  margin-left: 0;
  align-self: flex-start;
  justify-content: center;
  gap: var(--app-hero-action-gap);
  padding: 0 var(--app-hero-action-padding-inline);
  border: 0;
  border-radius: var(--app-hero-action-radius);
  color: #ffffff;
  background: linear-gradient(
    90deg,
    var(--app-action-orange-start),
    var(--app-action-orange-end)
  );
  font-size: var(--app-hero-action-font-size);
  font-weight: 800;
  line-height: var(--app-hero-action-height);
}

.platform-hero-banner__action:active {
  transform: scale(0.98);
}

.platform-hero-banner__action:disabled {
  opacity: 0.48;
}

.platform-hero-banner__action:focus-visible {
  outline: 2px solid #7a2800;
  outline-offset: 2px;
}

.platform-hero-banner__action::after {
  border: 0;
}

@media (max-width: 359px) {
  .platform-hero-banner__copy {
    width: 76%;
    padding-right: 16rpx;
    padding-left: 24rpx;
  }

  .platform-hero-banner__title,
  .platform-hero-banner__highlight {
    font-size: 32rpx;
  }

  .platform-hero-banner__description {
    font-size: 17rpx;
  }

  .platform-hero-banner__points {
    gap: 9rpx;
  }
}
</style>
