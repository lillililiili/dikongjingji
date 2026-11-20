<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import type { CommentSheetState } from '../../application/video-feed-session'
import { sendableComment } from '../../application/video-feed-session'
import { REPORT_REASON_LABELS, formatCommentTime, formatVideoCount, nameInitial } from '../../application/video-view'
import {
  CONTENT_COMMENT_LIMIT,
  CONTENT_COMMENT_REPORT_REASONS,
  type ContentComment,
  type ContentCommentReportReason,
} from '../../domain/content'

// The comment panel of the stream (board VideoFeed): how many, newest first, the author's replies
// marked 作者, 举报 on others' comments, and the input. The board's per-comment likes are not in this
// step; nothing here counts views.
const props = defineProps<{ state: CommentSheetState; count: number }>()
const emit = defineEmits<{
  close: []
  send: [text: string, done: (sent: boolean) => void]
  more: []
  retry: []
  report: [commentId: string, reason: ContentCommentReportReason]
}>()

const draft = ref('')
watch(() => props.state.videoId, () => { draft.value = '' })

const canSend = computed(() => sendableComment(draft.value) !== null && !props.state.sending)
const tooLong = computed(() => Array.from(draft.value.trim()).length > CONTENT_COMMENT_LIMIT)
// The panel shows the server's total once it has one; the stream's count before that.
const total = computed(() => (props.state.status === 'ready' ? props.state.total : props.count))

const COLORS = ['#ff6a00', '#11804a', '#1d63c4', '#6a4fe0']

function avatarColor(comment: ContentComment, index: number): string {
  return comment.byAuthor ? '#11804a' : COLORS[index % COLORS.length]
}

// uni-app H5 throttles input events: send what the confirm event carries when there is one.
function onInput(event: Event): void {
  draft.value = String((event as Event & { detail?: { value?: string } }).detail?.value ?? '')
}

function onConfirm(event: Event): void {
  send(String((event as Event & { detail?: { value?: string } }).detail?.value ?? draft.value))
}

function send(value?: string): void {
  if (typeof value === 'string') draft.value = value
  const text = sendableComment(draft.value)
  if (!text || props.state.sending) return
  emit('send', text, (sent) => {
    if (sent) draft.value = ''
  })
}

function report(comment: ContentComment): void {
  uni.showActionSheet({
    itemList: CONTENT_COMMENT_REPORT_REASONS.map((reason) => REPORT_REASON_LABELS[reason]),
    success: (result) => {
      const reason = CONTENT_COMMENT_REPORT_REASONS[result.tapIndex]
      if (reason) emit('report', comment.commentId, reason)
    },
  })
}
</script>

<template>
  <view class="comments">
    <button class="comments__scrim" aria-label="关闭评论" data-testid="video-comments-scrim" @click="emit('close')" />
    <view class="comments__panel" role="dialog" aria-label="评论" data-testid="video-comments" :data-state="state.status">
      <view class="comments__head">
        <text class="comments__count" data-testid="video-comments-count">{{ formatVideoCount(total) }} 条评论</text>
        <button class="comments__close" aria-label="关闭" data-testid="video-comments-close" @click="emit('close')">×</button>
      </view>

      <scroll-view class="comments__list" scroll-y @scrolltolower="emit('more')">
        <view v-if="state.status === 'loading'" class="comments__state">正在加载…</view>
        <view v-else-if="state.status === 'error'" class="comments__state">
          <text>评论加载失败</text>
          <button class="comments__retry" data-testid="video-comments-retry" @click="emit('retry')">重新加载</button>
        </view>
        <view v-else-if="!state.items.length" class="comments__state" data-testid="video-comments-empty">还没有评论，来说第一句</view>
        <template v-else>
          <view
            v-for="(comment, index) in state.items"
            :key="comment.commentId"
            class="comments__item"
            :data-testid="`video-comment-${comment.commentId}`"
          >
            <text class="comments__avatar" :style="{ background: avatarColor(comment, index) }" aria-hidden="true">
              {{ comment.mine ? '我' : nameInitial(comment.authorName) }}
            </text>
            <view class="comments__body">
              <view class="comments__name-line">
                <text class="comments__name">{{ comment.mine ? '我' : comment.authorName }}</text>
                <text v-if="comment.byAuthor" class="comments__author" data-testid="video-comment-author">作者</text>
              </view>
              <text class="comments__text">{{ comment.text }}</text>
              <text class="comments__when">{{ formatCommentTime(comment.createdAt) }}</text>
            </view>
            <button
              v-if="!comment.mine"
              class="comments__report"
              :data-testid="`video-comment-report-${comment.commentId}`"
              aria-label="举报这条评论"
              @click="report(comment)"
            >
              举报
            </button>
          </view>
          <view v-if="state.loadingMore" class="comments__state">正在加载…</view>
          <button v-else-if="state.hasMore" class="comments__more" data-testid="video-comments-more" @click="emit('more')">查看更多评论</button>
        </template>
      </scroll-view>

      <text v-if="state.sendError" class="comments__error" role="alert" data-testid="video-comment-error">{{ state.sendError }}</text>
      <text v-else-if="tooLong" class="comments__error" role="alert">评论最多 {{ CONTENT_COMMENT_LIMIT }} 字</text>
      <view class="comments__input-row">
        <input
          class="comments__input"
          data-testid="video-comment-input"
          :value="draft"
          placeholder="说点什么…"
          aria-label="写评论"
          confirm-type="send"
          :maxlength="CONTENT_COMMENT_LIMIT + 20"
          @input="onInput"
          @confirm="onConfirm"
        >
        <button
          class="comments__send"
          data-testid="video-comment-send"
          :disabled="!canSend"
          :style="{ background: canSend ? '#ff6a00' : '#c9d3e1' }"
          @click="send()"
        >
          <LineIcon v-if="state.sending" name="spinner" :size="16" color="#ffffff" />
          <text v-else>发送</text>
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the board VideoFeed's comment panel (390 wide). */
button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: inherit;
  font-family: inherit;
  line-height: normal;
  text-align: left;
}

