<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  AGREEMENT_BODY_PLACEHOLDER,
  AGREEMENT_DOCS,
  AGREEMENT_DOCUMENTS,
  type AgreementDoc,
} from '../domain/agreement'

import { SETTINGS_CHEVRON } from './settings-icons'

const HEADSET = 'M4 14v-2a8 8 0 1 1 16 0v2M4 14h3v5H5a1 1 0 0 1-1-1zM20 14h-3v5h2a1 1 0 0 0 1-1zM17 19c0 1.5-2 2-5 2'
const CHECK = 'M5 12l5 5 9-10'

// 协议与规则 (board Agreement, 390 wide): one document at a time, the others in 全部协议.
// The prop is not named doc: uni hands the page's query to the page component, and a `doc` attribute
// falling through would override it with the raw value of the link.
const props = withDefaults(defineProps<{
  selected: AgreementDoc
  // 有更新 (S5-9): the latest 系统通知 about each document the host has read; a document without one
  // (or not read yet) shows no entry.
  updates?: Partial<Record<AgreementDoc, { noticeId: string } | null>>
}>(), { updates: () => ({}) })

const emit = defineEmits<{
  back: []
  contactPlatform: []
  // The document on screen changed (on open and from 全部协议), so the host can read its update.
  shown: [doc: AgreementDoc]
  openUpdate: [noticeId: string]
}>()

const current = ref<AgreementDoc>(props.selected)
const sheetOpen = ref(false)
watch(() => props.selected, (doc) => { current.value = doc })
watch(current, (doc) => emit('shown', doc), { immediate: true })

const update = computed(() => props.updates[current.value] ?? null)

const document = computed(() => AGREEMENT_DOCUMENTS[current.value])

function pick(doc: AgreementDoc): void {
  current.value = doc
  sheetOpen.value = false
}
</script>

<template>
  <view class="agreement" data-testid="agreement-page" :data-doc="current">
    <AppPageHeader title="协议与规则" test-id="agreement" @back="emit('back')">
      <template #actions>
        <button class="agreement__all" data-testid="agreement-all" aria-haspopup="dialog" @click="sheetOpen = true">
          <text>全部协议</text>
        </button>
      </template>
    </AppPageHeader>

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
        <button
          v-if="update"
          class="agreement__update"
          data-testid="agreement-update"
          @click="update && emit('openUpdate', update.noticeId)"
        >
          <text class="agreement__update-tag">有更新</text>
          <text class="agreement__update-text">查看本次更新摘要</text>
          <LineIcon :d="SETTINGS_CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
      </view>

      <view class="agreement__card agreement__sections">
        <view v-for="section in document.sections" :key="section" class="agreement__section">
          <text class="agreement__section-title">{{ section }}</text>
          <text class="agreement__section-body" data-testid="agreement-placeholder">{{ AGREEMENT_BODY_PLACEHOLDER }}</text>
        </view>
      </view>

      <button class="agreement__card agreement__contact" data-testid="agreement-contact" @click="emit('contactPlatform')">
        <LineIcon :d="HEADSET" :size="20" color="#536178" />
        <text class="agreement__contact-text">对内容有疑问，联系平台客服</text>
        <LineIcon :d="SETTINGS_CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
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
          <LineIcon v-if="doc === current" :d="CHECK" :size="18" color="#ff6a00" :stroke-width="2.6" />
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

.agreement__all {
  display: flex;
  height: 44px;
  margin-right: 8px;
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
  box-shadow: var(--app-card-shadow);
}

.agreement__card--lead {
  padding: 16px 14px 14px;
  gap: 12px;
}

.agreement__update {
  display: flex;
  min-height: 44px;
  margin: -6px 0 -8px;
  flex-direction: row;
  align-items: center;
  gap: 6px;
  color: #202c42;
}

.agreement__update-tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.agreement__update-text {
  min-width: 0;
  flex: 1;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.agreement__title-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.agreement__title {
  min-width: 0;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

/* Sample data says so quietly (R5 #14). */
.agreement__sample {
  flex-shrink: 0;
  color: #738096;
  font-size: 11px;
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
