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
}>()

const emit = defineEmits<{
  retry: []
  login: []
  customerService: []
  settings: []
  selectStatus: [status: InspectionMineStatus]
  selectOrder: [order: PlatformMineOrder]
  selectFeature: [entry: PlatformMineFeatureEntry]
  selectLink: [code: string]
  viewAllOrders: []
}>()

const content = computed(() => props.state.kind === 'ready' ? props.state.content : undefined)
const unavailableDialogVisible = ref(false)
const hasFeatureValue = (entry: PlatformMineFeatureEntry): boolean =>
  Boolean(entry.valueText?.trim())

const selectFeature = (entry: PlatformMineFeatureEntry): void => {
  if (resolvePlatformAvailabilityAction(entry.availability) === 'SHOW_UNAVAILABLE') {
    unavailableDialogVisible.value = true
    return
  }
  emit('selectFeature', entry)
}

// Line icons, as on the 2026-11-14 prototype board Mine.
const STATUS_ICONS: Record<InspectionMineStatus, string> = {
  AWAITING_PLAN: 'compose',
  AWAITING_CONFIRMATION: 'checkbox',
  IN_PROGRESS: 'paperplane',
  AWAITING_ACCEPTANCE: 'auth',
  COMPLETED: 'medal',
}
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
        <!-- Above the card, as on the prototype, so the card keeps its right side for the sign-in button. -->
        <view class="platform-mine__profile-actions">
          <button class="platform-mine__headset" aria-label="联系客服" @click="emit('customerService')">
            <UniIcons type="headphones" size="22" color="#202c42" />
          </button>
          <button class="platform-mine__settings" aria-label="设置" @click="emit('settings')">
            <UniIcons type="gear" size="22" color="#202c42" />
          </button>
        </view>
        <view class="platform-mine__profile">
          <view class="platform-mine__identity">
            <view class="platform-mine__avatar">
              <image
                v-if="content.user?.avatarUrl"
                class="platform-mine__avatar-image"
                :src="content.user.avatarUrl"
                mode="aspectFill"
              />
              <view v-else class="platform-mine__avatar-fallback">
                <UniIcons type="person" size="28" color="#a94c26" />
              </view>
            </view>
            <view v-if="content.user" class="platform-mine__identity-copy">
              <view class="platform-mine__name-line">
                <text class="platform-mine__name">{{ content.user.displayName }}</text>
                <text v-if="content.user.levelName" class="platform-mine__level">{{ content.user.levelName }}</text>
              </view>
              <text v-if="content.user.certificationLabel" class="platform-mine__certification">
                <UniIcons type="auth-filled" size="12" color="#f59e0b" />
                {{ content.user.certificationLabel }}
              </text>
              <text class="platform-mine__location">
                {{ content.user.locationText || '未设置所在地区' }}
              </text>
            </view>
            <!-- A guest has no level or certification to show, only the way in. -->
            <template v-else>
              <view class="platform-mine__identity-copy">
                <text class="platform-mine__name">未登录</text>
                <text class="platform-mine__location">登录后查看订单和服务进度</text>
              </view>
              <button class="platform-mine__login" data-testid="mine-login" @click="emit('login')">
                登录 / 注册
              </button>
            </template>
          </view>
        </view>

        <view v-if="content.statusCounts" class="platform-mine__status-card">
          <text class="platform-mine__status-heading">我的订单</text>
          <view class="platform-mine__status-row">
            <button
              v-for="item in INSPECTION_STATUS_ITEMS"
              :key="item.code"
              class="platform-mine__status-item"
              :aria-label="`${item.label}，${content.statusCounts[item.code]} 个订单`"
              @click="emit('selectStatus', item.code)"
            >
              <view class="platform-mine__status-icon">
                <UniIcons :type="STATUS_ICONS[item.code]" size="24" color="#202c42" />
                <!-- A badge only where there is something to do, as on the prototype. -->
                <text v-if="content.statusCounts[item.code] > 0" class="platform-mine__status-count">
                  {{ content.statusCounts[item.code] > 99 ? '99+' : content.statusCounts[item.code] }}
                </text>
              </view>
              <text class="platform-mine__status-label">{{ item.label }}</text>
            </button>
          </view>
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
                  <text class="platform-mine__order-status">{{ order.statusText || getInspectionStatusLabel(order.status) }}</text>
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

        <view v-if="content.joinEntries?.length" class="platform-mine__joins" data-testid="mine-joins">
          <text class="platform-mine__joins-title">我的参与</text>
          <view class="platform-mine__joins-grid">
            <button
              v-for="entry in content.joinEntries"
              :key="entry.code"
              class="platform-mine__join"
              :data-testid="`mine-link-${entry.code.toLowerCase()}`"
              @click="emit('selectLink', entry.code)"
            >
              <view class="platform-mine__join-icon">
                <UniIcons :type="entry.icon" size="22" color="#ff6a00" />
              </view>
              <text class="platform-mine__join-title">{{ entry.title }}</text>
            </button>
          </view>
        </view>

        <view v-if="content.linkRows?.length" class="platform-mine__rows">
          <button
            v-for="entry in content.linkRows"
            :key="entry.code"
            class="platform-mine__link-row"
            :data-testid="`mine-link-${entry.code.toLowerCase()}`"
            @click="emit('selectLink', entry.code)"
          >
            <UniIcons :type="entry.icon" size="20" color="#536178" />
            <text class="platform-mine__link-title">{{ entry.title }}</text>
            <UniIcons type="right" size="14" color="#91a0b4" />
          </button>
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
            <text v-if="hasFeatureValue(entry)" class="platform-mine__feature-value">{{ entry.valueText }}</text>
          </button>
        </view>
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
.platform-mine__scroll{padding-top:calc(var(--status-bar-height) + 10px);padding-bottom:var(--app-root-tabbar-content-offset)}
.platform-mine__profile{position:relative;min-height:260rpx;overflow:hidden;padding:32rpx 34rpx 24rpx;background:linear-gradient(145deg,#dfeeff,#f7fbff)}
.platform-mine__profile-background{position:absolute;inset:0;width:100%;height:100%;opacity:.38;object-position:right center}
.platform-mine__profile::after{content:"";position:absolute;right:-70rpx;bottom:-50rpx;width:390rpx;height:210rpx;border-radius:50%;background:radial-gradient(circle,rgba(99,173,255,.26),rgba(99,173,255,0) 68%)}
.platform-mine__profile-actions{position:absolute;top:20rpx;right:28rpx;z-index:2;display:flex;gap:22rpx}
.platform-mine__headset,.platform-mine__settings{display:flex;width:54rpx;height:54rpx;align-items:center;justify-content:center}
.platform-mine__identity{position:relative;z-index:1;display:flex;align-items:center;gap:22rpx}
.platform-mine__avatar{width:122rpx;height:122rpx;flex:0 0 auto;overflow:hidden;border:7rpx solid rgba(255,255,255,.94);border-radius:50%;background:#e9edf2;box-shadow:0 10rpx 26rpx rgba(69,106,149,.14)}
.platform-mine__avatar-image{width:100%;height:100%}
.platform-mine__avatar-fallback{display:flex;width:100%;height:100%;align-items:center;justify-content:center}
.platform-mine__identity-copy{display:flex;min-width:0;flex-direction:column;align-items:flex-start}
.platform-mine__name-line{display:flex;max-width:100%;align-items:center;gap:14rpx}
.platform-mine__name{overflow:hidden;font-size:31rpx;font-weight:700;text-overflow:ellipsis;white-space:nowrap}
.platform-mine__level{padding:2rpx 9rpx;border:1rpx solid #8ab7e8;border-radius:13rpx;color:#347ec6;font-size:22rpx}
.platform-mine__certification{display:flex;align-items:center;gap:4rpx;margin-top:8rpx;padding:4rpx 10rpx;border-radius:10rpx;color:#855009;background:#fff0dc;font-size:22rpx}
.platform-mine__location{margin-top:7rpx;color:#67717d;font-size:17rpx}
.platform-mine__status-card{position:relative;z-index:3;display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:4rpx;margin:-18rpx 22rpx 12rpx;padding:31rpx 8rpx 29rpx;border-radius:24rpx;background:#fff;box-shadow:0 7rpx 22rpx rgba(43,66,94,.07)}
.platform-mine__status-item{display:flex;min-width:0;flex-direction:column;align-items:center}
.platform-mine__status-icon{position:relative;display:flex;width:100rpx;height:60rpx;align-items:center;justify-content:flex-start}
.platform-mine__status-image{width:60rpx;height:60rpx;border-radius:13rpx}
.platform-mine__status-count{position:absolute;right:0;top:0;display:flex;box-sizing:border-box;min-width:36rpx;height:30rpx;align-items:center;justify-content:center;padding:0 6rpx;border:2rpx solid #fff;border-radius:20rpx;color:#fff;background:#ff463b;font-size:15rpx;font-weight:700;line-height:1}
.platform-mine__status-label{margin-top:4rpx;overflow-wrap:break-word;font-size:22rpx;font-weight:600;white-space:nowrap}
.platform-mine__status-value{margin-top:3rpx;color:#7d8590;font-size:17rpx;white-space:nowrap}
.platform-mine__orders{margin:0 22rpx 12rpx;padding:14rpx 20rpx 8rpx;border-radius:24rpx;background:#fff}
.platform-mine__section-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:10rpx}
.platform-mine__section-title{font-size:24rpx;font-weight:700}
.platform-mine__view-all{min-height:38rpx;color:#6d7681;font-size:17rpx;line-height:38rpx}
.platform-mine__order-list{display:flex;flex-direction:column}
.platform-mine__order{display:grid;grid-template-columns:176rpx minmax(0,1fr) minmax(104rpx,auto);gap:14rpx;align-items:center;padding:20rpx 0;border-bottom:1rpx solid #edf0f3;text-align:left}
.platform-mine__order:last-child{border-bottom:0}
.platform-mine__order-image{width:176rpx;height:128rpx;border-radius:19rpx}
.platform-mine__order-copy{display:flex;min-width:0;flex-direction:column}
.platform-mine__order-title-line{display:flex;min-width:0;flex-wrap:wrap;align-items:center;gap:3rpx 9rpx}
.platform-mine__order-title{overflow:hidden;font-size:21rpx;font-weight:600;text-overflow:ellipsis;white-space:nowrap}
.platform-mine__order-status{flex:0 0 auto;padding:3rpx 7rpx;border-radius:10rpx;color:#19733f;background:#e8f8ee;font-size:20rpx}
.platform-mine__order-meta{display:flex;align-items:center;gap:3rpx;margin-top:4rpx;overflow:hidden;color:#76808c;font-size:15rpx;text-overflow:ellipsis;white-space:nowrap}
.platform-mine__order-number{font-size:13rpx}
.platform-mine__amount{display:flex;min-width:0;flex-direction:column;align-items:flex-end}
.platform-mine__amount-label{margin-top:2rpx;color:#7a838e;font-size:14rpx}
.platform-mine__amount-text{color:#ff6508;font-size:25rpx;font-weight:800;text-align:right;white-space:nowrap}
.platform-mine__order-action{min-width:94rpx;height:38rpx;margin-top:7rpx;border:1rpx solid #737d88;border-radius:19rpx;color:#353b42;font-size:20rpx;line-height:36rpx;text-align:center}
.platform-mine__orders-empty{padding:46rpx 0;color:#8a929c;font-size:22rpx;text-align:center}
.platform-mine__feature-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:4rpx;margin:0 22rpx;padding:12rpx 14rpx;border-radius:24rpx;background:#fff}
.platform-mine__feature{position:relative;display:flex;min-width:0;flex-direction:column;align-items:center}
.platform-mine__feature-icon{display:flex;width:44rpx;height:44rpx;align-items:center;justify-content:center;overflow:hidden;border-radius:13rpx}
.platform-mine__feature-image{width:100%;height:100%}
.platform-mine__feature-title{margin-top:7rpx;overflow-wrap:break-word;font-size:22rpx;font-weight:600;text-align:center}
.platform-mine__feature-value{margin-top:3rpx;color:#7d8590;font-size:17rpx}
.platform-mine__state{display:flex;min-height:100vh;flex-direction:column;align-items:center;justify-content:center;padding:40rpx}
.platform-mine__state-title{margin-top:24rpx;color:#4f5966;font-size:28rpx;font-weight:700;text-align:center}
.platform-mine__state-copy{margin-top:10rpx;color:#9198a1;font-size:22rpx}
.platform-mine__retry{min-width:188rpx;min-height:76rpx;margin-top:28rpx;border-radius:40rpx;color:#fff;background:#ff6508;font-size:25rpx;line-height:76rpx}
.platform-mine__skeleton{width:100%;border-radius:24rpx;background:linear-gradient(90deg,#edf0f3 25%,#f7f8fa 37%,#edf0f3 63%);background-size:400% 100%;animation:mine-skeleton 1.25s ease infinite}
.platform-mine__skeleton--profile{height:300rpx}
.platform-mine__skeleton--status{height:150rpx;margin-top:26rpx}
.platform-mine__skeleton--orders{height:430rpx;margin-top:26rpx}
@keyframes mine-skeleton{0%{background-position:100% 0}100%{background-position:0 0}}
@media (min-width:420px){.platform-mine__status-card,.platform-mine__orders,.platform-mine__feature-grid{margin-right:28rpx;margin-left:28rpx}.platform-mine__order{grid-template-columns:188rpx minmax(0,1fr) minmax(112rpx,auto)}.platform-mine__order-image{width:188rpx}}
/* 2026-11-14 prototype (boards Mine and MineGuest, 390 wide): the theme layer over the layout above. */
.platform-mine{color:#202c42;background:#f5f7fc}
.platform-mine__scroll{display:flex;padding:calc(var(--status-bar-height) + 8px) 16px var(--app-root-tabbar-content-offset);flex-direction:column;gap:10px}
.platform-mine__profile{min-height:92px;padding:15px 12px;border-radius:20px;background:linear-gradient(120deg,#fff1e0,#f1eaff 60%,#e5f6f2);box-shadow:0 5px 14px rgba(80,73,122,.05);box-sizing:border-box}
.platform-mine__profile::after{display:none}
.platform-mine__profile-actions{position:static;display:flex;height:44px;margin:0 -8px -6px 0;justify-content:flex-end;gap:0}
.platform-mine__headset,.platform-mine__settings{width:44px;height:44px}
.platform-mine__identity{gap:12px}
.platform-mine__avatar{width:56px;height:56px;border:3px solid #fff;background:linear-gradient(140deg,#ffe0b5,#ffc5b0);box-shadow:0 4px 12px rgba(182,86,37,.13);box-sizing:border-box}
.platform-mine__name-line{gap:8px}
.platform-mine__name{font-size:19px;line-height:26px}
.platform-mine__level{padding:0 6px;border-color:#c9d3e1;border-radius:999px;color:#536178;font-size:11px;line-height:16px}
.platform-mine__certification{margin-top:4px;padding:1px 6px;border-radius:6px;color:#ba470b;background:#fff0e3;font-size:12px;line-height:17px}
.platform-mine__location{margin-top:4px;color:#627187;font-size:12px;line-height:17px}
.platform-mine__status-card{display:flex;margin:0;padding:10px 8px 8px;flex-direction:column;gap:8px;border-radius:14px;box-shadow:0 3px 12px rgba(38,60,101,.06);outline:1px solid rgba(232,237,244,.5);outline-offset:-1px}
.platform-mine__status-heading{padding:0 8px;font-size:16px;font-weight:700;line-height:22px}
.platform-mine__status-row{display:grid;grid-template-columns:repeat(5,minmax(0,1fr))}
.platform-mine__status-item{min-height:52px;gap:4px;justify-content:center}
.platform-mine__status-icon{width:auto;height:24px;justify-content:center}
.platform-mine__status-count{top:-6px;right:auto;left:50%;min-width:16px;height:16px;margin-left:6px;padding:0 4px;border:0;border-radius:8px;background:#ff5200;font-size:11px;line-height:16px}
.platform-mine__status-label{margin-top:0;font-size:12px;font-weight:400;line-height:17px}
.platform-mine__orders{margin:0;padding:14px;border-radius:14px;box-shadow:0 3px 12px rgba(38,60,101,.06);outline:1px solid rgba(232,237,244,.5);outline-offset:-1px}
.platform-mine__section-heading{margin-bottom:6px}
.platform-mine__section-title{font-size:16px;line-height:22px}
.platform-mine__view-all{min-height:44px;margin:-11px 0;color:#627187;font-size:13px;line-height:44px}
.platform-mine__order{grid-template-columns:72px minmax(0,1fr) auto;gap:12px;padding:12px 0;border-bottom-color:#f0f1f3}
.platform-mine__order-image{width:72px;height:72px;border-radius:10px}
.platform-mine__order-title{font-size:15px;line-height:21px}
.platform-mine__order-status{padding:1px 6px;border-radius:6px;color:#11804a;background:#e2f6ed;font-size:12px}
.platform-mine__order-meta{margin-top:2px;color:#627187;font-size:12px;line-height:17px}
.platform-mine__orders-empty{color:#738096;font-size:13px}
.platform-mine__feature-grid{margin:0;border-radius:14px;box-shadow:0 3px 12px rgba(38,60,101,.06);outline:1px solid rgba(232,237,244,.5);outline-offset:-1px}
.platform-mine__feature-title{font-size:13px;font-weight:500}
.platform-mine__identity-copy{flex:1}
.platform-mine__name-line{flex-wrap:wrap;row-gap:2px}
.platform-mine__login{flex:none;height:40px;margin:0;padding:0 16px;border-radius:999px;color:#fff;background:#ff6a00;font-size:14px;font-weight:500;line-height:40px;white-space:nowrap}
.platform-mine__login::after{display:none}
.platform-mine__order-title{display:-webkit-box;white-space:normal;-webkit-box-orient:vertical;-webkit-line-clamp:2}
.platform-mine__status-item{overflow:visible}
.platform-mine__amount-text{color:#e85400;font-size:15px;font-weight:700;line-height:21px}
.platform-mine__amount-label{margin-top:0;color:#738096;font-size:11px;line-height:16px}
.platform-mine__order-action{min-width:64px;height:26px;margin-top:6px;border-color:#dfe5ee;border-radius:13px;color:#202c42;font-size:12px;line-height:24px}
.platform-mine__feature-grid{padding:12px 8px;gap:0}
.platform-mine__feature{gap:6px}
.platform-mine__feature-icon{width:40px;height:40px;border-radius:12px}
.platform-mine__feature-title{margin-top:0;font-size:12px;line-height:17px}
.platform-mine__feature-value{margin-top:0;color:#738096;font-size:11px;line-height:16px}
.platform-mine__order-meta{white-space:normal;overflow-wrap:anywhere}
/* 我的参与 and the rows under it: sizes from the 2026-11-14 prototype board Mine. */
.platform-mine__joins {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 8px 8px;
  border-radius: 14px;
  background: #ffffff;
}

.platform-mine__joins-title {
  padding: 0 8px;
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.platform-mine__joins-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.platform-mine__join {
  display: flex;
  min-height: 62px;
  margin: 0;
  padding: 0;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  border: 0;
  color: #202c42;
  background: transparent;
}

.platform-mine__join::after,
.platform-mine__link-row::after {
  border: 0;
}

.platform-mine__join-icon {
  display: flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: #fff0e3;
}

.platform-mine__join-title {
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.platform-mine__rows {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
}

.platform-mine__link-row {
  display: flex;
  width: 100%;
  min-height: 44px;
  margin: 0;
  padding: 0 14px;
  align-items: center;
  gap: 12px;
  border: 0;
  border-bottom: 1px solid #f0f1f3;
  border-radius: 0;
  color: #202c42;
  background: transparent;
  text-align: left;
  box-sizing: border-box;
}

.platform-mine__link-row:last-child {
  border-bottom-color: transparent;
}

.platform-mine__link-title {
  flex: 1;
  font-size: 15px;
}
</style>
