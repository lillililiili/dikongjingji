<script setup lang="ts">
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import type { PersonalPasswordStatus } from '@/features/account/domain/identity-account'
import LineIcon from '@/shared/ui/LineIcon.vue'

import type { AgreementDoc } from '../domain/agreement'
import { AGREEMENT_DOCUMENTS } from '../domain/agreement'

import { AGREEMENT_ICONS, SETTINGS_RULE_DOCS } from './settings-icons'

// 设置 (board Settings, 390 wide). Plan §26.2 built the account card, 地址管理, 协议与规则, 帮助与
// 反馈, 关于 and 退出登录; S5-9 added the 通知 switches. With the ACCOUNT switch (S5-8) the account
// group becomes the board's 手机号 and 登录设备管理, and 隐私 (个人信息查看与导出, 权限管理), 新手引导
// and 注销账户 appear.
withDefaults(defineProps<{
  // The signed-in account; null while it is read (or could not be).
  account: { displayName: string; organizationName: string | null; maskedPhone?: string | null } | null
  addressCount: number | null
  addressesEnabled: boolean
  helpEnabled: boolean
  accountPages?: boolean
  passwordStatus?: PersonalPasswordStatus | null
  passwordLoadFailed?: boolean
  // 处理中 next to 注销账户 while a closure request runs.
  closureTag?: string | null
  // The 通知 switches (S5-9); null leaves the group out (public builds, or not read yet).
  notifications?: { orderServicePush: boolean; activityPush: boolean; discoverDot: boolean } | null
}>(), { accountPages: false, closureTag: null, notifications: null })

const emit = defineEmits<{
  back: []
  addresses: []
  agreement: [doc: AgreementDoc]
  help: []
  about: []
  logout: []
  phone: []
  password: []
  devices: []
  data: []
  permissions: []
  onboarding: []
  closure: []
  toggleNotification: [key: 'orderServicePush' | 'activityPush' | 'discoverDot']
}>()

// The three switches of the board's 通知 group, in its order. 订单与服务进度's 站内消息 is always on.
const SWITCH_ICONS = {
  bell: 'M6 16v-5a6 6 0 1 1 12 0v5l1.5 2h-15zM10 20.5a2 2 0 0 0 4 0',
  flag: 'M6 21V4M6 4h11l-2 4 2 4H6',
  discover: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM15.5 8.5l-2 5-5 2 2-5z',
}

// Row icons copied from the board Settings (显示名 and 所属组织 take the board Mine's person and
// organization icons; the board shows the phone number there, which accounts do not have yet).
const ICONS = {
  person: 'M12 12a4 4 0 1 0 0-8a4 4 0 1 0 0 8zM4 21c1-4 4-6 8-6s7 2 8 6',
  organization: 'M4 20V8l6-4v16M10 20h10V10l-10-2M14 13h2M14 16h2',
  address: 'M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11zM12 12.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 1 0 0 5z',
  help: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM9.5 9.5a2.5 2.5 0 1 1 3.6 2.2c-.7.4-1.1.9-1.1 1.8M12 16.5h.01',
  about: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01',
  chevron: 'M9 5l7 7-7 7',
  // Board Settings rows (S5-8).
  phone: 'M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2',
  devices: 'M3 5h13v9H3zM6 18h7M9.5 14v4M18 9h3v11h-3z',
  data: 'M10 11a4 4 0 1 0 0-8a4 4 0 1 0 0 8zM3 21c.8-3.5 3.4-6 7-6c1.2 0 2.3.3 3.2.8M18 14v7M15 18l3 3 3-3',
  permissions: 'M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4',
  onboarding: 'M4 6h16v12H4zM8 12l3 3 5-6',
  closure: 'M4 7h16M10 11v6M14 11v6M5 7l1 13h12l1-13M9 7V4h6v3',
}

const RULES = SETTINGS_RULE_DOCS.map((doc) => ({ doc, d: AGREEMENT_ICONS[doc] }))
</script>

