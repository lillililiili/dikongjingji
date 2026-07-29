<script setup lang="ts">
import { computed } from 'vue'
import {
  getInspectionStatusLabel,
  INSPECTION_STATUS_ITEMS,
  type InspectionMineStatus,
  type PlatformMineFeatureEntry,
  type PlatformMineOrder,
  type PlatformMineViewState,
} from './platform-mine'

const props = defineProps<{
  state: PlatformMineViewState
  activeTab?: 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE'
}>()

const emit = defineEmits<{
  retry: []
  customerService: []
  settings: []
  selectStatus: [status: InspectionMineStatus]
  selectOrder: [order: PlatformMineOrder]
  selectFeature: [entry: PlatformMineFeatureEntry]
  viewAllOrders: []
  selectTab: [tab: 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE']
}>()

const content = computed(() => props.state.kind === 'ready' ? props.state.content : undefined)
</script>

<template>
  <view class="platform-mine">
    <view v-if="props.state.kind === 'loading'" class="platform-mine__state">
      <view class="platform-mine__skeleton platform-mine__skeleton--profile" />
      <view class="platform-mine__skeleton platform-mine__skeleton--status" />
      <view class="platform-mine__skeleton platform-mine__skeleton--orders" />
    </view>

    <view v-else-if="props.state.kind === 'error'" class="platform-mine__state">
      <view class="platform-mine__state-mark platform-mine__state-mark--error" />
      <text class="platform-mine__state-title">{{ props.state.message }}</text>
      <button class="platform-mine__retry" @click="emit('retry')">重新加载</button>
    </view>

    <view v-else-if="props.state.kind === 'empty'" class="platform-mine__state">
      <view class="platform-mine__state-mark" />
      <text class="platform-mine__state-title">暂无个人中心数据</text>
      <text class="platform-mine__state-copy">登录后可查看巡检服务进度</text>
    </view>

    <template v-else-if="content">
      <view class="platform-mine__scroll">
        <view class="platform-mine__profile">
          <view class="platform-mine__profile-actions">
            <button class="platform-mine__headset" aria-label="联系客服" @click="emit('customerService')">
              <view class="platform-mine__headset-icon" />
            </button>
            <button class="platform-mine__settings" aria-label="设置" @click="emit('settings')">
              <view class="platform-mine__settings-icon" />
            </button>
          </view>
          <view class="platform-mine__identity">
            <view class="platform-mine__avatar">
              <image
                v-if="content.user?.avatarUrl"
                class="platform-mine__avatar-image"
                :src="content.user.avatarUrl"
                mode="aspectFill"
              />
              <view v-else class="platform-mine__avatar-fallback">
                <view class="platform-mine__avatar-head" />
                <view class="platform-mine__avatar-body" />
              </view>
            </view>
            <view class="platform-mine__identity-copy">
              <view class="platform-mine__name-line">
                <text class="platform-mine__name">{{ content.user?.displayName || '未登录用户' }}</text>
                <text v-if="content.user?.levelName" class="platform-mine__level">
                  {{ content.user.levelName }}
                </text>
              </view>
              <text v-if="content.user?.certificationLabel" class="platform-mine__certification">
                {{ content.user.certificationLabel }}
              </text>
              <text v-if="content.user?.locationText" class="platform-mine__location">
                {{ content.user.locationText }}
              </text>
            </view>
          </view>
        </view>

        <view class="platform-mine__status-card">
          <button
            v-for="(item, index) in INSPECTION_STATUS_ITEMS"
            :key="item.code"
            class="platform-mine__status-item"
            @click="emit('selectStatus', item.code)"
          >
            <view :class="['platform-mine__status-icon', `platform-mine__status-icon--${index + 1}`]">
              <text
                v-if="typeof content.statusCounts[item.code] === 'number' && content.statusCounts[item.code]! > 0"
                class="platform-mine__status-count"
              >
                {{ content.statusCounts[item.code]! > 99 ? '99+' : content.statusCounts[item.code] }}
              </text>
            </view>
            <text class="platform-mine__status-label">{{ item.label }}</text>
          </button>
        </view>

        <view class="platform-mine__orders">
          <view class="platform-mine__section-heading">
            <text class="platform-mine__section-title">近期巡检订单</text>
            <button class="platform-mine__view-all" @click="emit('viewAllOrders')">全部订单</button>
          </view>
          <view v-if="content.recentOrders.length" class="platform-mine__order-list">
            <button
              v-for="order in content.recentOrders"
              :key="order.id"
              class="platform-mine__order"
              @click="emit('selectOrder', order)"
            >
              <image class="platform-mine__order-image" :src="order.imageUrl" mode="aspectFill" />
              <view class="platform-mine__order-copy">
                <view class="platform-mine__order-title-line">
                  <text class="platform-mine__order-title">{{ order.title }}</text>
                  <text class="platform-mine__order-status">{{ getInspectionStatusLabel(order.status) }}</text>
                </view>
                <text v-if="order.locationText" class="platform-mine__order-meta">{{ order.locationText }}</text>
                <text v-if="order.scheduledAtText" class="platform-mine__order-meta">
                  {{ order.scheduledAtText }}
                </text>
                <text v-if="order.orderNumber" class="platform-mine__order-meta">
                  订单编号：{{ order.orderNumber }}
                </text>
              </view>
              <view v-if="order.amount" class="platform-mine__amount">
                <text class="platform-mine__amount-label">{{ order.amount.label }}</text>
                <text class="platform-mine__amount-text">{{ order.amount.text }}</text>
              </view>
            </button>
          </view>
          <view v-else class="platform-mine__orders-empty">
            <text>暂无近期巡检订单</text>
          </view>
        </view>

        <view v-if="content.featureEntries.length" class="platform-mine__feature-grid">
          <button
            v-for="(entry, index) in content.featureEntries"
            :key="entry.code"
            class="platform-mine__feature"
            @click="emit('selectFeature', entry)"
          >
            <view :class="['platform-mine__feature-icon', `platform-mine__feature-icon--${index % 4}`]" />
            <text class="platform-mine__feature-title">{{ entry.title }}</text>
            <text v-if="entry.valueText" class="platform-mine__feature-value">{{ entry.valueText }}</text>
            <text
              v-if="entry.availability === 'UNAVAILABLE'"
              class="platform-mine__feature-unavailable"
            >
              暂未开放
            </text>
          </button>
        </view>
      </view>

      <view class="platform-mine__tabbar">
        <button
          v-for="tab in [
            { code: 'HOME', label: '首页' },
            { code: 'SERVICE', label: '服务' },
            { code: 'DISCOVER', label: '发现' },
            { code: 'MALL', label: '商城' },
            { code: 'MINE', label: '我的' },
          ]"
          :key="tab.code"
          :class="['platform-mine__tab', { 'platform-mine__tab--active': (props.activeTab || 'MINE') === tab.code }]"
          @click="emit('selectTab', tab.code as 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE')"
        >
          <view :class="['platform-mine__tab-icon', `platform-mine__tab-icon--${tab.code.toLowerCase()}`]" />
          <text>{{ tab.label }}</text>
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.platform-mine{min-height:100vh;color:#17191d;background:#f7f8fa;font-family:"PingFang SC","Microsoft YaHei",sans-serif}
button{margin:0;padding:0;border:0;background:transparent;line-height:1.2}
button::after{border:0}
.platform-mine__scroll{padding-bottom:calc(128rpx + env(safe-area-inset-bottom))}
.platform-mine__profile{position:relative;min-height:300rpx;overflow:hidden;padding:calc(env(safe-area-inset-top) + 78rpx) 38rpx 48rpx;background:linear-gradient(145deg,#e1efff,#f7fbff)}
.platform-mine__profile::after{content:"";position:absolute;right:-70rpx;bottom:-50rpx;width:390rpx;height:210rpx;border-radius:50%;background:radial-gradient(circle,rgba(99,173,255,.26),rgba(99,173,255,0) 68%)}
.platform-mine__profile-actions{position:absolute;top:calc(env(safe-area-inset-top) + 22rpx);right:30rpx;z-index:2;display:flex;gap:28rpx}
.platform-mine__headset,.platform-mine__settings{position:relative;width:58rpx;height:58rpx}
.platform-mine__headset-icon{position:absolute;inset:13rpx 8rpx 10rpx;border:5rpx solid #171b20;border-bottom-color:transparent;border-radius:50% 50% 20% 20%}
.platform-mine__headset-icon::before,.platform-mine__headset-icon::after{content:"";position:absolute;bottom:-3rpx;width:9rpx;height:22rpx;border-radius:6rpx;background:#171b20}
.platform-mine__headset-icon::before{left:-7rpx}.platform-mine__headset-icon::after{right:-7rpx}
.platform-mine__settings-icon{position:absolute;inset:10rpx;border:5rpx solid #171b20;border-radius:12rpx;transform:rotate(30deg)}
.platform-mine__settings-icon::after{content:"";position:absolute;inset:9rpx;border:4rpx solid #171b20;border-radius:50%}
.platform-mine__identity{position:relative;z-index:1;display:flex;align-items:center;gap:28rpx}
.platform-mine__avatar{width:122rpx;height:122rpx;flex:0 0 auto;overflow:hidden;border:7rpx solid rgba(255,255,255,.94);border-radius:50%;background:#e9edf2;box-shadow:0 10rpx 26rpx rgba(69,106,149,.14)}
.platform-mine__avatar-image{width:100%;height:100%}
.platform-mine__avatar-fallback{position:relative;width:100%;height:100%}
.platform-mine__avatar-head{position:absolute;top:25rpx;left:39rpx;width:38rpx;height:38rpx;border-radius:50%;background:#aab5c2}
.platform-mine__avatar-body{position:absolute;left:23rpx;bottom:14rpx;width:70rpx;height:42rpx;border-radius:50% 50% 20% 20%;background:#aab5c2}
.platform-mine__identity-copy{display:flex;min-width:0;flex-direction:column;align-items:flex-start}
.platform-mine__name-line{display:flex;max-width:100%;align-items:center;gap:14rpx}
.platform-mine__name{overflow:hidden;font-size:40rpx;font-weight:850;text-overflow:ellipsis;white-space:nowrap}
.platform-mine__level{padding:4rpx 12rpx;border:1rpx solid #8ab7e8;border-radius:15rpx;color:#347ec6;font-size:18rpx}
.platform-mine__certification{margin-top:13rpx;padding:7rpx 13rpx;border-radius:11rpx;color:#9b5b0a;background:#fff0dc;font-size:19rpx}
.platform-mine__location{margin-top:12rpx;color:#67717d;font-size:21rpx}
.platform-mine__status-card{position:relative;z-index:3;display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:4rpx;margin:-28rpx 22rpx 18rpx;padding:27rpx 10rpx 24rpx;border-radius:28rpx;background:#fff;box-shadow:0 9rpx 28rpx rgba(43,66,94,.08)}
.platform-mine__status-item{display:flex;min-width:0;flex-direction:column;align-items:center}
.platform-mine__status-icon{position:relative;width:54rpx;height:48rpx;border-radius:15rpx;background:linear-gradient(145deg,#75aaff,#3f85ee)}
.platform-mine__status-icon--2{background:linear-gradient(145deg,#7cbbff,#4d8ff5)}
.platform-mine__status-icon--3{background:linear-gradient(145deg,#ffd161,#ff9b10)}
.platform-mine__status-icon--4{background:linear-gradient(145deg,#6edb9b,#38b96c)}
.platform-mine__status-icon--5{background:linear-gradient(145deg,#8ee2ad,#49c87e)}
.platform-mine__status-count{position:absolute;right:-12rpx;top:-12rpx;min-width:29rpx;height:29rpx;padding:0 7rpx;border:3rpx solid #fff;border-radius:20rpx;color:#fff;background:#ff463b;font-size:17rpx;font-weight:700;line-height:26rpx}
.platform-mine__status-label{margin-top:11rpx;overflow-wrap:break-word;font-size:20rpx;white-space:nowrap}
.platform-mine__orders{margin:0 22rpx 18rpx;padding:25rpx 20rpx;border-radius:28rpx;background:#fff}
.platform-mine__section-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:10rpx}
.platform-mine__section-title{font-size:31rpx;font-weight:800}
.platform-mine__view-all{min-height:52rpx;color:#6d7681;font-size:21rpx;line-height:52rpx}
.platform-mine__order-list{display:flex;flex-direction:column}
.platform-mine__order{display:grid;grid-template-columns:176rpx minmax(0,1fr) 104rpx;gap:18rpx;align-items:center;padding:22rpx 0;border-bottom:1rpx solid #edf0f3;text-align:left}
.platform-mine__order:last-child{border-bottom:0}
.platform-mine__order-image{width:176rpx;height:128rpx;border-radius:19rpx}
.platform-mine__order-copy{display:flex;min-width:0;flex-direction:column}
.platform-mine__order-title-line{display:flex;min-width:0;align-items:center;gap:9rpx}
.platform-mine__order-title{overflow:hidden;font-size:24rpx;font-weight:750;text-overflow:ellipsis;white-space:nowrap}
.platform-mine__order-status{flex:0 0 auto;padding:4rpx 9rpx;border-radius:11rpx;color:#228b53;background:#e8f8ee;font-size:17rpx}
.platform-mine__order-meta{display:block;margin-top:8rpx;overflow:hidden;color:#76808c;font-size:18rpx;text-overflow:ellipsis;white-space:nowrap}
.platform-mine__amount{display:flex;min-width:0;flex-direction:column;align-items:flex-end}
.platform-mine__amount-label{color:#ff6508;font-size:18rpx}
.platform-mine__amount-text{max-width:104rpx;margin-top:5rpx;overflow-wrap:break-word;color:#ff6508;font-size:21rpx;font-weight:750;text-align:right}
.platform-mine__orders-empty{padding:46rpx 0;color:#8a929c;font-size:22rpx;text-align:center}
.platform-mine__feature-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:28rpx 10rpx;margin:0 22rpx;padding:30rpx 16rpx;border-radius:28rpx;background:#fff}
.platform-mine__feature{position:relative;display:flex;min-width:0;flex-direction:column;align-items:center}
.platform-mine__feature-icon{width:56rpx;height:56rpx;border:6rpx solid #66a3ff;border-radius:16rpx}
.platform-mine__feature-icon--1{border-radius:50%;border-color:#54c87c}
.platform-mine__feature-icon--2{transform:rotate(45deg);border-color:#ff9d2e}
.platform-mine__feature-icon--3{border-color:#8b70f0}
.platform-mine__feature-title{margin-top:11rpx;overflow-wrap:break-word;font-size:21rpx;font-weight:700;text-align:center}
.platform-mine__feature-value{margin-top:6rpx;color:#7d8590;font-size:17rpx}
.platform-mine__feature-unavailable{margin-top:5rpx;color:#ff6508;font-size:16rpx}
.platform-mine__tabbar{position:fixed;right:0;bottom:0;left:0;z-index:20;display:grid;grid-template-columns:repeat(5,1fr);padding:12rpx 14rpx calc(12rpx + env(safe-area-inset-bottom));border-top:1rpx solid #e7e9ed;background:rgba(255,255,255,.97)}
.platform-mine__tab{display:flex;min-height:78rpx;flex-direction:column;align-items:center;justify-content:center;gap:7rpx;color:#4c535c;font-size:19rpx}
.platform-mine__tab--active{color:#ff6508;font-weight:700}
.platform-mine__tab-icon{position:relative;width:34rpx;height:34rpx;border:4rpx solid currentColor;border-radius:10rpx}
.platform-mine__tab-icon--service{border-radius:50%}
.platform-mine__tab-icon--discover{transform:rotate(45deg);border-radius:50% 50% 50% 8rpx}
.platform-mine__tab-icon--mall{border-radius:7rpx 7rpx 11rpx 11rpx}
.platform-mine__tab-icon--mine{border-radius:50% 50% 12rpx 12rpx}
.platform-mine__state{display:flex;min-height:100vh;flex-direction:column;align-items:center;justify-content:center;padding:40rpx}
.platform-mine__state-mark{width:76rpx;height:76rpx;border:6rpx solid #b8c0ca;border-radius:50%}
.platform-mine__state-mark--error{border-color:#ff6508}
.platform-mine__state-title{margin-top:24rpx;color:#4f5966;font-size:28rpx;font-weight:700;text-align:center}
.platform-mine__state-copy{margin-top:10rpx;color:#9198a1;font-size:22rpx}
.platform-mine__retry{min-width:188rpx;min-height:76rpx;margin-top:28rpx;border-radius:40rpx;color:#fff;background:#ff6508;font-size:25rpx;line-height:76rpx}
.platform-mine__skeleton{width:100%;border-radius:24rpx;background:linear-gradient(90deg,#edf0f3 25%,#f7f8fa 37%,#edf0f3 63%);background-size:400% 100%;animation:mine-skeleton 1.25s ease infinite}
.platform-mine__skeleton--profile{height:300rpx}
.platform-mine__skeleton--status{height:150rpx;margin-top:26rpx}
.platform-mine__skeleton--orders{height:430rpx;margin-top:26rpx}
@keyframes mine-skeleton{0%{background-position:100% 0}100%{background-position:0 0}}
@media (min-width:420px){.platform-mine__status-card,.platform-mine__orders,.platform-mine__feature-grid{margin-right:28rpx;margin-left:28rpx}.platform-mine__order{grid-template-columns:188rpx minmax(0,1fr) 112rpx}.platform-mine__order-image{width:188rpx}}
</style>
