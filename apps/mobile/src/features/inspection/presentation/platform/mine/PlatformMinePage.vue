<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref } from 'vue'
import PlatformUnavailableDialog from '../shared/PlatformUnavailableDialog.vue'
import { resolvePlatformAvailabilityAction } from '../shared/platform-availability'
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
const unavailableDialogVisible = ref(false)

const selectFeature = (entry: PlatformMineFeatureEntry): void => {
  if (resolvePlatformAvailabilityAction(entry.availability) === 'SHOW_UNAVAILABLE') {
    unavailableDialogVisible.value = true
    return
  }
  emit('selectFeature', entry)
}

const statusImageUrls: Record<InspectionMineStatus, string> = {
  AWAITING_PLAN: '/static/images/inspection/platform/mine-status-plan-v2.png',
  AWAITING_CONFIRMATION: '/static/images/inspection/platform/mine-status-confirm-v2.png',
  IN_PROGRESS: '/static/images/inspection/platform/mine-status-progress-v2.png',
  AWAITING_ACCEPTANCE: '/static/images/inspection/platform/mine-status-acceptance-v2.png',
  COMPLETED: '/static/images/inspection/platform/mine-status-completed-v2.png',
}

const tabs = [
  { code: 'HOME', label: '首页', icon: 'home' },
  { code: 'SERVICE', label: '服务', icon: 'smallcircle-filled' },
  { code: 'DISCOVER', label: '发现', icon: 'navigate' },
  { code: 'MALL', label: '商城', icon: 'shop' },
  { code: 'MINE', label: '我的', icon: 'person' },
] as const
</script>

<template>
  <view class="platform-mine">
    <view v-if="props.state.kind === 'loading'" class="platform-mine__state">
      <view class="platform-mine__skeleton platform-mine__skeleton--profile" />
      <view class="platform-mine__skeleton platform-mine__skeleton--status" />
      <view class="platform-mine__skeleton platform-mine__skeleton--orders" />
    </view>

    <view v-else-if="props.state.kind === 'error'" class="platform-mine__state">
      <UniIcons type="info-filled" size="42" color="#ff6508" />
      <text class="platform-mine__state-title">{{ props.state.message }}</text>
      <button class="platform-mine__retry" @click="emit('retry')">重新加载</button>
    </view>

    <view v-else-if="props.state.kind === 'empty'" class="platform-mine__state">
      <UniIcons type="info" size="42" color="#a4acb7" />
      <text class="platform-mine__state-title">暂无个人中心数据</text>
      <text class="platform-mine__state-copy">登录后可查看巡检服务进度</text>
    </view>

    <template v-else-if="content">
      <view class="platform-mine__scroll">
        <view class="platform-mine__profile">
          <image
            class="platform-mine__profile-background"
            src="/static/images/inspection/platform/home-hero.png"
            mode="aspectFill"
          />
          <view class="platform-mine__profile-actions">
            <button class="platform-mine__headset" aria-label="联系客服" @click="emit('customerService')">
              <UniIcons type="headphones" size="28" color="#171b20" />
            </button>
            <button class="platform-mine__settings" aria-label="设置" @click="emit('settings')">
              <UniIcons type="gear" size="28" color="#171b20" />
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
                <UniIcons type="person-filled" size="40" color="#aab5c2" />
              </view>
            </view>
            <view class="platform-mine__identity-copy">
              <view class="platform-mine__name-line">
                <text class="platform-mine__name">{{ content.user?.displayName || '未登录用户' }}</text>
                <text class="platform-mine__level">{{ content.user?.levelName || 'Lv.0' }}</text>
              </view>
              <text class="platform-mine__certification">
                <UniIcons type="auth-filled" size="12" color="#f59e0b" />
                {{ content.user?.certificationLabel || '身份待完善' }}
              </text>
              <text class="platform-mine__location">
                {{ content.user?.locationText || '未设置所在地区' }}
              </text>
            </view>
          </view>
        </view>

        <view class="platform-mine__status-card">
          <button
            v-for="item in INSPECTION_STATUS_ITEMS"
            :key="item.code"
            class="platform-mine__status-item"
            @click="emit('selectStatus', item.code)"
          >
            <view class="platform-mine__status-icon">
              <image
                class="platform-mine__status-image"
                :src="statusImageUrls[item.code]"
                mode="aspectFit"
              />
              <text class="platform-mine__status-count">
                {{ content.statusCounts[item.code] > 99 ? '99+' : content.statusCounts[item.code] }}
              </text>
            </view>
            <text class="platform-mine__status-label">{{ item.label }}</text>
            <text class="platform-mine__status-value">{{ content.statusCounts[item.code] }}个订单</text>
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
                <text class="platform-mine__order-meta">
                  <UniIcons type="location" size="11" color="#76808c" />
                  {{ order.locationText }}
                </text>
                <text class="platform-mine__order-meta">
                  <UniIcons type="calendar" size="11" color="#76808c" />
                  {{ order.scheduledAtText }}
                </text>
                <text class="platform-mine__order-meta platform-mine__order-number">
                  订单编号：{{ order.orderNumber }}
                </text>
              </view>
              <view class="platform-mine__amount">
                <text class="platform-mine__amount-text">{{ order.amount.text }}</text>
                <text class="platform-mine__amount-label">{{ order.amount.label }}</text>
                <text class="platform-mine__order-action">查看订单</text>
              </view>
            </button>
          </view>
          <view v-else class="platform-mine__orders-empty">
            <text>暂无近期巡检订单</text>
          </view>
        </view>

        <view v-if="content.featureEntries.length" class="platform-mine__feature-grid">
          <button
            v-for="entry in content.featureEntries"
            :key="entry.code"
            class="platform-mine__feature"
            @click="selectFeature(entry)"
          >
            <view class="platform-mine__feature-icon">
              <image
                class="platform-mine__feature-image"
                :src="entry.imageUrl"
                mode="aspectFit"
              />
            </view>
            <text class="platform-mine__feature-title">{{ entry.title }}</text>
            <text class="platform-mine__feature-value">{{ entry.valueText }}</text>
          </button>
        </view>
      </view>

      <view class="platform-mine__tabbar">
        <button
          v-for="tab in tabs"
          :key="tab.code"
          :class="['platform-mine__tab', { 'platform-mine__tab--active': (props.activeTab || 'MINE') === tab.code }]"
          @click="emit('selectTab', tab.code as 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE')"
        >
          <view v-if="tab.code === 'SERVICE'" class="platform-mine__service-tab-icon">
            <UniIcons
              v-for="dot in 4"
              :key="dot"
              type="smallcircle-filled"
              size="12"
              :color="(props.activeTab || 'MINE') === tab.code ? '#ff6508' : '#4c535c'"
            />
          </view>
          <UniIcons
            v-else
            :type="tab.icon"
            size="25"
            :color="(props.activeTab || 'MINE') === tab.code ? '#ff6508' : '#4c535c'"
          />
          <text>{{ tab.label }}</text>
        </button>
      </view>
      <PlatformUnavailableDialog
        :visible="unavailableDialogVisible"
        @close="unavailableDialogVisible = false"
      />
    </template>
  </view>
