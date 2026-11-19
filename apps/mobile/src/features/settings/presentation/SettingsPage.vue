<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { AgreementDoc } from '../domain/agreement'
import { AGREEMENT_DOCUMENTS } from '../domain/agreement'

// 设置 (board Settings, 390 wide) with only what plan §26.2 lists: no phone number, devices,
// notification switches, privacy export or account deletion yet.
defineProps<{
  // The signed-in account; null while it is read (or could not be).
  account: { displayName: string; organizationName: string | null } | null
  addressCount: number | null
}>()

const emit = defineEmits<{
  back: []
  addresses: []
  agreement: [doc: AgreementDoc]
  help: []
  about: []
  logout: []
}>()

const RULES: Array<{ doc: AgreementDoc; icon: string }> = [
  { doc: 'USER_AGREEMENT', icon: 'compose' },
  { doc: 'PRIVACY_POLICY', icon: 'locked' },
  { doc: 'CHILD_PRIVACY_RULES', icon: 'staff' },
]
</script>

<template>
  <view class="settings" data-testid="settings-page">
    <view class="settings__header">
      <button class="settings__back" data-testid="settings-back" aria-label="返回" @click="emit('back')">
        <UniIcons type="left" size="22" color="#202c42" />
      </button>
      <text class="settings__title">设置</text>
    </view>

    <view class="settings__body">
      <view class="settings__group">
        <text class="settings__group-title">账号</text>
        <view class="settings__card">
          <view class="settings__row settings__row--static">
            <UniIcons type="person" size="20" color="#536178" />
            <text class="settings__row-title">显示名</text>
            <text class="settings__row-value" data-testid="settings-display-name">{{ account?.displayName || '—' }}</text>
          </view>
          <view class="settings__row settings__row--static">
            <UniIcons type="staff" size="20" color="#536178" />
            <text class="settings__row-title">所属组织</text>
            <text class="settings__row-value" data-testid="settings-organization">
              {{ account ? account.organizationName || '个人账号' : '—' }}
            </text>
          </view>
        </view>
      </view>

      <button class="settings__card settings__row" data-testid="settings-addresses" @click="emit('addresses')">
        <UniIcons type="location" size="20" color="#536178" />
        <text class="settings__row-title">地址管理</text>
        <text v-if="addressCount !== null" class="settings__row-value">{{ addressCount }} 个</text>
        <UniIcons type="right" size="14" color="#91a0b4" />
      </button>

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
            <UniIcons :type="rule.icon" size="20" color="#536178" />
            <text class="settings__row-title">{{ AGREEMENT_DOCUMENTS[rule.doc].title }}</text>
            <UniIcons type="right" size="14" color="#91a0b4" />
          </button>
        </view>
      </view>

      <view class="settings__card">
        <button class="settings__row" data-testid="settings-help" @click="emit('help')">
          <UniIcons type="help" size="20" color="#536178" />
          <text class="settings__row-title">帮助与反馈</text>
          <UniIcons type="right" size="14" color="#91a0b4" />
        </button>
        <button class="settings__row" data-testid="settings-about" @click="emit('about')">
          <UniIcons type="info" size="20" color="#536178" />
          <text class="settings__row-title">关于</text>
          <UniIcons type="right" size="14" color="#91a0b4" />
        </button>
      </view>
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

.settings__header {
  display: flex;
  padding: calc(var(--status-bar-height) + 8px) 8px 8px;
  align-items: center;
  gap: 4px;
  /* The page head of the light theme (theme-page-head). */
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.settings__back {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.settings__title {
  font-size: 18px;
  font-weight: 700;
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

.settings__footer {
  margin-top: auto;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
}

.settings__logout {
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
