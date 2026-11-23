<script setup lang="ts">
import { computed } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import type { SystemNoticeLink } from '../domain/system-notice'

import { NOTICE_ICONS as ICONS, formatNoticeTime, noticeItemIcon, type NoticeDetailState } from './notice-format'

// 通知详情 (board NoticeDetail, 390 wide): one 系统通知 — what it says, its facts (版本、生效日期), the
// main changes, each leading to the page that handles it, and the action at the bottom.

const props = defineProps<{ state: NoticeDetailState }>()

const emit = defineEmits<{
  back: []
  retry: []
  open: [link: SystemNoticeLink]
  contactPlatform: []
}>()

const notice = computed(() => (props.state.kind === 'ready' ? props.state.notice : null))
</script>

<template>
  <view class="notice" data-testid="notice-page">
    <AppPageHeader title="通知详情" plain test-id="notice" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="notice__body" aria-label="正在加载">
      <view class="notice__skeleton notice__skeleton--lead" />
      <view class="notice__skeleton" />
    </view>

    <view v-else-if="state.kind !== 'ready'" class="notice__body">
      <view class="notice__state" :data-testid="state.kind === 'missing' ? 'notice-missing' : 'notice-error'">
        <text class="notice__state-title">{{ state.kind === 'missing' ? '这条通知已撤回或不存在' : '通知加载失败，请稍后重试' }}</text>
        <button v-if="state.kind === 'error'" class="notice__state-action" data-testid="notice-retry" @click="emit('retry')">
          重新加载
        </button>
      </view>
    </view>

    <template v-else-if="notice">
      <view class="notice__body">
        <view class="notice__card notice__lead">
          <view class="notice__heading">
            <view class="notice__bell">
              <LineIcon :d="ICONS.bell" :size="24" color="#536178" :stroke-width="1.8" />
            </view>
            <view class="notice__titles">
              <text class="notice__title" data-testid="notice-title">{{ notice.title }}</text>
              <text class="notice__meta">系统通知 · {{ formatNoticeTime(notice.publishedAt) }}</text>
            </view>
          </view>
          <text
            v-for="(paragraph, index) in notice.paragraphs"
            :key="`p-${index}`"
            class="notice__paragraph"
            data-testid="notice-paragraph"
          >{{ paragraph }}</text>
          <view
            v-if="notice.facts.length"
            class="notice__facts"
            :style="{ gridTemplateColumns: `repeat(${notice.facts.length}, minmax(0, 1fr))` }"
          >
            <view v-for="fact in notice.facts" :key="fact.label" class="notice__fact" data-testid="notice-fact">
              <text class="notice__fact-key">{{ fact.label }}</text>
              <text class="notice__fact-value">{{ fact.value }}</text>
            </view>
          </view>
        </view>

        <view v-if="notice.items.length" class="notice__card notice__changes">
          <text class="notice__changes-title">主要变化</text>
          <button
            v-for="(item, index) in notice.items"
            :key="`i-${index}`"
            class="notice__change"
            :data-testid="`notice-item-${index}`"
            :disabled="!item.link"
            @click="item.link && emit('open', item.link)"
          >
            <view class="notice__change-icon">
              <LineIcon :d="noticeItemIcon(item.link)" :size="20" color="#ff6a00" :stroke-width="1.8" />
            </view>
            <view class="notice__change-copy">
              <text class="notice__change-title">{{ item.title }}</text>
              <text class="notice__change-text">{{ item.description }}</text>
            </view>
            <LineIcon v-if="item.link" :d="ICONS.chevron" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
        </view>

        <view v-if="notice.footnote" class="notice__footnote">
          <LineIcon :d="ICONS.info" :size="14" color="#738096" :stroke-width="2" />
          <text class="notice__footnote-text">{{ notice.footnote }}</text>
        </view>
      </view>

      <view class="notice__bar">
        <button class="notice__service" data-testid="notice-contact" @click="emit('contactPlatform')">
          <LineIcon :d="ICONS.service" :size="22" color="#536178" :stroke-width="1.8" />
          <text>客服</text>
        </button>
        <button
          v-if="notice.action"
          class="notice__primary"
          data-testid="notice-action"
          @click="notice.action && emit('open', notice.action.link)"
        >
          {{ notice.action.label }}
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board NoticeDetail (390 wide). */
.notice {
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

.notice__body {
  display: flex;
  padding: 12px 16px;
  flex: 1;
  flex-direction: column;
  gap: 10px;
}

.notice__card {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.notice__lead {
  padding: 16px 14px 14px;
  gap: 12px;
}

.notice__heading {
  display: flex;
  align-items: center;
  gap: 12px;
}

.notice__bell {
  display: flex;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef2f8;
}

.notice__titles {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.notice__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.notice__meta {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.notice__paragraph {
  color: #536178;
  font-size: 14px;
  line-height: 22px;
}

.notice__facts {
  display: grid;
  padding: 10px 0;
  border-radius: 10px;
  background: #f5f7fc;
}

.notice__fact {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.notice__fact-key {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.notice__fact-value {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  white-space: nowrap;
}

.notice__changes {
  padding: 14px 14px 4px;
}

.notice__changes-title {
  margin-bottom: 6px;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.notice__change {
  display: flex;
  width: 100%;
  min-height: 68px;
  flex-direction: row;
  align-items: center;
  gap: 12px;
  border-top: 1px solid #f0f1f3;
  border-radius: 0;
  color: #202c42;
}

.notice__change[disabled] {
  color: #202c42;
  opacity: 1;
}

.notice__change-icon {
  display: flex;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: #fff0e3;
}

.notice__change-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.notice__change-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  white-space: nowrap;
}

.notice__change-text {
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.notice__footnote {
  display: flex;
  padding: 0 4px;
  gap: 6px;
}

.notice__footnote > :first-child {
  margin-top: 2px;
  flex-shrink: 0;
}

.notice__footnote-text {
  min-width: 0;
  color: #627187;
  font-size: 12px;
  line-height: 18px;
}

.notice__bar {
  display: flex;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 10px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.notice__service {
  display: flex;
  width: 56px;
  height: 46px;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #536178;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.notice__primary {
  height: 46px;
  flex: 1;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
  white-space: nowrap;
}

.notice__skeleton {
  height: 160px;
  border-radius: 14px;
  background: #eef2f8;
}

.notice__skeleton--lead {
  height: 200px;
}

.notice__state {
  display: flex;
  padding: 40px 16px;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  border-radius: 14px;
  background: #ffffff;
  text-align: center;
}

.notice__state-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.notice__state-action {
  height: 40px;
  margin-top: 8px;
  padding: 0 24px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
  line-height: 40px;
}
</style>
