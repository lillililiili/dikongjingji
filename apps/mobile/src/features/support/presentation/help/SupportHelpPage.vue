<script setup lang="ts">
import { computed, ref } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import IconTile from '@/shared/ui/IconTile.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { SUPPORT_FEEDBACK_LABELS } from '../../application/chat-view'
import { HELP_CATEGORIES, type HelpCategory } from '../../application/help-faq'
import { SUPPORT_FEEDBACK_LIMIT, SUPPORT_FEEDBACK_TYPES, type SupportFeedbackType } from '../../domain/support'

// 帮助与反馈 (board Help, 390 wide). No screenshots in feedback (no file storage yet) and no
// promise of when or whether it is answered.
const props = defineProps<{
  // The feedback was received in this visit.
  sent: boolean
  submitting: boolean
}>()

const emit = defineEmits<{
  back: []
  openFeedback: []
  submitFeedback: [type: SupportFeedbackType, text: string]
  contactPlatform: []
}>()

// Icons copied from the board Help; the two entry squares are its icon tiles (blue, violet).
const ICONS = {
  pencil: 'M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4',
  complain: 'M12 3l8 3v6c0 4.5-3.2 7.8-8 9c-4.8-1.2-8-4.5-8-9V6zM12 8v5M12 16h.01',
  headset: 'M4 14v-2a8 8 0 1 1 16 0v2M4 14h3v5H5a1 1 0 0 1-1-1zM20 14h-3v5h2a1 1 0 0 0 1-1zM17 19c0 1.5-2 2-5 2',
  check: 'M5 12l5 5 9-10',
  up: 'M6 15l6-6 6 6',
  down: 'M6 9l6 6 6-6',
}

const categoryId = ref<HelpCategory['id']>(HELP_CATEGORIES[0].id)
const openQuestion = ref<string | null>(HELP_CATEGORIES[0].questions[0].id)
const category = computed(() => HELP_CATEGORIES.find((item) => item.id === categoryId.value) ?? HELP_CATEGORIES[0])

const sheetOpen = ref(false)
const feedbackType = ref<SupportFeedbackType | null>(null)
const feedbackText = ref('')
const canSubmit = computed(() => feedbackType.value !== null && feedbackText.value.trim().length > 0 && !props.submitting)

function pickCategory(id: HelpCategory['id']): void {
  categoryId.value = id
  openQuestion.value = HELP_CATEGORIES.find((item) => item.id === id)?.questions[0]?.id ?? null
}

function toggle(id: string): void {
  openQuestion.value = openQuestion.value === id ? null : id
}

function onText(event: Event): void {
  const value = (event as Event & { detail?: { value?: string } }).detail?.value ?? ''
  feedbackText.value = String(value).slice(0, SUPPORT_FEEDBACK_LIMIT)
}

function openSheet(): void {
  emit('openFeedback')
  sheetOpen.value = true
}

function submit(): void {
  const text = feedbackText.value.trim()
  if (!feedbackType.value || !text || props.submitting) return
  emit('submitFeedback', feedbackType.value, text)
}

// Called by the page host once the server has the feedback.
function reset(): void {
  sheetOpen.value = false
  feedbackType.value = null
  feedbackText.value = ''
}

defineExpose({ reset })
</script>

