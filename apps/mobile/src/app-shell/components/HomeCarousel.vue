<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import {
  HOME_CAROUSEL_INTERVAL_MS,
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
  // matchMedia only exists on H5; the App always auto-plays.
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

// Slides change by swiping only (user 2026-11-23: no arrows, dots or pause bar under the banner).
const currentIndex = ref(0)
const reducedMotion = prefersReducedMotion()
const hasSeveralSlides = computed(() => props.slides.length > 1)
const autoplay = computed(() =>
  hasSeveralSlides.value && props.active && !reducedMotion,
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

watch(() => props.slides.length, (slideCount) => {
  if (currentIndex.value >= slideCount) currentIndex.value = 0
})
</script>

<template>
  <view
    v-if="slides.length"
    class="home-carousel"
    data-testid="home-carousel"
    :data-current-slide="slides[currentIndex]?.id"
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

</style>
