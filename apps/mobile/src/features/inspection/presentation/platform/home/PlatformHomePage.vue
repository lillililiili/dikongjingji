<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { ref } from 'vue'

import HomeCarousel from '@/app-shell/components/HomeCarousel.vue'
import HomeChannelEntries from '@/app-shell/components/HomeChannelEntries.vue'
import type { HomeCarouselSlide } from '@/app-shell/navigation/home-carousel'
import type {
  HomeChannelCard,
  HomeEntryTarget,
  HomeKingEntry,
} from '@/app-shell/navigation/home-channels'
import PlatformHeroBanner from '@/shared/presentation/PlatformHeroBanner.vue'
import PlatformUnavailableDialog from '../shared/PlatformUnavailableDialog.vue'
import type { PlatformAvailability } from '../shared/platform-availability'
import type {
  PlatformHomeActivity,
  PlatformHomeFeaturedService,
  PlatformHomeViewState,
} from './platform-home'
import { resolvePlatformAvailabilityAction } from './platform-home'

const props = defineProps<{
  state: PlatformHomeViewState
  cards: readonly HomeChannelCard[]
  kingEntries: readonly HomeKingEntry[]
  carouselSlides: readonly HomeCarouselSlide[]
  carouselActive: boolean
  /**
   * False where the page that hosts this view shows its own blocks under the entries (the
   * blocks of the prototype's 推荐 channel); the inspection sections and the "no service"
   * state are then left out. A load failure is still reported.
   */
  showSections: boolean
}>()

const emit = defineEmits<{
  retry: []
  search: []
  selectEntry: [target: HomeEntryTarget]
  selectFeaturedService: [service: PlatformHomeFeaturedService]
  selectActivity: [activity: PlatformHomeActivity]
  selectEnterpriseSolution: []
}>()

const unavailableDialogVisible = ref(false)

const proceedOrShowUnavailable = (
  availability: PlatformAvailability,
  proceed: () => void,
): void => {
  if (resolvePlatformAvailabilityAction(availability) === 'SHOW_UNAVAILABLE') {
    unavailableDialogVisible.value = true
    return
  }
  proceed()
}

const selectFeaturedService = (service: PlatformHomeFeaturedService): void =>
  proceedOrShowUnavailable(service.availability, () => emit('selectFeaturedService', service))

const selectActivity = (activity: PlatformHomeActivity): void =>
  proceedOrShowUnavailable(activity.availability, () => emit('selectActivity', activity))

const selectEnterpriseSolution = (availability: PlatformAvailability): void =>
  proceedOrShowUnavailable(availability, () => emit('selectEnterpriseSolution'))

</script>

