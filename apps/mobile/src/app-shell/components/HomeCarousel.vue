<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import {
  HOME_CAROUSEL_INTERVAL_MS,
  stepHomeCarousel,
  type HomeCarouselSlide,
} from '@/app-shell/navigation/home-carousel'
import type { HomeEntryTarget } from '@/app-shell/navigation/home-channels'

const props = defineProps<{
  slides: readonly HomeCarouselSlide[]
  // False while the home page or the recommend channel is hidden, so slides do not advance unseen.
  active: boolean
}>()

const emit = defineEmits<{
  select: [target: HomeEntryTarget]
}>()

function prefersReducedMotion(): boolean {
  // matchMedia only exists on H5; the App keeps auto-play on until the user pauses it.
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

const currentIndex = ref(0)
const userPaused = ref(prefersReducedMotion())
const hasSeveralSlides = computed(() => props.slides.length > 1)
const autoplay = computed(() =>
  hasSeveralSlides.value && props.active && !userPaused.value,
)

function handleChange(event: Event): void {
  const current = (
    event as Event & {
      detail?: {
        current?: number
      }
    }
  ).detail?.current

  currentIndex.value = current ?? 0
}

function step(delta: number): void {
  currentIndex.value = stepHomeCarousel(
    currentIndex.value,
    delta,
    props.slides.length,
  )
}

watch(() => props.slides.length, (slideCount) => {
  if (currentIndex.value >= slideCount) currentIndex.value = 0
})
</script>

<template>
  <view
    v-if="slides.length"
    class="home-carousel"
    data-testid="home-carousel"
    aria-roledescription="轮播图"
    aria-label="热门精选"
  >
    <swiper
      class="home-carousel__swiper"
      :current="currentIndex"
      :autoplay="autoplay"
      :interval="HOME_CAROUSEL_INTERVAL_MS"
      :circular="hasSeveralSlides"
      :duration="350"
      @change="handleChange"
    >
      <swiper-item v-for="slide in slides" :key="slide.id">
        <view
          :class="[
            'home-carousel__slide',
            `home-carousel__slide--${slide.tone.toLowerCase()}`,
          ]"
          :data-testid="`home-carousel-slide-${slide.id}`"
        >
          <image
            class="home-carousel__image"
            :src="slide.imageUrl"
            :alt="slide.imageAlt"
            mode="aspectFill"
          />
          <view class="home-carousel__fade" />
          <view class="home-carousel__copy">
            <text class="home-carousel__tag">{{ slide.tag }}</text>
            <view class="home-carousel__title">
              <text
                v-for="line in slide.titleLines"
                :key="line"
                class="home-carousel__title-line"
              >
                {{ line }}
              </text>
            </view>
            <view class="home-carousel__description">
              <text
                v-for="line in slide.descriptionLines"
                :key="line"
                class="home-carousel__description-line"
              >
                {{ line }}
              </text>
            </view>
            <button
              class="home-carousel__action"
              :data-testid="`home-carousel-action-${slide.id}`"
              @click="emit('select', slide.target)"
            >
              <text>{{ slide.actionLabel }}</text>
              <UniIcons type="right" size="13" color="#ffffff" />
            </button>
          </view>
        </view>
      </swiper-item>
    </swiper>

    <view v-if="hasSeveralSlides" class="home-carousel__bar">
      <text class="home-carousel__note">运营精选 · 示例内容</text>
      <view class="home-carousel__controls">
        <button
          class="home-carousel__control"
          data-testid="home-carousel-prev"
          aria-label="上一张"
          @click="step(-1)"
        >
          <UniIcons type="left" size="14" color="#596b84" />
        </button>
        <button
          v-for="(slide, index) in slides"
          :key="slide.id"
          class="home-carousel__control home-carousel__control--dot"
          :data-testid="`home-carousel-dot-${slide.id}`"
          :aria-label="`第 ${index + 1} 张：${slide.tag}`"
          :aria-pressed="index === currentIndex"
          @click="currentIndex = index"
        >
          <view
            :class="[
              'home-carousel__dot',
              { 'home-carousel__dot--active': index === currentIndex },
            ]"
          />
        </button>
        <button
          class="home-carousel__control"
          data-testid="home-carousel-next"
          aria-label="下一张"
          @click="step(1)"
        >
          <UniIcons type="right" size="14" color="#596b84" />
        </button>
        <button
          class="home-carousel__control home-carousel__control--pause"
          data-testid="home-carousel-pause"
          :aria-label="userPaused ? '播放轮播' : '暂停轮播'"
          :aria-pressed="userPaused"
          @click="userPaused = !userPaused"
        >
          <text class="home-carousel__pause-glyph">{{ userPaused ? '▶' : 'Ⅱ' }}</text>
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.home-carousel {
  overflow: hidden;
  border-radius: 22px;
  background: #edf4ff;
  box-shadow: 0 7px 22px rgba(48, 74, 117, 0.07);
}

