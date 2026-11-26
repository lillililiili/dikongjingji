<script setup lang="ts">
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { AGREEMENT_DOCUMENTS, type AgreementDoc } from '../domain/agreement'

import { AGREEMENT_ICONS, APP_MARK, SETTINGS_CHEVRON, SETTINGS_RULE_DOCS } from './settings-icons'

// 关于 (board About, 390 wide). The app name follows the prototype (低空产业平台); the filing number
// and the operator are not decided yet and say so.
defineProps<{ version: string }>()

const emit = defineEmits<{
  back: []
  agreement: [doc: AgreementDoc]
}>()

const RULES = SETTINGS_RULE_DOCS.map((doc) => ({ doc, d: AGREEMENT_ICONS[doc] }))
</script>

<template>
  <view class="about" data-testid="about-page">
    <AppPageHeader title="关于" test-id="about" @back="emit('back')" />

    <view class="about__body">
      <view class="about__brand">
        <view class="about__icon" role="img" aria-label="App 图标">
          <LineIcon :d="APP_MARK" :size="44" color="#ffffff" :stroke-width="1.7" />
        </view>
        <text class="about__name">低空产业平台</text>
        <text class="about__version" data-testid="about-version">版本 {{ version || '—' }}</text>
      </view>

      <view class="about__group">
        <text class="about__group-title">协议与规则</text>
        <view class="about__card">
          <button
            v-for="rule in RULES"
            :key="rule.doc"
            class="about__row"
            :data-testid="`about-agreement-${rule.doc.toLowerCase()}`"
            @click="emit('agreement', rule.doc)"
          >
            <LineIcon :d="rule.d" :size="20" color="#536178" />
            <text class="about__row-title">{{ AGREEMENT_DOCUMENTS[rule.doc].title }}</text>
            <LineIcon :d="SETTINGS_CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
        </view>
      </view>

      <view class="about__group">
        <text class="about__group-title">备案信息</text>
        <view class="about__card">
          <view class="about__row about__row--static">
            <text class="about__row-title">App 备案号</text>
            <text class="about__row-value" data-testid="about-filing">暂未公布</text>
          </view>
          <view class="about__row about__row--static">
            <text class="about__row-title">运营主体</text>
            <text class="about__row-value">暂未公布</text>
          </view>
        </view>
      </view>
    </view>

  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board About (390 wide). */
.about {
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

.about__body {
  display: flex;
  padding: 0 16px;
  flex-direction: column;
  gap: 14px;
}

.about__brand {
  display: flex;
  padding: 32px 0 18px;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.about__icon {
  display: flex;
  width: 76px;
  height: 76px;
  margin-bottom: 10px;
  align-items: center;
  justify-content: center;
  border-radius: 20px;
  background: linear-gradient(145deg, #ff8f3d 0%, #ff6a00 100%);
  box-shadow: 0 6px 16px rgba(255, 106, 0, 0.24);
}

.about__name {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.about__version {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.about__group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.about__group-title {
  padding: 0 4px;
  color: #738096;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.about__card {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.about__row {
  display: flex;
  width: 100%;
  min-height: 50px;
  padding: 0 14px;
  align-items: center;
  gap: 12px;
  box-sizing: border-box;
  border-bottom: 1px solid #f0f1f3;
  border-radius: 0;
  color: #202c42;
}

.about__row:last-child {
  border-bottom-color: transparent;
}

.about__row-title {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  line-height: 21px;
  white-space: nowrap;
}

.about__row-value {
  min-width: 0;
  color: #738096;
  font-size: 14px;
  line-height: 20px;
  text-align: right;
}

.about__copyright {
  /* Right under the filing card, as on the board. */
  padding: 20px 16px calc(28px + env(safe-area-inset-bottom));
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}
</style>