<template>
  <view class="help" data-testid="help-page">
    <AppPageHeader title="帮助与反馈" test-id="help" @back="emit('back')" />

    <view class="help__body">
      <view v-if="sent" class="help__sent" role="status" data-testid="help-feedback-sent">
        <LineIcon :d="ICONS.check" :size="16" color="#159a70" :stroke-width="2.4" />
        <text>已收到你的反馈</text>
      </view>

      <view class="help__entries">
        <button class="help__entry" data-testid="help-feedback" aria-haspopup="dialog" @click="openSheet">
          <IconTile class="help__entry-tile" tone="blue" :d="ICONS.pencil" :size="40" :icon-size="22" />
          <text class="help__entry-title">提交反馈</text>
          <text class="help__entry-copy">功能问题、页面错误或建议</text>
        </button>
        <button class="help__entry" data-testid="help-complain" @click="emit('contactPlatform')">
          <IconTile class="help__entry-tile" tone="violet" :d="ICONS.complain" :size="40" :icon-size="22" />
          <text class="help__entry-title">投诉服务或商家</text>
          <text class="help__entry-copy">平台客服受理，协助沟通</text>
        </button>
      </view>

      <view class="help__faq">
        <text class="help__faq-title">常见问题</text>
        <view class="help__cats" role="group" aria-label="问题分类">
          <button
            v-for="item in HELP_CATEGORIES"
            :key="item.id"
            class="help__cat"
            :data-testid="`help-category-${item.id.toLowerCase()}`"
            :aria-pressed="item.id === categoryId"
            @click="pickCategory(item.id)"
          >
            <text :class="['help__cat-label', { 'help__cat-label--on': item.id === categoryId }]">{{ item.label }}</text>
          </button>
        </view>
        <view
          v-for="question in category.questions"
          :key="question.id"
          class="help__question"
          :data-testid="`help-question-${question.id}`"
        >
          <button class="help__question-head" :aria-expanded="openQuestion === question.id" @click="toggle(question.id)">
            <text :class="['help__question-text', { 'help__question-text--open': openQuestion === question.id }]">{{ question.question }}</text>
            <LineIcon :d="openQuestion === question.id ? ICONS.up : ICONS.down" :size="16" color="#91a0b4" :stroke-width="2" />
          </button>
          <text v-if="openQuestion === question.id" class="help__answer" data-testid="help-answer">{{ question.answer }}</text>
        </view>
      </view>
    </view>

    <view class="help__footer">
      <text class="help__footer-note">没有找到答案？可以直接问平台客服</text>
      <button class="help__contact" data-testid="help-contact-platform" @click="emit('contactPlatform')">
        <LineIcon :d="ICONS.headset" :size="20" color="#ffffff" :stroke-width="2" />
        <text>联系平台客服</text>
      </button>
    </view>

    <template v-if="sheetOpen">
      <view class="help__mask" aria-label="关闭" @click="sheetOpen = false" />
      <view class="help__sheet" role="dialog" aria-label="提交反馈" data-testid="help-feedback-sheet">
        <view class="help__sheet-head">
          <text class="help__sheet-title">提交反馈</text>
          <text class="help__sheet-copy">订单问题建议直接联系客服</text>
        </view>
        <view class="help__field">
          <text class="help__field-label">反馈类型</text>
          <view class="help__types" role="group" aria-label="反馈类型">
            <button
              v-for="type in SUPPORT_FEEDBACK_TYPES"
              :key="type"
              class="help__type"
              :data-testid="`help-feedback-type-${type.toLowerCase()}`"
              :aria-pressed="type === feedbackType"
              @click="feedbackType = type"
            >
              <text :class="['help__type-label', { 'help__type-label--on': type === feedbackType }]">{{ SUPPORT_FEEDBACK_LABELS[type] }}</text>
            </button>
          </view>
        </view>
        <view class="help__textarea-box">
          <textarea
            class="help__textarea"
            data-testid="help-feedback-text"
            aria-label="问题描述"
            placeholder="写下遇到的问题或建议（必填）"
            :value="feedbackText"
            :maxlength="SUPPORT_FEEDBACK_LIMIT"
            @input="onText"
          />
          <text class="help__count" data-testid="help-feedback-count">{{ feedbackText.length }}/{{ SUPPORT_FEEDBACK_LIMIT }}</text>
        </view>
        <view class="help__sheet-actions">
          <button class="help__cancel" data-testid="help-feedback-cancel" @click="sheetOpen = false">取消</button>
          <button
            :class="['help__submit', { 'help__submit--off': !canSubmit }]"
            data-testid="help-feedback-submit"
            :disabled="!canSubmit"
            @click="submit"
          >
            {{ submitting ? '提交中…' : '提交' }}
          </button>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Help (390 wide). */
