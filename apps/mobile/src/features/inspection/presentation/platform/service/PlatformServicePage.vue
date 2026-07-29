<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type {
  PlatformRecommendedService,
  PlatformRobotScenario,
  PlatformServiceChannel,
  PlatformServiceViewState,
} from './platform-service'

const props = defineProps<{
  state: PlatformServiceViewState
  activeTab?: 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE'
}>()

const emit = defineEmits<{
  retry: []
  search: []
  message: []
  selectChannel: [channel: PlatformServiceChannel]
  selectScenario: [scenario: PlatformRobotScenario]
  selectRecommendedService: [service: PlatformRecommendedService]
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

const capabilityIcons: Record<string, string> = {
  QUALITY: 'medal-filled',
  PROCESS: 'auth-filled',
  COVERAGE: 'home-filled',
  SUPPORT: 'headphones',
}
</script>

<template>
  <view class="platform-service">
    <view v-if="props.state.kind === 'loading'" class="platform-service__state">
      <view class="platform-service__skeleton platform-service__skeleton--head" />
      <view class="platform-service__skeleton platform-service__skeleton--hero" />
      <view class="platform-service__skeleton platform-service__skeleton--grid" />
    </view>

    <view v-else-if="props.state.kind === 'error'" class="platform-service__state">
      <UniIcons type="info-filled" size="42" color="#ff6508" />
      <text class="platform-service__state-title">{{ props.state.message }}</text>
      <button class="platform-service__retry" @click="emit('retry')">重新加载</button>
    </view>

    <view v-else-if="props.state.kind === 'empty'" class="platform-service__state">
      <UniIcons type="info" size="42" color="#a4acb7" />
      <text class="platform-service__state-title">当前城市暂无服务频道</text>
      <text class="platform-service__state-copy">服务开通后会在这里展示</text>
    </view>

    <template v-else>
      <view class="platform-service__scroll">
        <view class="platform-service__top">
          <view class="platform-service__location">
            <view class="platform-service__city-line">
              <text class="platform-service__city">{{ props.state.content.cityName }}</text>
              <UniIcons type="down" size="13" color="#17191d" />
            </view>
            <text class="platform-service__weather">{{ props.state.content.weatherText }}</text>
          </view>
          <button class="platform-service__search" @click="emit('search')">
            <UniIcons type="search" size="20" color="#23272c" />
            <text>{{ props.state.content.searchPlaceholder || '搜索无人机服务、景点、活动等' }}</text>
          </button>
          <button class="platform-service__message" aria-label="消息" @click="emit('message')">
            <UniIcons type="chatboxes" size="28" color="#1e2227" />
            <text class="platform-service__badge">
              {{ props.state.content.unreadCount > 99 ? '99+' : props.state.content.unreadCount }}
            </text>
          </button>
        </view>

        <scroll-view v-if="props.state.content.channels.length" class="platform-service__channels" scroll-x>
          <view class="platform-service__channel-row">
            <button
              v-for="channel in props.state.content.channels"
              :key="channel.code"
              :class="[
                'platform-service__channel',
                { 'platform-service__channel--active': channel.code === props.state.content.activeChannelCode },
              ]"
              @click="emit('selectChannel', channel)"
            >
              <text>{{ channel.label }}</text>
              <text v-if="unavailable(channel.availability)" class="platform-service__channel-status">
                暂未开放
              </text>
            </button>
          </view>
        </scroll-view>

        <view v-if="props.state.content.hero" class="platform-service__hero">
          <image class="platform-service__hero-image" :src="props.state.content.hero.imageUrl" mode="aspectFill" />
          <view class="platform-service__hero-copy">
            <text class="platform-service__hero-title">{{ props.state.content.hero.title }}</text>
            <text class="platform-service__hero-highlight">{{ props.state.content.hero.highlight }}</text>
            <text class="platform-service__hero-description">{{ props.state.content.hero.description }}</text>
            <button class="platform-service__hero-action" @click="emit('search')">立即体验</button>
          </view>
        </view>

        <view v-if="props.state.content.scenarios.length" class="platform-service__scenario-grid">
          <button
            v-for="scenario in props.state.content.scenarios"
            :key="scenario.code"
            class="platform-service__scenario"
            @click="emit('selectScenario', scenario)"
          >
            <view
              class="platform-service__scenario-icon-wrap"
              :style="{ backgroundColor: scenario.iconBackground }"
            >
              <UniIcons :type="scenario.iconType" size="31" :color="scenario.iconColor" />
              <text v-if="scenario.availability === 'UNAVAILABLE'" class="platform-service__unavailable">
                暂未开放
              </text>
            </view>
            <text class="platform-service__scenario-title">{{ scenario.title }}</text>
            <text class="platform-service__scenario-subtitle">{{ scenario.subtitle }}</text>
          </button>
        </view>

        <view v-if="props.state.content.capabilities.length" class="platform-service__capabilities">
          <view class="platform-service__section-heading">
            <text class="platform-service__section-title">机器人联盟</text>
            <text class="platform-service__section-copy">服务能力</text>
          </view>
          <view class="platform-service__capability-grid">
            <view
              v-for="(capability, index) in props.state.content.capabilities"
              :key="capability.code"
              class="platform-service__capability"
            >
              <view class="platform-service__capability-icon">
                <UniIcons
                  :type="capabilityIcons[capability.code] || 'checkbox-filled'"
                  size="20"
                  :color="['#f59e0b', '#3b82f6', '#22c55e', '#16a34a'][index] || '#20b967'"
                />
              </view>
              <view class="platform-service__capability-copy">
                <text class="platform-service__capability-title">{{ capability.title }}</text>
                <text class="platform-service__capability-description">{{ capability.description }}</text>
              </view>
            </view>
          </view>
        </view>

        <view v-if="props.state.content.recommendedServices.length" class="platform-service__recommendations">
          <view class="platform-service__section-heading">
            <text class="platform-service__section-title">精选设备＆服务推荐</text>
            <text class="platform-service__section-copy">专业可信</text>
          </view>
          <scroll-view class="platform-service__recommendation-scroll" scroll-x>
            <view class="platform-service__recommendation-row">
              <button
                v-for="service in props.state.content.recommendedServices"
                :key="service.id"
                class="platform-service__service-card"
                @click="emit('selectRecommendedService', service)"
              >
                <view class="platform-service__service-image-wrap">
                  <image class="platform-service__service-image" :src="service.imageUrl" mode="aspectFill" />
                  <text v-if="unavailable(service.availability)" class="platform-service__unavailable">
                    暂未开放
                  </text>
                </view>
                <text class="platform-service__service-title">{{ service.title }}</text>
                <text v-if="service.subtitle" class="platform-service__service-subtitle">
                  {{ service.subtitle }}
                </text>
                <view class="platform-service__service-meta">
                  <text class="platform-service__quote">{{ service.quoteLabel }}</text>
                </view>
                <view class="platform-service__service-stats">
                  <text class="platform-service__rating">
                    <UniIcons type="star-filled" size="11" color="#ffad00" />
                    {{ service.rating.toFixed(1) }}
                  </text>
                  <text class="platform-service__metric">{{ service.soldCountText }}</text>
                </view>
                <view class="platform-service__service-action">{{ service.actionLabel }}</view>
              </button>
            </view>
          </scroll-view>
          <view class="platform-service__guarantees">
            <text><UniIcons type="checkbox" size="11" color="#77808c" />平台担保</text>
            <text><UniIcons type="checkbox" size="11" color="#77808c" />费用透明</text>
            <text><UniIcons type="person" size="11" color="#77808c" />专业团队</text>
            <text><UniIcons type="star" size="11" color="#77808c" />真实服务</text>
          </view>
        </view>
      </view>

      <view class="platform-service__tabbar">
        <button
          v-for="tab in tabs"
          :key="tab.code"
          :class="['platform-service__tab', { 'platform-service__tab--active': (props.activeTab || 'SERVICE') === tab.code }]"
          @click="emit('selectTab', tab.code as 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE')"
        >
          <view v-if="tab.code === 'SERVICE'" class="platform-service__service-tab-icon">
            <UniIcons
              v-for="dot in 4"
              :key="dot"
              type="smallcircle-filled"
              size="12"
              :color="(props.activeTab || 'SERVICE') === tab.code ? '#ff6508' : '#4c535c'"
            />
          </view>
          <UniIcons
            v-else
            :type="tab.icon"
            size="25"
            :color="(props.activeTab || 'SERVICE') === tab.code ? '#ff6508' : '#4c535c'"
          />
          <text>{{ tab.label }}</text>
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.platform-service{min-height:100vh;color:#16191d;background:#f7f8fa;font-family:"PingFang SC","Microsoft YaHei",sans-serif}
button{margin:0;padding:0;border:0;background:transparent;line-height:1.2}
button::after{border:0}
.platform-service__scroll{padding:calc(env(safe-area-inset-top) + 10px) 18rpx calc(108rpx + env(safe-area-inset-bottom))}
.platform-service__top{display:grid;grid-template-columns:132rpx minmax(0,1fr) 54rpx;gap:16rpx;align-items:center;margin-bottom:10rpx}
.platform-service__city-line{display:flex;align-items:center;gap:8rpx}
.platform-service__city{font-size:31rpx;font-weight:800;white-space:nowrap}
.platform-service__weather{display:block;margin-top:3rpx;color:#4f5966;font-size:19rpx;white-space:nowrap}
.platform-service__search{display:flex;min-width:0;height:58rpx;align-items:center;gap:12rpx;padding:0 18rpx;border:2rpx solid #2c2f33;border-radius:31rpx;color:#8a9099;font-size:21rpx;text-align:left}
.platform-service__search text{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.platform-service__message{position:relative;display:flex;width:54rpx;height:58rpx;align-items:center;justify-content:center}
.platform-service__badge{position:absolute;right:-4rpx;top:-6rpx;min-width:28rpx;height:28rpx;padding:0 6rpx;border:3rpx solid #fff;border-radius:20rpx;color:#fff;background:#ff4136;font-size:16rpx;font-weight:700;line-height:25rpx}
.platform-service__channels{width:100%;margin-bottom:10rpx;white-space:nowrap}
.platform-service__channel-row{display:grid;min-width:100%;grid-template-columns:repeat(5,max-content);align-items:center;justify-content:space-between;gap:8rpx}
.platform-service__channel{position:relative;min-height:52rpx;padding:0 16rpx;border-radius:20rpx;color:#30353b;background:#fff;font-size:20rpx;line-height:52rpx;white-space:nowrap}
.platform-service__channel--active{color:#fff;background:linear-gradient(90deg,#ff9c00,#ff6200);font-weight:800}
.platform-service__channel--active::after{content:"";position:absolute;right:28%;bottom:-5rpx;left:28%;height:5rpx;border-radius:50%;background:#fff;box-shadow:0 3rpx 0 #ff7200}
.platform-service__channel-status{position:absolute;right:5rpx;bottom:1rpx;color:#ff6508;font-size:11rpx;line-height:1}
.platform-service__channel--active .platform-service__channel-status{color:#fff}
.platform-service__hero{position:relative;height:246rpx;overflow:hidden;border-radius:23rpx;background:#dfefff}
.platform-service__hero-image{width:100%;height:100%}
.platform-service__hero-copy{position:absolute;top:31rpx;left:32rpx;display:flex;width:46%;flex-direction:column;align-items:flex-start}
.platform-service__hero-title,.platform-service__hero-highlight{font-size:35rpx;font-weight:900;line-height:1.06}
.platform-service__hero-highlight{color:#ff5d00}
.platform-service__hero-description{margin-top:9rpx;color:#394451;font-size:17rpx;line-height:1.35}
.platform-service__hero-action{min-width:138rpx;min-height:46rpx;margin-top:11rpx;padding:0 20rpx;border-radius:25rpx;color:#fff;background:linear-gradient(90deg,#ff9000,#ff5e00);font-size:20rpx;font-weight:700;line-height:46rpx}
.platform-service__scenario-grid{display:grid;height:350rpx;grid-template-columns:repeat(4,minmax(0,1fr));grid-template-rows:repeat(2,minmax(0,1fr));gap:10rpx 12rpx;margin-top:12rpx;padding:14rpx 15rpx 10rpx;border-radius:24rpx;background:#fff}
.platform-service__scenario{min-width:0;text-align:center}
.platform-service__scenario-icon-wrap{position:relative;display:flex;width:82rpx;height:72rpx;align-items:center;justify-content:center;margin:0 auto;border-radius:18rpx}
.platform-service__scenario-title{display:block;margin-top:5rpx;overflow-wrap:break-word;font-size:20rpx;font-weight:750;line-height:1.05;white-space:nowrap}
.platform-service__scenario-subtitle{display:block;margin-top:1rpx;overflow:hidden;color:#737c87;font-size:14rpx;line-height:1.05;text-overflow:ellipsis;white-space:nowrap}
.platform-service__unavailable{position:absolute;right:-4rpx;bottom:-3rpx;padding:3rpx 6rpx;border-radius:9rpx;color:#fff;background:rgba(30,35,42,.72);font-size:11rpx;white-space:nowrap}
.platform-service__capabilities,.platform-service__recommendations{margin-top:12rpx;padding:14rpx 15rpx;border-radius:24rpx;background:#fff}
.platform-service__section-heading{display:flex;align-items:center;gap:8rpx;margin-bottom:10rpx}
.platform-service__section-title{font-size:24rpx;font-weight:800}
.platform-service__section-copy{color:#77808c;font-size:16rpx}
.platform-service__capability-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8rpx}
.platform-service__capability{display:flex;min-width:0;align-items:center;gap:6rpx}
.platform-service__capability-icon{display:flex;width:34rpx;height:34rpx;flex:0 0 auto;align-items:center;justify-content:center;border-radius:50%;background:#e8faef}
.platform-service__capability-copy{min-width:0}
.platform-service__capability-title{display:block;overflow:hidden;font-size:16rpx;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.platform-service__capability-description{display:block;margin-top:1rpx;overflow:hidden;color:#7b8490;font-size:13rpx;text-overflow:ellipsis;white-space:nowrap}
.platform-service__recommendation-scroll{width:100%;white-space:nowrap}
.platform-service__recommendation-row{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:9rpx}
.platform-service__service-card{min-width:0;text-align:left}
.platform-service__service-image-wrap{position:relative;height:104rpx;overflow:hidden;border-radius:13rpx}
.platform-service__service-image{width:100%;height:100%}
.platform-service__service-title{display:block;margin-top:6rpx;overflow:hidden;font-size:17rpx;font-weight:750;text-overflow:ellipsis;white-space:nowrap}
.platform-service__service-subtitle{display:block;margin-top:1rpx;overflow:hidden;color:#707986;font-size:13rpx;text-overflow:ellipsis;white-space:nowrap}
.platform-service__service-meta{display:flex;min-height:22rpx;align-items:center;margin-top:3rpx}
.platform-service__service-stats{display:flex;align-items:center;justify-content:space-between}
.platform-service__quote{color:#ff5d00;font-size:18rpx;font-weight:800}
.platform-service__rating{display:flex;align-items:center;color:#7a838e;font-size:12rpx}
.platform-service__metric{color:#7a838e;font-size:12rpx}
.platform-service__service-action{height:28rpx;margin-top:3rpx;border-radius:14rpx;color:#fff;background:linear-gradient(90deg,#ff9300,#ff6508);font-size:14rpx;line-height:28rpx;text-align:center}
.platform-service__guarantees{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8rpx;margin-top:8rpx;padding-top:7rpx;border-top:1rpx solid #edf0f3;color:#626c79;font-size:13rpx;text-align:center}
.platform-service__tabbar{position:fixed;right:0;bottom:0;left:0;z-index:20;display:grid;grid-template-columns:repeat(5,1fr);padding:7rpx 14rpx calc(7rpx + env(safe-area-inset-bottom));border-top:1rpx solid #e7e9ed;background:rgba(255,255,255,.97)}
.platform-service__tab{display:flex;min-height:82rpx;flex-direction:column;align-items:center;justify-content:center;gap:4rpx;color:#4c535c;font-size:18rpx}
.platform-service__tab--active{color:#ff6508;font-weight:700}
.platform-service__service-tab-icon{display:grid;width:50rpx;height:50rpx;grid-template-columns:repeat(2,20rpx);grid-template-rows:repeat(2,20rpx);place-content:center;gap:3rpx;line-height:1}
.platform-service__state{display:flex;min-height:100vh;flex-direction:column;align-items:center;justify-content:center;padding:40rpx}
.platform-service__state-title{margin-top:24rpx;color:#4f5966;font-size:28rpx;font-weight:700;text-align:center}
.platform-service__state-copy{margin-top:10rpx;color:#9198a1;font-size:22rpx}
.platform-service__retry{min-width:188rpx;min-height:76rpx;margin-top:28rpx;border-radius:40rpx;color:#fff;background:#ff6508;font-size:25rpx;line-height:76rpx}
.platform-service__skeleton{width:100%;border-radius:24rpx;background:linear-gradient(90deg,#edf0f3 25%,#f7f8fa 37%,#edf0f3 63%);background-size:400% 100%;animation:service-skeleton 1.25s ease infinite}
.platform-service__skeleton--head{height:70rpx}
.platform-service__skeleton--hero{height:314rpx;margin-top:26rpx}
.platform-service__skeleton--grid{height:390rpx;margin-top:26rpx}
@keyframes service-skeleton{0%{background-position:100% 0}100%{background-position:0 0}}
@media (min-width:420px){.platform-service__scroll{padding-right:26rpx;padding-left:26rpx}.platform-service__hero{height:328rpx}.platform-service__scenario-grid{gap:24rpx 18rpx}}
</style>
