<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

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

const currentIndex = ref(0)
const carousel = ref<{ $el: HTMLElement } | null>(null)
const motion = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(prefers-reduced-motion: reduce)')
  : null
const reducedMotion = ref(motion?.matches ?? false)
const pageHidden = ref(false)
const hovering = ref(false)
const focused = ref(false)
const hasSeveralSlides = computed(() => props.slides.length > 1)
const autoplay = computed(() => hasSeveralSlides.value && props.active && !reducedMotion.value
  && !pageHidden.value && !hovering.value && !focused.value)

function syncMotion(): void { reducedMotion.value = motion?.matches ?? false }
function syncVisibility(): void { pageHidden.value = document.hidden }

onMounted(() => {
  motion?.addEventListener?.('change', syncMotion)
  if (typeof document !== 'undefined') {
    syncVisibility()
    document.addEventListener('visibilitychange', syncVisibility)
  }
})
onBeforeUnmount(() => {
  motion?.removeEventListener?.('change', syncMotion)
  if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', syncVisibility)
})

// On H5 uni-view converts component events to UniApp events, dropping pointer coordinates.
watch(carousel, (view, _, onCleanup) => {
  // #ifdef H5
  const el = view?.$el
  if (!el) return
  let start: { x: number; y: number } | undefined
  let dragged = false
  function enter(event: PointerEvent) { hovering.value = event.pointerType === 'mouse' }
  function leave() { hovering.value = false }
  function focusIn() { focused.value = true }
  function focusOut() { focused.value = false }
  function pointerDown(event: PointerEvent) {
    start = { x: event.clientX, y: event.clientY }
    dragged = false
  }
  function pointerMove(event: PointerEvent) {
    if (start && Math.hypot(event.clientX - start.x, event.clientY - start.y) > 8) dragged = true
  }
  function pointerUp(event: PointerEvent) {
    pointerMove(event)
    start = undefined
  }
  function suppressDragClick(event: MouseEvent) {
    if (dragged && event.detail !== 0) {
      event.preventDefault()
      event.stopImmediatePropagation()
    }
    dragged = false
  }
  function activateWithKeyboard(event: KeyboardEvent) {
    if (!(event.target instanceof HTMLElement) || !event.target.matches('.home-carousel__slide')) return
    const step = event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0
    if (step && hasSeveralSlides.value) {
      event.preventDefault()
      currentIndex.value = (currentIndex.value + step + props.slides.length) % props.slides.length
      void nextTick(() => el?.querySelector<HTMLElement>('.home-carousel__slide[tabindex="0"]')?.focus({ preventScroll: true }))
      return
    }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      const slide = props.slides[currentIndex.value]
      if (slide && !event.repeat) emit('select', slide.target)
    }
  }
  el.addEventListener('keydown', activateWithKeyboard)
  el.addEventListener('pointerenter', enter)
  el.addEventListener('pointerleave', leave)
  el.addEventListener('focusin', focusIn)
  el.addEventListener('focusout', focusOut)
  el.addEventListener('pointerdown', pointerDown, true)
  el.addEventListener('pointermove', pointerMove, true)
  el.addEventListener('pointerup', pointerUp, true)
  el.addEventListener('click', suppressDragClick, true)
  onCleanup(() => {
    el.removeEventListener('keydown', activateWithKeyboard)
    el.removeEventListener('pointerenter', enter)
    el.removeEventListener('pointerleave', leave)
    el.removeEventListener('focusin', focusIn)
    el.removeEventListener('focusout', focusOut)
    el.removeEventListener('pointerdown', pointerDown, true)
    el.removeEventListener('pointermove', pointerMove, true)
    el.removeEventListener('pointerup', pointerUp, true)
    el.removeEventListener('click', suppressDragClick, true)
    hovering.value = false
    focused.value = false
  })
  // #endif
})

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
    ref="carousel"
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
      <swiper-item v-for="(slide, index) in slides" :key="slide.id">
        <button
          :class="[
            'home-carousel__slide',
            `home-carousel__slide--${slide.tone.toLowerCase()}`,
          ]"
          :data-testid="`home-carousel-slide-${slide.id}`"
          role="button"
          :aria-label="slide.actionLabel"
          :tabindex="index === currentIndex ? 0 : -1"
          @click="emit('select', slide.target)"
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
            <view
              class="home-carousel__action"
              :data-testid="`home-carousel-action-${slide.id}`"
            >
              <text>{{ slide.actionLabel }}</text>
              <UniIcons type="right" size="13" color="#ffffff" />
            </view>
          </view>
        </button>
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
  pointer-events: none;
  border: 0;
}

.home-carousel__swiper {
  height: 228px;
}

.home-carousel__slide {
  position: relative;
  display: block;
  width: 100%;
  border-radius: 0;
  text-align: left;
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

.home-carousel__slide--sky .home-carousel__image {
  width: 100%;
}

.home-carousel__slide--violet .home-carousel__image {
  width: 61%;
}

.home-carousel__slide--sky .home-carousel__image :deep(div:first-child),
.home-carousel__slide--sun .home-carousel__image :deep(div:first-child) {
  background-position: 60% center !important;
}

.home-carousel__slide--sky .home-carousel__image :deep(img),
.home-carousel__slide--sun .home-carousel__image :deep(img) {
  object-position: 60% center;
}

/* Keeps the copy readable where the picture runs under it. */
.home-carousel__fade {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    var(--home-carousel-bg) 8%,
    var(--home-carousel-bg) 30%,
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