.help {
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

.help__body {
  display: flex;
  flex: 1;
  padding: 12px 16px;
  flex-direction: column;
  gap: 12px;
}

.help__sent {
  display: flex;
  padding: 10px 12px;
  align-items: center;
  gap: 6px;
  border-radius: 12px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 13px;
  line-height: 18px;
}

.help__entries {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.help__entry {
  display: flex;
  padding: 14px;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  box-sizing: border-box;
  border-radius: 14px;
  color: #202c42;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.help__entry-tile {
  margin-bottom: 6px;
}

.help__entry-icon {
  display: flex;
  width: 40px;
  height: 40px;
  margin-bottom: 6px;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: #fff0e3;
}

.help__entry-icon--blue {
  background: #e6f0ff;
}

.help__entry-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  white-space: nowrap;
}

.help__entry-copy {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.help__faq {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.help__faq-title {
  padding: 14px 14px 2px;
  font-size: 17px;
  font-weight: 700;
  line-height: 22px;
}

.help__cats {
  display: grid;
  padding: 0 14px 2px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
}

.help__cat {
  display: flex;
  height: 44px;
  align-items: center;
}

.help__cat-label {
  display: flex;
  width: 100%;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #536178;
  background: #eef2f8;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.help__cat-label--on {
  color: #ba470b;
  background: #fff0e3;
  font-weight: 500;
}

.help__question {
  display: flex;
  flex-direction: column;
  border-bottom: 1px solid #f0f1f3;
}

.help__question:last-child {
  border-bottom-color: transparent;
}

.help__question-head {
  display: flex;
  width: 100%;
  min-height: 50px;
  padding: 8px 14px;
  align-items: center;
  gap: 10px;
  box-sizing: border-box;
  color: #202c42;
}

.help__question-text {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  line-height: 21px;
}

.help__question-text--open {
  font-weight: 500;
}

.help__answer {
  margin: 0 14px 12px;
  padding: 10px 12px;
  border-radius: 10px;
  color: #536178;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 20px;
}

.help__footer {
  display: flex;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 8px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

.help__footer-note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.help__contact {
  display: flex;
  height: 46px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
  white-space: nowrap;
}

.help__mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(0, 0, 0, 0.45);
}

.help__sheet {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 101;
  display: flex;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 14px;
  border-radius: 20px 20px 0 0;
  background: #ffffff;
}

.help__sheet-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.help__sheet-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.help__sheet-copy {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.help__field {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.help__field-label {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.help__types {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
}

.help__type {
  display: flex;
  height: 44px;
  align-items: center;
}

.help__type-label {
  display: flex;
  width: 100%;
  height: 34px;
  box-sizing: border-box;
  align-items: center;
  justify-content: center;
  border: 1px solid #e5ebf3;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.help__type-label--on {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fff0e3;
  font-weight: 500;
}

.help__textarea-box {
  position: relative;
}

.help__textarea {
  display: block;
  width: 100%;
  height: 96px;
  padding: 10px 12px 26px;
  box-sizing: border-box;
  border: 1px solid #e5ebf3;
  border-radius: 10px;
  color: #202c42;
  background: #f5f7fc;
  font-size: 14px;
  line-height: 20px;
}

.help__count {
  position: absolute;
  right: 10px;
  bottom: 6px;
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
}

.help__sheet-actions {
  display: flex;
  gap: 10px;
}

.help__cancel,
.help__submit {
  height: 46px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 46px;
  text-align: center;
  white-space: nowrap;
}

.help__cancel {
  box-sizing: border-box;
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
  line-height: 44px;
}

.help__submit {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.help__submit--off,
.help__submit[disabled] {
  color: #ffffff;
  background: #c9d3e1;
}
</style>
