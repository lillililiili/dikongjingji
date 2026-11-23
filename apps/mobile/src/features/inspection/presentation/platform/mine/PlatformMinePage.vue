<script setup lang="ts">
import { computed } from 'vue'

import IconTile from '@/shared/ui/IconTile.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { iconTileToneAt } from '@/shared/ui/icon-tile-tones'

import {
  MINE_ENTRY_ICONS,
  MINE_GEAR_ICON,
  MINE_ORDER_STAGES,
  formatMineBadge,
  formatMineCity,
  formatMineSubline,
  type MineOrderStage,
  type PlatformMineViewState,
} from './platform-mine'

// Layout, sizes and colours copied from the 2026-11-14 boards Mine (signed in) and MineGuest.
const props = defineProps<{
  state: PlatformMineViewState
  // Goods orders exist (trade module, preview builds): 待付款 is their way in from here.
  goodsOrdersEntry?: boolean
}>()

const emit = defineEmits<{
  retry: []
  login: []
  settings: []
  selectStage: [stage: MineOrderStage]
  viewAllOrders: []
  openEnrollment: []
  openAcceptance: []
  selectLink: [code: string]
  switchIdentity: []
}>()

const content = computed(() => (props.state.kind === 'ready' ? props.state.content : undefined))
const signedIn = computed(() => Boolean(content.value?.user))

// Icon tiles take the board's colours in the order they appear on the page: the gear, the avatar
// and the 核销码 square come before 我的参与 when signed in, only the avatar for a guest.
const joinToneOffset = computed(() => {
  if (!signedIn.value) return 1
  return content.value?.enrollmentCard ? 3 : 2
})

const CHEVRON = 'M9 5l7 7-7 7'
const INFO = 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01'
const PERSON = 'M12 12a4 4 0 1 0 0-8a4 4 0 1 0 0 8zM4 21c1-4 4-6 8-6s7 2 8 6'
const QR = 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 14h2M14 18h2v2M18 18h2v2'
const BUILDING = 'M4 20V8l6-4v16M10 20h10V10l-10-2M14 13h2M14 16h2'

const stageCount = (code: MineOrderStage): number | undefined =>
  signedIn.value ? content.value?.orderCounts[code] : undefined
</script>

