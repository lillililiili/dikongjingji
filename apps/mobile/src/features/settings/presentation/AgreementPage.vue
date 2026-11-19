<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import {
  AGREEMENT_BODY_PLACEHOLDER,
  AGREEMENT_DOCS,
  AGREEMENT_DOCUMENTS,
  type AgreementDoc,
} from '../domain/agreement'

// 协议与规则 (board Agreement, 390 wide): one document at a time, the others in 全部协议.
// The prop is not named doc: uni hands the page's query to the page component, and a `doc` attribute
// falling through would override it with the raw value of the link.
const props = defineProps<{ selected: AgreementDoc }>()

const emit = defineEmits<{
  back: []
  contactPlatform: []
}>()

const current = ref<AgreementDoc>(props.selected)
const sheetOpen = ref(false)
watch(() => props.selected, (doc) => { current.value = doc })

const document = computed(() => AGREEMENT_DOCUMENTS[current.value])

function pick(doc: AgreementDoc): void {
  current.value = doc
  sheetOpen.value = false
}
</script>

<template>
  <view class="agreement" data-testid="agreement-page" :data-doc="current">
    <view class="agreement__header">
      <button class="agreement__back" data-testid="agreement-back" aria-label="返回" @click="emit('back')">
        <UniIcons type="left" size="22" color="#202c42" />
      </button>
      <text class="agreement__heading">协议与规则</text>
      <button class="agreement__all" data-testid="agreement-all" aria-haspopup="dialog" @click="sheetOpen = true">
        <text>全部协议</text>
      </button>
    </view>

    <view class="agreement__body">
      <view class="agreement__card agreement__card--lead">
        <view class="agreement__title-line">
          <text class="agreement__title" data-testid="agreement-title">{{ document.title }}</text>
          <text class="agreement__sample">示例</text>
        </view>
        <view class="agreement__meta">
          <view class="agreement__meta-item">
            <text class="agreement__meta-key">版本</text>
            <text class="agreement__meta-value">{{ document.version }}</text>
          </view>
          <view class="agreement__meta-item">
            <text class="agreement__meta-key">更新日期</text>
            <text class="agreement__meta-value">{{ document.updatedOn }}</text>
          </view>
          <view class="agreement__meta-item">
            <text class="agreement__meta-key">生效日期</text>
            <text class="agreement__meta-value">{{ document.effectiveOn }}</text>
          </view>
        </view>
        <text class="agreement__meta-note">版本和日期为示例，以正式发布的协议为准</text>
      </view>

      <view class="agreement__card agreement__sections">
        <view v-for="section in document.sections" :key="section" class="agreement__section">
          <text class="agreement__section-title">{{ section }}</text>
          <text class="agreement__section-body" data-testid="agreement-placeholder">{{ AGREEMENT_BODY_PLACEHOLDER }}</text>
        </view>
      </view>

      <button class="agreement__card agreement__contact" data-testid="agreement-contact" @click="emit('contactPlatform')">
        <UniIcons type="headphones" size="20" color="#536178" />
        <text class="agreement__contact-text">对内容有疑问，联系平台客服</text>
        <UniIcons type="right" size="14" color="#91a0b4" />
      </button>
    </view>

    <template v-if="sheetOpen">
      <view class="agreement__mask" aria-label="关闭" @click="sheetOpen = false" />
      <view class="agreement__sheet" role="dialog" aria-label="全部协议" data-testid="agreement-sheet">
        <text class="agreement__sheet-title">全部协议</text>
        <button
          v-for="doc in AGREEMENT_DOCS"
          :key="doc"
          :class="['agreement__sheet-row', { 'agreement__sheet-row--on': doc === current }]"
          :data-testid="`agreement-pick-${doc.toLowerCase()}`"
          :aria-pressed="doc === current"
          @click="pick(doc)"
        >
          <text class="agreement__sheet-name">{{ AGREEMENT_DOCUMENTS[doc].title }}</text>
          <text class="agreement__sheet-version">{{ AGREEMENT_DOCUMENTS[doc].version }}</text>
          <UniIcons v-if="doc === current" type="checkmarkempty" size="18" color="#ff6a00" />
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Agreement (390 wide). */
.agreement {
  min-height: 100vh;
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

.agreement__header {
  display: flex;
  padding: calc(var(--status-bar-height) + 8px) 16px 8px 8px;
  align-items: center;
  gap: 4px;
  /* The page head of the light theme (theme-page-head). */
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.agreement__back {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.agreement__heading {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  white-space: nowrap;
}

.agreement__all {
  display: flex;
  height: 44px;
  margin-left: auto;
  align-items: center;
}

.agreement__all text {
  height: 32px;
  padding: 0 14px;
  box-sizing: border-box;
  border: 1px solid #e5ebf3;
  border-radius: 999px;
  color: #536178;
  background: #ffffff;
  font-size: 13px;
  line-height: 30px;
  white-space: nowrap;
}

.agreement__body {
  display: flex;
  padding: 12px 16px 24px;
  flex-direction: column;
  gap: 10px;
}

.agreement__card {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
}

.agreement__card--lead {
  padding: 16px 14px 14px;
  gap: 12px;
}

.agreement__title-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.agreement__title {
  min-width: 0;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.agreement__sample {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  line-height: 16px;
}

.agreement__meta {
  display: grid;
  padding: 10px 0;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-radius: 10px;
  background: #f5f7fc;
}

.agreement__meta-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.agreement__meta-key {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.agreement__meta-value {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  white-space: nowrap;
}

.agreement__meta-note {
  margin-top: -4px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.agreement__sections {
  padding: 16px 14px;
  gap: 16px;
}

.agreement__section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.agreement__section-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 22px;
}

.agreement__section-body {
  color: #738096;
  font-size: 14px;
  line-height: 22px;
}

.agreement__contact {
  min-height: 50px;
  padding: 0 14px;
  flex-direction: row;
  align-items: center;
  gap: 10px;
  color: #202c42;
}

.agreement__contact-text {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  line-height: 21px;
}

.agreement__mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(0, 0, 0, 0.45);
}

.agreement__sheet {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 101;
  display: flex;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 8px;
  border-radius: 20px 20px 0 0;
  background: #ffffff;
}

.agreement__sheet-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.agreement__sheet-row {
  display: flex;
  width: 100%;
  min-height: 50px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
  border-radius: 0;
}

.agreement__sheet-name {
  min-width: 0;
  flex: 1;
  color: #202c42;
  font-size: 15px;
  line-height: 21px;
}

.agreement__sheet-row--on .agreement__sheet-name {
  color: #ba470b;
  font-weight: 500;
}

.agreement__sheet-version {
  flex-shrink: 0;
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}
</style>
