<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBackPress, onLoad, onReady } from '@dcloudio/uni-app'

import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import type { AgreementDoc } from '@/features/settings/domain/agreement'
import { buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { undoNeedlessKeyboardPan, watchH5KeyboardViewport } from '@/shared/ui/keyboard-viewport'
import {
  completeBookingAuthenticationRecovery,
  saveBookingAuthSession,
} from '../../../application/service-flow/booking-auth-context'
import { inspectionAuthApi } from '../../../data/service-flow/uni-booking-http-client'
import { isRootTabReturnUrl, normalizeInspectionReturnUrl } from '../../../navigation/inspection-routes'
import { describeLoginResume } from '../login-resume'

interface LoginRouteQuery {
  returnUrl?: string
}

const loginName = ref('')
const password = ref('')
const returnUrl = ref('/pages/home/index')
const isSubmitting = ref(false)
const errorMessage = ref('')
// Board Login: the agreement is not ticked in advance; 登录 stays orange and says what is missing.
const agreementAccepted = ref(false)
const resumeAction = computed(() => describeLoginResume(returnUrl.value))

onLoad((query: LoginRouteQuery | undefined) => {
  returnUrl.value = normalizeInspectionReturnUrl(query?.returnUrl ?? '')
})

// A login name is typed as it is: no capital first letter, no autocorrect, no spell check. uni-app's
// <input> does not pass these attributes on to the real field, so they are set on it once the page is
// drawn: on H5 in this document, on the App in the page's webview (its view layer has the DOM).
const PLAIN_FIELD_SCRIPT = `document.querySelectorAll('[data-plain-input] input').forEach(function(f){`
  + `f.setAttribute('autocapitalize','off');f.setAttribute('autocorrect','off');f.setAttribute('spellcheck','false')})`
let stopKeyboardWatch: () => void = () => {}

// H5: onMounted, not onReady. A page hook registered in this component (the page file only wraps it)
// is never called on H5, so the fields kept their defaults; by the time this component is mounted
// its <input> children are in the document.
onMounted(() => {
  // #ifdef H5
  document.querySelectorAll('[data-plain-input] input').forEach((field) => {
    field.setAttribute('autocapitalize', 'off')
    field.setAttribute('autocorrect', 'off')
    field.setAttribute('spellcheck', 'false')
  })
  // The fields sit high on the page: iOS still pans it when the keyboard opens and the head goes under
  // the status bar. Undo that pan whenever the focused field would be in sight without it.
  stopKeyboardWatch = watchH5KeyboardViewport((viewport) => {
    setTimeout(() => undoNeedlessKeyboardPan(viewport), 50)
  })
  // #endif
})

onReady(() => {
  // #ifdef APP-PLUS
  const pages = getCurrentPages() as Array<{ $getAppWebview?: () => { evalJS(script: string): void } }>
  pages[pages.length - 1]?.$getAppWebview?.().evalJS(PLAIN_FIELD_SCRIPT)
  // #endif
})

onBeforeUnmount(() => stopKeyboardWatch())

onBackPress(() => {
  exitLogin()
  return true
})

async function submitLogin(): Promise<void> {
  if (isSubmitting.value) return
  const normalizedLoginName = loginName.value.trim()
  if (!normalizedLoginName || !password.value) {
    errorMessage.value = '请输入用户名和密码'
    return
  }
  if (!agreementAccepted.value) {
    uni.showToast({ title: '请先阅读并同意用户协议和隐私政策', icon: 'none' })
    return
  }

  isSubmitting.value = true
  errorMessage.value = ''
  try {
    const response = await inspectionAuthApi.login(
      normalizedLoginName,
      password.value,
    )
    saveBookingAuthSession(response)
    completeBookingAuthenticationRecovery()
    continueToProtectedPage()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败，请重试'
  } finally {
    isSubmitting.value = false
  }
}

function continueToProtectedPage(): void {
  const target = normalizeInspectionReturnUrl(returnUrl.value)
  if (isRootTabReturnUrl(target)) {
    uni.reLaunch({ url: target })
    return
  }
  uni.redirectTo({
    url: target,
    fail: () => uni.reLaunch({ url: target }),
  })
}

// The agreement pages are part of 设置与公共页 (M18, preview builds); public builds name them only.
function openAgreement(doc: AgreementDoc): void {
  if (!SUPPORT_PREVIEW_ENABLED) {
    uni.showToast({ title: '协议正文待提供', icon: 'none' })
    return
  }
  uni.navigateTo({ url: buildAgreementUrl(doc) })
}

function exitLogin(): void {
  completeBookingAuthenticationRecovery()
  uni.reLaunch({ url: '/pages/home/index' })
}
</script>

<template>
  <view class="inspection-login">
    <AppPageHeader class="inspection-login__header" title="登录" test-id="login" @back="exitLogin" />

    <main class="inspection-login__content">
      <view v-if="resumeAction" class="inspection-login__resume" data-testid="login-resume">
        <text class="inspection-login__resume-title">登录后继续：{{ resumeAction }}</text>
        <text class="inspection-login__resume-text">登录成功后自动回到原操作</text>
      </view>

      <view class="inspection-login__form">
        <text class="inspection-login__form-title">账号登录</text>
        <view class="inspection-login__input" data-plain-input>
          <LineIcon name="person" :size="18" color="#738096" />
          <input
            v-model="loginName"
            type="text"
            maxlength="80"
            placeholder="请输入用户名"
            placeholder-class="inspection-login__placeholder"
            aria-label="用户名"
            confirm-type="next"
          />
        </view>
        <view class="inspection-login__input">
          <LineIcon name="lock" :size="18" color="#738096" />
          <input
            v-model="password"
            type="text"
            password
            maxlength="256"
            placeholder="请输入密码"
            placeholder-class="inspection-login__placeholder"
            aria-label="密码"
            confirm-type="done"
            @confirm="submitLogin"
          />
        </view>
        <text v-if="errorMessage" class="inspection-login__error">{{ errorMessage }}</text>
        <button
          class="inspection-login__submit"
          :disabled="isSubmitting"
          @click="submitLogin"
        >
          {{ isSubmitting ? '正在登录…' : '登录' }}
        </button>
      </view>

      <view
        class="inspection-login__agreement"
        data-testid="login-agreement"
        role="checkbox"
        :aria-checked="agreementAccepted ? 'true' : 'false'"
        @click="agreementAccepted = !agreementAccepted"
      >
        <view :class="['inspection-login__tick', { 'inspection-login__tick--on': agreementAccepted }]">
          <LineIcon v-if="agreementAccepted" name="check" :size="11" color="#ffffff" :stroke-width="3.2" />
        </view>
        <text class="inspection-login__agreement-text">我已阅读并同意<text class="inspection-login__link" @click.stop="openAgreement('USER_AGREEMENT')">《用户协议》</text><text class="inspection-login__link" @click.stop="openAgreement('PRIVACY_POLICY')">《隐私政策》</text><text class="inspection-login__link" @click.stop="openAgreement('CHILD_PRIVACY_RULES')">《儿童个人信息保护规则》</text></text>
      </view>

      <button class="inspection-login__browse" data-testid="login-browse" @click="exitLogin">先随便看看</button>
    </main>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Login (390 wide). One-tap login with the
   phone number is not built; the existing account login keeps the board's layout. */
.inspection-login { display: flex; min-height: 100vh; flex-direction: column; color: #202c42; background: #f5f7fc; }
.inspection-login__content { display: flex; width: 100%; max-width: 600px; min-height: 0; margin: 0 auto; padding: 8px 16px calc(24px + env(safe-area-inset-bottom)); flex: 1; flex-direction: column; box-sizing: border-box; }
.inspection-login__resume { display: flex; padding: 10px 12px; flex-direction: column; gap: 2px; border: 1px solid #ffd8b8; border-radius: 12px; background: #fff0e3; }
.inspection-login__resume-title { font-size: 13px; font-weight: 500; line-height: 18px; }
.inspection-login__resume-text { color: #627187; font-size: 12px; line-height: 17px; }
.inspection-login__form { display: flex; flex-direction: column; }
.inspection-login__form-title { margin: 28px 0 16px; font-size: 22px; font-weight: 700; line-height: 30px; }
.inspection-login__input { display: flex; height: 50px; padding: 0 14px; align-items: center; gap: 10px; border-radius: 12px; background: #fff; box-sizing: border-box; }
.inspection-login__input + .inspection-login__input { margin-top: 12px; }
.inspection-login__input input { min-width: 0; height: 50px; flex: 1; color: #202c42; font-size: 15px; }
.inspection-login__placeholder { color: #738096; }
.inspection-login__error { display: block; margin-top: 12px; color: #d64f67; font-size: 13px; line-height: 18px; }
.inspection-login__submit { height: 48px; margin: 24px 0 0; border: 0; border-radius: 999px; color: #fff; background: #ff6a00; box-shadow: var(--app-primary-shadow); font-size: 16px; font-weight: 500; line-height: 48px; }
.inspection-login__submit::after, .inspection-login__browse::after { border: 0; }
.inspection-login__submit[disabled] { opacity: 0.7; }
.inspection-login__agreement { display: flex; margin-top: 16px; padding: 6px 0; align-items: flex-start; gap: 8px; color: #627187; font-size: 12px; line-height: 18px; }
.inspection-login__tick { display: flex; width: 18px; height: 18px; flex: none; align-items: center; justify-content: center; border: 1.5px solid #91a0b4; border-radius: 50%; background: #fff; box-sizing: border-box; }
.inspection-login__tick--on { border: 0; background: #ff6a00; }
.inspection-login__agreement-text { min-width: 0; flex: 1; }
.inspection-login__link { color: #ba470b; }
.inspection-login__browse { min-height: 44px; margin: auto auto 0; padding: 0 16px; border: 0; color: #627187; background: transparent; font-size: 14px; line-height: 44px; }
</style>