<template>
  <view class="platform-mine">
    <view v-if="props.state.kind === 'loading'" class="platform-mine__state">
      <view class="platform-mine__skeleton platform-mine__skeleton--profile" />
      <view class="platform-mine__skeleton platform-mine__skeleton--status" />
    </view>

    <view v-else-if="props.state.kind === 'error'" class="platform-mine__state">
      <text class="platform-mine__state-title">{{ props.state.message }}</text>
      <button class="platform-mine__retry" @click="emit('retry')">重新加载</button>
    </view>

    <template v-else-if="content">
      <view :class="['platform-mine__scroll', { 'platform-mine__scroll--guest': !signedIn }]">
        <!-- Signed in (board Mine): the gear tile above a gradient profile card. -->
        <template v-if="content.user">
          <view class="platform-mine__profile-actions">
            <button class="platform-mine__settings" data-testid="mine-settings" aria-label="设置" @click="emit('settings')">
              <IconTile tone="blue" :d="MINE_GEAR_ICON" :size="44" :icon-size="22" />
            </button>
          </view>
          <view class="platform-mine__profile" data-testid="mine-profile">
            <view v-if="content.user.enterpriseIdentity" class="platform-mine__identity" data-testid="mine-enterprise-identity">
              <!-- The board draws the enterprise in the same tilted avatar tile as the person. -->
              <view class="platform-mine__avatar">
                <LineIcon class="platform-mine__avatar-icon" :d="BUILDING" :size="28" color="#a94c26" />
                <view class="platform-mine__avatar-shine" />
              </view>
              <view class="platform-mine__identity-copy">
                <text class="platform-mine__name">{{ content.user.enterpriseIdentity.name }}</text>
                <text class="platform-mine__location">{{ content.user.enterpriseIdentity.roleLabel }}</text>
              </view>
              <button v-if="content.user.switchLabel" class="platform-mine__switch" data-testid="mine-switch-identity" @click.stop="emit('switchIdentity')">{{ content.user.switchLabel }}</button>
            </view>
            <view v-else class="platform-mine__identity">
              <view class="platform-mine__avatar">
                <image
                  v-if="content.user.avatarUrl"
                  class="platform-mine__avatar-image"
                  :src="content.user.avatarUrl"
                  mode="aspectFill"
                />
                <LineIcon v-else class="platform-mine__avatar-icon" :d="PERSON" :size="28" color="#a94c26" />
                <view class="platform-mine__avatar-shine" />
              </view>
              <view class="platform-mine__identity-copy">
                <view class="platform-mine__name-line">
                  <text class="platform-mine__name">{{ content.user.displayName }}</text>
                  <text v-if="content.user.levelName" class="platform-mine__level">{{ content.user.levelName }}</text>
                </view>
                <!-- Wraps between the number and the city, never inside 东营 (board Mine). -->
                <text v-if="content.user.maskedPhone" class="platform-mine__location" data-testid="mine-phone-line" :aria-label="formatMineSubline(content.user)"><text class="platform-mine__nowrap">{{ content.user.maskedPhone }} · </text><text class="platform-mine__nowrap">{{ formatMineCity(content.user.locationText) }}</text></text>
                <text v-else class="platform-mine__location">
                  <text v-if="content.user.certificationLabel" class="platform-mine__certification">{{ content.user.certificationLabel }} · </text>{{ formatMineCity(content.user.locationText) }}
                </text>
              </view>
              <button v-if="content.user.switchLabel" class="platform-mine__switch" data-testid="mine-switch-identity" @click.stop="emit('switchIdentity')">{{ content.user.switchLabel }}</button>
            </view>
          </view>
        </template>

        <!-- Guest (board MineGuest): one gradient band across the top. -->
        <view v-else class="platform-mine__guest-head" data-testid="mine-profile">
          <IconTile tone="blue" :d="PERSON" :size="56" :icon-size="28" />
          <view class="platform-mine__identity-copy">
            <text class="platform-mine__name">未登录</text>
            <text class="platform-mine__location">登录后查看订单和报名记录</text>
          </view>
          <button class="platform-mine__login" data-testid="mine-login" @click="emit('login')">
            登录 / 注册
          </button>
        </view>

        <view class="platform-mine__status-card">
          <view class="platform-mine__status-head">
            <text class="platform-mine__status-heading">我的订单</text>
            <button class="platform-mine__view-all" data-testid="mine-orders-all" @click="emit('viewAllOrders')">全部 ›</button>
          </view>
          <view class="platform-mine__status-row">
            <button
              v-for="item in MINE_ORDER_STAGES"
              :key="item.code"
              class="platform-mine__status-item"
              :data-testid="props.goodsOrdersEntry && item.code === 'TO_PAY' ? 'mine-goods-orders' : `mine-order-${item.code.toLowerCase()}`"
              :aria-label="item.label"
              @click="emit('selectStage', item.code)"
            >
              <view class="platform-mine__status-icon">
                <LineIcon :d="item.d" :size="24" color="#202c42" :stroke-width="1.6" />
                <!-- A badge only where an existing API gives a number and there is something to do. -->
                <text v-if="(stageCount(item.code) ?? 0) > 0" class="platform-mine__status-count">
                  {{ formatMineBadge(stageCount(item.code) ?? 0) }}
                </text>
              </view>
              <text class="platform-mine__status-label">{{ item.label }}</text>
            </button>
          </view>
          <button v-if="!signedIn" class="platform-mine__guest-hint" data-testid="mine-orders-login" @click="emit('login')">
            <LineIcon :d="INFO" :size="16" color="#ff6a00" :stroke-width="2" />
            <text class="platform-mine__guest-hint-text">登录后查看你的订单和服务进度</text>
            <text class="platform-mine__guest-hint-action">去登录 ›</text>
          </button>
        </view>

        <!-- Current items (board Mine): an enrolment to attend with its code, a report to accept. -->
        <view v-if="signedIn && content.enrollmentCard" class="platform-mine__todo platform-mine__todo--row" data-testid="mine-todo-enrollment">
          <button class="platform-mine__todo-copy" @click="emit('openEnrollment')">
            <text class="platform-mine__todo-tag platform-mine__todo-tag--green">报名成功 · 待参加</text>
            <text class="platform-mine__todo-title">{{ content.enrollmentCard.title }}</text>
            <text class="platform-mine__todo-meta">{{ content.enrollmentCard.meta }}</text>
          </button>
          <button
            class="platform-mine__todo-code"
            data-testid="mine-todo-ticket"
            aria-label="出示核销码"
            @click="emit('openEnrollment')"
          >
            <IconTile tone="orange" :size="60">
              <view class="platform-mine__todo-code-inner">
                <LineIcon :d="QR" :size="28" color="#CE681D" :stroke-width="1.6" />
                <text class="platform-mine__todo-code-text">核销码</text>
              </view>
            </IconTile>
          </button>
        </view>

        <view
          v-if="signedIn && content.acceptanceCard"
          class="platform-mine__todo platform-mine__order"
          data-testid="mine-todo-acceptance"
          @click="emit('openAcceptance')"
        >
          <text class="platform-mine__todo-tag platform-mine__todo-tag--orange">服务订单 · 待验收</text>
          <text class="platform-mine__todo-title platform-mine__order-title">{{ content.acceptanceCard.title }}</text>
          <view class="platform-mine__todo-foot">
            <text class="platform-mine__todo-meta">{{ content.acceptanceCard.meta }}</text>
            <text class="platform-mine__todo-action">去验收</text>
          </view>
        </view>

        <view v-if="content.joinEntries.length" class="platform-mine__joins" data-testid="mine-joins">
          <text class="platform-mine__joins-title">我的参与</text>
          <view class="platform-mine__joins-grid">
            <button
              v-for="(entry, index) in content.joinEntries"
              :key="entry.code"
              class="platform-mine__join"
              :data-testid="`mine-link-${entry.code.toLowerCase()}`"
              @click="emit('selectLink', entry.code)"
            >
              <IconTile :tone="iconTileToneAt(joinToneOffset + index)" :d="MINE_ENTRY_ICONS[entry.code]" :size="40" :icon-size="22" />
              <text class="platform-mine__join-title">{{ entry.title }}</text>
            </button>
          </view>
        </view>

        <view v-if="content.linkRows.length" class="platform-mine__rows">
          <button
            v-for="entry in content.linkRows"
            :key="entry.code"
            class="platform-mine__link-row"
            :data-testid="`mine-link-${entry.code.toLowerCase()}`"
            @click="emit('selectLink', entry.code)"
          >
            <LineIcon :d="MINE_ENTRY_ICONS[entry.code]" :size="20" color="#536178" />
            <text class="platform-mine__link-title">{{ entry.title }}</text>
            <text v-if="entry.valueText" class="platform-mine__link-value" :data-testid="`mine-link-value-${entry.code.toLowerCase()}`">{{ entry.valueText }}</text>
            <LineIcon :d="CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.platform-mine {
  min-height: 100vh;
  color: #202c42;
  background: #f5f7fc;
  font-family: 'PingFang SC', 'Noto Sans SC', 'Microsoft YaHei', sans-serif;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: inherit;
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
}