<template>
  <view class="platform-home">
    <HomeCarousel
      v-if="props.carouselSlides.length"
      class="platform-home__hero"
      :slides="props.carouselSlides"
      :active="props.carouselActive"
      @select="emit('selectEntry', $event)"
    />
    <PlatformHeroBanner
      v-else-if="props.state.kind === 'ready' && props.state.content.hero"
      class="platform-home__hero"
      :image-url="props.state.content.hero.imageUrl"
      :image-alt="`${props.state.content.hero.title}${props.state.content.hero.highlight}`"
      :title="props.state.content.hero.title"
      :highlight="props.state.content.hero.highlight"
      :description="props.state.content.hero.description"
      :highlights="props.state.content.hero.highlights"
      :action-label="props.state.content.hero.actionLabel"
      @action="emit('search')"
    />

    <HomeChannelEntries
      :cards="props.cards"
      :entries="props.kingEntries"
      @select="emit('selectEntry', $event)"
    />

    <view v-if="props.state.kind === 'loading'" class="platform-home__state" aria-label="正在加载">
      <view class="platform-home__skeleton platform-home__skeleton--short" />
      <view class="platform-home__skeleton platform-home__skeleton--cards" />
    </view>

    <view v-else-if="props.state.kind === 'error'" class="platform-home__state">
      <UniIcons type="info-filled" size="42" color="#ff6508" />
      <text class="platform-home__state-title">{{ props.state.message }}</text>
      <button class="platform-home__state-action" @click="emit('retry')">重新加载</button>
    </view>

    <view v-else-if="!props.showSections" />

    <view v-else-if="props.state.kind === 'empty'" class="platform-home__state">
      <UniIcons type="info" size="42" color="#a4acb7" />
      <text class="platform-home__state-title">当前城市暂无可用服务</text>
      <text class="platform-home__state-copy">服务开通后会在这里展示</text>
    </view>

    <template v-else>
      <view v-if="props.state.content.featuredServices.length" class="platform-home__section">
        <view class="platform-home__section-heading">
          <text class="platform-home__section-title">精选服务</text>
          <text class="platform-home__section-copy">为你推荐优质服务</text>
        </view>
        <scroll-view class="platform-home__featured-scroll" scroll-x>
          <view class="platform-home__featured-row">
            <button
              v-for="service in props.state.content.featuredServices"
              :key="service.id"
              class="platform-home__featured-card"
              @click="selectFeaturedService(service)"
            >
              <view class="platform-home__featured-image-wrap">
                <image class="platform-home__featured-image" :src="service.imageUrl" mode="aspectFill" />
              </view>
              <text class="platform-home__featured-title">{{ service.title }}</text>
              <text v-if="service.subtitle" class="platform-home__featured-subtitle">
                {{ service.subtitle }}
              </text>
              <view class="platform-home__featured-meta">
                <text v-if="service.quoteLabel" class="platform-home__quote">{{ service.quoteLabel }}</text>
              </view>
              <view class="platform-home__featured-stats">
                <view v-if="service.rating !== undefined" class="platform-home__rating">
                  <UniIcons type="star-filled" size="12" color="#e18400" />
                  <text>{{ service.rating.toFixed(1) }}</text>
                </view>
                <text v-if="service.soldCountText" class="platform-home__metric">{{ service.soldCountText }}</text>
              </view>
            </button>
          </view>
        </scroll-view>
      </view>

      <view
        v-if="props.state.content.activities.length"
        class="platform-home__section platform-home__section--activities"
      >
        <view class="platform-home__section-heading">
          <text class="platform-home__section-title">附近热门活动</text>
          <UniIcons type="location-filled" size="14" color="#4e8cff" />
          <text class="platform-home__section-copy">发现身边精彩</text>
        </view>
        <scroll-view class="platform-home__activity-scroll" scroll-x>
          <view class="platform-home__activity-row">
            <button
              v-for="activity in props.state.content.activities"
              :key="activity.id"
              class="platform-home__activity-card"
              @click="selectActivity(activity)"
            >
              <image class="platform-home__activity-image" :src="activity.imageUrl" mode="aspectFill" />
              <view class="platform-home__activity-copy">
                <text class="platform-home__activity-title">{{ activity.title }}</text>
                <text v-if="activity.locationText" class="platform-home__metric">
                  {{ activity.locationText }}
                </text>
                <text v-if="activity.distanceText" class="platform-home__metric">
                  {{ activity.distanceText }}
                </text>
              </view>
            </button>
          </view>
        </scroll-view>
      </view>

      <button
        v-if="props.state.content.enterpriseSolution"
        class="platform-home__enterprise"
        @click="selectEnterpriseSolution(props.state.content.enterpriseSolution.availability)"
      >
        <image
          class="platform-home__enterprise-illustration"
          :src="props.state.content.enterpriseSolution.imageUrl"
          mode="aspectFill"
        />
        <view class="platform-home__enterprise-copy">
          <view class="platform-home__enterprise-heading">
            <text class="platform-home__enterprise-title">
              {{ props.state.content.enterpriseSolution.title }}
            </text>
            <text class="platform-home__enterprise-description">
              {{ props.state.content.enterpriseSolution.description }}
            </text>
          </view>
          <view class="platform-home__enterprise-benefits">
            <view
              v-for="benefit in props.state.content.enterpriseSolution.benefits"
              :key="benefit.title"
              class="platform-home__enterprise-benefit"
            >
              <UniIcons
                :type="benefit.iconType"
                size="20"
                :color="benefit.color"
              />
              <view class="platform-home__enterprise-benefit-copy">
                <text class="platform-home__enterprise-benefit-title">{{ benefit.title }}</text>
                <text class="platform-home__enterprise-benefit-description">
                  {{ benefit.description }}
                </text>
              </view>
            </view>
          </view>
        </view>
      </button>
      <PlatformUnavailableDialog
        :visible="unavailableDialogVisible"
        @close="unavailableDialogVisible = false"
      />
    </template>
  </view>
</template>