<template>
  <view class="settings" data-testid="settings-page">
    <AppPageHeader title="设置" test-id="settings" @back="emit('back')" />

    <view class="settings__body">
      <view v-if="accountPages" class="settings__group">
        <text class="settings__group-title">账号与安全</text>
        <view class="settings__card">
          <button class="settings__row" data-testid="settings-phone" @click="emit('phone')">
            <LineIcon :d="ICONS.phone" :size="20" color="#536178" />
            <text class="settings__row-title">手机号</text>
            <text class="settings__row-value" data-testid="settings-phone-value">{{ account?.maskedPhone || '未绑定' }}</text>
            <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
          <button class="settings__row" data-testid="settings-devices" @click="emit('devices')">
            <LineIcon :d="ICONS.devices" :size="20" color="#536178" />
            <text class="settings__row-title">登录设备管理</text>
            <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
          <button v-if="passwordStatus?.eligible || passwordLoadFailed" class="settings__row" data-testid="settings-password" @click="emit('password')">
            <LineIcon name="lock" :size="20" color="#536178" />
            <text class="settings__row-title">登录密码</text>
            <text class="settings__row-value">{{ passwordLoadFailed ? '重新加载' : passwordStatus?.hasPassword ? '修改密码' : '设置密码' }}</text>
            <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
        </view>
      </view>

      <view v-else class="settings__group">
        <text class="settings__group-title">账号与安全</text>
        <view class="settings__card">
          <view class="settings__row settings__row--static">
            <LineIcon :d="ICONS.person" :size="20" color="#536178" />
            <text class="settings__row-title">显示名</text>
            <text class="settings__row-value" data-testid="settings-display-name">{{ account?.displayName || '—' }}</text>
          </view>
          <view class="settings__row settings__row--static">
            <LineIcon :d="ICONS.organization" :size="20" color="#536178" />
            <text class="settings__row-title">所属组织</text>
            <text class="settings__row-value" data-testid="settings-organization">
              {{ account ? account.organizationName || '个人账号' : '—' }}
            </text>
          </view>
        </view>
      </view>

      <!-- 通知 (board Settings): switches only govern pushes, the 消息 tab keeps every notification. -->
      <view v-if="notifications" class="settings__group" data-testid="settings-notifications">
        <text class="settings__group-title">通知</text>
        <view class="settings__card">
          <view class="settings__notice-block">
            <view class="settings__notice-head">
              <LineIcon :d="SWITCH_ICONS.bell" :size="20" color="#536178" />
              <text class="settings__row-title">订单与服务进度</text>
            </view>
            <view class="settings__notice-sub">
              <view class="settings__notice-copy">
                <text class="settings__notice-label">站内消息</text>
                <text class="settings__notice-hint">交易与服务状态必须通知到你</text>
              </view>
              <text class="settings__row-value">始终开启</text>
            </view>
            <view class="settings__notice-sub settings__notice-sub--switch">
              <text class="settings__notice-label settings__notice-label--grow">推送</text>
              <button
                class="settings__switch"
                role="switch"
                :aria-checked="notifications.orderServicePush"
                aria-label="订单与服务进度推送"
                data-testid="settings-switch-order-push"
                @click="emit('toggleNotification', 'orderServicePush')"
              >
                <view :class="['settings__switch-track', { 'settings__switch-track--on': notifications.orderServicePush }]">
                  <view class="settings__switch-knob" />
                </view>
              </button>
            </view>
          </view>
          <view class="settings__row settings__row--switch">
            <LineIcon :d="SWITCH_ICONS.flag" :size="20" color="#536178" />
            <text class="settings__row-title">活动提醒</text>
            <button
              class="settings__switch"
              role="switch"
              :aria-checked="notifications.activityPush"
              aria-label="活动提醒"
              data-testid="settings-switch-activity"
              @click="emit('toggleNotification', 'activityPush')"
            >
              <view :class="['settings__switch-track', { 'settings__switch-track--on': notifications.activityPush }]">
                <view class="settings__switch-knob" />
              </view>
            </button>
          </view>
          <view class="settings__row settings__row--switch settings__row--tall">
            <LineIcon :d="SWITCH_ICONS.discover" :size="20" color="#536178" />
            <view class="settings__notice-copy">
              <text class="settings__row-title">发现内容更新红点</text>
              <text class="settings__notice-hint">关闭后底栏“发现”不再显示红点</text>
            </view>
            <button
              class="settings__switch"
              role="switch"
              :aria-checked="notifications.discoverDot"
              aria-label="发现内容更新红点"
              data-testid="settings-switch-discover-dot"
              @click="emit('toggleNotification', 'discoverDot')"
            >
              <view :class="['settings__switch-track', { 'settings__switch-track--on': notifications.discoverDot }]">
                <view class="settings__switch-knob" />
              </view>
            </button>
          </view>
        </view>
      </view>

      <button v-if="addressesEnabled" class="settings__card settings__row" data-testid="settings-addresses" @click="emit('addresses')">
        <LineIcon :d="ICONS.address" :size="20" color="#536178" />
        <text class="settings__row-title">地址管理</text>
        <text v-if="addressCount !== null" class="settings__row-value">{{ addressCount }} 个</text>
        <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
      </button>

      <view v-if="accountPages" class="settings__group">
        <text class="settings__group-title">隐私</text>
        <view class="settings__card">
          <button class="settings__row" data-testid="settings-data" @click="emit('data')">
            <LineIcon :d="ICONS.data" :size="20" color="#536178" />
            <text class="settings__row-title">个人信息查看与导出</text>
            <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
          <button class="settings__row" data-testid="settings-permissions" @click="emit('permissions')">
            <LineIcon :d="ICONS.permissions" :size="20" color="#536178" />
            <view class="settings__row-copy">
              <text class="settings__row-title">权限管理</text>
              <text class="settings__row-sub">定位、相机、通知</text>
            </view>
            <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
        </view>
      </view>

      <view class="settings__group">
        <text class="settings__group-title">协议与规则</text>
        <view class="settings__card">
          <button
            v-for="rule in RULES"
            :key="rule.doc"
            class="settings__row"
            :data-testid="`settings-agreement-${rule.doc.toLowerCase()}`"
            @click="emit('agreement', rule.doc)"
          >
            <LineIcon :d="rule.d" :size="20" color="#536178" />
            <text class="settings__row-title">{{ AGREEMENT_DOCUMENTS[rule.doc].title }}</text>
            <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
        </view>
      </view>

      <view class="settings__card">
        <button v-if="helpEnabled" class="settings__row" data-testid="settings-help" @click="emit('help')">
          <LineIcon :d="ICONS.help" :size="20" color="#536178" />
          <text class="settings__row-title">帮助与反馈</text>
          <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
        <button v-if="accountPages" class="settings__row" data-testid="settings-onboarding" @click="emit('onboarding')">
          <LineIcon :d="ICONS.onboarding" :size="20" color="#536178" />
          <text class="settings__row-title">新手引导</text>
          <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
        <button class="settings__row" data-testid="settings-about" @click="emit('about')">
          <LineIcon :d="ICONS.about" :size="20" color="#536178" />
          <text class="settings__row-title">关于</text>
          <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
      </view>
    </view>

    <view v-if="accountPages" class="settings__body settings__body--tail">
      <button class="settings__card settings__row" data-testid="settings-closure" aria-haspopup="dialog" @click="emit('closure')">
        <LineIcon :d="ICONS.closure" :size="20" color="#536178" />
        <text class="settings__row-title">注销账户</text>
        <text v-if="closureTag" class="settings__row-tag" data-testid="settings-closure-tag">{{ closureTag }}</text>
        <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
      </button>
    </view>

    <view class="settings__footer">
      <button class="settings__logout" data-testid="settings-logout" @click="emit('logout')">退出登录</button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Settings (390 wide). */
