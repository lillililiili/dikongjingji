<script setup lang="ts">
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
</script>

<template>
  <view class="platform-service">
    <view v-if="props.state.kind === 'loading'" class="platform-service__state">
      <view class="platform-service__skeleton platform-service__skeleton--head" />
      <view class="platform-service__skeleton platform-service__skeleton--hero" />
      <view class="platform-service__skeleton platform-service__skeleton--grid" />
    </view>

    <view v-else-if="props.state.kind === 'error'" class="platform-service__state">
      <view class="platform-service__state-mark platform-service__state-mark--error" />
      <text class="platform-service__state-title">{{ props.state.message }}</text>
      <button class="platform-service__retry" @click="emit('retry')">重新加载</button>
    </view>

    <view v-else-if="props.state.kind === 'empty'" class="platform-service__state">
      <view class="platform-service__state-mark" />
      <text class="platform-service__state-title">当前城市暂无服务频道</text>
      <text class="platform-service__state-copy">服务开通后会在这里展示</text>
    </view>

    <template v-else>
      <view class="platform-service__scroll">
        <view class="platform-service__top">
          <view class="platform-service__location">
            <view class="platform-service__city-line">
              <text class="platform-service__city">{{ props.state.content.cityName }}</text>
              <view class="platform-service__caret" />
            </view>
            <text v-if="props.state.content.weatherText" class="platform-service__weather">
              {{ props.state.content.weatherText }}
            </text>
          </view>
          <button class="platform-service__search" @click="emit('search')">
            <view class="platform-service__search-icon" />
            <text>{{ props.state.content.searchPlaceholder || '搜索无人机服务、景点、活动等' }}</text>
          </button>
          <button class="platform-service__message" aria-label="消息" @click="emit('message')">
            <view class="platform-service__message-icon" />
            <text
              v-if="props.state.content.unreadCount && props.state.content.unreadCount > 0"
              class="platform-service__badge"
            >
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
            <view class="platform-service__scenario-image-wrap">
              <image class="platform-service__scenario-image" :src="scenario.imageUrl" mode="aspectFill" />
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
              v-for="capability in props.state.content.capabilities"
              :key="capability.code"
              class="platform-service__capability"
            >
              <view class="platform-service__capability-mark" />
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
                  <text v-if="service.quoteLabel" class="platform-service__quote">
                    {{ service.quoteLabel }}
                  </text>
                  <text v-if="service.rating" class="platform-service__metric">
                    {{ service.rating.toFixed(1) }}
                  </text>
                  <text v-if="service.soldCountText" class="platform-service__metric">
                    {{ service.soldCountText }}
                  </text>
                </view>
              </button>
            </view>
          </scroll-view>
          <view class="platform-service__guarantees">
            <text>平台担保</text>
            <text>费用透明</text>
            <text>专业团队</text>
            <text>真实服务</text>
          </view>
        </view>
      </view>

      <view class="platform-service__tabbar">
        <button
          v-for="tab in [
            { code: 'HOME', label: '首页' },
            { code: 'SERVICE', label: '服务' },
            { code: 'DISCOVER', label: '发现' },
            { code: 'MALL', label: '商城' },
            { code: 'MINE', label: '我的' },
          ]"
          :key="tab.code"
          :class="['platform-service__tab', { 'platform-service__tab--active': (props.activeTab || 'SERVICE') === tab.code }]"
          @click="emit('selectTab', tab.code as 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE')"
        >
          <view :class="['platform-service__tab-icon', `platform-service__tab-icon--${tab.code.toLowerCase()}`]" />
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
.platform-service__scroll{padding:calc(env(safe-area-inset-top) + 14px) 18rpx calc(128rpx + env(safe-area-inset-bottom))}
.platform-service__top{display:grid;grid-template-columns:126rpx minmax(0,1fr) 64rpx;gap:16rpx;align-items:center;margin:0 4rpx 18rpx}
.platform-service__city-line{display:flex;align-items:center;gap:8rpx}
.platform-service__city{font-size:34rpx;font-weight:800;white-space:nowrap}
.platform-service__caret{width:0;height:0;border-right:8rpx solid transparent;border-left:8rpx solid transparent;border-top:10rpx solid #17191d}
.platform-service__weather{display:block;margin-top:5rpx;color:#4f5966;font-size:20rpx;white-space:nowrap}
.platform-service__search{display:flex;min-width:0;height:68rpx;align-items:center;gap:16rpx;padding:0 22rpx;border:2rpx solid #2c2f33;border-radius:36rpx;color:#8a9099;font-size:23rpx;text-align:left}
.platform-service__search text{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.platform-service__search-icon{width:22rpx;height:22rpx;flex:0 0 auto;border:4rpx solid #23272c;border-radius:50%;position:relative}
.platform-service__search-icon::after{content:"";position:absolute;right:-9rpx;bottom:-7rpx;width:11rpx;height:4rpx;transform:rotate(45deg);border-radius:4rpx;background:#23272c}
.platform-service__message{position:relative;width:64rpx;height:64rpx}
.platform-service__message-icon{position:absolute;inset:14rpx 8rpx 15rpx;border:4rpx solid #1e2227;border-radius:12rpx}
.platform-service__message-icon::after{content:"";position:absolute;left:8rpx;bottom:-10rpx;width:12rpx;height:12rpx;border-bottom:4rpx solid #1e2227;border-left:4rpx solid #1e2227;transform:skewY(-34deg);background:#f7f8fa}
.platform-service__badge{position:absolute;right:-2rpx;top:-3rpx;min-width:30rpx;height:30rpx;padding:0 7rpx;border:3rpx solid #fff;border-radius:20rpx;color:#fff;background:#ff4136;font-size:18rpx;font-weight:700;line-height:27rpx}
.platform-service__channels{width:100%;margin-bottom:16rpx;white-space:nowrap}
.platform-service__channel-row{display:flex;gap:14rpx;padding:0 3rpx}
.platform-service__channel{position:relative;min-width:142rpx;min-height:70rpx;padding:0 22rpx;border-radius:22rpx;color:#30353b;background:#fff;font-size:23rpx;line-height:70rpx}
.platform-service__channel--active{color:#fff;background:linear-gradient(90deg,#ff9c00,#ff6200);font-weight:800}
.platform-service__channel-status{position:absolute;right:7rpx;bottom:3rpx;color:#ff6508;font-size:14rpx;line-height:1}
.platform-service__channel--active .platform-service__channel-status{color:#fff}
.platform-service__hero{position:relative;height:314rpx;overflow:hidden;border-radius:27rpx;background:#dfefff}
.platform-service__hero-image{width:100%;height:100%}
.platform-service__hero-copy{position:absolute;top:61rpx;left:33rpx;display:flex;width:45%;flex-direction:column;align-items:flex-start}
.platform-service__hero-title,.platform-service__hero-highlight{font-size:42rpx;font-weight:900;line-height:1.06}
.platform-service__hero-highlight{color:#ff5d00}
.platform-service__hero-description{margin-top:16rpx;color:#394451;font-size:20rpx;line-height:1.45}
.platform-service__hero-action{min-width:152rpx;min-height:56rpx;margin-top:17rpx;padding:0 24rpx;border-radius:30rpx;color:#fff;background:linear-gradient(90deg,#ff9000,#ff5e00);font-size:23rpx;font-weight:700;line-height:56rpx}
.platform-service__scenario-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:22rpx 14rpx;margin-top:18rpx;padding:24rpx 16rpx;border-radius:28rpx;background:#fff}
.platform-service__scenario{min-width:0;text-align:center}
.platform-service__scenario-image-wrap{position:relative;overflow:hidden;aspect-ratio:4/3;border-radius:20rpx;background:#eef3f7}
.platform-service__scenario-image{width:100%;height:100%}
.platform-service__scenario-title{display:block;margin-top:11rpx;overflow-wrap:break-word;font-size:22rpx;font-weight:750}
.platform-service__scenario-subtitle{display:block;margin-top:5rpx;overflow:hidden;color:#737c87;font-size:17rpx;text-overflow:ellipsis;white-space:nowrap}
.platform-service__unavailable{position:absolute;right:6rpx;bottom:6rpx;padding:4rpx 8rpx;border-radius:10rpx;color:#fff;background:rgba(30,35,42,.72);font-size:15rpx}
.platform-service__capabilities,.platform-service__recommendations{margin-top:18rpx;padding:22rpx 18rpx;border-radius:28rpx;background:#fff}
.platform-service__section-heading{display:flex;align-items:baseline;gap:12rpx;margin-bottom:18rpx}
.platform-service__section-title{font-size:29rpx;font-weight:800}
.platform-service__section-copy{color:#77808c;font-size:19rpx}
.platform-service__capability-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14rpx}
.platform-service__capability{display:flex;min-width:0;align-items:center;gap:8rpx}
.platform-service__capability-mark{width:36rpx;height:36rpx;flex:0 0 auto;border:5rpx solid #4dc67a;border-radius:50%}
.platform-service__capability-copy{min-width:0}
.platform-service__capability-title{display:block;overflow:hidden;font-size:19rpx;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.platform-service__capability-description{display:block;margin-top:3rpx;overflow:hidden;color:#7b8490;font-size:15rpx;text-overflow:ellipsis;white-space:nowrap}
.platform-service__recommendation-scroll{width:100%;white-space:nowrap}
.platform-service__recommendation-row{display:flex;gap:14rpx}
.platform-service__service-card{width:244rpx;flex:0 0 244rpx;text-align:left}
.platform-service__service-image-wrap{position:relative;height:150rpx;overflow:hidden;border-radius:17rpx}
.platform-service__service-image{width:100%;height:100%}
.platform-service__service-title{display:block;margin-top:11rpx;overflow:hidden;font-size:23rpx;font-weight:750;text-overflow:ellipsis;white-space:nowrap}
.platform-service__service-subtitle{display:block;margin-top:5rpx;overflow:hidden;color:#707986;font-size:18rpx;text-overflow:ellipsis;white-space:nowrap}
.platform-service__service-meta{display:flex;min-height:30rpx;align-items:center;gap:12rpx;margin-top:8rpx}
.platform-service__quote{color:#ff5d00;font-size:23rpx;font-weight:800}
.platform-service__metric{color:#7a838e;font-size:17rpx}
.platform-service__guarantees{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10rpx;margin-top:22rpx;padding-top:18rpx;border-top:1rpx solid #edf0f3;color:#626c79;font-size:17rpx;text-align:center}
.platform-service__tabbar{position:fixed;right:0;bottom:0;left:0;z-index:20;display:grid;grid-template-columns:repeat(5,1fr);padding:12rpx 14rpx calc(12rpx + env(safe-area-inset-bottom));border-top:1rpx solid #e7e9ed;background:rgba(255,255,255,.97)}
.platform-service__tab{display:flex;min-height:78rpx;flex-direction:column;align-items:center;justify-content:center;gap:7rpx;color:#4c535c;font-size:19rpx}
.platform-service__tab--active{color:#ff6508;font-weight:700}
.platform-service__tab-icon{position:relative;width:34rpx;height:34rpx;border:4rpx solid currentColor;border-radius:10rpx}
.platform-service__tab-icon--service{border-radius:50%}
.platform-service__tab-icon--discover{transform:rotate(45deg);border-radius:50% 50% 50% 8rpx}
.platform-service__tab-icon--mall{border-radius:7rpx 7rpx 11rpx 11rpx}
.platform-service__tab-icon--mine{border-radius:50% 50% 12rpx 12rpx}
.platform-service__state{display:flex;min-height:100vh;flex-direction:column;align-items:center;justify-content:center;padding:40rpx}
.platform-service__state-mark{width:76rpx;height:76rpx;border:6rpx solid #b8c0ca;border-radius:50%}
.platform-service__state-mark--error{border-color:#ff6508}
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