button::after {
  border: 0;
}

.platform-mine__scroll {
  display: flex;
  padding: calc(var(--status-bar-height) + 4px) 16px var(--app-root-tabbar-content-offset);
  flex-direction: column;
  gap: 8px;
}

.platform-mine__scroll--guest {
  padding-top: 0;
  gap: 10px;
}

/* Signed in */
.platform-mine__profile-actions {
  display: flex;
  margin: 0 -10px -12px 0;
  justify-content: flex-end;
  position: relative;
  z-index: 2;
}

.platform-mine__settings {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.platform-mine__profile {
  display: flex;
  min-height: 92px;
  padding: 15px 12px;
  align-items: center;
  border-radius: 20px;
  background: linear-gradient(120deg, #fff1e0, #f1eaff 60%, #e5f6f2);
  box-shadow: 0 5px 14px rgba(80, 73, 122, 0.05);
  box-sizing: border-box;
}

.platform-mine__identity {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 12px;
}

.platform-mine__avatar {
  position: relative;
  display: flex;
  width: 56px;
  height: 56px;
  flex: none;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 3px solid #fff;
  border-radius: 16px;
  background: linear-gradient(140deg, #ffe0b5, #ffc5b0);
  box-shadow: 0 4px 12px rgba(182, 86, 37, 0.13);
  transform: rotate(-3deg);
  box-sizing: border-box;
}

.platform-mine__avatar-image {
  width: 100%;
  height: 100%;
  transform: rotate(3deg);
}

.platform-mine__avatar-icon {
  transform: rotate(3deg);
}

.platform-mine__avatar-shine {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 7px;
  height: 7px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.72);
}

.platform-mine__identity-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.platform-mine__name-line {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 8px;
}

.platform-mine__name {
  overflow: hidden;
  font-size: 19px;
  font-weight: 700;
  line-height: 26px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.platform-mine__level {
  padding: 0 6px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #536178;
  font-size: 11px;
  line-height: 16px;
}

.platform-mine__location,
.platform-mine__certification {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.platform-mine__nowrap {
  white-space: nowrap;
}

/* S5-7 the switch button (board Mine). */
.platform-mine__switch {
  flex: none;
  min-height: 36px;
  margin: 0;
  padding: 0 12px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #fff;
  font-size: 12px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.platform-mine__switch::after {
  border: 0;
}

/* Guest */
.platform-mine__guest-head {
  display: flex;
  margin: 0 -16px;
  padding: calc(var(--status-bar-height) + 32px) 16px 12px;
  align-items: center;
  gap: 12px;
  background: linear-gradient(180deg, #fff0e3 0%, #fff8f2 70%, #f5f7fc 100%);
}

.platform-mine__login {
  display: flex;
  height: 40px;
  flex: none;
  padding: 0 16px;
  align-items: center;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 15px;
  font-weight: 700;
  white-space: nowrap;
}

.platform-mine__guest-hint {
  display: flex;
  min-height: 44px;
  margin: 0 6px;
  padding: 0 12px;
  align-items: center;
  gap: 6px;
  border-radius: 10px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  line-height: 18px;
}

.platform-mine__guest-hint-text {
  min-width: 0;
  flex: 1;
}

.platform-mine__guest-hint-action {
  flex: none;
  font-weight: 500;
  white-space: nowrap;
}

/* 我的订单 */
.platform-mine__status-card {
  display: flex;
  padding: 10px 8px 6px;
  flex-direction: column;
  gap: 8px;
  border-radius: 14px;
  background: #fff;
  box-shadow: var(--app-card-shadow);
}

.platform-mine__scroll--guest .platform-mine__status-card {
  padding-bottom: 8px;
}

.platform-mine__status-head {
  display: flex;
  padding: 0 8px;
  align-items: center;
  justify-content: space-between;
}

.platform-mine__status-heading,
.platform-mine__joins-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 22px;
}

.platform-mine__view-all {
  display: flex;
  min-height: 44px;
  margin: -6px -8px;
  padding: 0 8px;
  align-items: center;
  color: #627187;
  font-size: 13px;
}

.platform-mine__status-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.platform-mine__status-item {
  display: flex;
  min-width: 0;
  min-height: 48px;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  overflow: visible;
}

.platform-mine__status-icon {
  position: relative;
  display: flex;
}

.platform-mine__status-count {
  position: absolute;
  top: -4px;
  left: 50%;
  min-width: 16px;
  height: 16px;
  margin-left: 6px;
  padding: 0 4px;
  border-radius: 8px;
  color: #fff;
  background: #ff5200;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  box-sizing: border-box;
}

.platform-mine__status-label {
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

/* Current items */
.platform-mine__todo {
  display: flex;
  padding: 12px 14px;
  flex-direction: column;
  gap: 4px;
  border-radius: 14px;
  background: #fff;
  box-shadow: var(--app-card-shadow);
}

.platform-mine__todo--row {
  flex-direction: row;
  align-items: center;
  gap: 12px;
}

.platform-mine__todo-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.platform-mine__todo-tag {
  font-size: 12px;
  line-height: 17px;
}

.platform-mine__todo-tag--green {
  color: #11804a;
}

.platform-mine__todo-tag--orange {
  color: #ba470b;
}

.platform-mine__todo-title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.platform-mine__todo-meta {
  min-width: 0;
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.platform-mine__todo-code {
  flex: none;
}

.platform-mine__todo-code-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.platform-mine__todo-code-text {
  color: #536178;
  font-size: 11px;
  line-height: 14px;
}

.platform-mine__todo-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.platform-mine__todo-action {
  display: flex;
  min-height: 36px;
  flex: none;
  padding: 0 14px;
  align-items: center;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 13px;
}

/* 我的参与 */
.platform-mine__joins {
  display: flex;
  padding: 10px 8px 8px;
  flex-direction: column;
  gap: 8px;
  border-radius: 14px;
  background: #fff;
  box-shadow: var(--app-card-shadow);
}

.platform-mine__joins-title {
  padding: 0 8px;
}

.platform-mine__joins-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.platform-mine__join {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.platform-mine__join-title {
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

/* Rows */
.platform-mine__rows {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #fff;
  box-shadow: var(--app-card-shadow);
}

.platform-mine__link-row {
  display: flex;
  width: 100%;
  min-height: 44px;
  padding: 0 14px;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid #f0f1f3;
  box-sizing: border-box;
}

.platform-mine__link-row:last-child {
  border-bottom-color: transparent;
}

.platform-mine__link-title {
  flex: 1;
  font-size: 15px;
}

.platform-mine__link-value {
  color: #627187;
  font-size: 12px;
  white-space: nowrap;
}

/* Loading and error */
.platform-mine__state {
  display: flex;
  min-height: 100vh;
  padding: calc(var(--status-bar-height) + 16px) 16px;
  flex-direction: column;
  gap: 10px;
  box-sizing: border-box;
}

.platform-mine__state-title {
  margin-top: 40vh;
  color: #536178;
  font-size: 15px;
  text-align: center;
}

.platform-mine__retry {
  align-self: center;
  height: 40px;
  padding: 0 24px;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 14px;
  line-height: 40px;
}

.platform-mine__skeleton {
  border-radius: 14px;
  background: #eef2f8;
}

.platform-mine__skeleton--profile {
  height: 92px;
  margin-top: 40px;
}

.platform-mine__skeleton--status {
  height: 100px;
}
</style>
