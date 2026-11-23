<script setup lang="ts">
import { computed } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import { COURSE_OPTION_NAME, formatCourseAmount, formatValidity } from '../../application/course-format'
import type { CourseDetail } from '../../domain/course'

// The 报名 sheet (board PdSkuCourseFly): the one 规格 a course has (单人账号), what it costs and how
// it opens, and 去报名. A course ships nothing and comes one per account, so there is no quantity,
// no delivery and no cart here (design assumption, docs/redesign/09-课程实现说明.md).
const props = defineProps<{
  detail: CourseDetail
  coverUrl: string | null
  signedIn: boolean
  submitting: boolean
  // A refusal the server gave, already in words.
  message: string | null
}>()

const emit = defineEmits<{
  close: []
  submit: []
}>()

const amount = computed(() => props.detail.price?.amountMinor ?? 0)
const amountText = computed(() => formatCourseAmount(amount.value))
</script>

<template>
  <view class="buy-sheet" data-testid="course-buy-sheet">
    <view class="buy-sheet__mask" aria-label="关闭" @click="emit('close')" />
    <view class="buy-sheet__panel" role="dialog" aria-label="选择报名规格">
      <view class="buy-sheet__head">
        <image v-if="coverUrl" class="buy-sheet__thumb" :src="coverUrl" mode="aspectFill" :alt="COURSE_OPTION_NAME" />
        <view v-else class="buy-sheet__thumb buy-sheet__thumb--empty" />
        <view class="buy-sheet__head-copy">
          <text class="buy-sheet__price" data-testid="course-buy-price">{{ amountText }}</text>
          <text class="buy-sheet__meta">{{ detail.organization.name }} · 线上课 · {{ formatValidity(detail.validityDays) }}</text>
        </view>
        <button class="buy-sheet__close" aria-label="关闭" @click="emit('close')">
          <LineIcon name="close" :size="20" :stroke-width="2" color="#536178" />
        </button>
      </view>

      <view class="buy-sheet__body">
        <view class="buy-sheet__group">
          <text class="buy-sheet__label">规格</text>
          <view class="buy-sheet__option buy-sheet__option--on" role="radio" aria-checked="true">
            <text class="buy-sheet__option-name">{{ COURSE_OPTION_NAME }}</text>
            <text class="buy-sheet__option-price">{{ amountText }}</text>
          </view>
          <text class="buy-sheet__hint">一个账号开通一份，开通后只在本账号可看</text>
        </view>

        <view class="buy-sheet__group">
          <text class="buy-sheet__label">开通方式</text>
          <view class="buy-sheet__option buy-sheet__option--on buy-sheet__option--column">
            <text class="buy-sheet__option-name">线上开通</text>
            <text class="buy-sheet__option-sub">付款后立即开通，不发货</text>
          </view>
        </view>

        <view class="buy-sheet__sum">
          <view class="buy-sheet__sum-row"><text class="buy-sheet__sum-key">课程金额</text><text>{{ amountText }}</text></view>
          <view class="buy-sheet__sum-row"><text class="buy-sheet__sum-key">运费</text><text>¥0</text></view>
        </view>
        <text class="buy-sheet__note">付款后 7 天内、还没开始学习付费课时，可在课程学习页申请全额退款。</text>
        <view v-if="!signedIn" class="buy-sheet__guest" data-testid="course-buy-guest">
          <LineIcon name="person" :size="16" :stroke-width="2" color="#ba470b" />
          <text>未登录 · 点「去报名」先登录，登录后回到这里继续</text>
        </view>
        <text v-if="message" class="buy-sheet__error" data-testid="course-buy-error">{{ message }}</text>
      </view>

      <view class="buy-sheet__foot">
        <button
          class="buy-sheet__submit"
          data-testid="course-buy-submit"
          :disabled="submitting"
          @click="emit('submit')"
        >
          {{ submitting ? '提交中…' : `去报名 ${amountText}` }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
}

button::after {
  border: 0;
}

.buy-sheet {
  position: fixed;
  inset: 0;
  z-index: 900;
}

.buy-sheet__mask {
  /* Its own fixed layer under the panel: as a plain absolute child the swiper's layer above it
     kept the hero undimmed on H5. */
  position: fixed;
  inset: 0;
  z-index: 1;
  background: rgba(0, 0, 0, 0.45);
}

.buy-sheet__panel {
  position: absolute;
  z-index: 2;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  max-height: 85vh;
  flex-direction: column;
  border-radius: 20px 20px 0 0;
  background: #ffffff;
  color: #202c42;
}

.buy-sheet__head {
  display: flex;
  gap: 12px;
  padding: 16px 16px 12px;
}

.buy-sheet__thumb {
  width: 88px;
  height: 88px;
  margin-top: -36px;
  flex-shrink: 0;
  border: 3px solid #ffffff;
  border-radius: 10px;
  background: #eef2f8;
}

.buy-sheet__head-copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 4px;
}

.buy-sheet__price {
  color: #e85400;
  font-size: 22px;
  font-weight: 700;
}

.buy-sheet__meta {
  color: #627187;
  font-size: 12px;
}

.buy-sheet__close {
  display: flex;
  width: 44px;
  height: 44px;
  margin: -8px -8px 0 0;
  align-items: center;
  justify-content: center;
}

.buy-sheet__body {
  display: flex;
  padding: 0 16px;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 18px;
  overflow-y: auto;
}

.buy-sheet__group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.buy-sheet__label {
  font-size: 15px;
  font-weight: 500;
}

.buy-sheet__option {
  display: flex;
  min-height: 44px;
  padding: 0 12px;
  align-items: center;
  gap: 8px;
  border: 1.5px solid #e5ebf3;
  border-radius: 8px;
  box-sizing: border-box;
}

.buy-sheet__option--on {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fffaf5;
}

.buy-sheet__option--column {
  padding: 10px 12px;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.buy-sheet__option-name {
  flex: 1 1 auto;
  font-size: 14px;
  font-weight: 500;
}

.buy-sheet__option-price {
  font-size: 13px;
}

.buy-sheet__option-sub,
.buy-sheet__hint {
  color: #627187;
  font-size: 12px;
}

.buy-sheet__sum {
  display: flex;
  padding: 12px;
  flex-direction: column;
  gap: 6px;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 13px;
}

.buy-sheet__sum-row {
  display: flex;
  justify-content: space-between;
}

.buy-sheet__sum-key {
  color: #627187;
}

.buy-sheet__note {
  color: #627187;
  font-size: 12px;
  line-height: 1.6;
}

.buy-sheet__guest {
  display: flex;
  padding: 10px 12px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  line-height: 18px;
}

.buy-sheet__error {
  color: #d23c2f;
  font-size: 13px;
  line-height: 18px;
}

.buy-sheet__foot {
  display: flex;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
}

.buy-sheet__submit {
  display: flex;
  height: 46px;
  flex: 1 1 auto;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
}

.buy-sheet__submit[disabled] {
  opacity: 0.6;
}
</style>