</template>

<style scoped>
.platform-mine{min-height:100vh;color:#17191d;background:#f7f8fa;font-family:system-ui,-apple-system,BlinkMacSystemFont,"PingFang SC","Noto Sans CJK SC","Microsoft YaHei",sans-serif;font-synthesis:none;text-rendering:optimizeLegibility;-webkit-font-smoothing:antialiased}
button{margin:0;padding:0;border:0;background:transparent;font-family:inherit;line-height:1.2}
button::after{border:0}
.platform-mine__scroll{padding-bottom:calc(108rpx + env(safe-area-inset-bottom))}
.platform-mine__profile{position:relative;min-height:260rpx;overflow:hidden;padding:calc(env(safe-area-inset-top) + 76rpx) 34rpx 24rpx;background:linear-gradient(145deg,#dfeeff,#f7fbff)}
.platform-mine__profile-background{position:absolute;inset:0;width:100%;height:100%;opacity:.38;object-position:right center}
.platform-mine__profile::after{content:"";position:absolute;right:-70rpx;bottom:-50rpx;width:390rpx;height:210rpx;border-radius:50%;background:radial-gradient(circle,rgba(99,173,255,.26),rgba(99,173,255,0) 68%)}
.platform-mine__profile-actions{position:absolute;top:calc(env(safe-area-inset-top) + 20rpx);right:28rpx;z-index:2;display:flex;gap:22rpx}
.platform-mine__headset,.platform-mine__settings{display:flex;width:54rpx;height:54rpx;align-items:center;justify-content:center}
.platform-mine__identity{position:relative;z-index:1;display:flex;align-items:center;gap:22rpx}
.platform-mine__avatar{width:122rpx;height:122rpx;flex:0 0 auto;overflow:hidden;border:7rpx solid rgba(255,255,255,.94);border-radius:50%;background:#e9edf2;box-shadow:0 10rpx 26rpx rgba(69,106,149,.14)}
.platform-mine__avatar-image{width:100%;height:100%}
.platform-mine__avatar-fallback{display:flex;width:100%;height:100%;align-items:center;justify-content:center}
.platform-mine__identity-copy{display:flex;min-width:0;flex-direction:column;align-items:flex-start}
.platform-mine__name-line{display:flex;max-width:100%;align-items:center;gap:14rpx}
.platform-mine__name{overflow:hidden;font-size:31rpx;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.platform-mine__level{padding:2rpx 9rpx;border:1rpx solid #8ab7e8;border-radius:13rpx;color:#347ec6;font-size:15rpx}
.platform-mine__certification{display:flex;align-items:center;gap:4rpx;margin-top:8rpx;padding:4rpx 10rpx;border-radius:10rpx;color:#9b5b0a;background:#fff0dc;font-size:16rpx}
.platform-mine__location{margin-top:7rpx;color:#67717d;font-size:17rpx}
.platform-mine__status-card{position:relative;z-index:3;display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:4rpx;margin:-18rpx 22rpx 12rpx;padding:31rpx 8rpx 29rpx;border-radius:24rpx;background:#fff;box-shadow:0 7rpx 22rpx rgba(43,66,94,.07)}
.platform-mine__status-item{display:flex;min-width:0;flex-direction:column;align-items:center}
.platform-mine__status-icon{position:relative;display:flex;width:54rpx;height:48rpx;align-items:center;justify-content:center}
.platform-mine__status-image{width:48rpx;height:48rpx;border-radius:13rpx}
.platform-mine__status-count{position:absolute;right:-9rpx;top:-8rpx;min-width:27rpx;height:27rpx;padding:0 6rpx;border:3rpx solid #fff;border-radius:20rpx;color:#fff;background:#ff463b;font-size:15rpx;font-weight:700;line-height:24rpx}
.platform-mine__status-label{margin-top:4rpx;overflow-wrap:break-word;font-size:22rpx;font-weight:600;white-space:nowrap}
.platform-mine__status-value{margin-top:3rpx;color:#7d8590;font-size:17rpx;white-space:nowrap}
.platform-mine__orders{margin:0 22rpx 12rpx;padding:14rpx 20rpx 8rpx;border-radius:24rpx;background:#fff}
.platform-mine__section-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:10rpx}
.platform-mine__section-title{font-size:24rpx;font-weight:700}
.platform-mine__view-all{min-height:38rpx;color:#6d7681;font-size:17rpx;line-height:38rpx}
.platform-mine__order-list{display:flex;flex-direction:column}
.platform-mine__order{display:grid;grid-template-columns:176rpx minmax(0,1fr) 104rpx;gap:14rpx;align-items:center;padding:20rpx 0;border-bottom:1rpx solid #edf0f3;text-align:left}
.platform-mine__order:last-child{border-bottom:0}
.platform-mine__order-image{width:176rpx;height:128rpx;border-radius:19rpx}
.platform-mine__order-copy{display:flex;min-width:0;flex-direction:column}
.platform-mine__order-title-line{display:flex;min-width:0;align-items:center;gap:9rpx}
.platform-mine__order-title{overflow:hidden;font-size:21rpx;font-weight:600;text-overflow:ellipsis;white-space:nowrap}
.platform-mine__order-status{flex:0 0 auto;padding:3rpx 7rpx;border-radius:10rpx;color:#228b53;background:#e8f8ee;font-size:14rpx}
.platform-mine__order-meta{display:flex;align-items:center;gap:3rpx;margin-top:4rpx;overflow:hidden;color:#76808c;font-size:15rpx;text-overflow:ellipsis;white-space:nowrap}
.platform-mine__order-number{font-size:13rpx}
.platform-mine__amount{display:flex;min-width:0;flex-direction:column;align-items:flex-end}
.platform-mine__amount-label{margin-top:2rpx;color:#7a838e;font-size:14rpx}
.platform-mine__amount-text{max-width:104rpx;overflow-wrap:break-word;color:#ff6508;font-size:25rpx;font-weight:800;text-align:right}
.platform-mine__order-action{min-width:94rpx;height:33rpx;margin-top:7rpx;border:1rpx solid #8a929c;border-radius:17rpx;color:#353b42;font-size:14rpx;line-height:31rpx;text-align:center}
.platform-mine__orders-empty{padding:46rpx 0;color:#8a929c;font-size:22rpx;text-align:center}
.platform-mine__feature-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:4rpx;margin:0 22rpx;padding:12rpx 14rpx;border-radius:24rpx;background:#fff}
.platform-mine__feature{position:relative;display:flex;min-width:0;flex-direction:column;align-items:center}
.platform-mine__feature-icon{display:flex;width:44rpx;height:44rpx;align-items:center;justify-content:center;overflow:hidden;border-radius:13rpx}
.platform-mine__feature-image{width:100%;height:100%}
.platform-mine__feature-title{margin-top:7rpx;overflow-wrap:break-word;font-size:22rpx;font-weight:600;text-align:center}
.platform-mine__feature-value{margin-top:3rpx;color:#7d8590;font-size:17rpx}
.platform-mine__tabbar{position:fixed;right:0;bottom:0;left:0;z-index:20;display:grid;grid-template-columns:repeat(5,1fr);padding:7rpx 14rpx calc(7rpx + env(safe-area-inset-bottom));border-top:1rpx solid #e7e9ed;background:rgba(255,255,255,.97)}
.platform-mine__tab{display:flex;min-height:82rpx;flex-direction:column;align-items:center;justify-content:center;gap:4rpx;color:#4c535c;font-size:20rpx;font-weight:500}
.platform-mine__tab--active{color:#ff6508;font-weight:700}
.platform-mine__service-tab-icon{display:grid;width:50rpx;height:50rpx;grid-template-columns:repeat(2,20rpx);grid-template-rows:repeat(2,20rpx);place-content:center;gap:3rpx;line-height:1}
.platform-mine__state{display:flex;min-height:100vh;flex-direction:column;align-items:center;justify-content:center;padding:40rpx}
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
