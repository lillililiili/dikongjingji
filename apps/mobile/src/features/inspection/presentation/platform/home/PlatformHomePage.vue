<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type {
  PlatformHomeActivity,
  PlatformHomeFeaturedService,
  PlatformHomeServiceEntry,
  PlatformHomeViewState,
} from './platform-home'

const props = defineProps<{
  state: PlatformHomeViewState
  activeTab?: 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE'
}>()

const emit = defineEmits<{
  retry: []
  search: []
  message: []
  selectServiceEntry: [entry: PlatformHomeServiceEntry]
  selectFeaturedService: [service: PlatformHomeFeaturedService]
  selectActivity: [activity: PlatformHomeActivity]
  selectEnterpriseSolution: []
  selectTab: [tab: 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE']
}>()

const unavailable = (availability: 'AVAILABLE' | 'UNAVAILABLE'): boolean =>
  availability === 'UNAVAILABLE'

const tabs = [
  { code: 'HOME', label: '首页', icon: 'home' },
  { code: 'SERVICE', label: '服务', icon: 'smallcircle-filled' },
  { code: 'DISCOVER', label: '发现', icon: 'navigate' },
  { code: 'MALL', label: '商城', icon: 'shop' },
  { code: 'MINE', label: '我的', icon: 'person' },
] as const

const enterpriseBenefits = (description: string): string[] =>
  description.split(/[·｜|]/).map((item) => item.trim()).filter(Boolean).slice(0, 3)
</script>

<template>
  <view class="platform-home">
    <view v-if="props.state.kind === 'loading'" class="platform-home__state" aria-label="正在加载">
      <view class="platform-home__skeleton platform-home__skeleton--short" />
      <view class="platform-home__skeleton platform-home__skeleton--hero" />
      <view class="platform-home__skeleton platform-home__skeleton--cards" />
    </view>

    <view v-else-if="props.state.kind === 'error'" class="platform-home__state">
      <UniIcons type="info-filled" size="42" color="#ff6508" />
      <text class="platform-home__state-title">{{ props.state.message }}</text>
      <button class="platform-home__state-action" @click="emit('retry')">重新加载</button>
    </view>

    <view v-else-if="props.state.kind === 'empty'" class="platform-home__state">
      <UniIcons type="info" size="42" color="#a4acb7" />
      <text class="platform-home__state-title">当前城市暂无可用服务</text>
      <text class="platform-home__state-copy">服务开通后会在这里展示</text>
    </view>

    <template v-else>
      <view class="platform-home__scroll">
        <view class="platform-home__top">
          <view class="platform-home__location">
            <view class="platform-home__city-line">
              <text class="platform-home__city">{{ props.state.content.cityName }}</text>
              <UniIcons type="down" size="13" color="#17191d" />
            </view>
            <text class="platform-home__weather">{{ props.state.content.weatherText }}</text>
          </view>
          <button class="platform-home__search" @click="emit('search')">
            <UniIcons type="search" size="20" color="#23272c" />
            <text>{{ props.state.content.searchPlaceholder || '搜索无人机服务、景点、活动等' }}</text>
          </button>
          <button class="platform-home__message" aria-label="消息" @click="emit('message')">
            <UniIcons type="chatboxes" size="28" color="#1e2227" />
            <text class="platform-home__badge">
              {{ props.state.content.unreadCount > 99 ? '99+' : props.state.content.unreadCount }}
            </text>
          </button>
        </view>

        <view v-if="props.state.content.hero" class="platform-home__hero">
          <image
            class="platform-home__hero-image"
            :src="props.state.content.hero.imageUrl"
            mode="aspectFill"
          />
          <view class="platform-home__hero-copy">
            <text class="platform-home__hero-title">{{ props.state.content.hero.title }}</text>
            <text class="platform-home__hero-highlight">{{ props.state.content.hero.highlight }}</text>
            <text class="platform-home__hero-description">{{ props.state.content.hero.description }}</text>
            <button class="platform-home__hero-action" @click="emit('search')">立即体验</button>
          </view>
        </view>

        <view v-if="props.state.content.serviceEntries.length" class="platform-home__service-grid">
          <button
            v-for="entry in props.state.content.serviceEntries"
            :key="entry.code"
            class="platform-home__service-entry"
            @click="emit('selectServiceEntry', entry)"
          >
            <view
              class="platform-home__entry-icon-wrap"
              :style="{ backgroundColor: entry.iconBackground }"
            >
              <UniIcons :type="entry.iconType" size="31" :color="entry.iconColor" />
              <text
                v-if="entry.availability === 'UNAVAILABLE'"
                class="platform-home__unavailable"
              >
                暂未开放
              </text>
            </view>
            <text class="platform-home__entry-title">{{ entry.title }}</text>
            <text class="platform-home__entry-subtitle">{{ entry.subtitle }}</text>
          </button>
        </view>

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
                @click="emit('selectFeaturedService', service)"
              >
                <view class="platform-home__featured-image-wrap">
                  <image class="platform-home__featured-image" :src="service.imageUrl" mode="aspectFill" />
                  <text v-if="unavailable(service.availability)" class="platform-home__unavailable">
                    暂未开放
                  </text>
                </view>
                <text class="platform-home__featured-title">{{ service.title }}</text>
                <text v-if="service.subtitle" class="platform-home__featured-subtitle">
                  {{ service.subtitle }}
                </text>
                <view class="platform-home__featured-meta">
                  <text class="platform-home__quote">{{ service.quoteLabel }}</text>
                </view>
                <view class="platform-home__featured-stats">
                  <text class="platform-home__rating">
                    <UniIcons type="star-filled" size="11" color="#ffad00" />
                    {{ service.rating.toFixed(1) }}
                  </text>
                  <text class="platform-home__metric">{{ service.soldCountText }}</text>
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
                @click="emit('selectActivity', activity)"
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
                  <text v-if="unavailable(activity.availability)" class="platform-home__inline-unavailable">
                    暂未开放
                  </text>
                </view>
              </button>
            </view>
          </scroll-view>
        </view>

        <button
          v-if="props.state.content.enterpriseSolution"
          class="platform-home__enterprise"
          @click="emit('selectEnterpriseSolution')"
        >
          <view class="platform-home__enterprise-copy">
            <text class="platform-home__enterprise-title">
              {{ props.state.content.enterpriseSolution.title }}
            </text>
            <view class="platform-home__enterprise-benefits">
              <view
                v-for="(benefit, index) in enterpriseBenefits(props.state.content.enterpriseSolution.description)"
                :key="benefit"
                class="platform-home__enterprise-benefit"
              >
                <UniIcons
                  :type="['map-filled', 'bars', 'folder-add-filled'][index] || 'checkbox-filled'"
                  size="19"
                  :color="['#4e8cff', '#2bbf72', '#ff8a1f'][index] || '#4e8cff'"
                />
                <text>{{ benefit }}</text>
              </view>
            </view>
            <text
              v-if="unavailable(props.state.content.enterpriseSolution.availability)"
              class="platform-home__inline-unavailable"
            >
              暂未开放
            </text>
          </view>
          <image
            class="platform-home__enterprise-image"
            :src="props.state.content.enterpriseSolution.imageUrl"
            mode="aspectFit"
          />
        </button>
      </view>

      <view class="platform-home__tabbar">
        <button
          v-for="tab in tabs"
          :key="tab.code"
          :class="['platform-home__tab', { 'platform-home__tab--active': (props.activeTab || 'HOME') === tab.code }]"
          @click="emit('selectTab', tab.code as 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE')"
        >
          <view v-if="tab.code === 'SERVICE'" class="platform-home__service-tab-icon">
            <UniIcons v-for="dot in 4" :key="dot" type="smallcircle-filled" size="12" :color="(props.activeTab || 'HOME') === tab.code ? '#ff6508' : '#4c535c'" />
          </view>
          <UniIcons
            v-else
            :type="tab.icon"
            size="25"
            :color="(props.activeTab || 'HOME') === tab.code ? '#ff6508' : '#4c535c'"
          />
          <text>{{ tab.label }}</text>
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.platform-home{min-height:100vh;color:#17191d;background:#f7f8fa;font-family:"PingFang SC","Microsoft YaHei",sans-serif}
button{margin:0;padding:0;border:0;background:transparent;line-height:1.2}
button::after{border:0}
.platform-home__scroll{padding:calc(env(safe-area-inset-top) + 10px) 18rpx calc(108rpx + env(safe-area-inset-bottom))}
.platform-home__top{display:grid;grid-template-columns:142rpx minmax(0,1fr) 54rpx;gap:18rpx;align-items:center;margin-bottom:14rpx}
.platform-home__city-line{display:flex;align-items:center;gap:8rpx}
.platform-home__city{font-size:31rpx;font-weight:800;white-space:nowrap}
.platform-home__weather{display:block;margin-top:3rpx;color:#4f5966;font-size:19rpx;white-space:nowrap}
.platform-home__search{display:flex;min-width:0;height:58rpx;align-items:center;gap:12rpx;padding:0 18rpx;border:2rpx solid #2c2f33;border-radius:31rpx;color:#8a9099;font-size:21rpx;text-align:left}
.platform-home__search text{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.platform-home__message{position:relative;display:flex;width:54rpx;height:58rpx;align-items:center;justify-content:center}
.platform-home__badge{position:absolute;right:-4rpx;top:-6rpx;min-width:28rpx;height:28rpx;padding:0 6rpx;border:3rpx solid #fff;border-radius:20rpx;color:#fff;background:#ff4136;font-size:16rpx;font-weight:700;line-height:25rpx}
.platform-home__hero{position:relative;height:248rpx;overflow:hidden;margin-bottom:14rpx;border-radius:22rpx;background:#dff0ff}
.platform-home__hero-image{width:100%;height:100%}
.platform-home__hero-copy{position:absolute;top:34rpx;left:32rpx;display:flex;width:44%;flex-direction:column;align-items:flex-start}
.platform-home__hero-title,.platform-home__hero-highlight{font-size:36rpx;font-weight:900;line-height:1.08;letter-spacing:-1rpx}
.platform-home__hero-highlight{color:#ff5e00}
.platform-home__hero-description{margin-top:10rpx;color:#3e4752;font-size:18rpx;line-height:1.35}
.platform-home__hero-action{min-width:138rpx;min-height:48rpx;margin-top:13rpx;padding:0 20rpx;border-radius:25rpx;color:#fff;background:linear-gradient(90deg,#ff8a00,#ff5b00);font-size:21rpx;font-weight:700;line-height:48rpx}
.platform-home__service-grid{display:grid;height:288rpx;grid-template-columns:repeat(4,minmax(0,1fr));grid-template-rows:repeat(2,minmax(0,1fr));gap:10rpx 12rpx;padding:14rpx 15rpx 10rpx;border-radius:24rpx;background:#fff;box-shadow:0 6rpx 18rpx rgba(36,58,88,.05)}
.platform-home__service-entry{min-width:0;text-align:center}
.platform-home__entry-icon-wrap{position:relative;display:flex;width:76rpx;height:76rpx;align-items:center;justify-content:center;margin:0 auto;border-radius:21rpx}
.platform-home__entry-title{display:block;margin-top:5rpx;overflow-wrap:break-word;font-size:22rpx;font-weight:700;line-height:1.05;white-space:nowrap}
.platform-home__entry-subtitle{display:block;margin-top:1rpx;overflow:hidden;color:#747c87;font-size:16rpx;line-height:1.05;text-overflow:ellipsis;white-space:nowrap}
.platform-home__unavailable{position:absolute;right:-3rpx;bottom:-3rpx;padding:3rpx 6rpx;border-radius:9rpx;color:#fff;background:rgba(30,35,42,.72);font-size:13rpx;white-space:nowrap}
.platform-home__section{margin-top:14rpx;padding:16rpx 15rpx;border-radius:24rpx;background:#fff}
.platform-home__section--activities{padding-top:12rpx;padding-bottom:12rpx}
.platform-home__section-heading{display:flex;align-items:center;gap:8rpx;margin-bottom:12rpx}
.platform-home__section-title{font-size:27rpx;font-weight:800}
.platform-home__section-copy{color:#78818d;font-size:18rpx}
.platform-home__featured-scroll,.platform-home__activity-scroll{width:100%;white-space:nowrap}
.platform-home__featured-row{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10rpx}
.platform-home__activity-row{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10rpx}
.platform-home__featured-card{min-width:0;text-align:left}
.platform-home__featured-image-wrap{position:relative;height:100rpx;overflow:hidden;border-radius:14rpx}
.platform-home__featured-image{width:100%;height:100%}
.platform-home__featured-title{display:block;margin-top:7rpx;overflow:hidden;font-size:19rpx;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.platform-home__featured-subtitle{display:block;margin-top:2rpx;overflow:hidden;color:#69727e;font-size:15rpx;text-overflow:ellipsis;white-space:nowrap}
.platform-home__featured-meta{display:flex;min-height:25rpx;align-items:center;margin-top:4rpx}
.platform-home__featured-stats{display:flex;align-items:center;justify-content:space-between;margin-top:2rpx}
.platform-home__quote{color:#ff5d00;font-size:19rpx;font-weight:800}
.platform-home__rating{display:flex;align-items:center;color:#6f7782;font-size:14rpx}
.platform-home__metric{color:#7a838e;font-size:14rpx}
.platform-home__activity-card{display:flex;min-width:0;gap:9rpx;text-align:left}
.platform-home__activity-image{width:84rpx;height:74rpx;flex:0 0 auto;border-radius:12rpx}
.platform-home__activity-copy{display:flex;min-width:0;flex-direction:column;justify-content:center}
.platform-home__activity-title{overflow:hidden;font-size:18rpx;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.platform-home__inline-unavailable{margin-top:4rpx;color:#ff6508;font-size:14rpx}
.platform-home__enterprise{position:relative;display:flex;width:100%;height:106rpx;overflow:hidden;margin-top:14rpx;padding:11rpx 16rpx;border-radius:24rpx;background:#edf6ff;text-align:left}
.platform-home__enterprise-image{position:absolute;right:0;bottom:0;width:245rpx;height:108rpx}
.platform-home__enterprise-copy{position:relative;z-index:1;display:flex;width:72%;flex-direction:column}
.platform-home__enterprise-title{font-size:25rpx;font-weight:800}
.platform-home__enterprise-benefits{display:flex;gap:16rpx;margin-top:9rpx}
.platform-home__enterprise-benefit{display:flex;min-width:0;align-items:center;gap:4rpx;color:#536070;font-size:15rpx;white-space:nowrap}
.platform-home__tabbar{position:fixed;right:0;bottom:0;left:0;z-index:20;display:grid;grid-template-columns:repeat(5,1fr);padding:7rpx 14rpx calc(7rpx + env(safe-area-inset-bottom));border-top:1rpx solid #e7e9ed;background:rgba(255,255,255,.97)}
.platform-home__tab{display:flex;min-height:82rpx;flex-direction:column;align-items:center;justify-content:center;gap:4rpx;color:#4c535c;font-size:18rpx}
.platform-home__tab--active{color:#ff6508;font-weight:700}
.platform-home__service-tab-icon{display:grid;width:50rpx;height:50rpx;grid-template-columns:repeat(2,20rpx);grid-template-rows:repeat(2,20rpx);place-content:center;gap:3rpx;line-height:1}
.platform-home__state{display:flex;min-height:100vh;flex-direction:column;align-items:center;justify-content:center;padding:40rpx}
.platform-home__state-title{margin-top:24rpx;color:#4f5966;font-size:28rpx;font-weight:700;text-align:center}
.platform-home__state-copy{margin-top:10rpx;color:#9198a1;font-size:22rpx}
.platform-home__state-action{min-width:188rpx;min-height:76rpx;margin-top:28rpx;border-radius:40rpx;color:#fff;background:#ff6508;font-size:25rpx;line-height:76rpx}
.platform-home__skeleton{width:100%;border-radius:24rpx;background:linear-gradient(90deg,#edf0f3 25%,#f7f8fa 37%,#edf0f3 63%);background-size:400% 100%;animation:home-skeleton 1.25s ease infinite}
.platform-home__skeleton--short{height:70rpx}
.platform-home__skeleton--hero{height:316rpx;margin-top:26rpx}
.platform-home__skeleton--cards{height:300rpx;margin-top:26rpx}
@keyframes home-skeleton{0%{background-position:100% 0}100%{background-position:0 0}}
@media (min-width:420px){.platform-home__scroll{padding-right:28rpx;padding-left:28rpx}.platform-home__hero{height:330rpx}.platform-home__service-grid{gap:24rpx}}
</style>