button {
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

.home-carousel__swiper {
  height: 228px;
}

.home-carousel__slide {
  position: relative;
  height: 228px;
  overflow: hidden;
  background: var(--home-carousel-bg);
}

.home-carousel__slide--sky {
  --home-carousel-bg: #e9f5ff;
}

.home-carousel__slide--sun {
  --home-carousel-bg: #fff3df;
}

.home-carousel__slide--violet {
  --home-carousel-bg: #eee6fa;
}

.home-carousel__slide--mint {
  --home-carousel-bg: #e3f7ee;
}

.home-carousel__image {
  position: absolute;
  top: 0;
  right: 0;
  width: 67%;
  height: 100%;
}

/* Keeps the copy readable where the picture runs under it. */
.home-carousel__fade {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    var(--home-carousel-bg) 8%,
    var(--home-carousel-bg) 34%,
    rgba(255, 255, 255, 0) 89%
  );
}

.home-carousel__copy {
  position: relative;
  display: flex;
  width: 75%;
  height: 100%;
  padding: 18px 16px;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  box-sizing: border-box;
}

.home-carousel__tag {
  padding: 4px 8px;
  border: 1px solid rgba(255, 255, 255, 0.6);
  border-radius: 20px;
  color: #456b78;
  background: rgba(255, 255, 255, 0.69);
  font-size: 11px;
  font-weight: 600;
  line-height: 15px;
  white-space: nowrap;
}

.home-carousel__title {
  display: flex;
  flex-direction: column;
}

.home-carousel__title-line {
  color: #213b4a;
  font-size: 25px;
  font-weight: 700;
  line-height: 31px;
  white-space: nowrap;
}

.home-carousel__description {
  display: flex;
  flex-direction: column;
}

.home-carousel__description-line {
  color: #4e6472;
  font-size: 12px;
  line-height: 19px;
  white-space: nowrap;
}

.home-carousel__action {
  display: flex;
  min-height: 44px;
  margin-top: auto;
  padding: 0 15px;
  align-items: center;
  gap: 6px;
  border-radius: 24px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 5px 15px rgba(255, 106, 0, 0.19);
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.home-carousel__bar {
  display: flex;
  min-height: 44px;
  padding: 0 7px 0 12px;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.85);
}

.home-carousel__note {
  color: #64748b;
  font-size: 10px;
  white-space: nowrap;
}

.home-carousel__controls {
  display: flex;
  align-items: center;
}

.home-carousel__control {
  display: flex;
  min-width: 32px;
  min-height: 44px;
  align-items: center;
  justify-content: center;
}

.home-carousel__control--dot {
  min-width: 24px;
}

.home-carousel__control--pause {
  min-width: 38px;
}

.home-carousel__dot {
  width: 5px;
  height: 5px;
  border-radius: 9px;
  background: #bac4d3;
}

.home-carousel__dot--active {
  width: 15px;
  background: #ff6a00;
}

.home-carousel__pause-glyph {
  color: #596b84;
  font-size: 13px;
}
</style>
