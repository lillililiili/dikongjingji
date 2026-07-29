<script setup lang="ts">
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
</script>

<template>
  <view class="platform-home">
    <view v-if="props.state.kind === 'loading'" class="platform-home__state" aria-label="正在加载">
      <view class="platform-home__skeleton platform-home__skeleton--short" />
      <view class="platform-home__skeleton platform-home__skeleton--hero" />
      <view class="platform-home__skeleton platform-home__skeleton--cards" />
    </view>

    <view v-else-if="props.state.kind === 'error'" class="platform-home__state">
      <view class="platform-home__state-mark platform-home__state-mark--error" />
      <text class="platform-home__state-title">{{ props.state.message }}</text>
      <button class="platform-home__state-action" @click="emit('retry')">重新加载</button>
    </view>

    <view v-else-if="props.state.kind === 'empty'" class="platform-home__state">
      <view class="platform-home__state-mark" />
      <text class="platform-home__state-title">当前城市暂无可用服务</text>
      <text class="platform-home__state-copy">服务开通后会在这里展示</text>
    </view>

    <template v-else>
      <view class="platform-home__scroll">
        <view class="platform-home__top">
          <view class="platform-home__location">
            <view class="platform-home__city-line">
              <text class="platform-home__city">{{ props.state.content.cityName }}</text>
              <view class="platform-home__caret" />
            </view>
            <text v-if="props.state.content.weatherText" class="platform-home__weather">
              {{ props.state.content.weatherText }}
            </text>
          </view>
          <button class="platform-home__search" @click="emit('search')">
            <view class="platform-home__search-icon" />
            <text>{{ props.state.content.searchPlaceholder || '搜索无人机服务、景点、活动等' }}</text>
          </button>
          <button class="platform-home__message" aria-label="消息" @click="emit('message')">
            <view class="platform-home__message-icon" />
            <text
              v-if="props.state.content.unreadCount && props.state.content.unreadCount > 0"
              class="platform-home__badge"
            >
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
            <view class="platform-home__entry-image-wrap">
              <image class="platform-home__entry-image" :src="entry.imageUrl" mode="aspectFill" />
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
                  <text v-if="service.quoteLabel" class="platform-home__quote">
                    {{ service.quoteLabel }}
                  </text>
                  <text v-if="service.rating" class="platform-home__metric">
                    {{ service.rating.toFixed(1) }}
                  </text>
                  <text v-if="service.soldCountText" class="platform-home__metric">
                    {{ service.soldCountText }}
                  </text>
                </view>
              </button>
            </view>
          </scroll-view>
        </view>

        <view v-if="props.state.content.activities.length" class="platform-home__section">
          <view class="platform-home__section-heading">
            <text class="platform-home__section-title">附近热门活动</text>
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
          <image
            class="platform-home__enterprise-image"
            :src="props.state.content.enterpriseSolution.imageUrl"
            mode="aspectFill"
          />
          <view class="platform-home__enterprise-copy">
            <text class="platform-home__enterprise-title">
              {{ props.state.content.enterpriseSolution.title }}
            </text>
            <text class="platform-home__enterprise-description">
              {{ props.state.content.enterpriseSolution.description }}
            </text>
            <text
              v-if="unavailable(props.state.content.enterpriseSolution.availability)"
              class="platform-home__inline-unavailable"
            >
              暂未开放
            </text>
          </view>
        </button>
      </view>

      <view class="platform-home__tabbar">
        <button
          v-for="tab in [
            { code: 'HOME', label: '首页' },
            { code: 'SERVICE', label: '服务' },
            { code: 'DISCOVER', label: '发现' },
            { code: 'MALL', label: '商城' },
            { code: 'MINE', label: '我的' },
          ]"
          :key="tab.code"
          :class="['platform-home__tab', { 'platform-home__tab--active': (props.activeTab || 'HOME') === tab.code }]"
          @click="emit('selectTab', tab.code as 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE')"
        >
          <view :class="['platform-home__tab-icon', `platform-home__tab-icon--${tab.code.toLowerCase()}`]" />
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
.platform-home__scroll{padding:calc(env(safe-area-inset-top) + 14px) 20rpx calc(128rpx + env(safe-area-inset-bottom))}
.platform-home__top{display:grid;grid-template-columns:126rpx minmax(0,1fr) 64rpx;gap:16rpx;align-items:center;margin-bottom:20rpx}
.platform-home__city-line{display:flex;align-items:center;gap:8rpx}
.platform-home__city{font-size:34rpx;font-weight:800;white-space:nowrap}
.platform-home__caret{width:0;height:0;border-right:8rpx solid transparent;border-left:8rpx solid transparent;border-top:10rpx solid #17191d}
.platform-home__weather{display:block;margin-top:5rpx;color:#4f5966;font-size:20rpx;white-space:nowrap}
.platform-home__search{display:flex;min-width:0;height:68rpx;align-items:center;gap:16rpx;padding:0 22rpx;border:2rpx solid #2c2f33;border-radius:36rpx;color:#8a9099;font-size:23rpx;text-align:left}
.platform-home__search text{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.platform-home__search-icon{width:22rpx;height:22rpx;flex:0 0 auto;border:4rpx solid #23272c;border-radius:50%;position:relative}
.platform-home__search-icon::after{content:"";position:absolute;right:-9rpx;bottom:-7rpx;width:11rpx;height:4rpx;transform:rotate(45deg);border-radius:4rpx;background:#23272c}
.platform-home__message{position:relative;width:64rpx;height:64rpx}
.platform-home__message-icon{position:absolute;inset:14rpx 8rpx 15rpx;border:4rpx solid #1e2227;border-radius:12rpx}
.platform-home__message-icon::after{content:"";position:absolute;left:8rpx;bottom:-10rpx;width:12rpx;height:12rpx;border-bottom:4rpx solid #1e2227;border-left:4rpx solid #1e2227;transform:skewY(-34deg);background:#f7f8fa}
.platform-home__badge{position:absolute;right:-2rpx;top:-3rpx;min-width:30rpx;height:30rpx;padding:0 7rpx;border:3rpx solid #fff;border-radius:20rpx;color:#fff;background:#ff4136;font-size:18rpx;font-weight:700;line-height:27rpx}
.platform-home__hero{position:relative;height:316rpx;overflow:hidden;margin-bottom:22rpx;border-radius:26rpx;background:#dff0ff}
.platform-home__hero-image{width:100%;height:100%}
.platform-home__hero-copy{position:absolute;top:64rpx;left:34rpx;display:flex;width:42%;flex-direction:column;align-items:flex-start}
.platform-home__hero-title,.platform-home__hero-highlight{font-size:44rpx;font-weight:900;line-height:1.06;letter-spacing:-1rpx}
.platform-home__hero-highlight{color:#ff5e00}
.platform-home__hero-description{margin-top:16rpx;color:#3e4752;font-size:20rpx;line-height:1.45}
.platform-home__hero-action{min-width:154rpx;min-height:58rpx;margin-top:19rpx;padding:0 24rpx;border-radius:30rpx;color:#fff;background:linear-gradient(90deg,#ff8a00,#ff5b00);font-size:23rpx;font-weight:700;line-height:58rpx}
.platform-home__service-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:22rpx 16rpx;padding:24rpx 18rpx;border-radius:28rpx;background:#fff;box-shadow:0 8rpx 24rpx rgba(36,58,88,.06)}
.platform-home__service-entry{min-width:0;text-align:center}
.platform-home__entry-image-wrap{position:relative;overflow:hidden;aspect-ratio:1;border-radius:25rpx;background:#eef3f7}
.platform-home__entry-image{width:100%;height:100%}
.platform-home__entry-title{display:block;margin-top:12rpx;overflow-wrap:break-word;font-size:24rpx;font-weight:700}
.platform-home__entry-subtitle{display:block;margin-top:5rpx;overflow:hidden;color:#747c87;font-size:18rpx;text-overflow:ellipsis;white-space:nowrap}
.platform-home__unavailable{position:absolute;right:6rpx;bottom:6rpx;padding:4rpx 8rpx;border-radius:10rpx;color:#fff;background:rgba(30,35,42,.72);font-size:16rpx}
.platform-home__section{margin-top:22rpx;padding:24rpx 18rpx;border-radius:28rpx;background:#fff}
.platform-home__section-heading{display:flex;align-items:baseline;gap:12rpx;margin-bottom:18rpx}
.platform-home__section-title{font-size:30rpx;font-weight:800}
.platform-home__section-copy{color:#78818d;font-size:20rpx}
.platform-home__featured-scroll,.platform-home__activity-scroll{width:100%;white-space:nowrap}
.platform-home__featured-row,.platform-home__activity-row{display:flex;gap:16rpx}
.platform-home__featured-card{width:250rpx;flex:0 0 250rpx;text-align:left}
.platform-home__featured-image-wrap{position:relative;height:154rpx;overflow:hidden;border-radius:18rpx}
.platform-home__featured-image{width:100%;height:100%}
.platform-home__featured-title{display:block;margin-top:12rpx;overflow:hidden;font-size:24rpx;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.platform-home__featured-subtitle{display:block;margin-top:5rpx;overflow:hidden;color:#69727e;font-size:19rpx;text-overflow:ellipsis;white-space:nowrap}
.platform-home__featured-meta{display:flex;min-height:30rpx;align-items:center;gap:12rpx;margin-top:8rpx}
.platform-home__quote{color:#ff5d00;font-size:23rpx;font-weight:800}
.platform-home__metric{color:#7a838e;font-size:18rpx}
.platform-home__activity-card{display:flex;width:310rpx;flex:0 0 310rpx;gap:14rpx;text-align:left}
.platform-home__activity-image{width:116rpx;height:96rpx;flex:0 0 auto;border-radius:16rpx}
.platform-home__activity-copy{display:flex;min-width:0;flex-direction:column;justify-content:center}
.platform-home__activity-title{overflow:hidden;font-size:23rpx;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.platform-home__inline-unavailable{margin-top:7rpx;color:#ff6508;font-size:18rpx}
.platform-home__enterprise{position:relative;width:100%;height:170rpx;overflow:hidden;margin-top:22rpx;border-radius:28rpx;text-align:left}
.platform-home__enterprise-image{position:absolute;inset:0;width:100%;height:100%}
.platform-home__enterprise-copy{position:absolute;top:30rpx;left:28rpx;display:flex;width:43%;flex-direction:column}
.platform-home__enterprise-title{font-size:29rpx;font-weight:800}
.platform-home__enterprise-description{margin-top:8rpx;color:#5d6876;font-size:19rpx;line-height:1.45}
.platform-home__tabbar{position:fixed;right:0;bottom:0;left:0;z-index:20;display:grid;grid-template-columns:repeat(5,1fr);padding:12rpx 14rpx calc(12rpx + env(safe-area-inset-bottom));border-top:1rpx solid #e7e9ed;background:rgba(255,255,255,.97)}
.platform-home__tab{display:flex;min-height:78rpx;flex-direction:column;align-items:center;justify-content:center;gap:7rpx;color:#4c535c;font-size:19rpx}
.platform-home__tab--active{color:#ff6508;font-weight:700}
.platform-home__tab-icon{position:relative;width:34rpx;height:34rpx;border:4rpx solid currentColor;border-radius:10rpx}
.platform-home__tab-icon--service{border-radius:50%}
.platform-home__tab-icon--discover{transform:rotate(45deg);border-radius:50% 50% 50% 8rpx}
.platform-home__tab-icon--mall{border-radius:7rpx 7rpx 11rpx 11rpx}
.platform-home__tab-icon--mine{border-radius:50% 50% 12rpx 12rpx}
.platform-home__state{display:flex;min-height:100vh;flex-direction:column;align-items:center;justify-content:center;padding:40rpx}
.platform-home__state-mark{width:76rpx;height:76rpx;border:6rpx solid #b8c0ca;border-radius:50%}
.platform-home__state-mark--error{border-color:#ff6508}
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