.settings {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: normal;
  text-align: left;
}

button::after {
  border: 0;
}

.settings__body {
  display: flex;
  padding: 12px 16px;
  flex-direction: column;
  gap: 14px;
}

.settings__group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.settings__group-title {
  padding: 0 4px;
  color: #738096;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.settings__card {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.settings__row {
  display: flex;
  width: 100%;
  min-height: 50px;
  padding: 8px 14px;
  flex-direction: row;
  align-items: center;
  gap: 12px;
  box-sizing: border-box;
  border-bottom: 1px solid #f0f1f3;
  border-radius: 0;
  color: #202c42;
}

.settings__row:last-child {
  border-bottom-color: transparent;
}

button.settings__card.settings__row {
  border-bottom: 0;
  border-radius: 14px;
}

.settings__row-title {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  font-size: 15px;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings__row-value {
  max-width: 60%;
  flex-shrink: 0;
  overflow: hidden;
  color: #738096;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings__body--tail {
  padding-top: 0;
}

.settings__row-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.settings__row-sub {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.settings__row-tag {
  flex-shrink: 0;
  color: #ba470b;
  font-size: 14px;
  line-height: 20px;
}

.settings__notice-block {
  display: flex;
  padding-bottom: 4px;
  flex-direction: column;
  border-bottom: 1px solid #f0f1f3;
}

.settings__notice-head {
  display: flex;
  min-height: 44px;
  padding: 0 14px;
  align-items: center;
  gap: 12px;
}

.settings__notice-sub {
  display: flex;
  padding: 2px 14px 6px 46px;
  align-items: center;
  gap: 12px;
}

.settings__notice-sub--switch {
  min-height: 44px;
  padding: 0 10px 0 46px;
}

.settings__notice-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.settings__notice-label {
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.settings__notice-label--grow {
  min-width: 0;
  flex: 1;
}

.settings__notice-hint {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.settings__row--switch {
  padding: 0 10px 0 14px;
}

.settings__row--tall {
  min-height: 56px;
  padding-top: 6px;
  padding-bottom: 6px;
}

/* The board's switch: a 52×44 touch target around a 44×26 track. */
.settings__switch {
  display: flex;
  width: 52px;
  height: 44px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
}

.settings__switch-track {
  display: flex;
  width: 44px;
  height: 26px;
  padding: 2px;
  box-sizing: border-box;
  justify-content: flex-start;
  border-radius: 13px;
  background: #c9d3e1;
}

.settings__switch-track--on {
  justify-content: flex-end;
  background: #ff6a00;
}

.settings__switch-knob {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}

.settings__footer {
  margin-top: auto;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
}

.settings__logout {
  box-shadow: var(--app-card-shadow);
  width: 100%;
  height: 50px;
  border-radius: 14px;
  color: #202c42;
  background: #ffffff;
  font-size: 16px;
  font-weight: 500;
  line-height: 50px;
  text-align: center;
}
</style>