<style scoped>
.platform-home{padding:4px var(--app-page-horizontal-gutter) 0;color:#17191d;font-family:system-ui,-apple-system,BlinkMacSystemFont,"PingFang SC","Noto Sans CJK SC","Microsoft YaHei",sans-serif;font-synthesis:none;text-rendering:optimizeLegibility;-webkit-font-smoothing:antialiased}
button{margin:0;padding:0;border:0;background:transparent;font-family:inherit;line-height:1.2}
button::after{border:0}
.platform-home__hero{margin-bottom:14rpx}
.platform-home__section{margin-top:12rpx;padding:12px 10px 10px;border-radius:24rpx;background:#fff}
.platform-home__section--activities{padding-top:12px;padding-bottom:12px}
.platform-home__section-heading{display:flex;align-items:center;gap:8px;margin-bottom:10px}
.platform-home__section-title{font-size:17px;font-weight:700}
.platform-home__section-copy{color:var(--app-secondary-text);font-size:12px}
.platform-home__featured-scroll,.platform-home__activity-scroll{width:100%;white-space:nowrap}
.platform-home__featured-row{display:grid;width:max-content;min-width:100%;grid-auto-columns:calc((100vw - 2*var(--app-page-horizontal-gutter) - 31px)/2);grid-auto-flow:column;gap:10px;padding-right:2px;box-sizing:border-box}
.platform-home__activity-row{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:9px}
.platform-home__featured-card{min-width:0;text-align:left}
.platform-home__featured-image-wrap{position:relative;height:108px;overflow:hidden;border-radius:10px}
.platform-home__featured-image{width:100%;height:100%}
.platform-home__featured-title{display:block;margin-top:6px;overflow:hidden;font-size:14px;font-weight:800;text-overflow:ellipsis;white-space:nowrap}
.platform-home__featured-subtitle{display:block;margin-top:3px;overflow:hidden;color:var(--app-secondary-text);font-size:12px;text-overflow:ellipsis;white-space:nowrap}
.platform-home__featured-meta{display:flex;min-height:18px;align-items:center;margin-top:4px}
.platform-home__featured-stats{display:flex;align-items:center;justify-content:space-between;margin-top:2px}
.platform-home__quote{color:#d84b00;font-size:14px;font-weight:800}
.platform-home__rating{display:flex;align-items:center;gap:2px;color:var(--app-secondary-text);font-size:11px}
.platform-home__metric{color:var(--app-secondary-text);font-size:11px}
.platform-home__activity-card{display:flex;min-width:0;gap:9px;text-align:left}
.platform-home__activity-image{width:70px;height:62px;flex:0 0 auto;border-radius:10px}
.platform-home__activity-copy{display:flex;min-width:0;flex-direction:column;justify-content:center}
.platform-home__activity-title{overflow:hidden;font-size:12px;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.platform-home__enterprise{position:relative;display:flex;width:100%;height:126px;overflow:hidden;margin-top:8px;padding:12px 16px;border-radius:24rpx;background:#edf6ff;text-align:left}
.platform-home__enterprise::before{content:"";position:absolute;inset:0;z-index:1;background:linear-gradient(90deg,#edf6ff 0%,#edf6ff 58%,rgba(237,246,255,.94) 67%,rgba(237,246,255,.08) 83%)}
.platform-home__enterprise::after{content:"";position:absolute;top:0;right:29%;bottom:0;width:1rpx;background:rgba(116,157,205,.16)}
.platform-home__enterprise-illustration{position:absolute;inset:0;width:100%;height:100%;object-position:right center}
.platform-home__enterprise-copy{position:relative;z-index:2;display:flex;width:70%;min-width:0;flex-direction:column}
.platform-home__enterprise-heading{display:flex;align-items:baseline;gap:8px;white-space:nowrap}
.platform-home__enterprise-title{font-size:17px;font-weight:700}
.platform-home__enterprise-description{overflow:hidden;color:var(--app-secondary-text);font-size:12px;text-overflow:ellipsis;white-space:nowrap}
.platform-home__enterprise-benefits{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:7px;margin-top:12px}
.platform-home__enterprise-benefit{display:flex;min-width:0;align-items:center;gap:5px;color:#536070}
.platform-home__enterprise-benefit-copy{display:flex;min-width:0;flex-direction:column}
.platform-home__enterprise-benefit-title{overflow:hidden;font-size:11px;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.platform-home__enterprise-benefit-description{overflow:hidden;margin-top:2px;color:var(--app-secondary-text);font-size:10px;text-overflow:ellipsis;white-space:nowrap}
.platform-home__state{display:flex;min-height:240px;flex-direction:column;align-items:center;justify-content:center;padding:40rpx}
.platform-home__state-title{margin-top:24rpx;color:#4f5966;font-size:28rpx;font-weight:700;text-align:center}
.platform-home__state-copy{margin-top:10rpx;color:#9198a1;font-size:22rpx}
.platform-home__state-action{min-width:188rpx;min-height:76rpx;margin-top:28rpx;border-radius:40rpx;color:#fff;background:#ff6508;font-size:25rpx;line-height:76rpx}
.platform-home__skeleton{width:100%;border-radius:24rpx;background:linear-gradient(90deg,#edf0f3 25%,#f7f8fa 37%,#edf0f3 63%);background-size:400% 100%;animation:home-skeleton 1.25s ease infinite}
.platform-home__skeleton--short{height:70rpx;margin-top:24rpx}
.platform-home__skeleton--cards{height:300rpx;margin-top:26rpx}
@keyframes home-skeleton{0%{background-position:100% 0}100%{background-position:0 0}}
@media (min-width:420px){.platform-home__featured-image-wrap{height:116px}}
</style>
