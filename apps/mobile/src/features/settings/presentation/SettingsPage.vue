<script setup lang="ts">
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import type { AgreementDoc } from '../domain/agreement'
import { AGREEMENT_DOCUMENTS } from '../domain/agreement'

import { AGREEMENT_ICONS, SETTINGS_RULE_DOCS } from './settings-icons'

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

// Row icons copied from the board Settings (显示名 and 所属组织 take the board Mine's person and
// organization icons; the board shows the phone number there, which accounts do not have yet).
const ICONS = {
  person: 'M12 12a4 4 0 1 0 0-8a4 4 0 1 0 0 8zM4 21c1-4 4-6 8-6s7 2 8 6',
  organization: 'M4 20V8l6-4v16M10 20h10V10l-10-2M14 13h2M14 16h2',
  address: 'M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11zM12 12.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 1 0 0 5z',
  help: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM9.5 9.5a2.5 2.5 0 1 1 3.6 2.2c-.7.4-1.1.9-1.1 1.8M12 16.5h.01',
  about: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01',
  chevron: 'M9 5l7 7-7 7',
}

const RULES = SETTINGS_RULE_DOCS.map((doc) => ({ doc, d: AGREEMENT_ICONS[doc] }))
</script>

<template>
  <view class="settings" data-testid="settings-page">
    <AppPageHeader title="设置" test-id="settings" @back="emit('back')" />

    <view class="settings__body">
      <view class="settings__group">
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

      <button class="settings__card settings__row" data-testid="settings-addresses" @click="emit('addresses')">
        <LineIcon :d="ICONS.address" :size="20" color="#536178" />
        <text class="settings__row-title">地址管理</text>
        <text v-if="addressCount !== null" class="settings__row-value">{{ addressCount }} 个</text>
        <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
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
            <LineIcon :d="rule.d" :size="20" color="#536178" />
            <text class="settings__row-title">{{ AGREEMENT_DOCUMENTS[rule.doc].title }}</text>
            <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
        </view>
      </view>

      <view class="settings__card">
        <button class="settings__row" data-testid="settings-help" @click="emit('help')">
          <LineIcon :d="ICONS.help" :size="20" color="#536178" />
          <text class="settings__row-title">帮助与反馈</text>
          <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
        <button class="settings__row" data-testid="settings-about" @click="emit('about')">
          <LineIcon :d="ICONS.about" :size="20" color="#536178" />
          <text class="settings__row-title">关于</text>
          <LineIcon :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
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