button::after {
  border: 0;
}

.comments__scrim {
  position: fixed;
  z-index: 30;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
}

.comments__panel {
  position: fixed;
  z-index: 31;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  height: 520px;
  max-height: 80vh;
  flex-direction: column;
  border-radius: 16px 16px 0 0;
  color: #202c42;
  background: #ffffff;
}

.comments__head {
  display: flex;
  height: 48px;
  flex-shrink: 0;
  align-items: center;
  padding: 0 4px 0 16px;
  border-bottom: 1px solid #f0f1f3;
}

.comments__count {
  font-size: 15px;
  font-weight: 700;
}

.comments__close {
  width: 44px;
  height: 44px;
  margin-left: auto;
  color: #536178;
  font-size: 22px;
  text-align: center;
}

.comments__list {
  flex: 1;
  min-height: 0;
  box-sizing: border-box;
}

.comments__item {
  display: flex;
  gap: 10px;
  padding: 7px 16px;
}

.comments__item:first-child {
  padding-top: 14px;
}

.comments__avatar {
  display: flex;
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #ffffff;
  font-size: 14px;
  font-weight: 700;
  line-height: 34px;
  text-align: center;
}

.comments__body {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.comments__name-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.comments__name {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.comments__author {
  padding: 0 4px;
  border-radius: 4px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 11px;
}

.comments__text {
  font-size: 14px;
  line-height: 20px;
  word-break: break-all;
}

.comments__when {
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
}

.comments__report {
  flex-shrink: 0;
  min-height: 32px;
  padding: 0 4px;
  color: #91a0b4;
  font-size: 12px;
}

.comments__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 16px;
  color: #738096;
  font-size: 13px;
  text-align: center;
}

.comments__retry,
.comments__more {
  min-height: 36px;
  padding: 0 16px;
  color: #ba470b;
  font-size: 13px;
  text-align: center;
}

.comments__more {
  display: block;
  width: 100%;
}

.comments__error {
  padding: 6px 16px 0;
  color: #d64f67;
  font-size: 12px;
  line-height: 17px;
}

.comments__send[disabled] {
  color: #ffffff;
}

.comments__input-row {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 8px;
  padding: 8px 16px calc(8px + env(safe-area-inset-bottom));
  border-top: 1px solid #f0f1f3;
}

.comments__input {
  flex: 1;
  min-width: 0;
  height: 40px;
  padding: 0 14px;
  border-radius: 999px;
  box-sizing: border-box;
  color: #202c42;
  background: #eef2f8;
  font-size: 14px;
}

.comments__send {
  display: flex;
  flex-shrink: 0;
  height: 40px;
  align-items: center;
  justify-content: center;
  padding: 0 16px;
  border-radius: 999px;
  color: #ffffff;
  font-size: 14px;
  font-weight: 500;
}
</style>
